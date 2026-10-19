package net.markandrew.kiosk.music;

import org.json.JSONArray;
import org.json.JSONObject;

/** Detached, bounded display data for the explicitly selected player/queue. */
final class NowPlayingData {
    final String player, title, artist, image, state, queueState, queueId, itemId, mediaUri, repeat, device;
    final int groupSize;
    final boolean nativePause,canPlay;
    final boolean silent;
    final int duration, elapsed, volume;
    final boolean live, pause, skip, seek, volumeControl, muteControl, muted, shuffle, favorite, favoriteKnown, favoriteControl, queueControl, groupVolume, queueActive;
    NowPlayingData(JSONObject p, JSONObject q) {
        this(p, q, null);
    }
    NowPlayingData(JSONObject p, JSONObject q, Boolean resolvedFavorite) {
        player = BrowserPage.label(p.optString("name", "Player"));
        device="";
        state = p.optString("playback_state", "idle");
        queueState=q.optString("state","");
        queueId = q.optString("queue_id");
        JSONObject item = q.optJSONObject("current_item");
        if (item == null) item = new JSONObject();
        itemId = item.optString("queue_item_id");
        JSONObject media = item.optJSONObject("media_item");
        if (media == null) media = p.optJSONObject("current_media");
        if (media == null) media = new JSONObject();
        BrowserItem parsed = new BrowserItem(media);
        JSONObject radio=RadioMetadata.current(p,q,item,media);
        mediaUri = media.optString("uri"); favoriteKnown = resolvedFavorite != null;
        favorite = Boolean.TRUE.equals(resolvedFavorite);
        favoriteControl = !mediaUri.isEmpty() && "track".equals(media.optString("media_type"));
        shuffle = q.optBoolean("shuffle_enabled"); repeat = q.optString("repeat_mode", "off");
        queueControl = !queueId.isEmpty() && q.optInt("items", 0) > 0;
        queueActive = !queueId.isEmpty() && q.optBoolean("active") && q.optBoolean("available", true) && queueId.equals(p.optString("active_source"));
        JSONObject external=p.optJSONObject("current_media");
        canPlay=q.optBoolean("available",true)&&q.optInt("items",0)>0||!queueActive&&"paused".equals(state)&&external!=null&&!external.optString("uri").isEmpty();
        JSONArray members = p.optJSONArray("group_members");
        boolean hasOtherMember = false;
        if (members != null) for (int i = 0; i < members.length(); i++) if (!p.optString("player_id").equals(members.optString(i))) hasOtherMember = true;
        groupVolume = "group".equals(p.optString("type")) || hasOtherMember;
        java.util.Set<String> memberIds=new java.util.HashSet<>();
        if(!"group".equals(p.optString("type"))&&!p.optString("player_id").isEmpty())memberIds.add(p.optString("player_id"));
        if(members!=null)for(int i=0;i<members.length();i++)if(!members.optString(i).isEmpty())memberIds.add(members.optString(i));
        groupSize=memberIds.size();
        muted = p.optBoolean(groupVolume ? "group_volume_muted" : "volume_muted");
        silent=VolumeDisplay.silent(p,groupVolume);
        String mute = p.optString("mute_control", ""); muteControl = groupVolume || !mute.isEmpty() && !"none".equals(mute);
        title = RadioMetadata.text(radio,"title",BrowserPage.label(media.optString("name", item.optString("name", "Nothing queued"))));
        artist = RadioMetadata.text(radio,"artist",parsed.subtitle);
        duration = Math.max(0, Math.min(864000, media.optInt("duration", item.optInt("duration"))));
        elapsed = position(q,System.currentTimeMillis()/1000.0);
        volume = Math.max(0, Math.min(100, p.optInt(groupVolume ? "group_volume" : "volume_level")));
        live = "radio".equals(media.optString("media_type")) || duration == 0;
        String art = parsed.imagePath;
        JSONObject img = item.optJSONObject("image");
        if (art.isEmpty() && img != null) {
            try {
                JSONObject wrapped = new JSONObject().put("metadata", new JSONObject().put("images",
                    new JSONArray().put(new JSONObject(img.toString()).put("type", "thumb"))));
                art = new BrowserItem(wrapped).imagePath;
            } catch (Exception ignored) { /* Themed placeholder. */ }
        }
        String liveArt=RadioMetadata.image(radio.optString("image_url",""));
        image = liveArt.isEmpty()?art:liveArt;
        JSONArray features = p.optJSONArray("supported_features");
        nativePause=feature(features,"pause");
        pause = queueActive || feature(features, "pause"); skip = queueActive || feature(features, "next_previous");
        seek = !live && !itemId.isEmpty() && (queueActive || feature(features, "seek"));
        String control = p.optString("volume_control", "");
        volumeControl = groupVolume || !control.isEmpty() && !"none".equals(control);
    }
    NowPlayingData withFavorite(Boolean value) { return new NowPlayingData(this, value,player,device); }
    NowPlayingData withSelection(String id,String name,String ownId){
        return withSelection(id,name,ownId,id.equals(ownId)?name:"");
    }
    NowPlayingData withSelection(String id,String name,String ownId,String ownName){
        String label=player;
        if(groupVolume)label+=(groupSize>1?" + "+(groupSize-1):"")+" · Group";
        String subtitle=groupVolume?(ownName.isEmpty()?"Selected player: "+name:"This device: "+ownName+(id.equals(ownId)?"":" · Controlling: "+name)):"";
        return new NowPlayingData(this,favoriteKnown?favorite:null,label,subtitle);
    }
    private NowPlayingData(NowPlayingData d, Boolean value,String label,String device) {
        player=label;this.device=device;groupSize=d.groupSize; title=d.title; artist=d.artist; image=d.image; state=d.state;queueState=d.queueState; queueId=d.queueId;
        itemId=d.itemId; mediaUri=d.mediaUri; repeat=d.repeat; duration=d.duration; elapsed=d.elapsed; volume=d.volume;
        live=d.live; pause=d.pause; skip=d.skip; seek=d.seek; volumeControl=d.volumeControl; muteControl=d.muteControl;
        muted=d.muted;silent=d.silent; shuffle=d.shuffle; favorite=Boolean.TRUE.equals(value); favoriteKnown=value!=null;
        favoriteControl=d.favoriteControl; queueControl=d.queueControl; groupVolume=d.groupVolume; queueActive=d.queueActive;
        nativePause=d.nativePause;canPlay=d.canPlay;
    }
    private static boolean feature(JSONArray values, String feature) {
        if (values != null) for (int i = 0; i < values.length(); i++) if (feature.equals(values.optString(i))) return true;
        return false;
    }
    static int position(JSONObject queue,double now){
        double value=queue.optDouble("elapsed_time",0),updated=queue.optDouble("elapsed_time_last_updated",0),speed=queue.optDouble("playback_speed",1),delta=now-updated;
        if(!Double.isFinite(value))return 0;
        value=Math.max(0,value);if(!Double.isFinite(speed)||speed<0.25||speed>4)speed=1;
        if("playing".equals(queue.optString("state"))&&updated>0&&Double.isFinite(delta)&&delta>=0&&delta<=864000)value+=delta*speed;
        return (int)Math.max(0,Math.min(864000,value));
    }
}
