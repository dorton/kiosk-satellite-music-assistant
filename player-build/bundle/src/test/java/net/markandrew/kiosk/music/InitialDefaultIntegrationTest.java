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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.*;
import net.markandrew.kiosk.music.receiver.PluginReceiver;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real startup monitor, authenticated loopback reads and local settings saves only. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class InitialDefaultIntegrationTest {
    private static final String OWN="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",TRANSPORT="sendspin-cryptographic-server",INSTANCE="ma-instance-uuid";
    private Activity activity;private MusicAssistantPlugin plugin;private PluginReceiver receiver;
    private ServerSocket server;private ExecutorService http;
    private final List<Map<String,Object>> saves=new CopyOnWriteArrayList<>();
    private final List<String> reads=new CopyOnWriteArrayList<>();
    private final AtomicInteger infoReads=new AtomicInteger(),authReads=new AtomicInteger();
    private Map<String,Object> settings;private volatile TouchPanel panel;
    private volatile boolean missing,unavailable,queueUnreadable,changeInstance,changeUser,changeTransport;
    private volatile int queueItems;private volatile String playback="idle";
    private volatile String haUser="ha-user";
    private KsTheme theme(){Map<String,Integer> colors=new HashMap<>();for(String role:new String[]{"surface","surfaceContainerHigh","onSurface","onSurfaceVariant","primary","onPrimary","outlineVariant","error"})colors.put(role,0xff556677);return new KsTheme(true,1,colors,w->null,n->new android.graphics.Path());}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void state(String transport,boolean paired)throws Exception{
        Map<String,Object> state=new HashMap<>();for(String key:new String[]{"running","connected","active"})state.put(key,true);
        state.put("paired",paired);state.put("clientId",OWN);state.put("serverId",transport);state.put("serial",1L);set(receiver,"snapshot",state);
    }
    private JSONObject player(String id)throws Exception{return new JSONObject().put("player_id",id).put("name",id.equals(OWN)?"This kiosk":"Soundbar").put("type","player").put("available",!unavailable).put("enabled",true).put("hidden",id.equals(OWN)).put("playback_state",playback).put("active_source",id).put("volume_level",37);}
    private JSONObject queue(String id)throws Exception{return new JSONObject().put("queue_id",id).put("available",true).put("items",queueItems).put("state",playback);}
    private Object response(String request,String body)throws Exception{
        if(request.contains("/auth/me"))return new JSONObject().put("user_id",changeUser&&authReads.incrementAndGet()>1?"another-user":"ma-user");
        if(request.contains("/info")){int n=infoReads.incrementAndGet();if(changeTransport&&n>1)state("changed-transport",true);return new JSONObject().put("server_id",changeInstance&&n>1?"another-instance":INSTANCE);}
        JSONObject call=new JSONObject(body);String command=call.getString("command");reads.add(command);
        if(command.equals("players/get"))return player(missing?"wrong-player":call.getJSONObject("args").getString("player_id"));
        if(command.equals("players/all"))return new JSONArray().put(player(OWN)).put(player("soundbar"));
        if(command.equals("player_queues/all"))return new JSONArray().put(queue(OWN)).put(queue("soundbar"));
        if(command.equals("player_queues/get"))return queueUnreadable?JSONObject.NULL:queue(call.getJSONObject("args").getString("queue_id"));
        throw new AssertionError("Unexpected command: "+command);
    }
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();receiver=new PluginReceiver(activity);state(TRANSPORT,true);
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();
        http.submit(()->{while(!server.isClosed())try{Socket accepted=server.accept();http.submit(()->{try(Socket socket=accepted){
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String first=reader.readLine(),line;int length=0;
            while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            char[] body=new char[length];int count=0;while(count<length){int n=reader.read(body,count,length-count);if(n<0)throw new EOFException();count+=n;}
            byte[] bytes=response(first,new String(body)).toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception e){throw new RuntimeException(e);}});}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
        PluginHost host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("executeCommand")){
                String command=(String)args[0];PluginHost.CommandCallback cb=(PluginHost.CommandCallback)args[2];
                if(command.equals("getDashboardState"))cb.onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);else cb.onResult(false,null,"fixture unavailable");
            }else if(method.getName().equals("haCall"))((PluginHost.CommandCallback)args[1]).onResult(true,Collections.singletonMap("id",haUser),null);
            else if(method.getName().equals("saveSettings"))saves.add(new HashMap<>((Map<String,Object>)args[0]));
            else if(method.getName().equals("showOverlay")&&args[0].equals("item_actions")){View root=((OverlayFactory)args[2]).create(activity,theme());panel=(TouchPanel)get(plugin,"playerPanel");activity.setContentView(root);}
            return null;
        });
        settings=new HashMap<>();settings.put("addon","fixture_addon");settings.put("receiverEnabled",false);settings.put("defaultPlayer","");settings.put("playerMode","Fixed default player");settings.put("receiverName","Retained name");
        plugin=new MusicAssistantPlugin();plugin.start(host,settings);shadowOf(Looper.getMainLooper()).idle();set(plugin,"receiver",receiver);set(plugin,"localIdentity",OWN);
        IngressAccess.Session session=new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"ha-user","fixture_addon","fixture-cookie");
        for(String field:new String[]{"access","monitorAccess"})set(get(plugin,field),"cached",session);
        set(plugin,"displaySession",session);
    }
    private void pump(BooleanSupplier done)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);do{shadowOf(Looper.getMainLooper()).idle();if(done.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<end);fail("Initial-default operation did not finish");}
    private void poll()throws Exception{settings.put("receiverEnabled",true);set(plugin,"settings",new HashMap<>(settings));set(plugin,"nextMonitorRead",0L);Method m=MusicAssistantPlugin.class.getDeclaredMethod("pollReceiverPlayback");m.setAccessible(true);m.invoke(plugin);pump(()->{try{return !(Boolean)get(plugin,"monitorReading");}catch(Exception e){throw new RuntimeException(e);}});}
    private View find(View view,String label){if(view==null)return null;if(label.equals(String.valueOf(view.getContentDescription()))||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private void openChooser()throws Exception{set(plugin,"showing",true);BrowserView browser=new BrowserView(activity,theme(),plugin);set(plugin,"view",browser);activity.setContentView(browser);plugin.execute("open",Collections.emptyMap());pump(()->find(panel,"Soundbar")!=null);}
    @After public void stop()throws Exception{if(plugin!=null)plugin.stop();if(server!=null)server.close();if(http!=null)http.shutdownNow();shadowOf(Looper.getMainLooper()).idle();}
    @Test public void freshPairedLocalBecomesDefaultDespiteDifferentIdentityNamespaces()throws Exception{poll();assertEquals(1,saves.size());assertEquals(OWN,saves.get(0).get("defaultPlayer"));assertEquals("Retained name",saves.get(0).get("receiverName"));assertTrue(infoReads.get()>=2);}
    @Test public void verifiedPlayerDoesNotNeedAnInitialReadableQueueToBecomeDefault()throws Exception{queueUnreadable=true;poll();assertEquals(OWN,saves.get(0).get("defaultPlayer"));}
    @Test public void missingOrWrongExactPlayerCannotBecomeDefault()throws Exception{missing=true;poll();assertTrue(saves.isEmpty());}
    @Test public void unavailableLocalCannotBecomeDefault()throws Exception{unavailable=true;poll();assertTrue(saves.isEmpty());}
    @Test public void changingMaInstanceBlocksAssignment()throws Exception{changeInstance=true;poll();assertTrue(saves.isEmpty());}
    @Test public void changingMaUserBlocksAssignment()throws Exception{changeUser=true;poll();assertTrue(saves.isEmpty());}
    @Test public void changingSendspinTransportBlocksAssignment()throws Exception{changeTransport=true;poll();assertTrue(saves.isEmpty());}
    @Test public void unpairedReceiverCannotBecomeDefault()throws Exception{state(TRANSPORT,false);poll();assertTrue(saves.isEmpty());assertTrue(reads.isEmpty());}
    @Test public void savedExternalDefaultIsNotOverwritten()throws Exception{settings.put("defaultPlayer","soundbar");set(plugin,"settings",settings);Method create=MusicAssistantPlugin.class.getDeclaredMethod("createPlayerController");create.setAccessible(true);create.invoke(plugin);poll();assertTrue(saves.isEmpty());assertEquals("soundbar",((Map<?,?>)get(plugin,"settings")).get("defaultPlayer"));}
    @Test public void disabledLocalOpensChooserAndPersistsExternalDefaultBeforeLibrary()throws Exception{openChooser();assertNotNull(find(panel,"Choose default player"));assertNull(find(panel,"This kiosk"));View label=find(panel,"Soundbar");((View)label.getParent().getParent()).performClick();pump(()->!saves.isEmpty()&&!(Boolean)getUnchecked(plugin,"pendingOpenRoute"));assertEquals("soundbar",saves.get(0).get("defaultPlayer"));assertEquals(false,saves.get(0).get("receiverEnabled"));assertEquals("Retained name",saves.get(0).get("receiverName"));assertEquals(false,get(plugin,"nowPlayingSurface"));}
    private Object getUnchecked(Object target,String field){try{return get(target,field);}catch(Exception e){throw new RuntimeException(e);}}
    private Integer bootstrapVolume()throws Exception{
        ReceiverVolumeBootstrap reader=new ReceiverVolumeBootstrap((PluginHost)get(plugin,"host"),(ExecutorService)get(plugin,"monitorWorker"));
        set(get(reader,"access"),"cached",get(get(plugin,"monitorAccess"),"cached"));
        final boolean[] done={false};final Integer[] result={null};
        reader.read("fixture_addon",OWN,()->true,level->{result[0]=level;done[0]=true;});
        pump(()->done[0]);reader.close();return result[0];
    }
    @Test public void startupVolumeReadsExactExistingPlayerBeforeRegistrationEvenOffline()throws Exception{unavailable=true;assertEquals(Integer.valueOf(37),bootstrapVolume());assertTrue(saves.isEmpty());}
    @Test public void startupVolumeRejectsWrongExactPlayer()throws Exception{missing=true;assertNull(bootstrapVolume());}
    @Test public void startupVolumeRejectsChangedMaInstance()throws Exception{changeInstance=true;assertNull(bootstrapVolume());}
    @Test public void startupVolumeRejectsChangedMaAccount()throws Exception{changeUser=true;assertNull(bootstrapVolume());}
    @Test public void startupVolumeRejectsChangedHaAccount()throws Exception{haUser="changed";assertNull(bootstrapVolume());}
    @Test public void cancellingChooserLeavesDisabledReceiverAndDefaultUnset()throws Exception{openChooser();find(panel,"Cancel").performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(saves.isEmpty());assertEquals("",((Map<?,?>)get(plugin,"settings")).get("defaultPlayer"));assertEquals(false,((Map<?,?>)get(plugin,"settings")).get("receiverEnabled"));assertEquals(false,get(plugin,"showing"));}
    @Test public void haAccountChangeAfterOpeningChooserCannotSaveStaleChoice()throws Exception{openChooser();haUser="changed-ha-user";View label=find(panel,"Soundbar");((View)label.getParent().getParent()).performClick();pump(()->!((java.util.concurrent.atomic.AtomicBoolean)getUnchecked(plugin,"sending")).get());assertTrue(saves.isEmpty());assertEquals("",((Map<?,?>)get(plugin,"settings")).get("defaultPlayer"));}
    @Test public void aDefaultSavedElsewhereWhileChooserIsOpenIsNotOverwritten()throws Exception{openChooser();Map<String,Object> changed=new HashMap<>((Map<String,Object>)get(plugin,"settings"));changed.put("defaultPlayer","another-default");set(plugin,"settings",changed);View label=find(panel,"Soundbar");((View)label.getParent().getParent()).performClick();pump(()->!((java.util.concurrent.atomic.AtomicBoolean)getUnchecked(plugin,"sending")).get());assertTrue(saves.isEmpty());assertEquals("another-default",((Map<?,?>)get(plugin,"settings")).get("defaultPlayer"));}
}
