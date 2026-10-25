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
 * Songs the person synced themselves with the remote (the karaoke's sync mode) come first, from the TV, and can be
 * published to LRCLIB for everyone. Network calls and files: call from a background thread.
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

    /** The lyrics for this song: synced by the person, else synced if any source has them, else plain; null if none. */
    static Lyrics find(java.io.File dir, String artist, String title, String album, long durationMs) {
        String t = clean(title), a = mainArtist(artist);
        Lyrics mine = local(dir, artist, title);
        if (mine != null) return mine;
        Lyrics kept = kept(dir, artist, title);   // found before: no need for the sources (LRCLIB is sometimes down)
        if (kept != null) return kept;
        Lyrics found = search(a, t, album, durationMs);
        if (found != null && !found.synced.isEmpty()) keep(dir, artist, title, found);
        return found;
    }

    private static Lyrics search(String a, String t, String album, long durationMs) {
        AppLog.w("lyrics for: " + a + " · " + t + " · " + durationMs / 1000 + " s");
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

    // MARK: - Synced on this TV

    static final String MINE = "♥";   // the source shown for lyrics synced with the remote

    static Lyrics synced(List<Line> lines, List<String> plain) {
        return new Lyrics(new ArrayList<>(lines), new ArrayList<>(plain), false, MINE);
    }

    private static java.io.File file(java.io.File dir, String artist, String title) {
        return new java.io.File(new java.io.File(dir, "lyrics"), Integer.toHexString(key(artist, title).hashCode()) + ".lrc");
    }

    private static Lyrics local(java.io.File dir, String artist, String title) {
        java.io.File f = file(dir, artist, title);
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            List<Line> lines = parseLrc(out.toString("UTF-8"));
            List<String> plain = new ArrayList<>();
            for (Line l : lines) if (!l.text.isEmpty()) plain.add(l.text);
            return lines.isEmpty() ? null : new Lyrics(lines, plain, false, MINE);
        } catch (Exception e) {
            AppLog.w("lyrics: couldn't read " + f + ": " + e.getMessage());
            return null;
        }
    }

    /** Synced lyrics found before, kept on the TV (lyrics-kept/, with their source on the first line). */
    private static java.io.File keptFile(java.io.File dir, String artist, String title) {
        return new java.io.File(new java.io.File(dir, "lyrics-kept"), Integer.toHexString(key(artist, title).hashCode()) + ".lrc");
    }

    private static Lyrics kept(java.io.File dir, String artist, String title) {
        java.io.File f = keptFile(dir, artist, title);
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            String text = out.toString("UTF-8");
            int nl = text.indexOf('\n');
            String source = nl > 0 ? text.substring(0, nl).trim() : "LRCLIB";
            List<Line> lines = parseLrc(nl > 0 ? text.substring(nl + 1) : text);
            List<String> plain = new ArrayList<>();
            for (Line l : lines) if (!l.text.isEmpty()) plain.add(l.text);
            return lines.isEmpty() ? null : new Lyrics(lines, plain, false, source);
        } catch (Exception e) {
            return null;
        }
    }

    private static void keep(java.io.File dir, String artist, String title, Lyrics l) {
        try {
            java.io.File f = keptFile(dir, artist, title);
            f.getParentFile().mkdirs();
            try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                out.write((l.source + "\n" + toLrc(l.synced)).getBytes("UTF-8"));
            }
        } catch (Exception e) {
            AppLog.w("lyrics: couldn't keep: " + e.getMessage());
        }
    }

    static void saveLocal(java.io.File dir, String artist, String title, List<Line> lines) throws Exception {
        java.io.File f = file(dir, artist, title);
        f.getParentFile().mkdirs();
        try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
            out.write(("[ar:" + artist + "]\n[ti:" + title + "]\n" + toLrc(lines)).getBytes("UTF-8"));
        }
    }

    static String toLrc(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            long cs = Math.max(0, l.at) / 10;
            sb.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100)).append(l.text).append('\n');
        }
        return sb.toString();
    }

    /**
     * Publishes synced lyrics to LRCLIB, so every karaoke (and app) using it gets them. LRCLIB asks for a small proof
     * of work instead of an account: a SHA-256 of its prefix plus a number, at or below its target (tens of seconds
     * on a TV, on every core). Returns null when published, else what went wrong.
     */
    static String publish(String track, String artist, String album, long durationMs, List<Line> lines) {
        try {
            JSONObject challenge = new JSONObject(send(LRCLIB + "request-challenge", null, "", null));
            String prefix = challenge.getString("prefix");
            String nonce = solve(prefix, challenge.getString("target"));
            if (nonce == null) return "proof of work took too long";
            StringBuilder plain = new StringBuilder();
            for (Line l : lines) if (!l.text.isEmpty()) plain.append(l.text).append('\n');
            JSONObject body = new JSONObject()
                    .put("trackName", track).put("artistName", artist).put("albumName", album)
                    .put("duration", Math.round(durationMs / 1000.0))
                    .put("plainLyrics", plain.toString().trim()).put("syncedLyrics", toLrc(lines).trim());
            send(LRCLIB + "publish", prefix + ":" + nonce, body.toString(), null);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String solve(final String prefix, String targetHex) throws Exception {
        final byte[] target = new byte[32];
        for (int i = 0; i < 32; i++) target[i] = (byte) Integer.parseInt(targetHex.substring(i * 2, i * 2 + 2), 16);
        final int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        final java.util.concurrent.atomic.AtomicReference<String> found = new java.util.concurrent.atomic.AtomicReference<>();
        final long deadline = System.currentTimeMillis() + 5 * 60_000;
        Thread[] all = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int start = t;
            all[t] = new Thread(() -> {
                try {
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] p = prefix.getBytes("US-ASCII"), digits = new byte[20];
                    for (long n = start; found.get() == null; n += threads) {
                        if ((n & 0xFFFF) < threads && System.currentTimeMillis() > deadline) return;
                        int len = 0;
                        for (long v = n; ; v /= 10) { digits[19 - len++] = (byte) ('0' + v % 10); if (v < 10) break; }
                        md.update(p);
                        md.update(digits, 20 - len, len);
                        if (atMost(md.digest(), target)) found.compareAndSet(null, Long.toString(n));
                    }
                } catch (Exception ignored) {
                }
            }, "lrclib-pow");
            all[t].setPriority(Thread.MIN_PRIORITY);   // the karaoke keeps drawing smoothly
            all[t].start();
        }
        for (Thread t : all) t.join();
        return found.get();
    }

    private static boolean atMost(byte[] hash, byte[] target) {
        for (int i = 0; i < hash.length; i++) {
            int h = hash[i] & 0xFF, t = target[i] & 0xFF;
            if (h > t) return false;
            if (h < t) return true;
        }
        return true;
    }

    // MARK: - LRCLIB

    /**
     * Synced lyrics first: the exact match (name, artist, length), then a search by name and artist, then a free-text
     * search (Spotify's names often differ from LRCLIB's in case, featured artists or the album), keeping the closest
     * in length. Plain lyrics only if none of them is synced.
     */
    private static Lyrics fromLrclib(String a, String t, String album, long durationMs) throws Exception {
        Lyrics plainOnly = null;
        if (durationMs > 0) {
            String body = get(LRCLIB + "get?artist_name=" + enc(a) + "&track_name=" + enc(t)
                    + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                    + "&duration=" + Math.round(durationMs / 1000.0), null);
            if (body != null) {
                Lyrics l = fromLrclibJson(new JSONObject(body));
                if (l.instrumental || !l.synced.isEmpty()) return l;
                if (l.any()) plainOnly = l;
            }
        }
        JSONObject best = bestOf(get(LRCLIB + "search?track_name=" + enc(t) + "&artist_name=" + enc(a), null), a, t, durationMs, false);
        if (best == null || !synced(best)) {
            JSONObject wide = bestOf(get(LRCLIB + "search?q=" + enc(a + " " + t), null), a, t, durationMs, true);
            if (wide != null && (best == null || synced(wide))) best = wide;
        }
        AppLog.w("lyrics lrclib: " + (best == null ? "nothing" : (synced(best) ? "synced" : "plain") + " · "
                + best.optString("trackName") + " · " + best.optString("artistName") + " · " + best.optDouble("duration") + " s"));
        if (best != null && (synced(best) || plainOnly == null)) return fromLrclibJson(best);
        return plainOnly;
    }

    private static boolean synced(JSONObject o) {
        return !o.isNull("syncedLyrics") && !o.optString("syncedLyrics", "").isEmpty();
    }

    /** The closest in length (within 10 s), synced ones first; `check` keeps only the same song and artist. */
    private static JSONObject bestOf(String body, String a, String t, long durationMs, boolean check) throws Exception {
        JSONArray list = body == null ? null : new JSONArray(body);
        String wantT = simple(t), wantA = simple(a);
        JSONObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if (check) {
                String name = simple(clean(o.optString("trackName"))), artist = simple(o.optString("artistName"));
                if (name.isEmpty() || !(name.contains(wantT) || wantT.contains(name))) continue;
                if (!wantA.isEmpty() && !artist.contains(wantA) && !wantA.contains(artist)) continue;
            }
            double diff = durationMs > 0 ? Math.abs(o.optDouble("duration", 0) * 1000 - durationMs) / 1000.0 : 0;
            if (durationMs > 0 && diff > 10) continue;
            double score = diff + (synced(o) ? 0 : 100);
            if (score < bestScore) { bestScore = score; best = o; }
        }
        return best;
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

    /** Two retries, 1.5 and 4 s later: LRCLIB is sometimes busy for a moment (HTTP 503). */
    private static String get(String url, String referer) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return fetch(url, referer);
            } catch (java.io.IOException e) {
                if (attempt == 2) throw e;
                AppLog.w("lyrics: " + e.getMessage() + ", once more");
                Thread.sleep(attempt == 0 ? 1500 : 4000);
            }
        }
    }

    /** A POST with a JSON body (and LRCLIB's publish token, if any); the response body, or an error with LRCLIB's message. */
    private static String send(String url, String token, String body, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "FreeMyTV karaoke (github.com/simbasjungles/free-my-tv)");
        c.setRequestProperty("Content-Type", "application/json");
        if (token != null) c.setRequestProperty("X-Publish-Token", token);
        try {
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(body.getBytes("UTF-8")); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                try { byte[] buf = new byte[8192]; for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n); } finally { in.close(); }
            }
            String text = out.toString("UTF-8");
            if (code >= 400) throw new java.io.IOException("HTTP " + code + " " + text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String fetch(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
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
