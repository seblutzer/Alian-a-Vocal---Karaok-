package com.karaoke;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import com.karaoke.GitHubSyncManager;

public class LibraryPanel extends JPanel {

    public interface Listener {
        void onLoad(MusicLibrary.SavedMusic music);
        SaveRequest onSaveRequested();
    }

    public static class SaveRequest {
        public File   xmlFile;
        public File   audioFile;
        public double realDurationMin;
        public double offsetSeconds;
        public int    selectedVoice;
    }

    private final Listener                                  listener;
    private final DefaultListModel<MusicLibrary.SavedMusic> listModel   = new DefaultListModel<>();
    private final JList<MusicLibrary.SavedMusic>            musicList   = new JList<>(listModel);
    private final JTextField                                nameField   = new JTextField();
    private final JTextField                                authorField = new JTextField();
    private final JLabel                                    statusLabel = new JLabel(" ");
    private final JProgressBar conversionProgress = new JProgressBar(0, 100);
    private final JComboBox<String>                         coralCombo  = new JComboBox<>();

    private JButton saveBtn;
    private JButton convertAllBtn;
    private String currentCoral = null;

    private long pressTime = 0;
    private int  pressIndex = -1;
    private static final long LONG_CLICK_MS = 600;

    private enum SortMode {
        BY_NAME("🔤 Por Nome (Nome - Autor)"),
        BY_AUTHOR("✍ Por Autor (Autor - Nome)");

        private final String label;
        SortMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private SortMode currentSortMode = SortMode.BY_NAME;

    public LibraryPanel(Listener listener) {
        this.listener = listener;
        setLayout(new BorderLayout(4, 4));
        setBackground(new Color(18, 28, 52));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(60, 60, 100)),
                new EmptyBorder(6, 6, 6, 6)));
        setPreferredSize(new Dimension(240, 0));

        add(buildHeader(),   BorderLayout.NORTH);
        add(buildCenter(),   BorderLayout.CENTER);
        add(buildSaveArea(), BorderLayout.SOUTH);

        refresh();
    }

    // ── Construção da UI ──────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(new Color(18, 28, 52));
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // Título + botão refresh
        JPanel titleRow = new JPanel(new BorderLayout(4, 0));
        titleRow.setBackground(new Color(18, 28, 52));
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel title = new JLabel("🎵 BIBLIOTECA");
        title.setForeground(new Color(255, 215, 0));
        title.setFont(new Font("Segoe UI Symbol", Font.BOLD, 13));
        titleRow.add(title, BorderLayout.WEST);

        // Botão toggle de ordenação
        JButton sortToggleBtn = new JButton("Autor - Música");
        sortToggleBtn.setBackground(new Color(60, 70, 110));
        sortToggleBtn.setForeground(Color.WHITE);
        sortToggleBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
        sortToggleBtn.setFocusPainted(false);
        sortToggleBtn.setBorderPainted(false);
        sortToggleBtn.setPreferredSize(new Dimension(130, 24));
        sortToggleBtn.setToolTipText("Clique para alternar ordem de exibição");
        sortToggleBtn.addActionListener(e -> {
            if (currentSortMode == SortMode.BY_AUTHOR) {
                currentSortMode = SortMode.BY_NAME;
                sortToggleBtn.setText("Música - Autor");
            } else {
                currentSortMode = SortMode.BY_AUTHOR;
                sortToggleBtn.setText("Autor - Música");
            }
            refreshMusicList();
        });
        titleRow.add(sortToggleBtn, BorderLayout.CENTER);

        JButton refreshBtn = smallButton("🔄", new Color(52, 73, 94));
        refreshBtn.setToolTipText("Atualizar lista");
        refreshBtn.addActionListener(e -> {
            refreshBtn.setEnabled(false);
            refresh();

            // ✅ NOVO: Verificar se é primeira sincronização
            new Thread(() -> {
                try {
                    // Tentar baixar manifest
                    ManifestCreator.downloadManifest();

                    // ✅ Manifest existe - fazer sync normal
                    SwingUtilities.invokeLater(() -> {
                        syncWithGitHubNormal(refreshBtn);
                    });

                } catch (Exception ex) {
                    // ✅ Manifest não existe - gerar completo (primeira vez)
                    System.err.println("⚠️  Primeira sincronização detectada!");
                    SwingUtilities.invokeLater(() -> {
                        generateManifestAndSync(refreshBtn);
                    });
                }
            }).start();
        });
        titleRow.add(refreshBtn, BorderLayout.EAST);

        p.add(titleRow);


        // Seletor de coral
        JPanel coralPanel = new JPanel();
        coralPanel.setLayout(new BoxLayout(coralPanel, BoxLayout.X_AXIS));
        coralPanel.setBackground(new Color(18, 28, 52));
        coralPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JLabel coralLabel = new JLabel("Grupo:");
        coralLabel.setForeground(Color.WHITE);
        coralLabel.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));

        coralCombo.setBackground(new Color(30, 42, 70));
        coralCombo.setForeground(Color.WHITE);
        coralCombo.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
        coralCombo.setMaximumRowCount(10);
        coralCombo.addActionListener(e -> {
            String selected = (String) coralCombo.getSelectedItem();
            if (selected != null && !selected.equals(currentCoral)) {
                currentCoral = selected;
                refreshMusicList();
            }
        });

        JButton addCoralBtn = smallButton("+", new Color(52, 73, 94));
        addCoralBtn.setToolTipText("Novo coral");
        addCoralBtn.addActionListener(e -> createNewCoral());

        coralPanel.add(coralLabel);
        coralPanel.add(Box.createHorizontalStrut(4));
        coralPanel.add(coralCombo);
        coralPanel.add(Box.createHorizontalGlue()); // Ocupa espaço vazio
        coralPanel.add(addCoralBtn);

        p.add(Box.createVerticalStrut(6));
        p.add(coralPanel);

        return p;
    }

    private JPanel buildCenter() {
        musicList.setBackground(new Color(30, 42, 70));
        musicList.setForeground(Color.WHITE);
        musicList.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
        musicList.setSelectionBackground(new Color(39, 174, 96));
        musicList.setSelectionForeground(Color.WHITE);
        musicList.setFixedCellHeight(26);
        musicList.setBorder(new EmptyBorder(2, 4, 2, 4));
        musicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        musicList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int idx = musicList.locationToIndex(e.getPoint());
                if (idx < 0) return;

                if (SwingUtilities.isRightMouseButton(e)) {
                    musicList.setSelectedIndex(idx);
                    showContextMenu(e.getComponent(), e.getX(), e.getY());
                    return;
                }

                if (idx == musicList.getSelectedIndex()) {
                    pressTime  = System.currentTimeMillis();
                    pressIndex = idx;
                } else {
                    pressTime = 0;
                    pressIndex = -1;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) return;

                int idx = musicList.locationToIndex(e.getPoint());
                if (idx < 0 || idx != pressIndex) { pressTime = 0; return; }

                long elapsed = System.currentTimeMillis() - pressTime;
                pressTime = 0;

                if (elapsed >= LONG_CLICK_MS) {
                    renameSelected();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    pressTime = 0;
                    loadSelected();
                }
            }
        });

        musicList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) deleteSelected();
            }
        });

        // Adicionar este código no método buildCenter(), logo após o musicList.addKeyListener

        musicList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                MusicLibrary.SavedMusic selected = musicList.getSelectedValue();
                if (selected != null) {
                    authorField.setText(selected.author);
                    // Extrai o nome da música: usa o lado do " - " que NÃO é o autor
                    String[] parts = selected.name.split(" - ", 2);
                    String musicName = (parts.length == 2)
                            ? (parts[0].trim().equals(selected.author) ? parts[1].trim() : parts[0].trim())
                            : selected.name;
                    nameField.setText(musicName);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(musicList);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 100)));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(new Color(18, 28, 52));
        center.add(scroll, BorderLayout.CENTER);
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
        saveTitle.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 11));
        saveTitle.setAlignmentX(LEFT_ALIGNMENT);
        p.add(saveTitle);
        p.add(Box.createVerticalStrut(4));

        // Exibe o coral selecionado
        JLabel coralDisplayLbl = new JLabel("Coral: (será exibido aqui)");
        coralDisplayLbl.setForeground(new Color(100, 200, 100));
        coralDisplayLbl.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        coralDisplayLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(coralDisplayLbl);
        p.add(Box.createVerticalStrut(4));

        // Autor
        JLabel authorLbl = new JLabel("Autor/Compositor:");
        authorLbl.setForeground(Color.WHITE);
        authorLbl.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 10));
        authorLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(authorLbl);

        authorField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        authorField.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
        authorField.setAlignmentX(LEFT_ALIGNMENT);
        authorField.putClientProperty("JTextField.placeholderText", "Ex: Mozart");
        p.add(authorField);
        p.add(Box.createVerticalStrut(3));

        // Nome da música
        JLabel nameLbl = new JLabel("Nome da música:");
        nameLbl.setForeground(Color.WHITE);
        nameLbl.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 10));
        nameLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(nameLbl);

        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        nameField.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
        nameField.setAlignmentX(LEFT_ALIGNMENT);
        nameField.putClientProperty("JTextField.placeholderText", "Ex: Réquiem");
        nameField.addActionListener(e -> saveCurrentMusic());
        p.add(nameField);
        p.add(Box.createVerticalStrut(5));

        // Botões
        convertAllBtn = new JButton("🗜 Converter");
        convertAllBtn.setBackground(new Color(80, 50, 120));
        convertAllBtn.setForeground(Color.WHITE);
        convertAllBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
        convertAllBtn.setFocusPainted(false);
        convertAllBtn.setBorderPainted(false);
        convertAllBtn.setToolTipText("Converte todos os áudios para Opus 64 kbps");
        convertAllBtn.addActionListener(e -> convertAllToOpus());

        saveBtn = new JButton("💾 Salvar");
        saveBtn.setBackground(new Color(52, 73, 150));
        saveBtn.setForeground(Color.WHITE);
        saveBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
        saveBtn.setFocusPainted(false);
        saveBtn.setBorderPainted(false);
        saveBtn.addActionListener(e -> saveCurrentMusic());

        JPanel btnRow = new JPanel(new GridLayout(1, 2, 4, 0));
        btnRow.setBackground(new Color(18, 28, 52));
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        btnRow.setAlignmentX(LEFT_ALIGNMENT);
        btnRow.add(convertAllBtn);
        btnRow.add(saveBtn);
        p.add(btnRow);
        p.add(Box.createVerticalStrut(4));

        // Barra de progresso
        conversionProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        conversionProgress.setAlignmentX(LEFT_ALIGNMENT);
        conversionProgress.setStringPainted(true);
        conversionProgress.setBackground(new Color(30, 42, 70));
        conversionProgress.setForeground(new Color(39, 174, 96));
        conversionProgress.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 8));
        conversionProgress.setVisible(false);
        p.add(conversionProgress);
        p.add(Box.createVerticalStrut(2));

        statusLabel.setForeground(new Color(150, 220, 150));
        statusLabel.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 9));
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        p.add(statusLabel);

        // Atualiza o label do coral quando muda seleção
        coralCombo.addActionListener(e -> {
            String coral = (String) coralCombo.getSelectedItem();
            coralDisplayLbl.setText("🎤 Coral: " + (coral != null ? coral : "Nenhum"));
        });

        // Inicializa com o coral atual
        String initialCoral = (String) coralCombo.getSelectedItem();
        coralDisplayLbl.setText("🎤 Coral: " + (initialCoral != null ? initialCoral : "Nenhum"));

        return p;
    }

    // ── Refresh e atualização ─────────────────────────────────────────────────

    public void refresh() {
        // Atualiza lista de corais
        List<String> corals = MusicLibrary.listAllCorals();

        String prevSelected = (String) coralCombo.getSelectedItem();
        coralCombo.removeAllItems();

        for (String coral : corals) {
            coralCombo.addItem(coral);
        }

        // Seleciona o primeiro coral se houver
        if (coralCombo.getItemCount() > 0) {
            if (prevSelected != null) {
                coralCombo.setSelectedItem(prevSelected);
            } else {
                coralCombo.setSelectedIndex(0);
                currentCoral = (String) coralCombo.getSelectedItem();
            }
        }

        refreshMusicList();
    }

    private void refreshMusicList() {
        MusicLibrary.SavedMusic selected = musicList.getSelectedValue();
        listModel.clear();

        if (currentCoral != null) {
            List<MusicLibrary.SavedMusic> musics = MusicLibrary.listMusicsByCoral(currentCoral);

            // Ordena conforme modo selecionado
            musics.sort((m1, m2) -> {
                if (currentSortMode == SortMode.BY_AUTHOR) {
                    // Por autor: "Autor - Nome"
                    return m1.author.compareToIgnoreCase(m2.author);
                } else {
                    // Por nome: "Nome - Autor"
                    String name1 = m1.name.split(" - ")[1].trim();
                    String name2 = m2.name.split(" - ")[1].trim();
                    return name1.compareToIgnoreCase(name2);
                }
            });

            for (MusicLibrary.SavedMusic m : musics) {
                // Exibe conforme modo selecionado
                MusicLibrary.SavedMusic display = new MusicLibrary.SavedMusic();
                display.name = (currentSortMode == SortMode.BY_AUTHOR)
                        ? m.name  // "Autor - Nome"
                        : (m.name.split(" - ")[1].trim() + " - " + m.author);  // "Nome - Autor"
                display.coral = m.coral;
                display.author = m.author;
                display.folder = m.folder;
                display.xmlFile = m.xmlFile;
                display.audioFile = m.audioFile;
                display.realDurationMin = m.realDurationMin;
                display.offsetSeconds = m.offsetSeconds;
                display.selectedVoice = m.selectedVoice;

                listModel.addElement(display);
            }

            if (selected != null && selected.coral.equals(currentCoral)) {
                for (int i = 0; i < listModel.size(); i++) {
                    if (listModel.get(i).author.equals(selected.author) &&
                            listModel.get(i).name.contains(selected.name.split(" - ")[1])) {
                        musicList.setSelectedIndex(i);
                        break;
                    }
                }
            }

            setStatus(musics.isEmpty() ? "Nenhuma música neste coral." : musics.size() + " música(s).");
        }
    }

    private void createNewCoral() {
        String coralName = JOptionPane.showInputDialog(this,
                "Nome do novo coral:", "Novo Coral",
                JOptionPane.PLAIN_MESSAGE);

        if (coralName != null && !coralName.trim().isEmpty()) {
            coralName = coralName.trim();

            // Cria a pasta do coral
            File coralFolder = new File(
                    System.getProperty("user.home") + File.separator +
                            "KaraokeMusicas" + File.separator + coralName);
            coralFolder.mkdirs();

            coralCombo.addItem(coralName);
            coralCombo.setSelectedItem(coralName);
            currentCoral = coralName;

            setStatus("✓ Novo coral criado: " + coralName);
        }
    }

    // ── Menu de contexto ──────────────────────────────────────────────────────

    private void showContextMenu(Component comp, int x, int y) {
        MusicLibrary.SavedMusic sel = musicList.getSelectedValue();
        boolean hasSel = (sel != null);

        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(new Color(30, 42, 70));

        JMenuItem itemLoad = styledMenuItem("▶  Carregar", new Color(39, 174, 96));
        JMenuItem itemRename = styledMenuItem("✏  Renomear", new Color(41, 128, 185));
        JMenuItem itemDelete = styledMenuItem("🗑  Deletar", new Color(180, 60, 60));
        JMenuItem itemFolder = styledMenuItem("📂  Abrir Pasta", new Color(180, 140, 40));

        itemLoad.setEnabled(hasSel);
        itemRename.setEnabled(hasSel);
        itemDelete.setEnabled(hasSel);

        itemLoad.addActionListener(e -> loadSelected());
        itemRename.addActionListener(e -> renameSelected());
        itemDelete.addActionListener(e -> deleteSelected());
        itemFolder.addActionListener(e -> openLibraryFolder());

        menu.add(itemLoad);
        menu.add(itemRename);
        menu.addSeparator();
        menu.add(itemDelete);
        menu.addSeparator();
        menu.add(itemFolder);

        menu.show(comp, x, y);
    }

    private JMenuItem styledMenuItem(String text, Color fg) {
        JMenuItem item = new JMenuItem(text);
        item.setBackground(new Color(30, 42, 70));
        item.setForeground(fg);
        item.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        item.setBorderPainted(false);
        item.setOpaque(true);
        return item;
    }

    // ── Ações ─────────────────────────────────────────────────────────────────

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
                this, "Novo nome (Autor - Música):", "Renomear",
                JOptionPane.PLAIN_MESSAGE, null, null, m.name);
        if (newName == null) return;
        newName = newName.trim();

        if (newName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nome não pode estar vazio.",
                    "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            MusicLibrary.rename(m, newName);
            refreshMusicList();
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
                "<html><b>Excluir:</b> " + m.name + "<br><br>" +
                        "<small style='color:#cc4444'>⚠ Esta ação não pode ser desfeita.</small></html>",
                "Confirmar exclusão",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.YES_OPTION) return;

        try {
            MusicLibrary.delete(m);
            refreshMusicList();
            setStatus("🗑 Excluída.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Erro ao excluir:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openLibraryFolder() {
        File root = new File(System.getProperty("user.home") + File.separator + "KaraokeMusicas");
        if (!root.exists()) root.mkdirs();
        try {
            Desktop.getDesktop().open(root);
        } catch (IOException ex) {
            setStatus("⚠ Erro ao abrir pasta.");
        }
    }

    // ── Salvar ────────────────────────────────────────────────────────────────

    private void saveCurrentMusic() {
        String coral = (String) coralCombo.getSelectedItem();
        String author = authorField.getText().trim();
        String musicName = nameField.getText().trim();

        if (coral == null || coral.isEmpty()) {
            setStatus("⚠ Crie ou selecione um coral.");
            return;
        }
        if (author.isEmpty()) {
            setStatus("⚠ Digite o autor/compositor.");
            authorField.requestFocus();
            return;
        }
        if (musicName.isEmpty()) {
            setStatus("⚠ Digite o nome da música.");
            nameField.requestFocus();
            return;
        }

        String fullName = author + " - " + musicName;
        SaveRequest req = listener.onSaveRequested();
        if (req == null) {
            setStatus("⚠ Carregue um XML primeiro.");
            return;
        }

        // Verifica se já existe
        boolean exists = MusicLibrary.listMusicsByCoral(coral).stream()
                .anyMatch(m -> m.name.equalsIgnoreCase(fullName));
        if (exists) {
            int opt = JOptionPane.showConfirmDialog(this,
                    "\"" + fullName + "\" já existe em \"" + coral + "\". Sobrescrever?",
                    "Confirmar", JOptionPane.YES_NO_OPTION);
            if (opt != JOptionPane.YES_OPTION) return;
        }

        if (req.audioFile == null) {
            executeSave(fullName, coral, req, null);
            return;
        }

        // ✅ VERIFICAÇÃO: Se já é Opus, não precisa converter!
        String fileExtension = getFileExtension(req.audioFile);
        if (fileExtension.equalsIgnoreCase("opus")) {
            executeSave(fullName, coral, req, req.audioFile);
            return;
        }

        if (!FFmpegDetector.isAvailable()) {
            boolean nowAvailable = FFmpegDetector.ensureAvailable(this);
            if (!nowAvailable) {
                executeSave(fullName, coral, req, req.audioFile);
                return;
            }
        }

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
                conversionProgress.setString(latest + "%");
            }

            @Override
            protected void done() {
                setSavingState(false);
                try {
                    File opusFile = get();
                    executeSave(fullName, coral, req, opusFile);
                    opusFile.delete();
                } catch (Exception ex) {
                    setStatus("❌ Erro na conversão.");
                }
            }
        }.execute();
    }

    // ✅ NOVO: Método helper para extrair extensão
    private String getFileExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        return (lastDot > 0) ? name.substring(lastDot + 1) : "";
    }

    private void executeSave(String fullName, String coral, SaveRequest req, File audioFile) {
        try {
            MusicLibrary.save(fullName, coral, req.xmlFile, audioFile,
                    req.realDurationMin, req.offsetSeconds, req.selectedVoice);
            refreshMusicList();
            setStatus("✓ Salvo em \"" + coral + "\": " + fullName);

            // ✅ NOVO: Perguntar se deseja salvar no GitHub
            MusicLibrary.SavedMusic savedMusic = MusicLibrary.listMusicsByCoral(coral).stream()
                    .filter(m -> m.name.equalsIgnoreCase(fullName))
                    .findFirst()
                    .orElse(null);

            if (savedMusic != null) {
                askUploadToGitHub(savedMusic);
            }

            authorField.setText("");
            nameField.setText("");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao salvar:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void askUploadToGitHub(MusicLibrary.SavedMusic music) {
        int opt = JOptionPane.showConfirmDialog(this,
                "<html><b>Deseja enviar para GitHub?</b><br><br>" +
                        "Autor: " + music.author + "<br>" +
                        "Música: " + music.name.split(" - ")[1] + "<br>" +
                        "Coral: " + music.coral + "<br><br>" +
                        "<small>Isso criará/atualizará os arquivos no repositório remoto.</small></html>",
                "Upload para GitHub",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);

        if (opt == JOptionPane.YES_OPTION) {
            uploadToGitHubWithProgress(music);
        }
    }

    private void uploadToGitHubWithProgress(MusicLibrary.SavedMusic music) {
        setSavingState(true);
        setStatus("⏳ Enviando para GitHub...");

        GitHubUploadManager.uploadMusic(music, new GitHubUploadManager.UploadListener() {
            @Override
            public void onProgress(String message, int current, int total) {
                SwingUtilities.invokeLater(() -> {
                    setStatus(message);
                    if (total > 0) {
                        conversionProgress.setMaximum(total);
                        conversionProgress.setValue(current);
                        conversionProgress.setString(current + "/" + total);
                    }
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    setSavingState(false);
                    setStatus("❌ " + error);
                    JOptionPane.showMessageDialog(LibraryPanel.this,
                            error,
                            "Erro no Upload", JOptionPane.ERROR_MESSAGE);
                });
            }

            @Override
            public void onComplete(boolean success, String message) {
                SwingUtilities.invokeLater(() -> {
                    setSavingState(false);
                    setStatus(message);
                    if (success) {
                        JOptionPane.showMessageDialog(LibraryPanel.this,
                                message,
                                "Upload Concluído", JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            }
        });
    }

    // ── Conversão em lote ─────────────────────────────────────────────────────

    private void convertAllToOpus() {
        if (!FFmpegDetector.isAvailable()) {
            if (!FFmpegDetector.ensureAvailable(this)) {
                JOptionPane.showMessageDialog(this,
                        "FFmpeg é obrigatório para conversão.",
                        "Erro", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        List<MusicLibrary.SavedMusic> toProcess = MusicLibrary.listAll().stream()
                .filter(m -> m.audioFile != null && m.audioFile.exists())
                .filter(m -> !m.audioExtension().equals("opus"))
                .toList();

        if (toProcess.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Todos já estão em Opus.",
                    "✓", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (JOptionPane.showConfirmDialog(this,
                toProcess.size() + " música(s) serão convertidas.\nContinuar?",
                "Converter", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;

        long[] stats = {0, 0, 0};
        setBulkConvertState(true);

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                int total = toProcess.size();
                for (int i = 0; i < total; i++) {
                    MusicLibrary.SavedMusic music = toProcess.get(i);
                    int progress = i;  // ← Cria uma variável final

                    SwingUtilities.invokeLater(() -> {
                        conversionProgress.setMaximum(total);
                        conversionProgress.setValue(progress);
                        conversionProgress.setString((progress + 1) + "/" + total);
                    });

                    long originalSize = music.audioFile.length();
                    File tempOpus = null;
                    try {
                        tempOpus = AudioConverter.createTempOpus();
                        AudioConverter.convertToOpus(music.audioFile, tempOpus, null);
                        MusicLibrary.save(music.name, music.coral, music.xmlFile, tempOpus,
                                music.realDurationMin, music.offsetSeconds,
                                music.selectedVoice);

                        File savedOpus = new File(music.folder, "musica.opus");
                        if (savedOpus.exists())
                            stats[2] += originalSize - savedOpus.length();
                        stats[0]++;
                    } catch (Exception ex) {
                        stats[1]++;
                    } finally {
                        if (tempOpus != null && tempOpus.exists()) tempOpus.delete();
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                setBulkConvertState(false);
                double savedMb = stats[2] / (1024.0 * 1024.0);
                JOptionPane.showMessageDialog(LibraryPanel.this,
                        String.format("✅ %d convertidas\n❌ %d erros\n💾 %.1f MB livres",
                                stats[0], stats[1], savedMb),
                        "Concluído", JOptionPane.INFORMATION_MESSAGE);
                refresh();
            }
        }.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setSavingState(boolean saving) {
        saveBtn.setEnabled(!saving);
        convertAllBtn.setEnabled(!saving);
        authorField.setEnabled(!saving);
        nameField.setEnabled(!saving);
        conversionProgress.setVisible(saving);
    }

    private void setBulkConvertState(boolean converting) {
        convertAllBtn.setEnabled(!converting);
        saveBtn.setEnabled(!converting);
        conversionProgress.setVisible(converting);
    }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
        new javax.swing.Timer(4000, e -> {
            statusLabel.setText(" ");
            ((javax.swing.Timer) e.getSource()).stop();
        }).start();
    }

    private static JButton smallButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setPreferredSize(new Dimension(30, 24));
        return btn;
    }

    public void suggestName(String name) {
        String[] parts = name.split("[\\-–—]+", 2);
        if (parts.length == 2) {
            authorField.setText(parts[0].trim());
            nameField.setText(parts[1].trim());
        } else {
            nameField.setText(name);
        }
    }

    public void reloadList() { refresh(); }

    public MusicLibrary.SavedMusic getSelectedMusic() {
        return musicList.getSelectedValue();
    }

    // ═══════════════════════════════════════════════════════════════════════════
// ✅ NOVO: Métodos para sincronização com GitHub
// ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ Sincronização normal (quando manifest já existe)
     */
    private void syncWithGitHubNormal(JButton refreshBtn) {
        System.out.println("📡 Iniciando sincronização normal...\n");

        GitHubSyncManager.syncWithGitHub(new GitHubSyncManager.SyncListener() {
            @Override
            public void onProgress(String message, int current, int total) {
                SwingUtilities.invokeLater(() -> {
                    setStatus(message + (total > 0 ? " (" + current + "/" + total + ")" : ""));
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("❌ " + error);
                    refreshBtn.setEnabled(true);
                });
            }

            @Override
            public void onComplete(int downloaded, int updated, int skipped) {
                SwingUtilities.invokeLater(() -> {
                    refresh();

                    StringBuilder msg = new StringBuilder();
                    if (downloaded > 0) {
                        msg.append("✓ ").append(downloaded).append(" música(s) baixada(s)");
                    }
                    if (updated > 0) {
                        if (msg.length() > 0) msg.append(" | ");
                        msg.append("🔄 ").append(updated).append(" atualizada(s)");
                    }
                    if (skipped > 0) {
                        if (msg.length() > 0) msg.append(" | ");
                        msg.append("⚠ ").append(skipped).append(" erro(s)");
                    }
                    if (downloaded == 0 && updated == 0 && skipped == 0) {
                        msg.append("✓ Biblioteca atualizada (nenhuma novidade)");
                    }

                    setStatus(msg.toString());
                    refreshBtn.setEnabled(true);
                });
            }
        });
    }

    /**
     * ✅ Geração de manifest completo e sincronização (primeira vez)
     */
    private void generateManifestAndSync(JButton refreshBtn) {
        System.out.println("🔄 Gerando manifest completo...\n");

        // ✅ Mostrar dialog de progresso
        JDialog progressDialog = createProgressDialog("Gerando Manifest",
                "Escaneando todas as músicas no GitHub...");
        progressDialog.setLocationRelativeTo(this.getParent());
        progressDialog.setVisible(true);

        ManifestCreator.generateCompleteManifestFromGitHub(new ManifestCreator.ManifestListener() {
            @Override
            public void onProgress(String message, int current, int total) {
                SwingUtilities.invokeLater(() -> {
                    setStatus(message);
                    System.out.println(message);
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    setStatus("❌ Erro: " + error);
                    refreshBtn.setEnabled(true);

                    JOptionPane.showMessageDialog(LibraryPanel.this,
                            "<html><b>Erro ao gerar manifest:</b><br><br>" +
                                    error + "<br><br>" +
                                    "<small>Tente novamente mais tarde.</small></html>",
                            "Erro na Sincronização", JOptionPane.ERROR_MESSAGE);
                });
            }

            @Override
            public void onComplete(boolean success, int totalMusics) {
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();

                    String completionMsg = String.format(
                            "✅ Manifest criado com %d música(s)!\n\n" +
                                    "Iniciando sincronização...",
                            totalMusics);

                    setStatus("✓ Manifest gerado com " + totalMusics + " música(s)");
                    System.out.println(completionMsg);

                    // ✅ Agora fazer sync normal
                    syncWithGitHubNormal(refreshBtn);
                });
            }
        });
    }

    /**
     * ✅ Cria dialog de progresso simples
     */
    private JDialog createProgressDialog(String title, String message) {
        JDialog dialog = new JDialog();
        dialog.setTitle(title);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(400, 120);
        dialog.setResizable(false);
        dialog.setModal(false);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(new Color(30, 42, 70));
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JLabel msgLabel = new JLabel(message);
        msgLabel.setForeground(Color.WHITE);
        msgLabel.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setBackground(new Color(30, 42, 70));
        progressBar.setForeground(new Color(39, 174, 96));

        panel.add(msgLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);

        dialog.add(panel);
        return dialog;
    }
}