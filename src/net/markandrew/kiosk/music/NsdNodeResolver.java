package net.markandrew.kiosk.music;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded Android mDNS lookup of this device's advertised ESPHome node. No credentials. */
final class NsdNodeResolver implements NodeResolver {
    private final Supplier<Context> context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Attempt active;
    NsdNodeResolver(Supplier<Context> context) { this.context = context; }
    public void cancel() { main.post(() -> { if (active != null) active.finish("", ""); }); }
    public void resolve(String ip, List<DiscoveryPolicy.Choice> nodes, BooleanSupplier current,
                        Consumer<String> done, Consumer<String> fail) {
        main.post(() -> {
            if (!current.getAsBoolean()) return;
            if (active != null) active.finish("", "");
            Context c = context.get();
            if (c == null || ip == null || ip.isEmpty() || nodes.isEmpty()) {
                fail.accept("Automatic kiosk identification is unavailable. Pair this device under Advanced."); return;
            }
            NsdManager manager = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            if (manager == null) { fail.accept("Network discovery is unavailable. Pair this device under Advanced."); return; }
            active = new Attempt(manager, ip, nodes, current, done, fail); active.start();
        });
    }
    private final class Attempt implements NsdManager.DiscoveryListener {
        final NsdManager manager;
        final String ip;
        final List<DiscoveryPolicy.Choice> nodes;
        final BooleanSupplier current;
        final Consumer<String> done, fail;
        final Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
        final Map<String, String> addresses = new LinkedHashMap<>();
        List<NsdServiceInfo> pending;
        int index;
        boolean ended, listening;
        final Runnable stop = this::stopScan;
        final Runnable deadline = () -> finish("", "Kiosk identification timed out. Pair this device under Advanced; no other kiosk was selected.");
        Attempt(NsdManager manager, String ip, List<DiscoveryPolicy.Choice> nodes, BooleanSupplier current,
                Consumer<String> done, Consumer<String> fail) {
            this.manager = manager; this.ip = ip; this.nodes = nodes; this.current = current; this.done = done; this.fail = fail;
        }
        void start() {
            main.postDelayed(deadline, 10000);
            try { listening = true; manager.discoverServices("_esphomelib._tcp.", NsdManager.PROTOCOL_DNS_SD, this); }
            catch (RuntimeException ignored) { finish("", "Network discovery could not start. Pair this device under Advanced."); }
        }
        boolean live() { if (ended) return false; if (!current.getAsBoolean()) { finish("", ""); return false; } return true; }
        String node(NsdServiceInfo service) {
            String name = service.getServiceName();
            // ESPHome advertises its node name, not its friendly room/device label.
            if (name == null || !name.matches("[a-z0-9-]{1,100}")) return "";
            String id = name.replace('-', '_');
            return nodes.stream().anyMatch(n -> n.id.equals(id)) ? id : "";
        }
        public void onDiscoveryStarted(String type) { main.post(() -> { if (live()) main.postDelayed(stop, 2500); }); }
        public void onServiceFound(NsdServiceInfo service) { main.post(() -> {
            if (!live() || !listening || node(service).isEmpty()) return;
            found.put(node(service), service);
            if (found.size() > 32) finish("", "Too many kiosk candidates for automatic identification. Pair this device under Advanced.");
        }); }
        public void onServiceLost(NsdServiceInfo service) { main.post(() -> {
            if (!live() || node(service).isEmpty()) return;
            found.remove(node(service));
            if (!listening) finish("", "A kiosk left the network during identification. Refresh Setup.");
        }); }
        void stopScan() {
            if (!live()) return;
            try { manager.stopServiceDiscovery(this); }
            catch (RuntimeException ignored) { finish("", "Network discovery did not complete. Pair this device under Advanced."); }
        }
        public void onDiscoveryStopped(String type) { main.post(() -> {
            listening = false;
            if (!live()) return;
            pending = new ArrayList<>(found.values()); next();
        }); }
        @SuppressWarnings("deprecation") void next() {
            if (!live()) return;
            if (index == pending.size()) {
                String match = DiscoveryPolicy.uniqueNode(nodes, addresses, ip);
                finish(match, match.isEmpty() ? "This device could not be uniquely identified. Pair it under Advanced." : ""); return;
            }
            NsdServiceInfo service = pending.get(index++);
            try { manager.resolveService(service, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo info, int code) { main.post(() -> {
                    if (live()) finish("", "A kiosk address could not be verified. Pair this device under Advanced.");
                }); }
                public void onServiceResolved(NsdServiceInfo info) { main.post(() -> {
                    if (!live()) return;
                    if (info.getHost() == null || !node(service).equals(node(info))) {
                        finish("", "Kiosk identity response was incomplete. Pair this device under Advanced."); return;
                    }
                    addresses.put(node(service), info.getHost().getHostAddress()); next();
                }); }
            }); } catch (RuntimeException ignored) { finish("", "Kiosk address lookup failed. Pair this device under Advanced."); }
        }
        public void onStartDiscoveryFailed(String type, int code) { main.post(() -> finish("", "Network discovery was refused. Pair this device under Advanced.")); }
        public void onStopDiscoveryFailed(String type, int code) { main.post(() -> finish("", "Network discovery did not stop cleanly. Pair this device under Advanced.")); }
        void finish(String node, String error) {
            if (ended) return;
            ended = true; main.removeCallbacks(stop); main.removeCallbacks(deadline);
            if (listening) { listening = false; try { manager.stopServiceDiscovery(this); } catch (RuntimeException ignored) {} }
            if (active == this) active = null;
            if (!current.getAsBoolean()) return;
            if (!node.isEmpty()) done.accept(node); else if (!error.isEmpty()) fail.accept(error);
        }
    }
}
