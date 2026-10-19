package net.markandrew.kiosk.music;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import me.jxl.kiosk.plugins.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Native controller -> real loopback HTTP -> MA-shaped readback -> native UI. No HA/MA access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,qualifiers="w960dp-h480dp-land-mdpi")
public class NowPlayingConnectionTest {
    private ServerSocket server;
    private ExecutorService http;
    private NowPlayingController controller;
    private Activity activity;
    private View root;
    private volatile String state="playing",item="item1";
    private volatile boolean favorite, ignorePause;
    private volatile boolean delayedPause;
    private volatile boolean stopFallback;
    private volatile int pauseReads;
    private volatile int volume=45;
    private volatile boolean displayGroup,missingMemberVolume;
    private volatile int memberVolume;
    private volatile int queueItems=3;
    private volatile boolean muted,ignoreVolume,ignoreUnmute,changedAfterVolume;
    private final List<String> writes=new CopyOnWriteArrayList<>();
    private KsTheme theme(){
        Map<String,Integer> colors=new HashMap<>();
        for(String k:new String[]{"surface","surfaceContainerHigh","surfaceContainerHighest","onSurface","onSurfaceVariant","primary","onPrimary","primaryContainer","onPrimaryContainer","outlineVariant"})colors.put(k,0xff556677);
        return new KsTheme(true,1,colors,w->null,n->new android.graphics.Path());
    }
    private JSONObject track() throws Exception{return new JSONObject().put("uri","library://track/1").put("item_id","1").put("provider","library").put("media_type","track").put("name","Track").put("duration",200).put("favorite",favorite);}
    private JSONObject player() throws Exception{return new JSONObject().put("player_id","speaker").put("name","Speaker").put("type","player").put("available",true).put("enabled",true).put("active_source","speaker").put("playback_state",state).put("volume_control","native").put("mute_control","native").put("volume_level",volume).put("volume_muted",muted).put("group_members",changedAfterVolume&&writes.contains("players/cmd/volume_set")?new JSONArray().put("speaker").put("other"):new JSONArray()).put("supported_features",new JSONArray());}
    private JSONObject queue() throws Exception{return new JSONObject().put("queue_id","speaker").put("active",true).put("available",true).put("items",queueItems).put("state",state).put("current_item",queueItems==0?JSONObject.NULL:new JSONObject().put("queue_item_id",item).put("media_item",track().put("favorite",false)));}
    @Before public void setup() throws Exception {
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();
        http.submit(()->{while(!server.isClosed())try{Socket accepted=server.accept();http.submit(()->{try(Socket socket=accepted){
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            reader.readLine();int length=0;String line;
            while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            char[] chars=new char[length];int count=0;while(count<length){int read=reader.read(chars,count,length-count);if(read<0)throw new EOFException();count+=read;}
            String body=new String(chars);
            JSONObject request=new JSONObject(body);String command=request.getString("command");Object reply;
            switch(command){
                 case "players/get": if(pauseReads>0&&--pauseReads==0)state="paused";
                     if(displayGroup&&"other".equals(request.getJSONObject("args").optString("player_id"))){JSONObject member=new JSONObject().put("player_id","other");if(!missingMemberVolume)member.put("volume_level",memberVolume);reply=member;}
                     else{JSONObject own=player();if(displayGroup)own.put("group_members",new JSONArray().put("speaker").put("other")).put("group_volume",1);reply=own;}break;
                case "player_queues/get":reply=queue();break;
                case "music/item_by_uri":reply=track();break;
                  case "players/cmd/pause":writes.add(command);if(stopFallback)state="idle";else if(delayedPause)pauseReads=4;else if(!ignorePause)state="paused";reply=new JSONObject();break;
                case "players/cmd/play":writes.add(command);state="playing";reply=new JSONObject();break;
                case "players/cmd/next":writes.add(command);item="item2";reply=new JSONObject();break;
                case "players/cmd/volume_set":writes.add(command);if(!ignoreVolume)volume=request.getJSONObject("args").getInt("volume_level");reply=new JSONObject();break;
                case "players/cmd/volume_mute":writes.add(command);boolean requestedMute=request.getJSONObject("args").getBoolean("muted");if(requestedMute||!ignoreUnmute)muted=requestedMute;reply=new JSONObject();break;
                case "music/favorites/add_item":writes.add(command);favorite=true;reply=new JSONObject();break;
                case "music/favorites/remove_item":writes.add(command);favorite=false;reply=new JSONObject();break;
                default:throw new AssertionError("Unexpected fixture command "+command);
            }
            byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception error){throw new RuntimeException(error);}});}catch(IOException error){if(!server.isClosed())throw new RuntimeException(error);}});
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        PluginHost host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            switch(method.getName()){
                case "executeCommand":((PluginHost.CommandCallback)args[2]).onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);break;
                case "haCall":((PluginHost.CommandCallback)args[1]).onResult(true,Collections.singletonMap("id","fixture-user"),null);break;
                case "showOverlay":root=((OverlayFactory)args[2]).create(activity,theme());activity.setContentView(root);break;
            }
            return null;
        });
        IngressAccess access=new IngressAccess(host);Field cached=IngressAccess.class.getDeclaredField("cached");cached.setAccessible(true);
        cached.set(access,new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture_cookie"));
        controller=new NowPlayingController(host,access,"fixture_addon","speaker",()->true,()->"speaker");controller.show();
        pump(()->button("Pause")!=null&&button("Pause").isEnabled()&&button("Add favourite")!=null&&button("Add favourite").isEnabled());
    }
    @After public void teardown()throws Exception{if(controller!=null)controller.close();if(server!=null)server.close();if(http!=null)http.shutdownNow();}
    private NowPlayingData rendered(){try{Field f=NowPlayingController.class.getDeclaredField("rendered");f.setAccessible(true);return (NowPlayingData)f.get(controller);}catch(Exception error){throw new RuntimeException(error);}}
    @Test public void mainPlayerReadsMissingGroupMemberForZeroIconWithoutWrites()throws Exception{
        displayGroup=true;volume=0;memberVolume=0;
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));pump(()->rendered()!=null&&rendered().groupVolume&&rendered().silent);
        assertFalse(rendered().muted);assertEquals(1,rendered().volume);assertTrue(writes.isEmpty());
        memberVolume=7;shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));pump(()->!rendered().silent);assertTrue(writes.isEmpty());
    }
    @Test public void incompleteGroupReadingDoesNotTurnZeroSummaryIntoSilence()throws Exception{
        displayGroup=true;volume=0;missingMemberVolume=true;
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));pump(()->rendered()!=null&&rendered().groupVolume);
        assertFalse(rendered().silent);assertTrue(writes.isEmpty());
    }
    @Test public void remotelyClearedQueueRoutesEmptyControllerToLibraryWithoutWrites()throws Exception{
        List<String> routes=new CopyOnWriteArrayList<>();controller.navigation(routes::add);queueItems=0;state="idle";shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));pump(()->routes.contains("library"));assertTrue(writes.isEmpty());
    }
    @Test public void intercomHoldPreventsTransientEmptyQueueFromNavigatingAway()throws Exception{
        List<String> routes=new CopyOnWriteArrayList<>();controller.navigation(routes::add);controller.navigationHeld(()->true);queueItems=0;state="idle";shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));pump(()->button("Play")!=null);assertTrue(routes.isEmpty());assertTrue(writes.isEmpty());
    }
    @Test public void remoteEmptyDoesNotDiscardAnOpenSettingsDraft()throws Exception{
        List<String> routes=new CopyOnWriteArrayList<>();controller.navigation(routes::add);controller.panel("Equalizer",true,p->p.note("Unsaved draft"),()->{});queueItems=0;state="idle";shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));pump(()->button("Play")!=null);assertTrue(routes.isEmpty());assertNotNull(button("Unsaved draft"));assertTrue(writes.isEmpty());
    }
    private View find(View view,String label){
        if(view==null)return null;
        if(label.equals(String.valueOf(view.getContentDescription()))||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=find(((ViewGroup)view).getChildAt(i),label);if(child!=null)return child;}
        return null;
    }
    private View button(String label){return find(root,label);}
    private void pump(BooleanSupplier done)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        do{shadowOf(Looper.getMainLooper()).idle();if(done.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<deadline);
        fail("Native controller did not reach the expected fixture state");
    }
    @Test public void pauseResumeAndSkipWorkWithoutDeviceFeatureFlags()throws Exception{
        button("Pause").performClick();pump(()->button("Play")!=null&&button("Play").isEnabled());assertEquals("paused",state);
        button("Play").performClick();pump(()->button("Pause")!=null&&button("Pause").isEnabled());assertEquals("playing",state);
        button("Next").performClick();pump(()->item.equals("item2")&&button("Next").isEnabled());
        assertEquals(Arrays.asList("players/cmd/pause","players/cmd/play","players/cmd/next"),writes);
    }
    @Test public void favoriteReadbackOverridesStaleQueueMetadata()throws Exception{
        button("Add favourite").performClick();pump(()->button("Remove favourite")!=null&&button("Remove favourite").isEnabled());assertTrue(favorite);
        button("Remove favourite").performClick();pump(()->button("Add favourite")!=null&&button("Add favourite").isEnabled());assertFalse(favorite);
        assertEquals(Arrays.asList("music/favorites/add_item","music/favorites/remove_item"),writes);
    }
    @Test public void httpSuccessWithoutStateChangeIsNotClaimedAsConfirmed()throws Exception{
        ignorePause=true;button("Pause").performClick();
        pump(()->find(root,"pause submitted · state not confirmed. Check MA before retrying; no automatic retry.")!=null);
        assertEquals("playing",state);assertEquals(Arrays.asList("players/cmd/pause"),writes);
    }
    @Test public void delayedAcknowledgementUsesOnlyReadsAndStaysQuiet()throws Exception{
        delayedPause=true;button("Pause").performClick();pump(()->button("Play")!=null&&button("Play").isEnabled());
        assertEquals(Collections.singletonList("players/cmd/pause"),writes);
        assertNull(find(root,"Checking player · sending once…"));
        assertNull(find(root,"pause submitted · state not confirmed. Check MA before retrying; no automatic retry."));
    }
    @Test public void latePauseConfirmationClearsOnlyItsWarningWithoutResending()throws Exception{
        ignorePause=true;button("Pause").performClick();
        String warning="pause submitted · state not confirmed. Check MA before retrying; no automatic retry.";
        pump(()->find(root,warning)!=null);state="paused";
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        pump(()->button("Play")!=null&&button("Play").isEnabled()&&find(root,warning)==null);
        assertEquals(Collections.singletonList("players/cmd/pause"),writes);
    }
    @Test public void maStopFallbackConfirmsWithoutClaimingNativePausedState()throws Exception{
        stopFallback=true;List<String> diagnostics=new CopyOnWriteArrayList<>();controller.diagnostics(diagnostics::add);button("Pause").performClick();
        pump(()->button("Play")!=null&&button("Play").isEnabled()&&!diagnostics.isEmpty());
        assertEquals(Collections.singletonList("players/cmd/pause"),writes);assertTrue(diagnostics.get(diagnostics.size()-1).contains("confirmed=true"));
        assertNull(find(root,"pause submitted · state not confirmed. Check MA before retrying; no automatic retry."));
    }
    private void mutePlayer()throws Exception{button("Mute").performClick();pump(()->button("Unmute")!=null&&button("Unmute").isEnabled());assertTrue(muted);assertEquals(0,((SeekBar)button("Player volume")).getProgress());writes.clear();}
    private void slide(int value)throws Exception{SeekBar bar=(SeekBar)button("Player volume");bar.setProgress(value);Field f=SeekBar.class.getDeclaredField("mOnSeekBarChangeListener");f.setAccessible(true);((SeekBar.OnSeekBarChangeListener)f.get(bar)).onStopTrackingTouch(bar);}
    @Test public void muteDisplaysZeroAndUnmuteRestoresRetainedLevelWithoutVolumeWrite()throws Exception{
        mutePlayer();assertEquals(45,volume);button("Unmute").performClick();pump(()->!muted&&button("Mute")!=null&&button("Mute").isEnabled());assertEquals(45,((SeekBar)button("Player volume")).getProgress());assertEquals(Collections.singletonList("players/cmd/volume_mute"),writes);
    }
    @Test public void positiveSliderConfirmsLevelThenUnmutesOnce()throws Exception{
        mutePlayer();slide(27);pump(()->!muted&&volume==27&&button("Mute")!=null&&button("Mute").isEnabled());assertEquals(27,((SeekBar)button("Player volume")).getProgress());assertEquals(Arrays.asList("players/cmd/volume_set","players/cmd/volume_mute"),writes);
    }
    @Test public void zeroSliderWhileMutedDoesNotUnmute()throws Exception{
        mutePlayer();slide(0);pump(()->volume==0&&button("Unmute").isEnabled());assertTrue(muted);assertEquals(Collections.singletonList("players/cmd/volume_set"),writes);
    }
    @Test public void unconfirmedLevelCannotUnmuteOrAutomaticallyRetry()throws Exception{
        mutePlayer();ignoreVolume=true;slide(27);pump(()->find(root,"volume_set submitted · state not confirmed. Check MA before retrying; no automatic retry.")!=null);assertTrue(muted);assertEquals(Collections.singletonList("players/cmd/volume_set"),writes);
    }
    @Test public void changedGroupBetweenLevelAndUnmuteBlocksSecondMutation()throws Exception{
        mutePlayer();changedAfterVolume=true;slide(27);pump(()->find(root,"volume_set submitted · state not confirmed. Check MA before retrying; no automatic retry.")!=null);assertTrue(muted);assertEquals(Collections.singletonList("players/cmd/volume_set"),writes);
    }
    @Test public void uncertainUnmuteDoesNotClaimSuccessOrResendEitherStep()throws Exception{
        mutePlayer();ignoreUnmute=true;slide(27);pump(()->find(root,"volume_set submitted · state not confirmed. Check MA before retrying; no automatic retry.")!=null);assertTrue(muted);assertEquals(Arrays.asList("players/cmd/volume_set","players/cmd/volume_mute"),writes);
    }
}
