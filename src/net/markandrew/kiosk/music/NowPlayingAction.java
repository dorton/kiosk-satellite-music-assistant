package net.markandrew.kiosk.music;

import org.json.JSONObject;

/** Fresh capability/identity validation for one explicit Now Playing control. */
final class NowPlayingAction {
    final String command;
    final JSONObject args;
    NowPlayingAction(String action, int value, String leader, NowPlayingData expected, NowPlayingData latest) throws Exception {
        require(!leader.isEmpty() && expected.queueId.equals(latest.queueId), "Queue changed. Refresh first.");
        if (expected.queueActive && !latest.queueActive && !"volume_set".equals(action) && !"mute".equals(action))
            throw new IllegalArgumentException("Active source changed. Refresh before controlling it.");
        JSONObject values = new JSONObject();
        String name;
        switch (action) {
            case "play": require(latest.canPlay,"Nothing is available to play. Add music first.");name = "players/cmd/play"; break;
            case "pause": require(latest.pause, "Pause unavailable."); name = "players/cmd/pause"; break;
            case "previous": case "next": require(latest.skip, "Skipping unavailable."); name = "players/cmd/" + action; break;
            case "seek":
                require(latest.seek && expected.itemId.equals(latest.itemId), "Track changed or seeking unavailable.");
                name = "players/cmd/seek"; values.put("position", Math.max(0, Math.min(latest.duration, value))); break;
            case "volume_set": require(latest.volumeControl && expected.groupVolume == latest.groupVolume, "Volume target changed or unavailable.");require(value<=0||!latest.muted||latest.muteControl,"Unmute unavailable. No volume was changed."); name = latest.groupVolume ? "players/cmd/group_volume" : "players/cmd/volume_set"; values.put("volume_level", Math.max(0, Math.min(100, value))); break;
            case "mute": require(latest.muteControl && expected.groupVolume == latest.groupVolume, "Mute target changed or unavailable."); name = latest.groupVolume ? "players/cmd/group_volume_mute" : "players/cmd/volume_mute"; values.put("muted", !latest.muted); break;
            case "shuffle": require(latest.queueControl, "Queue unavailable."); name = "player_queues/shuffle"; values.put("shuffle_enabled", !latest.shuffle); break;
            case "repeat":
                require(latest.queueControl, "Queue unavailable."); name = "player_queues/repeat";
                values.put("repeat_mode", "off".equals(latest.repeat) ? "all" : "all".equals(latest.repeat) ? "one" : "off"); break;
            default: throw new IllegalArgumentException("Unsupported Now Playing control.");
        }
        values.put(name.startsWith("player_queues/") ? "queue_id" : "player_id", name.startsWith("player_queues/") ? latest.queueId : leader);
        command = name; args = values;
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
