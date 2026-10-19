package net.markandrew.kiosk.music;

import org.json.*;

public final class VisibilityDiagnosticsTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Visibility diagnostics " + checks); }
    public static void main(String[] args) throws Exception {
        String id = "H--Zl1-sQ6m6TCF896SYSZjAIn_GClNIw-Kq7GhBW00";
        String secret = "private-body-fixture";
        JSONObject own = new JSONObject().put("player_id", id).put("hide_in_ui", false).put("private", true)
            .put("available", true).put("enabled", true).put("name", secret).put("token", secret);
        JSONArray rows = new JSONArray().put(own)
            .put(new JSONObject().put("player_id", "other").put("private", true).put("hide_in_ui", false))
            .put(new JSONObject().put("player_id", "hidden").put("private", true).put("hide_in_ui", true));
        String text = VisibilityDiagnostics.summary(rows, id);
        check(text.contains("This receiver: present") && text.contains(id));
        check(text.contains("hide_in_ui: false · private: true"));
        check(text.contains("1 unhidden · 1 hidden")); check(!text.contains(secret));
        own.put("available", false); text = VisibilityDiagnostics.summary(rows, id);
        check(text.contains("available: false") && text.contains("hide_in_ui: false"));
        own.put("hide_in_ui", true); check(VisibilityDiagnostics.summary(rows,id).contains("hide_in_ui: true"));
        own.put("hide_in_ui", secret); text = VisibilityDiagnostics.summary(rows,id);
        check(text.contains("hide_in_ui: unknown") && !text.contains(secret));
        check(VisibilityDiagnostics.summary(new JSONArray(), id).contains("does not prove deletion"));
        check(VisibilityDiagnostics.summary(rows, secret).contains("identity unavailable"));
        check(!VisibilityDiagnostics.summary(rows, secret).contains(secret));
        rows.put(own); check(VisibilityDiagnostics.summary(rows,id).contains("duplicate MA records"));
        System.out.println("VisibilityDiagnostics: " + checks + " live flags / safe output checks passed");
    }
}
