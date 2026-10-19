# Music Assistant 1.0.0 — First community release

Music Assistant browsing and playback control, built for Kiosk Satellite touch
screens, with an independent encrypted Sendspin receiver for local playback.

## Highlights

- Browse albums, artists, tracks, playlists, radio, podcasts and audiobooks.
- Now Playing with artwork, live radio metadata and touch-friendly controls.
- Live queues, hold/overflow menus, favourites and playlist management.
- Player selection, grouping, member volume and optional hardware group volume.
- Voice ducking, call-aware playback, track lyrics and permission-gated player EQ.

## Installation

Download **musicassistantplugin-1.0.0.zip** from this release and install it with
Kiosk Satellite's Plugin Manager. Enable **Music Assistant**, then choose your
server, default player and optional local receiver in Plugin preferences.

Requires SDK 1-capable Kiosk Satellite on Android 8.0+ and an accessible Music
Assistant Home Assistant app. Development verification used MA 2.10.2 with
aiosendspin 9.1.1; newer Sendspin protocol revisions are not verified. EQ and
library actions depend on permissions and provider/player support.

**Existing users:** update in place. Do not uninstall, clear storage or reset
pairing. The installed plugin ID remains `musicassistantplugin`; the new ZIP
name follows Kiosk's community-release requirements.

## Release preparation

- Concise setup descriptions and installation/build/security documentation.
- Self-contained, pinned SDK/Gradle build and Actions release packaging.
- Temporary collector, remote debug logging and logging-only tests removed.
- Radio lyrics message: **Lyrics are not available for radio playback.**

Radio lyrics remain unsupported; ordinary track lyrics depend on providers.
Automated tests do not replace physical-device acceptance. Community listing is
submitted and approved separately; this release does not imply listing approval.
