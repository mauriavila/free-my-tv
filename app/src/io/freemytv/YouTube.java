package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The home screen's YouTube row: the latest videos from the channels the person is subscribed to (YouTube offers no
 * way to read an account's recommendations). Signed in once on the TV with Google's TV sign-in (a code entered at
 * google.com/device on a phone), with the person's own OAuth client, kept only on the TV:
 * adb push youtube_key.txt /sdcard/Android/data/io.freemytv/files/youtube_key.txt  (one line: CLIENT_ID:CLIENT_SECRET)
 * The token stays in the app's private files. YouTube Data API, read-only: subscriptions (by relevance, the channels
 * watched most first), each one's latest uploads, then their lengths to leave Shorts out. ~32 of the 10,000 daily
 * quota units per refresh, every 30 minutes.
 */
final class YouTube {
    static final int NO_KEY = 0, LOGIN = 1, READY = 2, ERROR = 3;

    static final class Video {
        final String id, title, channel;
        final long published;
        volatile Bitmap image;
        Video(String id, String title, String channel, long published) {
            this.id = id; this.title = title; this.channel = channel; this.published = published;
        }
    }

    interface Listener {
        void onYouTube();
    }

    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final long FEED_MS = 30 * 60_000L;
    private static final int CHANNELS = 30, VIDEOS = 20;

    private static YouTube instance;

    static synchronized YouTube get(Context c) {
        if (instance == null) instance = new YouTube(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    volatile int state = NO_KEY;
    volatile String userCode = "", verifyUrl = "google.com/device", error = "";
    volatile List<Video> videos = new ArrayList<>();
    private volatile long feedAt;
    private volatile boolean busy;
    private volatile Future<?> login;
    private String accessToken;
    private long accessUntil;

    private YouTube(Context c) {
        app = c;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        main.post(() -> { for (Listener l : listeners) l.onYouTube(); });
    }

    /** Signs in or refreshes the feed when needed (cheap to call often). */
    void refreshIfStale() {
        String[] key = keys();
        if (key == null) {
            if (state != NO_KEY) { state = NO_KEY; changed(); }
            return;
        }
        if (refreshToken() == null) {
            if (login == null || login.isDone()) login = io.submit(() -> signIn(key));
            return;
        }
        if (busy || System.currentTimeMillis() - feedAt < FEED_MS) return;
        busy = true;
        io.execute(() -> {
            try {
                loadFeed(key);
                feedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("youtube: " + e.getMessage());
                error = String.valueOf(e.getMessage());
                if (videos.isEmpty()) state = ERROR;
                feedAt = System.currentTimeMillis() - FEED_MS + 5 * 60_000L;   // try again in 5 minutes
                changed();
            } finally {
                busy = false;
            }
        });
    }

    // MARK: - Sign-in: Google's flow for TVs (a code to enter on a phone)

    private void signIn(String[] key) {
        try {
            JSONObject d = post("https://oauth2.googleapis.com/device/code",
                    "client_id=" + enc(key[0]) + "&scope=" + enc(SCOPE));
            userCode = d.getString("user_code");
            verifyUrl = d.optString("verification_url", "https://www.google.com/device").replaceFirst("^https?://(www\\.)?", "");
            long interval = Math.max(5, d.optLong("interval", 5)) * 1000, until = System.currentTimeMillis() + d.optLong("expires_in", 1800) * 1000;
            state = LOGIN;
            changed();
            AppLog.w("youtube: sign in at " + verifyUrl + " with the code on screen");
            while (System.currentTimeMillis() < until) {
                Thread.sleep(interval);
                JSONObject t = post("https://oauth2.googleapis.com/token", "client_id=" + enc(key[0]) + "&client_secret=" + enc(key[1])
                        + "&device_code=" + enc(d.getString("device_code")) + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
                String err = t.optString("error", "");
                if (err.equals("authorization_pending")) continue;
                if (err.equals("slow_down")) { interval += 5000; continue; }
                if (!err.isEmpty()) throw new java.io.IOException(err + " " + t.optString("error_description"));
                saveToken(t.getString("refresh_token"));
                accessToken = t.getString("access_token");
                accessUntil = System.currentTimeMillis() + (t.optLong("expires_in", 3600) - 60) * 1000;
                AppLog.w("youtube: signed in");
                state = READY;
                feedAt = 0;
                changed();
                main.post(this::refreshIfStale);
                return;
            }
            AppLog.w("youtube: the code expired, a new one");
            main.postDelayed(this::refreshIfStale, 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            AppLog.w("youtube sign-in: " + e.getMessage());
            error = String.valueOf(e.getMessage());
            state = ERROR;
            changed();
        }
    }

    private synchronized String access(String[] key) throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessUntil) return accessToken;
        String refresh = refreshToken();
        if (refresh == null) throw new java.io.IOException("not signed in");
        JSONObject t = post("https://oauth2.googleapis.com/token", "client_id=" + enc(key[0]) + "&client_secret=" + enc(key[1])
                + "&refresh_token=" + enc(refresh) + "&grant_type=refresh_token");
        if (t.has("error")) {
            if ("invalid_grant".equals(t.optString("error"))) {   // revoked or expired: sign in again
                tokenFile().delete();
                state = LOGIN;
                main.post(this::refreshIfStale);
            }
            throw new java.io.IOException(t.optString("error") + " " + t.optString("error_description"));
        }
        accessToken = t.getString("access_token");
        accessUntil = System.currentTimeMillis() + (t.optLong("expires_in", 3600) - 60) * 1000;
        return accessToken;
    }

    // MARK: - The feed

    private void loadFeed(String[] key) throws Exception {
        String token = access(key);
        JSONArray subs = get(API + "subscriptions?part=snippet&mine=true&order=relevance&maxResults=50", token).optJSONArray("items");
        List<String> channels = new ArrayList<>();
        for (int i = 0; subs != null && i < subs.length() && channels.size() < CHANNELS; i++) {
            String id = subs.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").optString("channelId");
            if (id.startsWith("UC")) channels.add(id);
        }
        List<Video> found = new ArrayList<>();
        List<String> thumbs = new ArrayList<>();
        long since = System.currentTimeMillis() - 21 * 86_400_000L;
        for (String ch : channels) {
            JSONArray items;
            try {
                items = get(API + "playlistItems?part=snippet,contentDetails&maxResults=3&playlistId=UU" + ch.substring(2), token).optJSONArray("items");
            } catch (Exception e) {
                continue;   // a channel without uploads
            }
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i), s = o.getJSONObject("snippet");
                String vid = o.getJSONObject("contentDetails").optString("videoId");
                long at = time(o.getJSONObject("contentDetails").optString("videoPublishedAt", s.optString("publishedAt")));
                if (vid.isEmpty() || at < since) continue;
                String channelName = s.optString("videoOwnerChannelTitle", s.optString("channelTitle"));
                found.add(new Video(vid, s.optString("title"), channelName, at));
                JSONObject th = s.optJSONObject("thumbnails");
                JSONObject medium = th == null ? null : th.optJSONObject("medium");
                thumbs.add(medium == null ? "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg" : medium.optString("url"));
            }
        }
        // Newest first; Shorts (a minute or less), lives and premieres out.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Long.compare(found.get(b).published, found.get(a).published));
        List<Integer> top = new ArrayList<>(order.subList(0, Math.min(45, order.size())));
        StringBuilder ids = new StringBuilder();
        for (int i : top) ids.append(ids.length() == 0 ? "" : ",").append(found.get(i).id);
        java.util.Set<String> keep = new java.util.HashSet<>();
        if (ids.length() > 0) {
            JSONArray details = get(API + "videos?part=contentDetails,snippet&id=" + ids, token).optJSONArray("items");
            for (int i = 0; details != null && i < details.length(); i++) {
                JSONObject v = details.getJSONObject(i);
                if (!"none".equals(v.getJSONObject("snippet").optString("liveBroadcastContent", "none"))) continue;
                if (seconds(v.getJSONObject("contentDetails").optString("duration")) <= 60) continue;
                keep.add(v.optString("id"));
            }
        }
        List<Video> out = new ArrayList<>();
        List<String> outThumbs = new ArrayList<>();
        for (int i : top) {
            if (!keep.contains(found.get(i).id)) continue;
            out.add(found.get(i));
            outThumbs.add(thumbs.get(i));
            if (out.size() == VIDEOS) break;
        }
        // Thumbnails we already have carry over; the rest are fetched (320 × 180).
        for (int i = 0; i < out.size(); i++) {
            for (Video old : videos) if (old.id.equals(out.get(i).id)) out.get(i).image = old.image;
        }
        videos = out;
        state = READY;
        error = "";
        changed();
        AppLog.w("youtube: " + out.size() + " videos from " + channels.size() + " channels");
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).image != null) continue;
            out.get(i).image = image(outThumbs.get(i));
            if (i % 4 == 3 || i == out.size() - 1) changed();
        }
    }

    /** Opens the video in the TV's YouTube app (Amazon's on Fire TV, Google's elsewhere), or just the app. */
    static void open(Activity a, Video v) {
        Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + v.id);
        for (String pkg : new String[] {"com.amazon.firetv.youtube", "com.google.android.youtube.tv", null}) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pkg != null) i.setPackage(pkg);
                a.startActivity(i);
                AppLog.w("youtube: opened " + v.id + " in " + pkg);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    // MARK: - Helpers

    private String[] keys() {
        try {
            File dir = app.getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, "youtube_key.txt");
            if (f == null || !f.isFile() || f.length() > 4096) return null;
            String[] p = read(new FileInputStream(f)).trim().split("[:\\s]+");
            return p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private File tokenFile() {
        return new File(app.getFilesDir(), "youtube_token.txt");
    }

    private String refreshToken() {
        File f = tokenFile();
        if (!f.isFile()) return null;
        try {
            String s = read(new FileInputStream(f)).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToken(String refresh) throws Exception {
        try (OutputStream out = new FileOutputStream(tokenFile())) { out.write(refresh.getBytes("UTF-8")); }
    }

    private static JSONObject post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(form.getBytes("UTF-8")); }
            int code = c.getResponseCode();
            return new JSONObject(read(code >= 400 ? c.getErrorStream() : c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = c.getResponseCode();
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                JSONObject err = new JSONObject(body).optJSONObject("error");
                throw new java.io.IOException("HTTP " + code + (err == null ? "" : ": " + err.optString("message")));
            }
            return new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap image(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10_000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "{}";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** "2026-10-21T15:00:05Z" (with or without milliseconds) → epoch ms; 0 if unreadable. */
    static long time(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "PT1H2M3S" → 3723. */
    static long seconds(String duration) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(duration == null ? "" : duration);
        if (!m.matches()) return 0;
        long s = 0;
        int[] mul = {86400, 3600, 60, 1};
        for (int i = 0; i < 4; i++) if (m.group(i + 1) != null) s += Long.parseLong(m.group(i + 1)) * mul[i];
        return s;
    }

    /** "hace 3 h", "hace 2 días". */
    static String ago(long at) {
        long min = Math.max(1, (System.currentTimeMillis() - at) / 60_000);
        if (min < 60) return Ui.tr("há " + min + " min", min + " min ago", "hace " + min + " min");
        long h = min / 60;
        if (h < 24) return Ui.tr("há " + h + " h", h + " h ago", "hace " + h + " h");
        long d = h / 24;
        return Ui.tr("há " + d + (d == 1 ? " dia" : " dias"), d + (d == 1 ? " day ago" : " days ago"), "hace " + d + (d == 1 ? " día" : " días"));
    }
}
