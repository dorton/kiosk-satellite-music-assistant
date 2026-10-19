package net.markandrew.kiosk.music;

/** Checks returned state, not merely an HTTP success. Never resubmits a command. */
final class NowPlayingResult {
    static boolean confirmed(String action, int value, NowPlayingData before, NowPlayingData after) {
        if (!before.queueId.equals(after.queueId)) return false;
        switch (action) {
            case "play": return "playing".equals(after.state);
            case "pause": return "paused".equals(after.state)||after.queueActive&&"paused".equals(after.queueState)&&!"playing".equals(after.state)
                ||before.queueActive&&!before.nativePause&&"playing".equals(before.state)&&"idle".equals(after.state)&&"idle".equals(after.queueState);
            case "next": case "previous": return !after.itemId.isEmpty() && !after.itemId.equals(before.itemId);
            case "seek": return after.itemId.equals(before.itemId) && Math.abs(after.elapsed - Math.max(0, Math.min(before.duration,value))) <= 5;
            case "volume_set": return levelConfirmed(value,before,after)&&(value<=0||!after.muted);
            case "mute": return before.groupVolume == after.groupVolume && before.muted != after.muted;
            case "shuffle": return before.shuffle != after.shuffle;
            case "repeat": return ("off".equals(before.repeat) ? "all" : "all".equals(before.repeat) ? "one" : "off").equals(after.repeat);
            default: return false;
        }
    }
    static boolean levelConfirmed(int value,NowPlayingData before,NowPlayingData after){return before.queueId.equals(after.queueId)&&before.groupVolume==after.groupVolume&&Math.abs(after.volume-Math.max(0,Math.min(100,value)))<=2;}
}
