package net.markandrew.kiosk.music;

import org.json.JSONArray;
import org.json.JSONObject;

/** Account-bound app enrollment. No configuration flow, admin credential or unpaired fallback. */
final class AppPairingFlow {
    interface Port {
        boolean current();
        boolean paired();
        void verifySession() throws Exception;
        JSONArray players() throws Exception;
        boolean claim() throws Exception;
        String privateToken() throws Exception;
        void approve(String token) throws Exception;
        long now();
        void sleep(long millis) throws Exception;
        void status(String message);
    }
    enum Result { PAIRED, REVIEW, CANCELLED }
    private final String clientId;
    private final Port port;
    AppPairingFlow(String clientId, Port port) { this.clientId = clientId; this.port = port; }
    Result run() {
        String stage = "Receiver not verified as an app player on the configured MA instance";
        try {
            current();
            if (port.paired()) return Result.PAIRED;
            port.verifySession(); current();
            boolean found = false;
            for (int i = 0; i < 20; i++) {
                current();
                if (exactPlayer(port.players(), clientId)) { found = true; break; }
                port.sleep(500);
            }
            require(found); current();
            if (port.paired()) return Result.PAIRED;
            stage = "Registration needs review — inspect MA before Retry registration";
            require(port.claim());
            port.verifySession(); current();
            if (port.paired()) return Result.PAIRED;
            stage = "App pairing refused or unconfirmed";
            String token = port.privateToken();
            try {
                require(token != null && !token.isEmpty()); current();
                port.status("Authorising this app receiver privately…");
                port.approve(token); // Single mutation; never automatically replay an uncertain outcome.
            } finally { token = null; }
            port.status("App pairing accepted — awaiting secure device confirmation");
            stage = "App pairing confirmation timed out";
            long deadline = port.now() + 45000;
            while (port.now() < deadline) {
                current();
                if (port.paired()) return Result.PAIRED;
                port.sleep(400);
            }
        } catch (Exception error) {
            if (!port.current()) return Result.CANCELLED;
            if (port.paired()) return Result.PAIRED;
            port.status(stage + " · " + PairingDiagnostics.describe(error) + ". No automatic resubmission.");
            return Result.REVIEW;
        }
        if (!port.current()) return Result.CANCELLED;
        if (port.paired()) return Result.PAIRED;
        port.status(stage + ". Inspect MA before Retry registration. No automatic resubmission.");
        return Result.REVIEW;
    }
    static boolean exactPlayer(JSONArray players, String clientId) {
        require(clientId != null && !clientId.isEmpty());
        int matches = 0;
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.optJSONObject(i);
            if (p != null && clientId.equals(p.optString("player_id"))) {
                // App receivers are native player type; external protocol receivers cannot use this route.
                require("player".equals(p.optString("type"))); matches++;
            }
        }
        require(matches <= 1);
        return matches == 1;
    }
    private void current() { require(port.current()); }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("App pairing stopped"); }
}
