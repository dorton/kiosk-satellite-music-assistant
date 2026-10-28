package net.markandrew.kiosk.music;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

/** Uses the documented HA bridge. It never asks for Kiosk's stored HA token. */
final class IngressAccess {
    static final class Session {
        final URI origin, base;
        final String userId, addon;
        final long createdAt=System.nanoTime();
        // Credential: memory only; never render, persist, or log this field.
        final String header, credential;
        Session(URI origin, URI base, String userId, String addon, String session) {
            this.origin = origin; this.base = base; this.userId = userId; this.addon = addon;
            this.header = "Cookie"; this.credential = "ingress_session=" + IngressPolicy.session(session);
        }
        /** Direct standalone MA server; the MA token stands in for the Ingress session. */
        Session(URI base, String authorization) {
            this.origin = base; this.base = base; this.userId = StandaloneServer.SLUG; this.addon = StandaloneServer.SLUG;
            this.header = "Authorization"; this.credential = authorization;
        }
    }

    private final PluginHost host;
    private volatile Session cached;
    IngressAccess(PluginHost host) { this.host = host; }
    void clear() { cached = null; }

    void ensure(String addon, BooleanSupplier current, Consumer<Session> success, Consumer<String> failure) {
        if(!current.getAsBoolean())return;
        if(StandaloneServer.enabled()){deliver(standalone(),current,success,failure);return;}
        try{IngressPolicy.addon(addon);}catch(IllegalArgumentException ignored){failure.accept("Music Assistant app is not selected. Resolve the connection in Setup before opening Now Playing.");return;}
        call("getDashboardState", Collections.emptyMap(), false, current, state -> {
            final URI origin;
            try { origin = IngressPolicy.origin(text(state, "homeAssistantUrl")); }
            catch (IllegalArgumentException error) { failure.accept(error.getMessage()); return; }
            ha(map("type", "auth/current_user"), current, user -> {
                String id = text(user, "id");
                if (id.isEmpty()) { failure.accept("Home Assistant identity is unavailable."); return; }
                Session old = cached;
                if (old != null) {
                    if (!old.origin.equals(origin) || !old.userId.equals(id) || !old.addon.equals(addon)) {
                        clear(); failure.accept("Home Assistant identity or server changed. Reopen the browser."); return;
                    }
                    if(System.nanoTime()-old.createdAt<java.util.concurrent.TimeUnit.MINUTES.toNanos(5)){
                        deliver(old,current,success,failure);return;
                    }
                    clear();
                }
                ha(supervisor("/addons/" + IngressPolicy.addon(addon) + "/info", "get"), current, info -> {
                    final URI base;
                    try {
                        if (!Boolean.TRUE.equals(info.get("ingress"))) throw new IllegalArgumentException();
                        base = IngressPolicy.ingress(origin, text(info, "ingress_url"));
                    } catch (IllegalArgumentException error) { failure.accept("Music Assistant Ingress is unavailable or has an unexpected address."); return; }
                    ha(supervisor("/ingress/session", "post"), current, result -> {
                        final Session session;
                        try { session = new Session(origin, base, id, addon, text(result, "session")); }
                        catch (IllegalArgumentException error) { failure.accept("Home Assistant refused a valid Ingress session."); return; }
                        // A configuration/identity change between the preceding callbacks must not publish this session.
                        ha(map("type", "auth/current_user"), current, latest -> {
                            if (!id.equals(text(latest, "id"))) { clear(); failure.accept("Home Assistant identity changed. Reopen the browser."); return; }
                             cached = session; deliver(session,current,success,failure);
                        }, failure);
                    }, failure);
                }, failure);
            }, failure);
        }, failure);
    }

    // Callers compare sessions by identity, so reuse one instance per configured server and token.
    private static volatile Session standaloneSession;
    private static synchronized Session standalone() {
        URI base = StandaloneServer.base(); String authorization = StandaloneServer.authorization();
        Session s = standaloneSession;
        if (s == null || !s.base.equals(base) || !s.credential.equals(authorization)) standaloneSession = s = new Session(base, authorization);
        return s;
    }

    private void ha(Map<String, Object> message, BooleanSupplier current, Consumer<Map<?, ?>> success, Consumer<String> failure) {
        call(null, message, true, current, success, failure);
    }
    private void deliver(Session session,BooleanSupplier current,Consumer<Session> success,Consumer<String> failure){
        if(!current.getAsBoolean())return;
        try{success.accept(session);}
        catch(RuntimeException ignored){failure.accept("HA Ingress connected, but the music request could not start. Reopen Music / Setup; no automatic command retry was made.");}
    }

    private void call(String command, Map<String, Object> message, boolean ha, BooleanSupplier current,
                      Consumer<Map<?, ?>> success, Consumer<String> failure) {
        if (!current.getAsBoolean()) return;
        PluginHost.CommandCallback callback = (ok, data, error) -> {
            if (!current.getAsBoolean()) return;
            String stage=!ha?"Kiosk HA address":message.containsKey("endpoint")?"HA "+String.valueOf(message.get("endpoint")).replaceAll("/addons/[^/]+/info","app information"):"HA "+message.get("type");
            if (!ok || !(data instanceof Map)) {
                clear();
                // Raw host errors and Supervisor responses can carry credentials. Do not echo them.
                failure.accept(!ok?DiscoveryPolicy.failure(stage,data):stage+" returned an unexpected response shape. Reopen Setup.");
                return;
            }
            try { success.accept((Map<?, ?>) data); }
            catch (RuntimeException ignored) { clear(); failure.accept(stage+" response could not be used. Reopen Setup; no credentials or response details were logged."); }
        };
        try {
            if (ha) host.haCall(message, callback);
            else host.executeCommand(command, message, callback);
        } catch (RuntimeException | LinkageError ignored) {
            clear(); failure.accept("This Kiosk build does not provide the required HA/overlay SDK access.");
        }
    }

    private static Map<String, Object> supervisor(String endpoint, String method) {
        Map<String, Object> value = map("type", "supervisor/api");
        value.put("endpoint", endpoint); value.put("method", method); return value;
    }
    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> result = new HashMap<>(); result.put(key, value); return result;
    }
    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key); return value instanceof String ? (String) value : "";
    }
}
