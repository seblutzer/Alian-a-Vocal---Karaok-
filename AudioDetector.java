
package com.karaoke;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayDeque;
import java.util.Deque;

public class AudioDetector implements SharedMicrophone.Consumer {

    // ── Configuração pública ──────────────────────────────────────────────────
    public double toleranceCents = 50.0;

    // ── Tamanho de buffer fixo (usado por KaraokeGameMode para calcular latência)
    private static final int BUFFER_SIZE = 4096;

    // ── Estado interno ────────────────────────────────────────────────────────
    private PitchDetector pitchDetector  = null;
    private int           lastSampleRate = -1;

    private final Deque<Double> freqHistory = new ArrayDeque<>();
    private static final int    HISTORY_SIZE = 8;

    private volatile double  instantFreq = 0.0;
    private volatile double  averageFreq = 0.0;
    private volatile boolean active      = false;

    private SharedMicrophone sharedMic;

    // ── Injeção do microfone ──────────────────────────────────────────────────
    public void setSharedMicrophone(SharedMicrophone mic) {
        this.sharedMic = mic;
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────
    public void startListening() {
        if (active || sharedMic == null) return;
        active = true;
        sharedMic.addConsumer(this);
    }

    public void stopListening() {
        if (!active) return;
        active = false;
        if (sharedMic != null) sharedMic.removeConsumer(this);
        instantFreq = 0.0;
        averageFreq = 0.0;
        freqHistory.clear();
    }

    // ── SharedMicrophone.Consumer ─────────────────────────────────────────────
    @Override
    public void onAudioData(byte[] buf, int offset, int length, AudioFormat format) {
        if (!active) return;

        int sampleRate = (int) format.getSampleRate();

        if (pitchDetector == null || sampleRate != lastSampleRate) {
            pitchDetector  = new PitchDetector(sampleRate);
            lastSampleRate = sampleRate;
        }

        int channels     = format.getChannels();
        int bytesPerSamp = format.getSampleSizeInBits() / 8;
        int frameSize    = bytesPerSamp * channels;
        int numFrames    = length / frameSize;

        float[] samples = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            int idx  = offset + i * frameSize;
            int low  = buf[idx]     & 0xFF;
            int high = buf[idx + 1];
            short raw = (short) ((high << 8) | low);
            samples[i] = raw / 32768f;
        }

        double freq = pitchDetector.detect(samples);
        instantFreq = (freq > 0) ? freq : 0.0;

        if (freq > 0) {
            freqHistory.addLast(freq);
            if (freqHistory.size() > HISTORY_SIZE) freqHistory.pollFirst();
            averageFreq = freqHistory.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0);
        } else {
            freqHistory.clear();
            averageFreq = 0.0;
        }
    }

    // ── Leituras de frequência ────────────────────────────────────────────────
    public double getInstantFreq() { return instantFreq; }
    public double getAverageFreq() { return averageFreq; }

    // ── Informações de buffer ─────────────────────────────────────────────────

    /**
     * Tamanho fixo do buffer de captura em frames.
     * Usado por {@code KaraokeGameMode} para estimar a latência.
     */
    public int getBufferSize() { return BUFFER_SIZE; }

    /**
     * Latência estimada em segundos com base no tamanho do buffer e no
     * sample rate atual do microfone compartilhado.
     */
    public double getEstimatedLatencySeconds() {
        return BUFFER_SIZE / (double) getSampleRate();
    }

    /** Sample rate ativo, ou 44100 como fallback. */
    public float getSampleRate() {
        return (sharedMic != null && sharedMic.getFormat() != null)
                ? sharedMic.getFormat().getSampleRate()
                : 44100f;
    }
}
