
package com.karaoke;

import java.io.*;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Converte/recomprime arquivos de áudio usando o FFmpeg do sistema.
 *
 * ── Estratégia ────────────────────────────────────────────────────────────────
 *  FFmpeg presente → converte qualquer entrada para Opus (.opus)
 *                    ~64 kbps, qualidade superior ao MP3 128 kbps,
 *                    tamanho ~2× menor que MP3 128 kbps
 *  FFmpeg ausente  → lança ConversionUnavailableException (sem FFmpeg, sem conversão)
 *
 * ── Por que Opus? ─────────────────────────────────────────────────────────────
 *  • 64 kbps Opus ≈ 128 kbps MP3 em qualidade perceptual
 *  • Desenvolvido para voz — ideal para karaokê
 *  • Suportado nativamente pelo FFmpeg (libopus)
 *  • Container: OGG (.opus) — universalmente suportado
 *
 * ── Estimativa de progresso ───────────────────────────────────────────────────
 *  FFmpeg não expõe % diretamente na saída padrão (apenas tempo decorrido).
 *  Solicitamos a duração total via ffprobe e calculamos a % com base no
 *  "time=HH:MM:SS.ss" que o FFmpeg escreve na stderr a cada frame processado.
 */
public class AudioConverter {

    /** Bitrate alvo do Opus em kbps. 64 = ótima qualidade de voz. */
    private static final int OPUS_BITRATE_KBPS = 64;

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Converte {@code input} (qualquer formato suportado pelo FFmpeg) para
     * Opus em {@code output} (.opus / container OGG).
     *
     * @param input            Arquivo de entrada (MP3, WAV, FLAC, AAC, OGG…)
     * @param output           Arquivo de saída — DEVE ter extensão .opus
     * @param progressCallback Recebe 0–100 durante a conversão; pode ser null
     * @throws ConversionUnavailableException se FFmpeg não estiver no PATH
     * @throws ConversionException            se a conversão falhar por outro motivo
     */
    public static void convertToOpus(File input, File output,
                                     Consumer<Integer> progressCallback)
            throws ConversionException {

        requireFfmpeg();

        // Obtém duração total para calcular progresso (0 = desconhecida)
        double totalSeconds = probeDuration(input);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-y",                                   // sobrescreve sem perguntar
                    "-i", input.getAbsolutePath(),
                    "-c:a", "libopus",                      // codec Opus
                    "-b:a", OPUS_BITRATE_KBPS + "k",        // bitrate alvo
                    "-ac", "1",                             // mono (karaokê)
                    "-ar", "48000",                         // Opus usa 48 kHz nativo
                    "-vn",                                  // sem vídeo
                    output.getAbsolutePath()
            );

            pb.redirectErrorStream(true);   // stderr → stdout (onde FFmpeg escreve)
            Process process = pb.start();

            // Lê a saída para calcular progresso E evitar deadlock no buffer
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (progressCallback != null && totalSeconds > 0) {
                        int pct = parseProgress(line, totalSeconds);
                        if (pct >= 0) progressCallback.accept(pct);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                if (output.exists()) output.delete();
                throw new ConversionException(
                        "FFmpeg encerrou com código " + exitCode +
                                ". Verifique se o arquivo de entrada é válido.", null);
            }

            // Garante 100% ao final
            if (progressCallback != null) progressCallback.accept(100);

        } catch (IOException | InterruptedException e) {
            if (output.exists()) output.delete();
            throw new ConversionException(
                    "Erro ao executar FFmpeg: " + e.getMessage(), e);
        }
    }

    /**
     * Cria um arquivo temporário com a extensão indicada.
     * O chamador é responsável por deletá-lo após o uso.
     *
     * @param extension ex: ".opus", ".mp3"  (com ponto)
     */
    public static File createTempAudio(String extension) throws ConversionException {
        try {
            return File.createTempFile("karaoke_conv_", extension);
        } catch (IOException e) {
            throw new ConversionException(
                    "Não foi possível criar arquivo temporário.", e);
        }
    }

    /** Atalho para criar temp .opus */
    public static File createTempOpus() throws ConversionException {
        return createTempAudio(".opus");
    }

    // ── Parsing de progresso ──────────────────────────────────────────────────

    /**
     * Extrai percentual de progresso a partir de uma linha da saída do FFmpeg.
     * Formato esperado: "...time=00:01:23.45..."
     *
     * @return 0–100 se a linha contém tempo; -1 se não for linha de progresso
     */
    private static int parseProgress(String line, double totalSeconds) {
        // FFmpeg escreve "time=HH:MM:SS.ss" nas linhas de progresso
        int idx = line.indexOf("time=");
        if (idx < 0) return -1;

        String timeStr = line.substring(idx + 5, Math.min(idx + 16, line.length())).trim();
        try {
            // HH:MM:SS.ss
            String[] parts = timeStr.split(":");
            if (parts.length != 3) return -1;
            double hours   = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            double elapsed = hours * 3600 + minutes * 60 + seconds;
            return (int) Math.min(99, (elapsed / totalSeconds) * 100);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Usa ffprobe para obter a duração do arquivo em segundos.
     * Retorna 0.0 se não conseguir (progresso ficará indisponível).
     */
    private static double probeDuration(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                output = r.readLine();
            }
            p.waitFor();
            if (output != null && !output.isBlank())
                return Double.parseDouble(output.trim());
        } catch (Exception ignored) {}
        return 0.0;
    }

    // ── Guard de FFmpeg ───────────────────────────────────────────────────────

    private static void requireFfmpeg() throws ConversionUnavailableException {
        if (!FFmpegDetector.isAvailable())
            throw new ConversionUnavailableException();
    }

    // ── Exceções ──────────────────────────────────────────────────────────────

    /** Base checked — força tratamento explícito no chamador. */
    public static class ConversionException extends Exception {
        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Lançada especificamente quando FFmpeg não está disponível.
     * O chamador pode distinguir "FFmpeg ausente" de "conversão falhou".
     */
    public static class ConversionUnavailableException extends ConversionException {
        public ConversionUnavailableException() {
            super("FFmpeg não está instalado ou não foi encontrado no PATH.", null);
        }
    }
}
