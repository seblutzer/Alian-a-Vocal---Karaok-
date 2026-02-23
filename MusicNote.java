
package com.karaoke;

import java.awt.Color;

/**
 * Representa uma nota musical no karaokê.
 * Equivalente à classe MusicNote do Python.
 */
public class MusicNote {

    public String pitch;       // Ex: "C4", "D#5"
    public int midi;
    public double duration;    // em segundos
    public double startTime;   // em segundos
    public double endTime;
    public String lyric;

    // Avaliação
    public boolean achieved     = false;
    public double  timeCorrect  = 0.0;   // Tempo total cantado corretamente
    public boolean wasEvaluated = false;
    public double  score        = 0.0;   // Pontuação final (0 a 1)
    public String  rating       = "";    // "Perfeito!", "Ótimo", "Bom", "OK", "Errou"
    public Color   ratingColor  = null;  // Cor da barra após avaliação (null = padrão)

    public MusicNote(String pitch, int midi, double duration,
                     double startTime, String lyric) {
        this.pitch     = pitch;
        this.midi      = midi;
        this.duration  = duration;
        this.startTime = startTime;
        this.lyric     = lyric;
        this.endTime   = startTime + duration;
    }

    public MusicNote shallowCopy() {
        MusicNote c    = new MusicNote(pitch, midi, duration, startTime, lyric);
        c.endTime      = this.endTime;
        c.wasEvaluated = this.wasEvaluated;
        c.timeCorrect  = this.timeCorrect;
        c.score        = this.score;
        c.rating       = this.rating;
        c.achieved     = this.achieved;
        c.ratingColor  = this.ratingColor;  // propaga a cor para a cópia de display
        return c;
    }

    /** Cria uma cópia limpa desta nota (sem dados de avaliação). */
    public MusicNote copy() {
        return new MusicNote(pitch, midi, duration, startTime, lyric);
    }
}
