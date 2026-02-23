
package com.karaoke;

import javax.sound.sampled.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abre o microfone UMA única vez e distribui os bytes de áudio para
 * múltiplos consumidores ({@link Consumer}).
 *
 * Tanto o {@link AudioDetector} quanto o {@link VoiceRecorder} recebem os
 * mesmos bytes sem precisar abrir uma segunda linha — o que causava o erro
 * "line … not supported" quando o hardware não permite linhas paralelas.
 *
 * Uso:
 * <pre>
 *   SharedMicrophone mic = new SharedMicrophone();
 *   mic.addConsumer(audioDetector);
 *   mic.addConsumer(voiceRecorder);   // só quando quiser gravar
 *   mic.start();
 *   ...
 *   mic.removeConsumer(voiceRecorder);
 *   mic.stop();
 * </pre>
 */
public class SharedMicrophone {

    // ── Formatos candidatos (ordem de preferência) ────────────────────────────
    private static final AudioFormat[] FORMAT_CANDIDATES = {
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 1, 2, 44100f, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000f, 16, 1, 2, 48000f, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000f, 16, 2, 4, 48000f, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 22050f, 16, 1, 2, 22050f, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000f, 16, 1, 2, 16000f, false),
    };

    // ── Interface do consumidor ───────────────────────────────────────────────
    public interface Consumer {
        /**
         * Chamado a cada chunk de áudio capturado.
         * <p><b>Atenção:</b> chamado em thread de captura — não bloqueie.</p>
         *
         * @param buf    buffer com os bytes PCM
         * @param offset posição inicial válida
         * @param length número de bytes válidos
         * @param format formato do áudio capturado
         */
        void onAudioData(byte[] buf, int offset, int length, AudioFormat format);
    }

    // ── Estado ────────────────────────────────────────────────────────────────
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();

    private TargetDataLine  line;
    private AudioFormat     activeFormat;
    private Thread          captureThread;
    private volatile boolean running = false;

    // ── Gerenciamento de consumidores ─────────────────────────────────────────
    public void addConsumer(Consumer c)    { consumers.add(c); }
    public void removeConsumer(Consumer c) { consumers.remove(c); }

    // ── Acesso ao formato ─────────────────────────────────────────────────────
    /** Formato negociado com o hardware. Disponível após {@link #start()}. */
    public AudioFormat getFormat() { return activeFormat; }

    public boolean isRunning() { return running; }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Abre o microfone com o melhor formato suportado e inicia o loop de captura.
     *
     * @throws LineUnavailableException se nenhum formato for aceito pelo hardware
     */
    public synchronized void start() throws LineUnavailableException {
        if (running) return;

        // Negocia formato com o hardware
        AudioFormat chosen     = null;
        TargetDataLine chosenLine = null;

        for (AudioFormat fmt : FORMAT_CANDIDATES) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) continue;
            try {
                TargetDataLine candidate = (TargetDataLine) AudioSystem.getLine(info);
                candidate.open(fmt);
                chosen     = fmt;
                chosenLine = candidate;
                break;
            } catch (LineUnavailableException ex) {
                System.err.printf("[SharedMic] Formato recusado %s: %s%n",
                        fmt, ex.getMessage());
            }
        }

        if (chosen == null) {
            throw new LineUnavailableException(
                    "Nenhum formato de áudio compatível encontrado no microfone.\n" +
                            "Verifique se um microfone está conectado e se o app tem permissão.");
        }

        System.out.printf("[SharedMic] Usando formato: %s%n", chosen);
        activeFormat = chosen;
        line         = chosenLine;
        line.start();
        running      = true;

        captureThread = new Thread(this::captureLoop, "SharedMic-Capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /** Para a captura e fecha a linha de hardware. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }

    // ── Loop de captura ───────────────────────────────────────────────────────
    private void captureLoop() {
        // Tamanho do buffer: ~20 ms de áudio para baixa latência
        int bufSize = (activeFormat != null)
                ? Math.max(1024, (int)(activeFormat.getSampleRate()
                * activeFormat.getFrameSize() * 0.02))
                : 4096;

        byte[] buf = new byte[bufSize];

        while (running) {
            if (line == null) break;
            int read = line.read(buf, 0, buf.length);
            if (read > 0 && !consumers.isEmpty()) {
                // Cópia defensiva: cada consumidor recebe os mesmos bytes
                byte[] copy = new byte[read];
                System.arraycopy(buf, 0, copy, 0, read);
                for (Consumer c : consumers) {
                    try { c.onAudioData(copy, 0, read, activeFormat); }
                    catch (Exception ex) {
                        System.err.printf("[SharedMic] Erro no consumidor %s: %s%n",
                                c.getClass().getSimpleName(), ex.getMessage());
                    }
                }
            }
        }
    }
}
