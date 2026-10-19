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

## Notes

- Local playback uses the plugin's own encrypted Sendspin receiver, separate from
  Kiosk's built-in player. The README lists the Kiosk settings to check first.
- **Lyrics are not available for radio playback.** Ordinary track lyrics depend on
  the provider and the station's metadata.
- EQ and library changes depend on the signed-in account's Music Assistant role and
  on what the provider or player supports.
- Automated checks do not replace physical-device acceptance. Try the plugin on one
  kiosk before rolling it out everywhere.

This is the first stable release. A community listing is submitted and approved
separately, so this release does not imply that it is listed yet.
