
package com.karaoke;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class KaraokeApp extends JFrame {

    // ── Infraestrutura de áudio ───────────────────────────────────────────────
    private final SharedMicrophone sharedMic     = new SharedMicrophone();
    private final AudioDetector    audioDetector = new AudioDetector();
    private final AudioPlayer      audioPlayer   = new AudioPlayer();   // ← era AudioPlayer
    private final VoiceRecorder    voiceRecorder = new VoiceRecorder();

    // ── Switch de gravação ────────────────────────────────────────────────────
    private RecordSwitch recordSwitch;

    // ── Dados musicais ────────────────────────────────────────────────────────
    private List<MusicNote>       notes        = new ArrayList<>();
    private double                xmlDuration  = 0.0;
    private double                realDuration = 0.0;
    private List<List<MusicNote>> allVoices    = new ArrayList<>();
    private Object                currentMode  = null;
    private String                lastAudioPath = null;   // ← era lastMp3Path
    private File                  lastXmlFile  = null;
    private String                musicName    = "";

    // ── UI ────────────────────────────────────────────────────────────────────
    private JComboBox<String> voiceCombo;
    private JTextField        durationField;
    private JLabel            infoLabel, statusLabel;
    private NoteScrollPanel   notePanel;
    private JLabel            audioLabel;              // ← era mp3Label
    private JButton           playPauseBtn;
    private JTextField        offsetField;
    private JLabel            offsetPreviewLabel;
    private JComboBox<String> gameOctaveCombo;

    private static final int[] OCTAVE_OPTIONS = {-1, 0, 1};

    private JLabel perfectLabel, greatLabel, goodLabel, okLabel,
            missedLabel, precisionLabel, totalScoreLabel;

    private LibraryPanel libraryPanel;

    // ═════════════════════════════════════════════════════════════════════════
    // Switch visual ON/OFF  (inalterado)
    // ═════════════════════════════════════════════════════════════════════════
    private static class RecordSwitch extends JToggleButton {

        private static final Color COLOR_ON   = new Color(200, 30, 30);
        private static final Color COLOR_OFF  = new Color(70, 70, 80);
        private static final Color COLOR_KNOB = Color.WHITE;
        private static final int   TRACK_H    = 24;
        private static final int   TRACK_W    = 54;
        private static final int   KNOB_DIAM  = 18;
        private static final int   PAD        = 3;

        RecordSwitch() {
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(TRACK_W + 90, 40));
            setToolTipText(
                    "<html>Ativar para gravar sua voz durante o modo <b>CANTAR</b>.</html>");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

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

            g2.setFont(new Font("Arial", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(isEnabled()
                    ? (isSelected() ? new Color(255, 130, 130) : new Color(160, 160, 180))
                    : new Color(100, 100, 110));
            g2.drawString(isSelected() ? "Gravar: ON" : "Gravar: OFF",
                    TRACK_W + 8,
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

    // ═════════════════════════════════════════════════════════════════════════
    // Construtor
    // ═════════════════════════════════════════════════════════════════════════
    public KaraokeApp() {
        super("🎤 Karaokê - Aliança Vocal");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        audioDetector.setSharedMicrophone(sharedMic);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                stopCurrentMode();
                audioPlayer.stop();
                audioDetector.stopListening();
                sharedMic.stop();
                dispose();
                System.exit(0);
            }
        });

        // ── Listener do AudioPlayer ───────────────────────────────────────────
        audioPlayer.setListener(new AudioPlayer.PlayerListener() {
            @Override public void onFinished() {
                SwingUtilities.invokeLater(() -> {
                    updatePlayPauseButton();
                    if (currentMode instanceof KaraokeGameMode mode)
                        mode.onMp3Finished();
                });
            }
            @Override public void onError(String msg) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(KaraokeApp.this, msg,
                                "Erro de Áudio", JOptionPane.ERROR_MESSAGE));
            }
        });

        voiceRecorder.setListener(new VoiceRecorder.Listener() {
            @Override public void onSaved(File file) {
                SwingUtilities.invokeLater(() -> {
                    recordSwitch.setEnabled(true);
                    statusLabel.setText("💾 Gravação salva: " + file.getName());
                });
                String type = file.getName().endsWith(".mp3") ? "MP3" : "WAV";
                int opt = JOptionPane.showConfirmDialog(KaraokeApp.this,
                        "Voz gravada com sucesso (" + type + ")!\n\n" +
                                "Arquivo: " + file.getAbsolutePath() + "\n\n" +
                                "Deseja abrir a pasta?",
                        "Gravação Concluída",
                        JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                if (opt == JOptionPane.YES_OPTION) {
                    try { Desktop.getDesktop().open(file.getParentFile()); }
                    catch (Exception ex) { /* ignora */ }
                }
            }
            @Override public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    recordSwitch.setEnabled(true);
                    recordSwitch.setSelected(false);
                });
                JOptionPane.showMessageDialog(KaraokeApp.this,
                        "Erro na gravação:\n" + message,
                        "Erro de Áudio", JOptionPane.ERROR_MESSAGE);
            }
        });

        getContentPane().setBackground(new Color(22, 33, 62));
        setLayout(new BorderLayout(5, 5));
        setSize(1200, 850);
        setLocationRelativeTo(null);
        createWidgets();
    }

    // ── PDF → XML ─────────────────────────────────────────────────────────────
    private void convertPdfToXml() {
        PdfToMusicXmlConverter.showDialog(this, xmlFile -> {
            try {
                MusicXMLParser.ParseResult result =
                        MusicXMLParser.parse(xmlFile.getAbsolutePath());
                notes       = result.allNotes;
                xmlDuration = result.totalDuration;
                allVoices   = result.voices;
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
                durationField.setText(
                        String.format("%.2f", xmlDuration / 60).replace(",", "."));
                libraryPanel.suggestName(musicName);
                statusLabel.setText("✓ PDF convertido e XML carregado!");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Erro ao abrir o XML gerado:\n" + ex.getMessage(),
                        "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // createWidgets
    // ═════════════════════════════════════════════════════════════════════════
    private void createWidgets() {

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(new Color(22, 33, 62));

        // ── Linha 1 ───────────────────────────────────────────────────────────
        JPanel line1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        line1.setBackground(new Color(22, 33, 62));

        JButton loadXmlBtn = createButton("📁 Carregar XML", new Color(15, 52, 96));
        loadXmlBtn.addActionListener(e -> loadXml());
        line1.add(loadXmlBtn);

        line1.add(createLabel("Voz:"));
        voiceCombo = new JComboBox<>();
        voiceCombo.setPreferredSize(new Dimension(160, 28));
        voiceCombo.addActionListener(e -> onVoiceChange());
        line1.add(voiceCombo);

        line1.add(createLabel("Duração real (min):"));
        durationField = new JTextField("0.0", 6);
        line1.add(durationField);

        JButton adjustBtn = createButton("⏱ Ajustar", new Color(233, 69, 96));
        adjustBtn.addActionListener(e -> adjustTime());
        line1.add(adjustBtn);

        JButton restoreDurBtn = createButton("↺ Restaurar", new Color(100, 60, 20));
        restoreDurBtn.addActionListener(e -> restoreOriginalDuration());
        line1.add(restoreDurBtn);

        infoLabel = createLabel("Nenhum arquivo carregado");
        infoLabel.setForeground(new Color(170, 170, 170));
        line1.add(infoLabel);

        // ── Linha 2 ───────────────────────────────────────────────────────────
        JPanel line2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        line2.setBackground(new Color(22, 33, 62));
        line2.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 90)));

        // Botão agora abre qualquer formato de áudio
        JButton loadAudioBtn = createButton("🎵 Carregar Áudio", new Color(52, 73, 94));
        loadAudioBtn.addActionListener(e -> loadAudio());
        line2.add(loadAudioBtn);

        JButton pdfBtn = createButton("📄 PDF → XML", new Color(120, 60, 130));
        pdfBtn.addActionListener(e -> convertPdfToXml());
        line2.add(pdfBtn);

        JButton audiverisBtn = createButton("⚙ Audiveris", new Color(60, 60, 80));
        audiverisBtn.addActionListener(e -> {
            PdfToMusicXmlConverter.resetAudiverisPath();
            statusLabel.setText("↺ Caminho do Audiveris redefinido.");
        });
        line2.add(audiverisBtn);

        playPauseBtn = createButton("▶ Play", new Color(39, 174, 96));
        playPauseBtn.addActionListener(e -> togglePlayPause());
        line2.add(playPauseBtn);

        JButton stopAudioBtn = createButton("⏹ Stop", new Color(192, 57, 43));
        stopAudioBtn.addActionListener(e -> stopAudio());
        line2.add(stopAudioBtn);

        audioLabel = createLabel("  Sem áudio");
        audioLabel.setForeground(new Color(150, 150, 180));
        line2.add(audioLabel);

        gameOctaveCombo = new JComboBox<>(
                new String[]{"−1 oitava (mais grave)", "Original", "+1 oitava (mais agudo)"});
        gameOctaveCombo.setSelectedIndex(1);
        gameOctaveCombo.setBackground(new Color(30, 40, 80));
        gameOctaveCombo.setForeground(Color.WHITE);
        gameOctaveCombo.setFont(new Font("Arial", Font.PLAIN, 12));
        gameOctaveCombo.setFocusable(false);
        line2.add(gameOctaveCombo);

        // ── Linha 3 ───────────────────────────────────────────────────────────
        JPanel line3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        line3.setBackground(new Color(30, 30, 55));
        line3.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 90)));

        line3.add(createLabel("🔧 Ajuste de sincronização (seg):"));
        offsetField = new JTextField("0.0", 6);
        line3.add(offsetField);

        JButton applyOffsetBtn = createButton("✓ Aplicar", new Color(52, 73, 94));
        applyOffsetBtn.addActionListener(e -> applyOffset());
        line3.add(applyOffsetBtn);

        JButton resetOffsetBtn = createButton("↺ Restaurar (0,0)", new Color(80, 40, 80));
        resetOffsetBtn.addActionListener(e -> {
            offsetField.setText("0.0");
            offsetPreviewLabel.setText("  Sem ajuste");
        });
        line3.add(resetOffsetBtn);

        offsetPreviewLabel = createLabel("  Sem ajuste");
        offsetPreviewLabel.setForeground(new Color(180, 220, 180));
        line3.add(offsetPreviewLabel);

        JLabel hintLabel = createLabel(
                "   ← notas ANTES do som: offset positivo  |  " +
                        "notas DEPOIS do som: offset negativo");
        hintLabel.setForeground(new Color(130, 130, 160));
        hintLabel.setFont(new Font("Arial", Font.ITALIC, 10));
        line3.add(hintLabel);

        topPanel.add(line1);
        topPanel.add(line2);
        topPanel.add(line3);
        add(topPanel, BorderLayout.NORTH);

        new javax.swing.Timer(200, e -> updateAudioTimeLabel()).start();

        // ── Placar ────────────────────────────────────────────────────────────
        JPanel scorePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 4));
        scorePanel.setBackground(new Color(15, 52, 96));
        scorePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));

        JLabel scoreTitle = new JLabel("📊 PLACAR");
        scoreTitle.setForeground(new Color(255, 215, 0));
        scoreTitle.setFont(new Font("Arial", Font.BOLD, 14));
        scorePanel.add(scoreTitle);

        perfectLabel    = createScoreLabel("⭐ Perfeito: 0",  new Color(255, 215, 0));
        greatLabel      = createScoreLabel("✓ Ótimo: 0",      new Color(39, 174, 96));
        goodLabel       = createScoreLabel("👍 Bom: 0",        new Color(52, 152, 219));
        okLabel         = createScoreLabel("✅ OK: 0",         new Color(100, 200, 180));
        missedLabel     = createScoreLabel("✗ Errou: 0",      new Color(231, 76, 60));
        precisionLabel  = createScoreLabel("Precisão: 0,0%",  new Color(255, 215, 0));
        totalScoreLabel = createScoreLabel("🎤 0,0s / 0,0s",  Color.WHITE);

        for (JLabel l : new JLabel[]{perfectLabel, greatLabel, goodLabel, okLabel,
                missedLabel, precisionLabel, totalScoreLabel})
            scorePanel.add(l);

        // ── Canvas de notas ───────────────────────────────────────────────────
        notePanel = new NoteScrollPanel(940, 300);
        JPanel noteWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 5));
        noteWrapper.setBackground(new Color(22, 33, 62));
        noteWrapper.add(notePanel);

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBackground(new Color(22, 33, 62));
        centerPanel.add(scorePanel);
        centerPanel.add(noteWrapper);

        // ── Biblioteca ────────────────────────────────────────────────────────
        libraryPanel = new LibraryPanel(new LibraryPanel.Listener() {
            @Override
            public void onLoad(MusicLibrary.SavedMusic music) {
                applyLibraryMusic(music);
            }

            @Override
            public LibraryPanel.SaveRequest onSaveRequested() {
                if (lastXmlFile == null) return null;
                LibraryPanel.SaveRequest req = new LibraryPanel.SaveRequest();
                req.xmlFile         = lastXmlFile;
                req.audioFile       = (lastAudioPath != null)
                        ? new File(lastAudioPath) : null;
                req.realDurationMin = (realDuration > 0) ? realDuration / 60.0 : 0.0;
                req.offsetSeconds   = getOffset();
                req.selectedVoice   = Math.max(0, voiceCombo.getSelectedIndex());
                return req;
            }
        });

        JPanel mainCenter = new JPanel(new BorderLayout(4, 0));
        mainCenter.setBackground(new Color(22, 33, 62));
        mainCenter.add(centerPanel,  BorderLayout.CENTER);
        mainCenter.add(libraryPanel, BorderLayout.EAST);
        add(mainCenter, BorderLayout.CENTER);

        // ── Botões de modo + switch ───────────────────────────────────────────
        JPanel bottomPanel = new JPanel();
        bottomPanel.setBackground(new Color(22, 33, 62));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        modePanel.setBackground(new Color(22, 33, 62));

        JButton gameBtn  = createBigButton("🎮 CANTAR",   new Color(39, 174, 96));
        JButton learnBtn = createBigButton("📚 APRENDER", new Color(52, 152, 219));
        JButton stopBtn  = createBigButton("⏹ PARAR",     new Color(231, 76, 60));

        gameBtn.addActionListener(e  -> startGameMode());
        learnBtn.addActionListener(e -> startLearningMode());
        stopBtn.addActionListener(e  -> stopCurrentMode());

        modePanel.add(gameBtn);
        modePanel.add(learnBtn);
        modePanel.add(stopBtn);

        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(2, 44));
        sep.setForeground(new Color(80, 80, 110));
        modePanel.add(sep);

        recordSwitch = new RecordSwitch();
        recordSwitch.addItemListener(e -> recordSwitch.repaint());
        modePanel.add(recordSwitch);

        statusLabel = createLabel("Aguardando...");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        bottomPanel.add(modePanel);
        bottomPanel.add(statusLabel);
        bottomPanel.add(Box.createVerticalStrut(8));
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Biblioteca
    // ═════════════════════════════════════════════════════════════════════════
    private void applyLibraryMusic(MusicLibrary.SavedMusic music) {
        try {
            MusicXMLParser.ParseResult result =
                    MusicXMLParser.parse(music.xmlFile.getAbsolutePath());
            notes       = result.allNotes;
            xmlDuration = result.totalDuration;
            allVoices   = result.voices;
            lastXmlFile = music.xmlFile;

            musicName = (music.name != null && !music.name.isBlank())
                    ? music.name.trim()
                    : music.xmlFile.getName()
                    .replaceAll("(?i)\\.(xml|musicxml)$", "").trim();

            voiceCombo.removeAllItems();
            for (int i = 0; i < allVoices.size(); i++)
                voiceCombo.addItem(result.voiceNames.get(i)
                        + " (" + allVoices.get(i).size() + " notas)");

            int vi = Math.min(music.selectedVoice,
                    Math.max(0, allVoices.size() - 1));
            if (!allVoices.isEmpty()) {
                voiceCombo.setSelectedIndex(vi);
                notes = allVoices.get(vi);
            }
            notePanel.setNotes(notes);
            infoLabel.setText(String.format("✓ %d notas | %.1f min | %d vozes",
                    result.allNotes.size(), xmlDuration / 60, allVoices.size()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao carregar XML:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // ── Carrega áudio (qualquer formato) ──────────────────────────────────
        if (music.audioFile != null && music.audioFile.exists()) {
            lastAudioPath = music.audioFile.getAbsolutePath();
            audioPlayer.load(lastAudioPath);
            audioLabel.setText("  🎵 " + music.audioFile.getName());
        } else {
            lastAudioPath = null;
            audioLabel.setText("  Sem áudio");
            if (music.audioFile != null)
                JOptionPane.showMessageDialog(this,
                        "Áudio não encontrado:\n" + music.audioFile.getAbsolutePath() +
                                "\n\nCarregue-o manualmente.",
                        "Aviso", JOptionPane.WARNING_MESSAGE);
        }
        updatePlayPauseButton();

        if (music.realDurationMin > 0) {
            realDuration = music.realDurationMin * 60.0;
            durationField.setText(
                    String.format("%.2f", music.realDurationMin).replace(",", "."));
        } else {
            realDuration = 0.0;
            durationField.setText(
                    String.format("%.2f", xmlDuration / 60).replace(",", "."));
        }

        offsetField.setText(
                String.format("%.2f", music.offsetSeconds).replace(",", "."));
        applyOffset();
        libraryPanel.setName(music.name);
        statusLabel.setText("✓ Biblioteca: \"" + music.name + "\" carregada!");
    }

    // ── Placar ────────────────────────────────────────────────────────────────
    private void updateScoreDisplay(KaraokeGameMode.ScoreData d) {
        perfectLabel.setText("⭐ Perfeito: "  + d.perfect);
        greatLabel.setText("✓ Ótimo: "        + d.great);
        goodLabel.setText("👍 Bom: "           + d.good);
        okLabel.setText("✅ OK: "              + d.ok);
        missedLabel.setText("✗ Errou: "       + d.missed);
        precisionLabel.setText(
                String.format("Precisão: %.1f%%", d.precision).replace(".", ","));
        totalScoreLabel.setText(
                String.format("🎤 %.1fs / %.1fs",
                        d.totalScore, d.totalDuration).replace(".", ","));
    }

    // ── Offset ────────────────────────────────────────────────────────────────
    private double getOffset() {
        try {
            return Double.parseDouble(
                    offsetField.getText().trim().replace(",", "."));
        } catch (NumberFormatException e) { return 0.0; }
    }

    private void applyOffset() {
        double off = getOffset();
        offsetField.setText(String.format("%.2f", off).replace(",", "."));
        if (off == 0.0)
            offsetPreviewLabel.setText("  Sem ajuste");
        else if (off > 0.0)
            offsetPreviewLabel.setText(
                    String.format("  Notas atrasadas %.2fs em relação ao áudio", off));
        else
            offsetPreviewLabel.setText(
                    String.format("  Notas adiantadas %.2fs em relação ao áudio",
                            Math.abs(off)));
    }

    // ── Duração ───────────────────────────────────────────────────────────────
    private void adjustTime() {
        try {
            realDuration = Double.parseDouble(
                    durationField.getText().trim().replace(",", ".")) * 60;
            statusLabel.setText(String.format(
                    "⏱ Duração ajustada para %.2f min", realDuration / 60));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                    "Duração inválida!", "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreOriginalDuration() {
        realDuration = 0.0;
        if (xmlDuration > 0)
            durationField.setText(
                    String.format("%.2f", xmlDuration / 60).replace(",", "."));
        statusLabel.setText("↺ Duração restaurada para o tempo do XML");
    }

    // ── Áudio (qualquer formato) ──────────────────────────────────────────────

    /**
     * Abre seletor de arquivo aceitando MP3, WAV, FLAC, OGG e Opus.
     * Se FFmpeg estiver disponível, qualquer formato será convertido para
     * Opus ao salvar na biblioteca; sem FFmpeg, é salvo como está.
     */
    private void loadAudio() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecionar arquivo de áudio");

        // Filtro principal: todos os formatos suportados
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "Áudio (MP3, WAV, FLAC, OGG, Opus)",
                "mp3", "wav", "flac", "ogg", "opus"));

        // Filtros individuais para quem preferir navegar por tipo
        chooser.addChoosableFileFilter(
                new FileNameExtensionFilter("MP3 (*.mp3)",   "mp3"));
        chooser.addChoosableFileFilter(
                new FileNameExtensionFilter("WAV (*.wav)",   "wav"));
        chooser.addChoosableFileFilter(
                new FileNameExtensionFilter("FLAC (*.flac)", "flac"));
        chooser.addChoosableFileFilter(
                new FileNameExtensionFilter("OGG (*.ogg)",   "ogg"));
        chooser.addChoosableFileFilter(
                new FileNameExtensionFilter("Opus (*.opus)", "opus"));

        // Seleciona o filtro "todos" por padrão
        chooser.setFileFilter(chooser.getChoosableFileFilters()[1]);
        chooser.setAcceptAllFileFilterUsed(true);

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File chosen = chooser.getSelectedFile();
        lastAudioPath = chosen.getAbsolutePath();
        audioPlayer.load(lastAudioPath);
        audioLabel.setText("  🎵 " + chosen.getName());
        statusLabel.setText("✓ Áudio carregado: " + chosen.getName());
        updatePlayPauseButton();
    }

    private void togglePlayPause() {
        if (!audioPlayer.isLoaded()) {
            JOptionPane.showMessageDialog(this, "Carregue um áudio primeiro!");
            return;
        }
        if      (audioPlayer.isPlaying()) audioPlayer.pause();
        else if (audioPlayer.isPaused())  audioPlayer.resume();
        else                              audioPlayer.play();
        updatePlayPauseButton();
    }

    private void stopAudio() {
        audioPlayer.stop();
        if (lastAudioPath != null) {
            audioPlayer.load(lastAudioPath);
            audioLabel.setText("  🎵 " + new File(lastAudioPath).getName());
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
            audioLabel.setText(
                    String.format("  🎵 %d:%05.2f", (int) (t / 60), t % 60));
        }
        updatePlayPauseButton();
    }

    // ── XML ───────────────────────────────────────────────────────────────────
    private void loadXml() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecionar MusicXML");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "MusicXML (*.xml, *.musicxml)", "xml", "musicxml"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File xmlFile = chooser.getSelectedFile();
            MusicXMLParser.ParseResult result = MusicXMLParser.parse(xmlFile.getAbsolutePath());
            notes = result.allNotes; xmlDuration = result.totalDuration;
            allVoices = result.voices; lastXmlFile = xmlFile;

            musicName = xmlFile.getName()
                    .replaceAll("(?i)\\.(xml|musicxml)$", "")
                    .trim();

            voiceCombo.removeAllItems();
            for (int i = 0; i < allVoices.size(); i++)
                voiceCombo.addItem(result.voiceNames.get(i)
                        + " (" + allVoices.get(i).size() + " notas)");
            if (!allVoices.isEmpty()) { voiceCombo.setSelectedIndex(0); notes = allVoices.get(0); }
            infoLabel.setText(String.format("✓ %d notas | %.1f min | %d vozes",
                    result.allNotes.size(), xmlDuration / 60, allVoices.size()));
            durationField.setText(String.format("%.2f", xmlDuration / 60).replace(",", "."));
            notePanel.setNotes(notes);
            statusLabel.setText("✓ XML carregado!");
            libraryPanel.suggestName(musicName);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Erro ao carregar XML:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onVoiceChange() {
        int idx = voiceCombo.getSelectedIndex();
        if (idx >= 0 && idx < allVoices.size()) { notes = allVoices.get(idx); notePanel.setNotes(notes); }
    }

    // ── Modos de jogo ─────────────────────────────────────────────────────────
    private void startGameMode() {
        if (notes.isEmpty()) { JOptionPane.showMessageDialog(this, "Carregue um XML primeiro!"); return; }
        stopCurrentMode();

        if (!sharedMic.isRunning()) {
            try { sharedMic.start(); }
            catch (javax.sound.sampled.LineUnavailableException ex) {
                JOptionPane.showMessageDialog(this,
                        "Não foi possível abrir o microfone:\n" + ex.getMessage(),
                        "Erro de Áudio", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        audioDetector.startListening();

        double timeScale   = (realDuration > 0 && xmlDuration > 0) ? realDuration / xmlDuration : 1.0;
        double offset      = getOffset();
        int    octaveShift = OCTAVE_OPTIONS[gameOctaveCombo.getSelectedIndex()];

        List<MusicNote> copy = new ArrayList<>();
        for (MusicNote n : notes) copy.add(n.copy());
        updateScoreDisplay(new KaraokeGameMode.ScoreData());

        KaraokeGameMode mode = new KaraokeGameMode(
                copy, audioDetector, notePanel, this::updateScoreDisplay,
                timeScale, audioPlayer, offset);
        mode.setOctaveShift(octaveShift);

        // ── Callback chamado tanto ao fim natural quanto pelo PARAR ──────────
        mode.setOnFinishCallback(this::stopRecordingIfActive);

        currentMode = mode;
        mode.start();

        gameOctaveCombo.setEnabled(false);
        recordSwitch.setEnabled(false);

        if (recordSwitch.isSelected()) {
            String rawVoice  = voiceCombo.getSelectedIndex() >= 0
                    ? (String) voiceCombo.getSelectedItem() : "Voz";
            String voiceName = rawVoice == null ? "Voz"
                    : rawVoice.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
            String baseName  = (musicName.isBlank() ? "Gravação" : musicName)
                    + " - " + voiceName + " - Completo";

            voiceRecorder.setOutputName(baseName);
            sharedMic.addConsumer(voiceRecorder);
            voiceRecorder.start();
            statusLabel.setText(offset != 0.0
                    ? String.format("🎮 CANTANDO + 🔴 GRAVANDO (offset %.2fs, oitava %+d)",
                    offset, octaveShift)
                    : String.format("🎮 CANTANDO + 🔴 GRAVANDO (oitava %+d)",
                    octaveShift));
        } else {
            statusLabel.setText(offset != 0.0
                    ? String.format("🎮 CANTANDO! (offset %.2fs, oitava %+d)", offset, octaveShift)
                    : String.format("🎮 CANTANDO! Cante junto! (oitava %+d)", octaveShift));
        }
    }

    private void startLearningMode() {
        if (notes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Carregue um XML primeiro!"); return;
        }
        stopCurrentMode();

        if (!sharedMic.isRunning()) {
            try { sharedMic.start(); }
            catch (javax.sound.sampled.LineUnavailableException ex) {
                JOptionPane.showMessageDialog(this,
                        "Não foi possível abrir o microfone:\n" + ex.getMessage(),
                        "Erro de Áudio", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        audioDetector.startListening();

        double timeScale = (realDuration > 0 && xmlDuration > 0)
                ? realDuration / xmlDuration : 1.0;
        double offset    = getOffset();

        List<MusicNote> copy = new ArrayList<>();
        for (MusicNote n : notes) copy.add(n.copy());

        String rawVoice  = voiceCombo.getSelectedIndex() >= 0
                ? (String) voiceCombo.getSelectedItem() : "Voz";
        String voiceName = rawVoice == null ? "Voz"
                : rawVoice.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();

        LearningMode mode = new LearningMode(
                copy, audioDetector, notePanel, timeScale, audioPlayer, offset,
                voiceRecorder, sharedMic);

        currentMode = mode;
        mode.start();

        statusLabel.setText(offset != 0.0
                ? String.format("📚 APRENDENDO! (offset %.2fs)", offset)
                : "📚 APRENDENDO!");
    }

    private void stopRecordingIfActive() {
        if (voiceRecorder != null && voiceRecorder.isRecording()) {
            voiceRecorder.stop();
            sharedMic.removeConsumer(voiceRecorder);
        }
        if (recordSwitch != null) recordSwitch.setEnabled(true);
        if (gameOctaveCombo != null) gameOctaveCombo.setEnabled(true);
    }

    private void stopCurrentMode() {
        if (currentMode instanceof KaraokeGameMode m) m.stop();
        else if (currentMode instanceof LearningMode m) m.stop();
        currentMode = null;

        stopRecordingIfActive();
        audioDetector.stopListening();

        if (voiceRecorder.isRecording()) {
            voiceRecorder.stop();
            sharedMic.removeConsumer(voiceRecorder);
            statusLabel.setText("⏳ Salvando gravação...");
            // recordSwitch reabilitado pelo callback onSaved/onError
        } else {
            recordSwitch.setEnabled(true);
            statusLabel.setText("⏹ Parado");
        }

        sharedMic.stop();
        gameOctaveCombo.setEnabled(true);
    }

    // ── Helpers de UI ─────────────────────────────────────────────────────────
    private JButton createButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFont(new Font("Arial", Font.BOLD, 11));
        b.setFocusPainted(false); b.setBorderPainted(false);
        return b;
    }

    private JButton createBigButton(String text, Color bg) {
        JButton b = createButton(text, bg);
        b.setFont(new Font("Arial", Font.BOLD, 14));
        b.setPreferredSize(new Dimension(200, 50));
        return b;
    }

    private JLabel createLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.WHITE);
        l.setFont(new Font("Arial", Font.PLAIN, 11));
        return l;
    }

    private JLabel createScoreLabel(String text, Color color) {
        JLabel l = new JLabel("  " + text + "  ");
        l.setForeground(color); l.setFont(new Font("Arial", Font.BOLD, 12));
        return l;
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new KaraokeApp().setVisible(true));
    }
}