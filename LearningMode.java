
package com.karaoke;

import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Modo Aprendizado.
 *
 * ── Integração com KaraokeApp ─────────────────────────────────────────────
 *  Não abre mais uma JDialog separada. O KaraokeApp chama buildPanels() para
 *  obter os painéis (lista de trechos e painel de controle) que são injetados
 *  diretamente no layout da janela principal.
 *  Ao sair do modo treino, KaraokeApp restaura o layout original.
 *
 * ── Seleção múltipla ──────────────────────────────────────────────────────
 *  Shift+clique na lista seleciona um intervalo contíguo de trechos.
 *  Os trechos selecionados são mesclados em um "mega-segmento virtual" e
 *  tratados como um único segmento para Ouvir/Cantar.
 *
 * ── Gravação ──────────────────────────────────────────────────────────────
 *  Um switch ON/OFF aparece na barra de botões.
 *  Quando ativado, a fase CANTAR grava o microfone via VoiceRecorder.
 *
 * ── Segmentação ───────────────────────────────────────────────────────────
 *  Qualquer pausa > GAP_THRESHOLD_S entre notas consecutivas inicia um novo
 *  segmento.
 *
 * ── Offset de sincronização ───────────────────────────────────────────────
 *  noteTime = mp3Time - offset
 */
public class LearningMode {

    // ── Constantes ────────────────────────────────────────────────────────────
    private static final double GAP_THRESHOLD_S      = 0.05;
    private static final double LEAD_IN_S            = 2.0;
    private static final double MIDI_LATENCY_S       = 0.30;
    private static final long   LOOP_INTERVAL_MS     = 20;
    private static final double PASS_PRECISION       = 80.0;
    private static final int    MIDI_CHANNEL         = 0;
    private static final int    MIDI_VELOCITY        = 200;
    private static final int    MIDI_PROGRAM         = 52;
    private static final int    OCTAVE_SEMITONES     = 12;
    private static final int[]  OCTAVE_OPTIONS       = {-1, 0, 1};
    private static final double SHORT_NOTE_THRESHOLD = 1.0;
    private static final long   MP3_STOP_SETTLE_MS   = 200;
    private static final long   MP3_PLAY_SETTLE_MS   = 150;

    // ── Cores ─────────────────────────────────────────────────────────────────
    private static final Color COLOR_PERFECT = new Color(255, 215, 0);
    private static final Color COLOR_GREAT   = new Color(52, 152, 219);
    private static final Color COLOR_GOOD    = new Color(39, 174, 96);
    private static final Color COLOR_OK      = new Color(116, 115, 115);
    private static final Color COLOR_MISSED  = new Color(231, 76, 60);

    // ── Segmento ──────────────────────────────────────────────────────────────
    public static class Segment {
        public final int             index;
        public final List<MusicNote> notes;
        public final double          startTime;
        public final double          endTime;
        public final double          playFrom;
        public final String          label;

        public Segment(int index, List<MusicNote> notes) {
            this.index     = index;
            this.notes     = Collections.unmodifiableList(new ArrayList<>(notes));
            this.startTime = notes.get(0).startTime;
            this.endTime   = notes.get(notes.size() - 1).endTime;
            this.playFrom  = Math.max(0.0, startTime - LEAD_IN_S);

            List<String> words = new ArrayList<>();
            for (MusicNote n : notes) {
                if (words.size() >= 5) break;
                if (n.lyric == null || n.lyric.isBlank()) continue;
                for (String w : n.lyric.trim().split("\\s+")) {
                    if (words.size() >= 5) break;
                    String clean = w.replaceAll("[,;.!?]+$", "").trim();
                    if (!clean.isEmpty()) words.add(clean);
                }
            }
            int    m      = (int) (startTime / 60);
            double s      = startTime % 60;
            String phrase = String.join(" ", words);
            this.label = !phrase.isEmpty()
                    ? String.format("%d. \"%s%s\"", index + 1, phrase,
                    words.size() == 5 ? "…" : "")
                    : String.format("%d. %d:%05.2f", index + 1, m, s);
        }

        public double notesDuration() {
            double d = 0;
            for (MusicNote n : notes) d += n.duration;
            return d;
        }
    }

    // ── Mega-segmento (mescla de vários Segments contíguos) ───────────────────
    private static class MergedSegment {
        final List<Segment>  sources;
        final List<MusicNote> notes;
        final double startTime;
        final double endTime;
        final double playFrom;
        final String label;

        MergedSegment(List<Segment> sources) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));

            List<MusicNote> merged = new ArrayList<>();
            for (Segment s : sources) merged.addAll(s.notes);
            this.notes     = Collections.unmodifiableList(merged);
            this.startTime = sources.get(0).startTime;
            this.endTime   = sources.get(sources.size() - 1).endTime;
            this.playFrom  = sources.get(0).playFrom;

            int first = sources.get(0).index + 1;
            int last  = sources.get(sources.size() - 1).index + 1;
            this.label = sources.size() == 1
                    ? sources.get(0).label
                    : String.format("Trechos %d – %d", first, last);
        }

        double notesDuration() {
            double d = 0;
            for (MusicNote n : notes) d += n.duration;
            return d;
        }
    }

    // ── Fase ──────────────────────────────────────────────────────────────────
    private enum Phase { IDLE, LISTENING, SINGING, RESULT }

    // ── Dependências ──────────────────────────────────────────────────────────
    private final List<MusicNote> allNotes;
    private final AudioDetector   audioDetector;
    private final NoteScrollPanel canvas;
    private final double          timeScale;
    private final AudioPlayer     AudioPlayer;
    private final double          syncOffset;
    private final VoiceRecorder   voiceRecorder;
    private final SharedMicrophone sharedMic;

    private String voiceName = "Voz";
    private String musicName = "";

    /** Callback chamado quando o usuário clica em "Sair do Treino". */
    private Runnable onExitCallback;

    // ── MIDI ──────────────────────────────────────────────────────────────────
    private Synthesizer midiSynth;
    private MidiChannel midiChannel;

    // ── Estado ────────────────────────────────────────────────────────────────
    private List<Segment>      segments        = new ArrayList<>();
    private int                currentSegIdx   = 0;
    private MergedSegment      activeMerged    = null;
    private volatile boolean   running         = false;
    private volatile Phase     phase           = Phase.IDLE;
    private volatile int       loopToken       = 0;

    private double   segScore   = 0.0;
    private double   segElapsed = 0.0;
    private volatile int octaveShift = 0;

    private final Map<MusicNote, List<Double>> pitchSamples = new IdentityHashMap<>();

    // ── UI – painéis públicos injetados no KaraokeApp ─────────────────────────
    /** Lista de trechos – vai para o lado EAST (onde ficava a biblioteca). */
    private JPanel segmentPanel;
    /** Controles do modo treino – vai para o painel SOUTH (bottom). */
    private JPanel learningControlPanel;
    /** Painel de precisão – vai para o espaço do placar. */
    private JPanel precisionPanel;

    private JList<String>            segmentList;
    private DefaultListModel<String> listModel;
    private JLabel                   phaseLabel, precisionLabel, segInfoLabel;
    private JProgressBar             progressBar;
    private JButton                  listenBtn, singBtn;
    private JLabel                   resultLabel;
    private JPanel                   resultPanel;
    private JComboBox<String>        octaveCombo;
    private JLabel                   selectionHintLabel;

    private JToggleButton recordSwitch;
    private JLabel        recIndicatorLabel;
    private javax.swing.Timer recBlinkTimer;

    // ── Construtores ──────────────────────────────────────────────────────────
    public LearningMode(List<MusicNote> notes, AudioDetector audioDetector,
                        NoteScrollPanel canvas, double timeScale,
                        AudioPlayer AudioPlayer, double syncOffset) {
        this(notes, audioDetector, canvas, timeScale, AudioPlayer, syncOffset, null, null);
    }

    public LearningMode(List<MusicNote> notes, AudioDetector audioDetector,
                        NoteScrollPanel canvas, double timeScale,
                        AudioPlayer AudioPlayer, double syncOffset,
                        VoiceRecorder voiceRecorder, SharedMicrophone sharedMic) {
        this.allNotes      = notes;
        this.audioDetector = audioDetector;
        this.canvas        = canvas;
        this.timeScale     = timeScale;
        this.AudioPlayer   = AudioPlayer;
        this.syncOffset    = syncOffset;
        this.voiceRecorder = voiceRecorder;
        this.sharedMic     = sharedMic;
    }

    public void setVoiceName(String name) {
        this.voiceName = (name != null && !name.isBlank()) ? name.trim() : "Voz";
    }

    public void setMusicName(String name) {
        this.musicName = (name != null && !name.isBlank()) ? name.trim() : "";
    }

    /**
     * Define o callback chamado quando o usuário clica em "Sair do Treino".
     * O KaraokeApp usa isso para restaurar o layout original.
     */
    public void setOnExitCallback(Runnable callback) {
        this.onExitCallback = callback;
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────
    /**
     * Inicializa MIDI, detecta segmentos e constrói os painéis.
     * Os painéis ficam disponíveis via getSegmentPanel(),
     * getLearningControlPanel() e getPrecisionPanel().
     * Retorna false se não houver segmentos (nada foi carregado ainda).
     */
    public boolean start() {
        initMidi();
        applyTimeScale();
        detectSegments();

        if (segments.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "Nenhum segmento detectado no XML.",
                    "Modo Aprendizado", JOptionPane.WARNING_MESSAGE);
            return false;
        }

        running = true;
        audioDetector.startListening();
        buildPanels();
        return true;
    }

    public void stop() {
        running = false;
        loopToken++;
        phase = Phase.IDLE;
        audioDetector.stopListening();
        if (AudioPlayer != null) AudioPlayer.stop();
        stopAllMidi();
        closeMidi();
        stopRecordingIfActive();
    }

    // ── Painéis públicos ──────────────────────────────────────────────────────
    /** Painel lateral com a lista de trechos (substitui a biblioteca). */
    public JPanel getSegmentPanel()        { return segmentPanel; }
    /** Painel inferior com os controles de treino (substitui os botões de modo). */
    public JPanel getLearningControlPanel() { return learningControlPanel; }
    /** Painel de precisão/progresso (substitui o placar). */
    public JPanel getPrecisionPanel()      { return precisionPanel; }

    // ── Gravação ──────────────────────────────────────────────────────────────
    private void startRecordingForMerged(MergedSegment merged) {
        if (voiceRecorder == null || sharedMic == null) return;
        if (!recordSwitch.isSelected()) return;

        String fileName = buildRecordingName(merged);
        voiceRecorder.setOutputName(fileName);
        sharedMic.addConsumer(voiceRecorder);
        voiceRecorder.start();
        startBlinking();
        System.out.println("[LearningMode] Gravando: " + fileName);
    }

    private String buildRecordingName(Segment seg) {
        String numPart    = String.valueOf(seg.index + 1);
        String trechoPart = stripNumericPrefix(seg.label);
        String core       = voiceName + " - " + numPart + " - " + trechoPart;
        return musicName.isBlank() ? core : musicName + " - " + core;
    }

    private String buildRecordingName(MergedSegment merged) {
        if (merged.sources.size() == 1) return buildRecordingName(merged.sources.get(0));

        Segment first = merged.sources.get(0);
        Segment last  = merged.sources.get(merged.sources.size() - 1);
        String numPart    = (first.index + 1) + "-" + (last.index + 1);
        String trechoPart = stripNumericPrefix(first.label) + " - " + stripNumericPrefix(last.label);
        String core = voiceName + " - " + numPart + " - " + trechoPart;
        return musicName.isBlank() ? core : musicName + " - " + core;
    }

    private String stripNumericPrefix(String label) {
        return label.replaceAll("^\\d+\\.\\s*", "").trim();
    }

    private void stopRecordingIfActive() {
        if (voiceRecorder == null) return;
        if (voiceRecorder.isRecording()) {
            voiceRecorder.stop();
            if (sharedMic != null) sharedMic.removeConsumer(voiceRecorder);
        }
        stopBlinking();
    }

    private void startBlinking() {
        if (recIndicatorLabel == null) return;
        recIndicatorLabel.setVisible(true);
        final boolean[] v = {true};
        recBlinkTimer = new javax.swing.Timer(600, e -> {
            recIndicatorLabel.setVisible(v[0]);
            v[0] = !v[0];
        });
        recBlinkTimer.start();
    }

    private void stopBlinking() {
        if (recBlinkTimer != null) { recBlinkTimer.stop(); recBlinkTimer = null; }
        if (recIndicatorLabel != null)
            SwingUtilities.invokeLater(() -> recIndicatorLabel.setVisible(false));
    }

    // ── MIDI ──────────────────────────────────────────────────────────────────
    private void initMidi() {
        try {
            midiSynth   = MidiSystem.getSynthesizer();
            midiSynth.open();
            midiChannel = midiSynth.getChannels()[MIDI_CHANNEL];
            midiChannel.programChange(MIDI_PROGRAM);
        } catch (MidiUnavailableException e) {
            System.err.println("[LearningMode] MIDI indisponível: " + e.getMessage());
            midiSynth   = null;
            midiChannel = null;
        }
    }

    private void closeMidi()         { if (midiSynth != null && midiSynth.isOpen()) midiSynth.close(); midiSynth = null; midiChannel = null; }
    private void midiNoteOn(int n)   { if (midiChannel != null) midiChannel.noteOn(n, MIDI_VELOCITY); }
    private void midiNoteOff(int n)  { if (midiChannel != null) midiChannel.noteOff(n); }
    private void stopAllMidi()       { if (midiChannel != null) midiChannel.allNotesOff(); }

    // ── Pré-processamento ─────────────────────────────────────────────────────
    private void applyTimeScale() {
        for (MusicNote note : allNotes) {
            note.startTime *= timeScale;
            note.duration  *= timeScale;
            note.endTime    = note.startTime + note.duration;
        }
    }

    private void detectSegments() {
        segments.clear();
        if (allNotes.isEmpty()) return;

        List<MusicNote> sorted = new ArrayList<>(allNotes);
        sorted.sort(Comparator.comparingDouble(n -> n.startTime));

        List<MusicNote> current = new ArrayList<>();
        current.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            MusicNote prev = sorted.get(i - 1);
            MusicNote next = sorted.get(i);
            if (next.startTime - prev.endTime > GAP_THRESHOLD_S) {
                segments.add(new Segment(segments.size(), current));
                current = new ArrayList<>();
            }
            current.add(next);
        }
        if (!current.isEmpty())
            segments.add(new Segment(segments.size(), current));

        System.out.printf("[LearningMode] %d segmento(s) | offset=%.2fs%n",
                segments.size(), syncOffset);
    }

    // ── MergedSegment a partir da seleção atual ───────────────────────────────
    private MergedSegment buildMergedFromSelection() {
        int[] selected = segmentList.getSelectedIndices();
        if (selected == null || selected.length == 0)
            selected = new int[]{currentSegIdx};

        List<Segment> sources = new ArrayList<>();
        for (int idx : selected) {
            if (idx >= 0 && idx < segments.size())
                sources.add(segments.get(idx));
        }
        if (sources.isEmpty()) sources.add(segments.get(currentSegIdx));

        return new MergedSegment(sources);
    }

    // ── Notas para o canvas ───────────────────────────────────────────────────
    private List<MusicNote> displayNotes(MergedSegment merged) {
        if (octaveShift == 0) return merged.notes;
        List<MusicNote> shifted = new ArrayList<>(merged.notes.size());
        for (MusicNote n : merged.notes) {
            MusicNote copy = n.shallowCopy();
            copy.midi = Math.max(0, Math.min(127, n.midi + octaveShift * OCTAVE_SEMITONES));
            shifted.add(copy);
        }
        return shifted;
    }

    // ── Construção dos painéis (sem JDialog) ─────────────────────────────────
    private void buildPanels() {
        buildSegmentPanel();
        buildPrecisionPanel();
        buildLearningControlPanel();

        setPhaseUI(Phase.IDLE);
        loadFromSelection();
    }

    /** Painel lateral: lista de trechos (substitui LibraryPanel). */
    private void buildSegmentPanel() {
        segmentPanel = new JPanel(new BorderLayout(4, 4));
        segmentPanel.setBackground(new Color(15, 25, 50));
        segmentPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        segmentPanel.setPreferredSize(new Dimension(230, 0));

        JLabel listTitle = new JLabel("🎵 Trechos (" + segments.size() + ")");
        listTitle.setForeground(new Color(255, 215, 0));
        listTitle.setFont(new Font("Segoe UI Symbol", Font.BOLD, 12));

        selectionHintLabel = new JLabel("Shift+clique: selecionar intervalo");
        selectionHintLabel.setForeground(new Color(130, 160, 200));
        selectionHintLabel.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 10));

        JPanel listHeader = new JPanel(new BorderLayout(2, 2));
        listHeader.setBackground(new Color(15, 25, 50));
        listHeader.add(listTitle,          BorderLayout.NORTH);
        listHeader.add(selectionHintLabel, BorderLayout.SOUTH);
        segmentPanel.add(listHeader, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        for (Segment seg : segments) listModel.addElement(seg.label);

        segmentList = new JList<>(listModel);
        segmentList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        styleList(segmentList);
        segmentList.setSelectedIndex(0);
        attachListListener();

        JScrollPane listScroll = new JScrollPane(segmentList);
        listScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 80, 120)));
        segmentPanel.add(listScroll, BorderLayout.CENTER);

        if (syncOffset != 0.0) {
            JLabel offLbl = new JLabel(String.format("  offset: %.2fs", syncOffset));
            offLbl.setForeground(new Color(180, 220, 180));
            offLbl.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 10));
            segmentPanel.add(offLbl, BorderLayout.SOUTH);
        }
    }

    /** Painel de precisão/progresso (substitui o placar de pontuação). */
    private void buildPrecisionPanel() {
        precisionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 6));
        precisionPanel.setBackground(new Color(15, 52, 96));
        precisionPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel title = new JLabel("📚 MODO TREINO");
        title.setForeground(new Color(255, 215, 0));
        title.setFont(new Font("Segoe UI Symbol", Font.BOLD, 14));
        precisionPanel.add(title);

        segInfoLabel = new JLabel("Selecione um trecho para começar");
        segInfoLabel.setForeground(new Color(200, 220, 255));
        segInfoLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        precisionPanel.add(segInfoLabel);

        phaseLabel = new JLabel("");
        phaseLabel.setForeground(new Color(255, 215, 0));
        phaseLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 13));
        precisionPanel.add(phaseLabel);

        precisionLabel = new JLabel("Precisão: —");
        precisionLabel.setForeground(new Color(200, 200, 200));
        precisionLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 13));
        precisionPanel.add(precisionLabel);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Aguardando...");
        progressBar.setBackground(new Color(30, 40, 70));
        progressBar.setForeground(new Color(52, 152, 219));
        progressBar.setPreferredSize(new Dimension(200, 22));
        precisionPanel.add(progressBar);

        resultLabel = new JLabel("");
        resultLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        resultPanel = new JPanel();
        resultPanel.setBackground(new Color(15, 52, 96));
        resultPanel.add(resultLabel);
        resultPanel.setVisible(false);
        precisionPanel.add(resultPanel);
    }

    /** Painel inferior: controles de treino (substitui os botões de modo). */
    private void buildLearningControlPanel() {
        learningControlPanel = new JPanel();
        learningControlPanel.setLayout(new BoxLayout(learningControlPanel, BoxLayout.Y_AXIS));
        learningControlPanel.setBackground(new Color(22, 33, 62));

        // ── Seletor de oitava
        JPanel octavePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        octavePanel.setBackground(new Color(22, 33, 62));
        octavePanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel octaveLbl = styledLabel("🎵 Oitava:", 12, Font.BOLD);
        octaveLbl.setForeground(new Color(200, 220, 255));
        octaveCombo = new JComboBox<>(new String[]{
                "−1 oitava (mais grave)", "Original", "+1 oitava (mais agudo)"});
        octaveCombo.setSelectedIndex(1);
        octaveCombo.setBackground(new Color(30, 40, 80));
        octaveCombo.setForeground(Color.WHITE);
        octaveCombo.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));
        octaveCombo.setFocusable(false);
        octaveCombo.addActionListener(e -> {
            octaveShift = OCTAVE_OPTIONS[octaveCombo.getSelectedIndex()];
            if (activeMerged != null)
                canvas.setNotes(displayNotes(activeMerged));
        });
        octavePanel.add(octaveLbl);
        octavePanel.add(octaveCombo);

        // ── Botões de ação + switch de gravação
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnPanel.setBackground(new Color(22, 33, 62));
        btnPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        listenBtn = bigButton("👂 Ouvir trecho", new Color(52, 73, 94));
        singBtn   = bigButton("🎤 Cantar!",       new Color(39, 174, 96));

        JButton exitBtn = bigButton("⬅ Sair do Treino", new Color(150, 50, 50));
        exitBtn.addActionListener(e -> {
            stop();
            if (onExitCallback != null) onExitCallback.run();
        });

        listenBtn.addActionListener(e -> startListenPhase());
        singBtn.addActionListener(e   -> startSingPhase());

        btnPanel.add(listenBtn);
        btnPanel.add(singBtn);
        btnPanel.add(exitBtn);

        if (voiceRecorder != null && sharedMic != null) {
            JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
            sep.setPreferredSize(new Dimension(2, 36));
            sep.setForeground(new Color(80, 80, 110));
            btnPanel.add(sep);

            recordSwitch = buildRecordSwitch();
            btnPanel.add(recordSwitch);

            recIndicatorLabel = new JLabel("  🔴 GRAVANDO");
            recIndicatorLabel.setForeground(new Color(255, 80, 80));
            recIndicatorLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 12));
            recIndicatorLabel.setVisible(false);
            btnPanel.add(recIndicatorLabel);
        }

        JLabel hint = styledLabel(
                "Dica: ouça o trecho (MIDI + MP3), depois cante tentando superar 80%.",
                10, Font.ITALIC);
        hint.setForeground(new Color(130, 130, 160));
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        learningControlPanel.add(octavePanel);
        learningControlPanel.add(btnPanel);
        learningControlPanel.add(hint);
        learningControlPanel.add(Box.createVerticalStrut(4));
    }

    private JToggleButton buildRecordSwitch() {
        JToggleButton sw = new JToggleButton() {
            private static final int TRACK_H   = 22;
            private static final int TRACK_W   = 50;
            private static final int KNOB_DIAM = 16;
            private static final int PAD       = 3;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int trackY = (getHeight() - TRACK_H) / 2;
                Color trackColor = !isEnabled()
                        ? (isSelected() ? new Color(200,30,30).darker()  : new Color(70,70,80).darker())
                        : (isSelected() ? new Color(200,30,30)           : new Color(70,70,80));
                g2.setColor(trackColor);
                g2.fillRoundRect(0, trackY, TRACK_W, TRACK_H, TRACK_H, TRACK_H);
                int knobX = isSelected() ? TRACK_W - KNOB_DIAM - PAD : PAD;
                int knobY = trackY + (TRACK_H - KNOB_DIAM) / 2;
                g2.setColor(isEnabled() ? Color.WHITE : Color.LIGHT_GRAY);
                g2.fillOval(knobX, knobY, KNOB_DIAM, KNOB_DIAM);
                g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(isEnabled()
                        ? (isSelected() ? new Color(255,130,130) : new Color(160,160,180))
                        : new Color(100,100,110));
                g2.drawString(isSelected() ? "Gravar: ON" : "Gravar: OFF",
                        TRACK_W + 6, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        sw.setOpaque(false);
        sw.setContentAreaFilled(false);
        sw.setBorderPainted(false);
        sw.setFocusPainted(false);
        sw.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sw.setPreferredSize(new Dimension(50 + 90, 36));
        sw.setToolTipText(
                "<html>Ativar para gravar sua voz ao clicar em <b>Cantar!</b>.<br>" +
                        "O arquivo será salvo como <i>{voz} - {trecho}</i>.</html>");
        sw.addItemListener(e -> sw.repaint());
        return sw;
    }

    // ── Seleção de segmento ───────────────────────────────────────────────────
    private void attachListListener() {
        segmentList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && phase == Phase.IDLE) {
                loadFromSelection();
            }
        });
    }

    private void loadFromSelection() {
        int[] selected = segmentList.getSelectedIndices();
        if (selected == null || selected.length == 0) return;

        currentSegIdx = selected[0];
        MergedSegment merged = buildMergedFromSelection();
        activeMerged = merged;

        resetMergedNotes(merged);
        segScore   = 0.0;
        segElapsed = 0.0;

        canvas.setNotes(displayNotes(merged));
        canvas.updateTime(merged.startTime);

        String infoText;
        if (merged.sources.size() == 1) {
            Segment seg = merged.sources.get(0);
            infoText = String.format("Trecho %d/%d — %.1fs — %s",
                    seg.index + 1, segments.size(), merged.notesDuration(), seg.label);
        } else {
            int first = merged.sources.get(0).index + 1;
            int last  = merged.sources.get(merged.sources.size() - 1).index + 1;
            infoText = String.format("Trechos %d–%d/%d — %.1fs",
                    first, last, segments.size(), merged.notesDuration());
        }

        final String _info = infoText;
        SwingUtilities.invokeLater(() -> {
            segInfoLabel.setText(_info);
            precisionLabel.setText("Precisão: —");
            precisionLabel.setForeground(new Color(200, 200, 200));
            progressBar.setValue(0);
            progressBar.setString("Aguardando...");
            resultPanel.setVisible(false);
            setPhaseUI(Phase.IDLE);
        });
    }

    private void resetMergedNotes(MergedSegment merged) {
        pitchSamples.clear();
        for (MusicNote n : merged.notes) {
            n.wasEvaluated = false;
            n.timeCorrect  = 0.0;
            n.score        = 0.0;
            n.rating       = "";
            n.achieved     = false;
            n.ratingColor  = null;
            if (n.duration <= SHORT_NOTE_THRESHOLD)
                pitchSamples.put(n, new ArrayList<>());
        }
    }

    // ── Fases ─────────────────────────────────────────────────────────────────
    private void startListenPhase() {
        if (!running || activeMerged == null) return;
        setPhaseUI(Phase.LISTENING);
        phaseLabel.setText("👂 Ouça o trecho...");
        resultPanel.setVisible(false);

        resetMergedNotes(activeMerged);
        canvas.setNotes(displayNotes(activeMerged));
        spawnLoop(activeMerged, false);
    }

    private void startSingPhase() {
        if (!running || activeMerged == null) return;
        setPhaseUI(Phase.SINGING);
        phaseLabel.setText("🎤 Agora cante!");
        segScore   = 0.0;
        segElapsed = 0.0;

        resetMergedNotes(activeMerged);
        canvas.setNotes(displayNotes(activeMerged));
        spawnLoop(activeMerged, true);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────
    private void spawnLoop(MergedSegment merged, boolean sing) {
        loopToken++;
        final int myToken = loopToken;

        stopAllMidi();
        if (sing) startRecordingForMerged(merged);

        Thread t = new Thread(() -> {
            if (AudioPlayer != null) AudioPlayer.stop();
            sleep(MP3_STOP_SETTLE_MS);

            if (loopToken != myToken) { stopRecordingIfActive(); return; }

            if (AudioPlayer != null && AudioPlayer.isLoaded()) {
                double mp3SeekPos = Math.max(0.0, merged.playFrom + syncOffset);
                AudioPlayer.setVolume(0.5f);
                AudioPlayer.seek(mp3SeekPos);
                AudioPlayer.play();
            }

            sleep(MP3_PLAY_SETTLE_MS);
            segmentLoop(merged, sing, myToken);
        }, sing ? "LM-Sing" : "LM-Listen");
        t.setDaemon(true);
        t.start();
    }

    private void segmentLoop(MergedSegment merged, boolean sing, int myToken) {
        long   lastCheck      = System.currentTimeMillis();
        double locScore       = 0.0;
        double locElapsed     = 0.0;
        int    activeMidiNote = -1;

        final double expectedMp3Start = merged.playFrom + syncOffset;

        while (loopToken == myToken) {

            long   now       = System.currentTimeMillis();
            double deltaTime = (now - lastCheck) / 1000.0;
            lastCheck = now;

            double rawMp3Time = (AudioPlayer != null && AudioPlayer.isLoaded())
                    ? AudioPlayer.getCurrentTime() : expectedMp3Start;
            double mp3Time = rawMp3Time < expectedMp3Start - 0.1
                    ? expectedMp3Start : rawMp3Time;
            double noteTime = mp3Time - syncOffset;

            final double _nt = noteTime;
            SwingUtilities.invokeLater(() -> canvas.updateTime(_nt));

            if (sing) {
                canvas.addTrailPoint(noteTime, audioDetector.getInstantFreq());

                for (MusicNote note : merged.notes) {
                    if (!note.wasEvaluated) {
                        if (noteTime >= note.startTime && noteTime <= note.endTime) {
                            evalNote(note, deltaTime);
                        } else if (noteTime > note.endTime) {
                            finalizeNote(note);
                            locScore   += note.score;
                            locElapsed += note.duration;
                        }
                    }
                }
            } else {
                double midiTime    = noteTime + MIDI_LATENCY_S;
                int    desiredNote = -1;
                for (MusicNote note : merged.notes) {
                    if (midiTime >= note.startTime && midiTime <= note.endTime) {
                        desiredNote = Math.max(0, Math.min(127,
                                note.midi + octaveShift * OCTAVE_SEMITONES));
                        break;
                    }
                }
                if (desiredNote != activeMidiNote) {
                    if (activeMidiNote != -1) midiNoteOff(activeMidiNote);
                    if (desiredNote    != -1) midiNoteOn(desiredNote);
                    activeMidiNote = desiredNote;
                }
            }

            double segLength = merged.endTime - merged.startTime;
            double elapsed   = noteTime - merged.startTime;
            int    barPct    = (int) Math.max(0, Math.min(100, (elapsed / segLength) * 100));

            final double fScore   = locScore;
            final double fElapsed = locElapsed;
            final int    fBar     = barPct;
            final double fElapsedDisplay = elapsed;

            SwingUtilities.invokeLater(() -> {
                if (loopToken != myToken) return;
                progressBar.setValue(fBar);
                progressBar.setString(String.format("%d%%  —  %.1fs / %.1fs",
                        fBar, Math.max(0.0, fElapsedDisplay), segLength));
                if (sing && fElapsed > 0) {
                    double prec = Math.min((fScore / fElapsed) * 100.0, 100.0);
                    precisionLabel.setText(
                            String.format("Precisão: %.1f%%", prec).replace(".", ","));
                    precisionLabel.setForeground(colorForPrecision(prec));
                }
            });

            if (noteTime >= merged.endTime + 0.3) {
                if (!sing && activeMidiNote != -1) midiNoteOff(activeMidiNote);
                if (AudioPlayer != null) AudioPlayer.stop();

                if (sing) {
                    for (MusicNote note : merged.notes) {
                        if (!note.wasEvaluated) {
                            finalizeNote(note);
                            locScore   += note.score;
                            locElapsed += note.duration;
                        }
                    }
                    segScore   = locScore;
                    segElapsed = locElapsed;
                    stopRecordingIfActive();
                }

                final double finalScore   = locScore;
                final double finalElapsed = locElapsed;

                SwingUtilities.invokeLater(() -> {
                    if (loopToken != myToken) return;
                    progressBar.setValue(100);
                    if (sing) {
                        showSegmentResult(finalScore, finalElapsed);
                    } else {
                        phaseLabel.setText("✅ Trecho ouvido! Pronto para cantar?");
                        phaseLabel.setForeground(new Color(255, 215, 0));
                        setPhaseUI(Phase.IDLE);
                    }
                });
                break;
            }

            sleep(LOOP_INTERVAL_MS);
        }

        if (loopToken != myToken) {
            stopAllMidi();
            stopRecordingIfActive();
        }
    }

    // ── Avaliação ─────────────────────────────────────────────────────────────
    private void evalNote(MusicNote note, double deltaTime) {
        int    shiftedMidi = Math.max(0, Math.min(127,
                note.midi + octaveShift * OCTAVE_SEMITONES));
        double targetHz    = PitchDetector.midiToHz(shiftedMidi);

        if (note.duration <= SHORT_NOTE_THRESHOLD) {
            double freq = audioDetector.getInstantFreq();
            if (freq > 0) {
                pitchSamples.get(note).add(freq);
                if (Math.abs(PitchDetector.frequencyToCents(freq, targetHz))
                        <= audioDetector.toleranceCents)
                    note.timeCorrect += deltaTime;
            }
        } else {
            double freq = audioDetector.getAverageFreq();
            if (freq > 0 && Math.abs(PitchDetector.frequencyToCents(freq, targetHz))
                    <= audioDetector.toleranceCents)
                note.timeCorrect += deltaTime;
        }
    }

    private void finalizeNote(MusicNote note) {
        if (note.wasEvaluated) return;
        note.wasEvaluated = true;
        if (note.duration <= SHORT_NOTE_THRESHOLD) finalizeShortNote(note);
        else                                        finalizeWithTimeCorrect(note);
    }

    private void finalizeWithTimeCorrect(MusicNote note) {
        note.timeCorrect = Math.min(note.timeCorrect, note.duration);
        note.score       = Math.min(note.timeCorrect * 1.2, note.duration);
        double pct = note.duration > 0 ? (note.timeCorrect / note.duration) * 100.0 : 0.0;
        applyRatingFromLevel(note, pctToLevel(pct));
    }

    private void finalizeShortNote(MusicNote note) {
        int    shiftedMidi = Math.max(0, Math.min(127,
                note.midi + octaveShift * OCTAVE_SEMITONES));
        double targetHz    = PitchDetector.midiToHz(shiftedMidi);

        note.timeCorrect = Math.min(note.timeCorrect, note.duration);
        double scoreA = Math.min(note.timeCorrect * 1.2, note.duration);
        double pctA   = note.duration > 0 ? (note.timeCorrect / note.duration) * 100.0 : 0.0;
        int    levelA = pctToLevel(pctA);

        List<Double> samples = pitchSamples.getOrDefault(note, List.of());
        double scoreB = 0.0; int levelB = 0;
        if (!samples.isEmpty()) {
            double avgFreq = samples.stream().mapToDouble(d -> d).average().orElse(0);
            int    avgMidi = avgFreq > 0 ? PitchDetector.hzToMidi(avgFreq) : -1;
            int    diff    = Math.abs(avgMidi - shiftedMidi);
            if      (diff == 0) { scoreB = note.duration; levelB = 4; }
            else if (diff == 1) { scoreB = note.duration * 0.5; levelB = 2; }
        }

        double scoreC = 0.0; int levelC = 0;
        if (!samples.isEmpty()) {
            int total = samples.size(), correct = 0;
            for (double f : samples)
                if (Math.abs(PitchDetector.frequencyToCents(f, targetHz))
                        <= audioDetector.toleranceCents) correct++;
            double pctC = (correct * 100.0) / total;
            if      (pctC >= 50.0) { scoreC = note.duration;        levelC = 4; }
            else if (pctC >= 37.5) { scoreC = note.duration * 0.75; levelC = 3; }
            else if (pctC >= 25.0) { scoreC = note.duration * 0.50; levelC = 2; }
            else if (correct > 1)  { scoreC = note.duration * 0.05; levelC = 1; }
        }

        int    bestLevel = Math.max(levelA, Math.max(levelB, levelC));
        double bestScore = bestLevel == levelA ? scoreA : bestLevel == levelB ? scoreB : scoreC;
        note.score = bestScore;
        applyRatingFromLevel(note, bestLevel);
    }

    private int pctToLevel(double pct) {
        if (pct >= 90) return 4;
        if (pct >= 75) return 3;
        if (pct >= 50) return 2;
        if (pct >=  5) return 1;
        return 0;
    }

    private void applyRatingFromLevel(MusicNote note, int level) {
        switch (level) {
            case 4 -> { note.rating = "Perfeito!"; note.achieved = true;  note.ratingColor = COLOR_PERFECT; }
            case 3 -> { note.rating = "Ótimo";     note.achieved = true;  note.ratingColor = COLOR_GREAT;   }
            case 2 -> { note.rating = "Bom";       note.achieved = true;  note.ratingColor = COLOR_GOOD;    }
            case 1 -> { note.rating = "OK";        note.achieved = true;  note.ratingColor = COLOR_OK;      }
            default-> { note.rating = "Errou";     note.achieved = false; note.ratingColor = COLOR_MISSED;  }
        }
    }

    // ── Resultado ─────────────────────────────────────────────────────────────
    private void showSegmentResult(double score, double elapsed) {
        double  precision = elapsed > 0 ? Math.min((score / elapsed) * 100.0, 100.0) : 0.0;
        boolean passed    = precision >= PASS_PRECISION;

        precisionLabel.setText(
                String.format("Precisão final: %.1f%%", precision).replace(".", ","));
        precisionLabel.setForeground(colorForPrecision(precision));

        String rangeInfo = activeMerged != null && activeMerged.sources.size() > 1
                ? String.format(" (%s)", activeMerged.label) : "";
        String emoji = precision >= 90 ? "🏆" : precision >= 80 ? "✅"
                : precision >= 50 ? "👍" : "💪";
        resultLabel.setText(passed
                ? String.format("%s %.1f%%%s — Excelente!", emoji, precision, rangeInfo)
                : String.format("%s %.1f%%%s — Continue praticando!", emoji, precision, rangeInfo));
        resultLabel.setForeground(colorForPrecision(precision));
        resultPanel.setVisible(true);

        phaseLabel.setText(passed ? "✅ Trecho aprovado!" : "🔁 Tente novamente!");
        phaseLabel.setForeground(colorForPrecision(precision));
        setPhaseUI(Phase.RESULT);

        // ← ADICIONE ISTO:
        SwingUtilities.invokeLater(() -> {
            phase = Phase.IDLE;  // Restaura IDLE para permitir nova seleção imediatamente
        });
    }


    // ── Estado da UI ──────────────────────────────────────────────────────────
    private void setPhaseUI(Phase p) {
        phase = p;
        switch (p) {
            case IDLE -> {
                listenBtn.setEnabled(true);
                singBtn.setEnabled(true);
                octaveCombo.setEnabled(true);
                segmentList.setEnabled(true);
                if (recordSwitch != null) recordSwitch.setEnabled(true);
                phaseLabel.setText("⬇ Escolha: Ouvir ou Cantar");
                phaseLabel.setForeground(new Color(255, 215, 0));
            }
            case LISTENING, SINGING -> {
                listenBtn.setEnabled(false);
                singBtn.setEnabled(false);
                octaveCombo.setEnabled(false);
                segmentList.setEnabled(false);
                if (recordSwitch != null) recordSwitch.setEnabled(false);
            }
            case RESULT -> {
                listenBtn.setEnabled(true);
                singBtn.setEnabled(true);
                octaveCombo.setEnabled(true);
                segmentList.setEnabled(true);
                if (recordSwitch != null) recordSwitch.setEnabled(true);
            }
        }
    }

    // ── Utilitários ───────────────────────────────────────────────────────────
    private Color colorForPrecision(double pct) {
        if (pct >= PASS_PRECISION) return new Color(39, 174, 96);
        if (pct >= 50)             return new Color(230, 126, 34);
        return new Color(231, 76, 60);
    }

    private void styleList(JList<String> list) {
        list.setBackground(new Color(20, 30, 55));
        list.setForeground(Color.WHITE);
        list.setFont(new Font("Monospaced", Font.PLAIN, 12));
        list.setSelectionBackground(new Color(52, 152, 219));
        list.setSelectionForeground(Color.WHITE);
    }

    private JLabel styledLabel(String text, int size, int style) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Segoe UI Symbol", style, size));
        return lbl;
    }

    private JButton bigButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setPreferredSize(new Dimension(170, 42));
        return btn;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
