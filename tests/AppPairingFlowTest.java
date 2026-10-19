package net.markandrew.kiosk.music;

import java.util.*;
import org.json.*;

/** Pure full-flow tests, no live receiver or MA access. */
public final class AppPairingFlowTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("App pairing check " + checks); }
    static JSONObject player(String id, String type) throws Exception {
        return new JSONObject().put("player_id", id).put("type", type);
    }
    static class Fake implements AppPairingFlow.Port {
        boolean current = true, paired, reserved, autoConfirm = true, failVerify, cancelOnToken;
        Exception failure;
        int claims, reads, submissions, verifies;
        long time;
        JSONArray rows;
        List<String> messages = new ArrayList<>();
        Fake() throws Exception { rows = new JSONArray().put(player("own", "player")); }
        public boolean current() { return current; }
        public boolean paired() { return paired; }
        public void verifySession() throws Exception { verifies++; if (failVerify) throw new Exception("private-account-fixture"); }
        public JSONArray players() { return rows; }
        public boolean claim() { claims++; if (reserved) return false; reserved = true; return true; }
        public String privateToken() { reads++; if (cancelOnToken) current = false; return "private-token-fixture"; }
        public void approve(String token) throws Exception {
            check(reserved && verifies == 2 && current && token.equals("private-token-fixture"));
            submissions++; if (failure != null) throw failure; if (autoConfirm) paired = true;
        }
        public long now() { return time; }
        public void sleep(long millis) { time += millis; }
        public void status(String message) { messages.add(message); }
        AppPairingFlow.Result run() { return new AppPairingFlow("own", this).run(); }
        void safe() { for (String m : messages) check(!m.contains("private-token-fixture") && !m.contains("private-account-fixture")); }
    }
    public static void main(String[] args) throws Exception {
        Fake good = new Fake(); check(good.run() == AppPairingFlow.Result.PAIRED);
        check(good.reads == 1 && good.submissions == 1 && good.claims == 1); good.safe();
        Fake paired = new Fake(); paired.paired = true; check(paired.run() == AppPairingFlow.Result.PAIRED);
        check(paired.reads == 0 && paired.claims == 0);
        for (Exception failure : new Exception[]{new PairingDiagnostics.Failure(PairingDiagnostics.Kind.HTTP, 403),
                new PairingDiagnostics.Failure(PairingDiagnostics.Kind.TIMEOUT, 0), new Exception("private-token-fixture")}) {
            Fake bad = new Fake(); bad.failure = failure;
            check(bad.run() == AppPairingFlow.Result.REVIEW); check(bad.submissions == 1 && bad.reads == 1);
            check(bad.run() == AppPairingFlow.Result.REVIEW); check(bad.submissions == 1 && bad.reads == 1); bad.safe();
        }
        Fake oldMarker = new Fake(); oldMarker.reserved = true;
        check(oldMarker.run() == AppPairingFlow.Result.REVIEW); check(oldMarker.submissions == 0 && oldMarker.reads == 0);
        Fake timeout = new Fake(); timeout.autoConfirm = false;
        check(timeout.run() == AppPairingFlow.Result.REVIEW && timeout.time >= 45000);
        check(timeout.submissions == 1); timeout.safe();
        Fake cancel = new Fake(); cancel.cancelOnToken = true;
        check(cancel.run() == AppPairingFlow.Result.CANCELLED); check(cancel.submissions == 0);
        Fake accountChanged = new Fake(); accountChanged.failVerify = true;
        check(accountChanged.run() == AppPairingFlow.Result.REVIEW); check(accountChanged.claims == 0 && accountChanged.reads == 0); accountChanged.safe();
        Fake absent = new Fake(); absent.rows = new JSONArray().put(player("other", "player"));
        check(absent.run() == AppPairingFlow.Result.REVIEW); check(absent.claims == 0 && absent.submissions == 0);
        for (JSONArray rows : new JSONArray[]{new JSONArray().put(player("own", "protocol")),
                new JSONArray().put(player("own", "player")).put(player("own", "player"))}) {
            Fake wrong = new Fake(); wrong.rows = rows;
            check(wrong.run() == AppPairingFlow.Result.REVIEW); check(wrong.claims == 0 && wrong.submissions == 0);
        }
        System.out.println("AppPairingFlow: " + checks + " account-bound / no-replay checks passed (mocked, no live pairing)");
    }
}
