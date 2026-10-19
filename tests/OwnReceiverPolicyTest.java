package net.markandrew.kiosk.music;

import java.util.*;

public final class OwnReceiverPolicyTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Own receiver policy " + checks); }
    public static void main(String[] args) {
        String id = "H--Zl1-sQ6m6TCF896SYSZjAIn_GClNIw-Kq7GhBW00";
        check(!OwnReceiverPolicy.controllerOnly("",id));check(!OwnReceiverPolicy.controllerOnly(id,id));
        check(OwnReceiverPolicy.controllerOnly("soundbar",id));check(OwnReceiverPolicy.controllerOnly("soundbar",""));
        check(OwnReceiverPolicy.controllerOnly(id,""));
        Map<String,Object> state = new HashMap<>();
        state.put("clientId", id);
        for (String key : Arrays.asList("running", "connected", "paired")) state.put(key, true);
        check(id.equals(OwnReceiverPolicy.id(state, true, "ma", "ma")));
        check(OwnReceiverPolicy.id(state, false, "ma", "ma").isEmpty());
        check(OwnReceiverPolicy.id(state, true, "other", "ma").isEmpty());
        check(OwnReceiverPolicy.id(state, true, "", "ma").isEmpty());
        check(OwnReceiverPolicy.id(null, true, "ma", "ma").isEmpty());
        for (String key : Arrays.asList("running", "connected", "paired")) {
            state.put(key, false); check(OwnReceiverPolicy.id(state, true, "ma", "ma").isEmpty()); state.put(key, true);
        }
        for (Object invalid : new Object[]{"", "null", "same-name", 42}) {
            state.put("clientId", invalid); check(OwnReceiverPolicy.id(state, true, "ma", "ma").isEmpty());
        }
        check(OwnReceiverPolicy.selectable(id, "player", id, true));
        check(!OwnReceiverPolicy.selectable("other", "player", id, true));
        check(!OwnReceiverPolicy.selectable(id, "protocol", id, true));
        check(!OwnReceiverPolicy.selectable(id, "player", "", true));
        check(!OwnReceiverPolicy.selectable("", "player", "", true));
        check(OwnReceiverPolicy.selectable("speaker", "player", "", false));
        System.out.println("OwnReceiverPolicy: " + checks + " exact identity / secure state / instance checks passed");
    }
}
