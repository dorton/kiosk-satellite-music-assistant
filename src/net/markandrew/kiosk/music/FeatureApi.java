package net.markandrew.kiosk.music;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Native port of the reference card's guarded queue/library/playlist operations. */
final class FeatureApi {
    final MusicApi api = new MusicApi();
    interface BeforeWrite { void verify() throws Exception; }
    final class Connection {
        final IngressAccess.Session session;
        final BooleanSupplier current;
        boolean submitted;
        final BeforeWrite beforeWrite;
        Connection(IngressAccess.Session session, BooleanSupplier current) { this(session, current, () -> {}); }
        Connection(IngressAccess.Session session, BooleanSupplier current, BeforeWrite beforeWrite) { this.session = session; this.current = current; this.beforeWrite = beforeWrite; }
        Object read(String command, JSONObject args) throws Exception {
            require(current.getAsBoolean(), "This view or selected player changed. Reopen the action.");
            Object result = api.command(session, command, args);
            require(current.getAsBoolean(), "This view changed; late results were discarded."); return result;
        }
        JSONObject object(String command, JSONObject args) throws Exception { Object v = read(command, args); require(v instanceof JSONObject, "Music Assistant returned incomplete information."); return (JSONObject) v; }
        JSONArray array(String command, JSONObject args) throws Exception { Object v = read(command, args); require(v instanceof JSONArray, "Music Assistant returned incomplete information."); if("players/all".equals(command))VolumeDisplay.annotate((JSONArray)v);return (JSONArray) v; }
        Object write(String command, JSONObject args) throws Exception {
            require(current.getAsBoolean(), "Action cancelled before submission."); beforeWrite.verify();
            submitted = true; return api.mutate(session, command, args, current);
        }
        JSONObject item(String uri) throws Exception { return object("music/item_by_uri", args("uri", uri)); }
        JSONObject queue(String player) throws Exception {
            PlayerPolicy.Snapshot snapshot = api.snapshot(session);
            DefaultPlayerPolicy.selected(snapshot, player);
            PlayerPolicy.Player leader = snapshot.leader(player);
            FeatureApi.require(leader != null && leader.available, "Queue leader is unavailable.");
            PlayerPolicy.Queue q = snapshot.queues.get(snapshot.queue(player));
            FeatureApi.require(q != null && q.available, "The saved MA queue is unavailable.");
            JSONObject result = object("player_queues/get", args("queue_id", snapshot.queue(player)));
            require(snapshot.queue(player).equals(result.optString("queue_id")) && result.optBoolean("available", true), "Queue identity or availability changed.");
            return result;
        }
    }
    static JSONObject args(Object... pairs) throws Exception {
        JSONObject a = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) a.put((String) pairs[i], pairs[i + 1]); return a;
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    static String controller(String type) {
        switch (type) { case "album": return "albums"; case "artist": return "artists"; case "track": return "tracks";
            case "playlist": return "playlists"; case "radio": return "radios"; case "podcast": return "podcasts"; case "audiobook": return "audiobooks";
            default: throw new IllegalArgumentException("This media type does not support library actions."); }
    }
    static boolean contains(JSONArray array, String value) { if (array != null) for (int i = 0; i < array.length(); i++) if (value.equals(array.optString(i))) return true; return false; }
    static JSONObject byId(JSONArray array, String field, String id) {
        for (int i = 0; i < array.length(); i++) { JSONObject row = array.optJSONObject(i); if (row != null && id.equals(row.optString(field))) return row; }
        return null;
    }
    static String ref(JSONObject row, String field) { return PlayerPolicy.reference(row.opt(field), JSONObject.NULL); }
    JSONObject favoriteItem(Connection c, String uri) throws Exception {
        JSONObject raw = c.item(uri); controller(raw.optString("media_type"));
        if ("library".equals(raw.optString("provider"))) return raw;
        for (int offset = 0; offset < 500; offset += 100) {
            JSONArray candidates = c.array("music/" + controller(raw.optString("media_type")) + "/library_items", args("search", raw.optString("name"), "limit", 100, "offset", offset, "summary", false));
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.getJSONObject(i); JSONArray mappings = candidate.optJSONArray("provider_mappings");
                if (mappings != null) for (int j = 0; j < mappings.length(); j++) {
                    JSONObject mapping = mappings.getJSONObject(j);
                    if (raw.optString("item_id").equals(mapping.optString("item_id")) && (raw.optString("provider").equals(mapping.optString("provider_instance")) || raw.optString("provider").equals(mapping.optString("provider_domain")))) return candidate;
                }
            }
            if (candidates.length() < 100) return raw;
        }
        throw new IllegalArgumentException("Could not safely resolve the library identity. Nothing was changed.");
    }
    void favorite(Connection c, String uri, boolean enabled) throws Exception {
        JSONObject raw = favoriteItem(c, uri);
        if (enabled) c.write("music/favorites/add_item", args("item", raw.getString("uri")));
        else {
            require("library".equals(raw.optString("provider")), "Could not resolve the favourite's library identity.");
            c.write("music/favorites/remove_item", args("media_type", raw.getString("media_type"), "library_item_id", raw.getString("item_id")));
        }
        require(favoriteItem(c, uri).optBoolean("favorite") == enabled, "Favourite update is unverified. Check MA before retrying.");
    }
    static final class LibraryInfo {
        JSONObject raw, deletion;
        boolean inLibrary, canAdd, canRemove;
    }
    LibraryInfo library(Connection c, String uri) throws Exception {
        LibraryInfo info = new LibraryInfo(); info.raw = c.item(uri); controller(info.raw.optString("media_type"));
        JSONObject user = (JSONObject) api.identity(c.session);
        JSONObject scopes = c.object("auth/scopes", args()); JSONArray permissions = scopes.optJSONArray(user.optString("role"));
        info.inLibrary = "library".equals(info.raw.optString("provider"));
        JSONArray mappings = info.raw.optJSONArray("provider_mappings"); JSONArray owners = new JSONArray();
        if (mappings != null) for (int i = 0; i < mappings.length(); i++) if (mappings.getJSONObject(i).optBoolean("in_library")) owners.put(mappings.getJSONObject(i));
        info.canAdd = !info.inLibrary && (contains(permissions, "*") || contains(permissions, "library.write"));
        info.canRemove = info.inLibrary && (contains(permissions, "*") || contains(permissions, "library.manage"));
        if (info.inLibrary && "playlist".equals(info.raw.optString("media_type")) && info.raw.optBoolean("is_editable") && !info.raw.optBoolean("is_dynamic")
            && (contains(permissions, "*") || contains(permissions, "library.write")) && (contains(permissions, "*") || contains(permissions, "config.providers.read")) && owners.length() == 1) {
            JSONObject owner = owners.getJSONObject(0); String domain = owner.optString("provider_domain");
            if (domain.equals("builtin") || domain.equals("qobuz")) {
                JSONObject provider = byId(c.array("providers", args("provider_type", "music")), "instance_id", owner.optString("provider_instance"));
                if (provider != null && provider.optBoolean("available") && contains(provider.optJSONArray("supported_features"), "library_playlists_edit")
                    && Boolean.TRUE.equals(c.read("config/providers/get_value", args("instance_id", provider.getString("instance_id"), "key", "library_sync_back"))))
                    info.deletion = args("providerId", provider.getString("instance_id"), "name", provider.optString("name", domain), "uri", provider.getString("instance_id") + "://playlist/" + owner.getString("item_id"));
            }
        }
        return info;
    }
    JSONArray albumTracks(Connection c, LibraryInfo info) throws Exception {
        JSONObject user = (JSONObject) api.identity(c.session); JSONArray filter = user.optJSONArray("provider_filter");
        require(filter != null && filter.length() == 0, "Album-and-tracks removal requires an unrestricted provider view.");
        LibraryInfo fresh = library(c, info.raw.getString("uri")); require(fresh.canRemove && "album".equals(fresh.raw.optString("media_type")), "Album removal permission or identity changed.");
        JSONArray tracks = c.array("music/albums/album_tracks", args("item_id", fresh.raw.getString("item_id"), "provider_instance_id_or_domain", "library", "in_library_only", true));
        require(tracks.length() < 500, "The affected-track list may be incomplete. Nothing was removed.");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < tracks.length(); i++) { JSONObject t = tracks.getJSONObject(i); require("library".equals(t.optString("provider")) && "track".equals(t.optString("media_type")) && !t.optString("uri").isEmpty() && ids.add(t.getString("uri")), "Unreliable affected-track identity. Nothing was removed."); }
        return tracks;
    }
    void removeLibrary(Connection c, LibraryInfo expected, JSONArray tracks, boolean delete) throws Exception {
        LibraryInfo fresh = library(c, expected.raw.getString("uri")); require(fresh.inLibrary && fresh.raw.getString("uri").equals(expected.raw.getString("uri")), "Library identity changed. Nothing was removed.");
        if (tracks != null) {
            require(!delete && "album".equals(fresh.raw.optString("media_type")), "Recursive removal is only for confirmed album-and-tracks previews.");
            JSONArray current = albumTracks(c, fresh); require(uriSet(current).equals(uriSet(tracks)) && current.length() == tracks.length(), "Linked tracks changed. Review and confirm again.");
        }
        if (delete) {
            require(expected.deletion != null && fresh.deletion != null && expected.deletion.getString("uri").equals(fresh.deletion.getString("uri")), "Provider owner/deletion support changed. Nothing was submitted.");
            c.write("music/library/remove_item", args("media_type", "playlist", "library_item_id", fresh.raw.getString("item_id"), "recursive", false));
        } else {
            require(fresh.canRemove, "Library-management permission is required.");
            c.write("music/" + controller(fresh.raw.getString("media_type")) + "/remove", args("item_id", fresh.raw.getString("item_id"), "recursive", tracks != null));
        }
        require(Boolean.FALSE.equals(c.read("music/verify_item_uri", args("uri", fresh.raw.getString("uri")))), "Removal is unverified. Inspect MA before retrying.");
        if (tracks != null) for (String uri : uriSet(tracks)) require(Boolean.FALSE.equals(c.read("music/verify_item_uri", args("uri", uri))), "Some linked-track removals are unverified. Inspect MA before retrying.");
        if (delete) {
            JSONObject owner = byId(c.array("providers", args("provider_type", "music")), "instance_id", fresh.deletion.getString("providerId"));
            require(owner != null && owner.optBoolean("available") && Boolean.FALSE.equals(c.read("music/verify_item_uri", args("uri", fresh.deletion.getString("uri")))), "Provider deletion is unverified. Inspect MA before retrying.");
        }
    }
    static Set<String> uriSet(JSONArray rows) throws Exception { Set<String> result = new HashSet<>(); for (int i = 0; i < rows.length(); i++) result.add(rows.getJSONObject(i).getString("uri")); return result; }
    JSONArray editablePlaylists(Connection c) throws Exception {
        JSONArray result = new JSONArray();
        for (int offset = 0; offset < 10000; offset += 100) {
            JSONArray page = c.array("music/playlists/library_items", args("limit", 100, "offset", offset, "summary", false, "order_by", "name"));
            for (int i = 0; i < page.length(); i++) { JSONObject p = page.getJSONObject(i); if (p.optBoolean("is_editable") && !p.optBoolean("is_dynamic") && (p.optJSONArray("supported_mediatypes") == null || contains(p.optJSONArray("supported_mediatypes"), "track"))) result.put(p); }
            if (page.length() < 100) return result;
        }
        throw new IllegalArgumentException("Playlist list exceeds the safe browse limit. Narrow the MA library first.");
    }
    JSONArray playlistProviders(Connection c) throws Exception {
        JSONArray result = new JSONArray(), all = c.array("providers", args("provider_type", "music"));
        for (int i = 0; i < all.length(); i++) { JSONObject p = all.getJSONObject(i); JSONArray f = p.optJSONArray("supported_features");
            if (p.optBoolean("available") && !p.optString("instance_id").isEmpty() && !p.optString("domain").equals("builtin") && contains(f, "playlist_tracks_edit") && (contains(f, "playlist_create") || contains(f, "playlist_create_tracks"))) result.put(p); }
        return result;
    }
    JSONObject create(Connection c, String name, String provider) throws Exception {
        require(!name.trim().isEmpty() && name.length() <= 200, "Enter a playlist name.");
        if (!provider.isEmpty()) require(byId(playlistProviders(c), "instance_id", provider) != null, "Provider is no longer available or editable. Nothing was created.");
        JSONObject a = args("name", name.trim(), "media_types", new JSONArray().put("track")); if (!provider.isEmpty()) a.put("provider_instance_or_domain", provider);
        Object result = c.write("music/playlists/create_playlist", a); require(result instanceof JSONObject, "Playlist creation is unverified. Check MA before retrying."); return (JSONObject) result;
    }
    void addTracks(Connection c, String playlistUri, JSONArray uris) throws Exception {
        JSONObject fresh = c.item(playlistUri);
        require("library".equals(fresh.optString("provider")) && fresh.optBoolean("is_editable") && !fresh.optBoolean("is_dynamic"), "Destination is no longer an editable library playlist.");
        c.write("music/playlists/add_playlist_tracks", args("db_playlist_id", fresh.getString("item_id"), "uris", uris));
    }
    void removePlaylistEntry(Connection c, String playlistUri, BrowserItem entry, JSONArray expected) throws Exception {
        require(entry.position > 0 && !entry.uri.isEmpty() && expected != null, "No reliable playlist occurrence snapshot. Refresh first.");
        JSONObject fresh = c.item(playlistUri);
        require("library".equals(fresh.optString("provider")) && fresh.optBoolean("is_editable") && !fresh.optBoolean("is_dynamic"), "This playlist is no longer editable.");
        JSONArray rows = c.array("music/playlists/playlist_tracks", args("item_id", fresh.getString("item_id"), "provider_instance_id_or_domain", fresh.getString("provider"), "force_refresh", true));
        require(samePlaylist(rows, expected), "Playlist positions changed. Nothing was removed; refresh and select the occurrence again.");
        boolean found = false; for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).optInt("position", -1) == entry.position && entry.uri.equals(rows.getJSONObject(i).optString("uri"))) found = true;
        require(found, "Playlist occurrence is missing. Nothing was removed.");
        c.write("music/playlists/remove_playlist_tracks", args("db_playlist_id", fresh.getString("item_id"), "positions_to_remove", new JSONArray().put(entry.position)));
    }
    static boolean samePlaylist(JSONArray a, JSONArray b) throws Exception {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optInt("position", -1) != b.getJSONObject(i).optInt("position", -1) || !a.getJSONObject(i).optString("uri").equals(b.getJSONObject(i).optString("uri"))) return false;
        return true;
    }
    static String queueMark(JSONObject q) { JSONObject item = q.optJSONObject("current_item"); return q.optString("queue_id") + ":" + q.optInt("items") + ":" + q.optInt("current_index", -1) + ":" + (item == null ? "" : item.optString("queue_item_id")); }
    JSONArray allQueue(Connection c, JSONObject q) throws Exception {
        int count = q.optInt("items"); require(count <= 10000, "Queue exceeds the 10,000-item safe operation limit.");
        JSONArray rows = new JSONArray(); Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < count; offset += 100) {
            JSONArray page = c.array("player_queues/items", args("queue_id", q.getString("queue_id"), "offset", offset, "limit", 100));
            require(page.length() > 0, "Queue changed during the read.");
            for (int i = 0; i < page.length(); i++) { JSONObject row = page.getJSONObject(i); require(!row.optString("queue_item_id").isEmpty() && seen.add(row.getString("queue_item_id")), "Queue snapshot contains missing/duplicate identities."); rows.put(row); }
        }
        JSONObject latest = c.object("player_queues/get", args("queue_id", q.getString("queue_id")));
        require(count == rows.length() && queueMark(q).equals(queueMark(latest)), "Queue changed during the read. Nothing was submitted.");
        JSONArray second = new JSONArray();
        for (int offset = 0; offset < count; offset += 100) {
            JSONArray page = c.array("player_queues/items", args("queue_id", q.getString("queue_id"), "offset", offset, "limit", 100));
            for (int i = 0; i < page.length(); i++) second.put(page.getJSONObject(i));
        }
        require(sameQueueRows(rows, second) && queueMark(q).equals(queueMark(c.object("player_queues/get", args("queue_id", q.getString("queue_id"))))), "Queue contents changed between snapshot passes. Nothing was submitted."); return rows;
    }
    static boolean sameQueueRows(JSONArray a, JSONArray b) {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i), y = b.optJSONObject(i);
            if (x == null || y == null || !x.optString("queue_item_id").equals(y.optString("queue_item_id"))) return false;
            JSONObject xm = x.optJSONObject("media_item"), ym = y.optJSONObject("media_item");
            if (!Objects.equals(xm == null ? null : xm.optString("uri"), ym == null ? null : ym.optString("uri")) || x.optBoolean("available", true) != y.optBoolean("available", true)) return false;
        }
        return true;
    }
    void queueItem(Connection c, String player, String expectedQueue, String itemId, boolean remove) throws Exception {
        JSONObject q = c.queue(player); require(expectedQueue.equals(q.optString("queue_id")), "Selected player queue changed.");
        JSONArray all = allQueue(c, q); int index = -1;
        for (int i = 0; i < all.length(); i++) if (itemId.equals(all.getJSONObject(i).optString("queue_item_id"))) index = i;
        require(index >= 0 && all.getJSONObject(index).optBoolean("available", true), "Queue item is missing or unavailable.");
        if (remove) {
            JSONObject latest = c.object("player_queues/get", args("queue_id", expectedQueue));
            require(canRemoveQueueItem(latest,index), "This item is current or protected by MA's committed buffer. Try again after the buffer is released.");
            c.write("player_queues/delete_item", args("queue_id", expectedQueue, "item_id_or_index", itemId));
            require(byId(allQueue(c, c.object("player_queues/get", args("queue_id", expectedQueue))), "queue_item_id", itemId) == null, "Removal is unverified; MA may have buffered the item. Do not blindly retry.");
        } else c.write("player_queues/play_index", args("queue_id", expectedQueue, "index", itemId));
    }
    static boolean canRemoveQueueItem(JSONObject queue,int index){
        int current=queue.optInt("current_index",-1);
        if(!queue.optBoolean("ended")&&index==current)return false;
        if(!queue.has("index_in_buffer"))return index>current; // Missing is not evidence of a released buffer.
        return queue.isNull("index_in_buffer")||index>Math.max(current,queue.optInt("index_in_buffer",-1));
    }
    static double elapsed(JSONObject queue,double nowSeconds){
        double value=Math.max(0,queue.optDouble("elapsed_time",0));
        double updated=queue.optDouble("elapsed_time_last_updated",0),delta=nowSeconds-updated;
        double speed=queue.optDouble("playback_speed",1);
        if(!Double.isFinite(speed)||speed<0.25||speed>4)speed=1;
        if("playing".equals(queue.optString("state"))&&updated>0&&Double.isFinite(delta)&&delta>=0&&delta<=864000)value+=delta*speed;
        return Double.isFinite(value)?Math.min(864000,value):0;
    }
    static JSONObject groupLeader(JSONArray rows, String selected) throws Exception {
        JSONObject p = byId(rows, "player_id", selected); Set<String> seen = new HashSet<>();
        while (p != null) {
            require(seen.add(p.getString("player_id")) && seen.size() <= 8, "Invalid or cyclic player group.");
            String parent = !ref(p, "active_group").isEmpty() ? ref(p, "active_group") : ref(p, "synced_to");
            if (parent.isEmpty()) return p;
            p = byId(rows, "player_id", parent); require(p != null, "The group leader is unavailable.");
        }
        throw new IllegalArgumentException("Choose an available default player in Setup.");
    }
    static String memberLabel(boolean leader,boolean grouped,boolean fixed) {
        return leader ? grouped ? "Group leader" : "Independent player" : fixed ? "Fixed member" : "Group member · tap to remove";
    }
    /** Stable partition: leader, active members, then remaining server-order choices. */
    static List<JSONObject> groupRows(JSONArray rows, String leader, Set<String> members) {
        List<JSONObject> result = new ArrayList<>();
        for (int section = 0; section < 3; section++) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                String id = row.optString("player_id");
                int rank = leader.equals(id) ? 0 : members.contains(id) ? 1 : 2;
                if (rank == section) result.add(row);
            }
        }
        return result;
    }
    static Set<String> members(JSONArray rows, JSONObject leader) {
        Set<String> result = new LinkedHashSet<>(); JSONArray ids = leader.optJSONArray("group_members");
        if (ids != null) for (int i = 0; i < ids.length(); i++) result.add(ids.optString(i));
        String id = leader.optString("player_id");
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && (id.equals(ref(row, "active_group")) || id.equals(ref(row, "synced_to")))) result.add(row.optString("player_id")); }
        if (!leader.optString("type").equals("group")) result.add(id); return result;
    }
    void member(Connection c, String selected, String leaderId, String member, boolean remove) throws Exception {
        JSONArray rows = c.array("players/all", args("return_unavailable", true)); JSONObject leader = groupLeader(rows, selected), child = byId(rows, "player_id", member);
        require(leaderId.equals(leader.optString("player_id")) && leader.optBoolean("available") && contains(leader.optJSONArray("supported_features"), "set_members"), "Group leader or membership support changed.");
        Set<String> existing = members(rows, leader);
        if (remove) require(existing.contains(member) && !member.equals(leaderId) && !contains(leader.optJSONArray("static_group_members"), member), "Fixed/leader/missing members cannot be removed.");
        else require(child != null && child.optBoolean("available") && child.optBoolean("enabled",true) && !child.optBoolean("hidden") && !child.optBoolean("hide_in_ui") && (child.optString("type").equals("player") || child.optString("type").equals("stereo_pair")) && !existing.contains(member) && contains(leader.optJSONArray("can_group_with"), member), "These players cannot group together.");
        c.write("players/cmd/set_members", args("target_player", leaderId, remove ? "player_ids_to_remove" : "player_ids_to_add", new JSONArray().put(member)));
    }
    static boolean localJoinCandidate(JSONArray rows,JSONObject leader,JSONObject child,String oldLeader){
        if(leader==null||child==null)return false;
        if(!child.optString("type").equals("player")&&!child.optString("type").equals("stereo_pair"))return false;
        String id=leader.optString("player_id"),local=child.optString("player_id");
        if(id.isEmpty()||id.equals(local)||id.equals(oldLeader)||!leader.optBoolean("available")||!leader.optBoolean("enabled",true)||leader.optBoolean("hidden")||leader.optBoolean("hide_in_ui"))return false;
        try{return id.equals(groupLeader(rows,id).optString("player_id"))&&contains(leader.optJSONArray("supported_features"),"set_members")
            &&(contains(leader.optJSONArray("can_group_with"),local)||!child.optString("provider").isEmpty()&&contains(leader.optJSONArray("can_group_with"),child.optString("provider")));
        }catch(Exception invalid){return false;}
    }
    String joinLocal(Connection c,String local,String oldLeader,Set<String> oldMembers,String target,Set<String> targetMembers)throws Exception{
        JSONArray rows=c.array("players/all",args("return_unavailable",true));JSONObject child=byId(rows,"player_id",local),source=groupLeader(rows,local),leader=byId(rows,"player_id",target);
        require(child!=null&&child.optBoolean("available")&&child.optBoolean("enabled",true),"Local receiver is unavailable.");
        require(oldLeader.equals(source.optString("player_id"))&&oldMembers.equals(members(rows,source)),"Local group changed. Reopen Join group.");
        require(!contains(source.optJSONArray("static_group_members"),local),"This device is a fixed member of its current group.");
        require(localJoinCandidate(rows,leader,child,oldLeader)&&targetMembers.equals(members(rows,leader)),"Destination group or compatibility changed. Reopen Join group.");
        c.write("players/cmd/set_members",args("target_player",target,"player_ids_to_add",new JSONArray().put(local)));
        JSONArray fresh=c.array("players/all",args("return_unavailable",true));JSONObject checked=groupLeader(fresh,local);
        require(target.equals(checked.optString("player_id"))&&members(fresh,checked).contains(local),"Join submitted; membership not yet confirmed. Check MA before retrying.");
        return "Join verified · destination before="+playbackState(leader)+" · after="+playbackState(checked);
    }
    private static String playbackState(JSONObject player){String state=player.optString("playback_state");return Arrays.asList("playing","paused","idle").contains(state)?state:"other";}
}
