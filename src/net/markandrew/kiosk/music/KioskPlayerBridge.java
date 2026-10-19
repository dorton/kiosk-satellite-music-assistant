package net.markandrew.kiosk.music;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

/** Only this configured kiosk's two documented ESPHome media-player actions. */
final class KioskPlayerBridge {
    static final class Pick {
        final String source, id, name;
        Pick(String source, String id) { this(source, id, ""); }
        Pick(String source, String id, String name) { this.source = source; this.id = id; this.name = name; }
        boolean same(Pick other) { return other != null && source.equals(other.source) && id.equals(other.id); }
    }
    static final class State {
        final Pick current;
        final java.util.Set<String> musicPlayers = new java.util.HashSet<>();
        State(Map<?, ?> response) {
            Object current = response.get("current");
            if (!(current instanceof Map)) throw new IllegalArgumentException();
            this.current = new Pick(text((Map<?, ?>) current, "source"), text((Map<?, ?>) current, "id"), text((Map<?, ?>) current, "name"));
            Object rows = response.get("players");
            if (!(rows instanceof List)) throw new IllegalArgumentException();
            for (Object row : (List<?>) rows) if (row instanceof Map && "music_assistant".equals(((Map<?, ?>) row).get("source"))
                && Boolean.TRUE.equals(((Map<?, ?>) row).get("available")))
                musicPlayers.add(text((Map<?, ?>) row, "id"));
        }
        Pick forPlayer(String playerId, String localId) {
            if (playerId.equals(localId)) return new Pick("device", "");
            if (!musicPlayers.contains(playerId)) throw new IllegalArgumentException("This player is not offered by native Kiosk. Set up Music Assistant on Kiosk's Media Player page first.");
            return new Pick("music_assistant", playerId);
        }
    }
    private final PluginHost host;
    KioskPlayerBridge(PluginHost host) { this.host = host; }

    void read(String node, BooleanSupplier current, Consumer<State> done, Consumer<String> fail) {
        call(node, "media_player_list", Collections.emptyMap(), current, response -> {
            try { done.accept(new State(response)); }
            catch (IllegalArgumentException ignored) { fail.accept("Kiosk returned an unsupported player-list response."); }
        }, fail);
    }

    void follow(String node, Pick pick, BooleanSupplier current, Runnable done, Consumer<String> fail) {
        if (!pick.source.equals("device") && !pick.source.equals("music_assistant")
            && !pick.source.equals("home_assistant") && !pick.source.equals("sonos")) {
            fail.accept("This native player source is unsupported."); return;
        }
        Map<String, Object> values = new HashMap<>(); values.put("source", pick.source);
        // media_player_list strips the session: prefix; media_player_set requires it.
        values.put("player", "device".equals(pick.source) && "*".equals(pick.id) ? "session:*" : pick.id);
        call(node, "media_player_set", values, current, ignored -> read(node, current, observed -> {
            if (pick.same(observed.current)) done.run();
            else fail.accept("Native player selection was not verified. Check current selection before retrying.");
        }, fail), fail);
    }

    private void call(String node, String action, Map<String, Object> values, BooleanSupplier current,
                      Consumer<Map<?, ?>> done, Consumer<String> fail) {
        if (!current.getAsBoolean()) return;
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "call_service"); message.put("domain", "esphome");
            message.put("service", PlayerPolicy.node(node) + "_" + action);
            message.put("service_data", values); message.put("return_response", true);
            host.haCall(message, (ok, data, error) -> {
                if (!current.getAsBoolean()) return;
                if (!ok || !(data instanceof Map) || !(((Map<?, ?>) data).get("response") instanceof Map)) {
                    fail.accept("Kiosk player action failed. Check this kiosk's ESPHome node, connection and permissions. Do not blindly retry a timed-out selection."); return;
                }
                done.accept((Map<?, ?>) ((Map<?, ?>) data).get("response"));
            });
        } catch (IllegalArgumentException error) { fail.accept(error.getMessage()); }
        catch (RuntimeException | LinkageError ignored) { fail.accept("Kiosk HA player bridge is unavailable."); }
    }
    private static String text(Map<?, ?> map, String key) { Object value = map.get(key); return value instanceof String ? (String) value : ""; }
}
