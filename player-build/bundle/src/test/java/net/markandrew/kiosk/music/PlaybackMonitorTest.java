package net.markandrew.kiosk.music;

import android.app.Activity;
import android.os.Looper;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
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

/** Real background monitor against loopback MA, including identity-bound first default. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PlaybackMonitorTest {
    private final String own="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private MusicAssistantPlugin plugin;
    private ServerSocket server;
    private ExecutorService http;
    private final List<Map<String,Object>> saves=new CopyOnWriteArrayList<>();
    private final List<String> commands=new CopyOnWriteArrayList<>();
    private volatile String state="idle",serverId="ma-instance-uuid";
    private volatile boolean changeInstance;
    private int infoReads;
    private volatile int reads;
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void poll()throws Exception{
        set(plugin,"nextMonitorRead",0L);
        Method m=MusicAssistantPlugin.class.getDeclaredMethod("pollReceiverPlayback");m.setAccessible(true);m.invoke(plugin);
        pump(()->{try{return !((Boolean)get(plugin,"monitorReading"));}catch(Exception e){throw new RuntimeException(e);}});
    }
    private JSONObject player(String id)throws Exception{return new JSONObject().put("player_id",id).put("name",id.equals(own)?"This kiosk":"Other default").put("type","player").put("available",true).put("enabled",true).put("active_source",id).put("playback_state",state);}
    @Before public void setup()throws Exception{
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();
        http.submit(()->{while(!server.isClosed())try{Socket accepted=server.accept();http.submit(()->{try(Socket socket=accepted){
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String first=reader.readLine(),line;int length=0;
            while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            char[] chars=new char[length];int count=0;while(count<length){int n=reader.read(chars,count,length-count);if(n<0)throw new EOFException();count+=n;}
            Object reply;
            if(first.contains("/auth/me"))reply=new JSONObject().put("user_id","fixture");
            else if(first.contains("/info"))reply=new JSONObject().put("server_id",changeInstance&&++infoReads>1?"changed-ma-instance":serverId);
            else{
                JSONObject request=new JSONObject(new String(chars));String command=request.getString("command"),id=request.getJSONObject("args").optString("player_id");
                if(command.equals("players/get")){reads++;reply=player(id);}
                else if(command.equals("player_queues/get"))reply=new JSONObject().put("queue_id",request.getJSONObject("args").getString("queue_id")).put("state",state).put("available",true).put("active",true);
                else throw new AssertionError("Unexpected monitor mutation / command "+command);
            }
            byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception e){throw new RuntimeException(e);}});}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        PluginHost host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("executeCommand")){
                String command=(String)args[0];commands.add(command);PluginHost.CommandCallback cb=(PluginHost.CommandCallback)args[2];
                if(command.equals("getDashboardState"))cb.onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);
                else if(command.equals("isScreensaverActive"))cb.onResult(true,true,null);
                else cb.onResult(false,null,"fixture unavailable");
            }else if(method.getName().equals("haCall"))((PluginHost.CommandCallback)args[1]).onResult(true,Collections.singletonMap("id","fixture-user"),null);
            else if(method.getName().equals("saveSettings"))saves.add(new HashMap<>((Map<String,Object>)args[0]));
            return null;
        });
        Map<String,Object> settings=new HashMap<>();settings.put("addon","fixture_addon");settings.put("receiverEnabled",false);settings.put("defaultPlayer","");settings.put("receiverName","Custom receiver");settings.put("preventScreensaverWhilePlaying",true);
        plugin=new MusicAssistantPlugin();plugin.start(host,settings);shadowOf(Looper.getMainLooper()).idle();
        settings.put("receiverEnabled",true);set(plugin,"settings",settings);
        PluginReceiver receiver=new PluginReceiver(activity);Map<String,Object> snapshot=new HashMap<>();
        snapshot.put("clientId",own);snapshot.put("serverId","paired-server");snapshot.put("running",true);snapshot.put("connected",true);snapshot.put("paired",true);snapshot.put("playback","idle");
        set(receiver,"snapshot",snapshot);set(plugin,"receiver",receiver);
        set(plugin,"localIdentity",own);
        set(get(plugin,"monitorAccess"),"cached",new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"));
    }
    private void pump(BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);do{shadowOf(Looper.getMainLooper()).idle();if(done.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<deadline);fail("Monitor did not finish");}
    @After public void stop()throws Exception{if(plugin!=null)plugin.stop();if(server!=null)server.close();if(http!=null)http.shutdownNow();shadowOf(Looper.getMainLooper()).idle();}
    @Test public void verifiedReceiverFillsOnlyEmptyDefaultWithoutResettingOtherSettings()throws Exception{
        poll();assertEquals(1,saves.size());assertEquals(own,saves.get(0).get("defaultPlayer"));assertEquals("Custom receiver",saves.get(0).get("receiverName"));assertEquals(true,saves.get(0).get("receiverEnabled"));
        poll();assertEquals(1,saves.size());
    }
    @Test public void changingMaInstanceCannotClaimDefault()throws Exception{changeInstance=true;poll();assertTrue(saves.isEmpty());}
    @Test public void existingDefaultIsObservedEvenWithLocalAudioIdle()throws Exception{
        Map<String,Object> settings=new HashMap<>((Map<String,Object>)get(plugin,"settings"));settings.put("defaultPlayer","other");settings.put("playerMode","Fixed default player");set(plugin,"settings",settings);
        Method create=MusicAssistantPlugin.class.getDeclaredMethod("createPlayerController");create.setAccessible(true);create.invoke(plugin);
        state="playing";set(plugin,"lastPostpone",-10000L);poll();assertTrue(saves.isEmpty());assertTrue(reads>0);assertTrue(commands.contains("postponeScreensaver"));
        commands.clear();state="paused";poll();assertFalse(commands.contains("postponeScreensaver"));
    }
}
