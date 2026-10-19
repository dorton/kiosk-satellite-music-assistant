package net.markandrew.kiosk.music;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real HTTP policy path, loopback only. Never connects to HA/MA or a physical receiver. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class EqConnectionTest {
    private ServerSocket server;
    private ExecutorService http;
    private final FeatureApi api=new FeatureApi();
    private FeatureApi.Connection c;
    private final AtomicBoolean current=new AtomicBoolean(true);
    private final AtomicInteger writes=new AtomicInteger();
    private volatile JSONObject config;
    private volatile String user="account",instance="ma",provider="sendspin",output="native",version="2.10.6",kind="player",savedPlayer="";
    private volatile boolean readAllowed=true,writeAllowed=true,ignoreSave,closeAfterSave;
    private FeatureController editor;
    private NowPlayingController playback;
    private ExecutorService editorWorker;
    private android.app.Activity activity;
    private final AtomicReference<TouchPanel> panel=new AtomicReference<>();
    private final AtomicBoolean dismissed=new AtomicBoolean();
    private android.widget.FrameLayout root;
    private JSONObject scopes()throws Exception{
        JSONArray grants=new JSONArray().put("players.control");if(readAllowed)grants.put("config.players.read");if(writeAllowed)grants.put("config.players.write");return FeatureApi.args("user",grants);
    }
    @Before public void setup()throws Exception{
        config=EqTest.config();server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();
        http.submit(()->{while(!server.isClosed())try{Socket s=server.accept();http.submit(()->serve(s));}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
        URI base=URI.create("http://127.0.0.1:"+server.getLocalPort()+"/");
        c=api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),base,"ha-account","fixture","fixture"),current::get);
    }
    private void serve(Socket socket){try(Socket s=socket){
        BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));String first=in.readLine(),line;int length=0;
        while((line=in.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
        Object result;
        if(first.contains(" /auth/me "))result=FeatureApi.args("user_id",user,"role","user","player_filter",new JSONArray());
        else if(first.contains(" /info "))result=FeatureApi.args("server_id",instance,"server_version",version);
        else{
            char[] chars=new char[length];int count=0;while(count<length){int n=in.read(chars,count,length-count);if(n<0)throw new EOFException();count+=n;}
            JSONObject request=new JSONObject(new String(chars));String command=request.getString("command");JSONObject args=request.getJSONObject("args");
            switch(command){
                case "auth/scopes":result=scopes();break;
                case "players/get":result=FeatureApi.args("player_id","satellite","name","Lounge","provider",provider,"type",kind,"available",true,"active_output_protocol",output);break;
                case "config/players/dsp/get":result=config;break;
                case "config/players/dsp/save":writes.incrementAndGet();savedPlayer=args.getString("player_id");if(!ignoreSave)config=EqDraft.copy(args.getJSONObject("config"));if(closeAfterSave)return;result=config;break;
                default:throw new IllegalArgumentException("Unexpected fixture command");
            }
        }
        byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }catch(Exception e){if(!server.isClosed())throw new RuntimeException(e);}}
    @After public void close()throws Exception{if(editor!=null)editor.close();if(playback!=null)playback.close();if(editorWorker!=null)editorWorker.shutdownNow();api.api.cancel();server.close();http.shutdownNow();}
    private EqApi.State load()throws Exception{return new EqApi().load(api,c,"satellite");}
    private EqApi.State edited()throws Exception{EqApi.State state=load();state.draft.add("tone_control").put("bass_level",2.5);return state;}
    private void reject(EqApi.State state)throws Exception{try{new EqApi().save(api,c,"satellite",state);fail("Expected refusal");}catch(Exception expected){assertEquals(0,writes.get());}}
    @Test public void saveUsesExactPlayerAndVerifiesPreservedFilters()throws Exception{
        EqApi.State state=edited();new EqApi().save(api,c,"satellite",state);assertEquals(1,writes.get());assertEquals("satellite",savedPlayer);assertTrue(c.submitted);
        assertTrue(EqDraft.same(state.draft.candidate(),config));assertEquals(2,config.getJSONArray("filters").length());assertEquals(-4,config.getDouble("input_gain"),0);
    }
    @Test public void loadAndDraftEditsNeverWrite()throws Exception{edited();assertEquals(0,writes.get());assertFalse(c.submitted);}
    @Test public void repeatedApplyUsesVerifiedBaselineAndKeepsBothCurves()throws Exception{
        EqApi.State first=load();first.draft.select(true);EqGraphic graphic=new EqGraphic(first.draft);graphic.band(0,5);graphic.preamp(-6);graphic.activate(true);
        EqApi.State second=new EqApi().save(api,c,"satellite",first);assertFalse(second.draft.changed());assertEquals(1,writes.get());
        second.draft.select(false);graphic=new EqGraphic(second.draft);graphic.ensureTone().put("bass_level",3);graphic.activate(false);
        EqApi.State third=new EqApi().save(api,c,"satellite",second);assertEquals(2,writes.get());assertFalse(new EqGraphic(third.draft).bank().getBoolean("enabled"));
        third.draft.select(true);new EqGraphic(third.draft).activate(true);EqApi.State fourth=new EqApi().save(api,c,"satellite",third);
        assertEquals(3,writes.get());assertTrue(new EqGraphic(fourth.draft).bank().getBoolean("enabled"));assertFalse(new EqGraphic(fourth.draft).tone().getBoolean("enabled"));assertEquals(5,new EqGraphic(fourth.draft).gains()[0],0);assertEquals(3,new EqGraphic(fourth.draft).tone().getDouble("bass_level"),0);
    }
    @Test public void presetLibraryOnlyVerificationDoesNotSubmitDsp()throws Exception{EqApi.State s=load();new EqApi().verify(api,c,"satellite",s);assertEquals(0,writes.get());assertFalse(c.submitted);}
    @Test public void presetLibraryOnlyVerificationStillChecksWritePermission()throws Exception{EqApi.State s=load();writeAllowed=false;try{new EqApi().verify(api,c,"satellite",s);fail();}catch(IllegalArgumentException expected){assertEquals(0,writes.get());}}
    @Test public void missingWriteScopeIsReadOnly()throws Exception{writeAllowed=false;EqApi.State s=edited();assertFalse(s.writable);reject(s);}
    @Test public void permissionRevocationBeforeApplyRefuses()throws Exception{EqApi.State s=edited();writeAllowed=false;reject(s);}
    @Test public void missingReadScopeRefusesWithoutMutation()throws Exception{readAllowed=false;try{load();fail();}catch(IllegalArgumentException expected){assertEquals(0,writes.get());}}
    @Test public void concurrentDspEditIsNotOverwritten()throws Exception{EqApi.State s=edited();config=EqDraft.copy(config).put("input_gain",-8);reject(s);}
    @Test public void changedAccountRefuses()throws Exception{EqApi.State s=edited();user="another";reject(s);}
    @Test public void changedServerRefuses()throws Exception{EqApi.State s=edited();instance="another";reject(s);}
    @Test public void changedProviderRefuses()throws Exception{EqApi.State s=edited();provider="another";reject(s);}
    @Test public void changedOutputRefuses()throws Exception{EqApi.State s=edited();output="other";reject(s);}
    @Test public void changedSelectionRefuses()throws Exception{EqApi.State s=edited();try{new EqApi().save(api,c,"another",s);fail();}catch(IllegalArgumentException expected){assertEquals(0,writes.get());}}
    @Test public void staleViewCannotWrite()throws Exception{EqApi.State s=edited();current.set(false);reject(s);}
    @Test public void groupSelectionIsNotRedirected()throws Exception{kind="group";try{load();fail();}catch(IllegalArgumentException expected){assertEquals(0,writes.get());}}
    @Test public void unsupportedServerVersionFailsClosed()throws Exception{version="2.11.0";try{load();fail();}catch(IllegalArgumentException expected){assertEquals(0,writes.get());}}
    @Test public void ignoredSaveRemainsUnverifiedAndIsNotRetried()throws Exception{EqApi.State s=edited();ignoreSave=true;try{new EqApi().save(api,c,"satellite",s);fail();}catch(IllegalArgumentException expected){assertEquals(1,writes.get());assertTrue(c.submitted);}}
    @Test public void lostResponseIsUncertainAndNotRetried()throws Exception{EqApi.State s=edited();closeAfterSave=true;try{new EqApi().save(api,c,"satellite",s);fail();}catch(Exception expected){assertEquals(1,writes.get());assertTrue(c.submitted);}}
    private me.jxl.kiosk.plugins.KsTheme theme(){java.util.Map<String,Integer> colors=new java.util.HashMap<>();for(String role:new String[]{"surface","surfaceContainer","surfaceContainerHigh","surfaceContainerHighest","onSurface","onSurfaceVariant","primary","onPrimary","primaryContainer","onPrimaryContainer","outline","outlineVariant","secondaryContainer","onSecondaryContainer","error","success"})colors.put(role,0xff556677);return new me.jxl.kiosk.plugins.KsTheme(true,1,colors,w->null,n->new android.graphics.Path());}
    private void createEditor()throws Exception{
        activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();root=new android.widget.FrameLayout(activity);activity.setContentView(root);
        for(String file:new String[]{"musicassistantplugin-eq-view-v1.txt","musicassistantplugin-eq-modes-v1.json","musicassistantplugin-eq-presets-v1.json"})new android.util.AtomicFile(new File(activity.getNoBackupFilesDir(),file)).delete();
        me.jxl.kiosk.plugins.PluginHost host=(me.jxl.kiosk.plugins.PluginHost)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{me.jxl.kiosk.plugins.PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("executeCommand")){((me.jxl.kiosk.plugins.PluginHost.CommandCallback)args[2]).onResult(true,java.util.Collections.singletonMap("homeAssistantUrl","https://fixture.invalid/"),null);return null;}
            if(method.getName().equals("haCall")){((me.jxl.kiosk.plugins.PluginHost.CommandCallback)args[1]).onResult(true,java.util.Collections.singletonMap("id","ha-account"),null);return null;}
            return null;
        });
        IngressAccess access=new IngressAccess(host);java.lang.reflect.Field cached=IngressAccess.class.getDeclaredField("cached");cached.setAccessible(true);cached.set(access,c.session);
        editorWorker=Executors.newSingleThreadExecutor();android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        editor=new FeatureController(host,access,editorWorker,new AtomicBoolean(),()->"fixture",()->"satellite",()->true,new FeatureController.Ui(){
            public void panel(String title,java.util.function.BooleanSupplier current,java.util.function.Consumer<TouchPanel> build){main.post(()->{if(!current.getAsBoolean())return;TouchPanel p=new TouchPanel(activity,theme(),title);root.removeAllViews();root.addView(p);build.accept(p);panel.set(p);});}
            public void dismiss(){dismissed.set(true);root.removeAllViews();}
            public void message(String message){}public void refresh(){}public void play(BrowserItem item,String option,boolean shuffle,String start){}public void open(BrowserItem item){}public void players(Runnable after){}
        });
        editor.equaliser();await(()->panel.get()!=null&&find(panel.get(),writeAllowed?"Apply":"EQ requires config.players.write. MA 2.10.x normally requires an administrator-authorised Ingress identity. No permissions were changed.")!=null);
        // Let the request's finally block release single-write admission.
        editorWorker.submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    private android.view.View find(android.view.View v,String text){if(v==null)return null;if(text.equals(String.valueOf(v.getContentDescription()))||v instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)v).getText()))return v;if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){android.view.View match=find(((android.view.ViewGroup)v).getChildAt(i),text);if(match!=null)return match;}return null;}
    private void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();if(condition.getAsBoolean())return;Thread.sleep(10);}fail("Timed out waiting for isolated fixture UI");}
    private void applyEditor()throws Exception{TouchPanel before=panel.get();find(before,"Apply").performClick();await(()->panel.get()!=before&&find(panel.get(),"Apply")!=null);editorWorker.submit(()->{}).get(5,TimeUnit.SECONDS);assertFalse(dismissed.get());}
    @Test public void controllerApplyStaysOpenRebasesAndCloseDiscardsOnlyPendingEdits()throws Exception{
        createEditor();find(panel.get(),"Bass · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();assertEquals(1,writes.get());assertEquals(1,new EqGraphic(new EqDraft(config)).tone().getDouble("bass_level"),0);
        find(panel.get(),"Bass · 1 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();assertEquals(2,writes.get());applyEditor();assertEquals(2,writes.get());
        find(panel.get(),"Bass · 2 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);find(panel.get(),"Close").performClick();assertTrue(dismissed.get());assertEquals(2,writes.get());assertEquals(2,new EqGraphic(new EqDraft(config)).tone().getDouble("bass_level"),0);
    }
    @Test public void controllerSwitchResetsLeavingTypeOnlyOnApplyAndThenDisplaysFlat()throws Exception{
        createEditor();find(panel.get(),"Switch to Advanced").performClick();await(()->find(panel.get(),"32 · 0 dB")!=null);
        find(panel.get(),"32 · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();
        find(panel.get(),"Switch to Basic").performClick();await(()->find(panel.get(),"Bass · 0 dB")!=null);
        assertEquals(1,new EqGraphic(new EqDraft(config)).gains()[0],0);find(panel.get(),"Bass · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);
        applyEditor();assertFalse(new EqGraphic(new EqDraft(config)).bank().getBoolean("enabled"));assertEquals(0,new EqGraphic(new EqDraft(config)).gains()[0],0);
        find(panel.get(),"Switch to Advanced").performClick();await(()->find(panel.get(),"32 · 0 dB")!=null);assertNotNull(find(panel.get(),"Flat ▾"));assertNotNull(find(panel.get(),"Preamp · 0 dB"));assertEquals(1,new EqGraphic(new EqDraft(config)).tone().getDouble("bass_level"),0);
        find(panel.get(),"32 · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();EqGraphic g=new EqGraphic(new EqDraft(config));assertTrue(g.bank().getBoolean("enabled"));assertFalse(g.tone().getBoolean("enabled"));assertEquals(1,g.gains()[0],0);assertEquals(0,g.tone().getDouble("bass_level"),0);assertEquals(3,writes.get());
    }
    @Test public void controllerDisableDoesNotMoveSlidersUntilApplied()throws Exception{
        createEditor();find(panel.get(),"Bass · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();
        ((android.widget.Switch)find(panel.get(),"EQ / DSP enabled")).setChecked(false);assertNotNull(find(panel.get(),"Bass · 1 dB"));assertTrue(config.getBoolean("enabled"));assertEquals(1,writes.get());
        applyEditor();assertFalse(config.getBoolean("enabled"));assertEquals(0,config.getDouble("input_gain"),0);assertNotNull(find(panel.get(),"Bass · 0 dB"));assertNotNull(find(panel.get(),"Preamp · 0 dB"));assertEquals(2,writes.get());
    }
    @Test public void disablingAdvancedAppliesFlatIncludingZeroPreamp()throws Exception{
        createEditor();find(panel.get(),"Switch to Advanced").performClick();await(()->find(panel.get(),"32 · 0 dB")!=null);find(panel.get(),"32 · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();
        ((android.widget.Switch)find(panel.get(),"EQ / DSP enabled")).setChecked(false);assertNotNull(find(panel.get(),"32 · 1 dB"));assertEquals(1,new EqGraphic(new EqDraft(config)).gains()[0],0);
        applyEditor();assertFalse(config.getBoolean("enabled"));assertEquals(0,config.getDouble("input_gain"),0);assertNotNull(find(panel.get(),"Flat ▾"));assertNotNull(find(panel.get(),"Preamp · 0 dB"));assertNotNull(find(panel.get(),"32 · 0 dB"));assertEquals(2,writes.get());
    }
    @Test public void closeAfterPendingTypeAndEnableChangesDoesNotResetMa()throws Exception{
        createEditor();find(panel.get(),"Bass · 0 dB").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);applyEditor();String before=config.toString();
        find(panel.get(),"Switch to Advanced").performClick();await(()->find(panel.get(),"32 · 0 dB")!=null);((android.widget.Switch)find(panel.get(),"EQ / DSP enabled")).setChecked(false);find(panel.get(),"Close").performClick();assertTrue(dismissed.get());assertEquals(before,config.toString());assertEquals(1,writes.get());
    }
    @Test public void controllerRefusesDirectEqOpenWithoutWritePermission()throws Exception{writeAllowed=false;createEditor();assertNull(find(panel.get(),"Apply"));assertEquals(0,writes.get());}
    @Test public void navigationChecksActualScopesAndRevocationWithoutBlockingPlayback()throws Exception{
        activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();NowPlayingView view=new NowPlayingView(activity,theme(),new NowPlayingView.Actions(){public void close(){}public void control(String command,int value){}public void navigate(String destination){}});
        playback=new NowPlayingController(null,null,"fixture","satellite",()->true,()->"satellite");java.lang.reflect.Field field=NowPlayingController.class.getDeclaredField("view");field.setAccessible(true);field.set(playback,view);
        java.lang.reflect.Method resolve=NowPlayingController.class.getDeclaredMethod("resolveEq",IngressAccess.Session.class);resolve.setAccessible(true);
        java.lang.reflect.Field checked=NowPlayingController.class.getDeclaredField("eqChecked"),reading=NowPlayingController.class.getDeclaredField("eqReading");checked.setAccessible(true);reading.setAccessible(true);
        android.view.View eq=find(view,"Equaliser");assertEquals(android.view.View.GONE,eq.getVisibility());
        for(boolean grant:new boolean[]{false,true,false}){
            writeAllowed=grant;checked.setLong(playback,-15000);resolve.invoke(playback,c.session);
            await(()->{try{return !reading.getBoolean(playback);}catch(Exception e){throw new RuntimeException(e);}});
            assertEquals(grant?android.view.View.VISIBLE:android.view.View.GONE,eq.getVisibility());assertEquals(android.view.View.VISIBLE,find(view,"Queue").getVisibility());assertEquals(0,writes.get());
        }
    }
}
