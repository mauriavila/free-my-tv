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
    private static final Map<String, Lyrics> CACHE = new HashMap<>();   // this process's songs with synced lyrics

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
    private String album = "";

    // No synced lyrics: after a short countdown Spotify opens (its own lyrics, from Musixmatch), and the karaoke comes
    // back by itself as soon as a song with synced lyrics starts. OK during the countdown stays.
    private static final long AUTO_MS = 4000;
    private long autoAt;            // uptime when Spotify opens; 0 = no countdown
    private boolean awayForLyrics;  // in Spotify for its lyrics: come back on the next song we have synced
    private final Runnable autoOpen = this::openForLyrics;

    private void openForLyrics() {
        autoAt = 0;
        holdFrontUntil = 0;
        if (!resumed || syncing || results != null || search.getVisibility() == View.VISIBLE || hasSynced()) return;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awayForLyrics = true;
            AppLog.w("karaoke: no synced lyrics, over to Spotify's");
        } catch (RuntimeException e) {
            AppLog.w("karaoke: couldn't open Spotify: " + e.getMessage());
        }
    }
    private long durationMs;

    // Sync mode (☰ on songs with lyrics but no times): the song starts over and OK marks when each line begins.
    private boolean syncing;
    private final List<String> syncText = new ArrayList<>();
    private final List<Lyrics.Line> syncDone = new ArrayList<>();
    private static final long REACTION_MS = 180;   // people press a little after the line starts

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
        Lights.partyStart(this);   // the room's lights too, until the karaoke closes
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        AppLog.w("karaoke: in front");
        awayForLyrics = false;
        if (state == NO_ACCESS) { disconnect(); connect(); } else refresh();
        beat.start();
        view.start();
    }

    @Override protected void onPause() {
        resumed = false;
        cancelAuto();
        if (!isFinishing() && SystemClock.uptimeMillis() < holdFrontUntil) {   // Spotify woke up with its screen
            AppLog.w("karaoke: Spotify came to the front while waking up, taking it back");
            bringToFront(900);
        }
        if (isFinishing()) Lights.partyStop(this);
        beat.stop();
        view.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        AppLog.w("karaoke: closed");
        main.removeCallbacksAndMessages(null);   // nothing left to run on a closed screen
        Lights.partyStop(this);
        if (browser != null) browser.disconnect();
        disconnect();
        io.shutdownNow();
        super.onDestroy();
    }

    // MARK: - What's playing

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { onMetadata(metadata); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { tryPending(); view.invalidate(); }
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
        tryPending();
        if (same) return;
        if (controller == null) setWaiting(); else onMetadata(controller.getMetadata());
    }

    /** The song waiting for Spotify, as soon as Spotify's session can take it. */
    private void tryPending() {
        if (controller == null || pendingUri == null || !canPlayUri()) return;
        String uri = pendingUri;
        pendingUri = null;
        controller.getTransportControls().playFromUri(Uri.parse(uri), new Bundle());
        AppLog.w("karaoke: played the waiting song once Spotify was up");
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
        KaraokeActivity.this.album = album;
        durationMs = duration;
        if (!same || (art != null && art != view.art)) view.setSong(art);
        if (art == null) {
            final String uri = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI);
            if (uri.startsWith("http")) run(() -> {
                Bitmap b = download(uri, 2);
                if (b != null) main.post(() -> { if (key.equals(trackKey)) view.setSong(b); });
            });
        }
        if (same) return;
        if (syncing) { syncing = false; toast(Ui.tr("A música mudou: sincronização cancelada", "The song changed: sync cancelled", "Cambió la canción: sincronización cancelada")); }
        trackKey = key;
        offsetMs = 0;
        lyrics = null;
        comeBack();
        if (CACHE.containsKey(key)) {
            show(CACHE.get(key));
            return;
        }
        state = LOADING;
        findLyrics(key, a, t, album, duration, true);
    }

    /**
     * Looks for the lyrics in the background. Only synced (or instrumental) results are remembered: a song that came
     * back without times (a source slow or down for a moment) is looked up again next time, and once more 15 s later
     * right now, switching to the synced lyrics if they turn up.
     */
    private void findLyrics(final String key, final String a, final String t, final String album, final long duration,
                            final boolean retry) {
        run(() -> {
            final Lyrics l = Lyrics.find(getFilesDir(), a, t, album, duration);
            final boolean good = l != null && (l.instrumental || !l.synced.isEmpty());
            AppLog.w("lyrics: " + t + " → " + (l == null ? "none" : l.source + (l.synced.isEmpty() ? " (no times)" : " (synced)")));
            main.post(() -> {
                if (good) CACHE.put(key, l);
                if (!key.equals(trackKey)) return;
                if (good || retry || lyrics == null) show(l);
                if (!good && retry) {
                    AppLog.w("lyrics: no times for " + t + ", once more in 15 s");
                    main.postDelayed(() -> { if (key.equals(trackKey) && !hasSynced()) findLyrics(key, a, t, album, duration, false); }, 15_000);
                }
            });
        });
    }

    private void show(Lyrics l) {
        lyrics = l;
        state = l == null || !l.any() ? (l != null && l.instrumental ? INSTRUMENTAL : NO_LYRICS) : READY;
        view.lyricsChanged();
        cancelAuto();
        if (hasSynced() || state == INSTRUMENTAL) {
            if (awayForLyrics && !resumed) {
                awayForLyrics = false;
                bringToFront(300);
            }
        } else if (resumed && !syncing && controller != null) {
            autoAt = SystemClock.uptimeMillis() + AUTO_MS;
            main.postDelayed(autoOpen, AUTO_MS);
        }
    }

    private boolean hasSynced() {
        return lyrics != null && !lyrics.synced.isEmpty();
    }

    private void cancelAuto() {
        main.removeCallbacks(autoOpen);
        autoAt = 0;
    }

    // MARK: - Sync with the remote

    private boolean canSync() {
        return state == READY && lyrics != null && lyrics.synced.isEmpty() && !lyrics.plain.isEmpty() && controller != null;
    }

    private void startSync() {
        syncText.clear();
        syncText.addAll(lyrics.plain);
        syncDone.clear();
        offsetMs = 0;
        syncing = true;
        MediaController.TransportControls tc = controller.getTransportControls();
        tc.seekTo(0);
        tc.play();
        view.lyricsChanged();
    }

    /** OK: the next line starts now. */
    private void markLine() {
        long at = Math.max(0, position() - REACTION_MS);
        syncDone.add(new Lyrics.Line(at, syncText.get(syncDone.size())));
        if (syncDone.size() == syncText.size()) finishSync();
    }

    private void finishSync() {
        syncing = false;
        final List<Lyrics.Line> lines = new ArrayList<>(syncDone);
        final Lyrics l = Lyrics.synced(lines, syncText);
        final String key = trackKey, a = artist, t = title, al = album;
        final long d = durationMs;
        CACHE.put(key, l);
        show(l);
        run(() -> {
            try {
                Lyrics.saveLocal(getFilesDir(), a, t, lines);
            } catch (Exception e) {
                AppLog.w("sync: couldn't save: " + e.getMessage());
            }
            if (al.isEmpty() || d <= 0) {
                main.post(() -> toast(Ui.tr("Letra salva nesta TV", "Lyrics saved on this TV", "Letra guardada en esta tele")));
                return;
            }
            main.post(() -> toast(Ui.tr("Letra salva · enviando ao LRCLIB…", "Lyrics saved · sending to LRCLIB…", "Letra guardada · subiendo a LRCLIB…")));
            String error = Lyrics.publish(t, a, al, d, lines);
            AppLog.w("sync: LRCLIB " + (error == null ? "published" : error));
            main.post(() -> toast(error == null ? Ui.tr("Publicada no LRCLIB ✓", "Published on LRCLIB ✓", "Publicada en LRCLIB ✓")
                    : Ui.tr("Não deu para enviar ao LRCLIB (fica salva nesta TV)", "Couldn't send it to LRCLIB (it stays on this TV)",
                    "No se pudo subir a LRCLIB (queda guardada en esta tele)")));
        });
    }

    /** Background work, unless the screen is already closed (its executor is shut down then). */
    private void run(Runnable job) {
        if (io.isShutdown()) return;
        try {
            io.execute(job);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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
        run(() -> {
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
            run(() -> {
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

    /** Spotify plays it in the background; if Spotify isn't running yet, wakeSpotify starts it behind the karaoke. */
    private void play(SpotifyCatalog.Track t) {
        results = null;
        if (canPlayUri()) {
            controller.getTransportControls().playFromUri(Uri.parse(t.uri()), new Bundle());
            Toast.makeText(this, Ui.tr("Tocando: ", "Playing: ", "Poniendo: ") + t.name, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingUri = t.uri();
        Toast.makeText(this, Ui.tr("Acordando o Spotify…", "Waking Spotify up…", "Despertando Spotify…"), Toast.LENGTH_SHORT).show();
        wakeSpotify(t.uri());
    }

    private android.media.browse.MediaBrowser browser;
    /** Until when (uptime) Spotify waking up may jump in front with its own screen: the karaoke takes the front back. */
    private long holdFrontUntil;

    /**
     * Spotify isn't playing (so it has no session to take the song): wake it up behind the karaoke. First its media
     * browser (if Spotify accepts us, its session plays the song straight away), then a Play key to Spotify itself
     * and then to the last player, both of which start it in the background; tryPending plays the song as soon as its
     * session shows up. Only if none of that works in 8 s does Spotify open on the song (and the karaoke come back).
     */
    private void wakeSpotify(final String uri) {
        holdFrontUntil = SystemClock.uptimeMillis() + 12_000;
        ComponentName service = spotifyService();
        if (service != null) {
            try {
                if (browser != null) browser.disconnect();
                browser = new android.media.browse.MediaBrowser(this, service, new android.media.browse.MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        if (!uri.equals(pendingUri) || browser == null) return;
                        pendingUri = null;
                        new MediaController(KaraokeActivity.this, browser.getSessionToken()).getTransportControls()
                                .playFromUri(Uri.parse(uri), new Bundle());
                        AppLog.w("karaoke: played through Spotify's media browser");
                        main.postDelayed(KaraokeActivity.this::refresh, 1500);
                    }

                    @Override public void onConnectionFailed() {
                        AppLog.w("karaoke: Spotify's media browser turned us down");
                    }
                }, null);
                browser.connect();
            } catch (RuntimeException e) {
                AppLog.w("karaoke: media browser: " + e.getMessage());
            }
        }
        main.postDelayed(() -> { if (uri.equals(pendingUri)) playKeyToSpotify(); }, 1200);
        main.postDelayed(() -> {
            if (!uri.equals(pendingUri)) return;
            AppLog.w("karaoke: Play key to the last player");
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
        }, 4000);
        main.postDelayed(() -> {
            if (!uri.equals(pendingUri)) return;
            pendingUri = null;
            holdFrontUntil = 0;   // opened on purpose: back when the song starts
            AppLog.w("karaoke: Spotify didn't wake up behind us: opening it on the song");
            if (!openSpotify(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(SPOTIFY))) {
                openSpotify(getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY));
            }
        }, 8000);
    }

    /** Spotify's media browser service, if it has one. */
    private ComponentName spotifyService() {
        Intent i = new Intent("android.media.browse.MediaBrowserService").setPackage(SPOTIFY);
        List<android.content.pm.ResolveInfo> found = getPackageManager().queryIntentServices(i, 0);
        if (found == null || found.isEmpty()) return null;
        return new ComponentName(SPOTIFY, found.get(0).serviceInfo.name);
    }

    /** A Play key straight to Spotify's media button receivers, which start its player without its screen. */
    private void playKeyToSpotify() {
        Intent probe = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(SPOTIFY);
        List<android.content.pm.ResolveInfo> receivers = getPackageManager().queryBroadcastReceivers(probe, 0);
        AppLog.w("karaoke: Play key to Spotify (" + (receivers == null ? 0 : receivers.size()) + " receivers)");
        if (receivers == null) return;
        for (android.content.pm.ResolveInfo r : receivers) {
            ComponentName cn = new ComponentName(SPOTIFY, r.activityInfo.name);
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)));
            }
        }
    }

    /** Spotify in front; the karaoke returns as soon as a new song starts there. */
    private boolean openSpotify(Intent i) {
        if (i == null) return false;
        holdFrontUntil = 0;   // on purpose: Spotify may stay in front
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
        bringToFront(600);   // a moment for Spotify to settle
    }

    private void bringToFront(long delayMs) {
        bringToFront(delayMs, 3);
    }

    /**
     * Back in front: the whole task moved to the front (startActivity from behind isn't always honored), then checked
     * 1.5 s later and tried again, up to `tries` times.
     */
    private void bringToFront(long delayMs, final int tries) {
        main.postDelayed(() -> {
            if (resumed || isFinishing() || isDestroyed()) return;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                am.moveTaskToFront(getTaskId(), 0);
            } catch (RuntimeException e) {
                AppLog.w("karaoke: moveTaskToFront: " + e.getMessage());
            }
            try {
                startActivity(new Intent(this, KaraokeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (RuntimeException e) {
                AppLog.w("karaoke: couldn't come back: " + e.getMessage());
            }
            AppLog.w("karaoke: back to the front (" + tries + ")");
            if (tries > 1) bringToFront(1500, tries - 1);
        }, delayMs);
    }

    // MARK: - Remote

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        if (autoAt > 0 && down) {   // any key stops the countdown; OK just stays
            cancelAuto();
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) return true;
        }
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
        if (syncing) {
            if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
            if (!first) return true;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    markLine();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:   // undo the last mark
                    if (!syncDone.isEmpty()) syncDone.remove(syncDone.size() - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:  // done early (the rest has no lines left to mark, or an outro)
                    if (!syncDone.isEmpty()) { syncText.subList(syncDone.size(), syncText.size()).clear(); finishSync(); }
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (controller != null) { if (playing()) controller.getTransportControls().pause(); else controller.getTransportControls().play(); }
                    break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    syncing = false;
                    view.lyricsChanged();
                    break;
                default:
                    break;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU && canSync()) {
            if (first) startSync();
            return true;
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

    private static final int[] LASER_COLORS = {0xFF39FF14, 0xFFFF1F5A, 0xFF00E5FF, 0xFFD500F9};   // green, red, cyan, violet

    // Neon tube colors for the lyrics: the glow, and the lighter core of the letters (each line its own).
    private static final int[] NEON = {0xFFFF2BD6, 0xFF2BD9FF, 0xFFFFE14D, 0xFF39FF6A, 0xFFFF8A1F, 0xFFB06BFF};
    private static final int[] NEON_CORE = new int[NEON.length];
    static {
        for (int i = 0; i < NEON.length; i++) {
            int c = NEON[i];
            NEON_CORE[i] = Color.rgb(Color.red(c) + (255 - Color.red(c)) * 3 / 5, Color.green(c) + (255 - Color.green(c)) * 3 / 5,
                    Color.blue(c) + (255 - Color.blue(c)) * 3 / 5);
        }
    }

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

        // Party, like a club: out-of-focus colored lights (bokeh), moving heads sweeping from the ceiling, a laser fan,
        // a disco ball's dots crossing the walls and drifting sparkles; with BeatPulse they swell on the beat. Soft
        // changes only: no strobe or full-screen flashes.
        private static final int BEAMS = 6, SPARKS = 42, HUES = 36, BOKEH = 14, LASERS = 9, DOTS = 72;
        private Bitmap disc;   // the soft disc every bokeh light is drawn from
        private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint laserGlow = new Paint(Paint.ANTI_ALIAS_FLAG), laserCore = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] bx = new float[BOKEH], by = new float[BOKEH], br = new float[BOKEH], bvx = new float[BOKEH],
                bvy = new float[BOKEH], bph = new float[BOKEH];
        private final int[] bhue = new int[BOKEH];
        private final float[] dlon = new float[DOTS], dcos = new float[DOTS], dsin = new float[DOTS], dsize = new float[DOTS];
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
        private Paint neonP;   // the sung part of a line: a glowing tube
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
            neonP = new Paint(lineP);
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
            if (disc == null) {
                disc = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(new RadialGradient(64, 64, 64, new int[] {0xFFFFFFFF, 0xD9FFFFFF, 0xA6FFFFFF, 0x00FFFFFF},
                        new float[] {0, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
                new Canvas(disc).drawCircle(64, 64, 64, p);
            }
            spritePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            for (int i = 0; i < BOKEH; i++) {
                bx[i] = r.nextFloat() * w;
                by[i] = r.nextFloat() * h;
                br[i] = (50 + r.nextFloat() * 150) * k;
                bvx[i] = (r.nextFloat() - 0.5f) * 16 * k;
                bvy[i] = (r.nextFloat() - 0.5f) * 12 * k;
                bph[i] = r.nextFloat() * 6.28f;
                bhue[i] = r.nextInt(HUES);
            }
            laserGlow.setStyle(Paint.Style.STROKE);
            laserGlow.setStrokeWidth(12 * k);
            laserGlow.setStrokeCap(Paint.Cap.ROUND);
            laserCore.setStyle(Paint.Style.STROKE);
            laserCore.setStrokeWidth(2.5f * k);
            laserGlow.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            laserCore.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            for (int i = 0; i < DOTS; i++) {
                dlon[i] = r.nextFloat() * 6.2832f;
                float lat = (r.nextFloat() - 0.5f) * 2.4f;
                dcos[i] = (float) Math.cos(lat);
                dsin[i] = (float) Math.sin(lat);
                dsize[i] = 0.7f + r.nextFloat() * 0.8f;
            }
            dotPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
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
                    if (syncing) drawSync(c, dt);
                    else if (lyrics != null && !lyrics.synced.isEmpty()) drawSynced(c, dt); else drawPlain(c, dt);
                    if (lyrics != null) c.drawText(Ui.tr("Letra: ", "Lyrics: ", "Letra: ") + lyrics.source, w - 80 * k, h - 50 * k, sourceP);
            }
            drawOffset(c);
            if (autoAt > 0) {
                long left = Math.max(1, (autoAt - now + 999) / 1000);
                c.drawText(Ui.tr("Sem letra sincronizada: abro o Spotify em ", "No synced lyrics: opening Spotify in ",
                        "Sin letra sincronizada: abro Spotify en ") + left + Ui.tr(" · OK fico aqui", " · OK stays here", " · OK me quedo"),
                        w / 2, h - 120 * k, cmdP);
            }
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
                blob.setAlpha(Math.round(255 * (0.3f + 0.25f * fadeIn + 0.1f * pulse)));
                c.drawRect(0, 0, w, h, blob);
            }
            drawBokeh(c, t, dt);
            drawBeams(c, t);
            drawLasers(c, t);
            drawDots(c, t);
            drawSparks(c, t, dt);
            c.drawRect(0, 0, w, h, veil);
        }

        /** Big out-of-focus lights in party colors, drifting and twinkling, a little bigger on the beat. */
        private void drawBokeh(Canvas c, float t, float dt) {
            for (int i = 0; i < BOKEH; i++) {
                float r = br[i] * (1 + 0.1f * pulse);
                bx[i] += bvx[i] * dt;
                by[i] += bvy[i] * dt;
                if (bx[i] < -r) bx[i] += w + 2 * r; else if (bx[i] > w + r) bx[i] -= w + 2 * r;
                if (by[i] < -r) by[i] += h + 2 * r; else if (by[i] > h + r) by[i] -= h + 2 * r;
                float tw = 0.5f + 0.5f * (float) Math.sin(t * 0.8f + bph[i]);
                spritePaint.setColorFilter(tints[(bhue[i] + (int) (t * 2)) % HUES]);
                spritePaint.setAlpha(Math.round(255 * Ui.clamp(0.1f + 0.16f * tw + 0.2f * pulse)));
                rect.set(bx[i] - r, by[i] - r, bx[i] + r, by[i] + r);
                c.drawBitmap(disc, null, rect, spritePaint);
            }
        }

        /** A fan of laser lines from above; every 8 s it moves, changes color and pattern; brighter on the beat. */
        private void drawLasers(Canvas c, float t) {
            int scene = (int) (t / 8);
            float st = t - scene * 8;
            float fade = Ui.clamp(st / 0.8f) * Ui.clamp((8 - st) / 0.8f);
            int where = scene % 3;
            float ox = w * (where == 0 ? 0.5f : where == 1 ? 0.1f : 0.9f), oy = -h * 0.03f;
            float center = where == 0 ? 90 : where == 1 ? 58 : 122;   // degrees, 90 = straight down
            float spread = 8 + 5 * (float) Math.sin(t * 0.9f);
            float sway = 20 * (float) Math.sin(t * 0.55f + scene);
            float a = fade * (0.18f + 0.45f * pulse);
            if (a < 0.01f) return;
            int color = LASER_COLORS[scene % LASER_COLORS.length];
            float len = w * 1.6f;
            laserGlow.setColor(color);
            laserGlow.setAlpha(Math.round(255 * a * 0.3f));
            laserCore.setColor(color);
            laserCore.setAlpha(Math.round(255 * a));
            for (int l = 0; l < LASERS; l++) {
                double ang = Math.toRadians(center + sway + (l - (LASERS - 1) / 2f) * spread);
                float ex = ox + (float) Math.cos(ang) * len, ey = oy + (float) Math.sin(ang) * len;
                c.drawLine(ox, oy, ex, ey, laserGlow);
                c.drawLine(ox, oy, ex, ey, laserCore);
            }
        }

        /** A disco ball's reflections: little lights turning across the walls, the ones facing us brighter. */
        private void drawDots(Canvas c, float t) {
            float rot = t * 0.32f, radius = w * 0.62f, cy = h * 0.42f;
            for (int i = 0; i < DOTS; i++) {
                float lon = dlon[i] + rot;
                float face = (float) Math.cos(lon);
                if (face < 0.1f) continue;
                float x = w / 2 + radius * (float) Math.sin(lon) * dcos[i];
                float y = cy + radius * 0.55f * dsin[i];
                dotPaint.setColor(i % 4 == 0 ? hueColors[(i * 7 + (int) (t * 3)) % HUES] : 0xFFFFFFFF);
                dotPaint.setAlpha(Math.round(255 * Ui.clamp(face * (0.3f + 0.45f * pulse))));
                c.drawCircle(x, y, (2.5f + 3 * face) * dsize[i] * k, dotPaint);
            }
        }

        /** Moving heads: four from the ceiling and two from the floor corners, sweeping, colors drifting. */
        private void drawBeams(Canvas c, float t) {
            for (int i = 0; i < BEAMS; i++) {
                float ox, oy, base, swing;
                if (i < 4) {
                    ox = w * (0.14f + 0.24f * i);
                    oy = -h * 0.05f;
                    base = 90 + (i - 1.5f) * 12;   // fanned out downwards
                    swing = 30;
                } else {
                    ox = i == 4 ? w * 0.02f : w * 0.98f;
                    oy = h * 1.04f;
                    base = i == 4 ? -60 : -120;
                    swing = 18;
                }
                float angle = base + swing * (float) Math.sin(t * (0.35f + 0.07f * i) + i * 1.7f);
                int hue = (int) ((t * 6 + i * 6) % HUES);
                beamPaint.setColorFilter(tints[(hue + HUES) % HUES]);
                beamPaint.setAlpha(Math.round(255 * (0.13f + 0.2f * pulse)));
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
                        i == idx ? p : i < idx ? 1 : 0, i);
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
         * One line, centered at y, drawn at SIZE and scaled (shrunk further to fit); `filled` (0..1) of it lit like a
         * neon tube (line `n`'s color) over a dimmer white copy. Past lines are fully lit and future ones not, so a line
         * never changes style. The glow keeps one radius, so the TV blurs each line once and reuses it.
         */
        private void drawLine(Canvas c, String s, float y, float scale, float alpha, float filled, int n) {
            lineP.setTextSize(SIZE * k);
            float tw = lineP.measureText(s), max = 1720 * k;
            float fit = tw * scale > max ? max / tw : scale;
            float base = y + SIZE * k * 0.35f, left = w / 2 - tw / 2;
            c.save();
            c.scale(fit, fit, w / 2, y);
            lineP.setAlpha(Math.round(255 * 0.42f * alpha));
            c.drawText(s, w / 2, base, lineP);
            if (filled > 0) {
                c.clipRect(left - 40 * k, base - SIZE * k * 1.6f, left + tw * filled, base + SIZE * k * 0.9f);
                int i = n % NEON.length;
                neonP.setTextSize(SIZE * k);
                neonP.setColor(NEON_CORE[i]);
                neonP.setAlpha(Math.round(255 * alpha));
                neonP.setShadowLayer(22 * k, 0, 0, NEON[i]);
                c.drawText(s, w / 2, base, neonP);
            }
            c.restore();
        }

        /** Sync mode: the line to mark lit in the middle, the marked ones going up, the hint and the count above. */
        private void drawSync(Canvas c, float dt) {
            int next = syncDone.size();
            if (scroll < 0) scroll = next;
            scroll += (next - scroll) * (1 - (float) Math.exp(-dt * 9f));
            c.drawText(Ui.tr("OK quando começar cada linha · ◀ desfazer · ▶ terminar · Voltar cancelar",
                    "OK as each line starts · ◀ undo · ▶ finish · Back cancel",
                    "OK cuando empieza cada línea · ◀ deshacer · ▶ terminar · Atrás cancelar"), w / 2, h * 0.26f, cmdP);
            c.drawText(next + " / " + syncText.size(), w / 2, h * 0.26f + 46 * k, cmdP);
            float cy = h * 0.56f;
            for (int i = Math.max(0, next - 3); i < syncText.size() && i <= next + 4; i++) {
                float d = i - scroll;
                float alpha = alphaAt(d);
                if (alpha > 0.01f) drawLine(c, syncText.get(i), cy + d * GAP * k, scaleAt(d), alpha, i <= next ? 1 : 0, i);
            }
        }

        /** Not synced: the plain lyrics, gliding with the song's progress. */
        private void drawPlain(Canvas c, float dt) {
            List<String> lines = lyrics.plain;
            MediaMetadata md = controller == null ? null : controller.getMetadata();
            long duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            float target = duration <= 0 ? 0 : Ui.clamp(position() / (float) duration) * Math.max(0, lines.size() - 1);
            if (scroll < 0) scroll = target;
            scroll += (target - scroll) * (1 - (float) Math.exp(-dt * 3f));
            c.drawText(Ui.tr("Letra sem sincronizar · ☰ sincronizar com o controle", "Lyrics not synced · ☰ sync it with the remote",
                    "Letra sin sincronizar · ☰ sincronizala con el control"), w / 2, h * 0.26f, cmdP);
            for (int i = Math.max(0, (int) scroll - 3); i < lines.size() && i <= scroll + 4; i++) {
                float d = i - scroll;
                float alpha = alphaAt(d);
                if (alpha > 0.01f) drawLine(c, lines.get(i), h * 0.56f + d * GAP * k, 0.66f + 0.2f * Ui.clamp(1 - Math.abs(d)), alpha, 1, i);
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
