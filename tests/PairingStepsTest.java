package net.markandrew.kiosk.music;

import org.json.*;

public final class PairingStepsTest {
    static JSONObject form(String id, String key, String option) throws Exception {
        JSONObject entry = new JSONObject().put("key", key);
        if (option != null) entry.put("options", new JSONArray().put(new JSONObject().put("value", option)));
        return new JSONObject().put("type", "form").put("step_id", id).put("entries", new JSONArray().put(entry));
    }
    static void rejects(JSONObject step) throws Exception {
        try { PairingSteps.values(step); throw new AssertionError("Accepted unsafe / unknown form"); }
        catch (IllegalArgumentException expected) {}
    }
    public static void main(String[] args) throws Exception {
        if (!"token".equals(PairingSteps.values(form("select_method", "pairing_method", "token")).getString("pairing_method"))) throw new AssertionError();
        if (!"pair".equals(PairingSteps.values(form("approve_device", "connect_method", "pair")).getString("connect_method"))) throw new AssertionError();
        if (PairingSteps.values(form("enter_token", "pairing_token", null)).length() != 0) throw new AssertionError();
        rejects(form("approve_device", "connect_method", "allow"));
        rejects(form("select_method", "pairing_method", "code"));
        rejects(form("unknown", "pairing_token", null));
        rejects(form("enter_token", "password", null));
        rejects(new JSONObject().put("type", "finish"));
        System.out.println("PairingSteps: 8 secure-flow checks passed");
    }
}
