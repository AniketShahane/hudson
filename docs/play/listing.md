# Google Play listing

Copy these into Play Console → Grow → Store presence → Main store listing. Limits are Google's:
title 30 characters, short description 80, full description 4000.

## App name (29 characters)

    Hudson: unofficial PATH times

## Short description (74 characters)

    Unofficial PATH train times, service alerts and a fast home-screen widget.

## Full description

    Hudson shows the next PATH trains at a glance.

    MY TRAINS
    Save the stations and directions you actually use. The next train is one giant number you can read from across the room, with the trains after it underneath.

    EVERY STATION
    Browse all 13 PATH stations and see the next train each way, or open the line map and see the minutes to the next train at every station at once.

    ALERTS
    PATH's own service alerts: delays, elevator outages and advisories, with what has ended earlier in the day. An alert that affects your station shows right above its trains.

    HOME-SCREEN WIDGET
    A bold poster-style widget in any size. Tap to switch between your stations instantly. It can follow your phone's colors.

    LOCK SCREEN TRACKING
    Start tracking before you leave and follow your train on the lock screen as a live update.

    NEAREST STATION
    Optionally find the PATH station closest to you. Your approximate location is used once, on your phone, and is never stored or sent anywhere.

    BUILT TO BE LIGHT
    No account, no ads, no tracking. Nothing runs in the background unless you have a widget on your home screen or are tracking a train.

    IMPORTANT
    Hudson is an independent app. It does not represent, and is not affiliated with or endorsed by, the Port Authority of New York and New Jersey or any government entity. PATH is a service of the Port Authority.

    Train times and alerts come from the Port Authority's public information at https://www.panynj.gov/path/en/index.html and can be late or wrong. Always check the signs in the station.

## Graphics (in docs/play/graphics)

| File | Where it goes |
|---|---|
| `icon_512.png` | App icon (512 × 512) |
| `feature_1024x500.png` | Feature graphic (1024 × 500) |
| `phone_1_my_trains.png` … `phone_5_alerts.png` | Phone screenshots (1440 × 2560, 9:16) |

The screenshots are produced by the app's own test run (`WalkthroughTest.storeScreenshots`) with sample
trains, plus PATH's real alerts from 25–26 Nov 2026 and one made-up delay so a service alert is visible.
Regenerate them after any design change: `./gradlew :app:testDebugUnitTest`, then copy from
`app/build/outputs/store/`.

## Category and details

- Category: Maps & Navigation
- Tags: public transit
- Contact email: your own (it is shown publicly on the listing)
- Privacy policy URL: `https://aniketshahane.github.io/hudson/privacy.html` (after turning on GitHub Pages, see PUBLISHING.md)

## App content declarations (Play Console → Policy → App content)

| Question | Answer |
|---|---|
| Privacy policy | The URL above |
| Ads | No ads |
| App access | All functionality available without special access |
| Content rating | Complete the questionnaire: a utility app with no user content, violence or purchases |
| Target audience | 13 and over (general audience; not designed for children) |
| Data safety: does the app collect or share user data? | **No.** Nothing leaves the phone except requests for public train data, which carry no personal data. Approximate location is used only on the device and is not collected. |
| Government apps | The app shows information from a government agency but is **not** affiliated with one. Declare that, give `https://www.panynj.gov/path/en/index.html` as the source, and keep the disclaimer in the description. |
| News app | No |
| Financial features, health | None |

Permissions the app requests, and why, in case the review asks:

| Permission | Why |
|---|---|
| Internet, network state | Download PATH's train times and alerts |
| Notifications, promoted notifications | The lock-screen tracking card, only when the user starts it |
| Approximate location | "Find my nearest station", only on request, only while the Stations screen is open |
