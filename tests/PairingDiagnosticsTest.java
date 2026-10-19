package net.markandrew.kiosk.music;

import org.json.*;

public final class PairingDiagnosticsTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Diagnostic check " + checks); }
    public static void main(String[] args) throws Exception {
        JSONObject roles = new JSONObject().put("admin", new JSONArray().put("*"))
            .put("user", new JSONArray().put("players.control"))
            .put("service", new JSONArray().put("config.players.write"));
        JSONObject user = new JSONObject().put("role", "admin");
        check(PairingDiagnostics.grant(user, roles) == PairingDiagnostics.Grant.GRANTED);
        user.put("role", "user");
        check(PairingDiagnostics.grant(user, roles) == PairingDiagnostics.Grant.DENIED);
        check(PairingDiagnostics.grant(user, roles, "players.control") == PairingDiagnostics.Grant.GRANTED);
        check(PairingDiagnostics.permission(user, PairingDiagnostics.Grant.GRANTED, "players.control").contains("players.control: granted"));
        user.put("role", "service");
        check(PairingDiagnostics.grant(user, roles) == PairingDiagnostics.Grant.GRANTED);
        user.put("role", "missing");
        check(PairingDiagnostics.grant(user, roles) == PairingDiagnostics.Grant.UNKNOWN);
        check(PairingDiagnostics.grant(user, new JSONArray()) == PairingDiagnostics.Grant.UNKNOWN);
        user.put("role", "bad"); roles.put("bad", new JSONArray().put("*").put(42));
        check(PairingDiagnostics.grant(user, roles) == PairingDiagnostics.Grant.UNKNOWN);
        roles.put("bad", new JSONArray().put("config_players_write"));
        check(PairingDiagnostics.grant(user, roles) == PairingDiagnostics.Grant.DENIED);
        String fixture = "disposable-secret-must-not-be-rendered";
        user.put("role", fixture).put("username", fixture).put("token", fixture);
        check(!PairingDiagnostics.permission(user, PairingDiagnostics.Grant.UNKNOWN).contains(fixture));
        check(!PairingDiagnostics.describe(new Exception(fixture)).contains(fixture));
        check(PairingDiagnostics.describe(new java.net.SocketTimeoutException(fixture)).contains("timed out"));
        check(PairingDiagnostics.describe(new PairingDiagnostics.Failure(PairingDiagnostics.Kind.HTTP, 403)).contains("HTTP 403 · permission denied"));
        check(PairingDiagnostics.describe(new PairingDiagnostics.Failure(PairingDiagnostics.Kind.NO_FLOW_ID, 200)).contains("HTTP 200 · setup response missing flow_id"));
        for (PairingDiagnostics.Kind kind : PairingDiagnostics.Kind.values()) {
            PairingDiagnostics.Failure failure = new PairingDiagnostics.Failure(kind, 99999);
            check(!PairingDiagnostics.describe(failure).contains("99999"));
            check(failure.getCause() == null);
        }
        System.out.println("PairingDiagnostics: " + checks + " safe-category / role-scope checks passed");
    }
}
