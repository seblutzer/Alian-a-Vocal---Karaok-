
package com.karaoke;

import javax.swing.*;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class KaraokeGameMode {

    public static final Color COLOR_PERFECT = new Color(255, 215,   0);
    public static final Color COLOR_GREAT   = new Color( 50, 205,  50);
    public static final Color COLOR_GOOD    = new Color(  0, 191, 255);
    public static final Color COLOR_OK      = new Color(255, 165,   0);
    public static final Color COLOR_MISSED  = new Color(220,  50,  60);

    public static class ScoreData {
        public int    perfect, great, good, ok, missed, total, evaluated;
        public double totalScore;
        public double totalDuration;
        public double elapsedDuration;
        public double precision;
    }

    private Runnable onFinishCallback = null;

    private static final int    OCTAVE_SEMITONES       = 12;
    private static final long   CHECK_INTERVAL_MS      = 20;
    private static final double SHORT_NOTE_THRESHOLD_S = 0.5;

    private final List<MusicNote>     notes;
    private final AudioDetector       audioDetector;
    private final NoteScrollPanel     canvas;
    private final Consumer<ScoreData> scoreCallback;
    private final double              timeScale;
    private final AudioPlayer         AudioPlayer;
    private final double              noteOffset;

    private volatile boolean isPlaying   = false;
    private volatile int     octaveShift = 0;
    private long startRealTime;

    private final AtomicBoolean resultsShown = new AtomicBoolean(false);

    private final Map<MusicNote, MusicNote>    displayMap     = new IdentityHashMap<>();
    private final Set<MusicNote>               sampleEligible = new HashSet<>();
    private final Map<MusicNote, List<Double>> pitchSamples   = new IdentityHashMap<>();

    private final Map<MusicNote, Double> noteActivationTime = new IdentityHashMap<>();
    private final Map<MusicNote, Double> firstSampleTime    = new IdentityHashMap<>();

    private int notesPerfect = 0, notesGreat = 0, notesGood = 0,
            notesOk     = 0, notesMissed = 0;

    private double totalScore      = 0.0;
    private double totalDuration   = 0.0;
    private double elapsedDuration = 0.0;

    public KaraokeGameMode(List<MusicNote> notes, AudioDetector audioDetector,
                           NoteScrollPanel canvas, Consumer<ScoreData> scoreCallback,
                           double timeScale, AudioPlayer AudioPlayer, double noteOffset) {
        this.notes         = notes;
        this.audioDetector = audioDetector;
        this.canvas        = canvas;
        this.scoreCallback = scoreCallback;
        this.timeScale     = timeScale;
        this.AudioPlayer   = AudioPlayer;
        this.noteOffset    = noteOffset;
    }

    public void setOctaveShift(int shift) { this.octaveShift = shift; }

    private List<MusicNote> buildDisplayNotes() {
        displayMap.clear();
        if (octaveShift == 0) return notes;
        List<MusicNote> shifted = new ArrayList<>(notes.size());
        for (MusicNote n : notes) {
            MusicNote copy = n.shallowCopy();
            copy.midi = Math.max(0, Math.min(127, n.midi + octaveShift * OCTAVE_SEMITONES));
            displayMap.put(n, copy);
            shifted.add(copy);
        }
        return shifted;
    }

    private void syncDisplayNote(MusicNote original) {
        MusicNote copy = displayMap.get(original);
        if (copy == null) return;
        copy.wasEvaluated = original.wasEvaluated;
        copy.timeCorrect  = original.timeCorrect;
        copy.score        = original.score;
        copy.rating       = original.rating;
        copy.achieved     = original.achieved;
        copy.ratingColor  = original.ratingColor;
    }

    private void classifyNotes() {
        sampleEligible.clear();
        pitchSamples.clear();
        noteActivationTime.clear();
        firstSampleTime.clear();

        int n = notes.size();
        for (int i = 0; i < n; i++) {
            MusicNote cur = notes.get(i);
            boolean isShort            = cur.duration <= SHORT_NOTE_THRESHOLD_S;
            boolean isFirstAfterChange = (i == 0) || (notes.get(i - 1).midi != cur.midi);
            if (isShort || isFirstAfterChange) {
                sampleEligible.add(cur);
                pitchSamples.put(cur, new ArrayList<>());
            }
        }
    }

    public void start() {
        isPlaying       = true;
        resultsShown.set(false);
        startRealTime   = System.currentTimeMillis();
        totalScore      = 0.0;
        totalDuration   = 0.0;
        elapsedDuration = 0.0;
        notesPerfect    = 0; notesGreat = 0; notesGood = 0;
        notesOk         = 0; notesMissed = 0;

        for (MusicNote note : notes) {
            note.startTime  *= timeScale;
            note.duration   *= timeScale;
            note.endTime     = note.startTime + note.duration;
            note.ratingColor = null;
            totalDuration   += note.duration;
        }

        classifyNotes();

        List<MusicNote> displayNotes = buildDisplayNotes();
        canvas.setNotes(displayNotes);

        audioDetector.startListening();

        if (AudioPlayer != null && AudioPlayer.isLoaded()) {
            AudioPlayer.stop();
            AudioPlayer.setVolume(1f);
            AudioPlayer.seek(0);
            AudioPlayer.play();
        }

        Thread t = new Thread(this::gameLoop, "KaraokeGame-Thread");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        if (!isPlaying) return;

        isPlaying = false;
        audioDetector.stopListening();
        if (AudioPlayer != null) AudioPlayer.stop();

        double currentTime = getCurrentTime();
        for (MusicNote note : notes)
            if (!note.wasEvaluated && note.startTime <= currentTime)
                finalizeNoteEvaluation(note);

        showResults();
    }

    public void onMp3Finished() {
        if (!isPlaying) return;

        isPlaying = false;
        audioDetector.stopListening();

        for (MusicNote note : notes)
            if (!note.wasEvaluated) finalizeNoteEvaluation(note);

        showResults();
    }

    private double getCurrentTime() {
        double raw = (AudioPlayer != null && AudioPlayer.isLoaded())
                ? AudioPlayer.getCurrentTime()
                : (System.currentTimeMillis() - startRealTime) / 1000.0;
        return raw - noteOffset;
    }

    private void gameLoop() {
        long loopStart = System.currentTimeMillis();
        while (isPlaying) {
            double t = getCurrentTime();
            if (t <= 2.0 || (System.currentTimeMillis() - loopStart) > 800) break;
            sleep(20);
        }
        long lastCheck = System.currentTimeMillis();

        while (isPlaying) {
            long   now         = System.currentTimeMillis();
            double currentTime = getCurrentTime();
            double deltaTime   = (now - lastCheck) / 1000.0;
            lastCheck = now;

            if (currentTime < 0) {
                canvas.updateTime(currentTime);
                sleep(CHECK_INTERVAL_MS);
                continue;
            }

            canvas.updateTime(currentTime);
            canvas.addTrailPoint(currentTime, audioDetector.getInstantFreq());

            for (MusicNote note : notes) {
                if (!note.wasEvaluated) {
                    if (note.startTime <= currentTime && currentTime <= note.endTime) {
                        if (!noteActivationTime.containsKey(note))
                            noteActivationTime.put(note, currentTime);
                        checkNoteSinging(note, deltaTime, currentTime);
                    } else if (currentTime > note.endTime) {
                        finalizeNoteEvaluation(note);
                    }
                }
            }

            updateScoreDisplay();

            if (!notes.isEmpty()
                    && currentTime > notes.get(notes.size() - 1).endTime + 2.0) {
                for (MusicNote note : notes)
                    if (!note.wasEvaluated) finalizeNoteEvaluation(note);
                isPlaying = false;
                showResults();
                break;
            }

            sleep(CHECK_INTERVAL_MS);
        }
    }

    public void setOnFinishCallback(Runnable callback) {
        this.onFinishCallback = callback;
    }

    private void checkNoteSinging(MusicNote note, double deltaTime, double currentTime) {
        if (sampleEligible.contains(note)) {
            double detectedFreq = audioDetector.getInstantFreq();
            if (detectedFreq > 0) {
                pitchSamples.get(note).add(detectedFreq);
                if (!firstSampleTime.containsKey(note))
                    firstSampleTime.put(note, currentTime);
            }
        } else {
            double detectedFreq = audioDetector.getAverageFreq();
            if (detectedFreq > 0) {
                int    shiftedMidi = Math.max(0, Math.min(127,
                        note.midi + octaveShift * OCTAVE_SEMITONES));
                double targetFreq  = PitchDetector.midiToHz(shiftedMidi);
                double centsDiff   = Math.abs(
                        PitchDetector.frequencyToCents(detectedFreq, targetFreq));
                if (centsDiff <= audioDetector.toleranceCents)
                    note.timeCorrect += deltaTime;
            }
        }
    }

    private void finalizeNoteEvaluation(MusicNote note) {
        if (note.wasEvaluated) return;
        note.wasEvaluated = true;

        if (sampleEligible.contains(note)) finalizeWithSampleCount(note);
        else                               finalizeWithTimeCorrect(note);

        syncDisplayNote(note);
    }

    private void finalizeWithTimeCorrect(MusicNote note) {
        note.timeCorrect = Math.min(note.timeCorrect, note.duration);

        double noteScore = Math.min(note.timeCorrect * 1.2, note.duration);
        note.score       = noteScore;
        totalScore      += noteScore;
        elapsedDuration += note.duration;

        double pct = note.duration > 0
                ? (note.timeCorrect / note.duration) * 100.0 : 0.0;

        if (pct >= 90) {
            note.rating = "Perfeito!"; note.achieved = true;  note.ratingColor = COLOR_PERFECT; notesPerfect++;
        } else if (pct >= 75) {
            note.rating = "Ótimo";     note.achieved = true;  note.ratingColor = COLOR_GREAT;   notesGreat++;
        } else if (pct >= 50) {
            note.rating = "Bom";       note.achieved = true;  note.ratingColor = COLOR_GOOD;    notesGood++;
        } else if (pct >= 5) {
            note.rating = "OK";        note.achieved = true;  note.ratingColor = COLOR_OK;      notesOk++;
        } else {
            note.rating = "Errou";     note.achieved = false; note.ratingColor = COLOR_MISSED;  notesMissed++;
        }
    }

    private void finalizeWithSampleCount(MusicNote note) {
        elapsedDuration += note.duration;

        List<Double> samples = pitchSamples.getOrDefault(note, List.of());
        int total = samples.size();

        int    shiftedMidi = Math.max(0, Math.min(127,
                note.midi + octaveShift * OCTAVE_SEMITONES));
        double targetFreq  = PitchDetector.midiToHz(shiftedMidi);

        int correct = 0;
        for (double f : samples) {
            double centsDiff = Math.abs(PitchDetector.frequencyToCents(f, targetFreq));
            if (centsDiff <= audioDetector.toleranceCents) correct++;
        }

        double pct = total > 0 ? (correct * 100.0 / total) : 0.0;
        double noteScore;

        if (correct == 0) {
            noteScore        = 0.0;
            note.rating      = "Errou";
            note.achieved    = false;
            note.ratingColor = COLOR_MISSED;
            notesMissed++;
        } else if (pct >= 20.0) {
            noteScore        = note.duration * 1.00;
            note.rating      = "Perfeito!";
            note.achieved    = true;
            note.ratingColor = COLOR_PERFECT;
            notesPerfect++;
        } else if (pct >= 15.0) {
            noteScore        = note.duration * 0.75;
            note.rating      = "Ótimo";
            note.achieved    = true;
            note.ratingColor = COLOR_GREAT;
            notesGreat++;
        } else if (pct >= 10.0) {
            noteScore        = note.duration * 0.50;
            note.rating      = "Bom";
            note.achieved    = true;
            note.ratingColor = COLOR_GOOD;
            notesGood++;
        } else {
            noteScore        = note.duration * 0.25;
            note.rating      = "OK";
            note.achieved    = true;
            note.ratingColor = COLOR_OK;
            notesOk++;
        }

        note.score  = noteScore;
        totalScore += noteScore;

        System.out.printf(
                "[NOTA-SMP] %-4s | dur=%.2fs | amostras=%d | corretas=%d | %.0f%% | score=%.2f | %s%n",
                note.pitch, note.duration, total, correct, pct, noteScore, note.rating);
    }

    private void updateScoreDisplay() {
        if (scoreCallback == null) return;
        ScoreData d = buildScoreData();
        SwingUtilities.invokeLater(() -> scoreCallback.accept(d));
    }

    private ScoreData buildScoreData() {
        ScoreData d       = new ScoreData();
        d.perfect         = notesPerfect;
        d.great           = notesGreat;
        d.good            = notesGood;
        d.ok              = notesOk;
        d.missed          = notesMissed;
        d.total           = notes.size();
        d.evaluated       = notesPerfect + notesGreat + notesGood + notesOk + notesMissed;
        d.totalScore      = totalScore;
        d.totalDuration   = totalDuration;
        d.elapsedDuration = elapsedDuration;
        d.precision       = elapsedDuration > 0
                ? Math.min((totalScore / elapsedDuration) * 100.0, 100.0) : 0.0;
        return d;
    }

    private void showResults() {
        if (!resultsShown.compareAndSet(false, true)) return;

        ScoreData d = buildScoreData();

        boolean isPartial = d.evaluated < d.total;

        int    elapsedMin = (int) (d.elapsedDuration / 60);
        double elapsedSec = d.elapsedDuration % 60;
        int    scoreMin   = (int) (d.totalScore / 60);
        double scoreSec   = d.totalScore % 60;

        String emoji = d.precision >= 85 ? "🏆 EXCELENTE!"
                : d.precision >= 65 ? "👍 BOM TRABALHO!"
                : d.precision >= 40 ? "🎵 CONTINUE ASSIM!"
                :                     "💪 VAMOS PRATICAR MAIS!";

        String title    = isPartial ? "🎤 RESULTADO PARCIAL 🎤" : "🎤 RESULTADO FINAL 🎤";
        String durLabel = isPartial ? "🎵 Duração cantada            " : "🎵 Duração total da música   ";

        String msg = String.format("""
                %s

                ⭐ Perfeito : %d
                ✓  Ótimo    : %d
                👍 Bom      : %d
                ✅ OK       : %d
                ✗  Errou    : %d
                ─────────────────────────────
                Total de notas : %d%s

                %s: %d:%05.2f
                🎤 Segundos cantados (±bônus) : %d:%05.2f

                🎯 Precisão geral : %.1f%%
                   (= %.2f / %.2f segundos)

                %s
                """,
                title,
                d.perfect, d.great, d.good, d.ok, d.missed,
                d.evaluated, isPartial ? String.format("  (de %d na música completa)", d.total) : "",
                durLabel, elapsedMin, elapsedSec,
                scoreMin, scoreSec,
                d.precision,
                d.totalScore, d.elapsedDuration,
                emoji);

        if (onFinishCallback != null)
            SwingUtilities.invokeLater(onFinishCallback);

        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(null, msg,
                        isPartial ? "Resultado Parcial" : "Resultado Final",
                        JOptionPane.INFORMATION_MESSAGE));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
