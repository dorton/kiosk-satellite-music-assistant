package net.markandrew.kiosk.music;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

/** Documented, read-only host identity command. Never request Kiosk settings or credentials. */
final class KioskNameAccess {
    static void read(PluginHost host, Consumer<String> done) {
        try {
            host.executeCommand("getDeviceInfo", Collections.emptyMap(), (ok, data, error) -> {
                done.accept(ok && data instanceof Map ? ReceiverNamePolicy.clean(((Map<?, ?>)data).get("name")) : "");
            });
        } catch (RuntimeException | LinkageError ignored) { done.accept(""); }
    }
}
