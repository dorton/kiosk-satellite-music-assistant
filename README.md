# Music Assistant for Kiosk Satellite

Play and control your Music Assistant library from a wall-mounted Kiosk.

Browse your music, podcasts and radio, see what is playing, manage the queue and
control players and groups — all from the touch screen.

By **Mark Andrew** · Version **1.0.0** · [MIT licence](LICENSE)

## What you can do

- Browse albums, artists, tracks, playlists, radio, podcasts and audiobooks.
- Search, mark favourites and choose which Library sections are shown.
- See what is playing, with artwork and live radio information.
- View the queue, reorder it and act on items with a press and hold.
- Choose a player, manage supported groups and set member volumes.
- Show track lyrics and adjust the sound for a supported player.
- Optionally play music on this kiosk itself, in sync with the rest of the house.

It also works as a controller only: leave playback off and use it to run music
playing on other players.

## What you need

- **Kiosk Satellite** with plugin support, on **Android 8.0 or newer**.
- **Home Assistant**, already signed in on the kiosk. The plugin uses that same
  account; there is nothing extra to sign in to.
- **Music Assistant** installed as a Home Assistant app, and available to that
  account. A standalone Music Assistant server is not supported.
- **Music Assistant 2.10.x** — this release is tested against 2.10.2.

You do not need to change any Kiosk settings, and the plugin never reconfigures
Kiosk's own music player.

## Install

1. Open the [latest release](https://github.com/mark-andrew/kiosk-satellite-music-assistant/releases/latest).
2. Download the plugin file, **`musicassistantplugin-1.0.0.zip`** — not the
   "Source code" archive.
3. On the kiosk, open the **Plugin Manager**, install that ZIP and enable
   **Music Assistant**. Accept only the permissions you are happy with.
4. Open the plugin from its icon.

**Already using an earlier build?** Update in place. Do not uninstall the plugin,
clear its storage or reset pairing — your settings and player identity are kept.

## Set it up

Open the plugin, then **Library → Settings → Plugin**:

1. Choose your **Music Assistant server**. If only one is available it is
   selected for you.
2. Turn on **Enable this kiosk's receiver** if you want music to play from this
   kiosk. Leave **Server override** blank unless you have been told otherwise. A
   blank receiver name follows the kiosk's name; you can type your own.
3. Choose your **Default player** — this kiosk, or another player in your home.
4. Tap **Save**. Each kiosk keeps its own settings.

### Playback preferences

| Setting | What it does |
| --- | --- |
| Auto-revert after idle | Goes back to your default player after you have finished with a temporary one. Default **60 seconds**; set **0** to switch this off. |
| Ducking | How far the music drops during a voice announcement, as **1–10%** of the current volume. Default **10%**. |
| Prevent screensaver while playing | Keeps the screen awake while music plays. Off by default, and it does not change your Kiosk display settings. |
| Hardware buttons control group volume | Lets the kiosk's volume buttons adjust the whole group this kiosk is playing in. Off by default. |

## What your account can do

Browsing, playback, volume, groups, the queue and playing music on this kiosk
work with any Music Assistant account. Favourites, adding to the library and
creating playlists need the **user** role. Changing sound settings and removing
items from your library need the **admin** role. The plugin never grants or
changes permissions itself.

## Using it

- **Tap** an album or other item to open it, and tap a track, radio station or
  queue item to play it.
- **Press and hold** an item, or tap **⋮**, for the actions available on it.
- **Play** on an album starts it straight away, replacing what is playing.
- Choosing a player for a moment does not change your saved default. Going back
  to the Library returns to the player you selected, and the idle timer can
  return you to the default.
- The queue keeps up if someone skips a track on another kiosk or in the Music
  Assistant app.
- **Close** just puts the interface away — the music carries on.

## Your privacy

- Sign-in is handled by Kiosk. You never type a password or token into the plugin.
- Your Music Assistant sign-in details stay in memory and are not written to disk.
  Pairing credentials are stored encrypted and tied to that one server.
- Artwork is fetched without your sign-in details and without following redirects.
- Nothing is sent to the plugin's author. Music Assistant, your configured
  providers and the artwork hosts still receive the requests their features need.
- Actions are never retried automatically. If something looks uncertain, check
  the player, queue or library before trying again.

See [SECURITY.md](SECURITY.md) for how to report a security concern.

## Things to know

- **Lyrics are not available for radio playback.** Live station information can
  still appear while playing.
- Artwork, grouping, library actions and sound settings depend on the player you
  are using and the permissions of your account.
- Very long lists are trimmed: narrow your search or filters if a section stops
  growing. Search shows up to 25 results per type.
- Sound settings are staged until you tap **Apply**, so you can try changes and
  cancel them. Your other sound settings are left alone.

## If something isn't working

Open **Plugin preferences → Diagnostics → Check connection** to see the status of
your server, sign-in, players and this kiosk's receiver.

- **No servers listed** — check the Music Assistant app is running in Home
  Assistant and that the kiosk is signed in.
- **No sound on this kiosk** — check **Enable this kiosk's receiver**, and
  confirm the kiosk can see the Music Assistant server on your network.
- **Lyrics are missing** — the message **"Lyrics are not available for radio
  playback."** is expected for live radio. Track lyrics depend on your providers.
- **A sound setting is greyed out** — the account signed in on the kiosk does not
  have permission to change it.

## Credits

Independent community plugin by Mark Andrew. Music Assistant, Kiosk Satellite and
the included player components are the work of their respective authors; see
[NOTICE.md](NOTICE.md) and [DEPENDENCIES.md](DEPENDENCIES.md).

Building or testing the plugin yourself? See [docs/DEVELOPING.md](docs/DEVELOPING.md).
