package net.markandrew.kiosk.music;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import me.jxl.kiosk.plugins.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real loopback transport; no HA, MA or Android volume writes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class HardwareVolumeTest {
    private ServerSocket server;private ExecutorService http;private HardwareVolumeController controller;
    private PluginHost host;private MusicAssistantPlugin plugin;
    private final AtomicBoolean busy=new AtomicBoolean();private final AtomicLong clock=new AtomicLong(10000);
    private final List<String> writes=new CopyOnWriteArrayList<>(),messages=new CopyOnWriteArrayList<>(),hostCommands=new CopyOnWriteArrayList<>();
    private volatile String selected="speaker",state="playing",volumeControl="native",haUser="fixture-user";
    private volatile boolean group,mutateMembership,changeInstance,uncertain,available=true,enabled=true,visible=true,active=true;
    private volatile int volume=40,playerReads,infoReads;
    private volatile boolean switchTarget,ignoreWrite;
    private volatile boolean deny,groupMuted,groupPlayer;
    private int master=40;
    private boolean roundedOutput,driftBeforeWrite,oppositeMember,delayedOutput,zeroLocal,roundedLocal;
    private int readbackCount;
    private String diagnosticCase="";
    private Integer staleGroupSummary;
    private boolean remoteFifteenSteps;
    private String quantizedScenario="";
    private boolean missingRemoteRange,changingRemoteRange,quantizedPeerMute,quantizedPeerOpposite,quantizedAlternating;
    private Map<String,Object> key(String name,String action,int repeat){Map<String,Object> e=new HashMap<>();e.put("key",name);e.put("action",action);e.put("repeat",repeat);e.put("time",Instant.now().toString());return e;}
    private JSONObject player(String id)throws Exception{
        JSONObject p=new JSONObject().put("player_id",id).put("name",id).put("type",groupPlayer&&id.equals("speaker")?"group":"player").put("available",available).put("enabled",enabled).put("hidden",!visible).put("playback_state",state).put("active_source","speaker").put("volume_control",volumeControl).put("volume_level",volume).put("group_volume",volume).put("group_volume_muted",groupMuted);
        if(oppositeMember&&!writes.isEmpty()&&id.equals("member"))p.put("volume_level",60);
        if(zeroLocal)p.put("volume_level",id.equals("member")?0:volume);
        if(roundedLocal&&!writes.isEmpty())p.put("volume_level",id.equals("member")?35:33).put("group_volume",35);
        if(!diagnosticCase.isEmpty()){
            boolean after=!writes.isEmpty();int groupLevel=after||diagnosticCase.equals("unchanged")?33:36;
            int local=after?diagnosticCase.equals("unstable")?23+playerReads%2:23:diagnosticCase.equals("opposite")?20:diagnosticCase.equals("unchanged")?23:26;
            p.put("group_volume",groupLevel).put("volume_level",id.equals("member")?local:after?groupLevel*2-local:groupLevel);
            if(diagnosticCase.equals("unchanged"))p.put("volume_level",id.equals("member")?local:33);
            if(diagnosticCase.equals("unstable")&&!id.equals("member"))p.put("volume_level",after?30:36);
            if(diagnosticCase.equals("maxvalid"))p.put("volume_level",id.equals("member")?local:after?30:36);
            if(diagnosticCase.equals("stale43")||diagnosticCase.equals("real43"))p.put("group_volume",after?43:42).put("volume_level",!after?42:id.equals("member")?39:diagnosticCase.equals("stale43")?39:43);
            if(diagnosticCase.equals("steady20"))p.put("group_volume",20).put("volume_level",after&&id.equals("member")?17:20);
        }
        if(staleGroupSummary!=null)p.put("group_volume",staleGroupSummary);
        // A hypothetical 15-step remote and finer local receiver. This models
        // the screenshot, not a claim about the real remote's hardware.
        if(remoteFifteenSteps){int remote=Math.round(Math.round(volume*15f/100)*100f/15);p.put("group_volume",remote).put("volume_level",id.equals("member")?Math.round(volume*17f/60):remote);}
        if(!quantizedScenario.isEmpty()){
            boolean after=!writes.isEmpty()&&!ignoreWrite,own=id.equals("member");
            int beforeGroup=quantizedScenario.equals("down43")?47:quantizedScenario.equals("down56")?60:quantizedScenario.equals("up27")?33:40;
            int beforeLocal=quantizedScenario.equals("down56")?17:quantizedScenario.equals("up27")?10:27;
            int afterGroup=quantizedScenario.equals("down56")?53:quantizedScenario.equals("up27")?27:40;
            int afterLocal=quantizedScenario.equals("down43")?25:quantizedScenario.equals("down56")?16:quantizedScenario.equals("up27")?8:quantizedScenario.equals("allUnchanged")?27:31;
            if(quantizedScenario.equals("newTie")){beforeLocal=37;afterLocal=40;}
            if(quantizedPeerOpposite)afterGroup=beforeGroup+7;
            if(quantizedAlternating)afterLocal+=playerReads%2*2;
            p.put("group_volume",after?afterGroup:beforeGroup).put("volume_level",own?(after?afterLocal:beforeLocal):(after?afterGroup:beforeGroup));
            if(after&&quantizedPeerMute&&!own)p.put("volume_muted",true);
            if(own||!missingRemoteRange)p.put("device_info",new JSONObject().put("software_version",VolumeCapability.advertise("Music Assistant Plugin 0.9.52-dev",VolumeCapability.range(0,own?30:changingRemoteRange&&after?30:15))));
        }
        if(quantizedScenario.equals("grid")){
            // Real trace: MA retains an interpolation reference of local 17,
            // peers 20 even after all physical member levels have reached 20.
            boolean own=id.equals("member"),after=!writes.isEmpty()&&!ignoreWrite;
            int localCommand=volume>=20?(int)Math.round(17+83d*(volume-20)/80):(int)Math.round(17d*volume/20);
            int local=after?VolumeCapability.range(0,30).quantizedPercent(localCommand):20;
            int peer=after?VolumeCapability.range(0,15).quantizedPercent(volume):20;
            p.put("volume_level",own?local:peer).put("group_volume",Math.max(local,peer));
        }
        if(group){if(id.equals("speaker")){JSONArray members=new JSONArray().put("speaker").put(mutateMembership&&playerReads>1?"changed":"member");if(quantizedScenario.equals("newTie")||quantizedScenario.equals("grid"))members.put("peer2").put("peer3");p.put("group_members",members);}else p.put("synced_to","speaker");}return p;
    }
    private JSONArray players()throws Exception{playerReads++;if(driftBeforeWrite&&playerReads==2)volume=42;if(delayedOutput&&!writes.isEmpty()&&++readbackCount==4)volume=33;JSONArray rows=new JSONArray().put(player("speaker")).put(player("member"));if(quantizedScenario.equals("newTie")||quantizedScenario.equals("grid"))rows.put(player("peer2")).put(player("peer3"));return rows;}
    private JSONObject queue()throws Exception{return new JSONObject().put("queue_id","speaker").put("available",true).put("active",active).put("state",state);}
    private Object response(String path,String body)throws Exception{
        if(path.contains("/auth/me"))return new JSONObject().put("user_id","ma-user").put("role","admin");
        if(path.contains("/info")){infoReads++;if(switchTarget&&infoReads>1)selected="other";return new JSONObject().put("server_id",changeInstance&&infoReads>1?"other":"instance").put("server_version","2.10.2");}
        JSONObject request=new JSONObject(body);String cmd=request.getString("command");
        if(cmd.equals("players/all"))return players();if(cmd.equals("player_queues/get"))return queue();
        if(cmd.equals("auth/scopes"))return new JSONObject().put("admin",deny?new JSONArray():new JSONArray().put("*"));
        if(cmd.startsWith("players/cmd/")){writes.add(cmd+":"+request.getJSONObject("args").toString());if(!ignoreWrite&&!delayedOutput)volume=roundedOutput?35:request.getJSONObject("args").getInt("volume_level");if(uncertain)throw new IOException("Simulated lost mutation response");return new JSONObject();}
        throw new IllegalArgumentException("Unexpected command "+cmd);
    }
    @Before public void setup()throws Exception{
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();
        http.submit(()->{while(!server.isClosed())try{Socket socket=server.accept();http.submit(()->{try(Socket client=socket){
            BufferedReader r=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));String path=r.readLine(),line;int len=0;
            while((line=r.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(line.substring(15).trim());
            char[] body=new char[len];int n=0;while(n<len){int count=r.read(body,n,len-n);if(count<0)throw new EOFException();n+=count;}
            byte[] bytes=response(path,new String(body)).toString().getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception ignored){/* Failure closes the socket without a response. */}});}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
        host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("subscribe"))hostCommands.add("subscribe:"+args[0]);
            if(method.getName().equals("executeCommand")){String command=(String)args[0];hostCommands.add(command);if(command.equals("getVolume"))((PluginHost.CommandCallback)args[2]).onResult(true,master,null);else if(command.equals("setVolume")){master=Integer.parseInt(String.valueOf(((Map<?,?>)args[1]).get("percent")));((PluginHost.CommandCallback)args[2]).onResult(true,null,null);}else ((PluginHost.CommandCallback)args[2]).onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);}
            else if(method.getName().equals("haCall"))((PluginHost.CommandCallback)args[1]).onResult(true,Collections.singletonMap("id",haUser),null);return null;
        });
        controller=new HardwareVolumeController(host,()->"fixture_addon",()->selected,()->enabled,busy,(text,error)->messages.add(text),System::currentTimeMillis,clock::get);
        cacheController();
    }
    private void cacheController()throws Exception{
        Field access=HardwareVolumeController.class.getDeclaredField("access");access.setAccessible(true);Field cache=IngressAccess.class.getDeclaredField("cached");cache.setAccessible(true);
        cache.set(access.get(controller),new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"));
    }
    private void press(String key)throws Exception{assertTrue(controller.key(key(key,"down",0)));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);while(busy.get()&&System.nanoTime()<end)Thread.sleep(5);assertFalse("Volume request stuck",busy.get());}
    @After public void stop()throws Exception{if(plugin!=null)plugin.stop();if(controller!=null)controller.close();if(server!=null)server.close();if(http!=null)http.shutdownNow();}
    @Test public void soloUsesPlayerVolumeAndNeverMaster()throws Exception{press("VOLUME_UP");assertEquals(45,volume);assertEquals(1,writes.size());assertTrue(writes.get(0).startsWith("players/cmd/volume_set:"));assertTrue(messages.toString().contains("confirmed"));assertFalse(hostCommands.contains("setVolume"));}
    @Test public void selectedMemberUsesVerifiedGroupLeader()throws Exception{group=true;selected="member";press("VOLUME_DOWN");assertEquals(35,volume);assertEquals(1,writes.size());assertTrue(writes.get(0).startsWith("players/cmd/group_volume:"));assertTrue(writes.get(0).contains("\"player_id\":\"speaker\""));}
    @Test public void pausedDoesNotChangeVolume()throws Exception{state="paused";press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void idleDoesNotChangeVolume()throws Exception{state="idle";press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void inactiveQueueDoesNotChangeVolume()throws Exception{active=false;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void unsupportedVolumeDoesNotSend()throws Exception{volumeControl="none";press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void unavailablePlayerDoesNotSend()throws Exception{available=false;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void hiddenExternalCannotBeControlled()throws Exception{visible=false;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void verifiedHiddenLocalCanBeControlled()throws Exception{visible=false;controller.ownReceiver(session->"speaker");press("VOLUME_UP");assertEquals(45,volume);}
    @Test public void changedMembershipDoesNotSend()throws Exception{group=true;mutateMembership=true;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void changedInstanceDoesNotSend()throws Exception{changeInstance=true;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void changedHaUserDoesNotSend()throws Exception{haUser="other";press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void clampsUpperLimit()throws Exception{volume=98;press("VOLUME_UP");assertEquals(100,volume);}
    @Test public void clampsLowerLimit()throws Exception{volume=2;press("VOLUME_DOWN");assertEquals(0,volume);}
    @Test public void noWriteAtLimit()throws Exception{volume=100;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void uncertainWriteNeverRetriesAndBlocksHeldRepeats()throws Exception{uncertain=true;press("VOLUME_UP");assertEquals(1,writes.size());assertTrue(messages.toString().contains("no automatic retry"));clock.addAndGet(500);assertFalse(controller.key(key("VOLUME_UP","down",1)));assertEquals(1,writes.size());}
    @Test public void ignoresUpOtherKeysStaleAndMalformedEvents(){assertFalse(controller.key(key("VOLUME_UP","up",0)));assertFalse(controller.key(key("MEDIA_NEXT","down",0)));Map<String,Object> stale=key("VOLUME_UP","down",0);stale.put("time","2020-01-01T00:00:00Z");assertFalse(controller.key(stale));stale.put("time","invalid");assertFalse(controller.key(stale));Map<String,Object> bad=key("VOLUME_UP","down",0);bad.put("repeat",-1);assertFalse(controller.key(bad));assertTrue(writes.isEmpty());}
    @Test public void busyActionDoesNotQueuePress(){busy.set(true);assertFalse(controller.key(key("VOLUME_UP","down",0)));assertTrue(writes.isEmpty());busy.set(false);}
    @Test public void disabledAndClosedIgnoreKeys(){enabled=false;assertFalse(controller.key(key("VOLUME_UP","down",0)));enabled=true;controller.close();assertFalse(controller.key(key("VOLUME_UP","down",0)));}
    @Test public void selectedTargetChangeDoesNotSend()throws Exception{switchTarget=true;press("VOLUME_UP");assertTrue(writes.isEmpty());}
    @Test public void unconfirmedReadbackDoesNotResend()throws Exception{ignoreWrite=true;press("VOLUME_UP");assertEquals(1,writes.size());assertEquals(40,volume);assertTrue(messages.toString().contains("no automatic retry"));}
    @Test public void sequentialPressesReadFreshVolume()throws Exception{press("VOLUME_UP");clock.addAndGet(300);volume=70;press("VOLUME_DOWN");assertEquals(65,volume);assertEquals(2,writes.size());}
    private Integer localDelta(String own,int delta)throws Exception{
        return localChange(own,delta,false);
    }
    private Integer localChange(String own,int value,boolean absolute)throws Exception{
        FeatureApi api=new FeatureApi();try{return LocalGroupVolume.change(api,api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true),own,value,"",absolute);}finally{api.api.cancel();}
    }
    private void refusesLocal(String own)throws Exception{try{localDelta(own,7);fail("Expected refusal");}catch(IllegalArgumentException expected){}}
    @Test public void optionalLocalGroupRedirectUsesNativeDeltaNotAnExtraFixedStep()throws Exception{group=true;assertEquals(Integer.valueOf(47),localDelta("member",7));assertEquals(1,writes.size());assertTrue(writes.get(0).startsWith("players/cmd/group_volume:"));assertFalse(writes.toString().contains("/volume_set"));}
    @Test public void staleZeroSummaryStillSubmitsZeroForNonzeroMembers()throws Exception{group=true;volume=2;staleGroupSummary=0;assertEquals(Integer.valueOf(0),localDelta("member",-3));assertEquals(1,writes.size());assertTrue(writes.get(0).contains("\"volume_level\":0"));}
    @Test public void staleHundredSummaryStillSubmitsHundredForLowerMembers()throws Exception{group=true;volume=98;staleGroupSummary=100;assertEquals(Integer.valueOf(100),localDelta("member",3));assertEquals(1,writes.size());assertTrue(writes.get(0).contains("\"volume_level\":100"));}
    @Test public void staleZeroSummaryDoesNotStartAnUpwardStepAtZero()throws Exception{group=true;volume=20;staleGroupSummary=0;assertEquals(Integer.valueOf(23),localDelta("member",3));assertEquals(1,writes.size());}
    @Test public void staleHundredSummaryDoesNotStartADownwardStepAtHundred()throws Exception{group=true;volume=80;staleGroupSummary=100;assertEquals(Integer.valueOf(77),localDelta("member",-3));assertEquals(1,writes.size());}
    @Test public void actualZeroEndpointRemainsNoOpDespiteStaleSummary()throws Exception{group=true;volume=0;staleGroupSummary=20;assertEquals(Integer.valueOf(0),localDelta("member",-3));assertTrue(writes.isEmpty());}
    @Test public void actualHundredEndpointRemainsNoOpDespiteStaleSummary()throws Exception{group=true;volume=100;staleGroupSummary=80;assertEquals(Integer.valueOf(100),localDelta("member",3));assertTrue(writes.isEmpty());}
    @Test public void staleEndpointUnchangedReadbackNeverRetries()throws Exception{group=true;volume=2;staleGroupSummary=0;ignoreWrite=true;try{localDelta("member",-3);fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("member maximum unchanged"));}assertEquals(1,writes.size());}
    @Test public void absoluteZeroUsesMemberMaximumForSubmission()throws Exception{group=true;volume=2;staleGroupSummary=0;assertEquals(Integer.valueOf(0),localChange("member",0,true));assertEquals(1,writes.size());}
    @Test public void absoluteHundredUsesMemberMaximumForSubmission()throws Exception{group=true;volume=98;staleGroupSummary=100;assertEquals(Integer.valueOf(100),localChange("member",100,true));assertEquals(1,writes.size());}
    @Test public void screenshot60To56Observed53CanRejectEvenOnePressOnUnknownCoarseRemote()throws Exception{
        group=true;volume=60;remoteFifteenSteps=true;
        try{localDelta("member",-4);fail("Unknown remote resolution must remain conservative");}
        catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("requested 56%"));assertTrue(e.getMessage().contains("member max 53.00%"));assertTrue(e.getMessage().contains("local 17.00% to 16.00%"));assertTrue(e.getMessage().contains("member maximum outside supported tolerance"));assertFalse(e.getMessage().contains("moved opposite"));}
        assertEquals(1,writes.size());
    }
    @Test public void optionalLocalSoloReturnsToUnchangedLocalPathWithoutMaWrite()throws Exception{assertNull(localDelta("speaker",7));assertTrue(writes.isEmpty());}
    @Test public void optionalLocalWorksForDedicatedGroupPlayer()throws Exception{group=true;groupPlayer=true;assertEquals(Integer.valueOf(33),localDelta("member",-7));assertEquals(1,writes.size());}
    @Test public void optionalLocalDeniedPermissionNeverWrites()throws Exception{group=true;deny=true;refusesLocal("member");assertTrue(writes.isEmpty());}
    @Test public void optionalLocalChangedMembershipNeverWrites()throws Exception{group=true;mutateMembership=true;refusesLocal("member");assertTrue(writes.isEmpty());}
    @Test public void optionalLocalChangedServerNeverWrites()throws Exception{group=true;changeInstance=true;refusesLocal("member");assertTrue(writes.isEmpty());}
    @Test public void optionalLocalMutedGroupNeverWritesAnIndividualUnmute()throws Exception{group=true;groupMuted=true;refusesLocal("member");assertTrue(writes.isEmpty());}
    @Test public void optionalLocalUnverifiedGroupWriteNeverRetries()throws Exception{group=true;ignoreWrite=true;refusesLocal("member");assertEquals(1,writes.size());}
    @Test public void directGroupPlanIsReadOnlyAndBindsMembership()throws Exception{group=true;FeatureApi api=new FeatureApi();try{FeatureApi.Connection c=api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true);LocalGroupVolume.Plan plan=LocalGroupVolume.plan(api,c,"member");assertNotNull(plan);assertEquals(40,plan.level);assertTrue(writes.isEmpty());assertEquals(Integer.valueOf(61),LocalGroupVolume.change(api,c,"member",61,plan.scope,true));assertEquals(1,writes.size());}finally{api.api.cancel();}}
    @Test public void directGroupWrongScopeNeverFallsBackOrWrites()throws Exception{group=true;FeatureApi api=new FeatureApi();try{FeatureApi.Connection c=api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true);try{LocalGroupVolume.change(api,c,"member",61,"different group",true);fail();}catch(IllegalArgumentException expected){}assertTrue(writes.isEmpty());}finally{api.api.cancel();}}
    @Test public void hostEventControlsSavedPlayerWithoutOpeningPluginUi()throws Exception{
        controller.close();plugin=new MusicAssistantPlugin();Map<String,Object> settings=new HashMap<>();settings.put("addon","fixture_addon");settings.put("receiverEnabled",false);settings.put("defaultPlayer","speaker");settings.put("playerMode","Fixed default player");plugin.start(host,settings);
        Field field=MusicAssistantPlugin.class.getDeclaredField("hardwareVolume");field.setAccessible(true);controller=(HardwareVolumeController)field.get(plugin);cacheController();
        assertTrue(hostCommands.contains("subscribe:device.key"));plugin.onEvent("ks.device.key",key("VOLUME_UP","down",0));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(writes.isEmpty()&&System.nanoTime()<end){Thread.sleep(5);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
        assertEquals(1,writes.size());assertEquals(45,volume);Field showing=MusicAssistantPlugin.class.getDeclaredField("showing");showing.setAccessible(true);assertEquals(false,showing.get(plugin));assertFalse(hostCommands.contains("setVolume"));
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @Test public void nativeVolumeEventReachesRealGroupCommandWithoutMediaSessionOrLocalReport()throws Exception{
        // Established local master ownership and encrypted registration are fixtures;
        // event dispatch, plugin wiring, group API and verification are production code.
        group=true;plugin=new MusicAssistantPlugin();set(plugin,"host",host);set(plugin,"running",true);set(plugin,"volumeEventsAvailable",true);set(plugin,"addon","fixture_addon");set(plugin,"localIdentity","member");
        Map<String,Object> settings=new HashMap<>();settings.put("hardwareGroupVolume",true);settings.put("receiverEnabled",true);settings.put("defaultPlayer","member");set(plugin,"settings",settings);
        net.markandrew.kiosk.music.receiver.PluginReceiver local=new net.markandrew.kiosk.music.receiver.PluginReceiver(org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get());
        Map<String,Object> state=new HashMap<>();state.put("playback","playing");state.put("serial",1L);state.put("serverId","instance");set(local,"snapshot",state);set(plugin,"receiver",local);
        Method init=MusicAssistantPlugin.class.getDeclaredMethod("initializeVolumeLink");init.setAccessible(true);init.invoke(plugin);
        Object groupRoute=field(plugin,"localGroupVolume"),access=field(groupRoute,"access");set(access,"cached",new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"));
        Object link=field(plugin,"localVolume");set(link,"identity","member:instance:1");set(link,"playback","playing");set(link,"original",100);set(link,"master",40);set(link,"music",40);local.linkedVolumeGate(false);
        master=47;plugin.onEvent("ks.device.volume",Collections.emptyMap());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(!String.valueOf(field(plugin,"hardwareVolumeRoute")).startsWith("Group volume verified")&&System.nanoTime()<deadline){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertEquals(1,writes.size());assertTrue(writes.get(0).startsWith("players/cmd/group_volume:"));assertEquals(47,volume);assertEquals(47,master);assertFalse(local.groupVolumeRouted());assertFalse((Boolean)field(local,"volumeGate"));assertFalse(((LocalVolumeLink)link).needsRecovery());assertTrue(String.valueOf(field(plugin,"hardwareVolumeRoute")).startsWith("Group volume verified"));
    }
    private Integer steppedDelta(int step,List<String> stages)throws Exception{
        FeatureApi api=new FeatureApi();try{return LocalGroupVolume.change(api,api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true),"member",-7,"",false,step,stages::add);}finally{api.api.cancel();}
    }
    @Test public void localNativeStepRoundingCanVerifyWithoutResubmission()throws Exception{group=true;roundedLocal=true;assertEquals(Integer.valueOf(35),steppedDelta(7,new ArrayList<>()));assertEquals(1,writes.size());}
    @Test public void remoteRoundingCannotBorrowTheLocalNativeStep()throws Exception{group=true;roundedOutput=true;try{steppedDelta(7,new ArrayList<>());fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("remote max step unknown"));}assertEquals(1,writes.size());}
    @Test public void localZeroDoesNotPreventTurningDownRemainingGroupMembers()throws Exception{group=true;zeroLocal=true;assertEquals(Integer.valueOf(0),steppedDelta(7,new ArrayList<>()));assertEquals(33,volume);assertEquals(1,writes.size());}
    @Test public void benignPreSubmissionLevelEchoUsesFreshVolumeNotFalseMembershipFailure()throws Exception{group=true;driftBeforeWrite=true;assertEquals(Integer.valueOf(35),steppedDelta(7,new ArrayList<>()));assertEquals(1,writes.size());assertTrue(writes.get(0).contains("\"volume_level\":35"));}
    @Test public void unchangedReadbackWithinOneNativeStepIsNotAcceptedAsSuccess()throws Exception{group=true;ignoreWrite=true;try{steppedDelta(7,new ArrayList<>());fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("requested 33%"));assertTrue(e.getMessage().contains("observed group 40%"));}assertEquals(1,writes.size());}
    @Test public void oppositeMemberChangeIsNotAcceptedAsQuantisation()throws Exception{group=true;roundedOutput=true;oppositeMember=true;try{steppedDelta(7,new ArrayList<>());fail();}catch(IllegalArgumentException expected){}assertEquals(1,writes.size());}
    @Test public void delayedServerLevelsAreReadOnlyPolledNotResubmitted()throws Exception{group=true;delayedOutput=true;assertEquals(Integer.valueOf(33),steppedDelta(7,new ArrayList<>()));assertEquals(1,writes.size());assertTrue(readbackCount>=5);}
    @Test public void deniedGrantDiagnosticNamesPermissionWithoutUpstreamPayload()throws Exception{group=true;deny=true;try{steppedDelta(7,new ArrayList<>());fail();}catch(Exception e){String message=LocalGroupVolume.failure(false,"permission",e);assertTrue(message.contains("players.control permission"));assertTrue(message.contains("not sent"));}assertTrue(writes.isEmpty());assertFalse(LocalGroupVolume.failure(false,"read",new IOException("private upstream payload")).contains("private upstream"));}
    private Integer diagnosticDelta()throws Exception{
        FeatureApi api=new FeatureApi();try{return LocalGroupVolume.change(api,api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true),"member",diagnosticCase.equals("unchanged")||diagnosticCase.endsWith("43")||diagnosticCase.equals("steady20")?-3:-6,"",false,3,text->{});}finally{api.api.cancel();}
    }
    private String diagnosticFailure(String scenario)throws Exception{
        group=true;diagnosticCase=scenario;try{diagnosticDelta();fail("Expected verification rejection");return "";}catch(IllegalArgumentException e){assertEquals(1,writes.size());String message=LocalGroupVolume.failure(true,"group readback (sample)",e);assertTrue(message.contains("requested 30%"));assertTrue(message.contains("observed group 33%"));assertTrue(message.contains("native step 3%"));assertFalse(message.contains("outside native-step tolerance"));return message;}
    }
    @Test public void screenshotLevelsIdentifyUnchangedMemberMaximumNotInstability()throws Exception{String message=diagnosticFailure("unchanged");assertTrue(message.contains("member maximum unchanged"));assertTrue(message.contains("local 23%"));assertTrue(message.contains("before reported group 33%"));assertFalse(message.contains("readings not stable"));assertFalse(message.contains("moved opposite"));}
    @Test public void screenshotLevelsIdentifyLocalMemberDirectionNotTolerance()throws Exception{String message=diagnosticFailure("opposite");assertTrue(message.contains("local moved opposite direction (20.00% to 23.00%)"));assertFalse(message.contains("member maximum unchanged"));assertFalse(message.contains("readings not stable"));}
    @Test public void screenshotGroupWithinToleranceStillReportsAlternatingMemberReadings()throws Exception{String message=diagnosticFailure("unstable");assertTrue(message.contains("readings not stable across two samples"));assertFalse(message.contains("group unchanged"));assertFalse(message.contains("moved opposite"));}
    @Test public void averageStyleFixtureCannotProveMaMaximumTarget()throws Exception{String message=diagnosticFailure("valid");assertTrue(message.contains("member max 43.00%"));assertTrue(message.contains("member maximum outside supported tolerance"));}
    @Test public void stableMemberMaximumCanVerifyDespiteStaleGroupSummary()throws Exception{group=true;diagnosticCase="maxvalid";assertEquals(Integer.valueOf(23),diagnosticDelta());assertEquals(1,writes.size());assertTrue(writes.get(0).contains("\"volume_level\":30"));}
    @Test public void screenshot42To39WithStale43SummaryCanVerifyActualMembers()throws Exception{group=true;diagnosticCase="stale43";assertEquals(Integer.valueOf(39),diagnosticDelta());assertEquals(1,writes.size());}
    @Test public void screenshot42To39WithActual43PeerIsNotAccepted()throws Exception{group=true;diagnosticCase="real43";try{diagnosticDelta();fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("member maximum moved opposite requested direction"));assertTrue(e.getMessage().contains("member 2 42.00% to 43.00% (max)"));assertTrue(e.getMessage().contains("local 42.00% to 39.00%"));}assertEquals(1,writes.size());}
    @Test public void screenshot20To17ShowsUnchangedPeerInsteadOfOnlyLocalMovement()throws Exception{group=true;diagnosticCase="steady20";try{diagnosticDelta();fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("member maximum unchanged"));assertTrue(e.getMessage().contains("member 2 20.00% to 20.00% (max)"));}assertEquals(1,writes.size());}
    private Integer quantizedChange(String scenario,List<String> stages)throws Exception{
        group=true;quantizedScenario=scenario;
        // These historical fixtures exercise verification of an exact target,
        // independently of the new relative target-selection policy.
        int target=scenario.equals("down43")?43:scenario.equals("down56")?56:scenario.equals("up27")?24:43;
        FeatureApi api=new FeatureApi();try{return LocalGroupVolume.change(api,api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true),"member",target,"",true,3,stages::add);}finally{api.api.cancel();}
    }
    private String quantizedFailure(String scenario)throws Exception{try{quantizedChange(scenario,new ArrayList<>());fail("Expected conservative rejection");return "";}catch(IllegalArgumentException e){assertEquals(1,writes.size());return e.getMessage();}}
    @Test public void screenshot40To43UnchangedPeerCanVerifyWithIndependentLocalMovement()throws Exception{List<String> stages=new ArrayList<>();assertEquals(Integer.valueOf(31),quantizedChange("up43",stages));assertEquals(1,writes.size());assertTrue(stages.toString().contains("unchanged maximum explained by native rounding"));assertTrue(stages.toString().contains("quantized maximum 40%"));}
    @Test public void screenshot47To43Observed40VerifiesUsingTheRemoteRange()throws Exception{assertEquals(Integer.valueOf(25),quantizedChange("down43",new ArrayList<>()));assertEquals(1,writes.size());}
    @Test public void screenshot60To56Observed53VerifiesUsingTheRemoteRange()throws Exception{assertEquals(Integer.valueOf(16),quantizedChange("down56",new ArrayList<>()));assertEquals(1,writes.size());}
    @Test public void remoteQuantizedAboveRequestedMaximumCanVerifyWhenItStillMovesDown()throws Exception{assertEquals(Integer.valueOf(8),quantizedChange("up27",new ArrayList<>()));assertEquals(1,writes.size());}
    @Test public void unchangedUnknownRemoteStillCannotBorrowLocalOrOtherMemberRange()throws Exception{missingRemoteRange=true;assertTrue(quantizedFailure("up43").contains("member maximum unchanged"));}
    @Test public void allMembersUnchangedIsNotProofEvenWhenMaximumCouldRoundThere()throws Exception{assertTrue(quantizedFailure("allUnchanged").contains("member maximum unchanged"));}
    @Test public void ignoredCommandIsNotAcceptedUsingAdvertisedRounding()throws Exception{ignoreWrite=true;assertTrue(quantizedFailure("up43").contains("member maximum unchanged"));}
    @Test public void rangeChangeDuringReadbackIsNotAccepted()throws Exception{changingRemoteRange=true;assertTrue(quantizedFailure("down43").contains("advertised member volume range changed"));}
    @Test public void oppositeMovementWithKnownRangeStillRejectsWithoutRetry()throws Exception{quantizedPeerOpposite=true;assertTrue(quantizedFailure("down43").contains("moved opposite"));}
    @Test public void muteChangeWithKnownRangeStillRejectsWithoutRetry()throws Exception{quantizedPeerMute=true;assertTrue(quantizedFailure("down43").contains("mute flag changed"));}
    @Test public void alternatingMemberReadingsWithKnownRangesStillReject()throws Exception{quantizedAlternating=true;assertTrue(quantizedFailure("down43").contains("readings not stable"));}
    @Test public void liveFourMemberTrace37To40JoiningRoundedMaximumVerifiesOnce()throws Exception{List<String> stages=new ArrayList<>();assertEquals(Integer.valueOf(40),quantizedChange("newTie",stages));assertEquals(1,writes.size());assertTrue(writes.get(0).contains("43"));assertTrue(stages.toString().contains("local 37.00% to 40.00% (max)"));assertTrue(stages.toString().contains("unchanged maximum explained by native rounding"));}
    @Test public void ignoredFourMemberTraceStillRejectsWithNoMutationRetry()throws Exception{ignoreWrite=true;assertTrue(quantizedFailure("newTie").contains("member maximum unchanged"));}
    @Test public void unstableNewTieStillRejectsWithNoMutationRetry()throws Exception{quantizedAlternating=true;assertTrue(quantizedFailure("newTie").contains("readings not stable"));}
    private Integer gridChange(int value,boolean absolute)throws Exception{group=true;quantizedScenario="grid";FeatureApi api=new FeatureApi();try{return LocalGroupVolume.change(api,api.new Connection(new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"),()->true),"member",value,"",absolute,3,text->{});}finally{api.api.cancel();}}
    @Test public void liveDeadZoneSelects24OnceThenNextPressAdvancesFromActual27()throws Exception{assertEquals(Integer.valueOf(20),gridChange(3,false));assertEquals(24,requestVolume(0));assertEquals(Integer.valueOf(27),gridChange(3,false));assertEquals(30,requestVolume(1));assertEquals(2,writes.size());}
    @Test public void gridAwareDirectionReversalUsesFreshPhysicalMaximum()throws Exception{gridChange(3,false);assertEquals(Integer.valueOf(20),gridChange(-3,false));assertEquals(24,requestVolume(0));assertEquals(23,requestVolume(1));assertEquals(2,writes.size());}
    @Test public void absolute23StillReproducesAllUnchangedAndDoesNotRetry()throws Exception{try{gridChange(23,true);fail();}catch(IllegalArgumentException error){assertTrue(error.getMessage().contains("member maximum unchanged"));}assertEquals(23,requestVolume(0));assertEquals(1,writes.size());}
    @Test public void ignoredGridAwareCommandStillRejectsAndDoesNotRetry()throws Exception{ignoreWrite=true;try{gridChange(3,false);fail();}catch(IllegalArgumentException error){assertTrue(error.getMessage().contains("member maximum unchanged"));}assertEquals(24,requestVolume(0));assertEquals(1,writes.size());}
    private int requestVolume(int index)throws Exception{return new JSONObject(writes.get(index).substring(writes.get(index).indexOf(':')+1)).getInt("volume_level");}
}
