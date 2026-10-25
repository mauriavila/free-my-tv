package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.widget.Toast;

/**
 * Fire TV: the whole TV's sound (Spotify, YouTube, everything) through Android's global equalizer (the output mix,
 * session 0), for when the bass is too much on the speaker: Normal, Less bass, Much less bass, and Clearer voices (for
 * singing: less bass, more mids). It lives in FireWake (the main process, kept running), so it stays on in other apps
 * and comes back after a restart; the choice is saved there.
 */
final class Sound {
    private Sound() {}

    static final int NORMAL = 0, LESS_BASS = 1, MUCH_LESS_BASS = 2, VOICES = 3;
    static final String ACTION = "io.freemytv.SOUND", EXTRA = "preset";

    /** dB at 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz (Android's usual five bands; others are interpolated). */
    private static final float[][] CURVES = {
        {0, 0, 0, 0, 0},
        {-6, -3, 0, 0, 0},
        {-11, -6, -1, 1, 0},
        {-7, -4, 2, 4, 1},
    };
    private static final float[] FREQS = {60, 230, 910, 3600, 14000};

    private static Equalizer eq;

    static boolean supported() {
        return Ui.FIRE;
    }

    /** The drawer's Sound: the four choices, the current one marked. */
    static void open(final Activity a) {
        final String[] names = {
            Ui.tr("Normal", "Normal", "Normal"),
            Ui.tr("Menos graves", "Less bass", "Menos bajos"),
            Ui.tr("Muito menos graves", "Much less bass", "Muchos menos bajos"),
            Ui.tr("Vozes mais claras", "Clearer voices", "Voces más claras"),
        };
        int current = prefs(a).getInt(EXTRA, NORMAL);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Som", "Sound", "Sonido"))
                .setSingleChoiceItems(names, current, (d, which) -> {
                    set(a, which);
                    d.dismiss();
                })
                .show();
    }

    /** Asks FireWake (main process) to apply and keep the preset. */
    static void set(Context c, int preset) {
        try {
            c.startService(new Intent(c, FireWake.class).setAction(ACTION).putExtra(EXTRA, preset));
        } catch (RuntimeException e) {
            AppLog.w("sound: " + e.getMessage());
        }
    }

    /** In FireWake: the saved preset (on start) or a new one. */
    static void apply(Context c, int preset, boolean announce) {
        prefs(c).edit().putInt(EXTRA, preset).apply();
        String error = null;
        try {
            if (preset == NORMAL) {
                if (eq != null) { eq.release(); eq = null; }
            } else {
                if (eq == null) eq = new Equalizer(1000, 0);   // high priority, the output mix
                short bands = eq.getNumberOfBands();
                short[] range = eq.getBandLevelRange();
                for (short b = 0; b < bands; b++) {
                    float hz = eq.getCenterFreq(b) / 1000f;
                    int mb = Math.round(gain(CURVES[preset], hz) * 100);
                    eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
                }
                eq.setEnabled(true);
            }
            AppLog.w("sound: preset " + preset);
        } catch (Throwable e) {
            error = e.getMessage();
            AppLog.w("sound: the TV doesn't allow the equalizer: " + e);
            if (eq != null) { try { eq.release(); } catch (Throwable ignored) {} eq = null; }
        }
        if (!announce) return;
        Toast.makeText(c, error == null
                ? Ui.tr("Som ajustado", "Sound set", "Sonido ajustado")
                : Ui.tr("Esta TV não deixa mudar o equalizador", "This TV doesn't allow changing the equalizer",
                "Esta tele no deja cambiar el ecualizador"), Toast.LENGTH_SHORT).show();
    }

    static void restore(Context c) {
        int preset = prefs(c).getInt(EXTRA, NORMAL);
        if (preset != NORMAL) apply(c, preset, false);
    }

    /** The curve's gain at hz (linear between the five points, in octaves). */
    private static float gain(float[] curve, float hz) {
        if (hz <= FREQS[0]) return curve[0];
        for (int i = 1; i < FREQS.length; i++) {
            if (hz <= FREQS[i]) {
                double t = Math.log(hz / FREQS[i - 1]) / Math.log(FREQS[i] / FREQS[i - 1]);
                return (float) (curve[i - 1] + (curve[i] - curve[i - 1]) * t);
            }
        }
        return curve[curve.length - 1];
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sound", Context.MODE_MULTI_PROCESS);   // written in the main process, read in :home
    }
}
