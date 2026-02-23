package com.karaoke;

/**
 * Detecção de pitch usando o algoritmo YIN.
 * Substitui o librosa.piptrack do Python.
 *
 * Referência: "YIN, a fundamental frequency estimator for speech and music"
 * de Alain de Cheveigné e Hideki Kawahara (2002)
 */
public class PitchDetector {

    private final int sampleRate;
    private final double threshold;   // Limiar de confiança (0.1 a 0.2 é bom)
    private final double minFreq;     // Hz mínimo detectável
    private final double maxFreq;     // Hz máximo detectável

    public PitchDetector(int sampleRate) {
        this.sampleRate = sampleRate;
        this.threshold = 0.15;
        this.minFreq = 80.0;
        this.maxFreq = 1000.0;
    }

    /**
     * Detecta a frequência fundamental de um chunk de áudio.
     * @param buffer Amostras de áudio normalizadas (-1.0 a 1.0)
     * @return Frequência em Hz, ou -1 se não detectada
     */
    public double detect(float[] buffer) {
        int minTau = (int) (sampleRate / maxFreq);
        int maxTau = (int) (sampleRate / minFreq);

        if (maxTau >= buffer.length / 2) {
            maxTau = buffer.length / 2 - 1;
        }

        double[] yinBuffer = new double[maxTau + 1];

        // Passo 1: Função de diferença
        for (int tau = 0; tau <= maxTau; tau++) {
            yinBuffer[tau] = 0;
            for (int j = 0; j < buffer.length - maxTau; j++) {
                double diff = buffer[j] - buffer[j + tau];
                yinBuffer[tau] += diff * diff;
            }
        }

        // Passo 2: Normalização cumulativa da média
        yinBuffer[0] = 1.0;
        double cumSum = 0;
        for (int tau = 1; tau <= maxTau; tau++) {
            cumSum += yinBuffer[tau];
            if (cumSum > 0) {
                yinBuffer[tau] *= tau / cumSum;
            }
        }

        // Passo 3: Encontrar o primeiro mínimo abaixo do threshold
        int tauEstimate = -1;
        for (int tau = minTau; tau <= maxTau; tau++) {
            if (yinBuffer[tau] < threshold) {
                // Verificar se é o mínimo local
                while (tau + 1 <= maxTau && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++;
                }
                tauEstimate = tau;
                break;
            }
        }

        if (tauEstimate == -1 || tauEstimate == 0) {
            return -1;
        }

        // Passo 4: Interpolação parabólica para maior precisão
        double betterTau;
        if (tauEstimate > 0 && tauEstimate < maxTau) {
            double s0 = yinBuffer[tauEstimate - 1];
            double s1 = yinBuffer[tauEstimate];
            double s2 = yinBuffer[tauEstimate + 1];
            betterTau = tauEstimate + (s2 - s0) / (2 * (2 * s1 - s2 - s0));
        } else {
            betterTau = tauEstimate;
        }

        return sampleRate / betterTau;
    }

    /**
     * Converte frequência Hz para número MIDI.
     * Equivalente ao librosa.hz_to_midi do Python.
     */
    public static int hzToMidi(double freq) {
        if (freq <= 0) return -1;
        return (int) Math.round(69 + 12 * (Math.log(freq / 440.0) / Math.log(2)));
    }

    /**
     * Converte número MIDI para frequência Hz.
     * Equivalente ao librosa.midi_to_hz do Python.
     */
    public static double midiToHz(int midi) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0);
    }

    /**
     * Calcula diferença em cents entre duas frequências.
     */
    public static double frequencyToCents(double freq1, double freq2) {
        if (freq1 <= 0 || freq2 <= 0) return 0;
        return 1200 * (Math.log(freq1 / freq2) / Math.log(2));
    }
}
