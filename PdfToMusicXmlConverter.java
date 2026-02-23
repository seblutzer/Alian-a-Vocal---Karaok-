
package com.karaoke;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Converte um PDF de partitura para MusicXML usando o Audiveris como processo externo.
 *
 * PRÉ-REQUISITOS (instalados separadamente):
 *   - Audiveris 5.3+ → https://github.com/Audiveris/audiveris/releases
 *   - Tesseract OCR  → https://github.com/UB-Mannheim/tesseract/wiki (Windows)
 *                      brew install tesseract (macOS)
 *                      apt install tesseract-ocr (Linux)
 *
 * O caminho do executável Audiveris pode ser configurado manualmente ou
 * detectado automaticamente nos locais padrão de instalação.
 *
 * Uso:
 *   PdfToMusicXmlConverter.showDialog(parentFrame, outputXmlFile -> {
 *       // outputXmlFile é o .musicxml gerado — pronto para abrir no KaraokeApp
 *   });
 */
public class PdfToMusicXmlConverter {

    // ── Configuração do caminho do Audiveris ──────────────────────────────────

    private static final String PREFS_KEY_AUDIVERIS = "audiveris_path";
    private static       String cachedAudiverisPath = null;

    // Locais padrão por SO onde o Audiveris costuma ser instalado
    private static final String[] DEFAULT_LOCATIONS = {
            // Windows
            "C:\\Program Files\\Audiveris\\bin\\Audiveris.bat",
            "C:\\Program Files (x86)\\Audiveris\\bin\\Audiveris.bat",
            System.getProperty("user.home") + "\\AppData\\Local\\Audiveris\\bin\\Audiveris.bat",
            // macOS
            "/Applications/Audiveris.app/Contents/MacOS/Audiveris",
            "/usr/local/bin/audiveris",
            // Linux
            "/usr/bin/audiveris",
            "/usr/local/bin/audiveris",
            System.getProperty("user.home") + "/.local/bin/audiveris",
    };

    // ── Ponto de entrada principal ────────────────────────────────────────────

    /**
     * Abre o diálogo completo de conversão e chama o callback com o
     * arquivo .musicxml gerado (ou não chama nada se cancelado/erro).
     *
     * @param parent   Janela pai para os diálogos
     * @param onDone   Callback chamado com o File do .musicxml resultante
     */
    public static void showDialog(JFrame parent, Consumer<File> onDone) {
        // 1. Verifica / localiza o Audiveris
        String audiverisPath = resolveAudiverisPath(parent);
        if (audiverisPath == null) return; // Usuário cancelou

        // 2. Escolhe o PDF
        JFileChooser pdfChooser = new JFileChooser();
        pdfChooser.setDialogTitle("Selecionar PDF com partitura");
        pdfChooser.setFileFilter(new FileNameExtensionFilter("PDF (*.pdf)", "pdf"));
        if (pdfChooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File pdfFile = pdfChooser.getSelectedFile();

        // 3. Escolhe pasta de saída
        JFileChooser outChooser = new JFileChooser();
        outChooser.setDialogTitle("Pasta onde salvar o MusicXML gerado");
        outChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        outChooser.setSelectedFile(pdfFile.getParentFile());
        if (outChooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File outputDir = outChooser.getSelectedFile();

        // 4. Roda a conversão em background (não trava a UI)
        runConversion(parent, audiverisPath, pdfFile, outputDir, onDone);
    }

    // ── Resolução do caminho do Audiveris ─────────────────────────────────────

    private static String resolveAudiverisPath(JFrame parent) {
        // Cache da sessão
        if (cachedAudiverisPath != null && new File(cachedAudiverisPath).exists())
            return cachedAudiverisPath;

        // Preferência salva
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences
                .userNodeForPackage(PdfToMusicXmlConverter.class);
        String saved = prefs.get(PREFS_KEY_AUDIVERIS, null);
        if (saved != null && new File(saved).exists()) {
            cachedAudiverisPath = saved;
            return saved;
        }

        // Auto-detect nos locais padrão
        for (String loc : DEFAULT_LOCATIONS) {
            if (new File(loc).exists()) {
                cachedAudiverisPath = loc;
                prefs.put(PREFS_KEY_AUDIVERIS, loc);
                return loc;
            }
        }

        // Não encontrou — pede ao usuário
        int opt = JOptionPane.showOptionDialog(
                parent,
                """
                O Audiveris não foi encontrado automaticamente.
                
                O Audiveris é necessário para converter PDF → MusicXML.
                Você pode:
                  • Baixar em: https://github.com/Audiveris/audiveris/releases
                  • Ou localizar manualmente o executável já instalado.
                """,
                "Audiveris não encontrado",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new String[]{"Localizar manualmente", "Abrir página de download", "Cancelar"},
                "Localizar manualmente"
        );

        if (opt == 1) { // Abrir download
            try {
                Desktop.getDesktop().browse(
                        new java.net.URI("https://github.com/Audiveris/audiveris/releases"));
            } catch (Exception ignored) {}
            return null;
        }
        if (opt != 0) return null; // Cancelar

        // Localizar manualmente
        JFileChooser exeChooser = new JFileChooser();
        exeChooser.setDialogTitle("Localizar executável do Audiveris");
        exeChooser.setFileFilter(new FileNameExtensionFilter(
                "Executável (*.bat, *.sh, *.exe, *)", "bat", "sh", "exe"));
        if (exeChooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;

        String path = exeChooser.getSelectedFile().getAbsolutePath();
        cachedAudiverisPath = path;
        prefs.put(PREFS_KEY_AUDIVERIS, path);
        return path;
    }

    /**
     * Permite redefinir o caminho do Audiveris manualmente (botão de config).
     */
    public static void resetAudiverisPath() {
        cachedAudiverisPath = null;
        java.util.prefs.Preferences.userNodeForPackage(PdfToMusicXmlConverter.class)
                .remove(PREFS_KEY_AUDIVERIS);
    }

    // ── Conversão em background ───────────────────────────────────────────────

    private static void runConversion(JFrame parent, String audiverisExe,
                                      File pdfFile, File outputDir,
                                      Consumer<File> onDone) {

        // Diálogo de progresso
        JDialog progressDialog = new JDialog(parent, "Convertendo PDF...", false);
        JTextArea logArea = new JTextArea(12, 55);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(20, 20, 30));
        logArea.setForeground(new Color(180, 255, 180));

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setString("Processando...");
        bar.setStringPainted(true);

        JButton cancelBtn = new JButton("✕ Cancelar");
        cancelBtn.setBackground(new Color(180, 40, 40));
        cancelBtn.setForeground(Color.WHITE);
        cancelBtn.setFocusPainted(false);

        JPanel dlgPanel = new JPanel(new BorderLayout(5, 5));
        dlgPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dlgPanel.setBackground(new Color(30, 30, 50));
        dlgPanel.add(bar,                        BorderLayout.NORTH);
        dlgPanel.add(new JScrollPane(logArea),   BorderLayout.CENTER);
        dlgPanel.add(cancelBtn,                  BorderLayout.SOUTH);

        progressDialog.setContentPane(dlgPanel);
        progressDialog.setSize(580, 320);
        progressDialog.setLocationRelativeTo(parent);

        // Thread de conversão
        Thread[] workerRef = new Thread[1];

        cancelBtn.addActionListener(e -> {
            if (workerRef[0] != null) workerRef[0].interrupt();
            progressDialog.dispose();
        });

        workerRef[0] = new Thread(() -> {
            Consumer<String> log = msg -> SwingUtilities.invokeLater(() -> {
                logArea.append(msg + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });

            try {
                // ── Monta o comando CLI do Audiveris ──────────────────────────
                // audiveris -batch -transcribe -export -output <dir> -- <pdf>
                List<String> cmd = new ArrayList<>();
                cmd.add(audiverisExe);
                cmd.add("-batch");
                cmd.add("-transcribe");
                cmd.add("-export");
                cmd.add("-output");
                cmd.add(outputDir.getAbsolutePath());
                cmd.add("--");
                cmd.add(pdfFile.getAbsolutePath());

                log.accept("▶ Comando: " + String.join(" ", cmd));
                log.accept("⏳ Iniciando reconhecimento óptico (OMR)...");
                log.accept("   (Pode levar de 30s a alguns minutos dependendo do PDF)");
                log.accept("");

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);   // junta stdout + stderr
                pb.directory(outputDir);

                Process process = pb.start();

                // Lê saída em tempo real
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted()) {
                            process.destroyForcibly();
                            log.accept("\n⛔ Cancelado pelo usuário.");
                            return;
                        }
                        log.accept(line);
                    }
                }

                boolean finished = process.waitFor(10, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    log.accept("\n⚠ Timeout: o processo demorou mais de 10 minutos.");
                    SwingUtilities.invokeLater(() -> progressDialog.dispose());
                    return;
                }

                int exitCode = process.exitValue();
                log.accept("\n── Processo finalizado (código " + exitCode + ") ──");

                // ── Localiza o .musicxml gerado ───────────────────────────────
                // Audiveris cria: <outputDir>/<pdfNameSemExtensao>/<pdfNameSemExtensao>.musicxml
                String baseName = pdfFile.getName().replaceAll("(?i)\\.pdf$", "");
                File   xmlFile  = findGeneratedXml(outputDir, baseName);

                if (xmlFile == null || !xmlFile.exists()) {
                    log.accept("❌ Arquivo .musicxml não encontrado em: " + outputDir);
                    log.accept("   Verifique se o Audiveris finalizou sem erros acima.");
                    SwingUtilities.invokeLater(() -> {
                        bar.setIndeterminate(false);
                        bar.setString("Falhou");
                        cancelBtn.setText("Fechar");
                    });
                    return;
                }

                log.accept("✅ MusicXML gerado: " + xmlFile.getAbsolutePath());

                // ── Sucesso ───────────────────────────────────────────────────
                File finalXml = xmlFile;
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue(100);
                    bar.setString("✓ Concluído!");
                    cancelBtn.setText("Fechar");
                    cancelBtn.setBackground(new Color(39, 174, 96));
                    cancelBtn.removeActionListener(cancelBtn.getActionListeners()[0]);
                    cancelBtn.addActionListener(ev -> progressDialog.dispose());

                    // Pergunta se já quer abrir no KaraokeApp
                    int open = JOptionPane.showConfirmDialog(
                            progressDialog,
                            "MusicXML gerado com sucesso!\n\n" + finalXml.getName()
                                    + "\n\nDeseja abrir agora no KaraokeApp?",
                            "Conversão concluída",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.INFORMATION_MESSAGE);

                    progressDialog.dispose();
                    if (open == JOptionPane.YES_OPTION) onDone.accept(finalXml);
                });

            } catch (InterruptedException ie) {
                log.accept("⛔ Cancelado.");
                SwingUtilities.invokeLater(() -> progressDialog.dispose());
            } catch (Exception ex) {
                String err = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    logArea.append("\n❌ Erro: " + err + "\n");
                    bar.setIndeterminate(false);
                    bar.setString("Erro");
                    cancelBtn.setText("Fechar");
                });
            }
        }, "audiveris-converter");

        workerRef[0].setDaemon(true);
        workerRef[0].start();
        progressDialog.setVisible(true);
    }

    // ── Localiza o .musicxml produzido pelo Audiveris ─────────────────────────

    /**
     * Audiveris pode gerar o arquivo em caminhos ligeiramente diferentes
     * dependendo da versão. Tenta as variações mais comuns.
     */
    private static File findGeneratedXml(File outputDir, String baseName) {
        // Variação 1: <outDir>/<baseName>/<baseName>.musicxml  (mais comum, v5.3+)
        File v1 = new File(outputDir, baseName + File.separator + baseName + ".musicxml");
        if (v1.exists()) return v1;

        // Variação 2: <outDir>/<baseName>.musicxml
        File v2 = new File(outputDir, baseName + ".musicxml");
        if (v2.exists()) return v2;

        // Variação 3: busca recursiva — qualquer .musicxml dentro de outputDir
        try {
            return Files.walk(outputDir.toPath(), 3)
                    .filter(p -> p.toString().toLowerCase().endsWith(".musicxml"))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
