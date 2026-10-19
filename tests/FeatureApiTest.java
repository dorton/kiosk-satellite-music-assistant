package net.markandrew.kiosk.music;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real feature API with a loopback fake MA. Never contacts HA, providers or devices. */
public final class FeatureApiTest {
    static int checks;
    static final List<String> writes = new ArrayList<>(), reads = new ArrayList<>();
    static JSONObject item, user, roles, queue, lastArgs;
    static JSONArray players, playlist, linked, queueItems, providers;
    static String fail = "";
    static boolean present, current, ignoreDelete;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Feature check " + checks + ": " + writes); }
    interface Action { void run() throws Exception; }
    static void refuses(Action action) throws Exception {
        int before = writes.size(); boolean refused = false;
        try { action.run(); } catch (Exception expected) { refused = true; }
        check(refused); check(writes.size() == before);
    }
    static JSONObject args(Object... values) throws Exception { return FeatureApi.args(values); }
    static JSONObject player(String id) throws Exception {
        return args("player_id", id, "name", id, "type", "player", "available", true, "enabled", true,
            "active_group", JSONObject.NULL, "synced_to", JSONObject.NULL, "active_source", JSONObject.NULL,
            "supported_features", new JSONArray().put("set_members"), "can_group_with", new JSONArray().put("other"));
    }
    static void reset() throws Exception {
        writes.clear(); reads.clear(); fail = ""; current = true; present = true;ignoreDelete=false;
        item = args("uri", "library://playlist/1", "provider", "library", "item_id", "1", "media_type", "playlist", "name", "Test", "favorite", true,
            "is_editable", true, "is_dynamic", false, "provider_mappings", new JSONArray().put(args("in_library", true, "provider_domain", "builtin", "provider_instance", "local", "item_id", "1")));
        user = args("username", "test", "role", "admin", "provider_filter", new JSONArray());
        roles = args("admin", new JSONArray().put("*"));
        queue = args("queue_id", "echo", "available", true, "items", 2, "current_index", 0, "index_in_buffer", 0, "current_item", args("queue_item_id", "a"));
        players = new JSONArray().put(player("echo")).put(player("other"));
        playlist = new JSONArray().put(args("position", 1, "uri", "library://track/1", "media_type", "track", "item_id", "1"));
        linked = new JSONArray(); queueItems = new JSONArray().put(args("queue_item_id", "a", "available", true)).put(args("queue_item_id", "b", "available", true));
        providers = new JSONArray().put(args("instance_id", "local", "domain", "builtin", "available", true, "supported_features", new JSONArray().put("library_playlists_edit")));
    }
    static Object response(String command, JSONObject a) throws Exception {
        reads.add(command);
        if (IngressPolicy.MUTATIONS.contains(command)) {
            writes.add(command); lastArgs = a;
            if (command.equals(fail)) throw new IllegalArgumentException("Simulated interrupted mutation");
            if (command.equals("music/favorites/add_item")) item.put("favorite", true);
            if (command.equals("music/favorites/remove_item")) item.put("favorite", false);
            if (command.endsWith("/remove") || command.equals("music/library/remove_item")) present = false;
            if (command.equals("music/playlists/create_playlist")) return item;
            if(command.equals("player_queues/delete_item")&&!ignoreDelete){
                for(int i=queueItems.length()-1;i>=0;i--)if(a.optString("item_id_or_index").equals(queueItems.getJSONObject(i).optString("queue_item_id")))queueItems.remove(i);
                queue.put("items",queueItems.length());
            }
            return new JSONObject();
        }
        switch (command) {
            case "music/item_by_uri": return item;
            case "music/verify_item_uri": return present;
            case "auth/scopes": return roles;
            case "players/all": return players;
            case "players/get": return FeatureApi.byId(players, "player_id", a.optString("player_id"));
            case "player_queues/all": return new JSONArray().put(queue);
            case "player_queues/get": return queue;
            case "player_queues/items": return a.optInt("offset") == 0 ? queueItems : new JSONArray();
            case "music/playlists/playlist_tracks": return playlist;
            case "music/albums/album_tracks": return linked;
            case "music/playlists/library_items": return new JSONArray().put(item);
            case "providers": return providers;
            case "config/providers/get_value": return true;
            default: throw new IllegalArgumentException("Unexpected test read: " + command);
        }
    }
    public static void main(String[] unused) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Object result;
                if (exchange.getRequestURI().getPath().equals("/auth/me")) result = user;
                else {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(); byte[] chunk = new byte[1024]; int n;
                    while ((n = exchange.getRequestBody().read(chunk)) != -1) buffer.write(chunk, 0, n);
                    JSONObject req = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                    result = response(req.getString("command"), req.getJSONObject("args"));
                }
                byte[] bytes = String.valueOf(result).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception expected) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            FeatureApi api = new FeatureApi(); IngressAccess.Session session = new IngressAccess.Session(new URI("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            FeatureApi.Connection c = api.new Connection(session, () -> current);
            check(FeatureApi.memberLabel(true,false,false).equals("Independent player"));
            check(FeatureApi.memberLabel(true,true,false).equals("Group leader"));
            check(FeatureApi.memberLabel(false,true,true).equals("Fixed member"));
            JSONArray order = new JSONArray().put(player("first")).put(player("member")).put(player("last")).put(player("echo"));
            Set<String> active = new HashSet<>(Arrays.asList("echo", "member"));
            List<JSONObject> sorted = FeatureApi.groupRows(order, "echo", active);
            check(sorted.get(0).optString("player_id").equals("echo"));
            check(sorted.get(1).optString("player_id").equals("member"));
            check(sorted.get(2).optString("player_id").equals("first"));
            check(sorted.get(3).optString("player_id").equals("last"));
            active.remove("member"); sorted = FeatureApi.groupRows(order, "echo", active);
            check(sorted.get(1).optString("player_id").equals("first"));
            check(sorted.get(2).optString("player_id").equals("member"));
            check(order.getJSONObject(0).optString("player_id").equals("first"));
            reset(); api.member(c, "echo", "echo", "other", false);
            check(writes.equals(Arrays.asList("players/cmd/set_members"))); check(lastArgs.getString("target_player").equals("echo"));
            reset(); players.getJSONObject(0).put("can_group_with", new JSONArray()); refuses(() -> api.member(c, "echo", "echo", "other", false));
            reset(); players.getJSONObject(0).put("group_members", new JSONArray().put("other")).put("static_group_members", new JSONArray().put("other"));
            refuses(() -> api.member(c, "echo", "echo", "other", true));
            reset(); refuses(() -> api.member(c, "echo", "changed", "other", false));
            reset(); players.getJSONObject(0).put("group_members", new JSONArray().put("other")); api.member(c, "echo", "echo", "other", true);
            check(lastArgs.getJSONArray("player_ids_to_remove").getString(0).equals("other"));
            reset(); players.getJSONObject(0).put("synced_to", "echo"); refuses(() -> FeatureApi.groupLeader(players, "echo"));
            reset(); BrowserItem entry = new BrowserItem(playlist.getJSONObject(0)); JSONArray expected = new JSONArray(playlist.toString());
            playlist.getJSONObject(0).put("position", 2); refuses(() -> api.removePlaylistEntry(c, item.getString("uri"), entry, expected));
            reset(); api.removePlaylistEntry(c, item.getString("uri"), new BrowserItem(playlist.getJSONObject(0)), new JSONArray(playlist.toString()));
            check(writes.equals(Arrays.asList("music/playlists/remove_playlist_tracks"))); check(lastArgs.getJSONArray("positions_to_remove").getInt(0) == 1);
            reset(); item.put("is_dynamic", true); refuses(() -> api.addTracks(c, item.getString("uri"), new JSONArray().put("library://track/1")));
            reset(); api.addTracks(c, item.getString("uri"), new JSONArray().put("library://track/1")); check(writes.size() == 1);
            reset(); api.favorite(c, item.getString("uri"), false); check(!item.optBoolean("favorite") && writes.size() == 1);
            reset(); roles = args("admin", new JSONArray()); FeatureApi.LibraryInfo denied = api.library(c, item.getString("uri")); check(!denied.canRemove && !denied.canAdd);
            refuses(() -> api.removeLibrary(c, denied, null, false));
            reset();item.put("media_type","track").put("uri","library://track/1").put("favorite",false).put("provider_mappings",new JSONArray());FeatureApi.LibraryInfo ordinary=api.library(c,item.getString("uri"));check(ordinary.inLibrary&&ordinary.canRemove&&!ordinary.canAdd);
            api.removeLibrary(c,ordinary,null,false);check(writes.equals(Arrays.asList("music/tracks/remove")));check(!lastArgs.getBoolean("recursive"));
            reset(); FeatureApi.LibraryInfo info = api.library(c, item.getString("uri")); check(info.canRemove && info.deletion != null);
            providers = new JSONArray(); refuses(() -> api.removeLibrary(c, info, null, true));
            reset(); FeatureApi.LibraryInfo remove = api.library(c, item.getString("uri")); api.removeLibrary(c, remove, null, false);
            check(writes.equals(Arrays.asList("music/playlists/remove"))); check(!lastArgs.getBoolean("recursive"));
            reset(); item.put("media_type", "album").put("uri", "library://album/1"); FeatureApi.LibraryInfo album = api.library(c, item.getString("uri"));
            user.put("provider_filter", new JSONArray().put("restricted")); refuses(() -> api.albumTracks(c, album));
            reset(); item.put("media_type", "album").put("uri", "library://album/1"); FeatureApi.LibraryInfo album2 = api.library(c, item.getString("uri"));
            linked.put(args("uri", "library://track/1", "provider", "library", "media_type", "track"));
            JSONArray preview = api.albumTracks(c, album2); linked.put(args("uri", "library://track/2", "provider", "library", "media_type", "track"));
            refuses(() -> api.removeLibrary(c, album2, preview, false));
            reset();item.put("media_type","album").put("uri","library://album/1");FeatureApi.LibraryInfo recursiveAlbum=api.library(c,item.getString("uri"));JSONArray allTracks=api.albumTracks(c,recursiveAlbum);api.removeLibrary(c,recursiveAlbum,allTracks,false);check(writes.equals(Arrays.asList("music/albums/remove")));check(lastArgs.getBoolean("recursive"));
            reset(); refuses(() -> api.queueItem(c, "echo", "echo", "a", true));
            reset(); queue.put("index_in_buffer", 1); refuses(() -> api.queueItem(c, "echo", "echo", "b", true));
            reset();api.queueItem(c,"echo","echo","b",true);
            check(writes.equals(Arrays.asList("player_queues/delete_item")));check(queueItems.length()==1&&queueItems.getJSONObject(0).optString("queue_item_id").equals("a"));
            reset();queue.put("current_index",1).put("index_in_buffer",JSONObject.NULL);
            api.queueItem(c,"echo","echo","a",true);check(queueItems.length()==1&&writes.equals(Arrays.asList("player_queues/delete_item")));
            check(!FeatureApi.canRemoveQueueItem(queue,1));queue.put("ended",true);check(FeatureApi.canRemoveQueueItem(queue,1));
            queue.put("index_in_buffer",0);check(!FeatureApi.canRemoveQueueItem(queue,0));
            JSONObject timed=args("elapsed_time",10,"elapsed_time_last_updated",100,"state","playing");
            check(Math.abs(FeatureApi.elapsed(timed,102.5)-12.5)<.001);timed.put("state","paused");check(FeatureApi.elapsed(timed,102.5)==10);
            timed.put("state","playing");check(FeatureApi.elapsed(timed,145)==55);
            timed.put("playback_speed",2);check(FeatureApi.elapsed(timed,145)==100);check(FeatureApi.elapsed(timed,99)==10);
            reset();JSONObject local=players.getJSONObject(1),destination=players.getJSONObject(0);
            check(FeatureApi.localJoinCandidate(players,destination,local,"other"));
            destination.put("hide_in_ui",true);check(!FeatureApi.localJoinCandidate(players,destination,local,"other"));destination.put("hide_in_ui",false);
            destination.put("private",true);check(FeatureApi.localJoinCandidate(players,destination,local,"other"));
            refuses(()->api.joinLocal(c,"other","changed",Collections.singleton("other"),"echo",Collections.singleton("echo")));
            refuses(()->api.joinLocal(c,"other","other",Collections.singleton("other"),"echo",Collections.singleton("changed")));
            local.put("static_group_members",new JSONArray().put("other"));
            refuses(()->api.joinLocal(c,"other","other",Collections.singleton("other"),"echo",Collections.singleton("echo")));
            reset();ignoreDelete=true;boolean unconfirmed=false;
            try{api.queueItem(c,"echo","echo","b",true);}catch(IllegalArgumentException unverifiedRemoval){unconfirmed=true;}
            check(unconfirmed&&Collections.frequency(writes,"player_queues/delete_item")==1);
            reset(); refuses(() -> api.queueItem(c, "echo", "changed", "b", false));
            reset(); api.queueItem(c, "echo", "echo", "b", false); check(writes.equals(Arrays.asList("player_queues/play_index")));
            reset(); queueItems.getJSONObject(1).put("queue_item_id", "a"); refuses(() -> api.allQueue(c, queue));
            reset(); queue.put("items", 10001); refuses(() -> api.allQueue(c, queue));
            reset(); queue.put("queue_id", "wrong"); refuses(() -> c.queue("echo"));
            reset(); current = false; refuses(() -> c.write("player_queues/clear", args("queue_id", "echo")));
            reset(); refuses(() -> c.write("auth/token", args()));
            reset(); FeatureApi.Connection revoked = api.new Connection(session, () -> true, () -> { throw new IllegalArgumentException("Identity changed"); });
            refuses(() -> revoked.write("player_queues/clear", args("queue_id", "echo"))); check(!revoked.submitted);
            reset(); refuses(() -> api.create(c, "", ""));
            reset(); refuses(() -> api.create(c, "Test", "unknown"));
            reset(); fail = "music/playlists/create_playlist"; boolean interrupted = false;
            try { api.create(c, "Test", ""); } catch (Exception expectedFailure) { interrupted = true; }
            check(interrupted && Collections.frequency(writes, "music/playlists/create_playlist") == 1 && c.submitted);
            reset(); check(api.editablePlaylists(c).length() == 1); item.put("is_dynamic", true); check(api.editablePlaylists(c).length() == 0);
            testPagesAndLyrics();
            System.out.println("PASS: " + checks + " native feature, pagination and lyrics checks (loopback only)");
        } finally { server.stop(0); }
    }
    static void testPagesAndLyrics() throws Exception {
        JSONArray first = new JSONArray(), second = new JSONArray();
        for (int i = 0; i < 48; i++) { first.put(args("name", "Album " + i, "uri", "library://album/" + i)); second.put(args("name", "Album " + (48 + i), "uri", "library://album/" + (48 + i))); }
        BrowserPage p = BrowserPage.library("Library", first, 0), more = BrowserPage.library("Library", second, 48), combined = p.append(more);
        check(p.rows.size() == 48 && combined.rows.size() == 96 && combined.offset == 48 && combined.next);
        check(combined.rows.get(0).name.equals("Album 0") && combined.rows.get(95).name.equals("Album 95"));
        refuses(() -> combined.append(p));
        BrowserPage end = combined.append(BrowserPage.library("Library", new JSONArray(), 96)); check(!end.next && end.rows.size() == 96);
        BrowserPage capped = p; for (int offset = 48; offset < 1968; offset += 48) capped = capped.append(BrowserPage.library("Library", second, offset));
        check(capped.rows.size() == 1968 && !capped.next && capped.message.startsWith("Browse limit"));
        JSONObject search = new JSONObject(); for (String category : new String[]{"albums", "playlists", "artists", "radios", "tracks", "podcasts", "audiobooks"}) search.put(category, first);
        BrowserPage results = BrowserPage.search(search); check(results.rows.size() == 175); check(results.rows.get(100).section.equals("Tracks"));
        LyricsData lrc = LyricsData.parse("Plain", "[offset:-500]\n[00:01.00][00:03.00]Hello\n[00:02.00]World");
        check(lrc.lines.size() == 3 && lrc.lines.get(0).time == 0.5 && lrc.lines.get(1).text.equals("World"));
        check(lrc.index(0) == -1 && lrc.index(2.6) == 2);
        check(LyricsData.parse("Plain text", "").plain.equals("Plain text"));
    }
}
