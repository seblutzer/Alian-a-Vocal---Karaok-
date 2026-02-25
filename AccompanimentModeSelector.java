
package com.karaoke;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Seletor visual de modo de acompanhamento (Segmented Control estilo iOS)
 * com 3 opções: Todas, Exceto Selecionada, Apenas Selecionada
 */
public class AccompanimentModeSelector extends JPanel {

    public interface ModeListener {
        void onModeChanged(MidiAccompanimentPlayer.PlaybackMode mode, String selectedVoiceName);
    }

    private MidiAccompanimentPlayer.PlaybackMode currentMode;
    private ModeListener listener;
    private String selectedVoiceName = "Soprano";
    private int selectedVoiceIndex = 0;

    // ── Cores ──────────────────────────────────────────────────────────────
    private static final Color COLOR_BACKGROUND = new Color(22, 33, 62);
    private static final Color COLOR_BORDER = new Color(80, 100, 140);
    private static final Color COLOR_ACTIVE_BG = new Color(52, 152, 219);
    private static final Color COLOR_ACTIVE_FG = Color.WHITE;
    private static final Color COLOR_INACTIVE_FG = new Color(150, 170, 200);
    private static final Color COLOR_HOVER = new Color(60, 180, 255);

    private static final int BUTTON_HEIGHT = 32;
    private static final int BUTTON_WIDTH = 120;
    private static final int PADDING = 2;
    private static final int CORNER_RADIUS = 6;

    private final Button[] buttons = new Button[3];
    private int hoveredButton = -1;

    // ─────────────────────────────────────────────────────────────────────
    public AccompanimentModeSelector() {
        this.currentMode = MidiAccompanimentPlayer.PlaybackMode.ALL_EXCEPT_SELECTED;
        setOpaque(false);
        setPreferredSize(new Dimension(BUTTON_WIDTH * 3 + PADDING * 4 + 20, BUTTON_HEIGHT + 10));

        buttons[0] = new Button(0, "🎼 Todas", MidiAccompanimentPlayer.PlaybackMode.ALL_VOICES);
        buttons[1] = new Button(1, "🎵 Exceto", MidiAccompanimentPlayer.PlaybackMode.ALL_EXCEPT_SELECTED);
        buttons[2] = new Button(2, "🎤 Apenas", MidiAccompanimentPlayer.PlaybackMode.ONLY_SELECTED);

        buttons[1].selected = true;
        currentMode = buttons[1].mode;

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                for (Button btn : buttons) {
                    if (btn.contains(e.getPoint())) {
                        selectButton(btn.index);
                        break;
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int oldHovered = hoveredButton;
                hoveredButton = -1;
                for (Button btn : buttons) {
                    if (btn.contains(e.getPoint())) {
                        hoveredButton = btn.index;
                        break;
                    }
                }
                if (oldHovered != hoveredButton) repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoveredButton = -1;
                repaint();
            }
        });

        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("<html><b>Modo de Acompanhamento:</b><br>" +
                "🎼 Todas - Reproduz todas as vozes<br>" +
                "🎵 Exceto - Todas exceto a escolhida<br>" +
                "🎤 Apenas - Apenas a voz escolhida</html>");
    }

    // ─────────────────────────────────────────────────────────────────────
    public void setSelectedVoice(int voiceIndex, String voiceName) {
        this.selectedVoiceIndex = voiceIndex;
        this.selectedVoiceName = voiceName;
        repaint();
    }

    public void setModeListener(ModeListener listener) {
        this.listener = listener;
    }

    public MidiAccompanimentPlayer.PlaybackMode getCurrentMode() {
        return currentMode;
    }

    public void setMode(MidiAccompanimentPlayer.PlaybackMode mode) {
        for (Button btn : buttons) {
            if (btn.mode == mode) {
                selectButton(btn.index);
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void selectButton(int index) {
        for (Button btn : buttons) btn.selected = false;
        buttons[index].selected = true;
        currentMode = buttons[index].mode;

        if (listener != null) {
            listener.onModeChanged(currentMode, selectedVoiceName);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int x = 10;
        int y = (getHeight() - BUTTON_HEIGHT) / 2;

        // Desenhar fundo do container
        g2.setColor(COLOR_BACKGROUND);
        g2.fillRect(x - 5, y - 2, BUTTON_WIDTH * 3 + PADDING * 4 + 10, BUTTON_HEIGHT + 4);

        // Desenhar borda
        g2.setColor(COLOR_BORDER);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(x - 5, y - 2, BUTTON_WIDTH * 3 + PADDING * 4 + 10,
                BUTTON_HEIGHT + 4, CORNER_RADIUS, CORNER_RADIUS);

        // Desenhar botões
        for (Button btn : buttons) {
            btn.paint(g2, x, y);
            x += BUTTON_WIDTH + PADDING;
        }

        // Informação da voz selecionada
        g2.setColor(new Color(150, 200, 255));
        g2.setFont(new Font("Segoe UI Symbol", Font.ITALIC, 9));
        FontMetrics fm = g2.getFontMetrics();
        String info = "Voz: " + selectedVoiceName;
        g2.drawString(info, 10, y + BUTTON_HEIGHT + fm.getAscent() + 4);

        g2.dispose();
    }

    // ─────────────────────────────────────────────────────────────────────
    private class Button {
        int index;
        String text;
        MidiAccompanimentPlayer.PlaybackMode mode;
        boolean selected;
        Rectangle bounds;

        Button(int index, String text, MidiAccompanimentPlayer.PlaybackMode mode) {
            this.index = index;
            this.text = text;
            this.mode = mode;
            this.selected = false;
            this.bounds = new Rectangle();
        }

        boolean contains(Point p) {
            return bounds.contains(p);
        }

        void paint(Graphics2D g2, int x, int y) {
            bounds.setBounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT);

            // Background
            Color bgColor = selected
                    ? COLOR_ACTIVE_BG
                    : (hoveredButton == index ? COLOR_HOVER.brighter() : new Color(40, 50, 90));

            g2.setColor(bgColor);
            g2.fillRoundRect(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, CORNER_RADIUS, CORNER_RADIUS);

            // Border
            g2.setColor(selected ? COLOR_ACTIVE_BG.darker() : COLOR_BORDER);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, CORNER_RADIUS, CORNER_RADIUS);

            // Text
            g2.setColor(selected ? COLOR_ACTIVE_FG : COLOR_INACTIVE_FG);
            g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (BUTTON_WIDTH - fm.stringWidth(text)) / 2;
            int ty = y + ((BUTTON_HEIGHT - fm.getHeight()) / 2) + fm.getAscent();
            g2.drawString(text, tx, ty);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Accompaniment Mode Selector");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(500, 150);
            frame.setLocationRelativeTo(null);

            AccompanimentModeSelector selector = new AccompanimentModeSelector();
            selector.setModeListener((mode, voice) ->
                    System.out.println("Mode: " + mode + " | Voice: " + voice)
            );
            selector.setSelectedVoice(0, "Soprano");

            frame.add(selector, BorderLayout.CENTER);
            frame.setVisible(true);
        });
    }
}
