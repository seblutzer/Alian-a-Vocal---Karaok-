
package com.karaoke;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Painel de rolagem de notas com:
 *  - Ponto vermelho no pitch instantâneo
 *  - Trilha histórica mostrando a variação real da frequência
 *  - Escala de frequência contínua (logarítmica) — não "snapa" para MIDI
 */
public class NoteScrollPanel extends JPanel {

    // ── Cores ────────────────────────────────────────────────────────────────
    private static final Color BG_COLOR         = new Color(26, 26, 46);
    private static final Color HIT_LINE_COLOR   = new Color(0, 255, 0);
    private static final Color GRID_LINE_COLOR  = new Color(42, 42, 62);
    private static final Color NOTE_LABEL_COLOR = new Color(136, 136, 136);
    private static final Color COLOR_PERFECT    = new Color(255, 215, 0);
    private static final Color COLOR_GREAT      = new Color(52, 152, 219);
    private static final Color COLOR_GOOD       = new Color(39, 174, 96);
    private static final Color COLOR_OK         = new Color(116, 115, 115);
    private static final Color COLOR_MISSED     = new Color(231, 76, 60);
    private static final Color COLOR_FUTURE     = new Color(155, 89, 182);

    // Ponto atual e trilha
    private static final Color COLOR_DOT        = Color.RED;
    private static final Color COLOR_TRAIL_HEAD = new Color(255, 80, 80);
    private static final Color COLOR_TRAIL_TAIL = new Color(120, 20, 20);

    // ── Configurações ────────────────────────────────────────────────────────
    private static final int    NOTE_HEIGHT       = 20;
    private static final double PIXELS_PER_SEC    = 100.0;
    private static final double WINDOW_TIME       = 8.0;
    private static final int    HIT_LINE_X        = 150;
    private static final double TRAIL_DURATION    = 3.5;
    private static final int    DOT_RADIUS        = 6;

    private static final String[] NOTE_NAMES = {
            "C","C#","D","D#","E","F","F#","G","G#","A","A#","B"
    };

    // ── Estado ───────────────────────────────────────────────────────────────
    private List<MusicNote> notes = List.of();
    private double currentTime    = 0.0;
    private int    minMidi        = 48;
    private int    maxMidi        = 84;

    private final Deque<double[]> trail = new ArrayDeque<>(); // {musicTime, freqHz}

    public NoteScrollPanel(int width, int height) {
        setPreferredSize(new Dimension(width, height));
        setBackground(BG_COLOR);
    }

    // ── API pública ──────────────────────────────────────────────────────────

    public void setNotes(List<MusicNote> notes) {
        this.notes = notes;
        if (!notes.isEmpty()) {
            minMidi = notes.stream().mapToInt(n -> n.midi).min().orElse(48) - 3;
            maxMidi = notes.stream().mapToInt(n -> n.midi).max().orElse(84) + 3;
        }
        trail.clear();
        repaint();
    }

    public void updateTime(double time) {
        this.currentTime = time;
        double cutoff = time - TRAIL_DURATION;
        while (!trail.isEmpty() && trail.peekFirst()[0] < cutoff) {
            trail.pollFirst();
        }
        SwingUtilities.invokeLater(this::repaint);
    }

    public void addTrailPoint(double musicTime, double freqHz) {
        if (freqHz > 0) {
            trail.addLast(new double[]{musicTime, freqHz});
        }
        SwingUtilities.invokeLater(this::repaint);
    }

    // ── Renderização ─────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(BG_COLOR);
        g2.fillRect(0, 0, w, h);

        drawGrid(g2, w, h);
        drawNotes(g2, h);
        drawTrail(g2, h);
        drawCurrentDot(g2, h);
        drawHitLine(g2, h);
    }

    private void drawGrid(Graphics2D g2, int w, int h) {
        g2.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 8));
        for (int midi = minMidi; midi <= maxMidi; midi++) {
            int y = midiToY(midi, h);
            String name = NOTE_NAMES[midi % 12];
            if (!name.contains("#")) {
                g2.setColor(GRID_LINE_COLOR);
                g2.drawLine(0, y, w, y);
            }
            g2.setColor(NOTE_LABEL_COLOR);
            g2.drawString(name + ((midi / 12) - 1), 5, y + 4);
        }
    }

    private void drawHitLine(Graphics2D g2, int h) {
        g2.setColor(HIT_LINE_COLOR);
        g2.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10, new float[]{5, 5}, 0));
        g2.drawLine(HIT_LINE_X, 0, HIT_LINE_X, h);
        g2.setStroke(new BasicStroke(1));
    }

    private void drawNotes(Graphics2D g2, int h) {
        double timeEnd = currentTime + WINDOW_TIME;
        g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));

        for (MusicNote note : notes) {
            if (note.endTime < currentTime - 2.0 || note.startTime > timeEnd) continue;

            int xStart = timeToX(note.startTime);
            int xEnd   = timeToX(note.endTime);
            int y      = midiToY(note.midi, h);
            int noteW  = Math.max(xEnd - xStart, 4);

            Color noteColor;
            if (note.wasEvaluated) {
                noteColor = switch (note.rating) {
                    case "Perfeito!" -> COLOR_PERFECT;
                    case "Ótimo"     -> COLOR_GREAT;
                    case "Bom"       -> COLOR_GOOD;
                    case "OK"        -> COLOR_OK;
                    default          -> COLOR_MISSED;
                };
            } else if (note.endTime < currentTime) {
                noteColor = COLOR_MISSED;
            } else {
                noteColor = COLOR_FUTURE;
            }

            g2.setColor(noteColor);
            g2.fillRoundRect(xStart, y - NOTE_HEIGHT / 2, noteW, NOTE_HEIGHT, 6, 6);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2));
            g2.drawRoundRect(xStart, y - NOTE_HEIGHT / 2, noteW, NOTE_HEIGHT, 6, 6);
            g2.setStroke(new BasicStroke(1));

            String text = !note.lyric.isEmpty() ? note.lyric : note.pitch;
            FontMetrics fm = g2.getFontMetrics();
            int tx = xStart + (noteW - fm.stringWidth(text)) / 2;
            g2.setColor(Color.WHITE);
            g2.drawString(text, tx, y + 4);

            if (note.wasEvaluated && note.endTime < currentTime) {
                g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 14));
                int iconX = xEnd + 30;
                switch (note.rating) {
                    case "Perfeito!" -> { g2.setColor(COLOR_PERFECT); g2.drawString("★", iconX, y + 5); }
                    case "Ótimo"     -> { g2.setColor(COLOR_GREAT);   g2.drawString("✓", iconX, y + 5); }
                    case "Bom"       -> { g2.setColor(COLOR_GOOD);    g2.drawString("↑", iconX, y + 5); }
                    case "OK"       ->  { g2.setColor(COLOR_OK);      g2.drawString("±", iconX, y + 5); }
                    default          -> { g2.setColor(COLOR_MISSED);  g2.drawString("✗", iconX, y + 5); }
                }
                g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 10));
            }
        }
    }

    /**
     * Desenha a trilha histórica de pitch.
     *
     * CORREÇÃO: todos os componentes de cor são clampados em [0, 255]
     * com Math.clamp() para evitar IllegalArgumentException quando
     * "progress" sai do intervalo [0.0, 1.0] por imprecisão de ponto flutuante
     * (ex.: ao fim da música, quando o tempo pode produzir valores negativos).
     */
    private void drawTrail(Graphics2D g2, int h) {
        if (trail.size() < 2) return;

        double[][] arr    = trail.toArray(new double[0][]);
        double     oldest = arr[0][arr[0].length - 1];              // arr[0][0] = tempo do primeiro ponto
        double     newest = arr[arr.length - 1][0];
        // ── corrigido: oldest deve ser o tempo do PRIMEIRO elemento ──────────
        oldest = arr[0][0];
        double span = Math.max(newest - oldest, 0.001);

        g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 0; i < arr.length - 1; i++) {
            double t0 = arr[i][0],     f0 = arr[i][1];
            double t1 = arr[i + 1][0], f1 = arr[i + 1][1];

            // Lacuna grande entre amostras = silêncio, não conectar
            if (t1 - t0 > 0.15) continue;

            int x0 = timeToX(t0), y0 = freqToY(f0, h);
            int x1 = timeToX(t1), y1 = freqToY(f1, h);

            // progress ∈ [0.0, 1.0] — clamp explícito para blindar contra
            // imprecisão de ponto flutuante ao fim da música
            float progress = (float) Math.max(0.0, Math.min(1.0, (t0 - oldest) / span));

            int alpha = (int) (60 + 160 * progress);   // 60 → 220
            int red   = (int) (COLOR_TRAIL_TAIL.getRed()   + progress * (COLOR_TRAIL_HEAD.getRed()   - COLOR_TRAIL_TAIL.getRed()));
            int green = (int) (COLOR_TRAIL_TAIL.getGreen() + progress * (COLOR_TRAIL_HEAD.getGreen() - COLOR_TRAIL_TAIL.getGreen()));
            int blue  = (int) (COLOR_TRAIL_TAIL.getBlue()  + progress * (COLOR_TRAIL_HEAD.getBlue()  - COLOR_TRAIL_TAIL.getBlue()));

            // Clamp final: garante intervalo válido [0, 255] em TODOS os canais
            g2.setColor(new Color(
                    Math.max(0, Math.min(255, red)),
                    Math.max(0, Math.min(255, green)),
                    Math.max(0, Math.min(255, blue)),
                    Math.max(0, Math.min(255, alpha))
            ));
            g2.drawLine(x0, y0, x1, y1);
        }

        g2.setStroke(new BasicStroke(1));
    }

    private void drawCurrentDot(Graphics2D g2, int h) {
        if (trail.isEmpty()) return;

        double[] last = null;
        for (double[] p : trail) last = p;
        if (last == null || last[1] <= 0) return;

        int x = timeToX(last[0]);
        int y = freqToY(last[1], h);

        g2.setColor(new Color(255, 80, 80, 80));
        g2.fillOval(x - DOT_RADIUS - 3, y - DOT_RADIUS - 3,
                (DOT_RADIUS + 3) * 2, (DOT_RADIUS + 3) * 2);

        g2.setColor(COLOR_DOT);
        g2.fillOval(x - DOT_RADIUS, y - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);

        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawOval(x - DOT_RADIUS, y - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);
        g2.setStroke(new BasicStroke(1));
    }

    // ── Conversões de coordenadas ─────────────────────────────────────────────

    private int midiToY(int midi, int h) {
        int span = Math.max(1, maxMidi - minMidi);
        double normalized = (midi - minMidi) / (double) span;
        return (int) (h * (1 - normalized) * 0.8 + h * 0.1);
    }

    private int freqToY(double freqHz, int h) {
        if (freqHz <= 0) return h / 2;
        double minFreq = PitchDetector.midiToHz(minMidi);
        double maxFreq = PitchDetector.midiToHz(maxMidi);
        double logFreq    = Math.log(Math.max(freqHz, minFreq));
        double logMin     = Math.log(minFreq);
        double logMax     = Math.log(maxFreq);
        double normalized = (logFreq - logMin) / Math.max(logMax - logMin, 0.001);
        return (int) (h * (1 - normalized) * 0.8 + h * 0.1);
    }

    private int timeToX(double time) {
        return (int) (HIT_LINE_X + (time - currentTime) * PIXELS_PER_SEC);
    }
}
