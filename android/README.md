# Gelombang Radio for Android

Native Android version of Gelombang Radio for phones, tablets and Android Auto.

- **Phones:** bottom tabs (Home, Explore, Search, Library), a mini player, and a full-screen player
- **Tablets and foldables:** a side rail, and on wide screens Explore shows countries and stations side by side
- **Android Auto:** Favourites, Recent, Regions and Trending tabs, voice search ("play jazz on Gelombang"), song titles, artwork, and a favourite button on the car's Now Playing screen
- **Background play** with a media notification and lock-screen controls, automatic reconnect, and a sleep timer that fades out
- **Shared format with the desktop app:** favourites backups (.json) work in both apps, and .m3u/.pls files can be imported

Requires Android 7.0 or newer.

## Install

1. Download `Gelombang-Radio-<version>-android.apk`.
2. Open it on your phone or tablet. Android asks you to allow installs from your browser or file manager the first time.

## Android Auto

Android Auto hides media apps that weren't installed from Google Play. To allow this one:

1. On your phone, open **Settings → Connected devices → Connection preferences → Android Auto** (or search Settings for "Android Auto").
2. Scroll down and tap **Version** about 10 times, then confirm to turn on developer mode.
3. Open the **⋮** menu → **Developer settings**, and turn on **Unknown sources**.
4. Reconnect to the car. Gelombang Radio appears with the other media apps.

## Build

GitHub Actions builds the APK on every push that touches `android/` (see `.github/workflows/android.yml`) and attaches it to the run as an artifact.

To sign builds with the release key, add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Without them, the APK is signed with a temporary key, and each new build has to replace the app instead of updating it.

To build locally, open this folder in Android Studio, or run `./gradlew assembleRelease`. For release signing, put a `keystore.properties` file next to this README with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Keep it and the keystore out of git; `.gitignore` already excludes them.

## Layout

```
app/src/main/java/com/yudhis7/gelombang/
  data/       stations, Radio Browser client, favourites/history storage, playlists
  playback/   PlaybackService (Media3 + Android Auto browse tree), live-stream player, artwork provider
  ui/         Compose screens for phones and tablets
app/src/main/assets/regions.json   countries and regions (same file as the desktop app)
```
