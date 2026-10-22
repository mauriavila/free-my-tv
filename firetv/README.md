# Free My TV on Fire TV

Fire OS blocks other home screens (and, on Fire OS 6, the Home button and launch at boot for accessibility helpers),
so on a Fire TV Free My TV is an app: its icon opens the home screen, and nothing on the stick is switched or turned
off. Works on Fire OS 6 and newer (Android 7.1+).

## Install

```
adb connect <fire-tv-ip>:5555
adb install -r FreeMyTV-firetv.apk
```

`FreeMyTV-firetv.apk` comes from this fork's **firetv** release, built by `.github/workflows/firetv.yml` on every push
to the `firetv` branch. Settings (city, region, language) are in `firetv/firetv.properties`.

## TMDB key (the picks)

The build carries no key, since this repository is public. Put yours on the stick, in a text file with only the key:

```
adb push tmdb_key.txt /sdcard/Android/data/io.freemytv/files/tmdb_key.txt
```

Open Free My TV again and the picks appear within a minute.

## Opening it after waking up

`FireWake` opens Free My TV over Amazon's home when the stick wakes up or boots, only while Amazon's home is in front.
To know what's in front it needs usage access, once:

```
adb shell appops set io.freemytv GET_USAGE_STATS allow
```

The Home button still goes to Amazon's home: Fire OS 6 doesn't let apps take it.

## Karaoke

*Karaoke* in the drawer is a whole karaoke in one screen: ▲ searches Spotify's catalog and lists the songs with their
covers; OK plays one in Spotify's app, in the background (`playFromUri` on its media session), so you never leave.
The lyrics glide line by line, the one being sung filling up as it goes, over a moving background in the album's
colors. Lyrics: LRCLIB, then NetEase Cloud Music, then lyrics.ovh (text only). Songs queued from phones show up too.

Once:

```
adb shell settings put secure enabled_notification_listeners io.freemytv/io.freemytv.MediaListener
adb push spotify_key.txt /sdcard/Android/data/io.freemytv/files/spotify_key.txt
```

`spotify_key.txt` is one line, `CLIENT_ID:CLIENT_SECRET`, from your own free app at developer.spotify.com (Web API).
Without it, ▲ opens Spotify's own search instead, and the karaoke comes back when a song starts.

Remote: ▲ search, ◀ ▶ move the lyrics earlier or later, OK pause, ⏪ ⏩ previous or next song, ▼ Spotify, Back leaves.

Songs with lyrics but no times (only lyrics.ovh had them): ☰ syncs them with the remote. The song starts over and you
press OK as each line starts (◀ undoes the last one, ▶ finishes early, Back cancels). They're saved on the TV, so the
song is synced from then on, and published to LRCLIB (its small proof of work runs in the background), so every app
using LRCLIB gets them too.

With no synced lyrics at all, after a 4 s countdown (OK stays) Spotify opens, to sing with its own lyrics, and the
karaoke comes back by itself as soon as a song with synced lyrics starts.

The background is a club: out-of-focus colored lights, moving heads from the ceiling, a laser fan, a disco ball's
dots and sparkles; the lyrics light up like neon tubes as they're sung. It moves on its own; to make them swell on the beat (BeatPulse reads only the
sound's spectrum, through Android's Visualizer), once: `adb shell pm grant io.freemytv android.permission.RECORD_AUDIO`.

## The home screen

At the top, the weather (now, high and low, the next six hours) over photos of beautiful places around the world
(Bing's photos of the day, no key needed), a new one every 5 minutes with the place in the corner. Below, a smaller row
with videos for you (the new ones from the channels whose videos you liked, plus what's popular in your country in
the categories you like most; no Shorts); OK opens one in YouTube. Then the
shelf and the drawer. ↑ from the shelf goes to the videos, ↑ again to the corner buttons.

YouTube offers no way to read an account's recommendations or history, so the row is built from your likes (and your
most relevant subscriptions when there are few). Once:

1. console.cloud.google.com → a new project → APIs & Services → Library → **YouTube Data API v3** → Enable.
2. OAuth consent screen: External, any name, your email; then **Publish app** (in testing, Google signs you out
   every 7 days).
3. Credentials → Create credentials → OAuth client ID → **TVs and Limited Input devices** → copy the ID and secret.
4. `adb push youtube_key.txt /sdcard/Android/data/io.freemytv/files/youtube_key.txt` (one line, `CLIENT_ID:CLIENT_SECRET`).
5. Open Free My TV: the row shows a code; enter it at google.com/device on your phone and allow (Google warns the app
   isn't verified, since it's yours: Advanced → continue). The token stays in the app's private files.

## Smart lights (Tuya, LEDVANCE SMART+ WiFi, Smart Life)

The drawer gets **Lights** (on, off, movie mode, warm, cool, party) and **Movie** (low warm light in one press), and the
karaoke turns them into a party while it's open, then puts each bulb back exactly as it was.

Once: a free cloud project at platform.tuya.com (data center of your Smart Life account, e.g. Western America), your
Smart Life / LEDVANCE account linked under Devices › Link App Account, then the project's Access ID and Secret on the
stick, one line `ACCESS_ID:ACCESS_SECRET` (add `:eu` or another region if the project isn't in Western America):

```
adb push tuya_key.txt /sdcard/Android/data/io.freemytv/files/tuya_key.txt
```

Then open Free My TV again. Tuya's free plan allows about 26,000 calls a month, so the party is a color program each
bulb runs by itself (the screen follows the beat; the bulbs, their own rhythm): about a dozen calls per karaoke.

## What changes on Fire TV

- The app's icon opens the home screen; Settings is the round button at the top right.
- The top buttons are All Apps and **Fire TV Home** (Amazon's home), instead of Live TV and HDMI inputs.
- Prime Video, YouTube, Paramount+ (Latin America) and Apple TV use their Fire TV packages; Xuper TV is on the shelf.
- The drawer (↓ from the shelf) adds **Karaoke**, **Lights** and **Movie** (with a Tuya key), **VPN** (NordVPN's quick connect, `nordvpn://connect`) and **Speaker** (connects
  a paired Bluetooth speaker; with several, a list to pick from).
- No Free My TV mode, debloat, ADB or screensaver switch: `HomeMode` does nothing on Amazon devices (`Ui.FIRE`).
