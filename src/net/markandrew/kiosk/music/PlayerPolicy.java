package net.markandrew.kiosk.music;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Port of the original card's playbackLeader/playbackQueue/transferEligible rules. */
public final class PlayerPolicy {
    private PlayerPolicy() {}
    /** JSON null is absence; other malformed values must not silently remove safety flags. */
    public static String reference(Object value, Object jsonNull) {
        if (value == null || value == jsonNull) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid MA player reference metadata.");
        return (String) value;
    }
    public static final class Player {
        public final String id, name, type, parent, syncedTo, activeSource, state;
        public final boolean available, visible;
        public final boolean grouped;
        public Player(String id, String name, String type, boolean available, boolean visible,
                      String parent, String syncedTo, String activeSource, String state) {
            this(id, name, type, available, visible, parent, syncedTo, activeSource, state, false);
        }
        public Player(String id, String name, String type, boolean available, boolean visible,
                      String parent, String syncedTo, String activeSource, String state, boolean grouped) {
            this.id = id; this.name = name; this.type = type; this.available = available; this.visible = visible;
            this.parent = parent; this.syncedTo = syncedTo; this.activeSource = activeSource; this.state = state;
            this.grouped = grouped;
        }
        public boolean audio() { return "player".equals(type) || "stereo_pair".equals(type) || "group".equals(type); }
    }
    public static final class Queue {
        public final String id, state;
        public final boolean available;
        public final int items;
        public Queue(String id, boolean available, int items, String state) {
            this.id = id; this.available = available; this.items = items; this.state = state;
        }
    }
    public static final class Snapshot {
        public final Map<String, Player> players = new LinkedHashMap<>();
        public final Map<String, Queue> queues = new LinkedHashMap<>();
        public final Map<String, String> playerMedia = new LinkedHashMap<>(), queueMedia = new LinkedHashMap<>();
        final Map<String, String> providers = new LinkedHashMap<>(), addresses = new LinkedHashMap<>();
        final Map<String, String> diagnosticProtocols = new LinkedHashMap<>();
        final Map<String, Boolean> sendspinOutputs = new LinkedHashMap<>();
        final Map<String, Boolean> zeroGroups = new LinkedHashMap<>();
        public Player resolve(String selector) {
            if (selector == null || selector.trim().isEmpty()) throw new IllegalArgumentException("Choose a player in plugin settings.");
            Player exact = players.get(selector.trim());
            if (exact != null) return exact;
            Player found = null;
            for (Player player : players.values()) if (player.name.equalsIgnoreCase(selector.trim())) {
                if (found != null) throw new IllegalArgumentException("Player name is ambiguous. Select its Music Assistant ID.");
                found = player;
            }
            if (found == null) throw new IllegalArgumentException("Configured player is missing. No fallback player was selected.");
            return found;
        }
        public Player leader(String id) {
            Player player = players.get(id);
            Set<String> visited = new HashSet<>();
            while (player != null) {
                if (!visited.add(player.id)) throw new IllegalArgumentException("Player grouping contains a cycle.");
                Player parent = players.get(player.parent);
                if (parent == null) parent = players.get(player.syncedTo);
                if (parent == null) return player;
                player = parent;
            }
            return null;
        }
        public String queue(String id) {
            Player leader = leader(id);
            return leader == null ? "" : queues.containsKey(leader.activeSource) ? leader.activeSource : leader.id;
        }
        public boolean externalSource(String id) {
            Player leader = leader(id);
            return leader != null && ("playing".equals(leader.state) || "paused".equals(leader.state))
                && !leader.activeSource.isEmpty() && !leader.activeSource.equals(queue(id));
        }
        public String playbackDescription(String id) {
            Player leader = leader(id); if (leader == null) return "Unavailable";
            String media = "";
            if ("playing".equals(leader.state) || "paused".equals(leader.state)) {
                if (!externalSource(id)) media = queueMedia.getOrDefault(queue(id), "");
                if (media.isEmpty()) media = playerMedia.getOrDefault(leader.id, "");
            }
            String state = leader.state.isEmpty() ? "idle" : leader.state;
            return (media.isEmpty() ? "" : media + "\n") + state + (grouped(id) ? " · group queue" : "");
        }
        public boolean grouped(String id) {
            Player p = players.get(id);
            if (p == null) return false;
            if (p.grouped || "group".equals(p.type) || !p.parent.isEmpty() || !p.syncedTo.isEmpty()) return true;
            for (Player other : players.values())
                if (!id.equals(other.id) && (id.equals(other.parent) || id.equals(other.syncedTo))) return true;
            return false;
        }
        public boolean transferable(String sourceId, String targetId) {
            Player source = leader(sourceId), target = players.get(targetId);
            String sourceQueue = queue(sourceId);
            Queue from = queues.get(sourceQueue), to = queues.get(targetId);
            return source != null && source.available && target != null && target.available && target.visible && target.audio()
                && !sourceId.equals(targetId) && !sourceQueue.equals(targetId) && !queue(targetId).equals(sourceQueue)
                && from != null && from.available && from.items > 0 && to != null && to.available;
        }
        public void validateTransfer(String sourceId, String targetId, String expectedQueue) {
            if (!queue(sourceId).equals(expectedQueue) || !transferable(sourceId, targetId))
                throw new IllegalArgumentException("Players or queues changed. Reopen Transfer.");
        }
    }
    public static String node(String value) {
        if (value == null || !value.matches("[a-z0-9_]{1,100}"))
            throw new IllegalArgumentException("This kiosk has not been paired. Open Setup; manual pairing is under Advanced.");
        return value;
    }
    public static boolean immediate(String option) { return "play".equals(option) || "replace".equals(option); }
    public static boolean replacing(String option) { return "replace".equals(option) || "replace_next".equals(option); }
}
