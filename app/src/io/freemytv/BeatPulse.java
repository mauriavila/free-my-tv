package io.freemytv;

import android.media.audiofx.Visualizer;
import android.os.SystemClock;

/**
 * The beat of what's playing, for the karaoke's party lights: Android's Visualizer on the output mix (session 0) gives
 * the sound's spectrum; a jump in the bass energy over its recent average is a beat. Only the spectrum, never the
 * sound itself, and nothing leaves the TV. Needs RECORD_AUDIO, given once with adb (pm grant io.freemytv
 * android.permission.RECORD_AUDIO); without it, or where Fire OS doesn't allow it, the lights just move on their own.
 */
final class BeatPulse {
    private Visualizer visualizer;
    private float average = -1;
    private long lastBeat;
    /** When the last beat came (uptime ms), and how strong the bass is now (0..1). */
    volatile long beatAt = -10_000;
    volatile float level;

    boolean start() {
        try {
            visualizer = new Visualizer(0);
            visualizer.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(Math.min(range[1], Math.max(range[0], 512)));
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 30_000);   // about 30 spectra a second
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {}
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) { onFft(fft, samplingRate); }
            }, rate, false, true);
            visualizer.setEnabled(true);
            return true;
        } catch (RuntimeException | LinkageError e) {   // no permission, or not allowed on this TV
            AppLog.w("karaoke beat: " + e.getMessage());
            stop();
            return false;
        }
    }

    void stop() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (RuntimeException ignored) {
        }
        visualizer = null;
    }

    private void onFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length / 2;
        if (n < 4) return;
        // Bins up to ~150 Hz: the kick drum and the bass.
        float hzPerBin = samplingRateMilliHz / 1000f / 2f / n;
        int last = Math.max(1, Math.min(n - 1, Math.round(150f / Math.max(1f, hzPerBin))));
        float energy = 0;
        for (int i = 1; i <= last; i++) {
            float re = fft[2 * i], im = fft[2 * i + 1];
            energy += (float) Math.sqrt(re * re + im * im);
        }
        energy /= last;
        if (average < 0) average = energy;
        average += (energy - average) * 0.04f;
        level = Math.min(1, energy / 64f);
        long now = SystemClock.uptimeMillis();
        if (energy > average * 1.35f && energy > 8 && now - lastBeat > 280) {
            lastBeat = now;
            beatAt = now;
        }
    }
}
