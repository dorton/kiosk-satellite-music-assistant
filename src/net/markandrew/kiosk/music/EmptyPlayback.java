package net.markandrew.kiosk.music;

import org.json.JSONObject;

/** Empty is positive evidence, never an absent/partial queue or a paused source. */
final class EmptyPlayback {
    static boolean confirmed(JSONObject player,JSONObject queue,String expectedQueue){
        String state=player.optString("playback_state"),queueState=queue.optString("state"),source=player.optString("active_source");
        return !expectedQueue.isEmpty()&&expectedQueue.equals(queue.optString("queue_id"))
            &&player.optBoolean("available",false)&&queue.optBoolean("available",false)&&queue.optInt("items",-1)==0
            &&(state.equals("idle")||state.equals("stopped"))&&(queueState.equals("idle")||queueState.equals("stopped"))
            &&(source.isEmpty()||source.equals(expectedQueue));
    }
}
