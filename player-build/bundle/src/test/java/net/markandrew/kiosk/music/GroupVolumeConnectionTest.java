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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import me.jxl.kiosk.plugins.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real guarded group-volume write/readback. No MA/HA connection or device audio. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30,qualifiers="w960dp-h480dp-land-mdpi")
public class GroupVolumeConnectionTest {
    private ServerSocket server;
    private ExecutorService http,worker;
    private FeatureController controller;
    private Activity activity;
    private KsTheme theme;
    private volatile int popupRenders;
    private TouchPanel panel;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final List<String> writes=new CopyOnWriteArrayList<>();
    private volatile int volume=40;
    private volatile boolean muted,changedGroup;
    private volatile String track="one";
    private volatile boolean gap,noLyrics,changeDuringLyrics,queueUnreadable;
    private volatile int lyricCalls;
    private final Set<String> favorites=ConcurrentHashMap.newKeySet();
    private volatile JSONObject joinedArgs;
    private final CountDownLatch slowLyrics=new CountDownLatch(1),releaseLyrics=new CountDownLatch(1);
    private volatile boolean delayLyrics;
    private volatile boolean extraLeader,movedToNew;
    private volatile boolean cleared,ignoreClear;
    private volatile int libraryVisits;
    private volatile String joinedTarget="",joiningSource="bed";
    private volatile String selectedId="hall";
    private int renders;
    private JSONObject player(String id)throws Exception{
        if(extraLeader&&id.equals("new"))return new JSONObject().put("player_id","new").put("name","Lounge").put("type","player").put("available",true).put("enabled",true)
            .put("can_group_with",new JSONArray().put("bed").put("hall")).put("supported_features",new JSONArray().put("set_members")).put("group_members",new JSONArray(movedToNew?Arrays.asList("new",joiningSource):Arrays.asList("new")));
        if(movedToNew&&id.equals(joiningSource))return new JSONObject().put("player_id",id).put("name",id).put("type","player").put("available",true).put("enabled",true).put("synced_to","new");
        return new JSONObject().put("player_id",id).put("name",id.equals("hall")?"Hallway":"Bedroom").put("type","player").put("available",true).put("enabled",true)
            .put("synced_to",id.equals("hall")||changedGroup?JSONObject.NULL:"hall").put("group_members",id.equals("hall")?new JSONArray(changedGroup?Arrays.asList("hall"):Arrays.asList("hall","bed")):new JSONArray())
            .put("supported_features",new JSONArray(Arrays.asList("set_members"))).put("volume_control","native").put("mute_control","native")
            .put("can_group_with",new JSONArray().put("bed"))
            .put("playback_state",cleared?"idle":"playing").put("active_source","hall")
            .put("volume_level",volume).put("group_volume",volume).put("volume_muted",muted).put("group_volume_muted",muted);
    }
    private JSONObject queue()throws Exception{
        if(cleared)return new JSONObject().put("queue_id","hall").put("available",true).put("items",0).put("state","idle");
        int index=Arrays.asList("one","two","three").indexOf(track);
        JSONObject q=new JSONObject().put("queue_id","hall").put("available",true).put("items",3).put("state","playing").put("current_index",index).put("index_in_buffer",index);
        if(!gap)q.put("current_item",new JSONObject().put("queue_item_id",track).put("media_item",new JSONObject().put("uri","library://track/"+track).put("media_type","track")));
        return q;
    }
    @Before public void setup()throws Exception{
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));http=Executors.newCachedThreadPool();worker=Executors.newSingleThreadExecutor();
        http.submit(()->{while(!server.isClosed())try{Socket accepted=server.accept();http.submit(()->{try(Socket socket=accepted){
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String first=reader.readLine(),line;int length=0;
            while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            char[] chars=new char[length];int count=0;while(count<length){int n=reader.read(chars,count,length-count);if(n<0)throw new EOFException();count+=n;}
            Object reply;
            if(first.contains("/auth/me"))reply=new JSONObject().put("user_id","fixture");
            else{
                JSONObject request=new JSONObject(new String(chars));String command=request.getString("command");
                if(command.equals("players/all")){JSONArray rows=new JSONArray().put(player("hall")).put(player("bed"));if(extraLeader)rows.put(player("new"));reply=rows;}
                else if(command.equals("player_queues/all"))reply=queueUnreadable?new JSONObject():new JSONArray().put(queue());
                else if(command.equals("player_queues/get"))reply=queue();
                else if(command.equals("players/get"))reply=player(request.getJSONObject("args").getString("player_id"));
                else if(command.equals("providers"))reply=new JSONArray();
                else if(command.equals("player_queues/items")){
                    JSONArray all=new JSONArray();if(!cleared)for(String name:Arrays.asList("one","two","three"))all.put(new JSONObject().put("queue_item_id",name).put("available",true).put("media_item",new JSONObject().put("name","Track "+name).put("uri","library://track/"+name).put("media_type","track")));reply=all;
                }
                else if(command.equals("player_queues/clear")){writes.add(command);if(!ignoreClear)cleared=true;reply=new JSONObject();}
                else if(command.equals("music/item_by_uri")){
                    String uri=request.getJSONObject("args").getString("uri");reply=new JSONObject().put("uri",uri).put("provider","library").put("item_id",uri.substring(uri.lastIndexOf('/')+1)).put("favorite",favorites.contains(uri)).put("media_type",uri.contains("/album/")?"album":"track").put("name","Track");
                }
                else if(command.equals("music/favorites/add_item")){writes.add(command);favorites.add(request.getJSONObject("args").getString("item"));reply=new JSONObject();}
                else if(command.equals("metadata/get_track_lyrics")){
                    lyricCalls++;String text="Lyrics "+track;
                    if(delayLyrics){delayLyrics=false;slowLyrics.countDown();releaseLyrics.await(6,TimeUnit.SECONDS);}
                    if(changeDuringLyrics){track="two";changeDuringLyrics=false;}
                    reply=noLyrics?JSONObject.NULL:new JSONArray().put(text).put(JSONObject.NULL);
                }
                else if(command.equals("players/cmd/volume_set")||command.equals("players/cmd/group_volume")){writes.add(command);volume=request.getJSONObject("args").getInt("volume_level");reply=new JSONObject();}
                else if(command.equals("players/cmd/volume_mute")||command.equals("players/cmd/group_volume_mute")){writes.add(command);muted=request.getJSONObject("args").getBoolean("muted");reply=new JSONObject();}
                else if(command.equals("players/cmd/set_members")){writes.add(command);joinedArgs=request.getJSONObject("args");joiningSource=joinedArgs.getJSONArray("player_ids_to_add").getString(0);movedToNew=joinedArgs.optString("target_player").equals("new");changedGroup=movedToNew;reply=new JSONObject();}
                else throw new AssertionError("Unexpected fixture command "+command);
            }
            byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception e){throw new RuntimeException(e);}});}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        PluginHost host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("executeCommand"))((PluginHost.CommandCallback)args[2]).onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);
            if(method.getName().equals("haCall"))((PluginHost.CommandCallback)args[1]).onResult(true,Collections.singletonMap("id","fixture-user"),null);
            return null;
        });
        IngressAccess access=new IngressAccess(host);Field cached=IngressAccess.class.getDeclaredField("cached");cached.setAccessible(true);
        cached.set(access,new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("http://127.0.0.1:"+server.getLocalPort()+"/"),"fixture-user","fixture_addon","fixture-cookie"));
        Map<String,Integer> colors=new HashMap<>();
        for(String role:new String[]{"surface","surfaceContainer","surfaceContainerHigh","surfaceContainerHighest","onSurface","onSurfaceVariant","primary","onPrimary","primaryContainer","onPrimaryContainer","outline","outlineVariant","secondaryContainer","onSecondaryContainer","error","success"})colors.put(role,0xff556677);
        theme=new KsTheme(true,1,colors,w->null,n->new android.graphics.Path());
        controller=new FeatureController(host,access,worker,busy,()->"fixture_addon",()->selectedId,()->true,new FeatureController.Ui(){
            public void panel(String title,BooleanSupplier current,Consumer<TouchPanel> build){activity.runOnUiThread(()->{renders++;panel=new TouchPanel(activity,theme,title);build.accept(panel);activity.setContentView(panel);});}
            public void popup(String title,BooleanSupplier current,Consumer<TouchPanel> build){popupRenders++;panel(title,current,build);}
            public void dismiss(){}public void message(String s){}public void refresh(){}public void play(BrowserItem i,String o,boolean s,String start){}public void open(BrowserItem i){}public void players(Runnable after){}
            public void joined(String target){joinedTarget=target;}
            public void library(){libraryVisits++;}
        });
        controller.group();pump(()->panel!=null&&find(panel,"Bedroom volume")!=null&&!busy.get());
    }
    private View find(View view,String label){if(label.equals(String.valueOf(view.getContentDescription()))||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View v=find(((ViewGroup)view).getChildAt(i),label);if(v!=null)return v;}return null;}
    private SeekBar slider(View view,String label){if(view instanceof SeekBar&&label.equals(String.valueOf(view.getContentDescription())))return (SeekBar)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SeekBar bar=slider(((ViewGroup)view).getChildAt(i),label);if(bar!=null)return bar;}return null;}
    private void pump(BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);do{shadowOf(Looper.getMainLooper()).idle();if(done.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<deadline);fail("Group operation did not finish");}
    private void slide(SeekBar bar,int value)throws Exception{bar.setProgress(value);Field listener=SeekBar.class.getDeclaredField("mOnSeekBarChangeListener");listener.setAccessible(true);((SeekBar.OnSeekBarChangeListener)listener.get(bar)).onStopTrackingTouch(bar);}
    @After public void stop()throws Exception{if(controller!=null)controller.close();if(worker!=null)worker.shutdownNow();if(server!=null)server.close();if(http!=null)http.shutdownNow();}
    @Test public void memberAndGroupReadbackStayInTheSamePanel()throws Exception{
        TouchPanel before=panel;int builds=renders;SeekBar member=slider(panel,"Bedroom volume");
        slide(member,65);pump(()->!busy.get()&&volume==65);assertSame(before,panel);assertSame(member,slider(panel,"Bedroom volume"));assertEquals(builds,renders);
        assertEquals(65,slider(panel,"Group volume").getProgress());
        View parent=(View)member.getParent();boolean inside=false;
        while(parent!=null){if("Player card: Bedroom".equals(String.valueOf(parent.getContentDescription()))){inside=true;break;}parent=parent.getParent() instanceof View?(View)parent.getParent():null;}
        assertTrue(inside);
        find(panel,"Mute Bedroom volume").performClick();pump(()->!busy.get()&&muted);assertNotNull(find(panel,"Unmute Bedroom volume"));
        find(panel,"Unmute Bedroom volume").performClick();pump(()->!busy.get()&&!muted);assertEquals(Arrays.asList("players/cmd/volume_set","players/cmd/volume_mute","players/cmd/volume_mute"),writes);assertEquals(builds,renders);
    }
    @Test public void clearConfirmedEmptyReturnsToLibraryOnce()throws Exception{
        controller.queue(100);pump(()->find(panel,"Clear queue")!=null&&!busy.get());find(panel,"Clear queue").performClick();pump(()->find(panel,"Confirm")!=null&&!busy.get());
        assertNotNull(find(panel,"Remove all items from this queue?"));find(panel,"Confirm").performClick();pump(()->libraryVisits==1);
        assertEquals(Collections.singletonList("player_queues/clear"),writes);assertTrue(cleared);assertEquals("hall",selectedId);
    }
    @Test public void queuePanelFollowsRemoteClearBackToLibraryWithoutCommands()throws Exception{
        controller.queue(100);pump(()->find(panel,"Clear queue")!=null&&!busy.get());cleared=true;tickLyrics();pump(()->libraryVisits==1);assertTrue(writes.isEmpty());assertEquals("hall",selectedId);
    }
    @Test public void unconfirmedClearDoesNotNavigateOrResubmit()throws Exception{
        ignoreClear=true;controller.queue(100);pump(()->find(panel,"Clear queue")!=null&&!busy.get());find(panel,"Clear queue").performClick();pump(()->find(panel,"Confirm")!=null&&!busy.get());find(panel,"Confirm").performClick();
        pump(()->!busy.get()&&writes.size()==1&&find(panel,"Action may already have been submitted. Check MA before retrying; no automatic retry was made.")!=null);
        assertEquals(0,libraryVisits);assertEquals(Collections.singletonList("player_queues/clear"),writes);
    }
    @Test public void groupSectionsUpdateAfterRemoteMembershipChangeWithoutRefreshButton()throws Exception{
        assertNotNull(find(panel,"In this group"));assertNull(find(panel,"Refresh group / volume"));assertNull(find(panel,"Available to add"));
        changedGroup=true;tickLyrics();pump(()->find(panel,"Available to add")!=null&&!busy.get());assertNotNull(find(panel,"In this group"));assertTrue(writes.isEmpty());assertNull(slider(panel,"Bedroom volume"));
    }
    @Test public void changedMembershipBlocksTheWriteWithoutReplacingThePanel()throws Exception{
        TouchPanel before=panel;changedGroup=true;slide(slider(panel,"Bedroom volume"),65);pump(()->!busy.get());
        assertTrue(writes.isEmpty());assertSame(before,panel);assertNotNull(find(panel,"Player/group changed. Refresh volume controls."));
    }
    @Test public void memberSliderAtZeroWhileMutedAndPositiveSelectionUnmutes()throws Exception{
        find(panel,"Mute Bedroom volume").performClick();pump(()->!busy.get()&&muted);assertEquals(0,slider(panel,"Bedroom volume").getProgress());writes.clear();
        slide(slider(panel,"Bedroom volume"),31);pump(()->!busy.get()&&!muted&&volume==31);assertEquals(31,slider(panel,"Bedroom volume").getProgress());assertEquals(Arrays.asList("players/cmd/volume_set","players/cmd/volume_mute"),writes);
    }
    @Test public void groupSliderUnmutesTheGroupRatherThanOneMember()throws Exception{
        find(panel,"Mute Group volume").performClick();pump(()->!busy.get()&&muted);assertEquals(0,slider(panel,"Group volume").getProgress());writes.clear();
        slide(slider(panel,"Group volume"),39);pump(()->!busy.get()&&!muted&&volume==39);assertEquals(Arrays.asList("players/cmd/group_volume","players/cmd/group_volume_mute"),writes);
    }
    @Test public void playerDetailsHasARealParentBackInsteadOfClose()throws Exception{
        List<String> calls=new ArrayList<>();controller.visibility("speaker",()->calls.add("connection"));pump(()->find(panel,"‹ Back")!=null&&!busy.get());
        assertNull(find(panel,"Close"));find(panel,"‹ Back").performClick();assertEquals(Collections.singletonList("connection"),calls);assertTrue(writes.isEmpty());
    }
    private void tickLyrics()throws Exception{
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
        // Queue a barrier behind the async reader, then drain posted UI work.
        final java.util.concurrent.atomic.AtomicBoolean drained=new java.util.concurrent.atomic.AtomicBoolean();
        worker.submit(()->drained.set(true));pump(drained::get);shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void lyricsFollowTrackChangesAndEmptyTransitionWithoutReplacingPanel()throws Exception{
        controller.lyrics();pump(()->panel!=null&&find(panel,"Lyrics one")!=null);TouchPanel before=panel;
        track="two";tickLyrics();pump(()->find(panel,"Lyrics two")!=null);assertSame(before,panel);assertNull(find(panel,"Lyrics one"));
        gap=true;tickLyrics();pump(()->find(panel,"No music track available. Lyrics will follow the next track.")!=null);assertNull(find(panel,"Lyrics two"));
        gap=false;track="three";tickLyrics();pump(()->find(panel,"Lyrics three")!=null);assertSame(before,panel);
    }
    @Test public void missingLyricsIsNotReportedAsAPermissionFailure()throws Exception{
        noLyrics=true;controller.lyrics();pump(()->panel!=null&&find(panel,"Lyrics unavailable from your MA providers.")!=null);
        assertTrue(lyricCalls>0);assertNull(find(panel,"Music Assistant could not complete the read. Check permissions and connection, then retry explicitly."));
    }
    @Test public void staleLyricsAreDiscardedWhenTrackChangesDuringLookup()throws Exception{
        changeDuringLyrics=true;controller.lyrics();pump(()->panel!=null&&track.equals("two"));
        assertNull(find(panel,"Lyrics one"));tickLyrics();pump(()->find(panel,"Lyrics two")!=null);
    }
    @Test public void lyricsRecoverForSameTrackAfterAQueueReadFailure()throws Exception{
        controller.lyrics();pump(()->panel!=null&&find(panel,"Lyrics one")!=null);
        queueUnreadable=true;tickLyrics();pump(()->find(panel,"Could not read the current track. Lyrics will refresh when the queue is readable.")!=null);
        assertNull(find(panel,"Lyrics one"));queueUnreadable=false;tickLyrics();pump(()->find(panel,"Lyrics one")!=null);
    }
    @Test public void externalVolumeReadbackUpdatesWithoutReplacingPanelOrSendingCommands()throws Exception{
        TouchPanel before=panel;SeekBar slider=slider(panel,"Bedroom volume");int builds=renders;
        volume=73;muted=true;tickLyrics();pump(()->slider.getProgress()==0&&find(panel,"Unmute Bedroom volume")!=null);
        assertSame(before,panel);assertEquals(builds,renders);assertTrue(writes.isEmpty());assertNotNull(find(panel,"Unmute Bedroom volume"));
        muted=false;tickLyrics();pump(()->slider.getProgress()==73);assertTrue(writes.isEmpty());assertSame(before,panel);
    }
    @Test public void queueTracksRemoteSkipAndUsesDirectIcons()throws Exception{
        controller.queue(100);pump(()->find(panel,"Track one")!=null&&find(panel,"Clear queue")!=null&&!busy.get());TouchPanel before=panel;
        assertNotNull(find(panel,"Save queue as playlist"));assertNull(find(panel,"Queue options"));assertNull(find(panel,"Options: Track one"));
        assertFalse(((View)find(panel,"Track one").getParent().getParent()).isLongClickable());
        assertNotNull(find(panel,"Add to playlist: Track one"));
        track="two";tickLyrics();pump(()->find(panel,"Remove from queue: Track two")!=null&&!find(panel,"Remove from queue: Track two").isEnabled());
        assertSame(before,panel);assertTrue(writes.isEmpty());
    }
    @Test public void directAlbumFavouriteDoesNotFavouriteItsTracksOrOpenAPanel()throws Exception{
        int builds=renders;final AtomicBoolean done=new AtomicBoolean();final List<String> errors=new ArrayList<>();
        BrowserItem album=new BrowserItem(new JSONObject().put("uri","library://album/one").put("media_type","album"));
        controller.favorite(album,state->done.set(state),errors::add);pump(()->done.get()&&!busy.get());
        assertEquals(Collections.singleton("library://album/one"),favorites);assertEquals(Collections.singletonList("music/favorites/add_item"),writes);assertEquals(builds,renders);assertTrue(errors.isEmpty());
    }
    @Test public void localJoinUsesTheSelectedLocalPlayer()throws Exception{
        changedGroup=true;selectedId="bed";controller.ownReceiver(session->"bed");controller.joinLocalGroup();
        pump(()->find(panel,"Join this leader's playback")!=null&&!busy.get());
        ((View)find(panel,"Join this leader's playback").getParent().getParent()).performClick();
        pump(()->joinedArgs!=null&&!busy.get());assertEquals("hall",joinedArgs.getString("target_player"));
        assertEquals("bed",joinedArgs.getJSONArray("player_ids_to_add").getString(0));assertEquals(Collections.singletonList("players/cmd/set_members"),writes);
    }
    @Test public void slowLyricsLookupDoesNotBlockNoticingANewTrack()throws Exception{
        delayLyrics=true;controller.lyrics();pump(()->slowLyrics.getCount()==0);track="two";tickLyrics();
        Field active=FeatureController.class.getDeclaredField("activeLyrics");active.setAccessible(true);Object watch=active.get(controller);Field key=watch.getClass().getDeclaredField("key");key.setAccessible(true);
        assertTrue(((String)key.get(watch)).endsWith("library://track/two"));
        releaseLyrics.countDown();tickLyrics();pump(()->find(panel,"Lyrics two")!=null);assertNull(find(panel,"Lyrics one"));assertTrue(writes.isEmpty());
    }
    @Test public void movingLocalReceiverRequiresConfirmationAndOneMembershipWrite()throws Exception{
        extraLeader=true;selectedId="bed";controller.ownReceiver(session->"bed");controller.joinLocalGroup();pump(()->find(panel,"Join this leader's playback")!=null&&!busy.get());
        ((View)find(panel,"Join this leader's playback").getParent().getParent()).performClick();pump(()->find(panel,"Confirm")!=null);
        assertTrue(writes.isEmpty());find(panel,"Confirm").performClick();pump(()->joinedArgs!=null&&!busy.get());
        assertEquals("new",joinedArgs.getString("target_player"));assertEquals("bed",joinedArgs.getJSONArray("player_ids_to_add").getString(0));assertEquals(Collections.singletonList("players/cmd/set_members"),writes);
    }
    @Test public void controllerOnlyJoinUsesSelectedExternalPlayerAndFollowsVerifiedDestination()throws Exception{
        extraLeader=true;controller.controllerOnly(()->true);controller.ownReceiver(session->"bed");controller.joinLocalGroup();
        pump(()->find(panel,"Join this leader's playback")!=null&&!busy.get());
        ((View)find(panel,"Join this leader's playback").getParent().getParent()).performClick();pump(()->find(panel,"Confirm")!=null);
        assertTrue(writes.isEmpty());find(panel,"Confirm").performClick();pump(()->joinedTarget.equals("new")&&!busy.get());
        assertEquals("hall",joiningSource);assertEquals(Collections.singletonList("players/cmd/set_members"),writes);
    }
    @Test public void playerSettingsExposeOnlySelectedPlayersSupportedControls()throws Exception{
        controller.playerSettings();pump(()->find(panel,"Hallway volume")!=null&&!busy.get());assertNull(find(panel,"Plugin preferences"));
        assertNotNull(find(panel,"Hallway volume"));assertNull(find(panel,"Bedroom volume"));assertNull(find(panel,"Receiver name · blank follows Kiosk"));assertNull(find(panel,"Enable this kiosk's receiver"));assertTrue(writes.isEmpty());
    }
    @Test public void createPlaylistUsesPopupForBothLoadingAndFormWithoutCreatingOnOpen()throws Exception{
        controller.createPlaylist(new JSONArray());pump(()->find(panel,"Save to: Music Assistant (local) ▾")!=null&&!busy.get());
        assertTrue(popupRenders>=2);assertNotNull(find(panel,"Create playlist"));assertTrue(writes.isEmpty());
    }
    @Test public void groupBackFromPlayerSettingsReturnsToSettingsNotMusic()throws Exception{
        controller.playerSettings();pump(()->find(panel,"Group / volume")!=null&&!busy.get());
        find(panel,"Group / volume").performClick();pump(()->find(panel,"Bedroom volume")!=null&&!busy.get());
        find(panel,"‹ Back").performClick();pump(()->find(panel,"Hallway volume")!=null&&!busy.get());
        assertNotNull(find(panel,"Hallway volume"));assertNull(find(panel,"Bedroom volume"));assertTrue(writes.isEmpty());
    }
    @Test public void duckingSliderHasOnlyOneToTenPercentAndDoesNotWriteMusicVolume()throws Exception{
        final int[] staged={10};TouchPanel preferences=new TouchPanel(activity,theme,"Preferences");
        preferences.boundedSlider("Ducking",1,10,10,value->staged[0]=value);
        SeekBar bar=slider(preferences,"Ducking");assertEquals(9,bar.getMax());assertEquals(9,bar.getProgress());
        slide(bar,0);assertEquals(1,staged[0]);slide(bar,9);assertEquals(10,staged[0]);assertTrue(writes.isEmpty());
    }
}
