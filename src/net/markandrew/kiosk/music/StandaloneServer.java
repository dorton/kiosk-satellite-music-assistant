package net.markandrew.kiosk.music;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Optional direct connection to a standalone (non-add-on) Music Assistant server.
 * When both a URL and a long-lived MA token are configured, Ingress is bypassed. */
final class StandaloneServer {
    static final String SLUG = "standalone";
    private static volatile URI base;
    // Credential: memory only after load; never render or log this field.
    private static volatile String token = "";

    private StandaloneServer() {}

    static void configure(Map<String, Object> settings) {
        String url = text(settings, "standaloneUrl"), key = text(settings, "standaloneToken");
        URI parsed = url.isEmpty() || key.isEmpty() ? null : parse(url);
        token = parsed == null ? "" : key;
        base = parsed;
    }
    static boolean enabled() { return base != null && !token.isEmpty(); }
    static URI base() { return base; }
    static String authorization() { return "Bearer " + token; }

    static URI parse(String value) {
        try {
            URI input = new URI(value.trim());
            String scheme = input.getScheme() == null ? "" : input.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || input.getHost() == null || input.getUserInfo() != null
                || input.getRawQuery() != null || input.getRawFragment() != null) return null;
            String path = input.getRawPath() == null || input.getRawPath().isEmpty() ? "/" : input.getRawPath();
            if (!path.endsWith("/")) path += "/";
            return new URI(scheme, null, input.getHost().toLowerCase(Locale.ROOT), input.getPort(), path, null, null);
        } catch (Exception ignored) { return null; }
    }
    private static String text(Map<String, Object> settings, String key) {
        Object value = settings == null ? null : settings.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
