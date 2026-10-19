package net.markandrew.kiosk.music;

import java.util.*;

/** Native source choices: a local app or another-room name is never a fallback. */
public final class FollowPolicyTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Follow check " + checks); }
    static void rejects(Runnable action, String expected) {
        checks++; try { action.run(); } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(expected)) throw new AssertionError(e.getMessage()); return;
        } throw new AssertionError("Expected refusal " + checks);
    }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> m = new HashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i+1]); return m;
    }
    public static void main(String[] args) {
        PlayerPolicy.Snapshot snapshot = new PlayerPolicy.Snapshot();
        snapshot.players.put("local", new PlayerPolicy.Player("local", "Bedroom 1", "player", true, true, "", "", "", "idle"));
        snapshot.players.put("other", new PlayerPolicy.Player("other", "Bedroom 1", "player", true, true, "", "", "", "idle"));
        snapshot.players.put("offline", new PlayerPolicy.Player("offline", "Offline", "player", false, true, "", "", "", "idle"));
        snapshot.providers.put("local", "sendspin"); snapshot.providers.put("offline", "sendspin");
        snapshot.providers.put("Bedroom 1", "sendspin");
        KioskPlayerBridge.Pick device = new KioskPlayerBridge.Pick("device", "");
        check(FollowPolicy.resolve(device, "local", snapshot).id.equals("local"));
        rejects(() -> FollowPolicy.resolve(device, "", snapshot), "uniquely matched");
        rejects(() -> FollowPolicy.resolve(device, "Bedroom 1", snapshot), "missing");
        rejects(() -> FollowPolicy.resolve(device, "offline", snapshot), "offline");
        rejects(() -> FollowPolicy.resolve(device, "other", snapshot), "Sendspin");
        rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick("device", "media_session"), "local", snapshot), "Android app");
        rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick("device", "unexpected"), "local", snapshot), "Android app");
        check(FollowPolicy.resolve(new KioskPlayerBridge.Pick("music_assistant", "other"), "local", snapshot).id.equals("other"));
        rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick("music_assistant", "Bedroom 1"), "local", snapshot), "missing");
        rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick("music_assistant", "missing"), "local", snapshot), "missing");
        rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick("music_assistant", "offline"), "local", snapshot), "offline");
        for (String source : Arrays.asList("home_assistant", "sonos")) {
            rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick(source, "other"), "local", snapshot), "not verified");
        }
        rejects(() -> FollowPolicy.resolve(new KioskPlayerBridge.Pick("unknown", "other"), "local", snapshot), "unsupported");
        rejects(() -> FollowPolicy.resolve(null, "local", snapshot), "identified");
        rejects(() -> FollowPolicy.resolve(device, "local", null), "unavailable");
        check(FollowPolicy.source(device).equals("This device · Sendspin"));
        check(FollowPolicy.source(new KioskPlayerBridge.Pick("device", "media_session")).contains("Local Media Session"));
        KioskPlayerBridge.State kiosk = new KioskPlayerBridge.State(map("current", map("source", "music_assistant", "id", "other", "name", "Bedroom 1"), "players", Collections.emptyList()));
        check(kiosk.current.name.equals("Bedroom 1"));
        check(FollowPolicy.summary(kiosk, "local", snapshot).contains("Connection: ready"));
        check(FollowPolicy.summary(kiosk, "local", snapshot).contains("Source: Music Assistant"));
        check(FollowPolicy.summary(null, "local", snapshot).contains("not verified"));
        List<DiscoveryPolicy.Choice> nodes = Arrays.asList(new DiscoveryPolicy.Choice("a", "Same name"), new DiscoveryPolicy.Choice("b", "Same name"));
        Map<String, String> addresses = new HashMap<>(); addresses.put("a", "192.0.2.1"); addresses.put("b", "192.0.2.2");
        check(DiscoveryPolicy.uniqueNode(nodes, addresses, "192.0.2.2").equals("b"));
        check(DiscoveryPolicy.uniqueNode(nodes, addresses, "192.0.2.3").isEmpty());
        check(DiscoveryPolicy.uniqueNode(nodes, addresses, "").isEmpty());
        check(DiscoveryPolicy.uniqueNode(nodes, addresses, null).isEmpty());
        addresses.put("a", "192.0.2.2"); check(DiscoveryPolicy.uniqueNode(nodes, addresses, "192.0.2.2").isEmpty());
        addresses.clear(); addresses.put("not_in_ha", "192.0.2.2"); check(DiscoveryPolicy.uniqueNode(nodes, addresses, "192.0.2.2").isEmpty());
        snapshot.providers.put("local", "universal_player"); snapshot.sendspinOutputs.put("local", true);
        check(FollowPolicy.resolve(device, "local", snapshot).id.equals("local"));
        snapshot.sendspinOutputs.put("local", false);
        rejects(() -> FollowPolicy.resolve(device, "local", snapshot), "Sendspin");
        System.out.println("PASS: " + checks + " native follow/identity checks (no network)");
    }
}
