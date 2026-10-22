package io.freemytv;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Fire TV only: Amazon keeps the Home button and the screen after waking for its own home screen, so this small
 * service in the main process opens Free My TV over it when the stick wakes up (screen on, screensaver ends, the TV
 * turning on over HDMI) or boots, but only while Amazon's home is in front: never over a video or another app. Fire OS
 * records its home coming back a little late, so it looks three times (1.5, 4 and 8 s after). Knowing what's in front needs
 * usage access, given once with adb: appops set io.freemytv GET_USAGE_STATS allow. Without it, only waking from
 * sleep (screen on) opens it, since Fire OS shows its own home after sleep anyway.
 */
public class FireWake extends Service {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    private static final String HDMI_PLUGGED = "android.intent.action.HDMI_PLUGGED";   // the TV turned on or off
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;

    static void start(Context c) {
        if (!Ui.FIRE) return;
        try {
            c.startService(new Intent(c, FireWake.class));
        } catch (RuntimeException e) {
            AppLog.w("fire wake: " + e.getMessage());
        }
    }

    /** After the stick boots: Amazon's home settles first, then ours goes over it. */
    static void afterBoot(Context c) {
        if (!Ui.FIRE) return;
        start(c);
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> openIfAmazonHome(app, true, false), 20_000);
    }

    @Override public void onCreate() {
        super.onCreate();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (isInitialStickyBroadcast()) return;   // HDMI's last state, sent on registering
                String action = intent.getAction();
                if (HDMI_PLUGGED.equals(action) && !intent.getBooleanExtra("state", false)) return;
                if (android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG.equals(action)
                        && intent.getIntExtra(android.media.AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) != 1) return;
                final boolean wokeUp = !Intent.ACTION_DREAMING_STOPPED.equals(action);
                AppLog.w("fire wake: " + action);
                main.removeCallbacksAndMessages(null);
                // Moments for Fire OS to bring its home screen back to the front first.
                long[] delays = {1500, 4000, 8000};
                for (int i = 0; i < delays.length; i++) {
                    final boolean last = i == delays.length - 1;
                    main.postDelayed(() -> {
                        if (openIfAmazonHome(getApplicationContext(), wokeUp, last && wokeUp)) main.removeCallbacksAndMessages(null);
                    }, delays[i]);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        f.addAction(HDMI_PLUGGED);
        f.addAction(android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG);
        registerReceiver(receiver, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;   // Fire OS may stop it to free memory: it comes back on its own
    }

    @Override public void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /**
     * Opens the home screen if Amazon's is in front (or, without usage access, only right after waking up); true if
     * opened. Free My TV "in front" right after waking is often a stale record from before the sleep (Fire OS doesn't
     * always record its home coming back), so on the last look after waking it opens then too: if Free My TV really
     * is in front, opening it again changes nothing (single task).
     */
    static boolean openIfAmazonHome(Context c, boolean wokeUp, boolean oursCounts) {
        String front = foreground(c);
        AppLog.w("fire wake: in front: " + front);
        boolean ours = c.getPackageName().equals(front);
        if (!(ours && oursCounts) && (front == null ? !wokeUp : !AMAZON_HOME.equals(front))) return false;
        try {
            c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppLog.w("fire wake: opened over Amazon's home");
            return true;
        } catch (RuntimeException e) {
            AppLog.w("fire wake: couldn't open: " + e.getMessage());
            return false;
        }
    }

    /** The app in front, from the usage events of the last day; null without usage access. */
    private static String foreground(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 86_400_000L, now);
            if (events == null || !events.hasNextEvent()) return null;   // no access gives no events
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
