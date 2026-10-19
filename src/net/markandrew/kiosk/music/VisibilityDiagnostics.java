package net.markandrew.kiosk.music;

import org.json.JSONArray;
import org.json.JSONObject;

/** Only allowlisted flags and a validated public receiver ID; never raw API responses. */
final class VisibilityDiagnostics {
    private static String flag(JSONObject row, String key) {
        Object value = row.opt(key);
        return value instanceof Boolean ? value.toString() : "unknown";
    }
    static String summary(JSONArray rows, String receiverId) {
        boolean validId = receiverId != null && receiverId.matches("[A-Za-z0-9_-]{43}");
        String own = validId ? "This receiver: not returned by MA (offline or filtered does not prove deletion)"
            : "This receiver: local identity unavailable";
        int unhidden = 0, hidden = 0, unknown = 0, matches = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            boolean isOwn = validId && receiverId.equals(row.opt("player_id"));
            if (isOwn) {
                matches++;
                own = "This receiver: present\nPublic player ID: " + receiverId
                    + "\nhide_in_ui: " + flag(row, "hide_in_ui") + " · private: " + flag(row, "private")
                    + "\navailable: " + flag(row, "available") + " · enabled: " + flag(row, "enabled");
            } else if (Boolean.TRUE.equals(row.opt("private"))) {
                if (Boolean.TRUE.equals(row.opt("hide_in_ui"))) hidden++;
                else if (Boolean.FALSE.equals(row.opt("hide_in_ui"))) unhidden++;
                else unknown++;
            }
        }
        if (matches > 1) own = "This receiver: duplicate MA records; identity needs review";
        return own + "\nOther private players returned by MA: " + unhidden + " unhidden · " + hidden
            + " hidden · " + unknown + " unknown\nRead-only live flags, not a saved-config export. MA account filters still apply.";
    }
}
