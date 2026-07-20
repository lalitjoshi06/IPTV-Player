# TV-Player (IPTV-Player)

Multi-playlist IPTV player for Android TV and phones with EPG, favorites, multi-source channels, and DASH/HLS support.

## Features

- **Multiple playlists** — Add any number of M3U/MPD playlists (URL or local file), toggle active/inactive individually
- **Per-playlist EPG control** — Enable or disable EPG loading per playlist via long-press dialog
- **DASH + HLS** — Widevine DRM with multi-session support; KODIPROP `stream_headers` and `license_key` parsed per-channel
- **Leanback UI** — Android TV native interface with vertical category tiles and channel list
- **Remote navigation** — UP/DOWN switches channels directly; LEFT/RIGHT opens side panels for category browsing
- **Favorites** — Star any channel; reorder, move up/down, or remove via long-press from the FAVORITES category
- **Per-stream headers** — `#KODIPROP:inputstream.adaptive.stream_headers` parsed per-entry; no hardcoded provider logic
- **Fast loading** — Minimal buffer durations (250ms playback start), 500ms error recovery retry, multi-source fallback
- **Caching** — Channels and EPG URLs cached to SharedPreferences; playlist-to-EPG mapping restored on reload; refresh-all button clears cache
- **Backup/restore** — Export/import all settings and playlists as JSON to Downloads folder
- **Search** — Find channels by name with temporary search results category


## Requirements

- Android 7.0+ (API 24)
- Design for Android TV Only (Working also for phone/tablet) 
- Internet permission for streaming

## Setup

1. Install the APK on your device
2. Launch the app — you'll be greeted by the **Setup** screen
3. Add playlists:
   - **Add by URL** — Enter a name and the M3U/MPD URL
   - **Add Local File** — Pick an M3U file from device storage
4. Tap **Continue** to load channels

## Usage

### Channel navigation

| Key | Action |
|---|---|
| **UP** | Next channel |
| **DOWN** | Previous channel |
| **LEFT** | Switch to last channel |
| **RIGHT** | Open side channel list |
| **ENTER / DPAD_CENTER** | Show/hide info bar |
| **BACK** | Exit player |

### Category & channel list (Main screen)

| Key | Action |
|---|---|
| **UP/DOWN** | Navigate categories or channels (locked at edges) |
| **RIGHT** | Jump from categories to channel list |
| **OK / Click** | Select category / Play channel |
| **Long-press** | Toggle favorite (channel) |

### Favorites

- Long-press any channel to add/remove from Favorites
- From the FAVORITES category, long-press for reorder options (Move Up / Move Down / Remove)
- Favorites use composite keys (`tvgId` or `playlist::name`) for uniqueness across playlists

### Settings

Accessible from the main screen:

- **Toggle playlists** — Click a playlist row to enable/disable it
- **Long-press playlist** — Dialog with options:
  - Toggle EPG ON/OFF for that playlist
  - Reload just that playlist
  - Remove playlist
- **Refresh All Content** — Clears all caches and re-fetches every active playlist
- **Add More** — Add another playlist
- **Backup / Restore** — Export or import config to `Downloads/tv_player_backup.json`

## Playlist format

Standard M3U with KODIPROP extensions:

```m3u
#EXTM3U x-tvg-url="http://example.com/epg.xml"
#EXTINF:-1 tvg-id="15" tvg-logo="logo.png" group-title="Entertainment", Channel Name
#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_key=http://license.server/wv.php?id=15
#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=MyUA|Referer=http://example.com
http://stream.server/channel.mpd
```

- `tvg-id` is used for EPG matching
- `stream_headers` values are applied per-channel via `MediaItem` request headers
- Multiple headers separated by `|`
- DASH and HLS URLs auto-detected by `.mpd` / `.m3u8` extension

## Building

Open in Android Studio or build from command line:

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

APK at `app/build/outputs/apk/debug/app-debug.apk`


## License

MIT

