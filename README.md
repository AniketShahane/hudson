# Hudson — PATH train widget

A Material 3 Expressive widget (and small app) for PATH departures, built for a Galaxy S25 Ultra
on One UI 9 (Android 17). Personal, sideloaded.

## What it does

- **Widget** (Glance, painted at the exact size the launcher gives): one-row strip, square, wide poster, and later trains listed underneath when it is tall.
  The next train is a big countdown drawn by a system `Chronometer`, so it ticks every second
  without waking the app; later trains are clock times, which never go stale.
- **Colors**: Settings → Colors switches the app and widget between the PATH line colors and your
  phone's Material You colors (from the wallpaper, light and dark); the line then shows as an edge bar.
- **Favorites**: default *Exchange Pl → NY* and *WTC → NJ*. Add any station, direction and
  optional terminals in the app. Tap the widget to step through them (each widget
  remembers its own); tap the station name, top-left by the small arrow, to open the app on that station.
- **Commute Live Update**: start from the widget's live button or the app. Up to three trains
  as countdowns (MetricStyle on Android 17), promoted to a Live Update, which One UI shows in the
  Now Bar on the lock screen. Ends by itself after 30 min (adjustable), on Stop, or when dismissed.
- Dynamic colour from the wallpaper throughout; PATH line colours as small dots.

## Data

`https://www.panynj.gov/bin/portauthority/ridepath.json`, the feed behind PATH's own schedule
pages: per station, per direction (ToNY/ToNJ), each train's `secondsToArrival` relative to its
`lastUpdated` stamp. Arrival = stamp + seconds. Unofficial, undocumented: if PANYNJ changes it,
`data/feed/RidePathFeed.kt` is the one file to update.

## Refresh

- Widgets: WorkManager every 15 min (its minimum), plus a one-off fetch ~20 s after the first
  listed train leaves, plus on every tap.
- Live Update: every ~45 s while running (no foreground service).
- App: every 30 s while open.

## Lock screen on One UI 9

One UI only lists Samsung widgets on the lock screen. Options:
1. **Live Update (recommended)** — appears in the Now Bar automatically. If it stays in the shade,
   open the app → Commute Live Update → More → *Allow Live Updates*.
2. **Widget via Good Lock → LockStar** → edit lock screen → Add widget → *PATH trains*.

## Build

CI only (GitHub Actions): unit tests + a Robolectric screenshot walkthrough (published to the
`ci-screenshots` branch), then a signed release APK (`hudson-apk` artifact). Install the release
APK. `tools/check-local.sh` runs the pure-Kotlin tests in seconds without the Android SDK.

The keystore in `app/` is a personal sideload key — never use it for a Play Store release.
