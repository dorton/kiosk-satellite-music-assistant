import java.net.URI;
import net.markandrew.kiosk.music.IngressPolicy;
import net.markandrew.kiosk.music.TouchLayout;

/** Pure-Java tests: no HA/MA access. Run with tools/check.py --java. */
public final class IngressPolicyTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Policy mismatch"); checks++;
    }
    private static void refuses(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Unsafe input was accepted");
    }
    public static void main(String[] args) {
        URI origin = IngressPolicy.origin("https://ha.example:443/");
        equal("https://ha.example/", origin.toString());
        equal("https://ha.example/api/hassio_ingress/example/", IngressPolicy.ingress(origin, "/api/hassio_ingress/example").toString());
        refuses(() -> IngressPolicy.origin("http://ha.example"));
        refuses(() -> IngressPolicy.origin("https://user:password@ha.example"));
        refuses(() -> IngressPolicy.origin("https://ha.example/?token=example"));
        refuses(() -> IngressPolicy.origin("https://ha.example/subpath"));
        for (String path : new String[] {"https://other.example/api/hassio_ingress/example", "http://ha.example/api/hassio_ingress/example", "/api/hassio_ingress/example?token=example", "/api/hassio_ingress/a/../b", "/api/hassio_ingress/%2e%2e", "/api/hassio_ingress/a/ws", "//other.example/api/hassio_ingress/example", "/api/hassio_ingress/"}) {
            refuses(() -> IngressPolicy.ingress(origin, path));
        }
        equal("example", IngressPolicy.session("example"));
        refuses(() -> IngressPolicy.session("example\r\nInjected: value"));
        refuses(() -> IngressPolicy.session(""));
        equal("example_music_assistant", IngressPolicy.addon("example_music_assistant"));
        refuses(() -> IngressPolicy.addon("../other"));
        for (String command : IngressPolicy.COMMANDS) { IngressPolicy.command(command); checks++; }
        for (String command : new String[] {"player_queues/play_media", "music/playlists/add_playlist_tracks", "auth/token", "players/all"}) {
            if (!command.equals("players/all")) refuses(() -> IngressPolicy.command(command));
        }
        IngressPolicy.playback("library://album/42", "play"); checks++;
        IngressPolicy.playback("spotify://track/example", "add"); checks++;
        IngressPolicy.playback("library://artist/42", "play"); checks++;
        for (String uri : new String[] {"https://example/track/42", "library://track/42\r\nHeader", "", "library://audio_source/42"})
            refuses(() -> IngressPolicy.playback(uri, "play"));
        refuses(() -> IngressPolicy.playback("library://album/42", "clear"));
        equal(48, TouchLayout.TARGET);
        equal(true, TouchLayout.stackedHeader(320));
        equal(true, TouchLayout.stackedHeader(559));
        equal(false, TouchLayout.stackedHeader(560));
        equal(false, TouchLayout.stackedHeader(960));
        equal(1, TouchLayout.columns(240, 640));
        equal(2, TouchLayout.columns(360, 800));
        equal(4, TouchLayout.columns(960, 480));
        equal(4, TouchLayout.columns(640, 360));
        equal(6, TouchLayout.columns(1280, 800));
        equal(6, TouchLayout.columns(1920, 1080));
        equal(3, TouchLayout.columns(480, 960));
        for (String type : new String[] {"album", "playlist", "artist", "podcast"}) equal("open", TouchLayout.tap(type));
        for (String type : new String[] {"track", "radio", "audiobook", "podcast_episode"}) equal("play", TouchLayout.tap(type));
        equal("menu", TouchLayout.tap("unknown"));
        equal("menu", TouchLayout.tap(null));
        System.out.println("PASS: " + checks + " pure Java policy checks (no network)");
    }
}
