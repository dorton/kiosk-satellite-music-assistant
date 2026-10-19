package net.markandrew.kiosk.music;

import org.json.*;

public final class NowPlayingActionTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Now Playing " + checks); }
    static JSONObject player() throws Exception {
        return new JSONObject().put("player_id", "speaker").put("volume_control", "native").put("mute_control", "native")
            .put("volume_level", 45).put("supported_features", new JSONArray(new String[]{"pause", "seek", "next_previous"}));
    }
    static JSONObject queue() throws Exception {
        return new JSONObject().put("queue_id", "queue").put("items", 3).put("repeat_mode", "off")
            .put("current_item", new JSONObject().put("queue_item_id", "item").put("media_item",
                new JSONObject().put("uri", "library://track/1").put("media_type", "track").put("duration", 180)));
    }
    interface Job { void run() throws Exception; }
    static void rejects(Job job) throws Exception { try { job.run(); } catch (IllegalArgumentException expected) { check(true); return; } throw new AssertionError("Expected rejection"); }
    public static void main(String[] args) throws Exception {
        JSONObject p = player(), q = queue(); NowPlayingData data = new NowPlayingData(p,q);
        check(data.favoriteControl && data.queueControl && data.muteControl && !data.groupVolume);
        NowPlayingAction shuffle = new NowPlayingAction("shuffle",0,"speaker",data,data);
        check(shuffle.command.equals("player_queues/shuffle") && shuffle.args.getBoolean("shuffle_enabled"));
        check(shuffle.args.getString("queue_id").equals("queue") && !shuffle.args.has("player_id"));
        String[] modes = {"off","all","one"}, next = {"all","one","off"};
        for (int i=0;i<3;i++) {
            NowPlayingData current = new NowPlayingData(p,q.put("repeat_mode",modes[i]));
            check(new NowPlayingAction("repeat",0,"speaker",current,current).args.getString("repeat_mode").equals(next[i]));
        }
        check(new NowPlayingAction("volume_set",200,"speaker",data,data).args.getInt("volume_level")==100);
        check(new NowPlayingAction("seek",200,"speaker",data,data).args.getInt("position")==180);
        check(new NowPlayingAction("mute",0,"speaker",data,data).command.equals("players/cmd/volume_mute"));
        check(new NowPlayingAction("mute",0,"speaker",data,data).args.getBoolean("muted"));
        NowPlayingData changedQueue = new NowPlayingData(p,queue().put("queue_id","other"));
        rejects(() -> new NowPlayingAction("play",0,"speaker",data,changedQueue));
        JSONObject changed = queue(); changed.getJSONObject("current_item").put("queue_item_id","other");
        NowPlayingData changedTrack = new NowPlayingData(p,changed);
        rejects(() -> new NowPlayingAction("seek",0,"speaker",data,changedTrack));
        rejects(() -> new NowPlayingAction("unknown",0,"speaker",data,data));
        NowPlayingData restricted = new NowPlayingData(new JSONObject(),new JSONObject().put("queue_id","queue"));
        for(String action:new String[]{"pause","next","previous","seek","volume_set","mute","shuffle","repeat"})
            rejects(() -> new NowPlayingAction(action,0,"speaker",data,restricted));
        JSONObject group = player().put("group_members",new JSONArray(new String[]{"speaker","other"})).put("group_volume",60).put("group_volume_muted",true);
        NowPlayingData grouped = new NowPlayingData(group,queue());
        check(grouped.groupVolume && grouped.volume==60 && grouped.muted);
        check(new NowPlayingAction("volume_set",55,"speaker",grouped,grouped).command.equals("players/cmd/group_volume"));
        check(new NowPlayingAction("mute",0,"speaker",grouped,grouped).command.equals("players/cmd/group_volume_mute"));
        check(!new NowPlayingAction("mute",0,"speaker",grouped,grouped).args.getBoolean("muted"));
        rejects(() -> new NowPlayingAction("volume_set",50,"speaker",data,grouped));
        rejects(() -> new NowPlayingAction("mute",0,"speaker",data,grouped));
        check(!new NowPlayingData(player().put("group_members",new JSONArray(new String[]{"speaker"})),queue()).groupVolume);
        JSONObject nativePlayer=player().put("supported_features",new JSONArray()).put("active_source","queue");
        JSONObject activeQueue=queue().put("active",true);
        NowPlayingData active=new NowPlayingData(nativePlayer,activeQueue);
        check(active.pause && active.skip && active.seek && active.queueActive);
        for(String command:new String[]{"pause","next","previous","seek"})check(new NowPlayingAction(command,42,"speaker",active,active).command.startsWith("players/cmd/"));
        NowPlayingData external=new NowPlayingData(new JSONObject(nativePlayer.toString()).put("active_source","external"),activeQueue);
        check(!external.pause&&!external.skip&&!external.seek&&!external.queueActive);
        rejects(()->new NowPlayingAction("pause",0,"speaker",active,external));
        check(!active.favoriteKnown && !active.favorite);
        NowPlayingData empty=new NowPlayingData(player(),queue().put("items",0));check(!empty.canPlay);rejects(()->new NowPlayingAction("play",0,"speaker",data,empty));
        NowPlayingData sourcePaused=new NowPlayingData(player().put("playback_state","paused").put("current_media",new JSONObject().put("uri","external://stream")),queue().put("items",0));check(sourcePaused.canPlay);check(new NowPlayingAction("play",0,"speaker",sourcePaused,sourcePaused).command.equals("players/cmd/play"));
        check(active.withFavorite(true).favoriteKnown && active.withFavorite(true).favorite);
        check(active.withFavorite(false).favoriteKnown && !active.withFavorite(false).favorite);
        check(!NowPlayingResult.confirmed("pause",0,active,active));
        NowPlayingData paused=new NowPlayingData(new JSONObject(nativePlayer.toString()).put("playback_state","paused"),activeQueue);
        check(NowPlayingResult.confirmed("pause",0,active,paused));
        NowPlayingData playingFallback=new NowPlayingData(new JSONObject(nativePlayer.toString()).put("playback_state","playing"),new JSONObject(activeQueue.toString()).put("state","playing"));
        NowPlayingData stoppedFallback=new NowPlayingData(new JSONObject(nativePlayer.toString()).put("playback_state","idle"),new JSONObject(activeQueue.toString()).put("state","idle"));
        check(NowPlayingResult.confirmed("pause",0,playingFallback,stoppedFallback));
        check(!NowPlayingResult.confirmed("pause",0,new NowPlayingData(new JSONObject(nativePlayer.toString()).put("playback_state","playing").put("supported_features",new JSONArray().put("pause")),activeQueue),stoppedFallback));
        check(!NowPlayingResult.confirmed("pause",0,external,stoppedFallback));
        check(!NowPlayingResult.confirmed("pause",0,playingFallback,changedQueue));
        check(!NowPlayingResult.confirmed("volume_set",90,active,active));
        check(NowPlayingResult.confirmed("volume_set",90,active,new NowPlayingData(new JSONObject(nativePlayer.toString()).put("volume_level",90),activeQueue)));
        NowPlayingData mutedLevel=new NowPlayingData(new JSONObject(nativePlayer.toString()).put("volume_level",27).put("volume_muted",true),activeQueue);
        check(NowPlayingResult.levelConfirmed(27,active,mutedLevel));check(!NowPlayingResult.confirmed("volume_set",27,active,mutedLevel));
        check(NowPlayingResult.confirmed("volume_set",0,active,new NowPlayingData(new JSONObject(nativePlayer.toString()).put("volume_level",0).put("volume_muted",true),activeQueue)));
        NowPlayingData cannotUnmute=new NowPlayingData(new JSONObject(nativePlayer.toString()).put("volume_muted",true).put("mute_control","none"),activeQueue);
        rejects(()->new NowPlayingAction("volume_set",27,"speaker",active,cannotUnmute));
        check(NowPlayingResult.confirmed("shuffle",0,active,new NowPlayingData(nativePlayer,new JSONObject(activeQueue.toString()).put("shuffle_enabled",true))));
        check(MusicPanelLayout.width(640,false)==320 && MusicPanelLayout.width(960,false)==400);
        check(MusicPanelLayout.width(640,true)==560 && MusicPanelLayout.width(320,true)==296);
        for(int size:new int[]{0,10,200,640,960}) for(boolean center:new boolean[]{true,false}) {
            check(MusicPanelLayout.width(size,center)>=0 && MusicPanelLayout.width(size,center)<=size);
            check(MusicPanelLayout.height(size,center)>=0 && MusicPanelLayout.height(size,center)<=size);
        }
        System.out.println("NowPlayingAction: " + checks + " command, capability, stale identity, group and panel layout checks passed");
    }
}
