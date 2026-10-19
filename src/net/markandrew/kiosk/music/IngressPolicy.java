package net.markandrew.kiosk.music;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Pure policy: no HTTP, Android, credentials, or writes. */
public final class IngressPolicy {
    public static final int PAGE_SIZE = 48;
    public static final int MAX_BROWSE_ITEMS = 2000;
    public static final int MAX_RESPONSE = 2 * 1024 * 1024;
    public static final Set<String> COMMANDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "music/albums/library_items", "music/playlists/library_items",
        "music/radios/library_items", "music/artists/library_items", "music/tracks/library_items",
        "music/podcasts/library_items", "music/audiobooks/library_items", "music/search",
        "music/albums/album_tracks", "music/playlists/playlist_tracks", "music/artists/artist_albums",
         "music/podcasts/podcast_episodes", "players/all", "players/get", "player_queues/all", "player_queues/get",
         "player_queues/items", "music/item_by_uri", "music/verify_item_uri", "metadata/get_track_lyrics",
           "providers", "auth/scopes", "config/providers/get_value", "config/flows/get", "config/players/dsp/get")));
    static final Set<String> MUTATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "players/cmd/play", "players/cmd/pause", "players/cmd/next", "players/cmd/previous", "players/cmd/seek",
        "config/players/setup", "config/flows/submit", "sendspin/pair_web_player", "config/players/dsp/save",
        "player_queues/play_index", "player_queues/delete_item", "player_queues/clear", "player_queues/shuffle", "player_queues/repeat",
        "players/cmd/set_members", "players/cmd/group_volume", "players/cmd/volume_set", "players/cmd/volume_mute", "players/cmd/group_volume_mute",
        "music/favorites/add_item", "music/favorites/remove_item", "music/library/add_item", "music/library/remove_item",
        "music/albums/remove", "music/artists/remove", "music/tracks/remove", "music/playlists/remove", "music/radios/remove", "music/podcasts/remove", "music/audiobooks/remove",
        "music/playlists/create_playlist", "music/playlists/add_playlist_tracks", "music/playlists/remove_playlist_tracks")));

    private IngressPolicy() {}

    public static String addon(String value) {
        if (value == null || !value.matches("[a-z0-9_]{1,100}"))
            throw new IllegalArgumentException("Invalid Music Assistant app slug.");
        return value;
    }

    public static URI origin(String value) {
        try {
            URI input = new URI(value);
            if (!"https".equalsIgnoreCase(input.getScheme()) || input.getHost() == null ||
                input.getUserInfo() != null || input.getRawQuery() != null || input.getRawFragment() != null ||
                input.getPort() == 0 || input.getPort() > 65535 ||
                !(input.getRawPath().isEmpty() || "/".equals(input.getRawPath()))) throw new Exception();
            return new URI("https", null, input.getHost().toLowerCase(java.util.Locale.ROOT),
                input.getPort() == 443 ? -1 : input.getPort(), "/", null, null);
        } catch (Exception error) {
            throw new IllegalArgumentException("Home Assistant must use a trusted HTTPS origin without credentials or a subpath.");
        }
    }

    public static URI ingress(URI origin, String value) {
        try {
            URI supplied = new URI(value);
            // Reject traversal before URI.resolve has a chance to normalise it.
            if (supplied.getRawPath() == null || !supplied.getRawPath().matches("/api/hassio_ingress/[A-Za-z0-9_-]+/?")) throw new Exception();
            URI candidate = origin.resolve(supplied);
            URI candidateOrigin = origin(candidate.getScheme() + "://" + candidate.getRawAuthority());
            if (!origin.equals(candidateOrigin) || candidate.getRawQuery() != null || candidate.getRawFragment() != null ||
                !candidate.getRawPath().matches("/api/hassio_ingress/[A-Za-z0-9_-]+/?")) throw new Exception();
            String path = candidate.getRawPath();
            return origin.resolve(path.endsWith("/") ? path : path + "/");
        } catch (Exception error) {
            throw new IllegalArgumentException("Home Assistant returned an unexpected Ingress address.");
        }
    }

    public static String session(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,512}"))
            throw new IllegalArgumentException("Home Assistant did not provide a valid Ingress session.");
        return value;
    }

    public static void command(String value) {
        if (!COMMANDS.contains(value)) throw new IllegalArgumentException("This Music Assistant read command is not supported.");
    }

    public static void playback(String uri, String option) {
        if (!("play".equals(option) || "next".equals(option) || "add".equals(option) || "replace".equals(option) || "replace_next".equals(option)) || uri == null || uri.length() > 2048
            || !uri.matches("[A-Za-z0-9_.-]+://(album|artist|playlist|track|radio|audiobook|podcast_episode)/[^\\s\\p{Cntrl}]+"))
            throw new IllegalArgumentException("Unsupported playback request.");
    }

}
