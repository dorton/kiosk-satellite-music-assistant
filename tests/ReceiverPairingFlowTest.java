package net.markandrew.kiosk.music;

import java.util.*;
import org.json.*;

/** Complete deterministic controller traces, using disposable fixture material only. */
public final class ReceiverPairingFlowTest {
    private static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Pairing flow check " + checks); }
    static JSONObject step(String type, String id) throws Exception {
        return new JSONObject().put("flow_id", "flow-1").put("type", type).put("step_id", id);
    }
    static JSONObject form(String id) throws Exception {
        JSONObject entry = new JSONObject();
        if ("select_method".equals(id)) entry.put("key", "pairing_method").put("options", new JSONArray().put(new JSONObject().put("value", "token")));
        else if (id.startsWith("approve_device")) entry.put("key", "connect_method").put("options", new JSONArray().put(new JSONObject().put("value", "pair")));
        else entry.put("key", "pairing_token");
        return step("form", id).put("entries", new JSONArray().put(entry));
    }
    static final class Fake implements ReceiverPairingFlow.Port {
        final Deque<JSONObject> steps = new ArrayDeque<>();
        final List<String> events = new ArrayList<>(), messages = new ArrayList<>();
        final List<JSONObject> retainedValues = new ArrayList<>();
        JSONArray registered;
        JSONObject present;
        boolean live = true, paired, claimed, denySetup, failTokenSubmit, pairedOnSubmit, stopAfterToken, failVerify, changeMAAfterSubmission;
        int verifies, tokenReads, setupCalls, submissions, tokenSubmissions, polls, reads;
        long time;
        // Deliberately different: these identities must NEVER be compared to each other.
        String maInstance = "ma-instance-uuid", expectedMA = maInstance;
        String transportIdentity = "sendspin-cryptographic-identity";
        Fake() throws Exception {
            registered = new JSONArray().put(new JSONObject().put("player_id", "own-native-id").put("type", "protocol"));
            steps.add(form("select_method")); steps.add(form("enter_token")); steps.add(step("progress", "pairing")); steps.add(step("finish", "done"));
        }
        public boolean current() { return live; }
        public boolean paired() { return paired; }
        public void verifySession() throws Exception {
            verifies++; events.add("verify");
            if (failVerify || !expectedMA.equals(maInstance)) throw new Exception("fixture identity / account changed");
        }
        public JSONArray players() { reads++; events.add("players"); return registered; }
        public boolean claim() { events.add("claim"); if (claimed) return false; claimed = true; return true; }
        public String privateToken() { tokenReads++; events.add("private-token"); if (stopAfterToken) live = false; return "disposable-pairing-fixture"; }
        private JSONObject next() throws Exception { present = steps.isEmpty() ? step("progress", "waiting") : steps.removeFirst(); return present; }
        public JSONObject setup() throws Exception {
            setupCalls++; events.add("setup"); if (denySetup) throw new Exception("fixture permission denied"); return next();
        }
        public JSONObject progress(String flow) throws Exception { check("flow-1".equals(flow)); polls++; events.add("progress"); return next(); }
        public JSONObject submit(String flow, JSONObject values) throws Exception {
            check("flow-1".equals(flow)); submissions++; retainedValues.add(values);
            String id = present.getString("step_id"); events.add("submit-" + id);
            if ("enter_token".equals(id)) {
                tokenSubmissions++; check(values.length() == 1 && "disposable-pairing-fixture".equals(values.optString("pairing_token")));
                if (failTokenSubmit) throw new Exception("disposable-pairing-fixture");
                if (pairedOnSubmit) paired = true;
            } else if ("select_method".equals(id)) check(values.length() == 1 && "token".equals(values.optString("pairing_method")));
            else check(values.length() == 1 && "pair".equals(values.optString("connect_method")));
            if (changeMAAfterSubmission) maInstance = "changed-during-setup";
            return next();
        }
        public long now() { return time; }
        public void sleep(long ms) { time += ms; }
        public void status(String message) { messages.add(message); }
        ReceiverPairingFlow.Result run() { return new ReceiverPairingFlow("own-native-id", this).run(); }
        void noLeakedToken() {
            for (JSONObject values : retainedValues) check(!values.has("pairing_token"));
            for (String message : messages) check(!message.contains("disposable-pairing-fixture"));
        }
    }
    public static void main(String[] args) throws Exception {
        Fake success = new Fake(); success.pairedOnSubmit = true;
        check(!success.maInstance.equals(success.transportIdentity));
        check(success.run() == ReceiverPairingFlow.Result.PAIRED);
        check(success.tokenReads == 1 && success.tokenSubmissions == 1 && success.setupCalls == 1);
        check(success.events.indexOf("players") < success.events.indexOf("setup"));
        check(success.events.indexOf("claim") < success.events.indexOf("setup")); success.noLeakedToken();

        Fake pending = new Fake();
        check(pending.run() == ReceiverPairingFlow.Result.SUBMITTED);
        check(pending.polls == 1 && pending.tokenReads == 1 && !pending.paired);
        check(pending.messages.get(pending.messages.size()-1).contains("awaiting device confirmation")); pending.noLeakedToken();

        Fake paired = new Fake(); paired.paired = true;
        check(paired.run() == ReceiverPairingFlow.Result.PAIRED);
        check(paired.setupCalls == 0 && paired.tokenReads == 0 && paired.reads == 0);

        for (String approve : new String[]{"approve_device", "approve_device_source"}) {
            Fake device = new Fake(); device.steps.addFirst(form(approve));
            check(device.run() == ReceiverPairingFlow.Result.SUBMITTED);
            check(device.submissions == 3 && device.tokenReads == 1); device.noLeakedToken();
        }
        Fake denied = new Fake(); denied.denySetup = true;
        check(denied.run() == ReceiverPairingFlow.Result.REVIEW);
        check(denied.tokenReads == 0 && denied.submissions == 0);
        check(denied.run() == ReceiverPairingFlow.Result.REVIEW && denied.setupCalls == 1);

        Fake unknown = new Fake(); unknown.steps.clear(); unknown.steps.add(form("unknown"));
        check(unknown.run() == ReceiverPairingFlow.Result.REVIEW && unknown.tokenReads == 0 && unknown.submissions == 0);

        Fake unpaired = new Fake(); unpaired.steps.clear();
        unpaired.steps.add(form("approve_device").put("entries", new JSONArray().put(new JSONObject().put("key", "connect_method")
            .put("options", new JSONArray().put(new JSONObject().put("value", "allow"))))));
        check(unpaired.run() == ReceiverPairingFlow.Result.REVIEW && unpaired.submissions == 0 && unpaired.tokenReads == 0);

        Fake repeat = new Fake(); repeat.steps.clear(); repeat.steps.add(form("enter_token")); repeat.steps.add(form("enter_token"));
        check(repeat.run() == ReceiverPairingFlow.Result.REVIEW && repeat.tokenReads == 1 && repeat.tokenSubmissions == 1);
        check(repeat.run() == ReceiverPairingFlow.Result.REVIEW && repeat.setupCalls == 1); repeat.noLeakedToken();

        Fake uncertain = new Fake(); uncertain.failTokenSubmit = true;
        check(uncertain.run() == ReceiverPairingFlow.Result.REVIEW && uncertain.tokenSubmissions == 1);
        check(uncertain.run() == ReceiverPairingFlow.Result.REVIEW && uncertain.tokenSubmissions == 1); uncertain.noLeakedToken();

        Fake stopped = new Fake(); stopped.stopAfterToken = true;
        check(stopped.run() == ReceiverPairingFlow.Result.CANCELLED && stopped.tokenReads == 1 && stopped.tokenSubmissions == 0);

        Fake wrongMA = new Fake(); wrongMA.maInstance = "different-ma-instance";
        check(wrongMA.run() == ReceiverPairingFlow.Result.REVIEW && wrongMA.setupCalls == 0 && wrongMA.tokenReads == 0);

        Fake account = new Fake(); account.failVerify = true;
        check(account.run() == ReceiverPairingFlow.Result.REVIEW && account.setupCalls == 0 && account.tokenReads == 0);

        Fake changedMA = new Fake(); changedMA.changeMAAfterSubmission = true;
        check(changedMA.run() == ReceiverPairingFlow.Result.REVIEW && changedMA.submissions == 1 && changedMA.tokenReads == 0);

        Fake wrapper = new Fake(); wrapper.registered.getJSONObject(0).put("type", "player");
        check(wrapper.run() == ReceiverPairingFlow.Result.REVIEW && wrapper.setupCalls == 0);

        Fake wrongId = new Fake(); wrongId.registered.getJSONObject(0).put("player_id", "someone-else");
        check(wrongId.run() == ReceiverPairingFlow.Result.REVIEW && wrongId.reads == 20 && wrongId.setupCalls == 0);

        Fake duplicate = new Fake(); duplicate.registered.put(new JSONObject(duplicate.registered.getJSONObject(0).toString()));
        check(duplicate.run() == ReceiverPairingFlow.Result.REVIEW && duplicate.setupCalls == 0);

        Fake changedFlow = new Fake(); changedFlow.steps.clear();
        changedFlow.steps.add(form("select_method"));
        changedFlow.steps.add(form("enter_token").put("flow_id", "another"));
        check(changedFlow.run() == ReceiverPairingFlow.Result.REVIEW && changedFlow.tokenReads == 0);

        Fake errors = new Fake(); errors.steps.clear(); errors.steps.add(form("enter_token").put("errors", new JSONObject().put("base", "rejected")));
        check(errors.run() == ReceiverPairingFlow.Result.REVIEW && errors.tokenReads == 0 && errors.submissions == 0);

        Fake timeout = new Fake(); timeout.steps.clear(); timeout.steps.add(step("progress", "waiting"));
        check(timeout.run() == ReceiverPairingFlow.Result.REVIEW && timeout.time >= 45000 && timeout.tokenReads == 0);
        Fake missingFlow = new Fake(); missingFlow.steps.clear(); missingFlow.steps.add(new JSONObject().put("type", "form"));
        check(missingFlow.run() == ReceiverPairingFlow.Result.REVIEW && missingFlow.tokenReads == 0);
        check(missingFlow.messages.get(missingFlow.messages.size()-1).contains("HTTP 200 · setup response missing flow_id"));
        System.out.println("ReceiverPairingFlow: " + checks + " full-sequence checks passed (mocked MA/receiver, no live pairing)");
    }
}
