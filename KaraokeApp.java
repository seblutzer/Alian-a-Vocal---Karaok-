package com.karaoke;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import com.formdev.flatlaf.FlatDarkLaf;

public class KaraokeApp extends JFrame {

    // ── Infraestrutura de áudio
    private final SharedMicrophone sharedMic = new SharedMicrophone();
    private final AudioDetector audioDetector = new AudioDetector();
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final VoiceRecorder voiceRecorder = new VoiceRecorder();

    // ── Dados musicais
    private List<MusicNote> notes = new ArrayList<>();
    private double xmlDuration = 0.0;
    private double realDuration = 0.0;
    private List<List<MusicNote>> allVoices = new ArrayList<>();
    private Object currentMode = null;
    private String lastAudioPath = null;
    private File lastXmlFile = null;
    private String musicName = "";

    // ── UI Principal
    private JComboBox<String> voiceCombo;
    private JLabel infoLabel, statusLabel;
    private NoteScrollPanel notePanel;
    private JLabel audioLabel;
    private JButton playPauseBtn;
    private RecordSwitch recordSwitch;
    private LibraryPanel libraryPanel;
    private MidiAccompanimentPlayer.PlaybackMode currentPlaybackMode
            = MidiAccompanimentPlayer.PlaybackMode.ALL_EXCEPT_SELECTED;
    private JButton playbackModeBtn; // Novo botão para alternar modos

    // ── Referências para troca de layout no modo treino
    private JPanel centerPanel;   // painel central (BorderLayout)
    private JPanel scorePanel;    // placar original
    private JPanel bottomPanel;   // rodapé original
    private JPanel leftPanel;     // painel esquerdo (placar + notas)

    // ── UI Avançadas (colapsadas por padrão)
    private JPanel advancedPanel;
    private JButton toggleAdvancedBtn;
    private JTextField durationField, offsetField;
    private JComboBox<String> gameOctaveCombo;
    private JSlider voiceBalanceSlider;
    private JLabel offsetPreviewLabel;

    private static final int[] OCTAVE_OPTIONS = {-1, 0, 1};
    private static final float[][] BALANCE_TABLE = {
            {0.0f, 1.0f},
            {0.5f, 1.0f},
            {1.0f, 1.0f},
            {1.0f, 0.5f},
            {1.0f, 0.0f},
    };

    private JLabel perfectLabel, greatLabel, goodLabel, okLabel,
            missedLabel, precisionLabel, totalScoreLabel, songNameLabel;

    // ═══════════════════════════════════════════════════════════════════════
    // RecordSwitch (inalterado)
    // ═══════════════════════════════════════════════════════════════════════
    private static class RecordSwitch extends JToggleButton {
        private static final Color COLOR_ON = new Color(200, 30, 30);
        private static final Color COLOR_OFF = new Color(70, 70, 80);
        private static final Color COLOR_KNOB = Color.WHITE;
        private static final int TRACK_H = 24;
        private static final int TRACK_W = 54;
        private static final int KNOB_DIAM = 18;
        private static final int PAD = 3;

        RecordSwitch() {
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(TRACK_W + 90, 40));
            setToolTipText("<html>Ativar para gravar sua voz durante o modo <b>CANTAR</b>.</html>");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int trackY = (getHeight() - TRACK_H) / 2;
            Color trackColor = !isEnabled()
                    ? (isSelected() ? COLOR_ON.darker() : COLOR_OFF.darker())
                    : (isSelected() ? COLOR_ON : COLOR_OFF);
            g2.setColor(trackColor);
            g2.fillRoundRect(0, trackY, TRACK_W, TRACK_H, TRACK_H, TRACK_H);

            int knobX = isSelected() ? TRACK_W - KNOB_DIAM - PAD : PAD;
            int knobY = trackY + (TRACK_H - KNOB_DIAM) / 2;
            g2.setColor(isEnabled() ? COLOR_KNOB : Color.LIGHT_GRAY);
            g2.fillOval(knobX, knobY, KNOB_DIAM, KNOB_DIAM);

            g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(isEnabled()
                    ? (isSelected() ? new Color(255, 130, 130) : new Color(160, 160, 180))
                    : new Color(100, 100, 110));
            g2.drawString(isSelected() ? "Gravar: ON" : "Gravar: OFF", TRACK_W + 8,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setToolTipText(enabled
                    ? "<html>Ativar para gravar sua voz durante o modo <b>CANTAR</b>.</html>"
                    : "<html>Não disponível durante o modo CANTAR.</html>");
            repaint();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Construtor
    // ═══════════════════════════════════════════════════════════════════════
    public KaraokeApp() {
        super("🎤 Karaokê - Aliança Vocal");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        audioDetector.setSharedMicrophone(sharedMic);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopCurrentMode();
                audioPlayer.stop();
                audioDetector.stopListening();
                sharedMic.stop();
                dispose();
                System.exit(0);
            }
        });

        setupAudioListeners();
        getContentPane().setBackground(new Color(22, 33, 62));
        setLayout(new BorderLayout(5, 5));
        setSize(1200, 850);
        setLocationRelativeTo(null);
        createWidgets();
    }

    private void setupAudioListeners() {
        audioPlayer.setListener(new AudioPlayer.PlayerListener() {
            @Override
            public void onFinished() {
                SwingUtilities.invokeLater(() -> {
                    updatePlayPauseButton();
                    if (currentMode instanceof KaraokeGameMode mode)
                        mode.onMp3Finished();
                });
            }

            @Override
            public void onError(String msg) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(KaraokeApp.this, msg,
                                "Erro de Áudio", JOptionPane.ERROR_MESSAGE));
            }
        });

        voiceRecorder.setListener(new VoiceRecorder.Listener() {
            @Override
            public void onSaved(File file) {
                SwingUtilities.invokeLater(() -> {
                    recordSwitch.setEnabled(true);
                    statusLabel.setText("💾 Gravação salva: " + file.getName());
                });
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    recordSwitch.setEnabled(true);
                    recordSwitch.setSelected(false);
                });
                JOptionPane.showMessageDialog(KaraokeApp.this,
                        "Erro na gravação:\n" + message,
                        "Erro de Áudio", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // createWidgets - DESIGN NOVO
    // ═══════════════════════════════════════════════════════════════════════
    private void createWidgets() {
        // ── PAINEL SUPERIOR (simples e clean)
        JPanel topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);

        new javax.swing.Timer(200, e -> updateAudioTimeLabel()).start();

        // ── PAINEL CENTRAL (Placar + Notas + Biblioteca)
        centerPanel = createCenterPanel();
        add(centerPanel, BorderLayout.CENTER);

        // ── PAINEL INFERIOR (Botões principais)
        bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // ────────────────────────────────────────────────────────────────────────
    // PAINEL SUPERIOR - Essencial apenas
    // ────────────────────────────────────────────────────────────────────────
    private JPanel createTopPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(22, 33, 62));
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 90)));

        // ── Linha 1: Carregamento
        JPanel line1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        line1.setBackground(new Color(22, 33, 62));

        JButton loadXmlBtn = createButton("📁 XML", new Color(15, 52, 96));
        loadXmlBtn.addActionListener(e -> loadXml());
        line1.add(loadXmlBtn);

        line1.add(createLabel("Voz:"));
        voiceCombo = new JComboBox<>();
        voiceCombo.setPreferredSize(new Dimension(180, 28));
        voiceCombo.addActionListener(e -> onVoiceChange());
        line1.add(voiceCombo);

        JButton loadAudioBtn = createButton("🎵 Áudio", new Color(52, 73, 94));
        loadAudioBtn.addActionListener(e -> loadAudio());
        line1.add(loadAudioBtn);

        playPauseBtn = createButton("▶ Play", new Color(39, 174, 96));
        playPauseBtn.addActionListener(e -> togglePlayPause());
        line1.add(playPauseBtn);

        JButton stopAudioBtn = createButton("⏹ Stop", new Color(192, 57, 43));
        stopAudioBtn.addActionListener(e -> stopAudio());
        line1.add(stopAudioBtn);

        audioLabel = createLabel("Sem áudio");
        audioLabel.setForeground(new Color(150, 150, 180));
        line1.add(audioLabel);

        line1.add(Box.createHorizontalGlue());
        infoLabel = createLabel("Aguardando XML");
        infoLabel.setForeground(new Color(170, 170, 170));
        line1.add(infoLabel);

        // Avançado (colapsável)
        toggleAdvancedBtn = createButton("⚙ Avançado", new Color(100, 80, 120));
        toggleAdvancedBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
        toggleAdvancedBtn.addActionListener(e -> toggleAdvancedPanel());
        line1.add(toggleAdvancedBtn);

        advancedPanel = createAdvancedPanel();
        advancedPanel.setVisible(false);

        panel.add(line1);
        panel.add(advancedPanel);

        return panel;
    }

    private JPanel createAdvancedPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBackground(new Color(25, 35, 65));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));

        // ── Ajuste de duração
        panel.add(createLabel("Duração (min):"));
        durationField = new JTextField("0.0", 5);
        panel.add(durationField);

        JButton adjustBtn = createButton("✓ Ajustar", new Color(233, 69, 96));
        adjustBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 9));
        adjustBtn.addActionListener(e -> adjustTime());
        panel.add(adjustBtn);

        JButton restoreDurBtn = createButton("↺ Restaurar", new Color(100, 60, 20));
        restoreDurBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 9));
        restoreDurBtn.addActionListener(e -> restoreOriginalDuration());
        panel.add(restoreDurBtn);

        panel.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(1, 25));
        }});

        // ── Offset de sincronização
        panel.add(createLabel("Offset (seg):"));
        offsetField = new JTextField("0.0", 5);
        panel.add(offsetField);

        JButton applyOffsetBtn = createButton("✓ Aplicar", new Color(52, 73, 94));
        applyOffsetBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 9));
        applyOffsetBtn.addActionListener(e -> applyOffset());
        panel.add(applyOffsetBtn);

        offsetPreviewLabel = createLabel("Sem ajuste");
        offsetPreviewLabel.setForeground(new Color(180, 220, 180));
        offsetPreviewLabel.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 10));
        panel.add(offsetPreviewLabel);

        panel.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(1, 25));
        }});

        // ── Oitava
        panel.add(createLabel("Oitava:"));
        gameOctaveCombo = new JComboBox<>(
                new String[]{"−1 (Grave)", "Original", "+1 (Agudo)"});
        gameOctaveCombo.setSelectedIndex(1);
        gameOctaveCombo.setBackground(new Color(30, 40, 80));
        gameOctaveCombo.setForeground(Color.WHITE);
        gameOctaveCombo.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
        gameOctaveCombo.setFocusable(false);
        gameOctaveCombo.setPreferredSize(new Dimension(140, 24));
        panel.add(gameOctaveCombo);

        panel.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(1, 25));
        }});

        // ── PDF → XML
        JButton pdfBtn = createButton("📄 PDF→XML", new Color(120, 60, 130));
        pdfBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 9));
        pdfBtn.addActionListener(e -> convertPdfToXml());
        panel.add(pdfBtn);

        return panel;
    }

    private void toggleAdvancedPanel() {
        advancedPanel.setVisible(!advancedPanel.isVisible());
        toggleAdvancedBtn.setText(advancedPanel.isVisible() ? "⚙ Avançado ▲" : "⚙ Avançado ▼");
        revalidate();
        repaint();
    }

    private void cyclePlaybackMode() {
        int voiceIdx = voiceCombo.getSelectedIndex();
        String voiceName = "?";

        if (voiceIdx >= 0 && voiceIdx < allVoices.size()) {
            String raw = (String) voiceCombo.getSelectedItem();
            voiceName = raw != null
                    ? raw.split("\\(")[0].trim()
                    : "Voz";
        }

        switch (currentPlaybackMode) {
            case ALL_EXCEPT_SELECTED:
                currentPlaybackMode = MidiAccompanimentPlayer.PlaybackMode.ONLY_SELECTED;
                playbackModeBtn.setText("🎵 Só " + voiceName);
                playbackModeBtn.setToolTipText("Tocando APENAS " + voiceName +
                        " | Clique para tocar TODAS");
                break;

            case ONLY_SELECTED:
                currentPlaybackMode = MidiAccompanimentPlayer.PlaybackMode.ALL_VOICES;
                playbackModeBtn.setText("🎵 Todas");
                playbackModeBtn.setToolTipText("Tocando TODAS as vozes incluindo " + voiceName +
                        " | Clique para retornar ao padrão");
                break;

            case ALL_VOICES:
                currentPlaybackMode = MidiAccompanimentPlayer.PlaybackMode.ALL_EXCEPT_SELECTED;
                playbackModeBtn.setText("🎵 Sem " + voiceName);
                playbackModeBtn.setToolTipText("Tocando todas EXCETO " + voiceName +
                        " | Clique para tocar só " + voiceName);
                break;
        }

        statusLabel.setText("🎵 Modo: " + currentPlaybackMode);
    }


    // ────────────────────────────────────────────────────────────────────────
    // PAINEL CENTRAL - Placar + Notas + Biblioteca
    // ────────────────────────────────────────────────────────────────────────
    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setBackground(new Color(22, 33, 62));

        // ── Placar
        JPanel scorePanel = createScorePanel();

        // ── Notas
        notePanel = new NoteScrollPanel(940, 450);
        JPanel noteWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 5));
        noteWrapper.setBackground(new Color(22, 33, 62));
        noteWrapper.add(notePanel);

        // ── Painel esquerdo (Placar + Notas)
        leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBackground(new Color(22, 33, 62));
        leftPanel.add(scorePanel);
        leftPanel.add(noteWrapper);

        // ── Biblioteca
        libraryPanel = new LibraryPanel(new LibraryPanel.Listener() {
            @Override
            public void onLoad(MusicLibrary.SavedMusic music) {
                applyLibraryMusic(music);
            }

            @Override
            public LibraryPanel.SaveRequest onSaveRequested() {
                if (lastXmlFile == null) return null;
                LibraryPanel.SaveRequest req = new LibraryPanel.SaveRequest();
                req.xmlFile = lastXmlFile;
                req.audioFile = (lastAudioPath != null) ? new File(lastAudioPath) : null;
                req.realDurationMin = (realDuration > 0) ? realDuration / 60.0 : 0.0;
                req.offsetSeconds = getOffset();
                req.selectedVoice = Math.max(0, voiceCombo.getSelectedIndex());
                return req;
            }
        });

        panel.add(leftPanel, BorderLayout.CENTER);
        panel.add(libraryPanel, BorderLayout.EAST);
        return panel;
    }


    private JPanel createScorePanel() {
        // Painel principal com layout vertical
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.PAGE_AXIS));
        mainPanel.setBackground(new Color(15, 52, 96));
        mainPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        // Título no topo
        JLabel title = new JLabel("📊 PLACAR");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI Symbol", Font.BOLD, 14));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(title);

        mainPanel.add(Box.createVerticalStrut(8));

        // Painel para os valores (horizontal)
        JPanel valuesPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 6));
        valuesPanel.setBackground(new Color(15, 52, 96));

        // Criar painéis verticais para cada métrica (label + valor)
        valuesPanel.add(createMetricPanel("Perfeito", "★", new Color(255, 215, 0), 0));
        valuesPanel.add(createMetricPanel("Ótimo", "✓", new Color(52, 152, 219), 1));
        valuesPanel.add(createMetricPanel("Bom", "↑", new Color(39, 174, 96), 2));
        valuesPanel.add(createMetricPanel("Ok", "±", new Color(116, 115, 115), 3));
        valuesPanel.add(createMetricPanel("Errado", "✗", new Color(231, 76, 60), 4));
        valuesPanel.add(createMetricPanel("Precisão", "\uD83C\uDFAF", new Color(255, 165, 0), 5));
        valuesPanel.add(createMetricPanel("Tempo", "⏱", new Color(251, 104, 0), 6));

        mainPanel.add(valuesPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Label da música na base
        songNameLabel = new JLabel("Escolha uma música...");
        songNameLabel.setForeground(new Color(173, 216, 230)); // Azul claro
        songNameLabel.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 20));
        songNameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(songNameLabel);

        scorePanel = mainPanel;
        return mainPanel;
    }

    // Método auxiliar para criar painéis individuais de métrica
    private JPanel createMetricPanel(String label, String icon, Color color, int index) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
        panel.setBackground(new Color(15, 52, 96));
        panel.setOpaque(false);

        // Label descritivo (acima)
        JLabel descLabel = new JLabel(label);
        descLabel.setForeground(color);
        descLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 12));
        descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(descLabel);

        // Valor
        JLabel valueLabel = new JLabel(icon + " 0");
        valueLabel.setForeground(color);
        valueLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 12));
        valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(valueLabel);

        // Armazenar referência ao label para atualizar depois
        switch (index) {
            case 0:
                perfectLabel = valueLabel;
                break;
            case 1:
                greatLabel = valueLabel;
                break;
            case 2:
                goodLabel = valueLabel;
                break;
            case 3:
                okLabel = valueLabel;
                break;
            case 4:
                missedLabel = valueLabel;
                break;
            case 5:
                precisionLabel = valueLabel;
                break;
            case 6:
                totalScoreLabel = valueLabel;
                break;
        }

        return panel;
    }

    // Método para atualizar o nome da música
    public void updateSongName(String songName) {
        String selected = voiceCombo.getSelectedItem() == null ? "" : voiceCombo.getSelectedItem().toString();
        String voice = selected.split(" \\(")[0];
        String text = (songName == null ? "" : songName) + ": " + (voice.isEmpty() ? "" : " " + voice);
        songNameLabel.setText(text);
    }


    // ────────────────────────────────────────────────────────────────────────
    // PAINEL INFERIOR - Botões Principais + Switch de Gravação
    // ────────────────────────────────────────────────────────────────────────
    private JPanel createBottomPanel() {
        bottomPanel = new JPanel();
        JPanel panel = bottomPanel;
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(22, 33, 62));

        // ── Status
        statusLabel = createLabel("Pronto");
        statusLabel.setFont(new Font("Segoe UI Symbol", Font.BOLD, 13));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setForeground(new Color(200, 200, 200));

        // ── Botões principais
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        modePanel.setBackground(new Color(22, 33, 62));

        JButton gameBtn = createBigButton("🎮 CANTAR", new Color(39, 174, 96));
        gameBtn.addActionListener(e -> startGameMode());

        JButton learnBtn = createBigButton("📚 APRENDER", new Color(52, 152, 219));
        learnBtn.addActionListener(e -> startLearningMode());

        JButton stopBtn = createBigButton("⏹ PARAR", new Color(231, 76, 60));
        stopBtn.addActionListener(e -> stopCurrentMode());

        modePanel.add(gameBtn);
        modePanel.add(learnBtn);
        modePanel.add(stopBtn);

        // ── Switch de gravação
        recordSwitch = new RecordSwitch();
        recordSwitch.addItemListener(e -> recordSwitch.repaint());

        JPanel switchPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 5));
        switchPanel.setBackground(new Color(22, 33, 62));
        switchPanel.add(recordSwitch);

        // ── Balance de vozes
        voiceBalanceSlider = new JSlider(0, 4, 2);
        voiceBalanceSlider.setSnapToTicks(true);
        voiceBalanceSlider.setPaintTicks(true);
        voiceBalanceSlider.setMajorTickSpacing(1);
        voiceBalanceSlider.setOpaque(false);
        voiceBalanceSlider.setForeground(Color.WHITE);
        voiceBalanceSlider.setPreferredSize(new Dimension(220, 40));
        voiceBalanceSlider.setToolTipText("<html>Vozes MIDI ↔ Playback de áudio</html>");

        java.util.Hashtable<Integer, JLabel> labelTable = new java.util.Hashtable<>();
        String[] ticks = {"Só PB", "50% Voz", "½ / ½", "50% PB", "Só Voz"};
        for (int i = 0; i <= 4; i++) {
            JLabel lbl = new JLabel(ticks[i]);
            lbl.setForeground(new Color(180, 210, 255));
            lbl.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 8));
            labelTable.put(i, lbl);
        }
        voiceBalanceSlider.setLabelTable(labelTable);
        voiceBalanceSlider.setPaintLabels(true);

        // Botão para alternar modo de PlaybackMode
        playbackModeBtn = createButton("🎵 Sem ?", new Color(52, 73, 94));
        playbackModeBtn.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
        playbackModeBtn.setPreferredSize(new Dimension(150, 28));
        playbackModeBtn.addActionListener(e -> cyclePlaybackMode());
        playbackModeBtn.setToolTipText("Clique para alternar modo de acompanhamento");
        switchPanel.add(playbackModeBtn);

        switchPanel.add(voiceBalanceSlider);

        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(modePanel);
        panel.add(switchPanel);
        panel.add(Box.createVerticalStrut(5));

        return panel;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Métodos de Funcionalidade
    // ═══════════════════════════════════════════════════════════════════════

    private void convertPdfToXml() {
        PdfToMusicXmlConverter.showDialog(this, xmlFile -> {
            try {
                MusicXMLParser.ParseResult result =
                        MusicXMLParser.parse(xmlFile.getAbsolutePath());
                notes = result.allNotes;
                xmlDuration = result.totalDuration;
                allVoices = result.voices;
                lastXmlFile = xmlFile;

                musicName = xmlFile.getName()
                        .replaceAll("(?i)\\.musicxml$", "").trim();

                voiceCombo.removeAllItems();
                for (int i = 0; i < allVoices.size(); i++)
                    voiceCombo.addItem(result.voiceNames.get(i)
                            + " (" + allVoices.get(i).size() + " notas)");
                if (!allVoices.isEmpty()) {
                    voiceCombo.setSelectedIndex(0);
                    notes = allVoices.get(0);
                }
                notePanel.setNotes(notes);
                infoLabel.setText(String.format("✓ %d notas | %.1f min | %d vozes",
                        result.allNotes.size(), xmlDuration / 60, allVoices.size()));
                statusLabel.setText("✓ PDF convertido e XML carregado!");

                updateSongName(musicName);

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Erro ao abrir o XML gerado:\n" + ex.getMessage(),
                        "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void loadXml() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecionar MusicXML");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "MusicXML (*.xml, *.musicxml)", "xml", "musicxml"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File xmlFile = chooser.getSelectedFile();
            MusicXMLParser.ParseResult result = MusicXMLParser.parse(xmlFile.getAbsolutePath());
            notes = result.allNotes;
            xmlDuration = result.totalDuration;
            allVoices = result.voices;
            lastXmlFile = xmlFile;

            musicName = xmlFile.getName()
                    .replaceAll("(?i)\\.(xml|musicxml)$", "").trim();

            voiceCombo.removeAllItems();
            for (int i = 0; i < allVoices.size(); i++)
                voiceCombo.addItem(result.voiceNames.get(i)
                        + " (" + allVoices.get(i).size() + " notas)");
            if (!allVoices.isEmpty()) {
                voiceCombo.setSelectedIndex(0);
                notes = allVoices.get(0);
            }

            updateSongName(musicName);

            infoLabel.setText(String.format("✓ %d notas | %.1f min | %d vozes",
                    result.allNotes.size(), xmlDuration / 60, allVoices.size()));
            durationField.setText(String.format("%.4f", xmlDuration / 60).replace(",", "."));
            notePanel.setNotes(notes);
            statusLabel.setText("✓ XML carregado!");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Erro ao carregar XML:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onVoiceChange() {
        int idx = voiceCombo.getSelectedIndex();
        if (idx >= 0 && idx < allVoices.size()) {
            notes = allVoices.get(idx);
            notePanel.setNotes(notes);
            updateSongName(musicName);

            // Atualiza o label do botão de modo
            String raw = (String) voiceCombo.getSelectedItem();
            String voiceName = raw != null
                    ? raw.split("\\(")[0].trim()
                    : "Voz";

            switch (currentPlaybackMode) {
                case ALL_EXCEPT_SELECTED:
                    playbackModeBtn.setText("🎵 Sem " + voiceName);
                    playbackModeBtn.setToolTipText("Tocando todas EXCETO " + voiceName);
                    break;
                case ONLY_SELECTED:
                    playbackModeBtn.setText("🎵 Só " + voiceName);
                    playbackModeBtn.setToolTipText("Tocando APENAS " + voiceName);
                    break;
                case ALL_VOICES:
                    playbackModeBtn.setText("🎵 Todas + " + voiceName);
                    playbackModeBtn.setToolTipText("Tocando TODAS as vozes");
                    break;
            }
        }
    }


    private void loadAudio() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecionar arquivo de áudio");
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "Áudio (MP3, WAV, FLAC, OGG, Opus)",
                "mp3", "wav", "flac", "ogg", "opus"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("MP3 (*.mp3)", "mp3"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("WAV (*.wav)", "wav"));
        chooser.setFileFilter(chooser.getChoosableFileFilters()[1]);

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File chosen = chooser.getSelectedFile();
        lastAudioPath = chosen.getAbsolutePath();
        audioPlayer.load(lastAudioPath);
        audioLabel.setText("🎵 " + chosen.getName());
        statusLabel.setText("✓ Áudio: " + chosen.getName());
        updatePlayPauseButton();
    }

    private void togglePlayPause() {
        if (!audioPlayer.isLoaded()) {
            JOptionPane.showMessageDialog(this, "Carregue um áudio primeiro!");
            return;
        }
        if (audioPlayer.isPlaying()) audioPlayer.pause();
        else if (audioPlayer.isPaused()) audioPlayer.resume();
        else audioPlayer.play();
        updatePlayPauseButton();
    }

    private void stopAudio() {
        audioPlayer.stop();
        if (lastAudioPath != null) {
            audioPlayer.load(lastAudioPath);
            audioLabel.setText("🎵 " + new File(lastAudioPath).getName());
        }
        updatePlayPauseButton();
    }

    private void updatePlayPauseButton() {
        if (audioPlayer.isPlaying()) {
            playPauseBtn.setText("⏸ Pausar");
            playPauseBtn.setBackground(new Color(230, 126, 34));
        } else {
            playPauseBtn.setText("▶ Play");
            playPauseBtn.setBackground(new Color(39, 174, 96));
        }
    }

    private void updateAudioTimeLabel() {
        if (audioPlayer.isLoaded()
                && (audioPlayer.isPlaying() || audioPlayer.isPaused())) {
            double t = audioPlayer.getCurrentTime();
            audioLabel.setText(String.format("🎵 %d:%05.2f", (int) (t / 60), t % 60));
        }
        updatePlayPauseButton();
    }

    private void adjustTime() {
        try {
            realDuration = Double.parseDouble(
                    durationField.getText().trim().replace(",", ".")) * 60;
            statusLabel.setText(String.format("⏱ Duração: %.2f min", realDuration / 60));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Duração inválida!", "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreOriginalDuration() {
        realDuration = 0.0;
        if (xmlDuration > 0)
            durationField.setText(String.format("%.2f", xmlDuration / 60).replace(",", "."));
        statusLabel.setText("↺ Duração restaurada");
    }

    private double getOffset() {
        try {
            return Double.parseDouble(offsetField.getText().trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void applyOffset() {
        double off = getOffset();
        offsetField.setText(String.format("%.2f", off).replace(",", "."));
        if (off == 0.0)
            offsetPreviewLabel.setText("Sem ajuste");
        else if (off > 0.0)
            offsetPreviewLabel.setText(String.format("Notas atrasadas %.2fs", off));
        else
            offsetPreviewLabel.setText(String.format("Notas adiantadas %.2fs", Math.abs(off)));
    }

    private void applyLibraryMusic(MusicLibrary.SavedMusic music) {
        try {
            MusicXMLParser.ParseResult result =
                    MusicXMLParser.parse(music.xmlFile.getAbsolutePath());
            notes = result.allNotes;
            xmlDuration = result.totalDuration;
            allVoices = result.voices;
            lastXmlFile = music.xmlFile;

            musicName = (music.name != null && !music.name.isBlank())
                    ? music.name.trim()
                    : music.xmlFile.getName().replaceAll("(?i)\\.(xml|musicxml)$", "").trim();

            voiceCombo.removeAllItems();
            for (int i = 0; i < allVoices.size(); i++)
                voiceCombo.addItem(result.voiceNames.get(i)
                        + " (" + allVoices.get(i).size() + " notas)");

            int vi = Math.min(music.selectedVoice, Math.max(0, allVoices.size() - 1));
            if (!allVoices.isEmpty()) {
                voiceCombo.setSelectedIndex(vi);
                notes = allVoices.get(vi);
            }

            updateSongName(musicName);

            notePanel.setNotes(notes);
            infoLabel.setText(String.format("✓ %d notas | %.1f min | %d vozes",
                    result.allNotes.size(), xmlDuration / 60, allVoices.size()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao carregar XML:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (music.audioFile != null && music.audioFile.exists()) {
            lastAudioPath = music.audioFile.getAbsolutePath();
            audioPlayer.load(lastAudioPath);
            audioLabel.setText("🎵 " + music.audioFile.getName());
        } else {
            lastAudioPath = null;
            audioLabel.setText("Sem áudio");
        }
        updatePlayPauseButton();

        if (music.realDurationMin > 0) {
            realDuration = music.realDurationMin * 60.0;
            durationField.setText(String.format("%.4f", music.realDurationMin).replace(",", "."));
        } else {
            realDuration = 0.0;
            durationField.setText(String.format("%.4f", xmlDuration / 60).replace(",", "."));
        }

        offsetField.setText(String.format("%.2f", music.offsetSeconds).replace(",", "."));
        applyOffset();
        statusLabel.setText("✓ Biblioteca: \"" + music.name + "\" carregada!");
    }

    private void updateScoreDisplay(KaraokeGameMode.ScoreData d) {
        perfectLabel.setText("★ " + d.perfect);
        greatLabel.setText("✓ " + d.great);
        goodLabel.setText("↑ " + d.good);
        okLabel.setText("± " + d.ok);
        missedLabel.setText("✗ " + d.missed);
        precisionLabel.setText(String.format("\uD83C\uDFAF %.0f%%", d.precision));
        totalScoreLabel.setText(String.format("⏱ %.2fs", d.totalScore));
    }

    private void startGameMode() {
        if (notes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Carregue um XML primeiro!");
            return;
        }
        stopCurrentMode();

        if (!sharedMic.isRunning()) {
            try {
                sharedMic.start();
            } catch (javax.sound.sampled.LineUnavailableException ex) {
                JOptionPane.showMessageDialog(this,
                        "Não foi possível abrir o microfone:\n" + ex.getMessage(),
                        "Erro de Áudio", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        audioDetector.startListening();

        double timeScale = (realDuration > 0 && xmlDuration > 0)
                ? realDuration / xmlDuration : 1.0;
        double offset = getOffset();
        int octaveShift = OCTAVE_OPTIONS[gameOctaveCombo.getSelectedIndex()];
        int selectedIdx = Math.max(0, voiceCombo.getSelectedIndex());

        List<MusicNote> copy = new ArrayList<>();
        for (MusicNote n : notes) copy.add(n.copy());

        float[] balance = getBalanceVolumes();
        float voiceFactor = balance[0];
        float playbackVol = balance[1];

        boolean playAcc = voiceFactor > 0f;
        List<List<MusicNote>> accompanimentVoices = new ArrayList<>();

        if (playAcc) {
            // Agora coleta vozes baseado no PlaybackMode
            for (int i = 0; i < allVoices.size(); i++) {
                boolean shouldInclude = switch (currentPlaybackMode) {
                    case ALL_EXCEPT_SELECTED -> i != selectedIdx;
                    case ONLY_SELECTED -> i == selectedIdx;
                    case ALL_VOICES -> true;
                };

                if (!shouldInclude) continue;

                List<MusicNote> voiceCopy = new ArrayList<>();
                for (MusicNote n : allVoices.get(i)) {
                    MusicNote c = n.copy();
                    c.startTime *= timeScale;
                    c.duration *= timeScale;
                    c.endTime = c.startTime + c.duration;
                    voiceCopy.add(c);
                }
                accompanimentVoices.add(voiceCopy);
            }
        }

        if (audioPlayer.isLoaded()) audioPlayer.setVolume(playbackVol);

        KaraokeGameMode mode = new KaraokeGameMode(
                copy, audioDetector, notePanel, this::updateScoreDisplay,
                timeScale, audioPlayer, offset,
                accompanimentVoices, playAcc, voiceFactor);

        mode.setOctaveShift(octaveShift);
        mode.setOnFinishCallback(this::stopRecordingIfActive);
        currentMode = mode;
        mode.start();

        gameOctaveCombo.setEnabled(false);
        recordSwitch.setEnabled(false);
        voiceBalanceSlider.setEnabled(false);

        if (recordSwitch.isSelected()) {
            String rawVoice = voiceCombo.getSelectedIndex() >= 0
                    ? (String) voiceCombo.getSelectedItem() : "Voz";
            String voiceName = rawVoice == null ? "Voz"
                    : rawVoice.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
            String baseName = (musicName.isBlank() ? "Gravação" : musicName)
                    + " - " + voiceName;

            voiceRecorder.setOutputName(baseName);
            sharedMic.addConsumer(voiceRecorder);
            voiceRecorder.start();
            statusLabel.setText("🎮 CANTANDO + 🔴 GRAVANDO");
        } else {
            statusLabel.setText("🎮 CANTANDO!");
        }
    }

    private void startLearningMode() {
        if (notes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Carregue um XML primeiro!");
            return;
        }
        stopCurrentMode();

        if (!sharedMic.isRunning()) {
            try {
                sharedMic.start();
            } catch (javax.sound.sampled.LineUnavailableException ex) {
                JOptionPane.showMessageDialog(this,
                        "Erro de microfone:\n" + ex.getMessage(),
                        "Erro de Áudio", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        audioDetector.startListening();

        double timeScale = (realDuration > 0 && xmlDuration > 0)
                ? realDuration / xmlDuration : 1.0;
        double offset = getOffset();

        List<MusicNote> copy = new ArrayList<>();
        for (MusicNote n : notes) copy.add(n.copy());

        String rawVoice = voiceCombo.getSelectedIndex() >= 0
                ? (String) voiceCombo.getSelectedItem() : "Voz";
        String voiceName = rawVoice == null ? "Voz"
                : rawVoice.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();

        LearningMode mode = new LearningMode(
                copy, audioDetector, notePanel, timeScale, audioPlayer, offset,
                voiceRecorder, sharedMic);

        mode.setVoiceName(voiceName);
        mode.setMusicName(musicName);
        mode.setOnExitCallback(this::exitLearningMode);

        currentMode = mode;
        boolean started = mode.start();
        if (!started) {
            currentMode = null;
            sharedMic.stop();
            return;
        }

        // ── Troca de layout: substitui placar e biblioteca pelos painéis de treino ──
        SwingUtilities.invokeLater(() -> {
            // Placar → painel de precisão do LearningMode
            leftPanel.remove(scorePanel);
            leftPanel.add(mode.getPrecisionPanel(), 0);

            // Biblioteca → lista de trechos
            centerPanel.remove(libraryPanel);
            centerPanel.add(mode.getSegmentPanel(), BorderLayout.EAST);

            // Rodapé → controles de treino
            remove(bottomPanel);
            add(mode.getLearningControlPanel(), BorderLayout.SOUTH);

            statusLabel.setText("📚 APRENDENDO!");
            revalidate();
            repaint();
        });
    }

    /**
     * Restaura o layout original da janela ao sair do modo treino.
     */
    private void exitLearningMode() {
        currentMode = null;
        audioDetector.stopListening();
        audioPlayer.setVolume(1.0f);
        sharedMic.stop();

        SwingUtilities.invokeLater(() -> {
            // ── Restaura rodapé ─────────────────────────────────────────────
            BorderLayout rootBl = (BorderLayout) getContentPane().getLayout();
            Component south = rootBl.getLayoutComponent(BorderLayout.SOUTH);
            if (south != bottomPanel) {
                if (south != null) remove(south);
                add(bottomPanel, BorderLayout.SOUTH);
            }

            // ── Restaura placar no leftPanel ────────────────────────────────
            if (leftPanel.getComponentCount() > 0
                    && leftPanel.getComponent(0) != scorePanel) {
                leftPanel.remove(0);
                leftPanel.add(scorePanel, 0);
            }

            // ── Restaura biblioteca no centerPanel ──────────────────────────
            Component east = ((BorderLayout) centerPanel.getLayout())
                    .getLayoutComponent(BorderLayout.EAST);
            if (east != libraryPanel) {
                if (east != null) centerPanel.remove(east);
                centerPanel.add(libraryPanel, BorderLayout.EAST);
            }

            recordSwitch.setEnabled(true);
            gameOctaveCombo.setEnabled(true);
            voiceBalanceSlider.setEnabled(true);
            statusLabel.setText("⏹ Parado");

            revalidate();
            repaint();
        });
    }

    private void stopCurrentMode() {
        if (currentMode instanceof LearningMode m) {
            // Para o LearningMode, o fluxo de limpeza de layout é feito em exitLearningMode.
            // Se foi chamado pelo botão PARAR (não pelo botão interno), precisamos fazer o mesmo.
            m.stop();
            currentMode = null;
            exitLearningMode();
            return;
        }
        if (currentMode instanceof KaraokeGameMode m) m.stop();
        currentMode = null;

        stopRecordingIfActive();
        audioDetector.stopListening();
        audioPlayer.setVolume(1.0f);

        if (voiceRecorder.isRecording()) {
            voiceRecorder.stop();
            sharedMic.removeConsumer(voiceRecorder);
            statusLabel.setText("⏳ Salvando gravação...");
        } else {
            recordSwitch.setEnabled(true);
            statusLabel.setText("⏹ Parado");
        }

        sharedMic.stop();
        gameOctaveCombo.setEnabled(true);
    }

    private void stopRecordingIfActive() {
        if (voiceRecorder != null && voiceRecorder.isRecording()) {
            voiceRecorder.stop();
            sharedMic.removeConsumer(voiceRecorder);
        }
        if (recordSwitch != null) recordSwitch.setEnabled(true);
        if (gameOctaveCombo != null) gameOctaveCombo.setEnabled(true);
        if (voiceBalanceSlider != null) voiceBalanceSlider.setEnabled(true);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers UI
    // ───────────────────────────────────────────────────────────────────────
    private JButton createButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(true);
        return b;
    }

    private JButton createBigButton(String text, Color bg) {
        JButton b = createButton(text, bg);
        b.setFont(new Font("Segoe UI Symbol", Font.BOLD, 15));
        b.setPreferredSize(new Dimension(180, 50));
        return b;
    }

    private JLabel createLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.WHITE);
        l.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
        return l;
    }

    private float[] getBalanceVolumes() {
        int idx = (voiceBalanceSlider != null) ? voiceBalanceSlider.getValue() : 2;
        return BALANCE_TABLE[idx];
    }

    public static void main(String[] args) {
        // 0. INICIALIZAR CONFIG DO GITHUB PRIMEIRO (antes de qualquer outra coisa)
        try {
            GitHubConfigManager.reload(); // Força inicialização e cria arquivo se necessário
            System.out.println("✓ GitHub configurado: " + GitHubConfigManager.getConfigPath());
        } catch (Exception e) {
            System.err.println("✗ Erro ao configurar GitHub: " + e.getMessage());
            System.exit(1);
        }

        // 1. Aplicar tema PRIMEIRO (antes de criar qualquer componente)
        try {
            FlatDarkLaf.setup();
        } catch (Exception e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }

        // 2. Criar a UI na thread do Swing
        SwingUtilities.invokeLater(() -> {
            KaraokeApp app = new KaraokeApp();
            app.setVisible(true);

            // 3. Inicializar FFmpeg em background (sem bloquear UI)
            FFmpegDetector.initInBackground(app);
        });
    }
}

// jpackage --input C:\Users\Sérgio\Downloads\input --dest C:\Users\Sérgio\Downloads\output --name AliancaVocalApp --main-jar KaraokeAliancaVocal.jar --main-class com.karaoke.KaraokeApp --icon C:\Users\Sérgio\Downloads\input\AliancaIco.ico --type msi --app-version 1.0.1 --vendor "Eduardo Lutzer" --description "Karaoke do Alianca Vocal" --win-menu --win-shortcut