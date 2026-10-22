package io.freemytv;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's log: adb logcat -s FreeMyTV, and also a file on the TV, since the system's log is small and gets
 * overwritten within minutes (on Fire TV especially). The file keeps about the last few days (1 MB, then the previous
 * one as freemytv-log.old.txt):
 * adb pull /sdcard/Android/data/io.freemytv/files/freemytv-log.txt
 * Written in the background, from every process (each line says which). Never logs keys.
 */
final class AppLog {
    private AppLog() {}

    private static final long MAX = 1024 * 1024;
    private static volatile File file;
    private static String process = "";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    /** Called once per process (App.onCreate). */
    static void init(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null) file = new File(dir, "freemytv-log.txt");
            byte[] buf = new byte[256];
            int n;
            try (java.io.InputStream in = new java.io.FileInputStream("/proc/self/cmdline")) { n = Math.max(0, in.read(buf)); }
            String name = new String(buf, 0, n, "UTF-8").trim();
            int colon = name.indexOf(':');
            process = colon >= 0 ? name.substring(colon).replace("\u0000", "") : "";
        } catch (Throwable ignored) {
        }
    }

    /** A crash, written right away (the process is about to end). */
    static void crash(String trace) {
        Log.e("FreeMyTV", trace);
        File f = file;
        if (f == null) return;
        try (OutputStream out = new FileOutputStream(f, true)) {
            out.write((new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date())
                    + (process.isEmpty() ? " " : " " + process + " ") + "CRASH " + trace + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    static void w(String message) {
        Log.i("FreeMyTV", message);
        final File f = file;
        if (f == null) return;
        final long at = System.currentTimeMillis();
        WRITER.execute(() -> {
            try {
                if (f.length() > MAX) f.renameTo(new File(f.getParentFile(), "freemytv-log.old.txt"));
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(at))
                        + (process.isEmpty() ? " " : " " + process + " ") + message + "\n";
                try (OutputStream out = new FileOutputStream(f, true)) { out.write(line.getBytes("UTF-8")); }
            } catch (Exception ignored) {
            }
        });
    }
}
