package net.markandrew.kiosk.music;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import me.jxl.kiosk.plugins.PluginHost;
final class DiscoveryAccess {
    static boolean failed;
    DiscoveryAccess(PluginHost host, ExecutorService worker, NodeResolver resolver) {}
    DiscoveryAccess(PluginHost host, ExecutorService worker) {}
    void addons(BooleanSupplier current, Consumer<List<DiscoveryPolicy.Choice>> done, Consumer<String> fail) {
        if (current.getAsBoolean()) done.accept(Collections.singletonList(new DiscoveryPolicy.Choice("ma", "Music Assistant")));
    }
    void cancel() {}
    void nodes(BooleanSupplier current, Consumer<List<DiscoveryPolicy.Choice>> done, Consumer<String> fail) {
        if (!current.getAsBoolean()) return;
        if (failed) fail.accept("Identity not verified"); else done.accept(Collections.singletonList(new DiscoveryPolicy.Choice("matched", "Kiosk")));
    }
    void node(String saved, List<DiscoveryPolicy.Choice> choices, BooleanSupplier current, Consumer<String> done, Consumer<String> fail) {
        if (current.getAsBoolean()) done.accept("matched");
    }
    void deviceIp(BooleanSupplier current, Consumer<String> done, Consumer<String> fail) { if (current.getAsBoolean()) done.accept("192.0.2.10"); }
}
