import net.markandrew.kiosk.music.PlayerPolicy;
import net.markandrew.kiosk.music.IngressPolicy;

/** Behaviour tests for the original card's player and transfer policy port. */
public final class PlayerPolicyTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected refusal " + checks); }
    private static PlayerPolicy.Player player(String id, String name, String group, String source, boolean available) {
        return new PlayerPolicy.Player(id, name, "player", available, true, group, "", source, "playing");
    }
    public static void main(String[] args) {
        Object jsonNull = new Object() { public String toString() { return "null"; } };
        check(PlayerPolicy.reference(null, jsonNull).isEmpty());
        check(PlayerPolicy.reference(jsonNull, jsonNull).isEmpty());
        check(PlayerPolicy.reference("", jsonNull).isEmpty());
        check(PlayerPolicy.reference("leader", jsonNull).equals("leader"));
        check(PlayerPolicy.reference("null", jsonNull).equals("null"));
        rejects(() -> PlayerPolicy.reference(Boolean.FALSE, jsonNull));
        rejects(() -> PlayerPolicy.reference(42, jsonNull));
        rejects(() -> PlayerPolicy.reference(new Object(), jsonNull));
        PlayerPolicy.Snapshot standalone = new PlayerPolicy.Snapshot();
        standalone.players.put("solo", new PlayerPolicy.Player("solo", "Solo", "player", true, true,
            PlayerPolicy.reference(jsonNull, jsonNull), PlayerPolicy.reference(jsonNull, jsonNull),
            PlayerPolicy.reference(jsonNull, jsonNull), "idle"));
        check(!standalone.grouped("solo")); check(standalone.leader("solo").id.equals("solo"));
        check(standalone.queue("solo").equals("solo")); check(!standalone.externalSource("solo"));
        standalone.players.put("member", new PlayerPolicy.Player("member", "Member", "player", true, true,
            PlayerPolicy.reference(null, jsonNull), PlayerPolicy.reference("solo", jsonNull), "", "idle"));
        check(standalone.grouped("member")); check(standalone.grouped("solo"));
        check(standalone.leader("member").id.equals("solo"));
        PlayerPolicy.Snapshot s = new PlayerPolicy.Snapshot();
        s.players.put("local", player("local", "Local", "", "local", true));
        s.players.put("kitchen", player("kitchen", "Kitchen", "", "kitchen", true));
        s.queues.put("local", new PlayerPolicy.Queue("local", true, 12, "playing"));
        s.queues.put("kitchen", new PlayerPolicy.Queue("kitchen", true, 0, "idle"));
        check(s.resolve("local").id.equals("local")); check(s.resolve(" LOCAL ").id.equals("local"));
        rejects(() -> s.resolve("missing")); rejects(() -> s.resolve("")); rejects(() -> s.resolve(null));
        check(s.queue("local").equals("local")); check(!s.externalSource("local"));
        check(s.transferable("local", "kitchen")); check(!s.transferable("local", "local"));
        check(!s.transferable("kitchen", "local"));
        s.validateTransfer("local", "kitchen", "local");
        rejects(() -> s.validateTransfer("local", "kitchen", "old"));
        s.players.put("duplicate", player("duplicate", "Local", "", "", true));
        rejects(() -> s.resolve("LOCAL")); check(s.resolve("local").id.equals("local")); s.players.remove("duplicate");
        s.players.put("local", player("local", "Local", "", "spotify_connect", true));
        check(s.externalSource("local"));
        s.players.put("local", player("local", "Local", "", "local", false));
        check(!s.transferable("local", "kitchen"));
        s.players.put("local", player("local", "Local", "", "local", true));
        s.players.put("kitchen", player("kitchen", "Kitchen", "local", "kitchen", true));
        check(s.leader("kitchen").id.equals("local")); check(s.queue("kitchen").equals("local"));
        check(s.grouped("local")); check(s.grouped("kitchen"));
        check(!s.transferable("local", "kitchen"));
        s.players.put("local", player("local", "Local", "kitchen", "local", true));
        rejects(() -> s.leader("local"));
        s.players.put("local", player("local", "Local", "", "local", true));
        s.players.put("kitchen", player("kitchen", "Kitchen", "", "kitchen", true));
        check(!s.grouped("local")); check(!s.grouped("kitchen"));
        s.queues.put("local", new PlayerPolicy.Queue("local", true, 0, "paused"));
        check(!s.transferable("local", "kitchen"));
        rejects(() -> s.validateTransfer("local", "kitchen", "local"));
        s.queues.put("local", new PlayerPolicy.Queue("local", true, 8, "paused"));
        check(s.transferable("local", "kitchen"));
        s.queues.put("kitchen", new PlayerPolicy.Queue("kitchen", false, 0, "idle"));
        check(!s.transferable("local", "kitchen"));
        check(PlayerPolicy.node("kiosk_satellite_cb23b5").equals("kiosk_satellite_cb23b5"));
        for (String bad : new String[] {"", "esphome.node", "node-name", "../node", "NODE"}) rejects(() -> PlayerPolicy.node(bad));
        for (String option : new String[] {"play", "next", "add", "replace", "replace_next"})
            IngressPolicy.playback("library://track/123", option);
        rejects(() -> IngressPolicy.playback("library://track/123", "clear"));
        check(PlayerPolicy.immediate("play")); check(PlayerPolicy.immediate("replace"));
        check(!PlayerPolicy.immediate("next")); check(!PlayerPolicy.immediate("replace_next")); check(!PlayerPolicy.immediate("add"));
        check(PlayerPolicy.replacing("replace")); check(PlayerPolicy.replacing("replace_next")); check(!PlayerPolicy.replacing("play"));
        System.out.println("PASS: " + checks + " player/queue policy checks (no network)");
    }
}
