package io.freemytv;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Searches Spotify's catalog for the karaoke's song list (Spotify Web API, "client credentials": the person's own free
 * app at developer.spotify.com, no Spotify login). The keys live on the TV, never in the build:
 * adb push spotify_key.txt /sdcard/Android/data/io.freemytv/files/spotify_key.txt  (one line: CLIENT_ID:CLIENT_SECRET).
 * Playing is then Spotify's own app: MediaController.playFromUri("spotify:track:…"). Network: background thread only.
 */
final class SpotifyCatalog {
    private SpotifyCatalog() {}

    static final class Track {
        final String id, name, artists, image;
        final long durationMs;
        Track(String id, String name, String artists, String image, long durationMs) {
            this.id = id; this.name = name; this.artists = artists; this.image = image; this.durationMs = durationMs;
        }
        String uri() { return "spotify:track:" + id; }
    }

    private static String token;
    private static long tokenUntil;

    /** The keys file is there (it doesn't check they work). */
    static boolean configured(Context c) {
        return keys(c) != null;
    }

    static synchronized List<Track> search(Context c, String query, String market) throws Exception {
        String bearer = token(c);
        // Development Mode apps: 10 results at most per request (since February 2026).
        String url = "https://api.spotify.com/v1/search?type=track&limit=10&market=" + market + "&q="
                + URLEncoder.encode(query, "UTF-8");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("Authorization", "Bearer " + bearer);
        List<Track> out = new ArrayList<>();
        try {
            int code = h.getResponseCode();
            if (code == 401) { token = null; throw new java.io.IOException("Spotify rejected the token"); }
            if (code != 200) {
                String why = "";
                try { why = new JSONObject(read(h.getErrorStream())).optJSONObject("error").optString("message"); } catch (Exception ignored) { }
                throw new java.io.IOException("Spotify HTTP " + code + (why.isEmpty() ? "" : ": " + why));
            }
            JSONArray items = new JSONObject(read(h.getInputStream())).getJSONObject("tracks").getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject t = items.getJSONObject(i);
                StringBuilder artists = new StringBuilder();
                JSONArray as = t.optJSONArray("artists");
                for (int j = 0; as != null && j < as.length(); j++) {
                    if (j > 0) artists.append(", ");
                    artists.append(as.getJSONObject(j).optString("name"));
                }
                String image = "";
                JSONObject album = t.optJSONObject("album");
                JSONArray images = album == null ? null : album.optJSONArray("images");
                // The smallest one that is at least 160 px (a list thumbnail).
                for (int j = 0; images != null && j < images.length(); j++) {
                    JSONObject im = images.getJSONObject(j);
                    if (image.isEmpty() || im.optInt("width") >= 160) image = im.optString("url");
                }
                out.add(new Track(t.optString("id"), t.optString("name"), artists.toString(), image, t.optLong("duration_ms")));
            }
        } finally {
            h.disconnect();
        }
        return out;
    }

    private static String token(Context c) throws Exception {
        if (token != null && System.currentTimeMillis() < tokenUntil) return token;
        String[] k = keys(c);
        if (k == null) throw new java.io.IOException("no Spotify keys");
        HttpURLConnection h = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        String basic = Base64.encodeToString((k[0] + ":" + k[1]).getBytes("UTF-8"), Base64.NO_WRAP);
        h.setRequestProperty("Authorization", "Basic " + basic);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream o = h.getOutputStream()) { o.write("grant_type=client_credentials".getBytes("UTF-8")); }
            int code = h.getResponseCode();
            if (code != 200) throw new java.io.IOException("Spotify keys rejected (HTTP " + code + ")");
            JSONObject o = new JSONObject(read(h.getInputStream()));
            token = o.getString("access_token");
            tokenUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000;
            return token;
        } finally {
            h.disconnect();
        }
    }

    /** {client id, client secret} from the keys file, or null. */
    private static String[] keys(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, "spotify_key.txt");
            if (!f.isFile() || f.length() > 4096) return null;
            String s;
            try (InputStream in = new FileInputStream(f)) { s = read(in); }
            String[] parts = s.trim().split("[:\\s]+");
            return parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty() ? new String[] {parts[0], parts[1]} : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
