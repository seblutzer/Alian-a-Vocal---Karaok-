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
 *
 * Prioridade de formato de saída:
 *  1. Opus  — se FFmpeg estiver disponível ({@link FFmpegDetector#isAvailable()})
 *  2. MP3   — se LAME estiver disponível no PATH
 *  3. WAV   — fallback final
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
     * @param name nome desejado — caracteres inválidos são substituídos por {@code _}.
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

    private String buildBaseName() {
        if (outputName != null && !outputName.isBlank()) {
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

            // Arquivo WAV temporário (prefixo "tmp_" para distinguir do produto final)
            File wavFile = File.createTempFile("tmp_" + baseName + "_", ".wav", OUTPUT_DIR);
            wavFile.deleteOnExit(); // garante limpeza mesmo em crash

            AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(pcmData),
                    format,
                    pcmData.length / format.getFrameSize());
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile);

            // ── Prioridade 1: Opus via FFmpeg ─────────────────────────────────
            if (FFmpegDetector.isAvailable()) {
                File opusFile = resolveConflict(new File(OUTPUT_DIR, baseName + ".opus"));
                if (convertToOpus(wavFile, opusFile)) {
                    wavFile.delete();
                    outputFile = opusFile;
                    fireSaved(outputFile);
                    return;
                }
                System.err.println("[VoiceRecorder] Conversão Opus falhou, tentando MP3…");
            }

            // ── Prioridade 2: MP3 via LAME ────────────────────────────────────
            File mp3File = resolveConflict(new File(OUTPUT_DIR, baseName + ".mp3"));
            if (convertToMp3(wavFile, mp3File)) {
                wavFile.delete();
                outputFile = mp3File;
                fireSaved(outputFile);
                return;
            }

            // ── Prioridade 3: WAV (fallback final) ────────────────────────────
            File finalWav = resolveConflict(new File(OUTPUT_DIR, baseName + ".wav"));
            wavFile.renameTo(finalWav);
            outputFile = finalWav;
            fireSaved(outputFile);

        } catch (IOException e) {
            fireError("Erro ao salvar gravação: " + e.getMessage());
        }
    }

    // ── Conversão Opus (FFmpeg) ───────────────────────────────────────────────

    /**
     * Converte {@code wav} para Opus usando FFmpeg.
     * Só deve ser chamado após confirmar {@link FFmpegDetector#isAvailable()}.
     *
     * Parâmetros usados:
     * <ul>
     *   <li>{@code -c:a libopus} — codec Opus</li>
     *   <li>{@code -b:a 128k}    — bitrate alvo (bom equilíbrio qualidade/tamanho)</li>
     *   <li>{@code -vn}          — ignora streams de vídeo, se houver</li>
     *   <li>{@code -y}           — sobrescreve destino sem perguntar</li>
     * </ul>
     */
    private boolean convertToOpus(File wav, File opus) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-i",  wav.getAbsolutePath(),
                    "-c:a", "libopus",
                    "-b:a", "128k",
                    "-vn",
                    "-y",
                    opus.getAbsolutePath());
            pb.redirectErrorStream(true);
            // Drena a saída para não travar o processo
            Process p = pb.start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0 && opus.exists() && opus.length() > 0;
        } catch (Exception e) {
            System.err.println("[VoiceRecorder] Erro ao converter para Opus: " + e.getMessage());
            return false;
        }
    }

    // ── Conversão MP3 (LAME) ──────────────────────────────────────────────────

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
                new ProcessBuilder(name, "--version")
                        .redirectErrorStream(true).start().waitFor();
                return name;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Resolução de conflito de nomes ────────────────────────────────────────

    /**
     * Se o arquivo já existir, adiciona {@code (2)}, {@code (3)}, …
     * Funciona com qualquer extensão.
     */
    private File resolveConflict(File file) {
        if (!file.exists()) return file;
        String parent = file.getParent();
        String name   = file.getName();
        int    dot    = name.lastIndexOf('.');
        String base   = dot >= 0 ? name.substring(0, dot)  : name;
        String ext    = dot >= 0 ? name.substring(dot)     : "";   // inclui o "."
        int    n      = 2;
        File   candidate;
        do {
            candidate = new File(parent, base + " (" + n++ + ")" + ext);
        } while (candidate.exists());
        return candidate;
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