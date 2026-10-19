package net.markandrew.kiosk.music;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real loopback MA requests; no live Home Assistant, Music Assistant, receiver or device writes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class IntercomPlaybackTest {
    private ServerSocket server;
    private ExecutorService http;
    private FeatureApi api;
    private IngressAccess.Session session;
    private volatile boolean grouped,leaderLocal,fixed,deny,ignore,changedMembers;
    private volatile boolean noNativePause,inactiveQueue,emptyQueue;
    private volatile boolean stopStyle,ended,clearSource;
    private volatile double resumePos=10;
    private volatile String localState="playing",groupState="playing",track="one",account="test",version="2.10.2";
    private volatile double elapsed=10;
    private final List<String> writes=new CopyOnWriteArrayList<>();
    private JSONObject player(String id)throws Exception{
        boolean leader=id.equals(leaderLocal?"local":"leader");
        boolean child=id.equals("local");
        return FeatureApi.args("player_id",id,"provider","sendspin","type","player","name",id,"available",true,"enabled",true,
            "playback_state",child?(grouped&&!leaderLocal?groupState:localState):groupState,
            "active_source",clearSource?"":grouped?leaderLocal?"local":"leader":id,"synced_to",grouped&&child&&!leaderLocal?"leader":JSONObject.NULL,
            "group_members",leader&&grouped?new JSONArray(leaderLocal?Arrays.asList("local","leader"):Arrays.asList("leader","local")):id.equals("leader")&&changedMembers?new JSONArray(Arrays.asList("leader","other")):new JSONArray().put(id),
            "static_group_members",fixed?new JSONArray().put("local"):new JSONArray(),
            "supported_features",new JSONArray(noNativePause?Arrays.asList("set_members"):Arrays.asList("pause","set_members")),"can_group_with",new JSONArray(Arrays.asList("local","leader","sendspin")));
    }
    private JSONObject queue(String id)throws Exception{
        boolean detached=!stopStyle&&id.equals("local")&&!grouped&&localState.equals("idle");
        return FeatureApi.args("queue_id",id,"available",true,"active",!inactiveQueue,"items",detached||emptyQueue?0:3,"current_index",detached?-1:0,
            "state",id.equals("local")?localState:groupState,"elapsed_time",detached?0:elapsed,"resume_pos",resumePos,"ended",ended,
            "current_item",detached?JSONObject.NULL:FeatureApi.args("queue_item_id",track,"media_item",FeatureApi.args("uri","library://track/"+track)));
    }
    private Object request(String path,JSONObject call)throws Exception{
        if(path.contains("/auth/me"))return FeatureApi.args("user_id",account,"role","admin");
        if(path.contains("/info"))return FeatureApi.args("server_id","fixture","server_version",version);
        String command=call.getString("command");JSONObject args=call.getJSONObject("args");
        switch(command){
            case "auth/scopes":return FeatureApi.args("admin",deny?new JSONArray():new JSONArray().put("*"));
            case "players/all":return new JSONArray().put(player("local")).put(player("leader"));
            case "player_queues/get":return queue(args.getString("queue_id"));
            case "players/cmd/pause":writes.add("pause:"+args.getString("player_id"));if(!ignore){localState=stopStyle?"idle":"paused";if(stopStyle){resumePos=elapsed;inactiveQueue=true;clearSource=true;}}return new JSONObject();
            case "players/cmd/play":writes.add("play:"+args.getString("player_id"));if(!ignore){localState="playing";inactiveQueue=false;clearSource=false;}return new JSONObject();
            case "players/cmd/set_members":
                boolean remove=args.has("player_ids_to_remove");assertEquals("leader",args.getString("target_player"));assertEquals("local",args.getJSONArray(remove?"player_ids_to_remove":"player_ids_to_add").getString(0));writes.add(remove?"leave":"join");
                if(!ignore){grouped=!remove;localState=remove?"idle":"playing";}return new JSONObject();
            default:throw new IllegalArgumentException("Unexpected fixture command "+command);
        }
    }
    @Before public void setup()throws Exception{
        api=new FeatureApi();server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();
        http.submit(()->{while(!server.isClosed())try{Socket s=server.accept();http.submit(()->{try(Socket socket=s){
            BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String first=r.readLine(),line;int length=0;
            while((line=r.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            char[] body=new char[length];int count=0;while(count<length){int n=r.read(body,count,length-count);if(n<0)throw new EOFException();count+=n;}
            Object result=request(first,length==0?null:new JSONObject(new String(body)));byte[] data=result.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
        }catch(Exception e){throw new RuntimeException(e);}});}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
        session=new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie");
    }
    @After public void stop()throws Exception{api.api.cancel();server.close();http.shutdownNow();}
    private FeatureApi.Connection connection(){return api.new Connection(session,()->true);}
    private void refuses(Throwing task)throws Exception{try{task.run();fail("Expected guarded refusal");}catch(IllegalArgumentException expected){}}
    private interface Throwing{void run()throws Exception;}
    @Test public void standalonePausesAndResumesOnlyTheLocalPlayer()throws Exception{
        IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");assertEquals(IntercomPlayback.Kind.PAUSED,r.kind);assertEquals("paused",localState);IntercomPlayback.finish(api,connection(),r);assertEquals(Arrays.asList("pause:local","play:local"),writes);
    }
    @Test public void activeQueuePausesAndResumesWithoutNativePauseFlag()throws Exception{
        noNativePause=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");assertTrue(r.queuePause);assertEquals("paused",localState);IntercomPlayback.finish(api,connection(),r);assertEquals(Arrays.asList("pause:local","play:local"),writes);
    }
    @Test public void noNativePauseAndInactiveQueueNeverSubmits()throws Exception{noNativePause=true;inactiveQueue=true;refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertTrue(writes.isEmpty());}
    @Test public void noNativePauseAndEmptyQueueNeverSubmits()throws Exception{noNativePause=true;emptyQueue=true;refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertTrue(writes.isEmpty());}
    @Test public void queueFallbackDoesNotResumeAfterQueueDeactivation()throws Exception{noNativePause=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");inactiveQueue=true;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(Collections.singletonList("pause:local"),writes);}
    @Test public void groupLeaderWithQueueFallbackStillNeverPauses()throws Exception{noNativePause=true;grouped=true;leaderLocal=true;assertEquals(IntercomPlayback.Kind.NONE,IntercomPlayback.begin(api,connection(),"local").kind);assertTrue(writes.isEmpty());}
    @Test public void maStopStylePauseRetainsOwnershipAndResumesSameQueue()throws Exception{noNativePause=true;stopStyle=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");assertEquals(IntercomPlayback.Kind.STOPPED,r.kind);assertEquals("idle",localState);IntercomPlayback.finish(api,connection(),r);assertEquals(Arrays.asList("pause:local","play:local"),writes);assertEquals("playing",localState);}
    @Test public void changedStopStyleResumePositionCancelsRestore()throws Exception{noNativePause=true;stopStyle=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");resumePos=44;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void endedStopStyleQueueDoesNotRestartAtBeginning()throws Exception{noNativePause=true;stopStyle=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");ended=true;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void clearedStopStyleQueueCancelsRestore()throws Exception{noNativePause=true;stopStyle=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");emptyQueue=true;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void memberLeavesAndRejoinsWithoutPausingLeader()throws Exception{
        grouped=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");assertEquals(IntercomPlayback.Kind.LEFT,r.kind);assertFalse(grouped);track="two";IntercomPlayback.finish(api,connection(),r);assertTrue(grouped);assertEquals(Arrays.asList("leave","join"),writes);
    }
    @Test public void groupLeaderNeverPausesOrLeaves()throws Exception{
        grouped=true;leaderLocal=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");assertEquals(IntercomPlayback.Kind.NONE,r.kind);IntercomPlayback.finish(api,connection(),r);assertTrue(writes.isEmpty());
    }
    @Test public void alreadyPausedDoesNotResume()throws Exception{localState="paused";IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");IntercomPlayback.finish(api,connection(),r);assertTrue(writes.isEmpty());}
    @Test public void changedStandaloneTrackCancelsResume()throws Exception{IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");track="two";refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(Collections.singletonList("pause:local"),writes);}
    @Test public void stopDuringCallCancelsResume()throws Exception{IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");localState="idle";refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void seekDuringCallCancelsResume()throws Exception{IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");elapsed=44;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void newGroupDuringCallCancelsResume()throws Exception{IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");grouped=true;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void changedOriginalMembershipCancelsRejoin()throws Exception{
        grouped=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");changedMembers=true;
        // Leader gained another member while the local receiver remains detached.
        refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(Collections.singletonList("leave"),writes);
    }
    @Test public void stoppedOriginalGroupCancelsRejoin()throws Exception{grouped=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");groupState="idle";refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void detachedPlaybackChangeCancelsRejoin()throws Exception{grouped=true;IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");localState="playing";refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void deniedPermissionNeverWrites()throws Exception{deny=true;refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertTrue(writes.isEmpty());}
    @Test public void revokedPermissionCancelsResume()throws Exception{IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");deny=true;refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void changedAccountCancelsResume()throws Exception{IntercomPlayback.Receipt r=IntercomPlayback.begin(api,connection(),"local");account="other";refuses(()->IntercomPlayback.finish(api,connection(),r));assertEquals(1,writes.size());}
    @Test public void unsupportedVersionNeverWrites()throws Exception{version="3.0.0";refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertTrue(writes.isEmpty());}
    @Test public void fixedMemberFallsBackToMuteWithoutLeaving()throws Exception{grouped=true;fixed=true;refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertTrue(writes.isEmpty());}
    @Test public void unverifiedPauseIsNotRetried()throws Exception{ignore=true;refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertEquals(Collections.singletonList("pause:local"),writes);}
    @Test public void unverifiedLeaveIsNotRetried()throws Exception{grouped=true;ignore=true;refuses(()->IntercomPlayback.begin(api,connection(),"local"));assertEquals(Collections.singletonList("leave"),writes);}
    @Test public void broadcastAndEndedAreNotNewCalls(){for(String s:new String[]{"idle","ended","missed","broadcasting","listening","unknown","calling"})assertFalse(IntercomPlayback.call(s));for(String s:new String[]{"ringing","in_call"})assertTrue(IntercomPlayback.call(s));}
    @Test public void outgoingCallOrUnobservedIncomingRingDoesNotMutate()throws Exception{
        List<String> messages=new CopyOnWriteArrayList<>();IntercomController c=controller(new java.util.concurrent.atomic.AtomicReference<>("same"),messages);
        try{c.state("calling");c.state("in_call");c.state("ended");c.state("idle");c.state("in_call");c.state("ended");assertFalse(c.busy());assertTrue(writes.isEmpty());}finally{c.close();}
    }
    private IntercomController controller(java.util.concurrent.atomic.AtomicReference<String> context,List<String> messages)throws Exception{
        me.jxl.kiosk.plugins.PluginHost host=(me.jxl.kiosk.plugins.PluginHost)java.lang.reflect.Proxy.newProxyInstance(me.jxl.kiosk.plugins.PluginHost.class.getClassLoader(),new Class<?>[]{me.jxl.kiosk.plugins.PluginHost.class},(p,m,a)->{
            if(m.getName().equals("executeCommand"))((me.jxl.kiosk.plugins.PluginHost.CommandCallback)a[2]).onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);
            if(m.getName().equals("haCall"))((me.jxl.kiosk.plugins.PluginHost.CommandCallback)a[1]).onResult(true,Collections.singletonMap("id","fixture-user"),null);return null;
        });
        IntercomController c=new IntercomController(host,()->"fixture_addon",context::get,s->"local",messages::add,()->{});
        java.lang.reflect.Field f=IntercomController.class.getDeclaredField("access");f.setAccessible(true);IngressAccess access=(IngressAccess)f.get(c);f=IngressAccess.class.getDeclaredField("cached");f.setAccessible(true);f.set(access,session);return c;
    }
    private void pump(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();if(condition.getAsBoolean())return;Thread.sleep(10);}fail("Timed out waiting for loopback call operation");
    }
    @Test public void duplicateCallStatesDoNotRepeatPauseOrResume()throws Exception{
        List<String> messages=new CopyOnWriteArrayList<>();IntercomController c=controller(new java.util.concurrent.atomic.AtomicReference<>("same"),messages);
        try{c.state("ringing");pump(()->messages.stream().anyMatch(s->s.contains("paused")));c.state("in_call");c.state("in_call");assertEquals(Collections.singletonList("pause:local"),writes);c.state("ended");pump(()->!c.busy());c.state("idle");assertEquals(Arrays.asList("pause:local","play:local"),writes);}finally{c.close();}
    }
    @Test public void changedSelectionDoesNotResumeOurPause()throws Exception{
        List<String> messages=new CopyOnWriteArrayList<>();java.util.concurrent.atomic.AtomicReference<String> context=new java.util.concurrent.atomic.AtomicReference<>("same");IntercomController c=controller(context,messages);
        try{c.state("ringing");pump(()->messages.stream().anyMatch(s->s.contains("paused")));context.set("changed");c.state("ended");pump(()->!c.busy());assertEquals(Collections.singletonList("pause:local"),writes);}finally{c.close();}
    }
    @Test public void callEndingBeforeFirstSubmissionCannotLeaveMusicPaused()throws Exception{
        List<String> messages=new CopyOnWriteArrayList<>();IntercomController c=controller(new java.util.concurrent.atomic.AtomicReference<>("same"),messages);
        try{c.state("ringing");c.state("ended");pump(()->!c.busy());assertEquals("playing",localState);assertTrue(writes.isEmpty()||writes.equals(Arrays.asList("pause:local","play:local")));}finally{c.close();}
    }
}
