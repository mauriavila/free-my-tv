package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

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
 * Karaoke, all from one screen: ▲ searches Spotify's catalog (SpotifyCatalog) and shows a list of songs with their
 * covers; OK on one makes Spotify's own app play it in the background (playFromUri on its media session), so the
 * karaoke never leaves. While it plays, the lyrics (Lyrics: LRCLIB, NetEase, lyrics.ovh) scroll smoothly in large
 * type, the line being sung filling up as it goes, over a slowly moving background in the album's colors. The music
 * keeps its original vocals: this is for singing along. Songs queued from phones (Spotify Connect) show up too.
 * Seeing and steering what plays needs the notification listener (MediaListener), turned on once with adb.
 * Remote: ▲ search, ◀ ▶ lyrics earlier or later, OK pause, ⏪ ⏩ previous or next song, ▼ Spotify's own screen.
 */
public class KaraokeActivity extends Activity {
    static final String LISTENER = "io.freemytv/io.freemytv.MediaListener";
    static final String SPOTIFY = "com.spotify.tv.android";

    private static final int NO_ACCESS = 0, WAITING = 1, LOADING = 2, NO_LYRICS = 3, INSTRUMENTAL = 4, READY = 5;
    private static final Map<String, Lyrics> CACHE = new HashMap<>();   // this process's songs, found or not (null)

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private KaraokeView view;
    private MediaSessionManager sessions;
    private ComponentName listener;
    private MediaController controller;

    private volatile int state = WAITING;
    private String trackKey = "", title = "", artist = "";
    private Lyrics lyrics;
    private long offsetMs, offsetShownAt = -10_000;
    private boolean resumed, returnOnNextSong;
    private String pendingUri;           // a song to play as soon as Spotify's session shows up

    // Search and the song list
    private EditText search;
    private String query = "";
    private List<SpotifyCatalog.Track> results;   // non-null: the list is showing
    private int resultIndex;
    private boolean searching;
    private String searchError;
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final BeatPulse beat = new BeatPulse();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new KaraokeView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        search = makeSearchBox();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        int side = (int) (getResources().getDisplayMetrics().widthPixels * 0.22f);
        lp.setMargins(side, (int) (getResources().getDisplayMetrics().heightPixels * 0.18f), side, 0);
        root.addView(search, lp);
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
        connect();   // followed from creation to the end, also from behind
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (state == NO_ACCESS) { disconnect(); connect(); } else refresh();
        beat.start();
        view.start();
    }

    @Override protected void onPause() {
        resumed = false;
        beat.stop();
        view.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        disconnect();
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
            if (sessions != null) pick(sessions.getActiveSessions(listener));
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
        boolean same = controller != null && best != null && controller.getSessionToken().equals(best.getSessionToken());
        if (!same) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = best;
            if (controller != null) controller.registerCallback(callback, main);
        }
        if (controller != null && pendingUri != null && canPlayUri()) {
            String uri = pendingUri;
            pendingUri = null;
            controller.getTransportControls().playFromUri(Uri.parse(uri), new Bundle());
            returnOnNextSong = true;
            AppLog.w("karaoke: played the waiting song once Spotify was up");
        }
        if (same) return;
        if (controller == null) setWaiting(); else onMetadata(controller.getMetadata());
    }

    private void setWaiting() {
        state = WAITING;
        trackKey = "";
        lyrics = null;
        title = "";
        artist = "";
        view.setSong(null);
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
        if (!same || (art != null && art != view.art)) view.setSong(art);
        if (art == null) {
            final String uri = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI);
            if (uri.startsWith("http")) io.execute(() -> {
                Bitmap b = download(uri, 2);
                if (b != null) main.post(() -> { if (key.equals(trackKey)) view.setSong(b); });
            });
        }
        if (same) return;
        trackKey = key;
        offsetMs = 0;
        lyrics = null;
        comeBack();
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

    private static Bitmap download(String url, int sample) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10_000);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
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

    // MARK: - Search and the song list

    private EditText makeSearchBox() {
        EditText e = new EditText(this);
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setHint(Ui.tr("Música ou artista…", "Song or artist…", "Canción o artista…"));
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0x99FFFFFF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, 44 * k);
        e.setTypeface(Ui.medium);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE015151C);
        bg.setStroke(Math.max(2, Math.round(3 * k)), 0x66FFFFFF);
        bg.setCornerRadius(44 * k);
        e.setBackground(bg);
        int pad = Math.round(34 * k);
        e.setPadding(pad, pad / 2, pad, pad / 2);
        e.setVisibility(View.GONE);
        e.setOnEditorActionListener((v, actionId, event) -> {
            String q = v.getText().toString().trim();
            closeSearch();
            if (!q.isEmpty()) runSearch(q);
            return true;
        });
        return e;
    }

    private void openSearch() {
        search.setText(query);
        search.setSelection(search.getText().length());
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setVisibility(View.GONE);
        view.requestFocus();
    }

    private void runSearch(final String q) {
        query = q;
        if (!SpotifyCatalog.configured(this)) {
            // No keys yet: Spotify's own search, and back here as soon as a song starts.
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).setPackage(SPOTIFY);
            if (!openSpotify(i)) openSpotify(getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY));
            return;
        }
        results = new ArrayList<>();
        resultIndex = 0;
        searching = true;
        searchError = null;
        final String region = Config.TMDB_REGION.trim().toUpperCase(java.util.Locale.ROOT);
        final String market = region.length() == 2 ? region : "US";
        io.execute(() -> {
            List<SpotifyCatalog.Track> found = null;
            String error = null;
            try {
                found = SpotifyCatalog.search(this, q, market);
            } catch (Exception e) {
                error = e.getMessage();
                AppLog.w("karaoke search: " + error);
            }
            final List<SpotifyCatalog.Track> list = found;
            final String err = error;
            main.post(() -> {
                if (results == null || !q.equals(query)) return;
                searching = false;
                searchError = err;
                results = list == null ? new ArrayList<SpotifyCatalog.Track>() : list;
                loadThumbs(results);
            });
        });
    }

    private void loadThumbs(List<SpotifyCatalog.Track> list) {
        for (final SpotifyCatalog.Track t : list) {
            if (t.image.isEmpty() || thumbs.containsKey(t.image)) continue;
            thumbs.put(t.image, null);
            io.execute(() -> {
                Bitmap b = download(t.image, 1);
                if (b != null) main.post(() -> thumbs.put(t.image, b));
            });
        }
    }

    private boolean canPlayUri() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        return controller != null && controller.getPackageName().contains("spotify")
                && (s == null || (s.getActions() & PlaybackState.ACTION_PLAY_FROM_URI) != 0);
    }

    /** Spotify plays it in the background; if Spotify isn't running yet, it opens, plays it, and we come back. */
    private void play(SpotifyCatalog.Track t) {
        results = null;
        if (canPlayUri()) {
            controller.getTransportControls().playFromUri(Uri.parse(t.uri()), new Bundle());
            Toast.makeText(this, Ui.tr("Tocando: ", "Playing: ", "Poniendo: ") + t.name, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingUri = t.uri();
        openSpotify(getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY));
    }

    /** Spotify in front; the karaoke returns as soon as a new song starts there. */
    private boolean openSpotify(Intent i) {
        if (i == null) return false;
        try {
            returnOnNextSong = true;
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            returnOnNextSong = false;
            return false;
        }
    }

    private void comeBack() {
        if (resumed || !returnOnNextSong) return;
        returnOnNextSong = false;
        // A moment for Spotify to settle, then back in front.
        main.postDelayed(() -> {
            try {
                startActivity(new Intent(this, KaraokeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                AppLog.w("karaoke: back in front");
            } catch (RuntimeException e) {
                AppLog.w("karaoke: couldn't come back: " + e.getMessage());
            }
        }, 600);
    }

    // MARK: - Remote

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        if (search.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) closeSearch(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (results != null) {   // the song list
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) results = null; return true; }
            if (!down) return true;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (resultIndex == 0 && first) { results = null; openSearch(); }
                    else resultIndex = Math.max(0, resultIndex - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    resultIndex = Math.min(Math.max(0, results.size() - 1), resultIndex + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (first && resultIndex < results.size()) play(results.get(resultIndex));
                    return true;
                default:
                    return true;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (!down) return isOurs(code) || super.dispatchKeyEvent(e);
        MediaController.TransportControls tc = controller == null ? null : controller.getTransportControls();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_SEARCH:
                if (first) openSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) openSpotify(getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY));
                return true;
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
                if (tc != null && first) { if (playing()) tc.pause(); else tc.play(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (tc != null && first) tc.skipToNext();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (tc != null && first) tc.skipToPrevious();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static boolean isOurs(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_SEARCH:
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
        private long lastFrame;

        // Background: the album art, tiny and stretched (as soft as a heavy blur), and three moving lights in its colors.
        Bitmap art;
        private Bitmap tiny;
        private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint blob = new Paint();
        private final RadialGradient[] blobs = new RadialGradient[3];
        private final Matrix m = new Matrix();
        private final Paint veil = new Paint();

        // Party: stage beams sweeping from below, slowly changing color, and drifting sparkles; with BeatPulse they
        // swell on the beat. Soft changes only: no strobe or full-screen flashes.
        private static final int BEAMS = 4, SPARKS = 42, HUES = 36;
        private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path beam = new android.graphics.Path();
        private final android.graphics.ColorFilter[] tints = new android.graphics.ColorFilter[HUES];
        private final int[] hueColors = new int[HUES];
        private final float[] sx = new float[SPARKS], sy = new float[SPARKS], svx = new float[SPARKS], svy = new float[SPARKS],
                sr = new float[SPARKS], sph = new float[SPARKS];
        private float pulse;
        private long songAt = -10_000;
        private final RectF rect = new RectF();

        private Shader artShader;
        private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Paint titleP, artistP, hintP, lineP, stateP, cmdP, pillP, rowTitleP, rowSubP, sourceP;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spin = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Lyrics: a smooth scroll position in lines (it glides to the line being sung).
        private float scroll = -1;
        private float listScroll;

        KaraokeView(Context c) {
            super(c);
            setFocusable(true);
        }

        void start() {
            running = true;
            lastFrame = SystemClock.uptimeMillis();
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
            sourceP = Ui.text(Ui.medium, 22 * k, 0x66FFFFFF);
            sourceP.setTextAlign(Paint.Align.RIGHT);
            lineP = Ui.text(Ui.displaySemibold, 80 * k, 0xFFFFFFFF);
            lineP.setTextAlign(Paint.Align.CENTER);
            lineP.setFlags(lineP.getFlags() | Paint.LINEAR_TEXT_FLAG);   // scaled smoothly, without snapping to pixels
            stateP = Ui.text(Ui.displaySemibold, 60 * k, 0xFFFFFFFF);
            stateP.setTextAlign(Paint.Align.CENTER);
            cmdP = Ui.text(Ui.medium, 28 * k, 0xCCFFFFFF);
            cmdP.setTextAlign(Paint.Align.CENTER);
            pillP = Ui.text(Ui.displaySemibold, 30 * k, 0xFF111111);
            pillP.setTextAlign(Paint.Align.CENTER);
            rowTitleP = Ui.text(Ui.semibold, 38 * k, 0xFFFFFFFF);
            rowSubP = Ui.text(Ui.medium, 29 * k, 0xB3FFFFFF);
            spin.setStyle(Paint.Style.STROKE);
            spin.setStrokeWidth(5 * k);
            spin.setStrokeCap(Paint.Cap.ROUND);
            spin.setColor(0xFFFFFFFF);
            veil.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x8C000000, 0x59000000, 0x99000000}, null, Shader.TileMode.CLAMP));
            float len = h * 1.25f;
            beamPaint.setShader(new LinearGradient(0, 0, len, 0, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            beamPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            beam.reset();
            beam.moveTo(0, 0);
            beam.lineTo(len, -len * 0.11f);
            beam.lineTo(len, len * 0.11f);
            beam.close();
            for (int i = 0; i < HUES; i++) {
                hueColors[i] = Color.HSVToColor(new float[] {i * 360f / HUES, 0.75f, 1f});
                tints[i] = new android.graphics.PorterDuffColorFilter(hueColors[i], android.graphics.PorterDuff.Mode.SRC_IN);
            }
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < SPARKS; i++) {
                sx[i] = r.nextFloat() * w;
                sy[i] = r.nextFloat() * h;
                svx[i] = (r.nextFloat() - 0.5f) * 24 * k;
                svy[i] = -(10 + r.nextFloat() * 28) * k;
                sr[i] = (2 + r.nextFloat() * 4.5f) * k;
                sph[i] = r.nextFloat() * 6.28f;
            }
            sparkPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            makeBlobs();
            artShader = null;
        }

        void setSong(Bitmap cover) {
            art = cover;
            artShader = null;
            tiny = cover == null ? null : Bitmap.createScaledBitmap(cover, 24, 24, true);
            makeBlobs();
            songAt = SystemClock.uptimeMillis();
            scroll = -1;
            invalidate();
        }

        void lyricsChanged() {
            scroll = -1;
            invalidate();
        }

        /** Three colors from the art (left, middle, right), brighter and more saturated; violet and pink without it. */
        private void makeBlobs() {
            int[] colors = {0xFF6A11CB, 0xFFFC2576, 0xFF2575FC};
            if (tiny != null) for (int i = 0; i < 3; i++) colors[i] = vivid(average(tiny, i * 8, (i + 1) * 8));
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
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            float t = (now - start) / 1000f;
            drawBackground(c, t, dt);
            drawHeader(c);
            if (results != null) {
                drawResults(c, t, dt);
                return;
            }
            switch (state) {
                case NO_ACCESS: drawNoAccess(c); break;
                case WAITING:
                    drawState(c, Ui.tr("▲ Busque uma música", "▲ Search a song", "▲ Buscá una canción"),
                            Ui.tr("Ela toca aqui mesmo, com a letra sincronizada.", "It plays right here, with the lyrics in sync.",
                                    "Suena acá mismo, con la letra sincronizada."));
                    break;
                case LOADING:
                    drawState(c, Ui.tr("Procurando a letra…", "Looking for the lyrics…", "Buscando la letra…"), null);
                    Ui.spinner(c, w / 2, h * 0.62f, 26 * k, t, spin);
                    break;
                case NO_LYRICS:
                    drawState(c, Ui.tr("Não achei a letra desta música", "No lyrics found for this song", "No encontré la letra de esta canción"),
                            Ui.tr("Procurei no LRCLIB, NetEase e lyrics.ovh · ⏩ próxima", "Searched LRCLIB, NetEase and lyrics.ovh · ⏩ next",
                                    "Busqué en LRCLIB, NetEase y lyrics.ovh · ⏩ siguiente"));
                    break;
                case INSTRUMENTAL:
                    drawState(c, "♪  Instrumental  ♪", null);
                    break;
                default:
                    if (lyrics != null && !lyrics.synced.isEmpty()) drawSynced(c, dt); else drawPlain(c, dt);
                    if (lyrics != null) c.drawText(Ui.tr("Letra: ", "Lyrics: ", "Letra: ") + lyrics.source, w - 80 * k, h - 50 * k, sourceP);
            }
            drawOffset(c);
        }

        private void drawBackground(Canvas c, float t, float dt) {
            c.drawColor(0xFF0B0B10);
            // The beat: jumps to 1 and fades in ~0.4 s (no beat detection: a slow breathing instead).
            long sinceBeat = SystemClock.uptimeMillis() - beat.beatAt;
            float target = sinceBeat < 2000 ? (float) Math.exp(-sinceBeat / 260f) : 0.25f + 0.25f * (float) Math.sin(t * 1.6f);
            pulse += (target - pulse) * Ui.clamp(dt * 18);
            float fadeIn = Ui.clamp((SystemClock.uptimeMillis() - songAt) / 900f);
            if (tiny != null) {
                bgPaint.setAlpha(Math.round(255 * 0.5f * fadeIn));
                rect.set(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f);
                c.drawBitmap(tiny, null, rect, bgPaint);
            }
            for (int i = 0; i < 3; i++) {
                float ph = i * 2.1f;
                float x = w * (0.5f + 0.38f * (float) Math.sin(t * (0.11f + 0.03f * i) + ph));
                float y = h * (0.5f + 0.32f * (float) Math.cos(t * (0.08f + 0.025f * i) + ph * 1.3f));
                float grow = 1 + 0.12f * pulse;
                m.setScale(grow, grow);
                m.postTranslate(x, y);
                blobs[i].setLocalMatrix(m);
                blob.setShader(blobs[i]);
                blob.setAlpha(Math.round(255 * (0.5f + 0.4f * fadeIn + 0.1f * pulse)));
                c.drawRect(0, 0, w, h, blob);
            }
            drawBeams(c, t);
            drawSparks(c, t, dt);
            c.drawRect(0, 0, w, h, veil);
        }

        /** Four stage lights from below, sweeping slowly, each a color that drifts around the color wheel. */
        private void drawBeams(Canvas c, float t) {
            for (int i = 0; i < BEAMS; i++) {
                float ox = w * (0.08f + 0.28f * i), oy = h * 1.04f;
                float base = -90 + (i - 1.5f) * 14;                          // fanned out upwards
                float angle = base + 24 * (float) Math.sin(t * (0.35f + 0.07f * i) + i * 1.7f);
                int hue = (int) ((t * 6 + i * 9) % HUES);
                beamPaint.setColorFilter(tints[(hue + HUES) % HUES]);
                beamPaint.setAlpha(Math.round(255 * (0.16f + 0.2f * pulse)));
                c.save();
                c.translate(ox, oy);
                c.rotate(angle);
                c.drawPath(beam, beamPaint);
                c.restore();
            }
        }

        /** Sparkles drifting up, twinkling; brighter on the beat. */
        private void drawSparks(Canvas c, float t, float dt) {
            for (int i = 0; i < SPARKS; i++) {
                sx[i] += svx[i] * dt;
                sy[i] += svy[i] * dt * (1 + pulse);
                if (sy[i] < -10 * k) { sy[i] = h + 10 * k; sx[i] = (sx[i] * 7.31f + 97 * k) % w; }
                if (sx[i] < 0) sx[i] += w; else if (sx[i] > w) sx[i] -= w;
                float tw = 0.5f + 0.5f * (float) Math.sin(t * 2.3f + sph[i]);
                sparkPaint.setColor(hueColors[(i * 5 + (int) (t * 4)) % HUES]);
                sparkPaint.setAlpha(Math.round(255 * Ui.clamp(0.15f + 0.45f * tw + 0.35f * pulse)));
                c.drawCircle(sx[i], sy[i], sr[i] * (1 + 0.5f * pulse), sparkPaint);
            }
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
                c.drawText(ellipsize(title, titleP, 1000 * k), tx, y + 58 * k, titleP);
                c.drawText(ellipsize(artist, artistP, 1000 * k), tx, y + 106 * k, artistP);
            }
            c.drawText(results != null ? Ui.tr("▲ ▼ escolher · OK tocar · Voltar fechar", "▲ ▼ choose · OK play · Back close",
                    "▲ ▼ elegir · OK reproducir · Atrás cerrar")
                    : Ui.tr("▲ buscar · OK pausa · ⏪ ⏩ música", "▲ search · OK pause · ⏪ ⏩ song", "▲ buscar · OK pausa · ⏪ ⏩ tema"),
                    w - 80 * k, y + 40 * k, hintP);
            if (results == null) c.drawText(Ui.tr("◀ ▶ ajustar letra · ▼ Spotify", "◀ ▶ adjust lyrics · ▼ Spotify",
                    "◀ ▶ ajustar letra · ▼ Spotify"), w - 80 * k, y + 78 * k, hintP);
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

        // MARK: Lyrics

        private static final float GAP = 118;   // between lines, at 1920 px wide
        private static final float SIZE = 80;   // the line being sung; the others are scaled down from it

        /**
         * Synced lyrics as a list that glides: every line has its place and the list scrolls smoothly to the one being
         * sung. Everything a line looks like (size, brightness, place) is a smooth function of its distance to the
         * middle, and the text is drawn at one size and scaled by the canvas, so nothing jumps or bounces as lines pass.
         */
        private void drawSynced(Canvas c, float dt) {
            List<Lyrics.Line> lines = lyrics.synced;
            long pos = position();
            int idx = -1;
            for (int i = 0; i < lines.size(); i++) { if (lines.get(i).at <= pos) idx = i; else break; }
            float target = Math.max(0, idx);
            if (scroll < 0) scroll = target;
            // Exponential glide: no overshoot, so no bounce at the end.
            scroll += (target - scroll) * (1 - (float) Math.exp(-dt * 4.5f));
            if (Math.abs(target - scroll) < 0.0005f) scroll = target;
            long at = idx >= 0 ? lines.get(idx).at : 0;
            long next = idx + 1 < lines.size() ? lines.get(idx + 1).at : at + 5000;
            float p = idx < 0 ? 0 : Ui.clamp((pos - at) / (float) Math.max(400, next - at));
            float cy = h * 0.56f;
            int from = Math.max(0, (int) Math.floor(scroll) - 3), to = Math.min(lines.size() - 1, (int) Math.ceil(scroll) + 4);
            for (int i = from; i <= to; i++) {
                float d = i - scroll;
                float alpha = alphaAt(d);
                if (alpha <= 0.01f) continue;
                drawLine(c, orNote(lines.get(i).text), cy + d * GAP * k, scaleAt(d), alpha,
                        i == idx ? p : i < idx ? 1 : 0);
            }
        }

        /** 1 in the middle, smaller further away (smooth, the same both ways). */
        private float scaleAt(float d) {
            float near = Ui.clamp(1 - Math.abs(d));
            return 0.66f + 0.34f * near * near * (3 - 2 * near);
        }

        /** Brightest in the middle, fading with distance; lines already sung fade a little more, smoothly. */
        private float alphaAt(float d) {
            float dist = Math.abs(d), near = Ui.clamp(1 - dist);
            float a = dist <= 1 ? 0.45f + 0.55f * near * near * (3 - 2 * near) : Math.max(0, 0.45f - (dist - 1) * 0.14f);
            return a * (1 - 0.3f * Ui.clamp(-d));
        }

        /**
         * One line, centered at y, drawn at SIZE and scaled (shrunk further to fit); `filled` (0..1) of it in full white
         * over a dimmer copy. Past lines are fully filled and future ones empty, so a line never changes style.
         */
        private void drawLine(Canvas c, String s, float y, float scale, float alpha, float filled) {
            lineP.setTextSize(SIZE * k);
            float tw = lineP.measureText(s), max = 1720 * k;
            float fit = tw * scale > max ? max / tw : scale;
            float base = y + SIZE * k * 0.35f, left = w / 2 - tw / 2;
            c.save();
            c.scale(fit, fit, w / 2, y);
            lineP.setAlpha(Math.round(255 * 0.42f * alpha));
            c.drawText(s, w / 2, base, lineP);
            if (filled > 0) {
                c.clipRect(left, base - SIZE * k * 1.2f, left + tw * filled, base + SIZE * k * 0.5f);
                lineP.setAlpha(Math.round(255 * alpha));
                c.drawText(s, w / 2, base, lineP);
            }
            c.restore();
        }

        /** Not synced: the plain lyrics, gliding with the song's progress. */
        private void drawPlain(Canvas c, float dt) {
            List<String> lines = lyrics.plain;
            MediaMetadata md = controller == null ? null : controller.getMetadata();
            long duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            float target = duration <= 0 ? 0 : Ui.clamp(position() / (float) duration) * Math.max(0, lines.size() - 1);
            if (scroll < 0) scroll = target;
            scroll += (target - scroll) * (1 - (float) Math.exp(-dt * 3f));
            c.drawText(Ui.tr("Letra sem sincronizar", "Lyrics not synced", "Letra sin sincronizar"), w / 2, h * 0.26f, cmdP);
            for (int i = Math.max(0, (int) scroll - 3); i < lines.size() && i <= scroll + 4; i++) {
                float d = i - scroll;
                float alpha = alphaAt(d);
                if (alpha > 0.01f) drawLine(c, lines.get(i), h * 0.56f + d * GAP * k, 0.66f + 0.2f * Ui.clamp(1 - Math.abs(d)), alpha, 1);
            }
        }

        // MARK: The song list

        private void drawResults(Canvas c, float t, float dt) {
            float x = 440 * k, right = w - 440 * k, top = 250 * k, rowH = 112 * k;
            cmdP.setTextAlign(Paint.Align.LEFT);
            c.drawText("“" + query + "”", x, top - 34 * k, cmdP);
            cmdP.setTextAlign(Paint.Align.CENTER);
            if (searching) {
                Ui.spinner(c, w / 2, h * 0.55f, 26 * k, t, spin);
                return;
            }
            if (results.isEmpty()) {
                drawState(c, searchError != null ? Ui.tr("Não deu para buscar no Spotify", "Couldn't search Spotify", "No se pudo buscar en Spotify")
                        : Ui.tr("Nada encontrado", "Nothing found", "No encontré nada"), searchError);
                return;
            }
            int visible = 6;
            float targetScroll = Math.max(0, Math.min(results.size() - visible, resultIndex - 2));
            listScroll += (targetScroll - listScroll) * (1 - (float) Math.exp(-dt * 10f));
            c.save();
            c.clipRect(0, top - 10 * k, w, top + visible * rowH + 10 * k);
            for (int i = 0; i < results.size(); i++) {
                float y = top + (i - listScroll) * rowH;
                if (y < top - rowH || y > top + visible * rowH) continue;
                SpotifyCatalog.Track tr = results.get(i);
                boolean focus = i == resultIndex;
                rect.set(x, y, right, y + rowH - 12 * k);
                if (focus) {
                    fill.setColor(0xF2FFFFFF);
                    c.drawRoundRect(rect, 24 * k, 24 * k, fill);
                } else {
                    fill.setColor(0x24FFFFFF);
                    c.drawRoundRect(rect, 24 * k, 24 * k, fill);
                }
                float ts = rowH - 32 * k, tx = x + 10 * k, ty = y + 10 * k;
                Bitmap thumb = thumbs.get(tr.image);
                if (thumb != null) {
                    rect.set(tx, ty, tx + ts, ty + ts);
                    c.drawBitmap(thumb, null, rect, thumbPaint);
                }
                float textX = tx + ts + 26 * k;
                rowTitleP.setColor(focus ? 0xFF111111 : 0xFFFFFFFF);
                rowSubP.setColor(focus ? 0x99111111 : 0xB3FFFFFF);
                c.drawText(ellipsize(tr.name, rowTitleP, right - textX - 30 * k), textX, y + 46 * k, rowTitleP);
                long sec = tr.durationMs / 1000;
                String sub = tr.artists + "  ·  " + sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
                c.drawText(ellipsize(sub, rowSubP, right - textX - 30 * k), textX, y + 86 * k, rowSubP);
            }
            c.restore();
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
