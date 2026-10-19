package net.markandrew.kiosk.music;

import java.time.Instant;
import java.util.*;
import org.json.*;

/** Hardware events are passive; never compensate by changing Android master volume. */
final class HardwareVolumePolicy {
    static final int STEP=5;
    static int direction(Map<String,Object> event,long now){
        if(!"down".equals(event.get("action")))return 0;
        String key=String.valueOf(event.get("key"));int direction="VOLUME_UP".equals(key)?1:"VOLUME_DOWN".equals(key)?-1:0;
        if(direction==0)return 0;
        Object repeat=event.get("repeat");
        if(!(repeat instanceof Number)||((Number)repeat).doubleValue()<0||((Number)repeat).doubleValue()!=((Number)repeat).intValue())return 0;
        try{long age=now-Instant.parse(String.valueOf(event.get("time"))).toEpochMilli();return age>=-250&&age<=2000?direction:0;}
        catch(RuntimeException invalid){return 0;}
    }
    static String queueId(JSONArray rows,String selected)throws Exception{
        JSONObject leader=FeatureApi.groupLeader(rows,selected);String source=FeatureApi.ref(leader,"active_source");
        FeatureApi.require(!source.isEmpty(),"No active MA music queue.");return source;
    }
    static final class Target {
        final String selected,id,queue,leader;final boolean group;final int level;final Set<String> members;
        Target(JSONArray rows,JSONObject queueState,String selected,String own)throws Exception{
            PlayerPolicy.Snapshot snapshot=MusicApi.parseSnapshot(rows,new JSONArray().put(queueState),own);
            DefaultPlayerPolicy.selected(snapshot,selected);
            JSONObject root=FeatureApi.groupLeader(rows,selected);
            FeatureApi.require(root.optBoolean("available")&&root.optBoolean("enabled",true),"Player/group unavailable.");
            this.selected=selected;leader=root.getString("player_id");members=FeatureApi.members(rows,root);
            group="group".equals(root.optString("type"))||members.stream().anyMatch(member->!member.equals(leader));
            id=leader;queue=queueId(rows,selected);
            FeatureApi.require(queue.equals(queueState.optString("queue_id"))&&queueState.optBoolean("available")&&queueState.optBoolean("active")&&"playing".equals(queueState.optString("state"))&&"playing".equals(root.optString("playback_state")),"Hardware volume only controls confirmed MA music playback.");
            boolean adjustable=false;
            if(group){for(String member:members){JSONObject row=FeatureApi.byId(rows,"player_id",member);if(row!=null&&row.optBoolean("available")&&row.optBoolean("enabled",true)&&adjustable(row))adjustable=true;}}
            else adjustable=adjustable(root);
            FeatureApi.require(adjustable,"This player/group does not expose adjustable volume.");
            Object volume=root.opt(group?"group_volume":"volume_level");
            FeatureApi.require(volume instanceof Number,"Current player volume is unavailable.");
            double value=((Number)volume).doubleValue();FeatureApi.require(Double.isFinite(value)&&value>=0&&value<=100,"Current player volume is invalid.");level=(int)Math.round(value);
        }
        private static boolean adjustable(JSONObject row){String control=row.optString("volume_control","");return !control.isEmpty()&&!"none".equals(control);}
        boolean same(Target other){return selected.equals(other.selected)&&id.equals(other.id)&&queue.equals(other.queue)&&leader.equals(other.leader)&&group==other.group&&members.equals(other.members);}
        int next(int direction){return Math.max(0,Math.min(100,level+STEP*direction));}
    }
}
