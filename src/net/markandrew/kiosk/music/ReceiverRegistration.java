package net.markandrew.kiosk.music;

import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.markandrew.kiosk.music.receiver.PluginReceiver;
import org.json.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Exact-identity encrypted pairing only; persist the attempt BEFORE any configuration mutation. */
final class ReceiverRegistration {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "music-plugin-registration"); t.setDaemon(true); return t; });
    private final MusicApi api = new MusicApi();
    private final IngressAccess access;
    private final DiscoveryAccess discovery;
    private final Supplier<PluginReceiver> receiver;
    private final Supplier<String> addon;
    private final BooleanSupplier enabled;
    private volatile boolean open = true;
    private boolean busy, terminal;
    private boolean diagnosticsOnly;
    private volatile String permission = "MA permission: not checked";
    private volatile String diagnostic = "";
    private volatile long generation;
    private volatile String status = "Waiting for receiver";
    ReceiverRegistration(PluginHost host, Supplier<PluginReceiver> receiver, Supplier<String> addon, BooleanSupplier enabled) {
        this.receiver = receiver; this.addon = addon; this.enabled = enabled;
        access = new IngressAccess(host); discovery = new DiscoveryAccess(host);
    }
    String status() { return status + (diagnostic.isEmpty() ? "" : " · " + diagnostic); }
    void start() { main.post(() -> tick()); }
    void retry() {
        PluginReceiver local = receiver.get();
        if (local == null || busy) return;
        local.resetPairingAttempt(); generation++; terminal = false; diagnosticsOnly = false; access.clear();
    }
    void refreshDiagnostics() {
        if (busy) return;
        generation++; terminal = false; diagnosticsOnly = true; access.clear();
    }
    private void tick() {
        if (!open) return;
        PluginReceiver local = receiver.get();
        if (local != null && Boolean.TRUE.equals(local.state().get("paired"))) {
            status = Boolean.TRUE.equals(local.state().get("active")) ? "Paired securely · Player ready" : "Paired securely — awaiting server activation";
            terminal = false;
        } else if (!busy && !terminal && enabled.getAsBoolean() && local != null && Boolean.TRUE.equals(local.state().get("connected"))) {
            begin(local);
        }
        main.postDelayed(() -> tick(), 5000);
    }
    private void begin(PluginReceiver local) {
        Map<String, Object> state = local.state();
        final String clientId = String.valueOf(state.get("clientId")), serverId = String.valueOf(state.get("serverId"));
        final Object serial = state.get("serial");
        final String configured = addon.get(); final long ticket = ++generation;
        final boolean attemptPairing = !diagnosticsOnly; diagnosticsOnly = false;
        BooleanSupplier current = () -> open && generation == ticket && enabled.getAsBoolean() && local == receiver.get()
            && configured.equals(addon.get()) && Boolean.TRUE.equals(local.state().get("connected"))
            && clientId.equals(local.state().get("clientId")) && serverId.equals(local.state().get("serverId"))
            && Objects.equals(serial, local.state().get("serial"));
        if (clientId.isEmpty() || serverId.isEmpty() || "null".equals(clientId) || "null".equals(serverId)) return;
        busy = true; status = "Verifying Music Assistant and receiver identity…";
        main.postDelayed(() -> {
            if (open && busy && generation == ticket) {
                generation++; api.cancel(); busy = false; terminal = true;
                status = "Registration timed out — inspect MA before Retry registration";
            }
        }, 90000);
        discovery.resolveAddon(configured, current, instance -> access.ensure(instance, current, session -> {
            try { worker.submit(() -> pair(local, clientId, serverId, current, session, ticket, attemptPairing)); }
            catch (RejectedExecutionException ignored) { busy = false; }
        }, error -> finish("Registration unavailable — check MA / HA permissions", true, ticket)),
            error -> finish("Choose the Music Assistant server in Plugin preferences", true, ticket));
    }
    private void verify(IngressAccess.Session session, String maInstanceId, String maUserId, BooleanSupplier current) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); boolean[] same = {false};
        access.ensure(session.addon, current, latest -> { same[0] = latest == session; latch.countDown(); }, error -> latch.countDown());
        require(latch.await(8, TimeUnit.SECONDS) && same[0] && current.getAsBoolean());
        // MA instance UUID and Sendspin cryptographic identity are different namespaces.
        require(maInstanceId.equals(api.serverInfo(session).optString("server_id")));
        require(maUserId.equals(((JSONObject)api.identity(session)).optString("user_id")));
        require(current.getAsBoolean());
    }
    private void pair(PluginReceiver local, String clientId, String transportId, BooleanSupplier current, IngressAccess.Session session, long ticket, boolean attemptPairing) {
        try {
            require(current.getAsBoolean());
            permission = "MA permission: not checked";
            final String maInstanceId = observe("info", () -> api.serverInfo(session)).getString("server_id");
            require(!maInstanceId.isEmpty());
            JSONObject user = object(observe("auth/me", () -> api.identity(session)));
            final String maUserId = user.optString("user_id"); require(!maUserId.isEmpty());
            Object scopes = observe("auth/scopes", () -> api.command(session, "auth/scopes", new JSONObject()));
            PairingDiagnostics.Grant grant = PairingDiagnostics.grant(user, scopes, "players.control");
            permission = PairingDiagnostics.permission(user, grant, "players.control");
            diagnostic = permission + " · auth/scopes: HTTP " + api.lastHttpStatus();
            if (!attemptPairing) {
                finish("Diagnostics only — pairing paused. Use Retry registration only when ready", true, ticket);
                return;
            }
            if (grant != PairingDiagnostics.Grant.GRANTED) {
                finish("App pairing paused — players.control is " + grant.name().toLowerCase(java.util.Locale.ROOT) + "; no token submitted", true, ticket);
                return;
            }
            AppPairingFlow.Result result = new AppPairingFlow(clientId, new AppPairingFlow.Port() {
                public boolean current() { return current.getAsBoolean(); }
                public boolean paired() { return Boolean.TRUE.equals(local.state().get("paired")); }
                public void verifySession() throws Exception { observe("MA session verification", () -> { verify(session, maInstanceId, maUserId, current); return null; }); }
                public JSONArray players() throws Exception {
                    Object raw = observe("players/all", () -> api.command(session, "players/all", new JSONObject().put("return_unavailable", true).put("return_protocol_players", true)));
                    require(raw instanceof JSONArray); return (JSONArray)raw;
                }
                public boolean claim() { return local.claimAutoPair(clientId, transportId); }
                public String privateToken() { return local.privateToken(clientId, transportId); }
                public void approve(String token) throws Exception {
                    JSONObject values = new JSONObject().put("pairing_token", token);
                    try {
                        Object response = observe("sendspin/pair_web_player", () -> api.mutate(session, "sendspin/pair_web_player", values, current));
                        if (response != JSONObject.NULL) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.RESPONSE_SHAPE, api.lastHttpStatus());
                    } finally { values.remove("pairing_token"); }
                }
                public long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
                public void sleep(long millis) throws Exception { Thread.sleep(millis); }
                public void status(String message) {
                    main.post(() -> { if (open && generation == ticket) status = message; });
                }
            }).run();
            main.post(() -> {
                if (!open || generation != ticket) return;
                busy = false; terminal = result != AppPairingFlow.Result.PAIRED;
                if (result == AppPairingFlow.Result.PAIRED)
                    status = Boolean.TRUE.equals(local.state().get("active")) ? "Paired securely · Player ready" : "Paired securely — awaiting server activation";
                else if (result == AppPairingFlow.Result.CANCELLED) status = "Receiver / MA connection changed — inspect MA before Retry registration";
            });
        } catch (Exception error) {
            finish("MA diagnostic stopped · " + PairingDiagnostics.describe(error) + ". Pairing paused", true, ticket);
        }
    }
    private interface DiagnosticRequest<T> { T run() throws Exception; }
    private <T> T observe(String stage, DiagnosticRequest<T> request) throws Exception {
        diagnostic = permission + " · " + stage + ": in progress";
        try {
            T value = request.run();
            diagnostic = permission + " · " + stage + ": HTTP " + api.lastHttpStatus();
            return value;
        } catch (Exception error) {
            diagnostic = permission + " · " + stage + ": " + PairingDiagnostics.describe(error);
            throw error;
        }
    }
    private JSONObject object(Object value) throws PairingDiagnostics.Failure {
        if (!(value instanceof JSONObject)) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.RESPONSE_SHAPE, api.lastHttpStatus());
        return (JSONObject)value;
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("Registration stopped"); }
    private void finish(String message, boolean stop, long ticket) { main.post(() -> {
        if (!open || generation != ticket) return; status = message; terminal = stop; busy = false;
    }); }
    void close() {
        open = false; generation++; main.removeCallbacksAndMessages(null);
        api.cancel(); access.clear(); discovery.cancel(); worker.shutdownNow();
    }
}
