package net.markandrew.kiosk.music;

import android.app.Activity;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import me.jxl.kiosk.plugins.*;
import net.markandrew.kiosk.music.receiver.PluginReceiver;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real plugin host integration, receiver snapshots only. No playback or HTTP. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class HostPlaybackIntegrationTest {
    private MusicAssistantPlugin plugin;
    private PluginReceiver receiver;
    private final String own="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private Map<String,Object> settings;
    private final List<String> commands=new ArrayList<>(),overlays=new ArrayList<>();
    private boolean screensaver,defer;
    private PluginHost.CommandCallback delayed;
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private String playback="idle";
    private void invoke(String name)throws Exception{
        if(name.equals("pollReceiverPlayback")){
            Field epoch=MusicAssistantPlugin.class.getDeclaredField("integrationEpoch");epoch.setAccessible(true);
            Method m=MusicAssistantPlugin.class.getDeclaredMethod("acceptMonitoredPlayback",String.class,String.class,long.class);m.setAccessible(true);
            Field f=MusicAssistantPlugin.class.getDeclaredField("players");f.setAccessible(true);PlayerController players=(PlayerController)f.get(plugin);
            m.invoke(plugin,players.selectedId(),playback,epoch.getLong(plugin));
        }else{Method m=MusicAssistantPlugin.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(plugin);}
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void playback(String state)throws Exception{
        playback=state;
        Map<String,Object> s=new HashMap<>();s.put("clientId",own);s.put("serial",1L);
        for(String key:new String[]{"connected","paired","active","running"})s.put(key,true);
        s.put("playback",state);set(receiver,"snapshot",s);
    }
    @Before public void setup()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();receiver=new PluginReceiver(activity);
        PluginHost host=(PluginHost)Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("executeCommand")){
                String command=(String)args[0];commands.add(command);PluginHost.CommandCallback cb=(PluginHost.CommandCallback)args[2];
                if(command.equals("isScreensaverActive")){if(defer)delayed=cb;else cb.onResult(true,screensaver,null);}
                else if(command.equals("getVoiceState")||command.equals("getIntercomState"))cb.onResult(true,Collections.singletonMap("state","idle"),null);
                else cb.onResult(false,null,"fixture unavailable");
            }else if(method.getName().equals("showOverlay"))overlays.add((String)args[0]);
            return null;
        });
        settings=new HashMap<>();settings.put("addon","fixture");settings.put("defaultPlayer",own);settings.put("playerMode","Fixed default player");settings.put("receiverEnabled",false);
        plugin=new MusicAssistantPlugin();plugin.start(host,settings);shadowOf(Looper.getMainLooper()).idle();
        settings.put("receiverEnabled",true);set(plugin,"settings",settings);set(plugin,"receiver",receiver);playback("idle");
        set(plugin,"localIdentity",own);
    }
    @After public void stop(){plugin.stop();shadowOf(Looper.getMainLooper()).idle();}
    @Test public void savingOnlyHardwareOptionKeepsControllersAndSelection()throws Exception{
        Field controller=MusicAssistantPlugin.class.getDeclaredField("players");controller.setAccessible(true);Object original=controller.get(plugin);
        Field epoch=MusicAssistantPlugin.class.getDeclaredField("integrationEpoch");epoch.setAccessible(true);long before=epoch.getLong(plugin);
        Map<String,Object> updated=new HashMap<>(settings);updated.put("hardwareGroupVolume",true);plugin.configure(updated);
        assertSame(original,controller.get(plugin));assertEquals(before,epoch.getLong(plugin));assertEquals(own,((PlayerController)original).selectedId());
    }
    @Test public void externalStartOpensOnceAndDoesNotReopenAfterDismissal()throws Exception{
        invoke("pollReceiverPlayback");playback("playing");invoke("pollReceiverPlayback");assertEquals(Collections.singletonList("browser"),overlays);
        invoke("stopBrowsing");invoke("pollReceiverPlayback");assertEquals(1,overlays.size());
    }
    @Test public void nativeScreensaverIsNeverDismissedOrCovered()throws Exception{
        screensaver=true;playback("playing");invoke("pollReceiverPlayback");assertTrue(overlays.isEmpty());
        assertFalse(commands.contains("stopScreensaver"));assertFalse(commands.contains("postponeScreensaver"));assertFalse(commands.contains("showNowPlaying"));
    }
    @Test public void voiceTurnDefersLaunchUntilItEnds()throws Exception{
        plugin.onEvent("ks.voice.state",Collections.singletonMap("state","responding"));shadowOf(Looper.getMainLooper()).idle();
        playback("playing");invoke("pollReceiverPlayback");assertTrue(overlays.isEmpty());
        plugin.onEvent("ks.voice.state",Collections.singletonMap("state","idle"));shadowOf(Looper.getMainLooper()).idle();
        invoke("pollReceiverPlayback");assertEquals(Collections.singletonList("browser"),overlays);
    }
    @Test public void staleScreensaverReadCannotOpenAfterPlaybackStops()throws Exception{
        defer=true;playback("playing");invoke("pollReceiverPlayback");assertNotNull(delayed);
        playback("paused");invoke("pollReceiverPlayback");delayed.onResult(true,false,null);shadowOf(Looper.getMainLooper()).idle();assertTrue(overlays.isEmpty());
    }
    @Test public void unrelatedDefaultIsNeverSilentlyReplaced()throws Exception{
        settings.put("defaultPlayer","other");set(plugin,"settings",settings);invoke("createPlayerController");
        playback("playing");invoke("pollReceiverPlayback");assertEquals("other",settings.get("defaultPlayer"));
        assertEquals(Collections.singletonList("browser"),overlays); // Observe the actual selected target, not an unrelated local receiver.
    }
    @Test public void externalSavedDefaultDisablesLocalReceiverRegardlessOfTemporaryControlTarget()throws Exception{
        Method enabled=MusicAssistantPlugin.class.getDeclaredMethod("localReceiverEnabled");enabled.setAccessible(true);
        assertTrue((Boolean)enabled.invoke(plugin));settings.put("defaultPlayer","soundbar");set(plugin,"settings",settings);
        assertFalse((Boolean)enabled.invoke(plugin));assertEquals(true,settings.get("receiverEnabled"));
        Field field=MusicAssistantPlugin.class.getDeclaredField("players");field.setAccessible(true);set(field.get(plugin),"temporary",own);
        assertFalse((Boolean)enabled.invoke(plugin));settings.put("defaultPlayer",own);assertTrue((Boolean)enabled.invoke(plugin));
    }
    @Test public void localKeysNeverEnterLegacyFivePercentPathEvenBeforePlaybackIsRecognised()throws Exception{
        Map<String,Object> event=new HashMap<>();event.put("key","VOLUME_DOWN");event.put("action","down");event.put("repeat",0);event.put("time",java.time.Instant.now().toString());
        commands.clear();plugin.onEvent("ks.device.key",event);shadowOf(Looper.getMainLooper()).idle();
        assertFalse(commands.contains("getDashboardState"));
        event=new HashMap<>(event);event.put("repeat",8);plugin.onEvent("ks.device.key",event);shadowOf(Looper.getMainLooper()).idle();
        assertFalse(commands.contains("getDashboardState"));assertFalse(commands.contains("setVolume"));
    }
    @Test public void overlappingVoiceSourcesRestoreOnlyAfterTheLastEnds()throws Exception{
        Field f=PluginReceiver.class.getDeclaredField("gain");f.setAccessible(true);AudioGainPolicy gain=(AudioGainPolicy)f.get(receiver);
        Map<String,Object> command=new HashMap<>();command.put("source","command");command.put("active",true);
        plugin.onEvent("ks.voice.interaction",command);shadowOf(Looper.getMainLooper()).idle();
        Map<String,Object> page=new HashMap<>();page.put("source","page");page.put("active",true);
        plugin.onEvent("ks.voice.interaction",page);shadowOf(Looper.getMainLooper()).idle();
        command=new HashMap<>(command);command.put("active",false);plugin.onEvent("ks.voice.interaction",command);shadowOf(Looper.getMainLooper()).idle();
        assertEquals(.1f,gain.gain(),.00001f);
        page=new HashMap<>(page);page.put("active",false);plugin.onEvent("ks.voice.interaction",page);shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1f,gain.gain(),.00001f);
    }
    @Test public void screensaverPreventionIsOptInAndOnlyForConfirmedPlaying()throws Exception{
        playback("playing");invoke("pollReceiverPlayback");assertFalse(commands.contains("postponeScreensaver"));
        settings.put("preventScreensaverWhilePlaying",true);set(plugin,"settings",settings);set(plugin,"lastPostpone",-10000L);
        playback("paused");invoke("pollReceiverPlayback");assertFalse(commands.contains("postponeScreensaver"));
        playback("");invoke("pollReceiverPlayback");assertFalse(commands.contains("postponeScreensaver"));
        playback("playing");invoke("pollReceiverPlayback");assertEquals(1,Collections.frequency(commands,"postponeScreensaver"));
        invoke("pollReceiverPlayback");assertEquals(1,Collections.frequency(commands,"postponeScreensaver"));
    }
    @Test public void homeDismissesWithoutNavigatingOrReloadingDashboard()throws Exception{
        set(plugin,"showing",true);commands.clear();
        Method home=MusicAssistantPlugin.class.getDeclaredMethod("navigateNowPlaying",String.class);home.setAccessible(true);home.invoke(plugin,"home");shadowOf(Looper.getMainLooper()).idle();
        assertFalse(commands.contains("loadStartUrl"));assertFalse(commands.contains("reload"));
        Field showing=MusicAssistantPlugin.class.getDeclaredField("showing");showing.setAccessible(true);assertFalse(showing.getBoolean(plugin));
    }
    private void openFakePlayer()throws Exception{
        Field f=MusicAssistantPlugin.class.getDeclaredField("host");f.setAccessible(true);PluginHost host=(PluginHost)f.get(plugin);
        NowPlayingController c=new NowPlayingController(host,new IngressAccess(host),"fixture",own,()->true,()->own);
        set(c,"view",new NowPlayingView(Robolectric.buildActivity(Activity.class).setup().get(),new KsTheme(true,1,new HashMap<String,Integer>(){{for(String k:new String[]{"surface","surfaceContainerHigh","surfaceContainerHighest","onSurface","onSurfaceVariant","primary","onPrimary","primaryContainer","onPrimaryContainer","outlineVariant","error"})put(k,0xff556677);}},w->null,n->new android.graphics.Path()),new NowPlayingView.Actions(){public void close(){}public void control(String s,int v){}public void navigate(String s){}}));
        set(plugin,"showing",true);set(plugin,"nowPlayingSurface",true);set(plugin,"nowPlayingController",c);overlays.clear();
    }
    @Test public void callReturnReopensPriorPlayerOnIdleWithoutAPlaybackTransition()throws Exception{
        openFakePlayer();plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","ringing"));shadowOf(Looper.getMainLooper()).idle();
        plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","in_call"));plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","ended"));shadowOf(Looper.getMainLooper()).idle();assertTrue(overlays.isEmpty());
        plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","idle"));shadowOf(Looper.getMainLooper()).idle();assertEquals(Collections.singletonList("now_playing"),overlays);
        plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","idle"));shadowOf(Looper.getMainLooper()).idle();assertEquals(1,overlays.size());assertFalse(commands.contains("sendspinControl"));assertFalse(commands.contains("showNowPlaying"));
    }
    @Test public void interactionDuringCallCancelsPlayerRestoration()throws Exception{
        openFakePlayer();plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","ringing"));shadowOf(Looper.getMainLooper()).idle();plugin.interaction();
        plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","ended"));plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","idle"));shadowOf(Looper.getMainLooper()).idle();assertTrue(overlays.isEmpty());
    }
    @Test public void linkedMasterWaitsForAssistantAnnouncementAndIntercomToAllEnd()throws Exception{
        final int[] master={100};final boolean[] held={true};final List<Integer> reports=new ArrayList<>();
        LocalVolumeLink link=new LocalVolumeLink(new LocalVolumeLink.Port(){
            public void read(java.util.function.Consumer<Integer> done,java.util.function.Consumer<String> fail){done.accept(master[0]);}
            public void write(int value,Runnable done,java.util.function.Consumer<String> fail){assertTrue(held[0]);master[0]=value;done.run();}
            public void gate(boolean value){held[0]=value;receiver.linkedVolumeGate(value);}
            public boolean report(int value){reports.add(value);return true;}
            public void later(Runnable task,long delay){}
            public void error(String text){fail(text);}
        });
        set(plugin,"localVolume",link);link.observe("verified-local","playing",20,1);assertEquals(20,master[0]);assertFalse(held[0]);reports.clear();
        plugin.onEvent("ks.voice.state",Collections.singletonMap("state","responding"));
        plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","in_call"));
        Map<String,Object> announcement=new HashMap<>();announcement.put("source","command");announcement.put("active",true);
        plugin.onEvent("ks.voice.interaction",announcement);shadowOf(Looper.getMainLooper()).idle();assertEquals(100,master[0]);assertTrue(held[0]);assertTrue(reports.isEmpty());
        plugin.onEvent("ks.voice.state",Collections.singletonMap("state","idle"));announcement=new HashMap<>(announcement);announcement.put("active",false);plugin.onEvent("ks.voice.interaction",announcement);
        shadowOf(Looper.getMainLooper()).idle();assertEquals(100,master[0]);assertTrue(held[0]);
        plugin.onEvent("ks.intercom.state",Collections.singletonMap("state","ended"));shadowOf(Looper.getMainLooper()).idle();assertEquals(20,master[0]);assertFalse(held[0]);assertFalse(reports.contains(100));
    }
}
