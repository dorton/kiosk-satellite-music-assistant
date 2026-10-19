package net.markandrew.kiosk.music;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed diagnostic vocabulary: no URL, body, credential or exception message can reach UI. */
final class PairingDiagnostics {
    enum Kind { HTTP, NON_JSON, INVALID_JSON, RESPONSE_SHAPE, NO_FLOW_ID, TIMEOUT, NETWORK, TLS, CANCELLED, TOO_LARGE }
    static final class Failure extends IOException {
        final Kind kind;
        final int http;
        Failure(Kind kind, int http) {
            super(kind.name()); this.kind = kind; this.http = http >= 100 && http <= 599 ? http : 0;
        }
    }
    static String describe(Exception error) {
        if (error instanceof Failure) {
            Failure f = (Failure)error;
            String prefix = f.http == 0 ? "" : "HTTP " + f.http + " · ";
            switch (f.kind) {
                case HTTP: return prefix + (f.http == 403 ? "permission denied" : f.http == 401 ? "authentication required" : "request refused; body withheld");
                case NON_JSON: return prefix + "non-JSON response; body withheld";
                case INVALID_JSON: return prefix + "invalid JSON; body withheld";
                case RESPONSE_SHAPE: return prefix + "unexpected response shape";
                case NO_FLOW_ID: return prefix + "setup response missing flow_id";
                case TIMEOUT: return prefix + "request timed out; outcome may be unknown";
                case TLS: return prefix + "TLS connection failed";
                case CANCELLED: return prefix + "request cancelled";
                case TOO_LARGE: return prefix + "response exceeded size limit";
                default: return prefix + "network / transport failure";
            }
        }
        if (error instanceof SocketTimeoutException) return "request timed out; outcome may be unknown";
        if (error instanceof InterruptedException) return "request cancelled";
        return "local validation / unexpected failure; details withheld";
    }
    enum Grant { GRANTED, DENIED, UNKNOWN }
    static Grant grant(JSONObject user, Object scopes) {
        return grant(user, scopes, "config.players.write");
    }
    static Grant grant(JSONObject user, Object scopes, String required) {
        if (!(scopes instanceof JSONObject) || !(user.opt("role") instanceof String)) return Grant.UNKNOWN;
        JSONArray values = ((JSONObject)scopes).optJSONArray(user.optString("role"));
        if (values == null) return Grant.UNKNOWN;
        for (int i = 0; i < values.length(); i++) {
            if (!(values.opt(i) instanceof String)) return Grant.UNKNOWN;
        }
        for (int i = 0; i < values.length(); i++)
            if ("*".equals(values.optString(i)) || required.equals(values.optString(i))) return Grant.GRANTED;
        return Grant.DENIED;
    }
    static String permission(JSONObject user, Grant grant) {
        return permission(user, grant, "config.players.write");
    }
    static String permission(JSONObject user, Grant grant, String required) {
        String role = user.optString("role");
        if (!("admin".equals(role) || "user".equals(role) || "guest".equals(role) || "service".equals(role))) role = "custom/unknown";
        return "MA role: " + role + " · " + required + ": " + grant.name().toLowerCase(java.util.Locale.ROOT);
    }
}
