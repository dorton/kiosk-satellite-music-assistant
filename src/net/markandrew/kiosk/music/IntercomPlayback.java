package net.markandrew.kiosk.music;

import java.util.*;
import org.json.*;

/** One call's verified local-player intervention. No queue transfers, default changes or write retries. */
final class IntercomPlayback {
    enum Kind { NONE, PAUSED, STOPPED, LEFT }
    static final class Receipt {
        Kind kind=Kind.NONE;
        boolean queuePause;
        double pauseFrom;
        final String local,account,server,version;
        String leader="",queue="",mark="",localMark="",localState="",targetShape="",localShape="";
        Set<String> remaining=Collections.emptySet();
        Receipt(String id,JSONObject user,JSONObject info){local=id;account=user.optString("user_id");server=info.optString("server_id");version=info.optString("server_version");}
    }
    static boolean call(String state){return Arrays.asList("ringing","in_call").contains(state);}
    private static String shape(JSONObject p){return p.optString("player_id")+":"+p.optString("provider")+":"+p.optString("type");}
    private static String source(JSONObject p){return FeatureApi.ref(p,"active_source");}
    private static String queueId(JSONObject leader){String active=source(leader);return active.isEmpty()?leader.optString("player_id"):active;}
    private static String state(JSONObject p){return p.optString("playback_state");}
    private static JSONObject player(JSONArray rows,String id){JSONObject p=FeatureApi.byId(rows,"player_id",id);FeatureApi.require(p!=null&&p.optBoolean("available")&&p.optBoolean("enabled",true),"Call player unavailable; local muting remains the fallback.");return p;}
    private static JSONArray rows(FeatureApi.Connection c)throws Exception{return c.array("players/all",FeatureApi.args("return_unavailable",true));}
    private static JSONObject queue(FeatureApi.Connection c,String id)throws Exception{
        JSONObject q=c.object("player_queues/get",FeatureApi.args("queue_id",id));FeatureApi.require(id.equals(q.optString("queue_id"))&&q.optBoolean("available",false),"Call queue identity unavailable.");return q;
    }
    private static void authority(FeatureApi api,FeatureApi.Connection c,Receipt r)throws Exception{
        JSONObject user=(JSONObject)api.api.identity(c.session),info=api.api.serverInfo(c.session);
        FeatureApi.require(!r.account.isEmpty()&&!r.server.isEmpty()&&r.account.equals(user.optString("user_id"))&&r.server.equals(info.optString("server_id"))&&r.version.equals(info.optString("server_version")),"Call account/server/version changed; automatic restoration cancelled.");
        FeatureApi.require(r.version.matches("2\\.10\\.[0-9]+(?:[.-].*)?"),"Call playback control supports MA 2.10.x; using local mute only.");
        JSONObject scopes=c.object("auth/scopes",FeatureApi.args());
        FeatureApi.require(PairingDiagnostics.grant(user,scopes,"players.control")==PairingDiagnostics.Grant.GRANTED,"Call playback permission unavailable; using local mute only.");
    }
    static Receipt begin(FeatureApi api,FeatureApi.Connection c,String local)throws Exception{
        Receipt r=new Receipt(local,(JSONObject)api.api.identity(c.session),api.api.serverInfo(c.session));authority(api,c,r);
        JSONArray initial=rows(c);JSONObject child=player(initial,local),leader=FeatureApi.groupLeader(initial,local);
        r.localShape=shape(child);r.targetShape=shape(leader);r.leader=leader.getString("player_id");r.queue=queueId(leader);
        Set<String> members=FeatureApi.members(initial,leader);
        if(!"playing".equals(state(leader)))return r;
        if(local.equals(r.leader)&&members.size()>1)return r; // Never interrupt a group led by this kiosk.
        JSONObject q=queue(c,r.queue);FeatureApi.require("playing".equals(q.optString("state")),"Call queue is not confirmed playing; local mute only.");r.mark=FeatureApi.queueMark(q);
        JSONArray fresh=rows(c);JSONObject now=player(fresh,local),current=FeatureApi.groupLeader(fresh,local);
        FeatureApi.require(r.localShape.equals(shape(now))&&r.targetShape.equals(shape(current))&&members.equals(FeatureApi.members(fresh,current))&&"playing".equals(state(current))&&r.queue.equals(queueId(current)),"Call player/group changed before submission.");
        if(local.equals(r.leader)){
            FeatureApi.require(members.size()==1,"Standalone grouping changed; local mute only.");
            r.queuePause=!FeatureApi.contains(now.optJSONArray("supported_features"),"pause");
            JSONObject latest=queue(c,r.queue);FeatureApi.require(r.mark.equals(FeatureApi.queueMark(latest))&&"playing".equals(latest.optString("state")),"Track changed before call pause.");
            FeatureApi.require(!r.queuePause||activeQueue(now,latest),"Standalone pause unsupported; local mute only.");
            r.pauseFrom=FeatureApi.elapsed(latest,System.currentTimeMillis()/1000d);
            authority(api,c,r);c.write("players/cmd/pause",FeatureApi.args("player_id",local));
            JSONObject paused=readback(()->{
                JSONArray after=rows(c);JSONObject checked=player(after,local),group=FeatureApi.groupLeader(after,local),value=queue(c,r.queue);
                boolean pausedState="paused".equals(state(checked))&&"paused".equals(value.optString("state"))&&(!r.queuePause||activeQueue(checked,value));
                boolean stoppedState=r.queuePause&&r.queue.equals(local)&&"idle".equals(state(checked))&&"idle".equals(value.optString("state"))&&compatibleSource(checked,r.queue)&&!value.optBoolean("ended")&&value.opt("resume_pos") instanceof Number&&Double.isFinite(value.optDouble("resume_pos"))&&Math.abs(value.optDouble("resume_pos")-r.pauseFrom)<=5;
                FeatureApi.require(local.equals(group.optString("player_id"))&&FeatureApi.members(after,group).size()==1&&r.localShape.equals(shape(checked))&&r.mark.equals(FeatureApi.queueMark(value))&&(pausedState||stoppedState),"Call pause submitted but unverified; no automatic resume.");return value;
            });
            r.kind="idle".equals(paused.optString("state"))?Kind.STOPPED:Kind.PAUSED;
            r.localMark=r.kind==Kind.STOPPED?stoppedMark(paused):positionMark(paused);
        }else{
            FeatureApi.require(FeatureApi.contains(current.optJSONArray("supported_features"),"set_members")&&!FeatureApi.contains(current.optJSONArray("static_group_members"),local),"Fixed member cannot leave; local mute only.");
            authority(api,c,r);c.write("players/cmd/set_members",FeatureApi.args("target_player",r.leader,"player_ids_to_remove",new JSONArray().put(local)));
            Set<String> expected=new LinkedHashSet<>(members);expected.remove(local);
            JSONObject detached=readback(()->{
                JSONArray after=rows(c);JSONObject value=player(after,local),resolved=FeatureApi.groupLeader(after,local),original=player(after,r.leader);
                FeatureApi.require(local.equals(resolved.optString("player_id"))&&FeatureApi.members(after,resolved).size()==1&&expected.equals(FeatureApi.members(after,original))&&r.targetShape.equals(shape(original)),"Call leave submitted but unverified; inspect Group before rejoining.");
                FeatureApi.require(Arrays.asList("idle","stopped","paused").contains(state(value)),"Detached receiver is not idle; automatic rejoin cancelled.");return value;
            });
            r.remaining=expected;r.localState=state(detached);
            r.localMark=positionMark(queue(c,queueId(detached)));r.kind=Kind.LEFT;
        }
        return r;
    }
    private static String positionMark(JSONObject q){return FeatureApi.queueMark(q)+":"+q.optString("state")+":"+q.optDouble("elapsed_time",0);}
    private static String stoppedMark(JSONObject q){return positionMark(q)+":"+q.opt("resume_pos")+":"+q.opt("elapsed_time_last_updated")+":"+q.optBoolean("ended");}
    private static boolean compatibleSource(JSONObject p,String queue){return source(p).isEmpty()||source(p).equals(queue);}
    private static boolean activeQueue(JSONObject p,JSONObject q){return new NowPlayingData(p,q).queueActive&&q.optInt("items",0)>0;}
    private interface Check<T>{T read()throws Exception;}
    private static <T>T readback(Check<T> check)throws Exception{
        IllegalArgumentException last=null;
        for(int delay:new int[]{0,150,350,700}){if(delay>0)Thread.sleep(delay);try{return check.read();}catch(IllegalArgumentException pending){last=pending;}}
        throw last;
    }
    static void finish(FeatureApi api,FeatureApi.Connection c,Receipt r)throws Exception{
        if(r==null||r.kind==Kind.NONE)return;
        authority(api,c,r);
        // Two independent reads before restoring; user/remote changes invalidate the receipt.
        checkRestore(c,r);authority(api,c,r);checkRestore(c,r);
        if(r.kind==Kind.PAUSED||r.kind==Kind.STOPPED){
            c.write("players/cmd/play",FeatureApi.args("player_id",r.local));
            readback(()->{JSONObject q=queue(c,r.queue);JSONArray after=rows(c);JSONObject p=FeatureApi.groupLeader(after,r.local);
                FeatureApi.require(r.local.equals(p.optString("player_id"))&&FeatureApi.members(after,p).size()==1&&"playing".equals(state(p))&&"playing".equals(q.optString("state"))&&r.mark.equals(FeatureApi.queueMark(q)),"Call resume submitted but unverified. Check the player; no retry.");return null;
            });
        }else{
            c.write("players/cmd/set_members",FeatureApi.args("target_player",r.leader,"player_ids_to_add",new JSONArray().put(r.local)));
            Set<String> expected=new LinkedHashSet<>(r.remaining);expected.add(r.local);
            readback(()->{JSONArray after=rows(c);JSONObject leader=FeatureApi.groupLeader(after,r.local);
                FeatureApi.require(r.leader.equals(leader.optString("player_id"))&&expected.equals(FeatureApi.members(after,leader)),"Call rejoin submitted but unverified. Check Group; no retry.");return null;
            });
        }
    }
    private static void checkRestore(FeatureApi.Connection c,Receipt r)throws Exception{
        JSONArray rows=rows(c);JSONObject local=player(rows,r.local),resolved=FeatureApi.groupLeader(rows,r.local);
        FeatureApi.require(r.localShape.equals(shape(local))&&r.local.equals(resolved.optString("player_id"))&&FeatureApi.members(rows,resolved).size()==1,"Local grouping changed during call; restoration cancelled.");
        if(r.kind==Kind.STOPPED){
            JSONObject q=queue(c,r.queue);
            FeatureApi.require("idle".equals(state(local))&&compatibleSource(local,r.queue)&&"idle".equals(q.optString("state"))&&!q.optBoolean("ended")&&r.localMark.equals(stoppedMark(q)),"Retained queue changed during call; automatic resume cancelled.");
        }else if(r.kind==Kind.PAUSED){
            JSONObject q=queue(c,r.queue);
            FeatureApi.require("paused".equals(state(local))&&r.queue.equals(queueId(local))&&r.localMark.equals(positionMark(q))&&(!r.queuePause||activeQueue(local,q)),"Playback changed during call; automatic resume cancelled.");
        }else{
            FeatureApi.require(r.localState.equals(state(local))&&r.localMark.equals(positionMark(queue(c,queueId(local)))),"Detached playback changed during call; automatic rejoin cancelled.");
            JSONObject leader=player(rows,r.leader);
            FeatureApi.require(r.targetShape.equals(shape(leader))&&r.leader.equals(FeatureApi.groupLeader(rows,r.leader).optString("player_id"))&&r.remaining.equals(FeatureApi.members(rows,leader))&&r.queue.equals(queueId(leader))&&"playing".equals(state(leader))&&FeatureApi.localJoinCandidate(rows,leader,local,""),"Original group changed or stopped; automatic rejoin cancelled.");
        }
    }
}
