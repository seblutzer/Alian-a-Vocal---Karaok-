
package com.karaoke;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Painel lateral que exibe e gerencia a biblioteca de músicas.
 *
 * ── Mudanças em relação à versão MP3-only ────────────────────────────────────
 *  • SaveRequest.mp3File  → audioFile  (qualquer formato)
 *  • FFmpeg presente → converte para Opus antes de salvar
 *  • FFmpeg ausente  → exibe diálogo de instalação → salva áudio original
 *  • "Comprimir Biblioteca" virou "Converter para Opus" e pula quem já é .opus
 *  • Barra de progresso reutilizada para salvar e para conversão em lote
 */
public class LibraryPanel extends JPanel {

    // ── Callbacks para o KaraokeApp ───────────────────────────────────────────
    public interface Listener {
        void onLoad(MusicLibrary.SavedMusic music);
        SaveRequest onSaveRequested();
    }

    public static class SaveRequest {
        public File   xmlFile;
        public File   audioFile;       // qualquer formato: mp3, wav, flac, ogg…
        public double realDurationMin;
        public double offsetSeconds;
        public int    selectedVoice;
    }

    // ── Estado interno ────────────────────────────────────────────────────────
    private final Listener                                  listener;
    private final DefaultListModel<MusicLibrary.SavedMusic> listModel   = new DefaultListModel<>();
    private final JList<MusicLibrary.SavedMusic>            musicList   = new JList<>(listModel);
    private final JTextField                                nameField   = new JTextField();
    private final JLabel                                    statusLabel = new JLabel(" ");
    private final JProgressBar conversionProgress = new JProgressBar(0, 100);

    private JButton saveBtn;
    private JButton convertAllBtn;

    private final JButton loadBtn   = smallButton("📂 Carregar",  new Color(39, 174, 96));
    private final JButton renameBtn = smallButton("✏ Renomear",  new Color(41, 128, 185));
    private final JButton deleteBtn = smallButton("🗑 Deletar",   new Color(150, 40, 40));

    // ── Construtor ────────────────────────────────────────────────────────────
    public LibraryPanel(Listener listener) {
        this.listener = listener;
        setLayout(new BorderLayout(4, 4));
        setBackground(new Color(18, 28, 52));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(60, 60, 100)),
                new EmptyBorder(6, 6, 6, 6)));
        setPreferredSize(new Dimension(220, 0));

        add(buildHeader(),   BorderLayout.NORTH);
        add(buildCenter(),   BorderLayout.CENTER);
        add(buildSaveArea(), BorderLayout.SOUTH);

        updateButtonStates();
        refresh();
    }

    // ── Construção da UI ──────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(new Color(18, 28, 52));

        JLabel title = new JLabel("🎵 BIBLIOTECA");
        title.setForeground(new Color(255, 215, 0));
        title.setFont(new Font("Arial", Font.BOLD, 13));
        p.add(title, BorderLayout.WEST);

        JButton refreshBtn = smallButton("🔄", new Color(52, 73, 94));
        refreshBtn.setToolTipText("Atualizar lista");
        refreshBtn.addActionListener(e -> refresh());
        p.add(refreshBtn, BorderLayout.EAST);
        return p;
    }

    private JPanel buildCenter() {
        musicList.setBackground(new Color(30, 42, 70));
        musicList.setForeground(Color.WHITE);
        musicList.setFont(new Font("Arial", Font.PLAIN, 12));
        musicList.setSelectionBackground(new Color(39, 174, 96));
        musicList.setSelectionForeground(Color.WHITE);
        musicList.setFixedCellHeight(28);
        musicList.setBorder(new EmptyBorder(2, 4, 2, 4));
        musicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        musicList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateButtonStates();
        });
        musicList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) loadSelected();
            }
        });

        JScrollPane scroll = new JScrollPane(musicList);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 100)));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        loadBtn.setToolTipText("Carrega a música selecionada");
        loadBtn.addActionListener(e -> loadSelected());

        renameBtn.setToolTipText("Renomeia a música selecionada");
        renameBtn.addActionListener(e -> renameSelected());

        deleteBtn.setToolTipText("Remove permanentemente a música da biblioteca");
        deleteBtn.addActionListener(e -> deleteSelected());

        JButton openFolderBtn = smallButton("📂 Abrir Pasta", new Color(100, 80, 20));
        openFolderBtn.setToolTipText("Abre ~/KaraokeMusicas no explorador de arquivos");
        openFolderBtn.addActionListener(e -> openLibraryFolder());

        // Grade 2×2
        JPanel btnGrid = new JPanel(new GridLayout(2, 2, 4, 3));
        btnGrid.setBackground(new Color(18, 28, 52));
        btnGrid.setBorder(new EmptyBorder(4, 0, 0, 0));
        btnGrid.add(openFolderBtn);
        btnGrid.add(loadBtn);
        btnGrid.add(renameBtn);
        btnGrid.add(deleteBtn);

        // Tooltip dinâmico conforme FFmpeg disponível
        boolean ffmpegOk = FFmpegDetector.isAvailable();
        convertAllBtn = smallButton("🗜 Converter para Opus", new Color(80, 50, 120));
        convertAllBtn.setToolTipText(ffmpegOk
                ? "Converte todos os áudios da biblioteca para Opus 64 kbps (~70% menor)"
                : "FFmpeg não encontrado — instale-o para habilitar esta opção");
        convertAllBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        convertAllBtn.addActionListener(e -> convertAllToOpus());

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
        btnPanel.setBackground(new Color(18, 28, 52));
        btnPanel.add(btnGrid);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(convertAllBtn);

        JPanel center = new JPanel(new BorderLayout(0, 0));
        center.setBackground(new Color(18, 28, 52));
        center.add(scroll,    BorderLayout.CENTER);
        center.add(btnPanel,  BorderLayout.SOUTH);
        return center;
    }

    private JPanel buildSaveArea() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(18, 28, 52));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 100)),
                new EmptyBorder(6, 0, 2, 0)));

        JLabel saveTitle = new JLabel("── Salvar música ──");
        saveTitle.setForeground(new Color(170, 170, 200));
        saveTitle.setFont(new Font("Arial", Font.ITALIC, 11));
        saveTitle.setAlignmentX(LEFT_ALIGNMENT);
        p.add(saveTitle);
        p.add(Box.createVerticalStrut(4));

        JLabel nameLbl = new JLabel("Nome:");
        nameLbl.setForeground(Color.WHITE);
        nameLbl.setFont(new Font("Arial", Font.PLAIN, 11));
        nameLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(nameLbl);

        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        nameField.setFont(new Font("Arial", Font.PLAIN, 12));
        nameField.setAlignmentX(LEFT_ALIGNMENT);
        nameField.addActionListener(e -> saveCurrentMusic());
        p.add(nameField);
        p.add(Box.createVerticalStrut(5));

        saveBtn = new JButton("💾 Salvar na Biblioteca");
        saveBtn.setBackground(new Color(52, 73, 150));
        saveBtn.setForeground(Color.WHITE);
        saveBtn.setFont(new Font("Arial", Font.BOLD, 11));
        saveBtn.setFocusPainted(false);
        saveBtn.setBorderPainted(false);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        saveBtn.setAlignmentX(LEFT_ALIGNMENT);
        saveBtn.addActionListener(e -> saveCurrentMusic());
        p.add(saveBtn);
        p.add(Box.createVerticalStrut(4));

        conversionProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        conversionProgress.setAlignmentX(LEFT_ALIGNMENT);
        conversionProgress.setStringPainted(true);
        conversionProgress.setBackground(new Color(30, 42, 70));
        conversionProgress.setForeground(new Color(39, 174, 96));
        conversionProgress.setFont(new Font("Arial", Font.PLAIN, 9));
        conversionProgress.setVisible(false);
        p.add(conversionProgress);
        p.add(Box.createVerticalStrut(2));

        statusLabel.setForeground(new Color(150, 220, 150));
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 10));
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        p.add(statusLabel);
        return p;
    }

    // ── Refresh / helpers públicos ────────────────────────────────────────────

    public void refresh() {
        MusicLibrary.SavedMusic selected = musicList.getSelectedValue();
        listModel.clear();
        List<MusicLibrary.SavedMusic> all = MusicLibrary.listAll();
        for (MusicLibrary.SavedMusic m : all) listModel.addElement(m);

        if (selected != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).name.equals(selected.name)) {
                    musicList.setSelectedIndex(i);
                    break;
                }
            }
        }
        setStatus(all.isEmpty() ? "Nenhuma música salva." : all.size() + " música(s).");
        updateButtonStates();
    }

    public void reloadList() { refresh(); }

    public MusicLibrary.SavedMusic getSelectedMusic() {
        return musicList.getSelectedValue();
    }

    public void suggestName(String name) {
        if (nameField.getText().isBlank()) nameField.setText(name);
    }

    public void setName(String name) {
        nameField.setText(name);
    }

    // ── Ações da lista ────────────────────────────────────────────────────────

    private void loadSelected() {
        MusicLibrary.SavedMusic m = musicList.getSelectedValue();
        if (m == null) { setStatus("⚠ Selecione uma música."); return; }
        listener.onLoad(m);
        setStatus("✓ Carregado: " + m.name);
    }

    private void renameSelected() {
        MusicLibrary.SavedMusic m = musicList.getSelectedValue();
        if (m == null) { setStatus("⚠ Selecione uma música."); return; }

        String newName = (String) JOptionPane.showInputDialog(
                this, "Novo nome para a música:", "Renomear música",
                JOptionPane.PLAIN_MESSAGE, null, null, m.name);
        if (newName == null) return;
        newName = newName.trim();

        if (newName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "O nome não pode estar vazio.",
                    "Nome inválido", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newName.equals(m.name)) return;

        try {
            MusicLibrary.rename(m, newName);
            if (nameField.getText().trim().equalsIgnoreCase(m.name))
                nameField.setText(newName);
            refresh();
            selectByName(newName);
            setStatus("✓ Renomeado para \"" + newName + "\"");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Erro ao renomear:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        MusicLibrary.SavedMusic m = musicList.getSelectedValue();
        if (m == null) { setStatus("⚠ Selecione uma música."); return; }

        int opt = JOptionPane.showConfirmDialog(this,
                "Excluir permanentemente:\n\n  \"" + m.name +
                        "\"\n\nEsta ação não pode ser desfeita.",
                "Confirmar exclusão", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.YES_OPTION) return;

        try {
            String deletedName = m.name;
            MusicLibrary.delete(m);
            refresh();
            setStatus("🗑 \"" + deletedName + "\" excluída.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Erro ao excluir:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openLibraryFolder() {
        File root = new File(
                System.getProperty("user.home") + File.separator + "KaraokeMusicas");
        if (!root.exists()) root.mkdirs();
        try {
            Desktop.getDesktop().open(root);
        } catch (IOException ex) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if      (os.contains("win")) pb = new ProcessBuilder("explorer.exe", root.getAbsolutePath());
                else if (os.contains("mac")) pb = new ProcessBuilder("open",         root.getAbsolutePath());
                else                         pb = new ProcessBuilder("xdg-open",     root.getAbsolutePath());
                pb.start();
            } catch (IOException ex2) {
                JOptionPane.showMessageDialog(this,
                        "Não foi possível abrir a pasta.\nCaminho: " + root.getAbsolutePath(),
                        "Erro", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ── Conversão em lote → Opus ──────────────────────────────────────────────

    /**
     * Converte todos os áudios da biblioteca que ainda NÃO são Opus.
     * Requer FFmpeg — exibe diálogo de instalação se ausente.
     */
    private void convertAllToOpus() {
        if (!FFmpegDetector.isAvailable()) {
            FFmpegDetector.showInstallDialog(this);
            return;
        }

        List<MusicLibrary.SavedMusic> all = MusicLibrary.listAll();
        List<MusicLibrary.SavedMusic> toProcess = all.stream()
                .filter(m -> m.audioFile != null && m.audioFile.exists())
                .filter(m -> !m.audioExtension().equals("opus"))
                .toList();

        if (toProcess.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Todos os áudios já estão em formato Opus.\nNada a converter.",
                    "Biblioteca otimizada ✓", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                toProcess.size() + " música(s) serão convertidas para Opus (64 kbps).\n" +
                        "Os arquivos originais serão substituídos.\n\nContinuar?",
                "Converter para Opus",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // [0]=processadas  [1]=erros  [2]=bytes economizados
        long[] stats = {0, 0, 0};
        setBulkConvertState(true);

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                int total = toProcess.size();
                for (int i = 0; i < total; i++) {
                    MusicLibrary.SavedMusic music = toProcess.get(i);
                    int idx = i + 1;

                    SwingUtilities.invokeLater(() -> {
                        conversionProgress.setMaximum(total);
                        conversionProgress.setValue(idx - 1);
                        conversionProgress.setString(idx + "/" + total + " — " + music.name);
                    });
                    publish(idx + "/" + total + " — " + music.name);

                    long originalSize = music.audioFile.length();
                    File tempOpus = null;
                    try {
                        tempOpus = AudioConverter.createTempOpus();
                        AudioConverter.convertToOpus(music.audioFile, tempOpus, null);

                        // Re-salva na biblioteca usando o novo .opus
                        MusicLibrary.save(music.name, music.xmlFile, tempOpus,
                                music.realDurationMin, music.offsetSeconds,
                                music.selectedVoice);

                        // Calcula espaço economizado a partir do arquivo já gravado
                        File savedOpus = new File(music.folder, "musica.opus");
                        if (savedOpus.exists())
                            stats[2] += originalSize - savedOpus.length();

                        stats[0]++;

                    } catch (Exception ex) {
                        stats[1]++;
                        System.err.println("Erro ao converter \""
                                + music.name + "\": " + ex.getMessage());
                    } finally {
                        if (tempOpus != null && tempOpus.exists()) tempOpus.delete();
                    }
                }
                return null;
            }

            @Override protected void process(List<String> chunks) {
                setStatusImmediate("⚙ " + chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                setBulkConvertState(false);
                double savedMb = stats[2] / (1024.0 * 1024.0);
                String summary = String.format(
                        "✅ Concluído!\n\n" +
                                "  Convertidas : %d música(s) → Opus\n" +
                                "  Erros       : %d\n" +
                                "  Economizado : %.1f MB",
                        stats[0], stats[1], savedMb);
                JOptionPane.showMessageDialog(LibraryPanel.this, summary,
                        "Conversão concluída",
                        stats[1] > 0
                                ? JOptionPane.WARNING_MESSAGE
                                : JOptionPane.INFORMATION_MESSAGE);
                setStatus(String.format("✓ %d convertidas · %.1f MB livres",
                        stats[0], savedMb));
                refresh();
            }
        }.execute();
    }

    // ── Salvar música atual ───────────────────────────────────────────────────

    /**
     * Fluxo de salvamento:
     *  1. Sem áudio              → salva só o XML
     *  2. Com áudio + FFmpeg     → converte para Opus em background → salva .opus
     *  3. Com áudio + sem FFmpeg → exibe aviso → salva áudio original
     */
    private void saveCurrentMusic() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("⚠ Digite um nome.");
            nameField.requestFocus();
            return;
        }

        SaveRequest req = listener.onSaveRequested();
        if (req == null) {
            setStatus("⚠ Carregue um XML primeiro.");
            return;
        }

        // Confirmação de sobrescrita
        boolean exists = MusicLibrary.listAll().stream()
                .anyMatch(m -> m.name.equalsIgnoreCase(name));
        if (exists) {
            int opt = JOptionPane.showConfirmDialog(this,
                    "\"" + name + "\" já existe. Sobrescrever?",
                    "Confirmar", JOptionPane.YES_NO_OPTION);
            if (opt != JOptionPane.YES_OPTION) return;
        }

        // ── Sem áudio: salva direto ───────────────────────────────────────────
        if (req.audioFile == null) {
            executeSave(name, req, null);
            return;
        }

        // ── Com áudio mas sem FFmpeg: avisa e salva original ─────────────────
        if (!FFmpegDetector.isAvailable()) {
            FFmpegDetector.showInstallDialog(this);
            setStatus("⚠ Salvo sem conversão (FFmpeg ausente).");
            executeSave(name, req, req.audioFile);
            return;
        }

        // ── Com áudio e FFmpeg: converte para Opus em background ──────────────
        setSavingState(true);
        setStatus("⏳ Convertendo para Opus…");

        new SwingWorker<File, Integer>() {
            @Override
            protected File doInBackground() throws Exception {
                File tempOpus = AudioConverter.createTempOpus();
                AudioConverter.convertToOpus(req.audioFile, tempOpus,
                        progress -> publish(progress));
                return tempOpus;
            }

            @Override protected void process(List<Integer> chunks) {
                int latest = chunks.get(chunks.size() - 1);
                conversionProgress.setValue(latest);
                conversionProgress.setString("Convertendo… " + latest + "%");
            }

            @Override
            protected void done() {
                setSavingState(false);
                try {
                    File opusFile = get();
                    setStatus("✓ Conversão concluída! Salvando…");
                    executeSave(name, req, opusFile);
                    opusFile.delete();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("❌ Falha na conversão.");
                    JOptionPane.showMessageDialog(LibraryPanel.this,
                            "Erro ao converter o áudio:\n" + cause.getMessage(),
                            "Erro de conversão", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Persiste no disco via MusicLibrary. */
    private void executeSave(String name, SaveRequest req, File audioFile) {
        try {
            MusicLibrary.save(name,
                    req.xmlFile,
                    audioFile,
                    req.realDurationMin,
                    req.offsetSeconds,
                    req.selectedVoice);
            refresh();
            selectByName(name);
            setStatus("✓ Salvo: " + name);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao salvar:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Helpers de estado de UI ───────────────────────────────────────────────

    /** Bloqueia a UI durante o salvamento individual. */
    private void setSavingState(boolean saving) {
        saveBtn.setEnabled(!saving);
        nameField.setEnabled(!saving);
        convertAllBtn.setEnabled(!saving);
        conversionProgress.setMaximum(100);
        conversionProgress.setVisible(saving);
        if (saving) {
            conversionProgress.setValue(0);
            conversionProgress.setString("Aguardando…");
        }
    }

    /** Bloqueia a UI durante a conversão em lote. */
    private void setBulkConvertState(boolean converting) {
        convertAllBtn.setEnabled(!converting);
        saveBtn.setEnabled(!converting);
        loadBtn.setEnabled(!converting);
        renameBtn.setEnabled(!converting);
        deleteBtn.setEnabled(!converting);
        conversionProgress.setVisible(converting);
        if (converting) {
            conversionProgress.setValue(0);
            conversionProgress.setString("Iniciando…");
        }
    }

    private void updateButtonStates() {
        boolean hasSel = (musicList.getSelectedValue() != null);
        loadBtn.setEnabled(hasSel);
        renameBtn.setEnabled(hasSel);
        deleteBtn.setEnabled(hasSel);
    }

    private void selectByName(String name) {
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).name.equalsIgnoreCase(name)) {
                musicList.setSelectedIndex(i);
                musicList.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    /** Exibe status por 4 s e depois limpa. */
    private void setStatus(String msg) {
        statusLabel.setText(msg);
        new javax.swing.Timer(4000, e -> {
            statusLabel.setText(" ");
            ((javax.swing.Timer) e.getSource()).stop();
        }).start();
    }

    /** Status imediato sem timer (usado durante operações longas). */
    private void setStatusImmediate(String msg) {
        statusLabel.setText(msg);
    }

    private static JButton smallButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Arial", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        return btn;
    }
}
