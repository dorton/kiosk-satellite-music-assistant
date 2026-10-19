package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.PluginHost;

/** Real discovery bridge logic, mocked SDK/HTTP: never contacts HA or plays music. */
public final class DiscoveryAccessTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Discovery bridge check " + checks); }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> m = new HashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i+1]); return m;
    }
    static final class InlineExecutor extends AbstractExecutorService {
        public void shutdown() {} public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        public void execute(Runnable work) { work.run(); }
    }
    static final class Host implements PluginHost {
        final List<String> calls = new ArrayList<>();
        boolean denied, changedOrigin, changedUser, defer, panelDenied;
        String signedPath = "/api/services?authSig=test.signature";
        int origins, identities;
        int ipReads;
        boolean changedIp;
        CommandCallback pending;
        public void executeCommand(String command, Map<String, Object> values, CommandCallback callback) {
            if (command.equals("getDashboardState")) callback.onResult(true,
                map("homeAssistantUrl", ++origins > 1 && changedOrigin ? "https://other.example/" : "https://ha.example/"), null);
            else if (command.equals("getDeviceInfo")) callback.onResult(true,
                map("ip", ++ipReads > 1 && changedIp ? "192.0.2.11" : "192.0.2.10"), null);
            else throw new AssertionError("Unexpected SDK read");
        }
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            String type = (String) message.get("type"); calls.add(type);
            if (defer) { pending = callback; return; }
            if (type.equals("auth/current_user")) callback.onResult(true, map("id", ++identities > 1 && changedUser ? "other" : "user"), null);
            else if (type.equals("auth/sign_path")) {
                check(message.get("path").equals("/api/services")); check(message.get("expires").equals(30));
                if (denied) callback.onResult(false, map("code", "not_allowed", "message", "private raw text"), "private raw text");
                else callback.onResult(true, map("path", signedPath), null);
            } else if (type.equals("get_panels")) {
                if(panelDenied)callback.onResult(false,map("code","too_large"),"private raw text");
                else callback.onResult(true,map("d5369777_music_assistant",map("component_name","app","title","Music Assistant","config",map("addon","d5369777_music_assistant"))),null);
            }
            else throw new AssertionError("Unexpected HA command: " + type);
        }
    }
    public static void main(String[] args) {
        String[] error = {""}; AtomicBoolean done = new AtomicBoolean();
        Host host = new Host(); DiscoveryAccess access = new DiscoveryAccess(host, new InlineExecutor());
        access.nodes(() -> true, choices -> done.set(choices.size() == 1), text -> error[0] = text);
        check(done.get() && error[0].isEmpty()); check(ServiceCatalogue.reads == 1);
        check(!host.calls.contains("get_services")); check(host.calls.size() == 3);
        for (int scenario = 0; scenario < 4; scenario++) {
            Host bad = new Host(); bad.denied = scenario == 0; bad.changedOrigin = scenario == 1;
            bad.changedUser = scenario == 2; if (scenario == 3) bad.signedPath = "https://other.example/api/services?authSig=test";
            error[0] = ""; done.set(false); int before = ServiceCatalogue.reads;
            new DiscoveryAccess(bad, new InlineExecutor()).nodes(() -> true, choices -> done.set(true), text -> error[0] = text);
            check(!done.get() && !error[0].isEmpty()); check(ServiceCatalogue.reads == before);
            check(!error[0].contains("private raw text"));
            if (scenario == 0) check(error[0].contains("not_allowed"));
        }
        Host cancelled = new Host(); cancelled.defer = true; AtomicBoolean current = new AtomicBoolean(true);
        done.set(false); error[0] = "";
        new DiscoveryAccess(cancelled, new InlineExecutor()).nodes(current::get, choices -> done.set(true), text -> error[0] = text);
        current.set(false); cancelled.pending.onResult(true, map("id", "user"), null);
        check(!done.get() && error[0].isEmpty());
        host.panelDenied=true;access.addons(() -> true, choices -> done.set(true), text -> error[0] = text);
        check(error[0].contains("too_large")); check(!error[0].contains("private raw text"));
        host.panelDenied=false;error[0]="";String[] app={""};
        access.resolveAddon("",()->true,value->app[0]=value,text->error[0]=text);
        check(app[0].equals("d5369777_music_assistant")&&error[0].isEmpty());check(!host.calls.contains("supervisor/api"));
        check(DiscoveryPolicy.panels(map("fake",map("component_name","custom","config",map("addon","d5369777_music_assistant")))).isEmpty());
        check(DiscoveryPolicy.panels(map("fake",map("component_name","app","config",map("addon","../../evil")))).isEmpty());
        List<DiscoveryPolicy.Choice> nodes = Collections.singletonList(new DiscoveryPolicy.Choice("matched", "Kiosk"));
        NodeResolver resolver = new NodeResolver() {
            public void cancel() {}
            public void resolve(String ip, List<DiscoveryPolicy.Choice> choices, java.util.function.BooleanSupplier live,
                                java.util.function.Consumer<String> success, java.util.function.Consumer<String> failure) {
                check(ip.equals("192.0.2.10")); if (live.getAsBoolean()) success.accept("matched");
            }
        };
        Host automatic = new Host(); String[] result = {""}; error[0] = "";
        DiscoveryAccess identity = new DiscoveryAccess(automatic, new InlineExecutor(), resolver);
        identity.node("", nodes, () -> true, node -> result[0] = node, text -> error[0] = text);
        check(result[0].equals("matched") && error[0].isEmpty()); check(automatic.ipReads == 2);
        automatic.ipReads = 0; automatic.changedIp = true; result[0] = "";
        identity.node("", nodes, () -> true, node -> result[0] = node, text -> error[0] = text);
        check(result[0].isEmpty() && error[0].contains("network changed"));
        result[0] = ""; error[0] = "";
        identity.node("matched", nodes, () -> true, node -> result[0] = node, text -> error[0] = text);
        check(result[0].equals("matched") && error[0].isEmpty());
        result[0] = "";
        identity.node("missing", nodes, () -> true, node -> result[0] = node, text -> error[0] = text);
        check(result[0].isEmpty() && error[0].contains("paired kiosk"));
        result[0] = ""; error[0] = "";
        identity.node("matched", nodes, () -> false, node -> result[0] = node, text -> error[0] = text);
        check(result[0].isEmpty() && error[0].isEmpty());
        System.out.println("PASS: " + checks + " mocked discovery bridge checks (no network)");
    }
}
