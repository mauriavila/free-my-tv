package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Karaoke: while Spotify (or any music app) plays, the song's lyrics in large type, synced line by line, the line
 * being sung filling up as it goes, over a slowly moving background in the album's colors. The music keeps its
 * original vocals: this is for singing along. Lyrics come from LRCLIB (Lyrics). Seeing what plays needs the
 * notification listener (MediaListener), turned on once with adb.
 * Remote: ← → move the lyrics earlier or later (if they run ahead or behind), OK pauses, ⏪ ⏩ change the song.
 */
public class KaraokeActivity extends Activity {
    static final String LISTENER = "io.freemytv/io.freemytv.MediaListener";

    private static final int NO_ACCESS = 0, WAITING = 1, LOADING = 2, NO_LYRICS = 3, INSTRUMENTAL = 4, READY = 5;
    private static final Map<String, Lyrics> CACHE = new HashMap<>();   // this process's songs, found or not (null)

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private KaraokeView view;
    private MediaSessionManager sessions;
    private ComponentName listener;
    private MediaController controller;

    private volatile int state = WAITING;
    private String trackKey = "", title = "", artist = "";
    private Lyrics lyrics;
    private long offsetMs, offsetShownAt = -10_000;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new KaraokeView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        connect();
        view.start();
    }

    @Override protected void onPause() {
        view.stop();
        disconnect();
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // MARK: - What's playing

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { onMetadata(metadata); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { view.invalidate(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };

    private void connect() {
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main);
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            state = NO_ACCESS;
        }
    }

    private void disconnect() {
        try {
            if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (RuntimeException ignored) {
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
    }

    private void refresh() {
        try {
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            state = NO_ACCESS;
        }
    }

    /** Spotify if it's there (playing first), else whatever plays, else the first session. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        int bestScore = -1;
        for (MediaController m : list == null ? new ArrayList<MediaController>() : list) {
            PlaybackState s = m.getPlaybackState();
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            int score = (m.getPackageName().contains("spotify") ? 2 : 0) + (playing ? 4 : 0);
            if (score > bestScore) { bestScore = score; best = m; }
        }
        if (controller != null && best != null && controller.getSessionToken().equals(best.getSessionToken())) return;
        if (controller != null) controller.unregisterCallback(callback);
        controller = best;
        if (controller == null) {
            setWaiting();
            return;
        }
        controller.registerCallback(callback, main);
        onMetadata(controller.getMetadata());
    }

    private void setWaiting() {
        state = WAITING;
        trackKey = "";
        lyrics = null;
        view.setSong("", "", null);
    }

    private void onMetadata(MediaMetadata m) {
        if (m == null) { setWaiting(); return; }
        final String t = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        final String a = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        final String album = text(m, MediaMetadata.METADATA_KEY_ALBUM, null);
        final long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (t.isEmpty()) { setWaiting(); return; }
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        final String key = Lyrics.key(a, t);
        boolean same = key.equals(trackKey);
        title = t;
        artist = a;
        if (!same || (art != null && art != view.art)) view.setSong(t, a, art);
        if (art == null) {
            final String uri = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI);
            if (uri.startsWith("http")) io.execute(() -> {
                Bitmap b = download(uri);
                if (b != null) main.post(() -> { if (key.equals(trackKey)) view.setSong(title, artist, b); });
            });
        }
        if (same) return;
        trackKey = key;
        offsetMs = 0;
        lyrics = null;
        if (CACHE.containsKey(key)) {
            show(CACHE.get(key));
            return;
        }
        state = LOADING;
        io.execute(() -> {
            final Lyrics l = Lyrics.find(a, t, album, duration);
            main.post(() -> {
                CACHE.put(key, l);
                if (key.equals(trackKey)) show(l);
            });
        });
    }

    private void show(Lyrics l) {
        lyrics = l;
        state = l == null || !l.any() ? (l != null && l.instrumental ? INSTRUMENTAL : NO_LYRICS) : READY;
        view.lyricsChanged();
    }

    private static String text(MediaMetadata m, String key, String fallback) {
        CharSequence v = m.getText(key);
        if ((v == null || v.length() == 0) && fallback != null) v = m.getText(fallback);
        return v == null ? "" : v.toString().trim();
    }

    private static Bitmap download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10_000);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Where the song is now (ms), with the person's correction; -1 if unknown. */
    private long position() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        if (s == null) return -1;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return pos + offsetMs;
    }

    private boolean playing() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    // MARK: - Remote

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isOurs(code) || super.dispatchKeyEvent(e);
        MediaController.TransportControls tc = controller == null ? null : controller.getTransportControls();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                offsetMs += 250;   // the lyrics were late: show them earlier
                offsetShownAt = SystemClock.uptimeMillis();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                offsetMs -= 250;
                offsetShownAt = SystemClock.uptimeMillis();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (tc != null && e.getRepeatCount() == 0) { if (playing()) tc.pause(); else tc.play(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (tc != null && e.getRepeatCount() == 0) tc.skipToNext();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (tc != null && e.getRepeatCount() == 0) tc.skipToPrevious();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static boolean isOurs(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return true;
            default:
                return false;
        }
    }

    // MARK: - The screen

    private final class KaraokeView extends View implements Choreographer.FrameCallback {
        private boolean running;
        private float k = 1, w = 1920, h = 1080;
        private final long start = SystemClock.uptimeMillis();

        // Background: the album art, tiny and stretched (as soft as a heavy blur), and three moving lights in its colors.
        private Bitmap tiny;
        private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint blob = new Paint();
        private final RadialGradient[] blobs = new RadialGradient[3];
        private final Matrix m = new Matrix();
        private final Paint veil = new Paint();
        private long songAt = -10_000;
        private final RectF rect = new RectF();

        // Header
        private Bitmap art;
        private Shader artShader;
        private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Paint titleP, artistP, hintP, bigP, smallP, nextP, stateP, cmdP, pillP;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spin = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Lyrics layout, redone when the line changes
        private int shownIndex = -2;
        private long scrollAt = -10_000;
        private final List<String> wrapped = new ArrayList<>();
        private float bigSize;

        KaraokeView(Context c) {
            super(c);
            setFocusable(true);
        }

        void start() {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
            w = nw;
            h = nh;
            k = nw / 1920f;
            titleP = Ui.text(Ui.displaySemibold, 44 * k, 0xFFFFFFFF);
            artistP = Ui.text(Ui.medium, 32 * k, 0xB3FFFFFF);
            hintP = Ui.text(Ui.medium, 24 * k, 0x8CFFFFFF);
            hintP.setTextAlign(Paint.Align.RIGHT);
            bigP = Ui.text(Ui.displaySemibold, 84 * k, 0xFFFFFFFF);
            bigP.setTextAlign(Paint.Align.CENTER);
            smallP = Ui.text(Ui.displayMedium, 52 * k, 0xFFFFFFFF);
            smallP.setTextAlign(Paint.Align.CENTER);
            nextP = Ui.text(Ui.displayMedium, 52 * k, 0xFFFFFFFF);
            nextP.setTextAlign(Paint.Align.CENTER);
            stateP = Ui.text(Ui.displaySemibold, 60 * k, 0xFFFFFFFF);
            stateP.setTextAlign(Paint.Align.CENTER);
            cmdP = Ui.text(Ui.medium, 28 * k, 0xCCFFFFFF);
            cmdP.setTextAlign(Paint.Align.CENTER);
            pillP = Ui.text(Ui.displaySemibold, 30 * k, 0xFF111111);
            pillP.setTextAlign(Paint.Align.CENTER);
            spin.setStyle(Paint.Style.STROKE);
            spin.setStrokeWidth(5 * k);
            spin.setStrokeCap(Paint.Cap.ROUND);
            spin.setColor(0xFFFFFFFF);
            veil.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x8C000000, 0x59000000, 0x99000000}, null, Shader.TileMode.CLAMP));
            makeBlobs();
            artShader = null;
            shownIndex = -2;
        }

        void setSong(String t, String a, Bitmap cover) {
            art = cover;
            artShader = null;
            tiny = cover == null ? null : Bitmap.createScaledBitmap(cover, 24, 24, true);
            makeBlobs();
            songAt = SystemClock.uptimeMillis();
            shownIndex = -2;
            invalidate();
        }

        void lyricsChanged() {
            shownIndex = -2;
            invalidate();
        }

        /** Three colors from the art (left, middle, right), a little brighter and more saturated; violet and pink without it. */
        private void makeBlobs() {
            int[] colors = {0xFF6A11CB, 0xFFFC2576, 0xFF2575FC};
            if (tiny != null) {
                for (int i = 0; i < 3; i++) colors[i] = vivid(average(tiny, i * 8, (i + 1) * 8));
            }
            float r = 760 * k;
            for (int i = 0; i < 3; i++) {
                blobs[i] = new RadialGradient(0, 0, r, Ui.alpha(colors[i], 0.75f), Ui.alpha(colors[i], 0f), Shader.TileMode.CLAMP);
            }
        }

        private int average(Bitmap b, int x0, int x1) {
            long r = 0, g = 0, bl = 0, n = 0;
            for (int x = x0; x < x1; x++) for (int y = 0; y < b.getHeight(); y++) {
                int p = b.getPixel(x, y);
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
            return n == 0 ? 0xFF6A11CB : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        }

        private int vivid(int c) {
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1, hsv[1] * 1.4f + 0.15f);
            hsv[2] = Math.max(0.55f, Math.min(1, hsv[2] * 1.3f));
            return Color.HSVToColor(hsv);
        }

        @Override protected void onDraw(Canvas c) {
            float t = (SystemClock.uptimeMillis() - start) / 1000f;
            drawBackground(c, t);
            drawHeader(c);
            switch (state) {
                case NO_ACCESS: drawNoAccess(c); break;
                case WAITING:
                    drawState(c, Ui.tr("Coloque uma música no Spotify", "Play a song on Spotify", "Poné una canción en Spotify"),
                            Ui.tr("A letra aparece aqui, sincronizada.", "The lyrics show up here, in sync.", "La letra aparece acá, sincronizada."));
                    break;
                case LOADING:
                    drawState(c, Ui.tr("Procurando a letra…", "Looking for the lyrics…", "Buscando la letra…"), null);
                    Ui.spinner(c, w / 2, h * 0.62f, 26 * k, t, spin);
                    break;
                case NO_LYRICS:
                    drawState(c, Ui.tr("Não achei a letra desta música", "No lyrics found for this song", "No encontré la letra de esta canción"),
                            Ui.tr("Passe para a próxima com ⏩", "Skip to the next one with ⏩", "Pasá a la siguiente con ⏩"));
                    break;
                case INSTRUMENTAL:
                    drawState(c, "♪  Instrumental  ♪", null);
                    break;
                default:
                    if (lyrics != null && !lyrics.synced.isEmpty()) drawSynced(c); else drawPlain(c);
            }
            drawOffset(c);
        }

        private void drawBackground(Canvas c, float t) {
            c.drawColor(0xFF0B0B10);
            float fadeIn = Ui.clamp((SystemClock.uptimeMillis() - songAt) / 900f);
            if (tiny != null) {
                bgPaint.setAlpha(Math.round(255 * 0.55f * fadeIn));
                rect.set(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f);
                c.drawBitmap(tiny, null, rect, bgPaint);
            }
            for (int i = 0; i < 3; i++) {
                float ph = i * 2.1f;
                float x = w * (0.5f + 0.38f * (float) Math.sin(t * (0.11f + 0.03f * i) + ph));
                float y = h * (0.5f + 0.32f * (float) Math.cos(t * (0.08f + 0.025f * i) + ph * 1.3f));
                m.setTranslate(x, y);
                blobs[i].setLocalMatrix(m);
                blob.setShader(blobs[i]);
                blob.setAlpha(Math.round(255 * (0.55f + 0.45f * fadeIn)));
                c.drawRect(0, 0, w, h, blob);
            }
            c.drawRect(0, 0, w, h, veil);
        }

        private void drawHeader(Canvas c) {
            float x = 80 * k, y = 64 * k, s = 130 * k;
            boolean song = !title.isEmpty() && state != NO_ACCESS;
            if (song && art != null) {
                if (artShader == null) {
                    artShader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    Matrix am = new Matrix();
                    am.setScale(s / art.getWidth(), s / art.getHeight());
                    am.postTranslate(x, y);
                    artShader.setLocalMatrix(am);
                }
                artPaint.setShader(artShader);
                rect.set(x, y, x + s, y + s);
                c.drawRoundRect(rect, 18 * k, 18 * k, artPaint);
            }
            float tx = song && art != null ? x + s + 32 * k : x;
            if (song) {
                c.drawText(ellipsize(title, titleP, 1050 * k), tx, y + 58 * k, titleP);
                c.drawText(ellipsize(artist, artistP, 1050 * k), tx, y + 106 * k, artistP);
            }
            c.drawText(Ui.tr("◀ ▶ ajustar letra · OK pausa · ⏪ ⏩ música", "◀ ▶ adjust lyrics · OK pause · ⏪ ⏩ song",
                    "◀ ▶ ajustar letra · OK pausa · ⏪ ⏩ tema"), w - 80 * k, y + 40 * k, hintP);
        }

        private void drawState(Canvas c, String line, String sub) {
            c.drawText(line, w / 2, h * 0.5f, stateP);
            if (sub != null) c.drawText(sub, w / 2, h * 0.5f + 70 * k, cmdP);
        }

        private void drawNoAccess(Canvas c) {
            c.drawText(Ui.tr("Falta uma permissão (uma vez só)", "One permission missing (only once)", "Falta un permiso (una sola vez)"),
                    w / 2, h * 0.42f, stateP);
            c.drawText(Ui.tr("No computador, com o adb conectado:", "On the computer, with adb connected:", "En la PC, con adb conectado:"),
                    w / 2, h * 0.42f + 80 * k, cmdP);
            c.drawText("adb shell settings put secure enabled_notification_listeners " + LISTENER, w / 2, h * 0.42f + 140 * k, cmdP);
            c.drawText(Ui.tr("Depois abra o Karaokê de novo.", "Then open Karaoke again.", "Después abrí Karaoke de nuevo."),
                    w / 2, h * 0.42f + 200 * k, cmdP);
        }

        private void drawSynced(Canvas c) {
            List<Lyrics.Line> lines = lyrics.synced;
            long pos = position();
            int idx = -1;
            for (int i = 0; i < lines.size(); i++) { if (lines.get(i).at <= pos) idx = i; else break; }
            long now = SystemClock.uptimeMillis();
            if (idx != shownIndex) {
                if (shownIndex != -2) scrollAt = now;
                shownIndex = idx;
                layoutCurrent(idx >= 0 ? lines.get(idx).text : "");
            }
            float e = 1 - Ui.clamp((now - scrollAt) / 380f);
            float shift = e * e * 150 * k;            // eases up into place
            float cy = h * 0.55f + shift;
            // Previous line, dim, above.
            if (idx >= 1) {
                smallP.setAlpha(Math.round(255 * 0.32f));
                c.drawText(ellipsize(orNote(lines.get(idx - 1).text), smallP, 1700 * k), w / 2, cy - 190 * k, smallP);
            }
            // The line being sung: dim, then filled with white as it goes.
            long at = idx >= 0 ? lines.get(idx).at : 0;
            long next = idx + 1 < lines.size() ? lines.get(idx + 1).at : at + 5000;
            float p = idx < 0 ? 0 : Ui.clamp((pos - at) / (float) Math.max(400, next - at));
            drawFilling(c, cy, p, 1 - e);
            // Next two lines, below.
            float ny = cy + (wrapped.size() > 1 ? 120 * k : 0) + 150 * k;
            for (int j = 1; j <= 2 && idx + j < lines.size(); j++) {
                nextP.setAlpha(Math.round(255 * (j == 1 ? 0.62f : 0.3f)));
                c.drawText(ellipsize(orNote(lines.get(idx + j).text), nextP, 1700 * k), w / 2, ny + (j - 1) * 92 * k, nextP);
            }
        }

        private void drawFilling(Canvas c, float cy, float p, float appear) {
            if (wrapped.isEmpty()) return;
            bigP.setTextSize(bigSize);
            float total = 0;
            for (String s : wrapped) total += bigP.measureText(s);
            float done = p * total, lineH = bigSize * 1.3f;
            float top = cy - (wrapped.size() - 1) * lineH / 2;
            for (int i = 0; i < wrapped.size(); i++) {
                String s = wrapped.get(i);
                float lw = bigP.measureText(s), y = top + i * lineH, left = w / 2 - lw / 2;
                bigP.setAlpha(Math.round(255 * 0.38f * (0.4f + 0.6f * appear)));
                c.drawText(s, w / 2, y, bigP);
                float f = Math.max(0, Math.min(lw, done));
                if (f > 0) {
                    c.save();
                    c.clipRect(left, y - bigSize, left + f, y + bigSize * 0.4f);
                    bigP.setAlpha(255);
                    c.drawText(s, w / 2, y, bigP);
                    c.restore();
                }
                done -= lw;
            }
        }

        /** Up to two lines that fit the width; the type shrinks a little for long lines. */
        private void layoutCurrent(String text) {
            wrapped.clear();
            String s = orNote(text);
            float max = 1680 * k;
            for (float size = 84; size >= 56; size -= 4) {
                bigSize = size * k;
                bigP.setTextSize(bigSize);
                List<String> out = wrap(s, bigP, max);
                if (out.size() <= 2 || size <= 56) {
                    wrapped.addAll(out.size() > 2 ? out.subList(0, 2) : out);
                    return;
                }
            }
        }

        private List<String> wrap(String s, Paint p, float max) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : s.split(" ")) {
                String trial = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(trial) > max && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(trial);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            return out;
        }

        /** Not synced: the plain lyrics, the window moving with the song's progress. */
        private void drawPlain(Canvas c) {
            List<String> lines = lyrics.plain;
            PlaybackState s = controller == null ? null : controller.getPlaybackState();
            MediaMetadata md = controller == null ? null : controller.getMetadata();
            long duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            float p = s == null || duration <= 0 ? 0 : Ui.clamp(position() / (float) duration);
            int center = Math.min(lines.size() - 1, (int) (p * lines.size()));
            c.drawText(Ui.tr("Letra sem sincronizar", "Lyrics not synced", "Letra sin sincronizar"), w / 2, h * 0.28f, cmdP);
            for (int j = -2; j <= 3; j++) {
                int i = center + j;
                if (i < 0 || i >= lines.size()) continue;
                Paint pt = j == 0 ? stateP : smallP;
                pt.setAlpha(Math.round(255 * (j == 0 ? 1f : j == -1 || j == 1 ? 0.55f : 0.28f)));
                c.drawText(ellipsize(lines.get(i), pt, 1700 * k), w / 2, h * 0.55f + j * 96 * k, pt);
            }
            stateP.setAlpha(255);
        }

        private void drawOffset(Canvas c) {
            long since = SystemClock.uptimeMillis() - offsetShownAt;
            if (since > 1800) return;
            float a = since < 1400 ? 1 : 1 - (since - 1400) / 400f;
            String text = Ui.tr("Letra ", "Lyrics ", "Letra ") + (offsetMs >= 0 ? "+" : "−")
                    + String.format(java.util.Locale.ROOT, "%.2f s", Math.abs(offsetMs) / 1000f);
            float tw = pillP.measureText(text) + 60 * k;
            rect.set(w / 2 - tw / 2, h - 150 * k, w / 2 + tw / 2, h - 86 * k);
            fill.setColor(Ui.alpha(0xF2FFFFFF, a));
            c.drawRoundRect(rect, 32 * k, 32 * k, fill);
            pillP.setAlpha(Math.round(255 * a));
            c.drawText(text, w / 2, rect.centerY() + Ui.halfCap(pillP), pillP);
        }

        private String orNote(String s) {
            return s == null || s.trim().isEmpty() ? "♪" : s;
        }

        private String ellipsize(String s, Paint p, float max) {
            if (p.measureText(s) <= max) return s;
            int n = p.breakText(s, true, max - p.measureText("…"), null);
            return s.substring(0, Math.max(0, n)) + "…";
        }
    }
}
