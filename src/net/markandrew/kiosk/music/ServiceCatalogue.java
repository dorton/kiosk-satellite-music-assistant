package net.markandrew.kiosk.music;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** A single read-only signed HA request. Signature and raw catalogue never leave memory. */
final class ServiceCatalogue {
    static final class ReadFailure extends Exception { ReadFailure(String message) { super(message); } }
    private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();
    List<DiscoveryPolicy.Choice> read(URI origin, String signedPath, BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) throw new ReadFailure("Kiosk discovery cancelled.");
        URI url = DiscoveryPolicy.signedServices(origin, signedPath);
        HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
        if (!active.compareAndSet(null, connection)) { connection.disconnect(); throw new ReadFailure("Kiosk discovery is already running."); }
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestMethod("GET"); connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            int status = connection.getResponseCode();
            if (status != 200) throw new ReadFailure("Kiosk catalogue read failed [HTTP " + status + "]. No redirect or fallback was followed.");
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw new ReadFailure("Kiosk catalogue was not JSON.");
            if (connection.getContentLengthLong() > DiscoveryPolicy.MAX_CATALOGUE)
                throw new ReadFailure("Kiosk catalogue exceeds the 2 MiB read limit.");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = stream.read(bytes)) != -1) {
                    if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new ReadFailure("Kiosk discovery cancelled.");
                    if (buffer.size() + count > DiscoveryPolicy.MAX_CATALOGUE) throw new ReadFailure("Kiosk catalogue exceeds the 2 MiB read limit.");
                    buffer.write(bytes, 0, count);
                }
                JSONTokener parser = new JSONTokener(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                Object value = parser.nextValue();
                if (!(value instanceof JSONArray) || parser.nextClean() != 0) throw new ReadFailure("Unsupported HA service catalogue.");
                Map<String, Object> actions = new LinkedHashMap<>();
                JSONArray domains = (JSONArray) value;
                for (int i = 0; i < domains.length(); i++) {
                    JSONObject domain = domains.getJSONObject(i);
                    if (!"esphome".equals(domain.optString("domain"))) continue;
                    JSONObject services = domain.getJSONObject("services");
                    java.util.Iterator<String> keys = services.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (!(key.endsWith("_media_player_list") || key.endsWith("_media_player_set"))) continue;
                        JSONObject description = services.optJSONObject(key);
                        actions.put(key, Collections.singletonMap("name", description == null ? "" : description.optString("name")));
                    }
                }
                return DiscoveryPolicy.nodes(Collections.singletonMap("esphome", actions));
            }
        } finally { active.compareAndSet(connection, null); connection.disconnect(); }
    }
    void cancel() { HttpURLConnection connection = active.getAndSet(null); if (connection != null) connection.disconnect(); }
}
