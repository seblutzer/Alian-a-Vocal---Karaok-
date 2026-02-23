
package com.karaoke;

import javax.sound.sampled.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Grava a voz do usuário consumindo os bytes fornecidos pelo
 * {@link SharedMicrophone} — sem abrir uma segunda linha de hardware.
 *
 * O nome do arquivo pode ser definido via {@link #setOutputName(String)}
 * antes de chamar {@link #start()}. Se não definido, usa timestamp.
 * Formato: "{outputName}_voz.wav/.mp3"
 */
public class VoiceRecorder implements SharedMicrophone.Consumer {

    private static final File OUTPUT_DIR = new File(
            System.getProperty("user.home"), "KaraokeRecordings");

    // ── Estado ────────────────────────────────────────────────────────────────
    private volatile boolean      recording   = false;
    private ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private AudioFormat           captureFormat;
    private File                  outputFile;
    private Listener              listener;

    /**
     * Nome base do arquivo de saída (sem extensão).
     * Se {@code null}, usa timestamp automático.
     * Caracteres inválidos para nomes de arquivo são sanitizados em {@link #start()}.
     */
    private String outputName = null;

    // ── Listener ──────────────────────────────────────────────────────────────
    public interface Listener {
        void onSaved(File file);
        void onError(String message);
    }

    public void setListener(Listener l) { this.listener = l; }
    public boolean isRecording()        { return recording; }
    public File    getFile()            { return outputFile; }

    /**
     * Define o nome base do arquivo de saída.
     * Deve ser chamado antes de {@link #start()}.
     *
     * @param name nome desejado, ex: {@code "Soprano - Trecho 1 \"Kyrie\""}.
     *             Caracteres {@code \ / : * ? " < > |} são substituídos por {@code _}.
     */
    public void setOutputName(String name) {
        this.outputName = name;
    }

    // ── SharedMicrophone.Consumer ─────────────────────────────────────────────
    @Override
    public void onAudioData(byte[] buf, int offset, int length, AudioFormat format) {
        if (!recording) return;
        captureFormat = format;
        synchronized (audioBuffer) {
            audioBuffer.write(buf, offset, length);
        }
    }

    // ── Controle de gravação ──────────────────────────────────────────────────

    /** Começa a gravar. Chame {@link #setOutputName(String)} antes se desejar nome customizado. */
    public synchronized void start() {
        if (recording) return;
        audioBuffer.reset();
        recording = true;
        System.out.println("[VoiceRecorder] Gravação iniciada."
                + (outputName != null ? " Nome: " + outputName : ""));
    }

    /** Para a gravação e salva o arquivo em thread separada. */
    public synchronized void stop() {
        if (!recording) return;
        recording = false;

        byte[] pcmData;
        synchronized (audioBuffer) {
            pcmData = audioBuffer.toByteArray();
            audioBuffer.reset();
        }

        final byte[]      data     = pcmData;
        final AudioFormat format   = captureFormat;
        final String      baseName = buildBaseName();

        Thread t = new Thread(() -> saveAudio(data, format, baseName), "VoiceRecorder-Save");
        t.setDaemon(true);
        t.start();

        System.out.println("[VoiceRecorder] Gravação parada — salvando como: " + baseName);
    }

    // ── Nome de arquivo ───────────────────────────────────────────────────────

    /**
     * Constrói o nome base do arquivo:
     * - se {@code outputName} foi definido → sanitiza e usa;
     * - caso contrário → usa timestamp.
     */
    private String buildBaseName() {
        if (outputName != null && !outputName.isBlank()) {
            // Remove / substitui caracteres proibidos em nomes de arquivo
            return outputName.trim()
                    .replaceAll("[\\\\/:*?\"<>|]", "_")
                    .replaceAll("\\s+", " ");
        }
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
    }

    // ── Salvar ────────────────────────────────────────────────────────────────
    private void saveAudio(byte[] pcmData, AudioFormat format, String baseName) {
        if (pcmData == null || pcmData.length == 0) {
            fireError("Nenhum dado de áudio capturado.");
            return;
        }
        if (format == null) {
            fireError("Formato de áudio desconhecido — microfone não estava ativo?");
            return;
        }

        try {
            OUTPUT_DIR.mkdirs();
            File wavFile = new File(OUTPUT_DIR, baseName + ".wav");

            // Se já existe um arquivo com mesmo nome, adiciona sufixo numérico
            wavFile = resolveConflict(wavFile);

            AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(pcmData),
                    format,
                    pcmData.length / format.getFrameSize());
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile);

            // Tenta converter para MP3 via LAME
            File mp3File = new File(OUTPUT_DIR,
                    wavFile.getName().replaceAll("\\.wav$", ".mp3"));
            if (convertToMp3(wavFile, mp3File)) {
                wavFile.delete();
                outputFile = mp3File;
            } else {
                outputFile = wavFile;
            }

            fireSaved(outputFile);

        } catch (IOException e) {
            fireError("Erro ao salvar gravação: " + e.getMessage());
        }
    }

    /**
     * Se o arquivo já existir, adiciona {@code (2)}, {@code (3)}, … ao nome.
     */
    private File resolveConflict(File file) {
        if (!file.exists()) return file;
        String parent = file.getParent();
        String name   = file.getName();
        String base   = name.replaceAll("\\.wav$", "");
        int    n      = 2;
        File   candidate;
        do {
            candidate = new File(parent, base + " (" + n++ + ").wav");
        } while (candidate.exists());
        return candidate;
    }

    private boolean convertToMp3(File wav, File mp3) {
        try {
            String lame = findLame();
            if (lame == null) return false;
            ProcessBuilder pb = new ProcessBuilder(
                    lame, "-V", "2", "--silent",
                    wav.getAbsolutePath(), mp3.getAbsolutePath());
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0 && mp3.exists() && mp3.length() > 0;
        } catch (Exception e) { return false; }
    }

    private String findLame() {
        String[] names = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new String[]{"lame.exe", "lame"} : new String[]{"lame"};
        for (String name : names) {
            try {
                new ProcessBuilder(name, "--version").redirectErrorStream(true).start().waitFor();
                return name;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Callbacks EDT ─────────────────────────────────────────────────────────
    private void fireSaved(File f) {
        if (listener != null)
            javax.swing.SwingUtilities.invokeLater(() -> listener.onSaved(f));
    }

    private void fireError(String msg) {
        if (listener != null)
            javax.swing.SwingUtilities.invokeLater(() -> listener.onError(msg));
    }
}