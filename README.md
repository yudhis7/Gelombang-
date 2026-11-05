# Gelombang Radio

A personal desktop radio for Windows and macOS. It plays live online stations from across Asia-Pacific, Australia and New Zealand in one window, so you don't have to open each station's website.

**[Download the latest version](https://github.com/yudhis7/Gelombang-/releases/latest)**

- **37 countries in 5 regions:** Australia & New Zealand, Pacific Islands, Southeast Asia, East Asia and South Asia
- **Live station directory:** country lists, region-wide "most popular" lists, and search by name or genre, all from the free community [Radio Browser](https://www.radio-browser.info) directory
- **Now playing:** shows the song title for stations that send it, and you can copy it with one click
- **Favourites, Recently played, and My stations** (your own stream links)
- **HLS (.m3u8) streams** play alongside the usual MP3, AAC and Ogg streams
- **Sleep timer** that fades the sound out before it stops
- **Background play:** keeps playing in the system tray (Windows) or the menu bar (macOS), with your favourites in the tray menu
- **Media keys** and the system media controls work
- **Import and export:** back up favourites as .m3u or .json, and import .m3u, .pls or .json files

## Install

All files are attached to each version on the [Releases page](https://github.com/yudhis7/Gelombang-/releases).

### Windows 10/11 (64-bit)

1. Run **`Gelombang-Radio-Setup-1.0.0.exe`**. It installs for your user account only, with no admin prompt, and adds Start menu and desktop shortcuts.
2. The app isn't code-signed, so SmartScreen may say *"Windows protected your PC"*. Click **More info → Run anyway**.

Prefer no installer? Unzip **`Gelombang-Radio-1.0.0-win-x64.zip`** anywhere and run `Gelombang Radio.exe`.

### macOS 12 Monterey or newer

1. Pick the zip that matches your Mac:
   - **Apple Silicon** (M1, M2, M3, M4 and later): `Gelombang-Radio-1.0.0-mac-arm64.zip`
   - **Intel**: `Gelombang-Radio-1.0.0-mac-x64.zip`

   To check which Mac you have, open  → About This Mac and look at **Chip** or **Processor**.
2. Unzip the file and drag **Gelombang Radio** into **Applications**.
3. The app isn't notarised by Apple, so the first launch is blocked. Open it once, then go to **System Settings → Privacy & Security** and click **Open Anyway**.

   If macOS says the app "is damaged", run this once in Terminal:

   ```
   xattr -cr "/Applications/Gelombang Radio.app"
   ```

### Android phones, tablets and Android Auto

There's also a native Android version. See [android/README.md](android/README.md) for installing it and for turning it on in Android Auto.

## Using it

| Action | Windows | macOS |
| --- | --- | --- |
| Play / pause | Space | Space |
| Search stations | Ctrl+F or / | ⌘F or / |
| Add or remove the playing station from favourites | Ctrl+D | ⌘D |
| Volume up / down | Ctrl+↑ / Ctrl+↓ | ⌘↑ / ⌘↓ |
| Next / previous station in the current list | Ctrl+→ / Ctrl+← | ⌘→ / ⌘← |
| Settings | Ctrl+, | ⌘, |

- **Search** covers the whole region. If you start typing while you're viewing a country, the search stays inside that country until you click the **in [country] ×** chip.
- **Genre chips** above a station list filter it, for example *news*, *jazz* or *dangdut*.
- **Closing the window** keeps the radio playing in the tray or menu bar. Use **Quit** from there, or turn this off in Settings.
- **My stations** (the **+** button) plays any direct stream link that isn't in the directory.

## Good to know

- **Some stations won't play outside their own country.** Broadcasters such as Japan's radiko and some Australian commercial networks restrict their streams by location. When a stream can't play, the player says so and you can move to another station.
- **The directory is community-maintained.** Stream links change, so the app refreshes your favourites' links from the directory each time it starts.
- **DAB+ over the air** (common in Australia) needs a hardware tuner and isn't covered. Most DAB+ stations also stream online, and those are listed here.
- **Your data stays on your computer.** Favourites, history and settings are in one file:
  - Windows: `%APPDATA%\Gelombang Radio\gelombang-data.json`
  - macOS: `~/Library/Application Support/Gelombang Radio/gelombang-data.json`

## Build from source

You need Node.js 20 or newer.

```
npm install
npm start            # run the app in development
npm run dist:win     # Windows installer + zip  -> dist/
npm run dist:mac     # macOS zips (arm64 + x64) -> dist/   (run this on a Mac)
```

### Project layout

```
src/main/main.js          window, tray, menus, app lifecycle
src/main/radiobrowser.js  Radio Browser client with mirror failover and caching
src/main/icy.js           reads in-stream song titles (Icecast/SHOUTcast metadata)
src/main/playlist.js      .m3u / .pls / .json import and export
src/main/store.js         local JSON storage
src/preload.js            safe bridge between the window and the app
src/renderer/             the interface (HTML, CSS, JS); hls.js is in vendor/
src/shared/regions.json   the countries and regions shown in the sidebar
```

To add or remove countries, edit `src/shared/regions.json`. Each country needs an ISO country code, a name and a time zone.

## Contributors

| Contributor | Contribution |
| --- | --- |
| [yudhis7](https://github.com/yudhis7) | Ideation: the concept of one desktop app for Asia-Pacific, Australia and New Zealand radio, and the product direction |

## Credits

- Station listings: [Radio Browser](https://www.radio-browser.info), a free community directory
- HLS playback: [hls.js](https://github.com/video-dev/hls.js)
- App shell: [Electron](https://www.electronjs.org)
