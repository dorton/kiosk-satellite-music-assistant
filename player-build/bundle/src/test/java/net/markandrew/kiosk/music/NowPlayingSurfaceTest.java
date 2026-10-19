package net.markandrew.kiosk.music;

import android.app.Activity;
import android.os.Looper;
import android.graphics.Path;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import me.jxl.kiosk.plugins.KsTheme;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual Android layout and touch dispatch, with no HA/MA or device access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w960dp-h480dp-land-mdpi")
public class NowPlayingSurfaceTest {
    private Activity activity;
    private NowPlayingView playback;
    private NowPlayingSurface surface;
    private final List<String> actions = new ArrayList<>();
    private final List<String> iconNames=new ArrayList<>();
    private KsTheme theme() {
        Map<String,Integer> colors = new HashMap<>();
        for (String role : new String[]{"surface", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "onSurface", "onSurfaceVariant", "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "outline", "outlineVariant", "secondaryContainer", "onSecondaryContainer", "error", "success"}) colors.put(role, 0xff556677);
        return new KsTheme(true,activity.getResources().getDisplayMetrics().density,colors,weight -> null,name -> {iconNames.add(name);return new Path();});
    }
    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        playback = new NowPlayingView(activity, theme(), new NowPlayingView.Actions() {
            public void close() { actions.add("library"); }
            public void control(String command,int value) { actions.add(command); }
            public void navigate(String destination) { actions.add(destination); }
        });
        JSONObject p = new JSONObject().put("player_id","speaker").put("name","Bedroom 1").put("playback_state","playing")
            .put("volume_control","native").put("mute_control","native").put("supported_features",new JSONArray(new String[]{"pause","seek","next_previous"}));
        JSONObject q = new JSONObject().put("queue_id","speaker").put("items",3)
            .put("current_item",new JSONObject().put("queue_item_id","item").put("media_item",new JSONObject().put("uri","library://track/1").put("media_type","track").put("duration",200)));
        playback.render(new NowPlayingData(p,q,false));
        surface = new NowPlayingSurface(activity, theme(),playback);
        activity.setContentView(surface); shadowOf(Looper.getMainLooper()).idle(); layout();
    }
    private void layout() {
        layoutAt(960,480);
    }
    private void layoutAt(int width,int maximumHeight) {
        View container=activity.findViewById(android.R.id.content);
        int height=container.getHeight()>0?Math.min(maximumHeight,container.getHeight()):maximumHeight;
        surface.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        surface.layout(0,0,width,height);
    }
    private View find(View view,String label) {
        if (label.equals(String.valueOf(view.getContentDescription())) || view instanceof TextView && label.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View match = find(((ViewGroup)view).getChildAt(i),label); if(match!=null)return match;
        }
        return null;
    }
    private SeekBar findSlider(View view,String label){
        if(view instanceof SeekBar&&label.equals(String.valueOf(view.getContentDescription())))return (SeekBar)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SeekBar bar=findSlider(((ViewGroup)view).getChildAt(i),label);if(bar!=null)return bar;}return null;
    }
    private void tap(View target) {
        assertNotNull(target); assertTrue(target.isEnabled());
        touch(target);
    }
    private void touch(View target) {
        int[] xy = new int[2], origin = new int[2]; target.getLocationOnScreen(xy); surface.getLocationOnScreen(origin);
        float x=xy[0]-origin[0]+target.getWidth()/2f, y=xy[1]-origin[1]+target.getHeight()/2f;
        assertTrue(x>=0 && x<960 && y>=0 && y<480);
        MotionEvent down=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(1,2,MotionEvent.ACTION_UP,x,y,0);
        surface.dispatchTouchEvent(down);surface.dispatchTouchEvent(up);down.recycle();up.recycle();
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void navigationButtonsReceiveActualTouches() {
        for(String label:new String[]{"Close","Library","Player","Queue","Lyrics","Transfer","Group"}) tap(find(playback,label));
        assertEquals(Arrays.asList("home","library","players","queue","lyrics","transfer","group"),actions);
    }
    @Test public void transportButtonsReceiveActualTouches() {
        for(String label:new String[]{"Shuffle off","Previous","Pause","Next","Repeat off","Mute","Add favourite"}) tap(find(playback,label));
        assertEquals(Arrays.asList("shuffle","previous","pause","next","repeat","mute","favorite"),actions);
    }
    private void assertVolumeGap(View root,String label,String muteLabel){
        SeekBar bar=findSlider(root,label);View mute=find(root,muteLabel);assertNotNull(bar);assertNotNull(mute);
        int[] b=new int[2],m=new int[2];bar.getLocationOnScreen(b);mute.getLocationOnScreen(m);
        assertTrue("Separate slider and mute touch areas",b[0]>=m[0]+mute.getWidth()+8);
        assertTrue("Thumb left fits",bar.getPaddingLeft()>=bar.getThumbOffset()+2);
        assertTrue("Thumb right fits",bar.getPaddingRight()>=bar.getThumb().getIntrinsicWidth()-bar.getThumbOffset()+2);
    }
    @Test public void volumeThumbAndMuteRemainSeparateAcrossSizes()throws Exception{
        JSONObject p=new JSONObject().put("player_id","speaker").put("volume_control","native").put("mute_control","native").put("volume_muted",true).put("volume_level",45);
        playback.render(new NowPlayingData(p,new JSONObject().put("queue_id","speaker")));
        for(int[] size:new int[][]{{360,480},{788,394},{960,480},{1280,800}}){layoutAt(size[0],size[1]);assertVolumeGap(playback,"Player volume","Unmute");}
        assertEquals(0xffd32f2f,NativeStyle.color("muted"));assertEquals(NativeStyle.color("muted"),((Button)find(playback,"Unmute")).getCurrentTextColor());
        p.put("volume_muted",false).put("volume_level",0);playback.render(new NowPlayingData(p,new JSONObject().put("queue_id","speaker")));assertEquals(NativeStyle.color("muted"),((Button)find(playback,"Mute")).getCurrentTextColor());
    }
    @Test public void memberVolumeThumbGapAndExplicitMuteColor(){
        TouchPanel.VolumeControl[] control={null};TouchPanel panel=surface.panel("Group",false,p->control[0]=p.volume("Bedroom volume",45,true,true,true,n->{},()->{}),surface::clearPanel);layout();
        assertVolumeGap(panel,"Bedroom volume","Unmute Bedroom volume");assertEquals(NativeStyle.color("muted"),((Button)find(panel,"Unmute Bedroom volume")).getCurrentTextColor());
        control[0].update(0,false);assertEquals(NativeStyle.color("muted"),((Button)find(panel,"Mute Bedroom volume")).getCurrentTextColor());assertFalse(control[0].muted());
        control[0].update(20,false);assertEquals(NativeStyle.color("onSurface"),((Button)find(panel,"Mute Bedroom volume")).getCurrentTextColor());
    }
    @Test public void eqPresetUsesMiddleHeaderSpaceWithoutAddingAnotherRow(){
        TouchPanel panel=surface.panel("Equalizer",true,p->{p.eqPresentation();p.eqHeader(true,true,v->{},()->{});p.eqChoice("My long custom preset name",new String[]{"Manual","My long custom preset name"},v->{});},surface::clearPanel);layout();
        View preset=find(panel,"Preset selector"),advanced=find(panel,"Switch to Basic");assertNotNull(preset);assertNotNull(advanced);
        int[] p=new int[2],a=new int[2];preset.getLocationOnScreen(p);advanced.getLocationOnScreen(a);assertEquals(p[1],a[1]);assertTrue(p[0]+preset.getWidth()<=a[0]);assertTrue("Preset gets middle width",preset.getWidth()>200);
    }
    @Test public void drawerIsAttachedSizedAndTouchableAbovePlayback() {
        TouchPanel panel=surface.panel("Queue",false,p->p.add("Panel action",()->actions.add("panel")),surface::clearPanel);
        layout();
        assertEquals(2,surface.getChildCount());assertSame(playback,surface.getChildAt(0));
        assertTrue(panel.getWidth()>250 && panel.getHeight()>400);
        tap(find(panel,"Panel action"));assertEquals(Arrays.asList("panel"),actions);
        surface.clearPanel();assertEquals(1,surface.getChildCount());
        tap(find(playback,"Pause"));assertEquals(Arrays.asList("panel","pause"),actions);
    }
    @Test public void centeredLyricsReplacesDrawerWithoutReplacingPlayback() {
        surface.panel("Queue",false,p->p.note("Queue"),surface::clearPanel);layout();
        TouchPanel lyrics=surface.panel("Lyrics",true,p->p.note("Lyrics"),surface::clearPanel);layout();
        assertEquals(2,surface.getChildCount());assertSame(playback,surface.getChildAt(0));
        assertEquals(560,lyrics.getWidth());
        assertEquals(200,lyrics.getLeft());
    }
    @Test public void graphicEqIsCenteredAbovePlaybackAndItsApplyIsVisible()throws Exception{
        EqDraft draft=new EqDraft(EqTest.config());
        TouchPanel eq=surface.panel("Equaliser · Bedroom 1",true,p->{try{new EqEditor(p,draft,true,new EqPresetStore(activity),v->{},()->{},()->{},()->actions.add("eq-apply"),surface::clearPanel);}catch(Exception e){throw new RuntimeException(e);}},surface::clearPanel);
        layout();assertEquals(900,eq.getWidth());assertEquals(30,eq.getLeft());assertSame(playback,surface.getChildAt(0));
        View apply=find(eq,"Apply");assertNotNull(apply);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(apply.getGlobalVisibleRect(visible));assertEquals(apply.getHeight(),visible.height());
        String before=draft.config.toString();tap(apply);assertEquals(Arrays.asList("eq-apply"),actions);assertEquals(before,draft.config.toString());assertNull(new EqGraphic(draft).bank());assertEquals(2,surface.getChildCount());
    }
    @Test @Config(sdk=30,qualifiers="w1280dp-h800dp-land-mdpi") public void eqFadersFitWithoutScrollingAcrossScreenSizes()throws Exception{
        EqDraft draft=new EqDraft(EqTest.config());
        TouchPanel eq=surface.panel("Equaliser",true,p->{try{new EqEditor(p,draft,true,new EqPresetStore(activity),v->{},()->{},()->{},()->{},surface::clearPanel);}catch(Exception e){throw new RuntimeException(e);}},surface::clearPanel);
        View slider=find(eq,"32 · 0 dB");assertNotNull(slider);String before=draft.config.toString();
        for(int[] screen:new int[][]{{960,480},{788,394},{600,480},{360,640},{1280,800}}){
            layoutAt(screen[0],screen[1]);assertFalse(hasScroll(eq));
            for(int i=0;i<((GraphicEqView)slider.getParent()).getChildCount();i++){
                View f=((GraphicEqView)slider.getParent()).getChildAt(i);assertTrue(f.getWidth()>=48);assertTrue(f.getHeight()>=70);fullyVisible(f);
            }
            View apply=find(eq,"Apply"),close=find(eq,"Close");fullyVisible(apply);fullyVisible(close);assertEquals(48,apply.getHeight());assertTrue(apply.getWidth()<eq.getWidth()/2);
            assertNull(find(eq,"Details / safety"));assertNull(find(eq,"Apply may restart playback · positive boosts can clip"));
        }
        assertEquals(before,draft.config.toString());
    }
    private boolean hasScroll(View v){if(v instanceof ScrollView||v instanceof HorizontalScrollView)return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(hasScroll(((ViewGroup)v).getChildAt(i)))return true;return false;}
    private void fullyVisible(View v){android.graphics.Rect rect=new android.graphics.Rect();String detail="surface "+surface.getWidth()+"×"+surface.getHeight()+" · "+v.getContentDescription();assertTrue(detail,v.getGlobalVisibleRect(rect));assertEquals(detail,v.getWidth(),rect.width());assertEquals(detail+layoutTree(playback),v.getHeight(),rect.height());}
    private String layoutTree(View v){String text="\n"+v.getClass().getSimpleName()+" "+v.getWidth()+"x"+v.getHeight()+" y="+v.getTop()+" "+(v instanceof TextView?((TextView)v).getText():v.getContentDescription());if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)text+=layoutTree(((ViewGroup)v).getChildAt(i));return text;}
    @Test public void realFaderDragIsNotStolenByTheOuterScrollPanel()throws Exception{
        EqDraft draft=new EqDraft(EqTest.config());
        TouchPanel eq=surface.panel("Equaliser",true,p->{try{new EqEditor(p,draft,true,new EqPresetStore(activity),v->{},()->{},()->{},()->{},surface::clearPanel);}catch(Exception e){throw new RuntimeException(e);}},surface::clearPanel);
        layout();View slider=find(eq,"32 · 0 dB");int[] xy=new int[2],origin=new int[2];slider.getLocationOnScreen(xy);surface.getLocationOnScreen(origin);
        float x=xy[0]-origin[0]+slider.getWidth()/2f,y=xy[1]-origin[1]+(24+slider.getHeight()-32)/2f;
        for(int i=0;i<4;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==3?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;float dy=i==0?0:i==1?12:26;MotionEvent e=MotionEvent.obtain(10,10+i,action,x,y-dy,0);surface.dispatchTouchEvent(e);e.recycle();}
        assertNotNull(new EqGraphic(draft).bank());assertEquals(Math.round(24*26.0/(slider.getHeight()-56)),new EqGraphic(draft).gains()[0],0);assertTrue(actions.isEmpty());
    }
    @Test public void basicEqCancelDismissesWithoutChangingDsp()throws Exception{
        EqDraft draft=new EqDraft(EqTest.config());
        TouchPanel eq=surface.panel("Equaliser",true,p->{try{new EqEditor(p,draft,false,new EqPresetStore(activity),v->{},()->{},()->{},()->actions.add("unexpected-apply"),surface::clearPanel);}catch(Exception e){throw new RuntimeException(e);}},surface::clearPanel);
        layout();tap(find(eq,"Close"));assertEquals(1,surface.getChildCount());assertFalse(draft.changed());assertTrue(actions.isEmpty());
    }
    @Test public void hiddenEqLeavesNoSlotAndOtherToolsRedistribute(){
        layout();View eq=find(playback,"Equaliser"),queue=find(playback,"Queue");assertEquals(View.GONE,eq.getVisibility());int without=queue.getWidth();
        playback.equaliserAvailable(true);layout();assertEquals(View.VISIBLE,eq.getVisibility());int with=queue.getWidth();assertTrue(without>with);assertEquals(with,eq.getWidth(),1);
        playback.equaliserAvailable(false);layout();assertEquals(without,queue.getWidth());int total=0;for(String label:new String[]{"Queue","Lyrics","Transfer","Group"}){View tool=find(playback,label);assertEquals(without,tool.getWidth(),1);LinearLayout.LayoutParams slot=(LinearLayout.LayoutParams)tool.getLayoutParams();total+=tool.getWidth()+slot.leftMargin+slot.rightMargin;}
        // Weighted Android layouts distribute indivisible pixels between children.
        // The stronger invariant is that the four tools occupy the whole row.
        assertEquals(((View)queue.getParent()).getWidth(),total);
    }
    @Test public void pendingControlDisablesTransportUntilResult() {
        playback.busy(true);assertFalse(find(playback,"Pause").isEnabled());
        playback.busy(false);tap(find(playback,"Pause"));assertEquals(Arrays.asList("pause"),actions);
    }
    @Test public void emptyIdleQueueDisablesPlayButLoadedExternalPauseCanResume()throws Exception{
        JSONObject p=new JSONObject().put("playback_state","idle"),q=new JSONObject().put("queue_id","speaker").put("items",0);
        playback.render(new NowPlayingData(p,q));layout();assertFalse(find(playback,"Play").isEnabled());touch(find(playback,"Play"));assertTrue(actions.isEmpty());
        p.put("playback_state","paused").put("current_media",new JSONObject().put("uri","external://stream"));playback.render(new NowPlayingData(p,q));assertTrue(find(playback,"Play").isEnabled());
    }
    @Test public void mainNavigationHasResponsiveTopBreathingRoomAndCloseNotHome(){
        assertNull(find(playback,"Home"));for(int[] size:new int[][]{{960,480},{788,394},{360,480}}){layoutAt(size[0],size[1]);View close=find(playback,"Close");int[] xy=new int[2],origin=new int[2];close.getLocationOnScreen(xy);surface.getLocationOnScreen(origin);assertTrue(xy[1]-origin[1]>=8);assertEquals(48,close.getHeight());assertTrue(close.getWidth()>=48);for(String label:new String[]{"Close","Library","Player"})fullyVisible(find(playback,label));}
        find(playback,"Close").performClick();assertEquals(Collections.singletonList("home"),actions);
    }
    @Test public void zeroVolumeUsesOffGlyphWithoutChangingMuteControlSemantics()throws Exception{
        JSONObject p=new JSONObject().put("player_id","speaker").put("volume_control","native").put("mute_control","native").put("volume_level",0).put("volume_muted",false);
        iconNames.clear();playback.render(new NowPlayingData(p,new JSONObject()));assertTrue(iconNames.contains("mdi:volume-off"));assertFalse(iconNames.contains("mdi:volume-high"));assertNotNull(find(playback,"Mute"));assertEquals(View.GONE,find(playback,"Group volume").getVisibility());
        iconNames.clear();p.put("volume_level",40);playback.render(new NowPlayingData(p,new JSONObject()));assertTrue(iconNames.contains("mdi:volume-high"));
        iconNames.clear();p.put("volume_muted",true);playback.render(new NowPlayingData(p,new JSONObject()));assertTrue(iconNames.contains("mdi:volume-off"));assertNotNull(find(playback,"Unmute"));assertTrue(actions.isEmpty());
        p.put("type","group").put("group_volume",20);playback.render(new NowPlayingData(p,new JSONObject()));assertEquals(View.VISIBLE,find(playback,"Group volume").getVisibility());
    }
    @Test public void overlaysCoverDashboardWithoutGapsAndStayBelowHostMenu(){me.jxl.kiosk.plugins.OverlaySpec spec=MusicOverlay.screen();assertEquals(0,spec.insetLeft());assertEquals(0,spec.insetRight());assertEquals(0,spec.insetTop());assertEquals(0,spec.insetBottom());assertTrue(spec.touchable());assertFalse(spec.onTop());assertTrue(spec.closeOnBack());assertFalse(spec.closeOnOutsideTouch());}
    @Test public void featureButtonsHaveEqualWeightsAndEightDpGaps(){layout();View queue=find(playback,"Queue"),lyrics=find(playback,"Lyrics");assertEquals(8,lyrics.getLeft()-queue.getRight());assertEquals(1,((LinearLayout.LayoutParams)queue.getLayoutParams()).weight,0);assertEquals(8,((LinearLayout.LayoutParams)((View)queue.getParent()).getLayoutParams()).topMargin);}
    @Test public void displayClockTicksWithoutNetworkAndRebasesOnPauseSeekAndTrack()throws Exception{
        NowPlayingController c=new NowPlayingController(null,null,"","speaker",()->true,()->"speaker");
        java.lang.reflect.Field field=NowPlayingController.class.getDeclaredField("view");field.setAccessible(true);field.set(c,playback);
        java.lang.reflect.Method present=NowPlayingController.class.getDeclaredMethod("present",NowPlayingData.class);present.setAccessible(true);
        java.lang.reflect.Field tick=NowPlayingController.class.getDeclaredField("clock");tick.setAccessible(true);
        JSONObject p=new JSONObject().put("playback_state","playing"),q=new JSONObject().put("queue_id","speaker").put("elapsed_time",10).put("current_item",new JSONObject().put("queue_item_id","one").put("media_item",new JSONObject().put("media_type","track").put("duration",200)));
        try{
            present.invoke(c,new NowPlayingData(p,q));((Runnable)tick.get(c)).run();assertNotNull(find(playback,"0:10 / 3:20"));
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));assertNotNull(find(playback,"0:11 / 3:20"));
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));assertNotNull(find(playback,"0:12 / 3:20"));
            p.put("playback_state","paused");q.put("elapsed_time",12);present.invoke(c,new NowPlayingData(p,q));shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertNotNull(find(playback,"0:12 / 3:20"));
            p.put("playback_state","playing");q.put("elapsed_time",80);present.invoke(c,new NowPlayingData(p,q));assertNotNull(find(playback,"1:20 / 3:20"));
            q.getJSONObject("current_item").put("queue_item_id","two");q.put("elapsed_time",0);present.invoke(c,new NowPlayingData(p,q));assertNotNull(find(playback,"0:00 / 3:20"));
        }finally{c.close();}
    }
    @Test @Config(qualifiers="w1280dp-h800dp-land-mdpi") public void timerRemainsOutsideScrollableMetadataForLongGroupedTitles()throws Exception{
        JSONObject p=new JSONObject().put("player_id","speaker").put("name","Kitchen group").put("playback_state","playing").put("group_members",new JSONArray(Arrays.asList("speaker","hallway"))).put("volume_control","native").put("mute_control","native").put("active_source","speaker");
        JSONObject media=new JSONObject().put("uri","library://track/1").put("media_type","track").put("duration",7506).put("name","A very long track title which wraps onto several lines on a small grouped player display").put("artists",new JSONArray().put(new JSONObject().put("name","Meat Loaf")));
        JSONObject q=new JSONObject().put("queue_id","speaker").put("active",true).put("items",3).put("elapsed_time",61).put("current_item",new JSONObject().put("queue_item_id","one").put("media_item",media));
        playback.render(new NowPlayingData(p,q).withSelection("hallway","Hallway","speaker","Kitchen"));playback.equaliserAvailable(true);
        for(int[] size:new int[][]{{788,394},{800,480},{960,480},{360,480},{1280,800}}){
            layoutAt(size[0],size[1]);View timer=find(playback,"1:01 / 125:06"),slider=findSlider(playback,"Track position");assertNotNull(timer);assertNotNull(slider);fullyVisible(timer);fullyVisible(slider);fullyVisible(find(playback,"Group volume"));fullyVisible(find(playback,"Equaliser"));
            View artist=find(playback,"Meat Loaf");assertNotNull(artist);fullyVisible(artist);assertSame(timer.getParent(),artist.getParent());
            assertSame(timer.getParent(),slider.getParent());int[] t=new int[2],s=new int[2];timer.getLocationOnScreen(t);slider.getLocationOnScreen(s);assertTrue(t[1]+timer.getHeight()<=s[1]);
            assertEquals(0,((View)timer.getParent()).getScrollY());
        }
    }
    @Test public void timerStillFitsWithGroupVolumeAndFeedback()throws Exception{
        JSONObject p=new JSONObject().put("player_id","speaker").put("type","group");JSONObject q=new JSONObject().put("elapsed_time",10).put("current_item",new JSONObject().put("media_item",new JSONObject().put("media_type","track").put("duration",200)));
        playback.render(new NowPlayingData(p,q));playback.message("Call restoration verified. This message must not hide playback controls.");layoutAt(788,394);fullyVisible(find(playback,"0:10 / 3:20"));fullyVisible(find(playback,"Queue"));
    }
    @Test public void boundaryDelayRecoversAfterLateCallbackInsteadOfDrifting(){assertEquals(1000,NowPlayingController.clockDelay(10000,10000));assertEquals(750,NowPlayingController.clockDelay(10250,10000));assertEquals(920,NowPlayingController.clockDelay(11080,10000));assertEquals(1,NowPlayingController.clockDelay(14999,10000));assertEquals(1000,NowPlayingController.clockDelay(15000,10000));}
    @Test public void clockRebaseSchedulesNextBoundaryRatherThanOldCallbackPhase()throws Exception{
        NowPlayingController c=new NowPlayingController(null,null,"","speaker",()->true,()->"speaker");java.lang.reflect.Field f=NowPlayingController.class.getDeclaredField("view");f.setAccessible(true);f.set(c,playback);java.lang.reflect.Method present=NowPlayingController.class.getDeclaredMethod("present",NowPlayingData.class);present.setAccessible(true);
        JSONObject p=new JSONObject().put("playback_state","playing"),q=new JSONObject().put("queue_id","speaker").put("elapsed_time",10).put("current_item",new JSONObject().put("queue_item_id","one").put("media_item",new JSONObject().put("media_type","track").put("duration",200)));
        try{
            present.invoke(c,new NowPlayingData(p,q));shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));q.put("elapsed_time",80);present.invoke(c,new NowPlayingData(p,q));
            java.lang.reflect.Field anchor=NowPlayingController.class.getDeclaredField("positionAt");anchor.setAccessible(true);
            long remaining=anchor.getLong(c)+1000-android.os.SystemClock.elapsedRealtime();assertTrue("Render left time before next boundary",remaining>1);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(remaining-1));assertEquals("before boundary", "1:20 / 3:20",timerText());
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));assertEquals("at boundary · now="+android.os.SystemClock.elapsedRealtime(),"1:21 / 3:20",timerText());
        }finally{c.close();}
    }
    private String timerText()throws Exception{java.lang.reflect.Field f=NowPlayingView.class.getDeclaredField("time");f.setAccessible(true);return ((TextView)f.get(playback)).getText().toString();}
    @Test public void compactRowsHaveSeparatePlaybackAndOptionsTargets() {
        TouchPanel panel=surface.panel("Queue",false,p->{
            p.tool("refresh","Refresh queue",false,()->actions.add("refresh"));
            p.section("Now playing");
            p.row("A track","Artist · 3:20","▶",true,true,()->actions.add("track"),()->actions.add("options"));
        },surface::clearPanel);layout();
        tap(find(panel,"A track"));tap(find(panel,"Options: A track"));tap(find(panel,"Refresh queue"));
        assertEquals(Arrays.asList("track","options","refresh"),actions);
        tap(find(panel,"Close panel"));assertEquals(1,surface.getChildCount());
    }
    @Test public void headerStaysVisibleWhileCurrentQueueRowIsFocused() {
        TouchPanel panel=surface.panel("Queue",false,p->{
            for(int i=0;i<30;i++)p.row("Track "+i,"Artist",""+i,i==20,true,()->{},null);
            p.focusCurrent();
        },surface::clearPanel);layout();shadowOf(Looper.getMainLooper()).idle();layout();
        ScrollView scroll=null;for(int i=0;i<panel.getChildCount();i++)if(panel.getChildAt(i)instanceof ScrollView)scroll=(ScrollView)panel.getChildAt(i);
        assertNotNull(scroll);assertTrue(scroll.getScrollY()>0);
        View close=find(panel,"Close panel");int top=close.getTop();scroll.scrollTo(0,100000);layout();assertEquals(top,close.getTop());
        tap(close);assertEquals(1,surface.getChildCount());
    }
    @Test public void toolbarButtonsAreCompactAndIconsStaySquare() {
        for (boolean centered : new boolean[]{false,true}) {
            TouchPanel panel=surface.panel("Icons",centered,p->{
                p.tool("refresh","Refresh",false,()->{});
                p.tool("shuffle","Shuffle",true,()->{});
            },surface::clearPanel);layout();
            for(String label:new String[]{"Refresh","Shuffle"}) {
                View button=find(panel,label);
                assertEquals(44,button.getWidth());assertEquals(44,button.getHeight());
                android.graphics.drawable.LayerDrawable layer=(android.graphics.drawable.LayerDrawable)button.getForeground();
                layer.setBounds(0,0,button.getWidth(),button.getHeight());
                android.graphics.Rect bounds=layer.getDrawable(0).getBounds();
                assertEquals(22,bounds.width());assertEquals(22,bounds.height());
                assertEquals(button.getWidth()/2,bounds.centerX(),1);
                assertEquals(button.getHeight()/2,bounds.centerY(),1);
            }
        }
    }
    private void assertOverviewBackground(View view) {
        android.graphics.drawable.GradientDrawable bg=(android.graphics.drawable.GradientDrawable)view.getBackground();
        assertArrayEquals(new int[]{0xff333539,0xff121315},bg.getColors());
        assertEquals(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,bg.getOrientation());
    }
    @Test public void overviewGradientCoversPlaybackBrowserSetupAndActionMenus() throws Exception {
        assertOverviewBackground(playback);
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(
            BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(proxy,method,args)->null);
        BrowserView browser=new BrowserView(activity,theme(),actions);assertOverviewBackground(browser);
        browser.style(theme());assertOverviewBackground(browser);
        TouchPanel setup=new TouchPanel(activity,theme(),"Setup");assertOverviewBackground(setup);
        ItemActionsView menu=new ItemActionsView(activity,theme(),new BrowserItem(new JSONObject().put("name","Track")),actions,()->{});
        assertOverviewBackground(menu);
        TouchPanel drawer=surface.panel("Players",false,p->{},surface::clearPanel);assertOverviewBackground(drawer);
        surface.panel("Lyrics",true,p->{},surface::clearPanel);
        // The rounded outer panel and its content both use the same gradient.
        android.view.ViewGroup scrim=(android.view.ViewGroup)surface.getChildAt(1);
        assertOverviewBackground(scrim.getChildAt(0));
    }
    @Test public void overviewButtonsAndSlidersDoNotInheritUnrelatedHostColors() {
        Button button=new Button(activity);NativeStyle.button(button,theme(),false);
        android.graphics.drawable.RippleDrawable ripple=(android.graphics.drawable.RippleDrawable)button.getBackground();
        assertEquals(0xff2a2b2f,((android.graphics.drawable.GradientDrawable)ripple.getDrawable(0)).getColor().getDefaultColor());
        assertEquals(0xffb6babf,button.getCurrentTextColor());
        NativeStyle.button(button,theme(),true);
        ripple=(android.graphics.drawable.RippleDrawable)button.getBackground();
        assertEquals(0xff488284,((android.graphics.drawable.GradientDrawable)ripple.getDrawable(0)).getColor().getDefaultColor());
        assertEquals(0xffffffff,button.getCurrentTextColor());
        SeekBar slider=new SeekBar(activity);NativeStyle.slider(slider,theme());
        assertEquals(0xff488284,slider.getThumbTintList().getDefaultColor());
    }
    @Test public void originalNavigationIconsSitAboveLabelsAndPlayersHasNoName() {
        assertNotNull(find(playback,"Player"));assertNull(find(playback,"Players"));
        for(String label:new String[]{"Queue","Lyrics","Transfer","Group"}) {
            Button button=(Button)find(playback,label);
            assertNotNull(button.getCompoundDrawables()[1]);
            assertEquals(20,button.getCompoundDrawables()[1].getBounds().height());
            assertEquals(label,button.getText().toString());
        }
    }
    @Test public void overflowOptionsStayThemedAttachedAndGuarded() {
        java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
        TouchPanel panel=surface.panel("Queue",false,p->{
            p.guard(current::get);p.menu("Queue options",new String[]{"Save queue","Clear queue"},new Runnable[]{()->actions.add("save"),()->actions.add("clear")});
        },surface::clearPanel);layout();
        assertEquals(View.GONE,((View)find(panel,"Save queue").getParent()).getVisibility());
        tap(find(panel,"Queue options"));layout();
        assertEquals(View.VISIBLE,((View)find(panel,"Save queue").getParent()).getVisibility());
        tap(find(panel,"Save queue"));assertEquals(Arrays.asList("save"),actions);
        tap(find(panel,"Queue options"));layout();current.set(false);
        find(panel,"Clear queue").performClick();assertEquals(Arrays.asList("save"),actions);
    }
    @Test public void queueDeleteIsSeparateFromPlaybackAndProtectedTracksAreDisabled() {
        TouchPanel panel=surface.panel("Queue",false,p->{
            p.mediaRow("Current track","Artist","▶",true,true,null,()->actions.add("play-current"),null,()->actions.add("delete-current"),false);
            p.mediaRow("Upcoming track","Artist","2",false,true,null,()->actions.add("play-upcoming"),null,()->actions.add("delete-upcoming"),true);
        },surface::clearPanel);layout();
        assertFalse(find(panel,"Remove from queue: Current track").isEnabled());
        tap(find(panel,"Remove from queue: Upcoming track"));assertEquals(Arrays.asList("delete-upcoming"),actions);
        tap(find(panel,"Upcoming track"));assertEquals(Arrays.asList("delete-upcoming","play-upcoming"),actions);
    }
    @Test public void playbackIconButtonsHaveVisibleGapsAndNoWeightedEmptyWidth() {
        View previous=find(playback,"Previous"),pause=find(playback,"Pause"),next=find(playback,"Next");
        assertEquals(44,previous.getWidth());assertEquals(44,next.getWidth());
        int[] a=new int[2],b=new int[2],c=new int[2];previous.getLocationOnScreen(a);pause.getLocationOnScreen(b);next.getLocationOnScreen(c);
        assertTrue(b[0]-a[0]-previous.getWidth()>=8);
        assertTrue(c[0]-b[0]-pause.getWidth()>=8);
    }
    @Test public void queueFocusKeepsNowPlayingHeadingVisible() {
        TouchPanel panel=surface.panel("Queue",false,p->{
            p.section("Previously played");for(int i=0;i<15;i++)p.row("Old "+i,"Artist","",false,true,()->{},null);
            p.section("Now playing");p.row("Current","Artist","▶",true,true,()->{},null);
            p.section("Playing next");for(int i=0;i<15;i++)p.row("Next "+i,"Artist","",false,true,()->{},null);
            p.focusCurrent();
        },surface::clearPanel);layout();shadowOf(Looper.getMainLooper()).idle();layout();
        android.graphics.Rect visible=new android.graphics.Rect();View heading=find(panel,"Now playing");
        assertTrue(heading.getGlobalVisibleRect(visible));assertEquals(heading.getHeight(),visible.height());
    }
    @Test @Config(qualifiers="w788dp-h394dp-land-195dpi") public void controlsStayFixedAtActualEchoDensityWithShortAndLongTitles() throws Exception {
        int baseline=-1;
        for(String title:new String[]{"Affirmation","A very long title that wraps over several lines without being truncated or moving the playback controls off screen"}){
            JSONObject p=new JSONObject().put("name","Bedroom 1").put("playback_state","playing").put("volume_control","native").put("supported_features",new JSONArray(new String[]{"pause","seek","next_previous"}));
            JSONObject q=new JSONObject().put("queue_id","speaker").put("current_item",new JSONObject().put("queue_item_id","item").put("media_item",new JSONObject().put("name",title).put("duration",240).put("media_type","track")));
            playback.render(new NowPlayingData(p,q,false));playback.message("An error remains visible");layout();
            TextView text=(TextView)find(playback,title);assertEquals(title,text.getText().toString());assertNull(text.getEllipsize());
            for(String label:new String[]{"Pause","Next","Queue","Lyrics","Transfer","Group"}){
                View button=find(playback,label);android.graphics.Rect visible=new android.graphics.Rect();
                assertTrue(label+" visible",button.getGlobalVisibleRect(visible));assertEquals(label+" full height "+visible,button.getHeight(),visible.height());assertTrue(label+" bottom "+visible,visible.bottom<=480);
                for(android.view.ViewParent parent=button.getParent();parent instanceof View;parent=parent.getParent())assertFalse(parent instanceof ScrollView);
            }
            int[] xy=new int[2];find(playback,"Pause").getLocationOnScreen(xy);
            if(baseline<0)baseline=xy[1];else assertEquals(baseline,xy[1]);
        }
    }
    private void boundedLayout(int width,int height,boolean portrait){
        layoutAt(width,height);
        View art=find(playback,"Album artwork"),pause=find(playback,"Pause"),progress=find(playback,"Track position");
        int[] a=new int[2],p=new int[2];art.getLocationOnScreen(a);pause.getLocationOnScreen(p);
        assertEquals(art.getWidth(),art.getHeight());assertTrue(art.getWidth()<=theme().px(440));
        if(portrait)assertTrue(p[1]>a[1]+art.getHeight());else assertTrue(p[0]>a[0]+art.getWidth());
        for(String label:new String[]{"Pause","Next","Queue","Lyrics","Transfer","Group"}){
            View v=find(playback,label);android.graphics.Rect rect=new android.graphics.Rect();
            assertTrue(label,v.getGlobalVisibleRect(rect));assertEquals(label,v.getHeight(),rect.height());
            assertTrue(label,rect.right<=width);
        }
        int[] progressXY=new int[2];progress.getLocationOnScreen(progressXY);
        // Metadata and transport remain one bounded group, not pinned to opposite edges.
        View player=find(playback,"Bedroom 1");int[] text=new int[2];player.getLocationOnScreen(text);
        assertTrue("Metadata-to-slider distance "+(progressXY[1]-text[1])+"; expected <= "+theme().px(144),progressXY[1]-text[1]<=theme().px(144));
    }
    @Test @Config(qualifiers="w1575dp-h984dp-land-195dpi") public void hallwayLayoutIsBoundedAndCentred(){boundedLayout(1920,1200,false);}
    @Test @Config(qualifiers="w1050dp-h656dp-land-195dpi") public void loungeLayoutIsBoundedAndCentred(){boundedLayout(1280,800,false);}
    @Test @Config(qualifiers="w480dp-h800dp-port-mdpi") public void portraitStacksArtworkWithoutHidingControls(){boundedLayout(480,800,true);}
    @Test public void groupPresentationNamesTheGroupAndThisMemberWithoutChangingItsIdentity()throws Exception{
        JSONObject leader=new JSONObject().put("player_id","hall").put("name","Hallway").put("group_members",new JSONArray(Arrays.asList("hall","bed","lounge")));
        NowPlayingData data=new NowPlayingData(leader,new JSONObject()).withSelection("bed","Bedroom 1","bed");
        assertEquals("Hallway + 2 · Group",data.player);assertEquals("This device: Bedroom 1",data.device);
        playback.render(data);layout();assertNotNull(find(playback,"Group volume"));assertNotNull(find(playback,"This device: Bedroom 1"));
        assertEquals(data.player,data.withFavorite(true).player);assertEquals(data.device,data.withFavorite(true).device);
        NowPlayingData remote=new NowPlayingData(leader,new JSONObject()).withSelection("hall","Hallway","bed","Bedroom 1");
        assertEquals("This device: Bedroom 1 · Controlling: Hallway",remote.device);
    }
    @Test public void volumeReadbackKeepsTheSamePanelControlsAndScrollPosition(){
        final TouchPanel.VolumeControl[] volume={null};
        TouchPanel panel=surface.panel("Group",false,p->{
            for(int i=0;i<20;i++)p.note("Member "+i);
            volume[0]=p.volume("Bedroom volume",40,false,true,true,n->{},()->{});
        },surface::clearPanel);layout();
        SeekBar slider=findSlider(panel,"Bedroom volume");ScrollView scroll=null;
        for(android.view.ViewParent parent=slider.getParent();parent!=null;parent=parent.getParent())if(parent instanceof ScrollView){scroll=(ScrollView)parent;break;}
        assertNotNull(scroll);scroll.scrollTo(0,100);int position=scroll.getScrollY();
        volume[0].busy(true);assertFalse(slider.isEnabled());volume[0].update(65,true);volume[0].busy(false);layout();
        assertSame(slider,findSlider(panel,"Bedroom volume"));assertEquals(0,slider.getProgress());assertEquals(position,scroll.getScrollY());assertTrue(volume[0].muted());
        assertNotNull(find(panel,"Unmute Bedroom volume"));assertTrue(slider.isEnabled());
        volume[0].update(65,false);assertEquals(65,slider.getProgress());
    }
    @Test public void serverPickerUsesASeparateLabelAndNameOnlyValue(){
        TouchPanel panel=surface.panel("Plugin preferences",true,p->p.picker("Music Assistant server",Arrays.asList(new DiscoveryPolicy.Choice("internal_slug","Music Assistant")),"internal_slug",id->actions.add(id),true),surface::clearPanel);
        assertNotNull(find(panel,"Music Assistant server"));assertNotNull(find(panel,"Music Assistant ▾"));assertNull(find(panel,"Music Assistant server: Music Assistant ▾"));assertNull(find(panel,"internal_slug"));
        find(panel,"Music Assistant ▾").performClick();find(panel,"Music Assistant").performClick();assertEquals(Collections.singletonList("internal_slug"),actions);assertNotNull(find(panel,"Music Assistant ▾"));
    }
    @Test public void queueHoldOpensAnAttachedPopupWithoutPlayingOrReplacingPanel()throws Exception{
        final TouchPanel[] holder={null};
        TouchPanel panel=surface.panel("Queue",false,p->{holder[0]=p;p.queueRow("Track","Artist","2",false,true,null,()->actions.add("play"),
            anchor->p.contextMenu(anchor,new String[]{"Add to playlist…"},new Runnable[]{()->actions.add("playlist")}),null,false);},surface::clearPanel);layout();
        View row=(View)find(panel,"Track").getParent().getParent();assertTrue(row.performLongClick());assertTrue(actions.isEmpty());
        java.lang.reflect.Field field=TouchPanel.class.getDeclaredField("popup");field.setAccessible(true);PopupWindow popup=(PopupWindow)field.get(panel);
        assertTrue(popup.isShowing());assertNull(find(panel,"Options: Track"));assertNull(find(popup.getContentView(),"Play this queue item"));
        find(popup.getContentView(),"Add to playlist…").performClick();assertEquals(Collections.singletonList("playlist"),actions);assertFalse(popup.isShowing());
        row.performClick();assertEquals(Arrays.asList("playlist","play"),actions);
    }
    @Test public void unknownFavouriteCanBeTappedForFreshVerification()throws Exception{
        JSONObject p=new JSONObject().put("player_id","speaker");
        JSONObject q=new JSONObject().put("queue_id","speaker").put("current_item",new JSONObject().put("media_item",new JSONObject().put("uri","library://track/1").put("media_type","track")));
        playback.render(new NowPlayingData(p,q));layout();View heart=find(playback,"Favourite");assertNotNull(heart);assertTrue(heart.isEnabled());heart.performClick();assertEquals(Collections.singletonList("favorite"),actions);
    }
    private int count(View root,String label){int n=label.equals(String.valueOf(root.getContentDescription()))?1:0;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)n+=count(((ViewGroup)root).getChildAt(i),label);return n;}
    @Test public void lyricsKeepExactlyOneFollowButtonAcrossTrackChanges(){
        TouchPanel panel=surface.panel("Lyrics",false,p->{},surface::clearPanel);layout();
        for(int i=0;i<5;i++){panel.clearLyrics("");panel.lyrics(LyricsData.parse("","[00:00.00]Track "+i+"\n[00:05.00]Next line"),0,true);layout();assertEquals(1,count(panel,"Follow playback"));}
        panel.clearLyrics("No track");assertEquals(0,count(panel,"Follow playback"));
    }
    @Test public void directQueueButtonsDoNotPlayOrOpenARowMenu(){
        TouchPanel panel=surface.panel("Queue",false,p->p.queueActionRow("Track","Artist","2",false,true,null,()->actions.add("play"),()->actions.add("playlist"),()->actions.add("remove"),true),surface::clearPanel);layout();
        find(panel,"Add to playlist: Track").performClick();find(panel,"Remove from queue: Track").performClick();assertEquals(Arrays.asList("playlist","remove"),actions);
        assertFalse(((View)find(panel,"Track").getParent().getParent()).isLongClickable());
    }
    @Test public void settingsDisclosureIsCollapsedInlineAndKeepsStagedInputs(){
        TouchPanel panel=surface.panel("Settings",false,p->{
            p.section("This kiosk");p.draftInput("Receiver name","Existing",value->actions.add(value));
            p.disclosure("Advanced / Diagnostics",inside->inside.draftInput("Server override","",value->{}));
        },surface::clearPanel);layout();
        assertNotNull(find(panel,"Receiver name"));assertNull(find(panel,"Server override"));View toggle=find(panel,"Advanced / Diagnostics");
        toggle.performClick();layout();EditText endpoint=(EditText)find(panel,"Server override");assertTrue(endpoint.isShown());endpoint.setText("ws://fixture.invalid:8927/sendspin");
        toggle.performClick();assertFalse(endpoint.isShown());toggle.performClick();assertSame(endpoint,find(panel,"Server override"));assertTrue(endpoint.getText().toString().contains("fixture.invalid"));assertTrue(actions.isEmpty());
    }
}
