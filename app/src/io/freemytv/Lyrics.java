package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics for the karaoke screen, from three free sources, best first: LRCLIB (lrclib.net, community-made,
 * synced), NetEase Cloud Music (synced lyrics for a large catalog, Latin music included) and lyrics.ovh (text only).
 * Network calls: call from a background thread.
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
    final String source;

    private Lyrics(List<Line> synced, List<String> plain, boolean instrumental, String source) {
        this.synced = synced;
        this.plain = plain;
        this.instrumental = instrumental;
        this.source = source;
    }

    boolean any() { return !synced.isEmpty() || !plain.isEmpty(); }

    private static final String LRCLIB = "https://lrclib.net/api/";
    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");

    /** The lyrics for this song, synced if any source has them, else plain; null if none has it. */
    static Lyrics find(String artist, String title, String album, long durationMs) {
        String t = clean(title), a = mainArtist(artist);
        Lyrics lrclib = null;
        try {
            lrclib = fromLrclib(a, t, album, durationMs);
        } catch (Exception e) {
            AppLog.w("lyrics lrclib: " + e.getMessage());
        }
        if (lrclib != null && (lrclib.instrumental || !lrclib.synced.isEmpty())) return lrclib;
        try {
            Lyrics netease = fromNetease(a, t, durationMs);
            if (netease != null && !netease.synced.isEmpty()) return netease;
        } catch (Exception e) {
            AppLog.w("lyrics netease: " + e.getMessage());
        }
        if (lrclib != null && lrclib.any()) return lrclib;
        try {
            return fromOvh(a, t);
        } catch (Exception e) {
            AppLog.w("lyrics ovh: " + e.getMessage());
            return null;
        }
    }

    // MARK: - LRCLIB

    private static Lyrics fromLrclib(String a, String t, String album, long durationMs) throws Exception {
        if (durationMs > 0) {
            String body = get(LRCLIB + "get?artist_name=" + enc(a) + "&track_name=" + enc(t)
                    + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                    + "&duration=" + Math.round(durationMs / 1000.0), null);
            if (body != null) {
                Lyrics l = fromLrclibJson(new JSONObject(body));
                if (l.any() || l.instrumental) return l;
            }
        }
        // Search: the closest in length, synced ones first.
        String body = get(LRCLIB + "search?track_name=" + enc(t) + "&artist_name=" + enc(a), null);
        JSONArray list = body == null ? null : new JSONArray(body);
        JSONObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            double diff = durationMs > 0 ? Math.abs(o.optDouble("duration", 0) * 1000 - durationMs) / 1000.0 : 0;
            if (durationMs > 0 && diff > 8) continue;
            double score = diff + (o.optString("syncedLyrics", "").isEmpty() || o.isNull("syncedLyrics") ? 100 : 0);
            if (score < bestScore) { bestScore = score; best = o; }
        }
        return best == null ? null : fromLrclibJson(best);
    }

    private static Lyrics fromLrclibJson(JSONObject o) {
        String s = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        List<String> plain = new ArrayList<>();
        String p = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        for (String line : p.split("\n")) if (!line.trim().isEmpty()) plain.add(line.trim());
        return new Lyrics(parseLrc(s), plain, o.optBoolean("instrumental", false), "LRCLIB");
    }

    // MARK: - NetEase Cloud Music

    private static final String NETEASE = "https://music.163.com/api/";

    private static Lyrics fromNetease(String a, String t, long durationMs) throws Exception {
        String body = get(NETEASE + "search/get/web?type=1&offset=0&limit=10&s=" + enc(a + " " + t), "https://music.163.com/");
        if (body == null) return null;
        JSONObject result = new JSONObject(body).optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        String wantT = simple(t), wantA = simple(a);
        long bestId = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject s = songs.getJSONObject(i);
            String name = simple(clean(s.optString("name")));
            if (!(name.contains(wantT) || wantT.contains(name))) continue;
            boolean artistOk = false;
            JSONArray artists = s.optJSONArray("artists");
            for (int j = 0; artists != null && j < artists.length(); j++) {
                String n = simple(artists.getJSONObject(j).optString("name"));
                if (!n.isEmpty() && (n.contains(wantA) || wantA.contains(n))) artistOk = true;
            }
            if (!artistOk) continue;
            double diff = durationMs > 0 ? Math.abs(s.optLong("duration") - durationMs) / 1000.0 : 0;
            if (durationMs > 0 && diff > 8) continue;
            if (diff < bestScore) { bestScore = diff; bestId = s.optLong("id", -1); }
        }
        if (bestId < 0) return null;
        String lyric = get(NETEASE + "song/lyric?lv=1&kv=1&tv=-1&id=" + bestId, "https://music.163.com/");
        if (lyric == null) return null;
        JSONObject lrc = new JSONObject(lyric).optJSONObject("lrc");
        List<Line> lines = parseLrc(lrc == null ? "" : lrc.optString("lyric", ""));
        // NetEase puts credits ("作词 : ...", "Composer: ...") in the first lines: leave them out.
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (!l.text.matches(".*(作词|作曲|编曲|制作人|混音|母带|Composer|Lyricist|Producer)\\s*[:：].*")) out.add(l);
        return out.isEmpty() ? null : new Lyrics(out, new ArrayList<String>(), false, "NetEase");
    }

    // MARK: - lyrics.ovh (text only)

    private static Lyrics fromOvh(String a, String t) throws Exception {
        String body = get("https://api.lyrics.ovh/v1/" + path(a) + "/" + path(t), null);
        if (body == null) return null;
        String text = new JSONObject(body).optString("lyrics", "");
        List<String> plain = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("Paroles de la chanson")) plain.add(s);
        }
        return plain.isEmpty() ? null : new Lyrics(new ArrayList<Line>(), plain, false, "lyrics.ovh");
    }

    // MARK: - Parsing and matching

    static List<Line> parseLrc(String s) {
        List<Line> synced = new ArrayList<>();
        for (String raw : (s == null ? "" : s).split("\n")) {
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
        return synced;
    }

    /** "Song - Remastered 2011", "Song (feat. X)" → "Song". */
    static String clean(String title) {
        if (title == null) return "";
        String t = title.replaceAll("(?i)\\s*[(\\[](feat|ft|with|con)\\.?\\s[^)\\]]*[)\\]]", "");
        t = t.replaceAll("(?i)\\s+-\\s+.*(remaster|version|versión|live|en vivo|edit|mix|mono|stereo|acoustic|acústic).*$", "");
        return t.trim();
    }

    /** "A, B & C" → "A" (the sources match the main artist best). */
    static String mainArtist(String artist) {
        if (artist == null) return "";
        return artist.split(",| & | feat\\.? | ft\\.? | x ")[0].trim();
    }

    /** Lower case, no accents or punctuation: for comparing names. */
    private static String simple(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String path(String s) throws Exception {
        return enc(s).replace("+", "%20");
    }

    private static String get(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "FreeMyTV karaoke (github.com/simbasjungles/free-my-tv)");
        c.setRequestProperty("Accept", "application/json");
        if (referer != null) c.setRequestProperty("Referer", referer);
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
