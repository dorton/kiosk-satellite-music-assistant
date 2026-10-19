package net.markandrew.kiosk.music;

import java.util.Map;

/** Preserve explicit saved IDs; never infer a destination from an address or name. */
final class DefaultPlayerPolicy {
    static String saved(Map<String, Object> settings) {
        String fixed = text(settings.get("defaultPlayer"));
        String local = text(settings.get("localPlayer"));
        if ("Fixed default player".equals(settings.get("playerMode"))) return fixed;
        return local.isEmpty() ? fixed : local;
    }
    private static String text(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    static Map<String,Object> normalized(Map<String,Object> settings){
        Map<String,Object> values=new java.util.HashMap<>(settings);
        values.put("defaultPlayer",saved(settings));values.put("playerMode","Fixed default player");
        values.remove("advanced");values.remove("kioskNode");return values;
    }
    static PlayerPolicy.Player selected(PlayerPolicy.Snapshot snapshot, String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Choose a default player in Setup.");
        PlayerPolicy.Player player = snapshot.players.get(id);
        if (player == null || !player.audio() || !player.visible || !player.available)
            throw new IllegalArgumentException("Selected player is unavailable or no longer offered. Choose a player in Setup or Players; no fallback was selected.");
        return player;
    }
}
