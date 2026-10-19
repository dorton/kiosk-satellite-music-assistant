package net.markandrew.kiosk.music;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NowPlayingDataTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        JSONObject player = new JSONObject().put("name", "Explicit player").put("playback_state", "playing")
            .put("volume_level", 150).put("volume_control", "native")
            .put("supported_features", new JSONArray(new String[]{"pause", "seek", "next_previous"}));
        JSONObject track = new JSONObject().put("name", "A track").put("media_type", "track").put("duration", 200)
            .put("artists", new JSONArray().put(new JSONObject().put("name", "Artist")));
        JSONObject queue = new JSONObject().put("queue_id", "expected-queue").put("elapsed_time", 42)
            .put("current_item", new JSONObject().put("queue_item_id", "stable-item").put("media_item", track));
        NowPlayingData data = new NowPlayingData(player, queue);
        check(data.seek && data.skip && data.pause && data.volumeControl);
        check(data.volume == 100 && data.elapsed == 42 && data.duration == 200);
        check(data.title.equals("A track") && data.artist.equals("Artist") && data.queueId.equals("expected-queue"));
        track.put("media_type", "radio");
        check(!new NowPlayingData(player, queue).seek && new NowPlayingData(player, queue).live);
        track.put("media_type", "track"); queue.getJSONObject("current_item").remove("queue_item_id");
        check(!new NowPlayingData(player, queue).seek);
        player.put("supported_features", new JSONArray()).put("volume_control", "none");
        data = new NowPlayingData(player, queue);
        check(!data.pause && !data.skip && !data.volumeControl);
        data = new NowPlayingData(new JSONObject(), new JSONObject());
        check(data.live && !data.seek && data.image.isEmpty());
        System.out.println("NowPlayingData: 9 parsing/capability checks passed");
    }
}
