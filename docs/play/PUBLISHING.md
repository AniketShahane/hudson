# Publishing Hudson on Google Play

Everything the app needs is in this repository. What only you can do is listed here, in order.
Facts about Google's rules were checked on 26 Nov 2026; they change, so re-read the linked pages.

## 0. Things to know first

- **A Play developer account** costs a one-time US$25: https://play.google.com/console/signup
- **New personal accounts must run a closed test first**: at least 12 testers opted in for 14 days in a row
  before Google lets you apply for production. Plan for that fortnight.
  https://support.google.com/googleplay/android-developer/answer/14151465
- **The name.** "PATH" is the Port Authority's trademark. The listing uses it only to describe what the app
  is for, says "unofficial" in the title, and carries a clear disclaimer. That is the usual approach, but it
  is not a guarantee: Google or the Port Authority can still object. If they do, the fallback is to drop
  "PATH" from the title and keep it in the description.
- **The sideloaded app and the Play app cannot update each other.** They are signed with different keys, so
  uninstall the sideloaded copy before installing from Play (your saved stations will need re-adding).

## 1. Create your upload key (once)

This is the key Google knows you by. It must never be committed; `.gitignore` already blocks it.
The key in `app/hudson.keystore` is public and is for sideloading only: the build refuses to make a
Play bundle with it.

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep the file and its passwords somewhere safe (a password manager). If you lose it, Google can reset
it, because Play App Signing holds the real signing key: https://developer.android.com/studio/publish/app-signing

## 2. Build the bundle

Either on your computer, with a `keystore.properties` file in the project root:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

```bash
./gradlew :app:bundlePlay
# -> app/build/outputs/bundle/play/app-play.aab
```

Or on GitHub: add four repository secrets (Settings → Secrets and variables → Actions) and every build
on `main` will also produce a `hudson-play-bundle` artifact:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | `base64 -w0 upload-keystore.jks` |
| `UPLOAD_STORE_PASSWORD` | the keystore password |
| `UPLOAD_KEY_ALIAS` | `upload` |
| `UPLOAD_KEY_PASSWORD` | the key password |

Before each new upload, raise `versionCode` in `app/build.gradle.kts` (Play rejects a repeat).

## 3. Publish the privacy policy

1. Put your contact email in `docs/privacy.html` (replace `[YOUR CONTACT EMAIL]`).
2. On GitHub: Settings → Pages → Deploy from a branch → `main`, folder `/docs`.
3. Check that https://aniketshahane.github.io/hudson/privacy.html opens. The app's Settings screen links to
   the same address; if you host it elsewhere, change `PRIVACY_URL` in `HomeScreen.kt` too.

## 4. Create the app in Play Console

1. Create app → name "Hudson: unofficial PATH times", App, Free.
2. Store listing: paste the text from `listing.md`; upload the graphics from `docs/play/graphics`.
3. App content: answer the declarations as in `listing.md` (privacy policy, no ads, data safety "no data
   collected", government-information declaration with the panynj.gov source).
4. Testing → Closed testing: create a track, upload `app-play.aab`, accept Play App Signing, add your
   testers' Google accounts, send them the opt-in link.
5. After 14 days with 12 testers: apply for production access, then promote the release.

## 5. What Google checks that this build already meets

| Requirement | Status |
|---|---|
| Target Android 16 (API 36) or higher for new apps | `targetSdk = 36` |
| Android App Bundle | `bundlePlay` |
| 16 KB memory pages | The two native libraries in the bundle are aligned to 16 KB (checked) |
| Predictive back | Enabled; in-app navigation follows the back gesture |
| No debug signing | The Play bundle is only ever signed with your upload key |

Sources: https://developer.android.com/google/play/requirements/target-sdk ·
https://developer.android.com/guide/app-bundle · https://developer.android.com/guide/practices/page-sizes

## 6. Not done here, on purpose

- Publishing itself, and anything in Play Console.
- A tablet-specific layout. The app works on tablets and foldables (it reflows and scrolls) but has not been
  designed for them; Play may show it as "not optimised for larger screens".
- Translations: English only.
