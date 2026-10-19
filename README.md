# Music Assistant for Kiosk Satellite

Browse your Music Assistant library, control players and groups, and play music
on your kiosk through an independent, encrypted Sendspin receiver.

By **Mark Andrew** · **1.0.0** · [MIT licence](LICENSE)

## Features

- Browse albums, artists, tracks, playlists, radio, podcasts and audiobooks.
- Search, filter favourites and choose which Library categories appear.
- Now Playing with artwork, live radio metadata and playback controls.
- Live queue views, touch-and-hold item menus and playlist creation.
- Select players, manage supported groups and adjust member volumes.
- Optional local playback, hardware group-volume control and voice ducking.
- Track lyrics and per-player EQ when supported and permitted by Music Assistant.

The plugin does not require or reconfigure Kiosk's built-in Sendspin player.

## Requirements

- **Kiosk Satellite** with public plugin SDK 1 support, on **Android 8.0+**.
  Kiosk 2026.10.31 provides the Remote Admin choice-refresh controls used here.
- A **Music Assistant Home Assistant app** accessible through Home Assistant
  Ingress, using the kiosk's existing Home Assistant account.
- Music Assistant **2.10.x**; development verification used **2.10.2** and
  **aiosendspin 9.1.1**. Other versions and newer Sendspin protocol revisions
  have not been verified. Hardware group-volume routing is guarded to 2.10.x.
- For local playback, the kiosk must be able to reach the discovered Sendspin
  endpoint. mDNS discovery may need an explicit endpoint override across subnets.

Standalone Music Assistant servers without Home Assistant app Ingress are not
automatically configured. EQ requires the selected player's DSP support and
`config.players.read` / `config.players.write` permissions; the plugin does not
grant these permissions or elevate a regular account.

## Install

1. Open [Releases](https://github.com/mark-andrew/kiosk-satellite-music-assistant/releases/latest).
2. Download **`musicassistantplugin-1.0.0.zip`**, not GitHub's source archive.
3. Install the ZIP with Kiosk's **Plugin Manager** ZIP installer and enable
   **Music Assistant**. Accept only permissions you understand.
4. Open the plugin and follow the setup below.

The repository is `kiosk-satellite-music-assistant`. Release ZIPs use the retained
plugin ID, `musicassistantplugin`, as required by the community directory.

**Updating from a development build:** update in place. Do not uninstall, clear
storage or reset pairing. The plugin ID, saved settings and receiver identity
are retained. Community-directory installation becomes available only after
separate approval; this README does not claim the plugin is already listed.

## Set up

Open **Library → Settings → Plugin**:

1. Choose a **Music Assistant server**. A single accessible app can be selected
   automatically; choose explicitly if more than one is available.
2. Enable **Enable this kiosk's receiver** for playback on this device. Leave
   **Server override** blank for normal discovery. A blank receiver name follows
   the kiosk's name; a custom name is retained.
3. Choose your **Default player**, either this kiosk or another accessible player.
4. Tap **Save**. Cancel discards staged changes. Each kiosk has its own settings.

Receiver registration uses the current Music Assistant account and retains the
kiosk's identity. Permission or trust failures are shown rather than bypassed.
Selecting an external default allows controller-only use without a local receiver.

### Playback preferences

| Setting | Behaviour |
| --- | --- |
| Auto-revert after idle | Returns to the default player after the temporary player is idle. Default: **60 seconds**; **0 disables** it. |
| Ducking | Local music level during voice interactions, as **1–10%** of its current volume. Default: **10%**. |
| Prevent screensaver while playing | Optional; off by default. Does not change Kiosk's saved display preferences. |
| Hardware buttons control group volume | Optional; off by default. Applies to the grouped, playing **local receiver**, not a temporary player selection. |

Kiosk's native settings are never changed automatically. External-player hardware
control also depends on Kiosk's own media-player volume routing. The **Group
volume** slider label describes its current target independently of the hardware
button preference.

## Touch controls

- Tap an album or other container to open it. Tap a track, radio station or queue
  item to play it. Hold an item or tap **⋮** for its actions.
- Album **Play** starts the album immediately, replacing the active queue.
- The player menu offers queue, group, transfer, lyrics and playlist actions.
  Destructive actions require confirmation and fresh permission/state checks.
- Player choices are temporary and do not overwrite the saved default. Library
  returns to the selected player; idle auto-revert can restore the default.
- Queue views follow remote track changes while open. Already played/current
  queue entries are protected from unsupported removal.
- **Close** dismisses the interface; it does not stop playback or change display
  preferences. Leaving Library for the player clears search, while detail/back
  navigation retains its context.
- EQ changes and resets are staged until **Apply**. Unrelated DSP filters are
  preserved. EQ is hidden when the account lacks the required permissions.

## Connection checks and privacy

Use **Plugin preferences → Diagnostics → Check connection** for server, sign-in,
player and receiver status. Remote Admin also offers choice refresh and a
read-only connection check on supported Kiosk versions. Refresh/check actions do
not select defaults, save settings, pair a receiver or change playback. Explicit
reconnect and registration-retry actions are separate.

- Home Assistant authentication is supplied by Kiosk; no manual token is needed.
- Ingress sessions remain in memory. Receiver credentials use an encrypted,
  server-bound store. HTTPS checks and encrypted Sendspin trust are not bypassed.
- External HTTPS artwork is requested without the Ingress cookie or redirects.
- No temporary collector, remote debug logging or plugin telemetry is included.
  Music Assistant, configured providers and artwork hosts still receive the
  requests needed for their features.
- Playback and library writes are not automatically retried. If an outcome is
  uncertain, check the actual player, queue or library before retrying.

See [SECURITY.md](SECURITY.md) for the security boundary and reporting guidance.

## Known limits

- **Lyrics are not available for radio playback.** Live station-provided song
  details may still appear in Now Playing. Track lyrics depend on your providers.
- Artwork, metadata, grouping, library operations and EQ depend on player/provider
  capabilities and account permissions.
- Browsing is bounded to about 2,000 accumulated items; narrow the search/filter
  at the limit. Search returns up to 25 results per type. Artwork and API reads
  have size/time limits.
- Open queue/group/lyrics panels use bounded polling, not a permanent Music
  Assistant event connection. Native hardware volume steps differ by device.
- Automated fixtures do not prove rendering, timing or every physical device's
  behaviour. See the [release checklist](docs/RELEASING.md).

## Build and test

The documented build targets Linux/macOS with Python 3, **JDK 21**, Android SDK **platform 36 / build-tools 36.0.0**
and accepted Android SDK licences. The included **Gradle 9.3.1** wrapper verifies
its distribution checksum. Its first run downloads Gradle and dependencies;
these development commands never install the plugin on a kiosk.

```sh
python3 tools/test.py --fetch-test-deps
python3 tools/build.py --version 1.0.0
python3 tools/check_package.py dist/musicassistantplugin-1.0.0.zip
python3 tools/release_check.py
```

`--fetch-test-deps` verifies and downloads the pinned test-only org.json JAR for
the pure-Java loopback API tests. For an offline build, supply it with
`--json-jar /path/to/org.json.jar`. Android fixtures also exercise real API classes.
Run builds/tests serially; do not run two Gradle processes in this checkout.

The compile-only SDK is included under `sdk/`, with its upstream licence. Its
exact revision and Git blob hashes are verified against `sdk.lock.json`; SDK
classes are not shipped in the plugin DEX. `--fetch-sdk` can restore missing
pinned SDK files, but refuses modified files. Vendored player provenance and
modifications are documented in [NOTICE.md](NOTICE.md) and [DEPENDENCIES.md](DEPENDENCIES.md).

Release assets are generated and uploaded by **GitHub Actions**, using the
[official Kiosk template workflow](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/blob/main/.github/workflows/build.yml)
adapted for the embedded player. See [docs/RELEASING.md](docs/RELEASING.md).
