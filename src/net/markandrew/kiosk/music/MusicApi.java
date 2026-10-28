package net.markandrew.kiosk.music;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Bounded HTTP with separate read and explicit playback entry points. */
final class MusicApi {
    private java.util.function.Function<IngressAccess.Session, String> ownReceiver = session -> "";
    void ownReceiver(java.util.function.Function<IngressAccess.Session, String> identity) { ownReceiver = identity; }
    private volatile int lastHttpStatus;
    int lastHttpStatus() { return lastHttpStatus; }
    private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();

    Object identity(IngressAccess.Session session) throws Exception {
        Object value = request(session, "auth/me", null);
        if (!(value instanceof JSONObject) || !(((JSONObject) value).has("user_id") || ((JSONObject) value).has("username")))
            throw new IOException("Music Assistant identity could not be verified.");
        return value;
    }
    JSONObject serverInfo(IngressAccess.Session session) throws Exception {
        Object value = request(session, "info", null);
        if (!(value instanceof JSONObject) || ((JSONObject)value).optString("server_id").isEmpty())
            throw new IOException("Music Assistant server identity is unavailable.");
        return (JSONObject)value;
    }
    /** Match the exact paired local client on one stable authenticated MA instance.
     * MA instance UUIDs and Sendspin transport identities are different namespaces. */
    void verifyInitialReceiver(IngressAccess.Session session,String playerId)throws Exception{
        if(playerId.isEmpty()||!playerId.equals(ownReceiver.apply(session)))throw new IllegalArgumentException("Local receiver is not verified.");
        String user=((JSONObject)identity(session)).optString("user_id"),instance=serverInfo(session).getString("server_id");
        if(user.isEmpty())throw new IllegalArgumentException("MA account identity is unavailable.");
        Object raw=command(session,"players/get",new JSONObject().put("player_id",playerId));
        if(!(raw instanceof JSONObject)||!playerId.equals(((JSONObject)raw).optString("player_id"))||!"player".equals(((JSONObject)raw).optString("type")))throw new IllegalArgumentException("Local player identity is unavailable on this MA instance.");
        PlayerPolicy.Snapshot snapshot=parseSnapshot(new org.json.JSONArray().put(raw),new org.json.JSONArray(),playerId);
        DefaultPlayerPolicy.selected(snapshot,playerId);
        if(!instance.equals(serverInfo(session).getString("server_id"))||!user.equals(((JSONObject)identity(session)).optString("user_id"))||!playerId.equals(ownReceiver.apply(session)))throw new IllegalArgumentException("MA or receiver identity changed.");
    }
    String accountName(IngressAccess.Session session) throws Exception {
        JSONObject user = (JSONObject) identity(session);
        String name = user.optString("display_name", user.optString("username", "Current MA account")).replaceAll("[\\p{Cntrl}]", " ");
        return name.substring(0, Math.min(name.length(), 120));
    }

    Object command(IngressAccess.Session session, String command, JSONObject args) throws Exception {
        IngressPolicy.command(command);
        return send(session, command, args);
    }

    private Object send(IngressAccess.Session session, String command, JSONObject args) throws Exception {
        JSONObject message = new JSONObject();
        message.put("message_id", "musicassistantplugin-request");
        message.put("command", command); message.put("args", args);
        return request(session, "api", message.toString().getBytes(StandardCharsets.UTF_8));
    }

    PlayerPolicy.Snapshot snapshot(IngressAccess.Session session) throws Exception {
        Object players = command(session, "players/all", new JSONObject().put("return_unavailable", true));
        Object queues = command(session, "player_queues/all", new JSONObject());
        if (!(players instanceof org.json.JSONArray) || !(queues instanceof org.json.JSONArray))
            throw new IOException("Invalid player/queue response.");
        return parseSnapshot((org.json.JSONArray) players, (org.json.JSONArray) queues, ownReceiver.apply(session));
    }
    /** Explicit read-only connection check. Never registers, pairs or submits playback. */
    PlayerPolicy.Snapshot connectionSnapshot(IngressAccess.Session session,java.util.function.Consumer<String> stage)throws Exception{
        stage.accept("Server");String server=serverInfo(session).getString("server_id");
        stage.accept("Sign-in");JSONObject user=(JSONObject)identity(session);String account=user.optString("user_id");
        if(server.isEmpty()||account.isEmpty())throw new IOException("Identity unavailable");
        stage.accept("Player access");Object scopes=command(session,"auth/scopes",new JSONObject());
        if(PairingDiagnostics.grant(user,scopes,"players.control")!=PairingDiagnostics.Grant.GRANTED)throw new IOException("Player permission unavailable");
        PlayerPolicy.Snapshot result=snapshot(session);
        stage.accept("Connection identity");
        if(!server.equals(serverInfo(session).getString("server_id"))||!account.equals(((JSONObject)identity(session)).optString("user_id")))throw new IOException("Identity changed");
        return result;
    }

    static PlayerPolicy.Snapshot parseSnapshot(org.json.JSONArray players, org.json.JSONArray queues, String ownId) throws Exception {
        VolumeDisplay.annotate(players);
        PlayerPolicy.Snapshot result = new PlayerPolicy.Snapshot();
        org.json.JSONArray rows = players;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject p = rows.getJSONObject(i);
            String id = p.getString("player_id");
            result.providers.put(id, p.optString("provider", ""));
            org.json.JSONArray protocols = p.optJSONArray("output_protocols");
            // Parse the complete bounded response, not the eight-row diagnostic preview.
            boolean hasSendspin = false, availableSendspin = false;
            if (protocols != null) for (int j = 0; j < protocols.length(); j++) {
                JSONObject protocol = protocols.optJSONObject(j);
                if (protocol != null && "sendspin".equals(protocol.optString("protocol_domain", ""))) {
                    hasSendspin = true;
                    if (Boolean.TRUE.equals(protocol.opt("available"))) availableSendspin = true;
                }
            }
            if (hasSendspin) result.sendspinOutputs.put(id, availableSendspin);
            StringBuilder protocolSummary = new StringBuilder();
            if (protocols != null) for (int j = 0; j < Math.min(protocols.length(), 8); j++) {
                JSONObject protocol = protocols.optJSONObject(j);
                if (protocol == null) continue;
                if (protocolSummary.length() > 0) protocolSummary.append(", ");
                protocolSummary.append(DiscoveryDiagnostics.token(protocol.optString("protocol_domain", "")));
                protocolSummary.append(protocol.optBoolean("available", true) ? " (available)" : " (offline)");
            }
            if (protocols != null && protocols.length() > 8) protocolSummary.append(", …");
            result.diagnosticProtocols.put(id, protocolSummary.toString());
            JSONObject device = p.optJSONObject("device_info");
            if (device != null) {
                JSONObject identifiers = device.optJSONObject("identifiers");
                result.addresses.put(id, identifiers == null ? device.optString("ip_address", "")
                    : identifiers.optString("ip_address", device.optString("ip_address", "")));
            }
            boolean grouped = false;
            org.json.JSONArray members = p.optJSONArray("group_members");
            if (members != null) for (int j = 0; j < members.length(); j++) if (!id.equals(members.optString(j))) grouped = true;
            result.zeroGroups.put(id,p.optBoolean("plugin_all_members_zero"));
            result.players.put(id, new PlayerPolicy.Player(id, p.optString("name"), p.optString("type"),
                p.optBoolean("available", false) && p.optBoolean("enabled", true),
                OwnReceiverPolicy.selectable(id, p.optString("type"), ownId,
                    p.optBoolean("hidden", false) || p.optBoolean("hide_in_ui", false)),
                PlayerPolicy.reference(p.opt("active_group"), JSONObject.NULL),
                PlayerPolicy.reference(p.opt("synced_to"), JSONObject.NULL),
                 PlayerPolicy.reference(p.opt("active_source"), JSONObject.NULL), p.optString("playback_state", ""), grouped));
            result.playerMedia.put(id, mediaLabel(p.optJSONObject("current_media")));
        }
        rows = queues;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject q = rows.getJSONObject(i); String id = q.getString("queue_id");
            result.queues.put(id, new PlayerPolicy.Queue(id, q.optBoolean("available", true), q.optInt("items"), q.optString("state")));
            JSONObject current = q.optJSONObject("current_item");
            result.queueMedia.put(id, mediaLabel(current == null ? null : current.optJSONObject("media_item")));
        }
        return result;
    }
    private static String mediaLabel(JSONObject media) {
        if (media == null) return "";
        String name = mediaText(media.optString("name", media.optString("title")));
        String artist = media.optString("artist");
        org.json.JSONArray artists = media.optJSONArray("artists");
        if (artists != null && artists.length() > 0) {
            JSONObject first = artists.optJSONObject(0);
            if (first != null) artist = first.optString("name");
        }
        if (name.isEmpty()) return "";
        return name + (artist.isEmpty() ? "" : " · " + mediaText(artist));
    }
    private static String mediaText(String value) {
        String text=value.replaceAll("[\\p{Cntrl}]"," ").trim();return text.substring(0,Math.min(200,text.length()));
    }

    /** Bounded presentation-only member reads; missing evidence never means group silence. */
    void annotateDisplayVolume(IngressAccess.Session session,JSONObject player,java.util.function.BooleanSupplier current)throws Exception{
        player.put("plugin_all_members_zero",false);
        org.json.JSONArray ids=player.optJSONArray("group_members");
        if(ids==null||ids.length()==0||ids.length()>64)return;
        org.json.JSONArray rows=new org.json.JSONArray().put(player);
        java.util.Set<String> seen=new java.util.HashSet<>();seen.add(player.optString("player_id"));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        try{
            for(int i=0;i<ids.length();i++){
                String id=ids.optString(i);if(id.isEmpty())return;if(!seen.add(id))continue;
                if(!current.getAsBoolean()||System.nanoTime()>deadline)return;
                Object value=command(session,"players/get",new JSONObject().put("player_id",id));
                if(!(value instanceof JSONObject)||!id.equals(((JSONObject)value).optString("player_id")))return;
                rows.put(value);
            }
            if(current.getAsBoolean())VolumeDisplay.annotate(rows);
        }catch(Exception ignored){player.put("plugin_all_members_zero",false);}
    }

    /** Only the exact selected player, its referenced leaders and its active queue. No list/discovery calls. */
    PlayerPolicy.Snapshot playbackSnapshot(IngressAccess.Session session, String playerId) throws Exception {
        if (playerId == null || playerId.isEmpty()) throw new IllegalArgumentException("Choose a default player in Setup.");
        org.json.JSONArray players = new org.json.JSONArray();
        java.util.Set<String> visited = new java.util.HashSet<>();
        String id = playerId;
        PlayerPolicy.Snapshot result;
        while (true) {
            if (!visited.add(id) || visited.size() > 8) throw new IllegalArgumentException("Player grouping is cyclic or too deep. Reopen Players.");
            Object row = command(session, "players/get", new JSONObject().put("player_id", id));
            if (!(row instanceof JSONObject) || !id.equals(((JSONObject) row).optString("player_id")))
                throw new IllegalArgumentException("Selected player is missing. No fallback was selected.");
            players.put(row);
            result = parseSnapshot(players, new org.json.JSONArray(), ownReceiver.apply(session));
            PlayerPolicy.Player player = result.players.get(id);
            if (!player.available || !player.audio()) throw new IllegalArgumentException("Selected player or group leader is unavailable. No fallback was selected.");
            String parent = !player.parent.isEmpty() ? player.parent : player.syncedTo;
            if (parent.isEmpty()) break;
            id = parent;
        }
        PlayerPolicy.Player leader = result.leader(playerId);
        String queueId = leader.activeSource.isEmpty() ? leader.id : leader.activeSource;
        Object queue = command(session, "player_queues/get", new JSONObject().put("queue_id", queueId));
        if (!(queue instanceof JSONObject) || !queueId.equals(((JSONObject) queue).optString("queue_id")))
            throw new IllegalArgumentException("Player queue could not be verified; an external source may be active. No queue was overwritten.");
        return parseSnapshot(players, new org.json.JSONArray().put(queue), ownReceiver.apply(session));
    }

    void play(IngressAccess.Session session, PlayerPolicy.Snapshot fresh, String playerId, String expectedQueue, boolean allowGrouped, String uri, String option, java.util.function.BooleanSupplier current) throws Exception {
        play(session, fresh, playerId, expectedQueue, allowGrouped, uri, option, false, "", current);
    }
    void play(IngressAccess.Session session, PlayerPolicy.Snapshot fresh, String playerId, String expectedQueue, boolean allowGrouped, String uri, String option, boolean shuffle, String start, java.util.function.BooleanSupplier current) throws Exception {
        IngressPolicy.playback(uri, option);
        PlayerPolicy.Player player = DefaultPlayerPolicy.selected(fresh, playerId), leader = fresh.leader(playerId);
        String id = fresh.queue(playerId);
        PlayerPolicy.Queue queue = fresh.queues.get(id);
        if (!player.available || leader == null || !leader.available || !id.equals(expectedQueue) || fresh.externalSource(playerId)
            || queue == null || !queue.available || (fresh.grouped(playerId) && !allowGrouped))
            throw new IOException("Player or queue changed. Reopen Players; external sources cannot be overwritten.");
        if (!current.getAsBoolean()) throw new IOException("Playback cancelled before submission.");
        JSONObject args = new JSONObject().put("queue_id", id).put("media", new org.json.JSONArray().put(uri)).put("option", option);
        if (shuffle) args.put("shuffle", true);
        if (!start.isEmpty()) { if (start.length() > 512 || start.matches(".*[\\p{Cntrl}].*")) throw new IllegalArgumentException("Invalid start-item identity."); args.put("start_item", start); }
        send(session, "player_queues/play_media", args);
    }
    Object mutate(IngressAccess.Session session, String command, JSONObject args, java.util.function.BooleanSupplier current) throws Exception {
        if (!IngressPolicy.MUTATIONS.contains(command)) throw new IllegalArgumentException("Unsupported music action.");
        if (!current.getAsBoolean()) throw new IllegalArgumentException("Action cancelled before submission.");
        return send(session, command, args);
    }

    void transfer(IngressAccess.Session session, String source, String target, String expectedQueue,
                  java.util.function.BooleanSupplier current) throws Exception {
        PlayerPolicy.Snapshot fresh = snapshot(session);
        fresh.validateTransfer(source, target, expectedQueue);
        if (!current.getAsBoolean()) throw new IOException("Transfer cancelled before submission.");
        send(session, "player_queues/transfer", new JSONObject().put("source_queue_id", expectedQueue)
            .put("target_queue_id", target).put("auto_play", "playing".equals(fresh.queues.get(expectedQueue).state)));
    }

    private Object request(IngressAccess.Session session, String endpoint, byte[] body) throws Exception {
        lastHttpStatus = 0;
        if (Thread.currentThread().isInterrupted()) throw new IOException("Browser request cancelled.");
        HttpURLConnection connection = (HttpURLConnection) session.base.resolve(endpoint).toURL().openConnection();
        if (!active.compareAndSet(null, connection)) { connection.disconnect(); throw new IOException("A browser read is already in progress."); }
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestProperty(session.header, session.credential);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MusicAssistantPlugin/1.0.2");
            connection.setRequestProperty("Accept-Language", "en-GB");
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int code = connection.getResponseCode(); lastHttpStatus = code;
            if (code != 200) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.HTTP, code);
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.NON_JSON, code);
            if (connection.getContentLengthLong() > IngressPolicy.MAX_RESPONSE) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.TOO_LARGE, code);
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = stream.read(bytes)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Browser request cancelled.");
                    if (buffer.size() + count > IngressPolicy.MAX_RESPONSE) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.TOO_LARGE, code);
                    buffer.write(bytes, 0, count);
                }
                JSONTokener parser = new JSONTokener(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                Object result = parser.nextValue();
                if (parser.nextClean() != 0) throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.INVALID_JSON, code);
                return result;
            }
        } catch (PairingDiagnostics.Failure error) { throw error;
        } catch (java.net.SocketTimeoutException error) {
            throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.TIMEOUT, lastHttpStatus);
        } catch (javax.net.ssl.SSLException error) {
            throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.TLS, lastHttpStatus);
        } catch (org.json.JSONException error) {
            throw new PairingDiagnostics.Failure(PairingDiagnostics.Kind.INVALID_JSON, lastHttpStatus);
        } catch (IOException error) {
            throw new PairingDiagnostics.Failure(Thread.currentThread().isInterrupted() ? PairingDiagnostics.Kind.CANCELLED : PairingDiagnostics.Kind.NETWORK, lastHttpStatus);
        } finally { active.compareAndSet(connection, null); connection.disconnect(); }
    }

    void cancel() {
        HttpURLConnection connection = active.getAndSet(null);
        if (connection != null) connection.disconnect();
    }
}
