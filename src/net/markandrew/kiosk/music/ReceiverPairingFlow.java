package net.markandrew.kiosk.music;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-equivalent own-receiver setup, independent of Android so the full flow is testable. */
final class ReceiverPairingFlow {
    interface Port {
        boolean current();
        boolean paired();
        void verifySession() throws Exception;
        JSONArray players() throws Exception;
        boolean claim() throws Exception;
        String privateToken() throws Exception;
        JSONObject setup() throws Exception;
        JSONObject progress(String flow) throws Exception;
        JSONObject submit(String flow, JSONObject values) throws Exception;
        long now();
        void sleep(long millis) throws Exception;
        void status(String message);
    }
    enum Result { PAIRED, SUBMITTED, REVIEW, CANCELLED }
    private final String clientId;
    private final Port port;
    ReceiverPairingFlow(String clientId, Port port) { this.clientId = clientId; this.port = port; }

    Result run() {
        String failure = "Receiver not verified on the configured MA instance — check Player settings";
        boolean claimed = false;
        String detail = "";
        try {
            current();
            if (port.paired()) return Result.PAIRED;
            port.verifySession(); current();
            boolean found = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                current();
                found = exactPlayer(port.players(), clientId);
                if (found) break;
                port.sleep(500);
            }
            require(found); current();
            if (port.paired()) return Result.PAIRED;
            failure = "Registration needs review — inspect MA before Retry registration";
            // Keep the plugin's persistent no-replay guard across automatic reconnects.
            require(port.claim()); claimed = true;
            port.verifySession(); current();
            failure = "MA setup refused or unconfirmed — check player-configuration permission before retrying";
            JSONObject step = port.setup();
            String flow = step.optString("flow_id");
            if (flow.isEmpty()) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.NO_FLOW_ID, 200);
            long deadline = port.now() + 45000;
            Set<String> submitted = new HashSet<>();
            while (port.now() < deadline) {
                current();
                if (port.paired()) return Result.PAIRED;
                failure = "Pairing flow rejected or unsupported — inspect MA before Retry registration";
                require(flow.equals(step.optString("flow_id")));
                JSONObject errors = step.optJSONObject("errors");
                require(errors == null || errors.length() == 0);
                String type = step.optString("type");
                if ("finish".equals(type)) {
                    port.status("Pairing submitted — awaiting device confirmation");
                    return Result.SUBMITTED;
                }
                if ("progress".equals(type)) {
                    port.sleep(400); current();
                    step = port.progress(flow);
                    continue;
                }
                JSONObject values = PairingSteps.values(step);
                String id = step.optString("step_id");
                require(submitted.add(id)); // Includes a once-only enter_token step.
                port.verifySession(); current();
                if (port.paired()) return Result.PAIRED;
                try {
                    if ("enter_token".equals(id)) {
                        values.put("pairing_token", port.privateToken());
                        current();
                        failure = "Token submission unconfirmed — inspect MA before retrying; token was not replayed";
                        port.status("Submitting receiver token privately…");
                    } else failure = "Pairing step unconfirmed — inspect MA before Retry registration";
                    step = port.submit(flow, values);
                    if ("enter_token".equals(id)) port.status("Token submitted — waiting for secure pairing confirmation");
                } finally { values.remove("pairing_token"); }
            }
            failure = "Pairing confirmation timed out — inspect MA before Retry registration";
        } catch (Exception error) {
            detail = PairingDiagnostics.describe(error);
            // Never expose API exceptions/forms: they may contain the submitted token.
        }
        if (!port.current()) return Result.CANCELLED;
        if (port.paired()) return Result.PAIRED;
        port.status(failure + (detail.isEmpty() ? "" : " · " + detail) + (claimed ? ". No automatic resubmission." : ""));
        return Result.REVIEW;
    }
    static boolean exactPlayer(JSONArray players, String id) {
        int matches = 0;
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.optJSONObject(i);
            if (p != null && id.equals(p.optString("player_id"))) {
                require("protocol".equals(p.optString("type"))); matches++;
            }
        }
        require(matches <= 1);
        return matches == 1;
    }
    private void current() { require(port.current()); }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("Pairing stopped"); }
}
