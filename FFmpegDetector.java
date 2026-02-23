
package com.karaoke;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.io.OutputStream;


/**
 * Detecta a presença do FFmpeg no PATH do sistema operacional.
 *
 * A detecção é feita UMA vez e cacheada — chamadas subsequentes
 * a isAvailable() custam apenas uma leitura de campo booleano.
 *
 * Se FFmpeg não estiver presente, showInstallDialog() exibe um
 * diálogo com link para download e instruções por plataforma.
 */
public final class FFmpegDetector {

    // ── Links de instalação por plataforma ────────────────────────────────────
    private static final String URL_WINDOWS = "https://www.gyan.dev/ffmpeg/builds/";
    private static final String URL_MAC     = "https://formulae.brew.sh/formula/ffmpeg";
    private static final String URL_LINUX   = "https://ffmpeg.org/download.html#build-linux";

    // ── Cache de detecção ─────────────────────────────────────────────────────
    private static Boolean cachedResult = null;

    private FFmpegDetector() {}

    /**
     * Retorna true se o binário "ffmpeg" está disponível no PATH do sistema.
     * Resultado é cacheado após a primeira chamada.
     */
    public static boolean isAvailable() {
        if (cachedResult != null) return cachedResult;

        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drena a saída para não travar o processo em nenhum SO
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            int code = p.waitFor();
            cachedResult = (code == 0);
        } catch (IOException | InterruptedException e) {
            // IOException = "não encontrado"; InterruptedException = improvável
            cachedResult = false;
        }
        return cachedResult;
    }

    /**
     * Exibe um diálogo informando que FFmpeg não está instalado,
     * com botão para abrir o link de download no navegador padrão.
     *
     * @param parent Componente pai para centralizar o diálogo (pode ser null)
     */
    public static void showInstallDialog(Component parent) {
        String os      = detectOS();
        String url     = installUrl(os);
        String command = installCommand(os);

        String message =
                "<html><body style='width:380px;font-family:Arial;'>" +
                        "<h3 style='color:#c0392b;margin-bottom:4px'>FFmpeg não encontrado</h3>" +
                        "<p>Para converter áudios para <b>Opus</b> (melhor qualidade, menor tamanho),<br>" +
                        "instale o FFmpeg no seu sistema.</p>" +
                        "<hr>" +
                        "<b>Como instalar (" + os + "):</b><br>" +
                        "<code style='background:#f4f4f4;padding:2px 6px;border-radius:3px'>"
                        + command + "</code>" +
                        "<br><br>" +
                        "<small>Ou baixe manualmente em:<br>" +
                        "<a href='" + url + "'>" + url + "</a></small>" +
                        "<hr>" +
                        "<small style='color:#777'>Sem FFmpeg, o áudio será salvo no formato original<br>" +
                        "e tocado normalmente. A conversão para Opus ficará indisponível.</small>" +
                        "</body></html>";

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Color.WHITE);

        JLabel label = new JLabel(message);
        panel.add(label, BorderLayout.CENTER);

        JButton openLink = new JButton("🌐 Abrir página de download");
        openLink.setBackground(new Color(41, 128, 185));
        openLink.setForeground(Color.WHITE);
        openLink.setFocusPainted(false);
        openLink.setBorderPainted(false);
        openLink.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(parent,
                        "Acesse manualmente:\n" + url,
                        "Abrir link", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        panel.add(openLink, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(parent, panel,
                "FFmpeg não encontrado", JOptionPane.WARNING_MESSAGE);
    }

    // ── Helpers internos ──────────────────────────────────────────────────────

    private static String detectOS() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win"))  return "Windows";
        if (os.contains("mac"))  return "macOS";
        return "Linux";
    }

    private static String installUrl(String os) {
        return switch (os) {
            case "Windows" -> URL_WINDOWS;
            case "macOS"   -> URL_MAC;
            default        -> URL_LINUX;
        };
    }

    private static String installCommand(String os) {
        return switch (os) {
            case "Windows" -> "winget install ffmpeg";
            case "macOS"   -> "brew install ffmpeg";
            default        -> "sudo apt install ffmpeg  (ou dnf / pacman)";
        };
    }
}
