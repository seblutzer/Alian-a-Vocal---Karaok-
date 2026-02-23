
package com.karaoke;

import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.JavaSoundAudioDevice;
import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;

import javax.sound.sampled.*;
import java.io.*;
import java.lang.reflect.Field;

public class AudioPlayer {

    public interface PlayerListener {
        void onFinished();
        void onError(String message);
    }

    private static final double MP3_FRAMES_PER_SECOND = 38.28;

    private static final int PCM_SAMPLE_RATE = 48_000;
    private static final int PCM_CHANNELS    = 2;
    private static final int PCM_SAMPLE_BITS = 16;
    private static final AudioFormat PCM_FORMAT = new AudioFormat(
            PCM_SAMPLE_RATE, PCM_SAMPLE_BITS, PCM_CHANNELS, true, false
    );

    private static final long STOP_JOIN_TIMEOUT_MS = 2_000;

    private String         filePath;
    private PlayerListener listener;
    private float          volume = 1.0f;

    private volatile boolean isPlaying    = false;
    private volatile boolean isPaused     = false;
    private volatile boolean clockTicking = false;

    private double offsetSeconds   = 0.0;
    private long   playStartMillis = 0;

    private AdvancedPlayer    jlPlayer;
    private VolumeAudioDevice jlDevice;
    private Thread            playerThread;

    private volatile Process        ffmpegProcess;
    private volatile SourceDataLine pcmLine;
    private Thread                  pcmFeedThread;

    public AudioPlayer() {}

    public void setListener(PlayerListener l) { this.listener = l; }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        if (jlDevice != null) jlDevice.setTargetVolume(this.volume);
        applyVolumeToLine(pcmLine);
    }

    public void load(String path) {
        stop();
        this.filePath      = path;
        this.offsetSeconds = 0.0;
    }

    public void seek(double seconds) {
        offsetSeconds = Math.max(0.0, seconds);
    }

    public void play() {
        if (filePath == null || isPlaying) return;
        isPlaying       = true;
        clockTicking    = true;
        isPaused        = false;
        playStartMillis = System.currentTimeMillis();
        if (isMp3(filePath)) playWithJLayer();
        else                 playWithFfmpeg();
    }

    public void pause() {
        if (!isPlaying) return;
        offsetSeconds += (System.currentTimeMillis() - playStartMillis) / 1000.0;
        isPaused     = true;
        isPlaying    = false;
        clockTicking = false;
        stopBackends();
        closeLineQuietly(pcmLine);
        pcmLine = null;
    }

    public void resume() {
        if (!isPaused) return;
        isPaused = false;
        play();
    }

    public void stop() {
        isPlaying     = false;
        clockTicking  = false;
        isPaused      = false;
        offsetSeconds = 0.0;
        stopBackends();
        closeLineQuietly(pcmLine);
        pcmLine = null;
    }

    public double getCurrentTime() {
        if (!clockTicking) return offsetSeconds;
        return offsetSeconds + (System.currentTimeMillis() - playStartMillis) / 1000.0;
    }

    public boolean isPlaying() { return isPlaying; }
    public boolean isPaused()  { return isPaused;  }
    public boolean isLoaded()  { return filePath != null; }

    // ── Backend MP3 — JLayer ──────────────────────────────────────────────────

    private void playWithJLayer() {
        final double startOffset = offsetSeconds;
        final int firstFrame = startOffset > 0.0
                ? (int) (startOffset * MP3_FRAMES_PER_SECOND) : 0;

        playerThread = new Thread(() -> {
            try {
                FileInputStream     fis = new FileInputStream(filePath);
                BufferedInputStream bis = new BufferedInputStream(fis);

                jlDevice = new VolumeAudioDevice();
                jlDevice.setTargetVolume(volume);
                jlPlayer = new AdvancedPlayer(bis, jlDevice);

                jlPlayer.setPlayBackListener(new PlaybackListener() {
                    @Override public void playbackFinished(PlaybackEvent e) {
                        isPlaying = false;
                        jlDevice  = null;
                        if (listener != null && !isPaused) listener.onFinished();
                    }
                });

                if (firstFrame > 0) jlPlayer.play(firstFrame, Integer.MAX_VALUE);
                else                jlPlayer.play();

            } catch (Exception e) {
                isPlaying = false;
                jlDevice  = null;
                if (listener != null)
                    listener.onError("Erro ao reproduzir MP3: " + e.getMessage());
            }
        }, "AudioPlayer-MP3");

        playerThread.setDaemon(true);
        playerThread.start();
    }

    // ── Backend PCM — FFmpeg → pipe → javax.sound ─────────────────────────────

    private void playWithFfmpeg() {
        final double startOffset = offsetSeconds;

        pcmFeedThread = new Thread(() -> {
            SourceDataLine localLine = null;
            Process        localProc = null;

            try {
                ProcessBuilder pb = buildFfmpegDecodeCommand(filePath, startOffset);
                localProc     = pb.start();
                ffmpegProcess = localProc;

                final Process procRef = localProc;
                Thread errDrain = new Thread(() -> {
                    try { procRef.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                    catch (IOException ignored) {}
                }, "FFmpeg-stderr-drain");
                errDrain.setDaemon(true);
                errDrain.start();

                DataLine.Info info = new DataLine.Info(SourceDataLine.class, PCM_FORMAT);
                localLine = (SourceDataLine) AudioSystem.getLine(info);
                localLine.open(PCM_FORMAT);
                applyVolumeToLine(localLine);
                localLine.start();
                pcmLine = localLine;

                byte[] buf = new byte[4096];
                try (InputStream pcm = localProc.getInputStream()) {
                    int n;
                    while (isPlaying && (n = pcm.read(buf)) != -1) {
                        localLine.write(buf, 0, n);
                    }
                }

                if (isPlaying) localLine.drain();

            } catch (Exception e) {
                if (isPlaying && listener != null && !isPaused)
                    listener.onError("Erro ao reproduzir áudio: " + e.getMessage());
            } finally {
                if (localProc != null) {
                    try { localProc.waitFor(); }
                    catch (InterruptedException ignored) {}
                    localProc.destroyForcibly();
                }

                if (ffmpegProcess == localProc) ffmpegProcess = null;

                boolean finished = isPlaying;
                isPlaying = false;
                if (finished && listener != null && !isPaused) listener.onFinished();
            }
        }, "AudioPlayer-PCM");

        pcmFeedThread.setDaemon(true);
        pcmFeedThread.start();
    }

    private static ProcessBuilder buildFfmpegDecodeCommand(String path, double seekSeconds) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("ffmpeg");
        if (seekSeconds > 0.01) {
            cmd.add("-ss");
            cmd.add(String.format(java.util.Locale.US, "%.3f", seekSeconds));
        }
        cmd.add("-i");  cmd.add(path);
        cmd.add("-f");  cmd.add("s16le");
        cmd.add("-ar"); cmd.add(String.valueOf(PCM_SAMPLE_RATE));
        cmd.add("-ac"); cmd.add(String.valueOf(PCM_CHANNELS));
        cmd.add("pipe:1");
        return new ProcessBuilder(cmd);
    }

    private void stopBackends() {
        if (jlPlayer != null) { jlPlayer.close(); jlPlayer = null; }
        jlDevice = null;
        if (playerThread != null) { playerThread.interrupt(); playerThread = null; }

        Process procSnap = ffmpegProcess;
        if (procSnap != null) { procSnap.destroyForcibly(); ffmpegProcess = null; }

        SourceDataLine lineSnap = pcmLine;
        if (lineSnap != null) { closeLineQuietly(lineSnap); pcmLine = null; }

        Thread t = pcmFeedThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            try { t.join(STOP_JOIN_TIMEOUT_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            pcmFeedThread = null;
        }
    }

    private static void closeLineQuietly(SourceDataLine line) {
        if (line == null) return;
        try {
            if (line.isRunning()) line.stop();
            if (line.isOpen())    line.close();
        } catch (Exception ignored) {}
    }

    private void applyVolumeToLine(SourceDataLine sdl) {
        if (sdl == null || !sdl.isOpen()) return;
        if (!sdl.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl ctrl = (FloatControl) sdl.getControl(FloatControl.Type.MASTER_GAIN);
        float safe    = Math.max(volume, 0.0001f);
        float dB      = 20.0f * (float) Math.log10(safe);
        float clamped = Math.max(ctrl.getMinimum(), Math.min(ctrl.getMaximum(), dB));
        ctrl.setValue(clamped);
    }

    private static boolean isMp3(String path) {
        return path != null && path.toLowerCase().endsWith(".mp3");
    }

    // ── VolumeAudioDevice — JLayer/MP3 ───────────────────────────────────────

    private static class VolumeAudioDevice extends JavaSoundAudioDevice {

        private volatile float          targetVolume = 1.0f;
        private volatile SourceDataLine cachedLine   = null;

        private static final Field SOURCE_FIELD;
        static {
            Field f = null;
            try {
                f = JavaSoundAudioDevice.class.getDeclaredField("source");
                f.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
            SOURCE_FIELD = f;
        }

        void setTargetVolume(float v) {
            targetVolume = Math.max(0f, Math.min(1f, v));
            applyToLine(cachedLine);
        }

        @Override
        protected void createSource() throws JavaLayerException {
            super.createSource();
            cachedLine = extractLine();
            applyToLine(cachedLine);
        }

        private SourceDataLine extractLine() {
            if (SOURCE_FIELD == null) return null;
            try { return (SourceDataLine) SOURCE_FIELD.get(this); }
            catch (IllegalAccessException e) { return null; }
        }

        private void applyToLine(SourceDataLine sdl) {
            if (sdl == null || !sdl.isOpen()) return;
            if (!sdl.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
            FloatControl ctrl = (FloatControl) sdl.getControl(FloatControl.Type.MASTER_GAIN);
            float safe    = Math.max(targetVolume, 0.0001f);
            float dB      = 20.0f * (float) Math.log10(safe);
            float clamped = Math.max(ctrl.getMinimum(), Math.min(ctrl.getMaximum(), dB));
            ctrl.setValue(clamped);
        }

        @Override public void close() { cachedLine = null; super.close(); }
    }
}
