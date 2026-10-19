package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.PluginHost;

/** Mocked HA bridge protocol tests. No HA, HTTP, Android or playback. */
public final class KioskPlayerBridgeTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Bridge check " + checks); }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> m = new HashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i+1]); return m;
    }
    static Map<String, Object> state(String source, String id) {
        return map("current", map("source", source, "id", id), "players", Arrays.asList(
            map("source", "music_assistant", "id", "kitchen", "available", true),
            map("source", "music_assistant", "id", "offline", "available", false),
            map("source", "home_assistant", "id", "media_player.other", "available", true)));
    }
    static final class Host implements PluginHost {
        final List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Object> response = state("device", "");
        boolean fail, defer, change = true;
        CommandCallback pending;
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            calls.add(message);
            if (defer) { pending = callback; return; }
            if (fail) { callback.onResult(false, null, "redacted"); return; }
            if (((String) message.get("service")).endsWith("_media_player_set") && change) {
                Map<?, ?> data = (Map<?, ?>) message.get("service_data");
                response = state((String) data.get("source"), (String) data.get("player"));
            }
            callback.onResult(true, map("response", response), null);
        }
    }
    public static void main(String[] args) {
        KioskPlayerBridge.State state = new KioskPlayerBridge.State(state("device", ""));
        check(state.current.same(new KioskPlayerBridge.Pick("device", "")));
        check(state.forPlayer("local", "local").source.equals("device"));
        check(state.forPlayer("kitchen", "local").id.equals("kitchen"));
        for (String id : Arrays.asList("offline", "media_player.other", "missing")) {
            boolean refused = false; try { state.forPlayer(id, "local"); } catch (IllegalArgumentException e) { refused = true; } check(refused);
        }
        Host host = new Host(); KioskPlayerBridge bridge = new KioskPlayerBridge(host);
        AtomicBoolean done = new AtomicBoolean(), failed = new AtomicBoolean();
        bridge.follow("test_kiosk", new KioskPlayerBridge.Pick("music_assistant", "kitchen"), () -> true, () -> done.set(true), e -> failed.set(true));
        check(done.get() && !failed.get()); check(host.calls.size() == 2);
        check(host.calls.get(0).get("service").equals("test_kiosk_media_player_set"));
        check(host.calls.get(1).get("service").equals("test_kiosk_media_player_list"));
        check(Boolean.TRUE.equals(host.calls.get(0).get("return_response")));
        host.response = state("device", ""); host.change = false; done.set(false);
        bridge.follow("test_kiosk", new KioskPlayerBridge.Pick("music_assistant", "kitchen"), () -> true, () -> done.set(true), e -> failed.set(true));
        check(failed.get() && !done.get());
        host.fail = true; host.calls.clear(); failed.set(false);
        bridge.follow("test_kiosk", new KioskPlayerBridge.Pick("device", ""), () -> true, () -> done.set(true), e -> failed.set(true));
        check(failed.get()); check(host.calls.size() == 1);
        host.calls.clear(); failed.set(false);
        bridge.read("../bad", () -> true, s -> done.set(true), e -> failed.set(true));
        check(failed.get() && host.calls.isEmpty());
        host.fail = false; host.defer = true; failed.set(false); done.set(false);
        AtomicBoolean current = new AtomicBoolean(true);
        bridge.read("test_kiosk", current::get, s -> done.set(true), e -> failed.set(true));
        current.set(false); host.pending.onResult(true, map("response", state("device", "")), null);
        check(!done.get() && !failed.get());
        System.out.println("PASS: " + checks + " mocked Kiosk bridge checks (no network)");
    }
}
