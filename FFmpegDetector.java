
package com.karaoke;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detecta, localiza e (opcionalmente) instala o FFmpeg automaticamente.
 *
 * ── Fluxos disponíveis ────────────────────────────────────────────────────────
 *
 *  [1] Startup silencioso — chamado uma vez ao iniciar o app:
 *      FFmpegDetector.initInBackground(frame)
 *        → detecta em background
 *        → se ausente: tenta instalar silenciosamente (sem perguntar)
 *        → sucesso: toast discreto de confirmação
 *        → falha:   aí sim pergunta ao usuário interativamente
 *
 *  [2] Sob demanda — chamado quando o usuário tenta usar uma função:
 *      FFmpegDetector.ensureAvailable(parent)
 *        → se disponível: retorna true imediatamente
 *        → se ausente: pergunta → instala → retorna true/false
 *
 *  [3] Verificação simples (não bloqueia, não instala):
 *      FFmpegDetector.isAvailable()
 *      FFmpegDetector.getResolvedPath()
 */
public final class FFmpegDetector {

    // ── Caminhos conhecidos por plataforma ────────────────────────────────────
    private static final List<String> MAC_KNOWN_PATHS = List.of(
            "/opt/homebrew/bin/ffmpeg",   // Apple Silicon (M1/M2/M3)
            "/usr/local/bin/ffmpeg",       // Intel Mac via Homebrew
            "/usr/bin/ffmpeg"
    );
    private static final List<String> LINUX_KNOWN_PATHS = List.of(
            "/usr/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/snap/bin/ffmpeg"
    );
    private static final List<String> WIN_KNOWN_PATHS = List.of(
            System.getenv("LOCALAPPDATA") != null
                    ? System.getenv("LOCALAPPDATA") + "\\Microsoft\\WinGet\\Packages\\ffmpeg\\bin\\ffmpeg.exe"
                    : "",
            "C:\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"
    );

    // ── Estado interno ────────────────────────────────────────────────────────
    private static volatile String  resolvedPath  = null;
    private static volatile boolean detectionDone = false;

    /** Garante que initInBackground() seja chamado no máximo uma vez. */
    private static volatile boolean backgroundInitStarted = false;

    private FFmpegDetector() {}

    // ═══════════════════════════════════════════════════════════════════════════
    //  API pública
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Retorna true se o ffmpeg foi encontrado (PATH ou caminho conhecido).
     * Resultado é cacheado após a primeira chamada.
     */
    public static boolean isAvailable() {
        if (!detectionDone) detect();
        return resolvedPath != null;
    }

    /**
     * Retorna o caminho absoluto do ffmpeg para usar nos ProcessBuilders.
     * Pode ser "ffmpeg" (se no PATH) ou um caminho absoluto (/opt/homebrew/bin/ffmpeg…).
     * Retorna null se não disponível.
     */
    public static String getResolvedPath() {
        if (!detectionDone) detect();
        return resolvedPath;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [STARTUP] Inicia detecção e instalação silenciosa em background.
     *
     * Deve ser chamado UMA VEZ logo após a janela principal estar visível.
     * Não bloqueia a EDT. Exibe um toast discreto no canto da janela pai.
     *
     * Fluxo:
     *   FFmpeg encontrado      → nada (silencioso)
     *   FFmpeg ausente
     *     ↳ instala silenciosamente
     *       sucesso            → toast verde "FFmpeg instalado!"
     *       falha              → toast laranja + pergunta interativa após 1 s
     *
     * @param parent janela principal do app
     */
    public static void initInBackground(Window parent) {
        if (backgroundInitStarted) return;
        backgroundInitStarted = true;

        // Já disponível → silencioso, sem toast, sem nada
        if (isAvailable()) return;

        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                // Tenta instalação silenciosa (sem diálogos)
                try {
                    return switch (detectOS()) {
                        case "macOS"   -> installMac(msg -> {});
                        case "Windows" -> installWindows(msg -> {});
                        default        -> installLinux(msg -> {});
                    };
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            protected void done() {
                try {
                    boolean installed = get();

                    if (installed) {
                        // Re-detecta para preencher resolvedPath com o caminho absoluto
                        detectionDone = false;
                        resolvedPath  = null;
                        detect();

                        if (isAvailable()) {
                            // Toast só aparece quando instalou AGORA, nesta sessão
                            showToast(parent,
                                    "✅  FFmpeg instalado automaticamente!",
                                    new Color(39, 174, 96),
                                    4000);
                            return;
                        }
                    }

                    // Instalação silenciosa falhou — aguarda 1 s e pergunta ao usuário
                    new javax.swing.Timer(1000, e -> {
                        ((javax.swing.Timer) e.getSource()).stop();
                        showToast(parent,
                                "⚠️  FFmpeg não encontrado. Verificando opções de instalação…",
                                new Color(200, 120, 0),
                                2500);
                        new javax.swing.Timer(2600, ev -> {
                            ((javax.swing.Timer) ev.getSource()).stop();
                            ensureAvailable(parent);
                        }).start();
                    }).start();

                } catch (Exception ignored) {}
            }
        }.execute();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [SOB DEMANDA] Garante que o FFmpeg esteja disponível antes de uma operação.
     *
     * Se disponível → retorna true imediatamente.
     * Se ausente    → exibe diálogo perguntando se quer instalar.
     *   Sim → instala com diálogo de progresso → true/false
     *   Não → mostra instruções manuais        → false
     *
     * @param parent componente pai para diálogos (pode ser null)
     * @return true se ffmpeg estiver disponível ao final
     */
    public static boolean ensureAvailable(Component parent) {
        if (isAvailable()) return true;

        int choice = JOptionPane.showConfirmDialog(
                parent,
                buildNotFoundPanel(),
                "FFmpeg não encontrado",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (choice == JOptionPane.YES_OPTION) {
            return runAutoInstall(parent);
        } else {
            showManualInstructions(parent);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Detecção
    // ═══════════════════════════════════════════════════════════════════════════

    private static synchronized void detect() {
        if (detectionDone) return;

        // 1. Tenta PATH do sistema
        if (tryPath("ffmpeg")) {
            resolvedPath = "ffmpeg";
            detectionDone = true;
            return;
        }

        // 2. Tenta caminhos conhecidos
        List<String> candidates = switch (detectOS()) {
            case "macOS"   -> MAC_KNOWN_PATHS;
            case "Windows" -> WIN_KNOWN_PATHS;
            default        -> LINUX_KNOWN_PATHS;
        };

        for (String path : candidates) {
            if (path != null && !path.isBlank() && tryPath(path)) {
                resolvedPath = path;
                detectionDone = true;
                return;
            }
        }

        detectionDone = true;
        // resolvedPath permanece null → não disponível
    }

    private static boolean tryPath(String binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binary, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Instalação automática (com diálogo de progresso)
    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean runAutoInstall(Component parent) {
        ProgressDialog progress = new ProgressDialog(parent);
        AtomicBoolean success = new AtomicBoolean(false);

        SwingWorker<Boolean, String> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    return switch (detectOS()) {
                        case "macOS"   -> installMac(this::publish);
                        case "Windows" -> installWindows(this::publish);
                        default        -> installLinux(this::publish);
                    };
                } catch (Exception e) {
                    publish("❌ Erro: " + e.getMessage());
                    return false;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(progress::appendLog);
            }

            @Override
            protected void done() {
                try { success.set(get()); } catch (Exception ignored) {}
                progress.dispose();
            }
        };

        worker.execute();
        progress.setVisible(true); // bloqueia EDT até dispose()

        if (success.get()) {
            detectionDone = false;
            resolvedPath  = null;
            detect();

            if (isAvailable()) {
                JOptionPane.showMessageDialog(parent,
                        "<html><b style='color:green'>✅ FFmpeg instalado com sucesso!</b><br>" +
                                "Caminho: <code>" + resolvedPath + "</code></html>",
                        "Instalação concluída", JOptionPane.INFORMATION_MESSAGE);
                return true;
            }
        }

        JOptionPane.showMessageDialog(parent,
                "<html><b style='color:red'>❌ Instalação automática falhou.</b><br>" +
                        "Por favor, instale manualmente e reinicie o aplicativo.</html>",
                "Falha na instalação", JOptionPane.ERROR_MESSAGE);
        showManualInstructions(parent);
        return false;
    }

    // ── macOS ─────────────────────────────────────────────────────────────────

    private static boolean installMac(LogSink log) throws IOException, InterruptedException {
        log.log("🍎 Detectado: macOS");

        String brewPath = findBrew(log);
        if (brewPath == null) {
            brewPath = installHomebrew(log);
        }
        if (brewPath == null) {
            log.log("❌ Não foi possível instalar o Homebrew.");
            return false;
        }

        log.log("🍺 Homebrew: " + brewPath);
        log.log("⏳ Instalando ffmpeg (isso pode levar alguns minutos)...");

        int result = runCommand(log, brewPath, "install", "ffmpeg");

        if (result == 0) {
            String ffmpegPath = brewPath.replace("brew", "ffmpeg");
            if (Files.exists(Path.of(ffmpegPath))) {
                resolvedPath  = ffmpegPath;
                detectionDone = true;
                log.log("✅ ffmpeg instalado em: " + ffmpegPath);
                return true;
            }
        }
        return result == 0;
    }

    private static String findBrew(LogSink log) {
        for (String path : List.of("/opt/homebrew/bin/brew", "/usr/local/bin/brew")) {
            try {
                new ProcessBuilder(path, "--version")
                        .redirectErrorStream(true).start().waitFor();
                if (Files.exists(Path.of(path))) {
                    log.log("🍺 Homebrew encontrado em: " + path);
                    return path;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String installHomebrew(LogSink log) throws IOException, InterruptedException {
        log.log("🍺 Homebrew não encontrado. Instalando Homebrew...");
        int result = runCommand(log,
                "/bin/bash", "-c",
                "curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh | /bin/bash"
        );
        if (result != 0) return null;

        String brewPath = Files.exists(Path.of("/opt/homebrew/bin/brew"))
                ? "/opt/homebrew/bin/brew"
                : "/usr/local/bin/brew";

        if (Files.exists(Path.of(brewPath))) {
            log.log("✅ Homebrew instalado em: " + brewPath);
            return brewPath;
        }
        return null;
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private static boolean installWindows(LogSink log) throws IOException, InterruptedException {
        log.log("🪟 Detectado: Windows");

        if (isWingetAvailable()) {
            log.log("📦 Usando winget para instalar ffmpeg...");
            int result = runCommand(log,
                    "winget", "install", "--id", "Gyan.FFmpeg",
                    "--source", "winget", "--accept-package-agreements",
                    "--accept-source-agreements", "--silent"
            );
            if (result == 0) {
                log.log("✅ ffmpeg instalado via winget!");
                Thread.sleep(2000);
                return true;
            }
            log.log("⚠️ winget falhou, tentando download direto...");
        } else {
            log.log("⚠️ winget não disponível, usando download direto...");
        }

        return downloadFFmpegWindows(log);
    }

    private static boolean isWingetAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("winget", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean downloadFFmpegWindows(LogSink log) throws IOException, InterruptedException {
        String downloadUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
        Path destDir = Path.of(System.getenv("LOCALAPPDATA"), "ffmpeg");
        Path zipPath = destDir.resolve("ffmpeg.zip");

        log.log("📥 Baixando ffmpeg de gyan.dev...");
        Files.createDirectories(destDir);

        int downloadResult = runCommand(log,
                "powershell", "-Command",
                "Invoke-WebRequest -Uri '" + downloadUrl + "' -OutFile '" + zipPath + "' -UseBasicParsing"
        );
        if (downloadResult != 0) { log.log("❌ Falha no download."); return false; }

        log.log("📦 Extraindo arquivos...");
        int extractResult = runCommand(log,
                "powershell", "-Command",
                "Expand-Archive -Path '" + zipPath + "' -DestinationPath '" + destDir + "' -Force"
        );
        if (extractResult != 0) { log.log("❌ Falha na extração."); return false; }

        try (var stream = Files.walk(destDir, 3)) {
            var ffmpegExe = stream
                    .filter(p -> p.getFileName().toString().equals("ffmpeg.exe"))
                    .findFirst();
            if (ffmpegExe.isPresent()) {
                resolvedPath  = ffmpegExe.get().toString();
                detectionDone = true;
                log.log("✅ ffmpeg instalado em: " + resolvedPath);
                Files.deleteIfExists(zipPath);
                return true;
            }
        }
        log.log("❌ ffmpeg.exe não encontrado após extração.");
        return false;
    }

    // ── Linux ─────────────────────────────────────────────────────────────────

    private static boolean installLinux(LogSink log) throws IOException, InterruptedException {
        log.log("🐧 Detectado: Linux");
        String pm = detectLinuxPackageManager(log);
        return switch (pm) {
            case "apt"    -> runCommand(log, "pkexec", "apt-get", "install", "-y", "ffmpeg") == 0;
            case "dnf"    -> runCommand(log, "pkexec", "dnf",     "install", "-y", "ffmpeg") == 0;
            case "pacman" -> runCommand(log, "pkexec", "pacman",  "-S", "--noconfirm", "ffmpeg") == 0;
            case "zypper" -> runCommand(log, "pkexec", "zypper",  "install", "-y", "ffmpeg") == 0;
            default       -> { log.log("❌ Package manager não reconhecido."); yield false; }
        };
    }

    private static String detectLinuxPackageManager(LogSink log) {
        for (String pm : List.of("apt-get", "dnf", "pacman", "zypper")) {
            try {
                Process p = new ProcessBuilder("which", pm)
                        .redirectErrorStream(true).start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                if (p.waitFor() == 0) {
                    log.log("📦 Package manager: " + pm);
                    return pm.replace("-get", "");
                }
            } catch (Exception ignored) {}
        }
        return "unknown";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Utilitários
    // ═══════════════════════════════════════════════════════════════════════════

    private static int runCommand(LogSink log, String... command) throws IOException, InterruptedException {
        log.log("▶ " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        if (detectOS().equals("macOS")) {
            pb.environment().merge("PATH",
                    "/opt/homebrew/bin:/usr/local/bin",
                    (existing, extra) -> extra + ":" + existing);
        }

        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) log.log("  " + line);
        }
        return p.waitFor();
    }

    private static String detectOS() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "Windows";
        if (os.contains("mac")) return "macOS";
        return "Linux";
    }

    private static String installUrl(String os) {
        return switch (os) {
            case "Windows" -> "https://www.gyan.dev/ffmpeg/builds/";
            case "macOS"   -> "https://formulae.brew.sh/formula/ffmpeg";
            default        -> "https://ffmpeg.org/download.html#build-linux";
        };
    }

    private static String manualCommand(String os) {
        return switch (os) {
            case "Windows" -> "winget install Gyan.FFmpeg";
            case "macOS"   -> "brew install ffmpeg";
            default        -> "sudo apt install ffmpeg";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Toast — notificação discreta no canto da janela
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Exibe uma notificação flutuante no canto inferior direito da janela pai.
     * Aparece com fade-in, fica visível por {@code durationMs} e some com fade-out.
     *
     * @param parent     janela de referência para posicionamento
     * @param message    texto da notificação
     * @param bgColor    cor de fundo (verde = sucesso, laranja = aviso)
     * @param durationMs tempo visível em ms (ex: 4000)
     */
    public static void showToast(Window parent, String message, Color bgColor, int durationMs) {
        JWindow toast = new JWindow(parent);
        toast.setAlwaysOnTop(true);

        JLabel label = new JLabel("<html><body style='padding:2px'>" + message + "</body></html>");
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Arial", Font.BOLD, 13));
        label.setBorder(new EmptyBorder(10, 16, 10, 16));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(bgColor);
        panel.setBorder(BorderFactory.createLineBorder(bgColor.darker(), 1));
        panel.add(label);

        toast.setContentPane(panel);
        toast.pack();

        // Posição: canto inferior direito da janela pai
        positionToast(toast, parent);
        parent.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentMoved(java.awt.event.ComponentEvent e)   { positionToast(toast, parent); }
            @Override public void componentResized(java.awt.event.ComponentEvent e) { positionToast(toast, parent); }
        });

        // Fade-in → espera → fade-out
        toast.setOpacity(0f);
        toast.setVisible(true);

        // Fase 1: fade-in (300 ms, 30 passos de 10 ms)
        animateOpacity(toast, 0f, 1f, 300, () ->
                // Fase 2: visível por durationMs
                new javax.swing.Timer(durationMs, e -> {
                    ((javax.swing.Timer) e.getSource()).stop();
                    // Fase 3: fade-out (400 ms)
                    animateOpacity(toast, 1f, 0f, 400, () ->
                            SwingUtilities.invokeLater(toast::dispose));
                }) {{ setRepeats(false); start(); }}
        );
    }

    private static void positionToast(JWindow toast, Window parent) {
        if (parent == null || !parent.isShowing()) return;
        int margin = 20;
        int x = parent.getX() + parent.getWidth()  - toast.getWidth()  - margin;
        int y = parent.getY() + parent.getHeight() - toast.getHeight() - margin;
        toast.setLocation(x, y);
    }

    /**
     * Anima a opacidade de um JWindow de {@code from} até {@code to}
     * em {@code durationMs} ms. Chama {@code onDone} ao terminar.
     */
    private static void animateOpacity(JWindow w, float from, float to,
                                       int durationMs, Runnable onDone) {
        int steps    = 20;
        int delay    = durationMs / steps;
        float delta  = (to - from) / steps;
        int[] count  = {0};

        javax.swing.Timer t = new javax.swing.Timer(delay, null);
        t.addActionListener(e -> {
            count[0]++;
            float opacity = Math.max(0f, Math.min(1f, from + delta * count[0]));
            try { w.setOpacity(opacity); } catch (Exception ignored) {}
            if (count[0] >= steps) {
                t.stop();
                if (onDone != null) onDone.run();
            }
        });
        t.start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UI – Diálogos
    // ═══════════════════════════════════════════════════════════════════════════

    private static JPanel buildNotFoundPanel() {
        String os = detectOS();
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        JLabel msg = new JLabel(
                "<html><body style='width:420px;font-family:Arial;'>" +
                        "<h3 style='color:#c0392b;margin:0 0 6px 0'>FFmpeg não encontrado</h3>" +
                        "<p>A instalação automática em segundo plano não foi concluída.<br>" +
                        "O FFmpeg é necessário para conversão de áudio para Opus.</p>" +
                        "<hr>" +
                        "<p><b>Deseja tentar instalar novamente?</b><br>" +
                        "<small style='color:#555'>O instalador executará automaticamente:<br>" +
                        "<code style='background:#f4f4f4;padding:2px 6px;border-radius:3px'>"
                        + manualCommand(os) + "</code></small></p>" +
                        "<hr>" +
                        "<small style='color:#777'>Sem FFmpeg, o áudio será salvo no formato original, sem conversão Opus.</small>" +
                        "</body></html>"
        );
        panel.add(msg, BorderLayout.CENTER);
        return panel;
    }

    private static void showManualInstructions(Component parent) {
        String os  = detectOS();
        String url = installUrl(os);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Color.WHITE);

        JLabel label = new JLabel(
                "<html><body style='width:380px;font-family:Arial;'>" +
                        "<b>Instalação manual (" + os + "):</b><br><br>" +
                        "<code style='background:#f4f4f4;padding:2px 6px;border-radius:3px'>"
                        + manualCommand(os) + "</code>" +
                        "<br><br><small>Ou baixe em: <a href='" + url + "'>" + url + "</a></small>" +
                        "<br><br><small style='color:#777'>Após instalar, reinicie o aplicativo.</small>" +
                        "</body></html>"
        );

        JButton openLink = new JButton("🌐 Abrir página de download");
        styleButton(openLink, new Color(41, 128, 185));
        openLink.addActionListener(e -> {
            try { Desktop.getDesktop().browse(URI.create(url)); }
            catch (Exception ex) { JOptionPane.showMessageDialog(parent, "Acesse: " + url); }
        });

        panel.add(label,    BorderLayout.CENTER);
        panel.add(openLink, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(parent, panel,
                "Instruções de instalação", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void styleButton(JButton btn, Color bg) {
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
    }

    // ── Interface funcional para log ──────────────────────────────────────────
    @FunctionalInterface
    private interface LogSink {
        void log(String message);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Diálogo de progresso (instalação interativa)
    // ═══════════════════════════════════════════════════════════════════════════

    private static class ProgressDialog extends JDialog {
        private final JTextArea logArea;

        ProgressDialog(Component parent) {
            super(parent instanceof Window w ? w
                            : SwingUtilities.getWindowAncestor(parent),
                    "Instalando FFmpeg...", ModalityType.APPLICATION_MODAL);

            setSize(560, 400);
            setLocationRelativeTo(parent);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setResizable(false);

            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(new EmptyBorder(12, 12, 12, 12));
            root.setBackground(Color.WHITE);

            JLabel title = new JLabel(
                    "<html><b>⏳ Instalando FFmpeg automaticamente...</b><br>" +
                            "<small style='color:#555'>Por favor, aguarde. Não feche esta janela.</small></html>"
            );
            root.add(title, BorderLayout.NORTH);

            logArea = new JTextArea();
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logArea.setEditable(false);
            logArea.setBackground(new Color(20, 20, 20));
            logArea.setForeground(new Color(180, 255, 180));
            logArea.setMargin(new Insets(6, 8, 6, 8));

            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
            root.add(scroll, BorderLayout.CENTER);

            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            bar.setString("Instalando...");
            bar.setStringPainted(true);
            root.add(bar, BorderLayout.SOUTH);

            setContentPane(root);
        }

        void appendLog(String text) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(text + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }
}
