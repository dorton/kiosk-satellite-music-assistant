package net.markandrew.kiosk.music;

import java.util.*;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

public final class ReceiverNamingTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Receiver naming " + checks); }
    static class Host implements PluginHost {
        boolean ok = true, fail;
        int reads;
        Object data;
        CommandCallback pending;
        public void haCall(Map<String,Object> message, CommandCallback done) { throw new AssertionError("No HA or settings access required"); }
        public void executeCommand(String command, Map<String,Object> args, CommandCallback callback) {
            check("getDeviceInfo".equals(command) && args.isEmpty()); reads++;
            if (fail) throw new IllegalStateException("private-error-fixture");
            pending = callback;
        }
        void answer() { pending.onResult(ok, data, "private-error-fixture"); }
    }
    public static void main(String[] args) {
        check("Bedroom 1".equals(ReceiverNamePolicy.resolve("", "Bedroom 1")));
        check("Bedroom 1".equals(ReceiverNamePolicy.resolve("Music Assistant Plugin", "Bedroom 1")));
        check("My speaker".equals(ReceiverNamePolicy.resolve("My speaker", "Bedroom 1")));
        check("Music Assistant Plugin".equals(ReceiverNamePolicy.resolve(null, null)));
        check("Hall way".equals(ReceiverNamePolicy.clean(" Hall\nway ")));
        check(ReceiverNamePolicy.clean(42).isEmpty());
        char[] eighty = new char[80]; Arrays.fill(eighty, 'x'); String longName = new String(eighty);
        check(ReceiverNamePolicy.clean(longName + "extra").length() == 80);
        check(ReceiverNamePolicy.clean(longName.substring(0,79) + "\uD83C\uDFB5").length() == 79);
        Host host = new Host(); List<String> result = new ArrayList<>();
        KioskNameAccess.read(host, result::add); check(result.isEmpty() && host.reads == 1);
        Map<String,Object> info = new HashMap<>(); info.put("name", "Kitchen"); info.put("token", "private-response-fixture");
        host.data = info; host.answer(); check(result.equals(Arrays.asList("Kitchen")));
        result.clear(); host.ok = false; KioskNameAccess.read(host, result::add); host.answer(); check(result.equals(Arrays.asList("")));
        result.clear(); host.ok = true; host.data = "private-response-fixture"; KioskNameAccess.read(host, result::add); host.answer(); check(result.equals(Arrays.asList("")));
        result.clear(); host.data = Collections.singletonMap("name", 42); KioskNameAccess.read(host, result::add); host.answer(); check(result.equals(Arrays.asList("")));
        result.clear(); host.fail = true; KioskNameAccess.read(host, result::add); check(result.equals(Arrays.asList("")));
        System.out.println("ReceiverNaming: " + checks + " host-read / fallback / override / sanitisation checks passed");
    }
}
