package net.markandrew.kiosk.music;

import java.util.*;

public final class DefaultPlayerPolicyTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Default check " + checks); }
    static void rejects(Runnable run) {
        checks++; try { run.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected refusal " + checks);
    }
    public static void main(String[] args) {
        Map<String, Object> settings = new HashMap<>();
        check(DefaultPlayerPolicy.saved(settings).isEmpty());
        settings.put("localPlayer", "echo"); settings.put("playerMode", "Follow Kiosk Satellite");
        check(DefaultPlayerPolicy.saved(settings).equals("echo"));
        settings.put("defaultPlayer", "old_override"); check(DefaultPlayerPolicy.saved(settings).equals("echo"));
        settings.put("playerMode", "Fixed default player"); check(DefaultPlayerPolicy.saved(settings).equals("old_override"));
        settings.put("defaultPlayer", ""); check(DefaultPlayerPolicy.saved(settings).isEmpty());
        settings.put("localPlayer", ""); settings.put("playerMode", "Follow Kiosk Satellite");
        settings.put("defaultPlayer", "chosen"); check(DefaultPlayerPolicy.saved(settings).equals("chosen"));
        PlayerPolicy.Snapshot s = new PlayerPolicy.Snapshot();
        s.players.put("echo", new PlayerPolicy.Player("echo", "Echo", "player", true, true, "", "", "", "idle"));
        check(DefaultPlayerPolicy.selected(s, "echo").id.equals("echo"));
        rejects(() -> DefaultPlayerPolicy.selected(s, "Echo"));
        rejects(() -> DefaultPlayerPolicy.selected(s, "")); rejects(() -> DefaultPlayerPolicy.selected(s, "missing"));
        rejects(() -> DefaultPlayerPolicy.selected(s, null));
        for (boolean available : new boolean[]{false, true}) {
            s.players.put("bad", new PlayerPolicy.Player("bad", "Bad", "player", available, false, "", "", "", "idle"));
            rejects(() -> DefaultPlayerPolicy.selected(s, "bad"));
        }
        s.players.put("bad", new PlayerPolicy.Player("bad", "Bad", "player", false, true, "", "", "", "idle"));
        rejects(() -> DefaultPlayerPolicy.selected(s, "bad"));
        s.players.put("bad", new PlayerPolicy.Player("bad", "Bad", "protocol", true, true, "", "", "", "idle"));
        rejects(() -> DefaultPlayerPolicy.selected(s, "bad"));
        check(settings.get("defaultPlayer").equals("chosen"));
        settings.put("playerMode","Follow Kiosk Satellite");settings.put("localPlayer","echo");settings.put("kioskNode","obsolete");settings.put("advanced",true);settings.put("receiverName","Keep me");
        Map<String,Object> migrated=DefaultPlayerPolicy.normalized(settings);check(migrated.get("defaultPlayer").equals("echo"));check(migrated.get("playerMode").equals("Fixed default player"));check(migrated.get("receiverName").equals("Keep me"));check(!migrated.containsKey("advanced")&&!migrated.containsKey("kioskNode"));check(settings.get("defaultPlayer").equals("chosen"));check(DefaultPlayerPolicy.normalized(migrated).equals(migrated));
        System.out.println("PASS: " + checks + " saved-default policy checks (no network)");
    }
}
