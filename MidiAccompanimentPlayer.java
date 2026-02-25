
package com.karaoke;

import javax.sound.midi.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Toca as notas MIDI das vozes concorrentes (acompanhamento) em sincronia
 * com o gameLoop do KaraokeGameMode, com suporte a 3 modos de reprodução.
 *
 * ── Design ────────────────────────────────────────────────────────────────
 *  • Cada voz recebe um canal MIDI próprio (base = 1, pula canal 9 = drums).
 *  • tick(currentTime) é chamado a cada iteração do gameLoop; decide
 *    internamente quais noteOn/noteOff precisam ser disparados.
 *  • As notas já chegam com timeScale aplicado (responsabilidade do chamador).
 *  • Velocidade mais baixa que a melodia (80 vs 200) para não sobrepor.
 *  • NOVO: Suporte a 3 modos de reprodução via PlaybackMode
 */
public class MidiAccompanimentPlayer {

    // ── Enum para Modos de Reprodução ────────────────────────────────────────
    /**
     * Define como as vozes de acompanhamento devem ser tocadas.
     */
    public enum PlaybackMode {
        /** Toca todas as vozes, EXCETO a voz escolhida pelo usuário (padrão original) */
        ALL_EXCEPT_SELECTED,
        /** Toca APENAS a voz escolhida pelo usuário */
        ONLY_SELECTED,
        /** Toca todas as vozes, INCLUINDO a voz escolhida pelo usuário */
        ALL_VOICES
    }

    // ── Configuração MIDI ─────────────────────────────────────────────────────
    private static final int BASE_CHANNEL   = 1;   // canal 0 = reservado para LearningMode
    private static final int DRUMS_CHANNEL  = 9;   // sempre pulado
    private static final double MIDI_LATENCY_S = 0.30;  // ← mesmo valor do LearningMode
    private final int velocity;  // mais suave que a melodia (200)
    private static final int MIDI_PROGRAM   = 52;  // Choir Aahs — igual ao LearningMode
    private static final int MAX_CHANNELS   = 16;

    // ── Estado por voz ────────────────────────────────────────────────────────
    /** Qual MusicNote está com noteOn ativo em cada voz (-1 = nenhuma). */
    private final int[]      activeNotes;   // midi number, -1 = silent
    /** Objeto MusicNote que gerou o noteOn atual (para detectar troca de nota). */
    private final MusicNote[] activeObjects;

    // ── Dependências ──────────────────────────────────────────────────────────
    private final List<List<MusicNote>> voices;   // notas já com timeScale
    private final int[]                 channels; // canal MIDI atribuído a cada voz
    private PlaybackMode                playbackMode;    // modo de reprodução atual
    private int                         selectedVoiceIdx; // índice da voz escolhida (-1 = nenhuma)

    // ── MIDI ──────────────────────────────────────────────────────────────────
    private Synthesizer  synth;
    private MidiChannel[] midiChannels;  // referência direta aos canais do synth

    // ── Flag de disponibilidade ───────────────────────────────────────────────
    private boolean available = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Construtor
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param accompanimentVoices listas de notas de cada voz concorrente,
     *                            já com timeScale aplicado e ordenadas por startTime.
     * @param velocityFactor      fator de velocidade (0.0 a 1.0)
     * @param playbackMode        modo de reprodução das vozes
     * @param selectedVoiceIdx    índice da voz escolhida pelo usuário (ignorado em ALL_VOICES)
     */
    public MidiAccompanimentPlayer(List<List<MusicNote>> accompanimentVoices,
                                   float velocityFactor,
                                   PlaybackMode playbackMode,
                                   int selectedVoiceIdx) {
        this.voices            = accompanimentVoices;
        this.velocity          = Math.round(100 * Math.max(0f, Math.min(1f, velocityFactor)));
        this.playbackMode      = playbackMode != null ? playbackMode : PlaybackMode.ALL_EXCEPT_SELECTED;
        this.selectedVoiceIdx  = selectedVoiceIdx;

        int voiceCount     = accompanimentVoices.size();
        this.activeNotes   = new int[voiceCount];
        this.activeObjects = new MusicNote[voiceCount];
        this.channels      = new int[voiceCount];

        int ch = BASE_CHANNEL;
        for (int i = 0; i < voiceCount; i++) {
            if (ch == DRUMS_CHANNEL) ch++;
            channels[i] = ch < MAX_CHANNELS ? ch++ : -1;
        }

        for (int i = 0; i < voiceCount; i++) {
            activeNotes[i]   = -1;
            activeObjects[i] = null;
        }

        System.out.println("[MidiAcc] Modo: " + this.playbackMode +
                ", Voz selecionada: " + this.selectedVoiceIdx +
                ", Velocidade: " + this.velocity);
    }

    /**
     * Construtor alternativo com modo padrão (ALL_EXCEPT_SELECTED)
     */
    public MidiAccompanimentPlayer(List<List<MusicNote>> accompanimentVoices,
                                   float velocityFactor) {
        this(accompanimentVoices, velocityFactor, PlaybackMode.ALL_EXCEPT_SELECTED, -1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Getters e Setters para modo e voz selecionada
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Muda o modo de reprodução em tempo de execução
     */
    public void setPlaybackMode(PlaybackMode mode) {
        if (mode != null) {
            this.playbackMode = mode;
            System.out.println("[MidiAcc] Modo alterado para: " + mode);
        }
    }

    /**
     * Define o índice da voz escolhida pelo usuário
     */
    public void setSelectedVoiceIndex(int voiceIdx) {
        if (voiceIdx >= -1 && voiceIdx < voices.size()) {
            this.selectedVoiceIdx = voiceIdx;
            System.out.println("[MidiAcc] Voz selecionada: " + voiceIdx);
        } else {
            System.err.println("[MidiAcc] Índice de voz inválido: " + voiceIdx);
        }
    }

    public PlaybackMode getPlaybackMode() {
        return playbackMode;
    }

    public int getSelectedVoiceIndex() {
        return selectedVoiceIdx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    /** Abre o sintetizador e configura os instrumentos. Chamar antes de tick(). */
    public void start() {
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            midiChannels = synth.getChannels();

            for (int i = 0; i < voices.size(); i++) {
                int ch = channels[i];
                if (ch >= 0 && ch < midiChannels.length) {
                    midiChannels[ch].programChange(MIDI_PROGRAM);
                }
            }
            available = true;
            System.out.printf("[MidiAcc] Iniciado — %d voz(es), canais: %s, modo: %s%n",
                    voices.size(), channelSummary(), playbackMode);
        } catch (MidiUnavailableException e) {
            available = false;
            System.err.println("[MidiAcc] MIDI indisponível: " + e.getMessage());
        }
    }

    /**
     * Para todo o MIDI e fecha o sintetizador.
     * Seguro para chamar múltiplas vezes.
     */
    public void stop() {
        if (!available) return;
        available = false;

        for (int i = 0; i < voices.size(); i++) {
            if (activeNotes[i] != -1) {
                noteOff(i, activeNotes[i]);
                activeNotes[i]   = -1;
                activeObjects[i] = null;
            }
        }

        if (synth != null && synth.isOpen()) {
            for (int i = 0; i < voices.size(); i++) {
                int ch = channels[i];
                if (ch >= 0 && ch < midiChannels.length) {
                    midiChannels[ch].controlChange(64, 0);   // sustain pedal off
                    midiChannels[ch].allNotesOff();           // respeita release
                    midiChannels[ch].controlChange(120, 0);  // All Sound Off — corte imediato
                }
            }
            synth.close();
            synth = null;
        }
        midiChannels = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  tick — chamado a cada iteração do gameLoop
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atualiza o estado MIDI para o instante {@code currentTime}.
     * Para cada voz, encontra a nota que deve estar soando, respeitando
     * o PlaybackMode configurado.
     *
     * @param currentTime tempo atual em segundos (já com timeScale aplicado)
     */
    public void tick(double currentTime) {
        if (!available) return;

        for (int voiceIdx = 0; voiceIdx < voices.size(); voiceIdx++) {
            // Verifica se esta voz deve ser tocada baseado no modo
            if (!shouldPlayVoice(voiceIdx)) {
                // Para a nota se estiver soando
                if (activeNotes[voiceIdx] != -1) {
                    noteOff(voiceIdx, activeNotes[voiceIdx]);
                    activeNotes[voiceIdx]   = -1;
                    activeObjects[voiceIdx] = null;
                }
                continue;
            }

            int ch = channels[voiceIdx];
            if (ch < 0) continue;  // sem canal disponível para esta voz

            MusicNote desired = findActiveNote(voices.get(voiceIdx), currentTime + MIDI_LATENCY_S);
            MusicNote current = activeObjects[voiceIdx];

            // Mesma nota ainda soando → nada a fazer
            if (desired == current) continue;

            // Para a nota anterior
            if (current != null && activeNotes[voiceIdx] != -1) {
                noteOff(voiceIdx, activeNotes[voiceIdx]);
            }

            // Inicia a nova nota
            if (desired != null) {
                int midiNote = clampMidi(desired.midi);
                noteOn(voiceIdx, midiNote);
                activeNotes[voiceIdx]   = midiNote;
                activeObjects[voiceIdx] = desired;
            } else {
                activeNotes[voiceIdx]   = -1;
                activeObjects[voiceIdx] = null;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers internos
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Determina se uma voz deve ser tocada baseado no PlaybackMode configurado
     */
    private boolean shouldPlayVoice(int voiceIdx) {
        switch (playbackMode) {
            case ALL_EXCEPT_SELECTED:
                // Toca todas EXCETO a voz selecionada
                return voiceIdx != selectedVoiceIdx;

            case ONLY_SELECTED:
                // Toca APENAS a voz selecionada
                return voiceIdx == selectedVoiceIdx;

            case ALL_VOICES:
                // Toca todas as vozes
                return true;

            default:
                return false;
        }
    }

    /**
     * Retorna a MusicNote que deve estar soando em {@code time},
     * ou {@code null} se estiver em pausa.
     * Usa busca linear — listas costumam ter centenas de notas, OK para 20 ms.
     */
    private MusicNote findActiveNote(List<MusicNote> notes, double time) {
        for (MusicNote n : notes) {
            if (time >= n.startTime && time <= n.endTime) return n;
            // Notas estão ordenadas; se começam após o tempo atual, pode parar
            if (n.startTime > time + 0.01) break;
        }
        return null;
    }

    private void noteOn(int voiceIdx, int midiNote) {
        try {
            midiChannels[channels[voiceIdx]].noteOn(midiNote, velocity);
        } catch (Exception e) {
            System.err.println("[MidiAcc] noteOn erro: " + e.getMessage());
        }
    }

    private void noteOff(int voiceIdx, int midiNote) {
        try {
            midiChannels[channels[voiceIdx]].noteOff(midiNote);
        } catch (Exception e) {
            System.err.println("[MidiAcc] noteOff erro: " + e.getMessage());
        }
    }

    private static int clampMidi(int midi) {
        return Math.max(0, Math.min(127, midi));
    }

    private String channelSummary() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < voices.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(channels[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** Retorna true se o sintetizador foi aberto com sucesso. */
    public boolean isAvailable() { return available; }
}