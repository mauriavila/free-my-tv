package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics for the karaoke screen, from LRCLIB (lrclib.net: free, no key, community-made), synced line by line
 * when it has them. Network calls: call from a background thread.
 */
final class Lyrics {
    /** One line and when it starts (ms from the song's start). */
    static final class Line {
        final long at;
        final String text;
        Line(long at, String text) { this.at = at; this.text = text; }
    }

    final List<Line> synced;      // empty if only plain lyrics (or none)
    final List<String> plain;     // the text without times
    final boolean instrumental;

    private Lyrics(List<Line> synced, List<String> plain, boolean instrumental) {
        this.synced = synced;
        this.plain = plain;
        this.instrumental = instrumental;
    }

    boolean any() { return !synced.isEmpty() || !plain.isEmpty(); }

    private static final String API = "https://lrclib.net/api/";
    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");

    /** The lyrics for this song, or null if LRCLIB has none (or can't be reached). */
    static Lyrics find(String artist, String title, String album, long durationMs) {
        String t = clean(title), a = mainArtist(artist);
        try {
            if (durationMs > 0) {
                JSONObject o = getObject(API + "get?artist_name=" + enc(a) + "&track_name=" + enc(t)
                        + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                        + "&duration=" + Math.round(durationMs / 1000.0));
                Lyrics l = o == null ? null : from(o);
                if (l != null && (l.any() || l.instrumental)) return l;
            }
            // Search: the closest in length, synced ones first.
            JSONArray list = getArray(API + "search?track_name=" + enc(t) + "&artist_name=" + enc(a));
            JSONObject best = null;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                double diff = durationMs > 0 ? Math.abs(o.optDouble("duration", 0) * 1000 - durationMs) / 1000.0 : 0;
                if (durationMs > 0 && diff > 8) continue;
                double score = diff + (o.optString("syncedLyrics", "").isEmpty() ? 100 : 0);
                if (score < bestScore) { bestScore = score; best = o; }
            }
            return best == null ? null : from(best);
        } catch (Exception e) {
            AppLog.w("lyrics: " + e.getMessage());
            return null;
        }
    }

    private static Lyrics from(JSONObject o) {
        List<Line> synced = new ArrayList<>();
        String s = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        for (String raw : s.split("\n")) {
            Matcher m = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                String frac = m.group(3);
                if (frac != null) ms += Math.round(Long.parseLong(frac) * Math.pow(10, 3 - frac.length()));
                times.add(ms);
                end = m.end();
            }
            String text = raw.substring(end).trim();
            for (long at : times) synced.add(new Line(at, text));
        }
        Collections.sort(synced, (x, y) -> Long.compare(x.at, y.at));
        List<String> plain = new ArrayList<>();
        String p = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        for (String line : p.split("\n")) if (!line.trim().isEmpty()) plain.add(line.trim());
        return new Lyrics(synced, plain, o.optBoolean("instrumental", false));
    }

    /** "Song - Remastered 2011", "Song (feat. X)" → "Song". */
    static String clean(String title) {
        if (title == null) return "";
        String t = title.replaceAll("(?i)\\s*[(\\[](feat|ft|with|con)\\.?\\s[^)\\]]*[)\\]]", "");
        t = t.replaceAll("(?i)\\s+-\\s+.*(remaster|version|versión|live|en vivo|edit|mix|mono|stereo|acoustic|acústic).*$", "");
        return t.trim();
    }

    /** "A, B & C" → "A" (LRCLIB matches the main artist best). */
    static String mainArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(",| & | feat\\.? | ft\\.? | x ")[0];
        return a.trim();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static JSONObject getObject(String url) throws Exception {
        String body = get(url);
        return body == null ? null : new JSONObject(body);
    }

    private static JSONArray getArray(String url) throws Exception {
        String body = get(url);
        return body == null ? null : new JSONArray(body);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "FreeMyTV karaoke (github.com/simbasjungles/free-my-tv)");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Lower-case key for the cache: artist + title. */
    static String key(String artist, String title) {
        return (mainArtist(artist) + "|" + clean(title)).toLowerCase(Locale.ROOT);
    }
}
