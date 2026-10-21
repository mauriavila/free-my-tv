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

## What changes on Fire TV

- The app's icon opens the home screen; Settings is the round button at the top right.
- The top buttons are All Apps and **Fire TV Home** (Amazon's home), instead of Live TV and HDMI inputs.
- Prime Video, YouTube, Paramount+ (Latin America) and Crunchyroll use their Fire TV packages on the shelf and in the picks.
- No Free My TV mode, debloat, ADB or screensaver switch: `HomeMode` does nothing on Amazon devices (`Ui.FIRE`).
