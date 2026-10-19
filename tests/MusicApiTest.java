package net.markandrew.kiosk.music;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real HTTP/parser/playback path against a loopback fake server. No HA/MA access. */
public final class MusicApiTest {
    static int checks;
    static final List<String> calls = new ArrayList<>();
    static final Map<String, JSONObject> players = new HashMap<>(), queues = new HashMap<>();
    static boolean denyControl,changeServer;static int infoReads;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("API check " + checks + ": " + calls); }
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception {
        int writes = Collections.frequency(calls, "player_queues/play_media");
        boolean refused = false;
        try { action.run(); } catch (Exception expected) { refused = true; }
        check(refused); check(Collections.frequency(calls, "player_queues/play_media") == writes);
    }
    static JSONObject player(String id) throws Exception {
        return new JSONObject().put("player_id", id).put("name", id).put("type", "player")
            .put("available", true).put("active_source", JSONObject.NULL)
            .put("active_group", JSONObject.NULL).put("synced_to", JSONObject.NULL).put("playback_state", "idle");
    }
    static void reset() throws Exception {
        calls.clear(); players.clear(); queues.clear(); players.put("echo", player("echo"));
        denyControl=false;changeServer=false;infoReads=0;
        queues.put("echo", new JSONObject().put("queue_id", "echo").put("available", true));
    }
    static void play(MusicApi api, IngressAccess.Session session, boolean grouped, boolean current) throws Exception {
        PlayerPolicy.Snapshot s = api.playbackSnapshot(session, "echo");
        api.play(session, s, "echo", s.queue("echo"), grouped, "library://album/1", "play", () -> current);
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/info",exchange->{try{
            calls.add("info");String body="{\"server_id\":\""+(changeServer&&infoReads++>0?"other":"server")+"\"}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});
        server.createContext("/auth/me",exchange->{try{
            calls.add("auth/me");byte[] bytes="{\"user_id\":\"user\",\"role\":\"user\"}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});
        server.createContext("/api", exchange -> {
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] part = new byte[1024]; int n;
                while ((n = exchange.getRequestBody().read(part)) != -1) buffer.write(part, 0, n);
                JSONObject request = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                String cmd = request.getString("command"); calls.add(cmd);
                JSONObject a = request.getJSONObject("args"); Object response;
                if (cmd.equals("players/get")) response = players.get(a.getString("player_id"));
                else if (cmd.equals("players/all")) response = new JSONArray(players.values());
                else if (cmd.equals("player_queues/all")) response = new JSONArray(queues.values());
                else if (cmd.equals("player_queues/get")) response = queues.get(a.getString("queue_id"));
                else if (cmd.equals("player_queues/play_media")) response = new JSONObject();
                else if (cmd.equals("auth/scopes")) response=new JSONObject().put("user",new JSONArray().put(denyControl?"other":"players.control"));
                else throw new IllegalArgumentException("Unexpected command " + cmd);
                byte[] body = String.valueOf(response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (Exception error) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            MusicApi api = new MusicApi();
            IngressAccess.Session session = new IngressAccess.Session(new URI("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            reset();List<String> stages=new ArrayList<>();check(api.connectionSnapshot(session,stages::add).players.containsKey("echo"));
            check(calls.equals(Arrays.asList("info","auth/me","auth/scopes","players/all","player_queues/all","info","auth/me")));check(stages.get(stages.size()-1).equals("Connection identity"));
            reset();denyControl=true;rejects(()->api.connectionSnapshot(session,s->{}));check(!calls.contains("players/all"));
            reset();changeServer=true;rejects(()->api.connectionSnapshot(session,s->{}));check(!calls.contains("player_queues/play_media"));
            reset();players.get("echo").put("playback_state","playing").put("active_source","echo");
            queues.get("echo").put("current_item",new JSONObject().put("media_item",new JSONObject().put("name","A song").put("artists",new JSONArray().put(new JSONObject().put("name","An artist")))));
            check(api.snapshot(session).playbackDescription("echo").equals("A song · An artist\nplaying"));
            players.get("echo").put("playback_state","idle");check(api.snapshot(session).playbackDescription("echo").equals("idle"));
            players.get("echo").put("playback_state","playing").put("active_source","line_in").put("current_media",new JSONObject().put("title","External track").put("artist","Other artist"));
            check(api.snapshot(session).playbackDescription("echo").equals("External track · Other artist\nplaying"));
            reset(); play(api, session, false, true);
            check(calls.equals(Arrays.asList("players/get", "player_queues/get", "player_queues/play_media")));
            reset(); rejects(() -> play(api, session, false, false));
            reset(); players.clear(); rejects(() -> play(api, session, false, true));
            reset(); players.get("echo").put("available", false); rejects(() -> play(api, session, false, true));
            reset(); players.get("echo").put("hidden", true); rejects(() -> play(api, session, false, true));
            reset(); queues.clear(); rejects(() -> play(api, session, false, true));
            reset(); queues.get("echo").put("available", false); rejects(() -> play(api, session, false, true));
            reset(); queues.get("echo").put("queue_id", "other"); rejects(() -> play(api, session, false, true));
            reset(); players.get("echo").put("active_source", "line_in").put("playback_state", "playing");
            rejects(() -> play(api, session, false, true));
            reset(); players.get("echo").put("group_members", new JSONArray().put("echo").put("other"));
            rejects(() -> play(api, session, false, true));
            reset(); players.get("echo").put("synced_to", "leader"); players.put("leader", player("leader"));
            queues.put("leader", new JSONObject().put("queue_id", "leader").put("available", true));
            rejects(() -> play(api, session, false, true)); calls.clear(); play(api, session, true, true);
            check(calls.equals(Arrays.asList("players/get", "players/get", "player_queues/get", "player_queues/play_media")));
            reset(); players.get("echo").put("active_group", "missing"); rejects(() -> play(api, session, true, true));
            reset(); players.get("echo").put("synced_to", "echo"); rejects(() -> play(api, session, true, true));
            reset(); players.get("echo").put("active_source", "queue2");
            queues.put("queue2", new JSONObject().put("queue_id", "queue2").put("available", true));
            play(api, session, false, true); check(calls.size() == 3);
            // Only the live verified own identity can override local hidden/private chooser flags.
            reset();
            players.get("echo").put("hidden", true).put("private", true).put("hide_in_ui", true);
            players.put("other", player("other").put("private", true).put("hide_in_ui", true));
            players.put("speaker", player("speaker"));
            check(!api.snapshot(session).players.get("echo").visible);
            api.ownReceiver(s -> "echo");
            PlayerPolicy.Snapshot own = api.snapshot(session);
            check(own.players.get("echo").visible);
            check(!own.players.get("other").visible && own.players.get("speaker").visible);
            check("echo".equals(DefaultPlayerPolicy.selected(own, "echo").id));
            rejects(() -> DefaultPlayerPolicy.selected(own, "other"));
            players.get("other").put("hide_in_ui", false);
            queues.put("other", new JSONObject().put("queue_id", "other").put("available", true));
            PlayerPolicy.Snapshot shared = api.snapshot(session);
            check(shared.players.get("other").visible);
            check("other".equals(DefaultPlayerPolicy.selected(shared, "other").id));
            PlayerPolicy.Snapshot otherPlayback = api.playbackSnapshot(session, "other");
            check(otherPlayback.players.get("other").visible);
            api.play(session, otherPlayback, "other", "other", false, "library://album/1", "play", () -> true);
            check(Collections.frequency(calls, "player_queues/play_media") == 1);
            play(api, session, false, true);
            check(Collections.frequency(calls, "player_queues/play_media") == 2);
            api.ownReceiver(s -> "");
            rejects(() -> play(api, session, false, true));
            api.ownReceiver(s -> "echo");
            players.get("echo").put("available", false);
            rejects(() -> play(api, session, false, true));
            players.get("echo").put("available", true).put("enabled", false);
            rejects(() -> play(api, session, false, true));
            players.get("echo").put("enabled", true).put("type", "protocol");
            check(!api.snapshot(session).players.get("echo").visible);
            System.out.println("PASS: " + checks + " real MusicApi loopback HTTP checks (no HA/MA access)");
        } finally { server.stop(0); }
    }
}
