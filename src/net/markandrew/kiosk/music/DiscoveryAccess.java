package net.markandrew.kiosk.music;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

/** Read-only public HA/SDK discovery; no app credentials or internal files. */
final class DiscoveryAccess {
    private final PluginHost host;
    private final java.util.concurrent.ExecutorService worker;
    private final ServiceCatalogue catalogue = new ServiceCatalogue();
    private final NodeResolver resolver;
    DiscoveryAccess(PluginHost host) { this(host, null); }
    DiscoveryAccess(PluginHost host, java.util.concurrent.ExecutorService worker) { this(host, worker, null); }
    DiscoveryAccess(PluginHost host, java.util.concurrent.ExecutorService worker, NodeResolver resolver) {
        this.host = host; this.worker = worker; this.resolver = resolver;
    }
    void cancel() { catalogue.cancel(); if (resolver != null) resolver.cancel(); }
    void node(String saved, List<DiscoveryPolicy.Choice> nodes, BooleanSupplier current, Consumer<String> done, Consumer<String> fail) {
        if (!current.getAsBoolean()) return;
        if (!saved.isEmpty()) {
            if (nodes.stream().anyMatch(n -> n.id.equals(saved))) done.accept(saved);
            else fail.accept("The paired kiosk is no longer advertised by HA. Check Advanced pairing.");
            return;
        }
        if (resolver == null) { fail.accept("Automatic identification is unavailable. Pair this device under Advanced."); return; }
        deviceIp(current, ip -> resolver.resolve(ip, nodes, current, matched -> deviceIp(current, latest -> {
            if (ip.equals(latest)) done.accept(matched);
            else fail.accept("This device's network changed. Refresh Setup before playback.");
        }, fail), fail), fail);
    }
    void addons(BooleanSupplier current, Consumer<List<DiscoveryPolicy.Choice>> done, Consumer<String> fail) {
        if (StandaloneServer.enabled()) {
            if (current.getAsBoolean()) done.accept(java.util.Collections.singletonList(new DiscoveryPolicy.Choice(StandaloneServer.SLUG, "Standalone Music Assistant")));
            return;
        }
        Map<String, Object> message = new HashMap<>(); message.put("type", "get_panels");
        ha(message, current, data -> done.accept(DiscoveryPolicy.panels(data)), fail);
    }
    void resolveAddon(String configured, BooleanSupplier current, Consumer<String> done, Consumer<String> fail) {
        if (StandaloneServer.enabled()) { done.accept(StandaloneServer.SLUG); return; }
        if (!configured.isEmpty()) {
            try { done.accept(IngressPolicy.addon(configured)); } catch (IllegalArgumentException error) { fail.accept(error.getMessage()); }
            return;
        }
        addons(current, choices -> {
            if (choices.size() == 1) done.accept(choices.get(0).id);
            else fail.accept(choices.isEmpty() ? "No accessible MA sidebar app was found. Choose the saved MA app slug in Setup; administrator access is not required."
                : "More than one MA instance was found. Choose one in Setup.");
        }, fail);
    }
    void nodes(BooleanSupplier current, Consumer<List<DiscoveryPolicy.Choice>> done, Consumer<String> fail) {
        if (worker == null) { fail.accept("Kiosk discovery worker is unavailable."); return; }
        origin(current, origin -> {
            Map<String, Object> identity = new HashMap<>(); identity.put("type", "auth/current_user");
            ha(identity, current, user -> {
                String userId = user instanceof Map ? DiscoveryPolicy.text((Map<?, ?>) user, "id") : "";
                if (userId.isEmpty()) { fail.accept("Kiosk discovery could not verify the HA user."); return; }
                Map<String, Object> message = new HashMap<>(); message.put("type", "auth/sign_path");
                message.put("path", "/api/services"); message.put("expires", 30);
                ha(message, current, data -> {
                    String path = data instanceof Map ? DiscoveryPolicy.text((Map<?, ?>) data, "path") : "";
                    DiscoveryPolicy.signedServices(origin, path);
                    // Do not send a signature to a server/account changed during the bridge calls.
                    origin(current, latest -> {
                        if (!origin.equals(latest)) { fail.accept("HA server changed during kiosk discovery. Reopen Setup."); return; }
                        ha(identity, current, latestUser -> {
                            if (!(latestUser instanceof Map) || !userId.equals(DiscoveryPolicy.text((Map<?, ?>) latestUser, "id"))) {
                                fail.accept("HA user changed during kiosk discovery. Reopen Setup."); return;
                            }
                            try { worker.submit(() -> {
                                if (!current.getAsBoolean()) return;
                                try { List<DiscoveryPolicy.Choice> choices = catalogue.read(origin, path, current); if (current.getAsBoolean()) done.accept(choices); }
                                catch (ServiceCatalogue.ReadFailure error) { if (current.getAsBoolean()) fail.accept(error.getMessage()); }
                                catch (Exception ignored) { if (current.getAsBoolean()) fail.accept("Kiosk catalogue could not be read securely. No selection was made."); }
                            }); } catch (RuntimeException ignored) { fail.accept("Kiosk discovery worker is unavailable."); }
                        }, fail);
                    }, fail);
                }, fail);
            }, fail);
        }, fail);
    }
    private void origin(BooleanSupplier current, Consumer<java.net.URI> done, Consumer<String> fail) {
        try { host.executeCommand("getDashboardState", Collections.emptyMap(), (ok, data, error) -> {
            if (!current.getAsBoolean()) return;
            if (!ok || !(data instanceof Map)) { fail.accept("Kiosk discovery could not read HA's address."); return; }
            try { done.accept(IngressPolicy.origin(DiscoveryPolicy.text((Map<?, ?>) data, "homeAssistantUrl"))); }
            catch (RuntimeException ignored) { fail.accept("Kiosk discovery requires an unchanged trusted HTTPS HA origin."); }
        }); } catch (RuntimeException | LinkageError ignored) { fail.accept("Kiosk discovery could not read HA's address."); }
    }
    void deviceIp(BooleanSupplier current, Consumer<String> done, Consumer<String> fail) {
        try { host.executeCommand("getDeviceInfo", Collections.emptyMap(), (ok, data, error) -> {
            if (!current.getAsBoolean()) return;
            if (!ok || !(data instanceof Map)) { fail.accept("Kiosk identity is unavailable. Select its local player in Setup."); return; }
            done.accept(DiscoveryPolicy.text((Map<?, ?>) data, "ip"));
        }); } catch (RuntimeException | LinkageError ignored) { fail.accept("Kiosk identity is unavailable. Select its local player in Setup."); }
    }
    private void ha(Map<String, Object> message, BooleanSupplier current, Consumer<Object> done, Consumer<String> fail) {
        if (!current.getAsBoolean()) return;
        try { host.haCall(message, (ok, data, error) -> {
            if (!current.getAsBoolean()) return;
            if (!ok) { fail.accept(DiscoveryPolicy.failure(String.valueOf(message.get("type")), data)); return; }
            try { done.accept(data); }
            catch (RuntimeException ignored) { fail.accept("Discovery response was unsupported or incomplete. No selection was made."); }
        }); } catch (RuntimeException | LinkageError ignored) { fail.accept("HA discovery is unavailable on this Kiosk build."); }
    }
}
