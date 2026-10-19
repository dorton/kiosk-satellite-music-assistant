package net.markandrew.kiosk.music;

import org.json.*;

/** Only known secure pairing choices; no guessed forms, unpaired approval or repeated token step. */
final class PairingSteps {
    static JSONObject values(JSONObject step) throws Exception {
        if (!"form".equals(step.optString("type"))) throw new IllegalArgumentException();
        String id = step.optString("step_id"); JSONArray entries = step.optJSONArray("entries");
        if (entries == null) throw new IllegalArgumentException();
        if ("select_method".equals(id) && option(entries, "pairing_method", "token"))
            return new JSONObject().put("pairing_method", "token");
        if (("approve_device".equals(id) || "approve_device_source".equals(id)) && option(entries, "connect_method", "pair"))
            return new JSONObject().put("connect_method", "pair");
        if ("enter_token".equals(id) && entries.length() == 1 && "pairing_token".equals(entries.getJSONObject(0).optString("key")))
            return new JSONObject();
        throw new IllegalArgumentException("Unsupported pairing step");
    }
    private static boolean option(JSONArray entries, String key, String value) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !key.equals(entry.optString("key"))) continue;
            JSONArray options = entry.optJSONArray("options");
            if (options != null) for (int j = 0; j < options.length(); j++) {
                JSONObject option = options.optJSONObject(j);
                if (option != null && value.equals(option.optString("value"))) return true;
            }
        }
        return false;
    }
}
