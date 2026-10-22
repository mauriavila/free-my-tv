package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fire TV home background: photos of beautiful places around the world, a new one every few minutes. They are Bing's
 * photos of the day (the last ~15, with the place in the caption), fetched without any key, kept in the cache, and each
 * one turned into a Backdrop (the photo with its scrims, plus the blurred versions the glass shows) in the background.
 */
final class Wallpapers {
    interface Listener {
        void onWallpaper(Backdrop b, String caption);
    }

    static final long EVERY_MS = 5 * 60_000L;
    private static final long LIST_MS = 6 * 3_600_000L;
    private static final int KEEP = 20;

    private static final class Item {
        final String url, caption;
        Item(String url, String caption) { this.url = url; this.caption = caption; }
    }

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private List<Item> items = new ArrayList<>();
    private long listAt, shownAt;
    private int index = -1;
    private volatile boolean busy;

    Wallpapers(Context c) {
        dir = new File(c.getCacheDir(), "walls");
    }

    /** The next photo if it's time (or there's none yet); the listener gets it on the main thread. */
    void rotateIfDue(boolean force, Listener l) {
        long now = System.currentTimeMillis();
        if (busy || (!force && shownAt != 0 && now - shownAt < EVERY_MS)) return;
        busy = true;
        io.execute(() -> {
            try {
                if (items.isEmpty() || System.currentTimeMillis() - listAt > LIST_MS) loadList();
                if (items.isEmpty()) return;
                for (int tries = 0; tries < Math.min(3, items.size()); tries++) {
                    index = (index + 1) % items.size();
                    Item it = items.get(index);
                    Bitmap src = decode(file(it));
                    if (src == null) continue;
                    final Backdrop b = Backdrop.make(src, false);
                    final String caption = it.caption;
                    shownAt = System.currentTimeMillis();
                    main.post(() -> l.onWallpaper(b, caption));
                    return;
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("wallpapers: " + e);
            } finally {
                busy = false;
            }
        });
    }

    /** Bing's photos of the day, in the TV's language: two pages of 8 (they overlap by one). */
    private void loadList() throws Exception {
        String mkt = Ui.es ? "es-ES" : Ui.de ? "de-DE" : Ui.ru ? "ru-RU" : Ui.en ? "en-US" : "pt-BR";
        List<Item> found = new ArrayList<>();
        for (int idx : new int[] {0, 7}) {
            String body = text("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&idx=" + idx + "&mkt=" + mkt);
            JSONArray images = new JSONObject(body).optJSONArray("images");
            for (int i = 0; images != null && i < images.length(); i++) {
                JSONObject o = images.getJSONObject(i);
                String path = o.optString("urlbase", "");
                if (path.isEmpty()) continue;
                String url = "https://www.bing.com" + path + "_1920x1080.jpg";
                String caption = o.optString("copyright", "").replaceAll("\\s*\\(©[^)]*\\)\\s*$", "").trim();
                boolean dup = false;
                for (Item x : found) dup |= x.url.equals(url);
                if (!dup) found.add(new Item(url, caption));
            }
        }
        if (found.isEmpty()) return;
        Collections.shuffle(found);
        items = found;
        index = -1;
        listAt = System.currentTimeMillis();
        AppLog.w("wallpapers: " + found.size() + " places");
    }

    /** The photo on disk (downloaded once); the oldest ones beyond KEEP are deleted. */
    private File file(Item it) throws Exception {
        if (!dir.isDirectory()) dir.mkdirs();
        File f = new File(dir, Integer.toHexString(it.url.hashCode()) + ".jpg");
        if (f.isFile() && f.length() > 10_000) return f;
        HttpURLConnection c = (HttpURLConnection) new URL(it.url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        File part = new File(dir, f.getName() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        part.renameTo(f);
        File[] all = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (all != null && all.length > KEEP) {
            Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - KEEP; i++) all[i].delete();
        }
        return f;
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) { f.delete(); return null; }
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= Backdrop.W) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static String text(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
