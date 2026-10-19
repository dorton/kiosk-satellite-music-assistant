package net.markandrew.kiosk.music;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import me.jxl.kiosk.plugins.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real browser -> plugin search restoration, with a cached fixture identity. No network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,qualifiers="w960dp-h480dp-land-mdpi")
public class LibraryInteractionTest {
    private Activity activity;
    private MusicAssistantPlugin plugin;
    private BrowserView browser;
    private IngressAccess.Session session;
    private TouchPanel overlay;
    private final List<String> commands=new ArrayList<>();
    private KsTheme theme(){
        Map<String,Integer> colors=new HashMap<>();
        for(String key:new String[]{"surface","surfaceContainerHigh","onSurface","onSurfaceVariant","primary","onPrimary","outlineVariant","error"})colors.put(key,0xff556677);
        return new KsTheme(true,1,colors,w->null,n->new android.graphics.Path());
    }
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private BrowserPage page(String title)throws Exception{
        return BrowserPage.library("Library",new JSONArray().put(new JSONObject().put("name",title).put("uri","library://track/1").put("media_type","track")),0);
    }
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();plugin=new MusicAssistantPlugin();
        new android.util.AtomicFile(new java.io.File(activity.getNoBackupFilesDir(),"musicassistantplugin-library-tabs-v1.json")).delete();
        PluginHost host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            if(method.getName().equals("executeCommand")){
                commands.add((String)args[0]);((PluginHost.CommandCallback)args[2]).onResult(true,Collections.singletonMap("homeAssistantUrl","https://fixture.invalid"),null);
            }else if(method.getName().equals("haCall"))((PluginHost.CommandCallback)args[1]).onResult(true,Collections.singletonMap("id","fixture-user"),null);
            else if(method.getName().equals("showOverlay")&&args[0].equals("item_actions")){
                View root=((OverlayFactory)args[2]).create(activity,theme());overlay=(TouchPanel)get(plugin,"playerPanel");activity.setContentView(root);
            }else if(method.getName().equals("hideOverlay")&&args[0].equals("item_actions")){
                overlay=null;if(browser!=null)activity.setContentView(browser);
            }
            return null;
        });
        IngressAccess access=new IngressAccess(host);
        session=new IngressAccess.Session(URI.create("https://fixture.invalid/"),URI.create("https://fixture.invalid/"),"fixture-user","fixture_addon","fixture_cookie");
        set(access,"cached",session);set(plugin,"host",host);set(plugin,"access",access);set(plugin,"addon","fixture_addon");
        set(plugin,"running",true);set(plugin,"showing",true);set(plugin,"displaySession",session);
        browser=new BrowserView(activity,theme(),plugin);set(plugin,"view",browser);activity.setContentView(browser);
        browser.setCategory("tracks");browser.render(page("Saved track"),true);layout();
    }
    @After public void stop(){plugin.stop();}
    private void layout(){
        browser.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));browser.layout(0,0,960,480);
        shadowOf(Looper.getMainLooper()).idle();
    }
    private View find(View view,String label){
        if(label.equals(String.valueOf(view.getContentDescription()))||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=find(((ViewGroup)view).getChildAt(i),label);if(child!=null)return child;}
        return null;
    }
    @Test public void libraryHeartIsAnIndependentToggleAndDoesNotOpenOrPlayTheAlbum()throws Exception{
        List<String> calls=new ArrayList<>();
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(proxy,method,args)->{
            if(method.getName().equals("favorite")){
                calls.add("favorite:"+((BrowserItem)args[0]).uri);((java.util.function.Consumer<Boolean>)args[1]).accept(true);
            }else if(Arrays.asList("open","play","menu").contains(method.getName()))calls.add(method.getName());
            return null;
        });
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);view.setCategory("albums");
        view.render(BrowserPage.library("Library",new JSONArray().put(new JSONObject().put("name","Album").put("uri","library://album/1").put("media_type","album")),0),true);
        Button heart=(Button)find(view,"Add favourite: Album");assertNotNull(heart);heart.performClick();shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(find(view,"Remove favourite: Album"));assertEquals(Collections.singletonList("favorite:library://album/1"),calls);
        assertEquals(0xffef5350,NativeStyle.color("favorite"));
    }
    @Test public void libraryActionsSitWithCategoriesNotInTheHeaderOverflow(){
        List<String> calls=new ArrayList<>();
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(proxy,method,args)->{if(Arrays.asList("createPlaylist","setup","players","close").contains(method.getName()))calls.add(method.getName());return null;});
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);
        View preferences=find(view,"Plugin"),albums=find(view,"Albums"),player=find(view,"Player");
        assertNull(find(view,"Create playlist"));assertNull(find(view,"Tools"));assertSame(albums.getParent(),preferences.getParent());assertSame(albums.getParent(),player.getParent());assertNull(find(view,"Player settings"));assertNull(find(view,"Join group"));
        assertNull(find(view,"Library tools"));assertNull(find(view,"⋯"));
        preferences.performClick();player.performClick();assertEquals(Arrays.asList("setup","players"),calls);
    }
    @Test public void albumCardKeepsMenuAndHoldAlongsideImmediateReplacePlay()throws Exception{
        List<String> calls=new ArrayList<>();BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(proxy,method,args)->{
            if(method.getName().equals("playOption"))calls.add("play:"+((BrowserItem)args[0]).uri+":"+args[1]);else if(method.getName().equals("menu"))calls.add("menu");return null;
        });
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);view.setCategory("albums");
        view.render(BrowserPage.library("Library",new JSONArray().put(new JSONObject().put("name","Album").put("uri","library://album/1").put("media_type","album")),0),true);
        for(int width:new int[]{360,788,960,1280}){
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));view.layout(0,0,width,480);
            view.render((BrowserPage)get(view,"page"),false);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));view.layout(0,0,width,480);
            View play=find(view,"Play album: Album · replace queue"),menu=find(view,"Actions for Album");assertNotNull(play);assertNotNull(menu);
            int[] p=new int[2],m=new int[2];play.getLocationOnScreen(p);menu.getLocationOnScreen(m);assertEquals(p[1],m[1]);assertTrue(p[0]+play.getWidth()<=m[0]);assertTrue(m[0]+menu.getWidth()<=width);assertNull(find(view,"Playback actions for Album"));assertFalse(((Button)play).getText().toString().contains("Play"));assertEquals(44,play.getWidth());
        }
        find(view,"Play album: Album · replace queue").performClick();assertEquals(Collections.singletonList("play:library://album/1:replace"),calls);
        find(view,"Actions for Album").performClick();View tile=(View)find(view,"Album artwork").getParent();assertTrue(tile.performLongClick());assertEquals(Arrays.asList("play:library://album/1:replace","menu","menu"),calls);
    }
    @Test public void fiveAlbumsFitAt1024AndFirstContentAlignsWithSidebar()throws Exception{
        JSONArray albums=new JSONArray();for(int i=0;i<10;i++)albums.put(new JSONObject().put("name","Album "+i).put("uri","library://album/"+i).put("media_type","album"));BrowserPage content=BrowserPage.library("Albums",albums,0);browser.setCategory("albums");browser.render(content,true);
        for(int i=0;i<3;i++){browser.measure(View.MeasureSpec.makeMeasureSpec(1024,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));browser.layout(0,0,1024,640);shadowOf(Looper.getMainLooper()).idle();}
        browser.measure(View.MeasureSpec.makeMeasureSpec(1024,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));browser.layout(0,0,1024,640);browser.render(content,true);browser.measure(View.MeasureSpec.makeMeasureSpec(1024,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));browser.layout(0,0,1024,640);
        View first=(View)find(browser,"Album 0 artwork").getParent();ViewGroup row=(ViewGroup)first.getParent();assertEquals(5,row.getChildCount());int[] card=new int[2],nav=new int[2],head=new int[2];first.getLocationOnScreen(card);find(browser,"Return").getLocationOnScreen(nav);View header=(View)get(browser,"heading");header.getLocationOnScreen(head);assertEquals(head[1]+header.getHeight()+4,card[1]);assertEquals(nav[1],card[1]);
        Button play=(Button)find(browser,"Play album: Album 0 · replace queue");assertEquals(44,play.getWidth());assertTrue(play.getBackground() instanceof android.graphics.drawable.RippleDrawable);assertTrue(((android.graphics.drawable.RippleDrawable)play.getBackground()).getDrawable(0) instanceof android.graphics.drawable.GradientDrawable);android.graphics.drawable.LayerDrawable glyph=(android.graphics.drawable.LayerDrawable)play.getForeground();assertEquals(18,glyph.getLayerWidth(0));
    }
    @Test public void railSeparatesPlaybackLibraryToolsAndSettings(){
        LinearLayout rail=(LinearLayout)find(browser,"Player").getParent();
        assertEquals("Return",((TextView)rail.getChildAt(0)).getText().toString());
        assertEquals("Player",((TextView)rail.getChildAt(1)).getText().toString());
        assertEquals("Library",((TextView)rail.getChildAt(2)).getText().toString());
        assertEquals("Playlists",((TextView)rail.getChildAt(3)).getText().toString());assertNull(find(browser,"Playback"));
        int settings=rail.indexOfChild(find(rail,"Settings"));assertTrue(settings>rail.indexOfChild(find(rail,"Filter / Sort")));assertEquals("Plugin",((TextView)rail.getChildAt(settings+1)).getText().toString());assertEquals("Library",((TextView)rail.getChildAt(settings+2)).getText().toString());
        assertNull(find(rail,"Tools"));assertNull(find(rail,"Create playlist"));assertTrue(rail.indexOfChild(find(rail,"Filter / Sort"))>rail.indexOfChild(find(rail,"Audiobooks")));assertTrue(rail.getChildAt(settings+2) instanceof Button);
    }
    @Test public void loadingCardRetainsRowsAndDisappearsOnReadFailure()throws Exception{
        browser.render(new BrowserPage("Library","Loading…",Collections.emptyList(),false,0,true,false),false);layout();
        assertNotNull(find(browser,"Saved track"));assertEquals(View.VISIBLE,find(browser,"Loading library").getVisibility());assertNull(find(browser,"Reading through HA Ingress…"));
        browser.render(BrowserPage.status("Library","Read failed",false,true),false);layout();
        assertEquals(View.GONE,find(browser,"Loading library").getVisibility());assertNotNull(find(browser,"Read failed"));assertNotNull(find(browser,"Retry"));
    }
    @Test public void librarySettingsSaveVisibilityWithoutChangingPluginPreferences()throws Exception{
        plugin.librarySettings();shadowOf(Looper.getMainLooper()).idle();assertNotNull(overlay);assertNull(find(overlay,"Visible Library tabs"));assertNull(find(overlay,"Plugin preferences"));
        ((Switch)find(overlay,"Radio")).setChecked(false);assertTrue(new LibraryTabs(activity).visible.contains("radio"));assertEquals(View.VISIBLE,find(browser,"Radio").getVisibility());
        find(overlay,"Save").performClick();shadowOf(Looper.getMainLooper()).idle();assertNull(overlay);assertFalse(new LibraryTabs(activity).visible.contains("radio"));assertEquals(View.GONE,find(browser,"Radio").getVisibility());assertTrue(commands.isEmpty());
        BrowserView reopened=new BrowserView(activity,theme(),plugin);assertEquals(View.GONE,find(reopened,"Radio").getVisibility());assertEquals(View.VISIBLE,find(reopened,"Albums").getVisibility());
    }
    @Test public void saveAndCloseAreSideBySideAndTextFilterIsAbsent()throws Exception{
        plugin.librarySettings();shadowOf(Looper.getMainLooper()).idle();View save=find(overlay,"Save");
        ViewGroup row=(ViewGroup)save.getParent();assertEquals(2,row.getChildCount());assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)row).getOrientation());assertEquals("Close",((TextView)row.getChildAt(1)).getText().toString());
        plugin.filters();shadowOf(Looper.getMainLooper()).idle();assertNull(find(overlay,"Filter this library category"));assertNotNull(find(overlay,"Sort"));assertNotNull(find(overlay,"Favourites"));
        find(overlay,"Sort").performClick();assertFalse(hasTextInput(overlay));assertNotNull(find(overlay,"Recently played"));
        find(overlay,"Favourites").performClick();assertFalse(hasTextInput(overlay));assertNotNull(find(overlay,"Favourites only"));
    }
    private boolean hasTextInput(View v){if(v instanceof EditText)return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(hasTextInput(((ViewGroup)v).getChildAt(i)))return true;return false;}
    @Test public void leavingLibraryClearsSearchButDoesNotKeepItsCachedIdentity()throws Exception{
        set(plugin,"category","search");set(plugin,"query","old search");
        Method clear=MusicAssistantPlugin.class.getDeclaredMethod("clearSearchOnPlayerExit");clear.setAccessible(true);clear.invoke(plugin);shadowOf(Looper.getMainLooper()).idle();
        assertEquals("albums",get(plugin,"category"));assertEquals("",get(plugin,"query"));assertNull(get(plugin,"displaySession"));assertNull(get(plugin,"searchOrigin"));
    }
    @Test public void closingLibrarySettingsDiscardsPendingVisibility()throws Exception{
        plugin.librarySettings();shadowOf(Looper.getMainLooper()).idle();((Switch)find(overlay,"Albums")).setChecked(false);find(overlay,"Close").performClick();shadowOf(Looper.getMainLooper()).idle();assertNull(overlay);assertTrue(new LibraryTabs(activity).visible.contains("albums"));assertEquals(View.VISIBLE,find(browser,"Albums").getVisibility());
    }
    @Test public void hiddenCategoryFallsBackToFirstVisibleTabWithoutHidingSearch()throws Exception{
        browser.libraryTabs(new LinkedHashSet<>(Arrays.asList("tracks","radio")));assertEquals("tracks",browser.visibleCategory("albums"));assertEquals("search",browser.visibleCategory("search"));assertEquals("detail",browser.visibleCategory("detail"));assertEquals(View.VISIBLE,find(browser,"Search").getVisibility());LinearLayout rail=(LinearLayout)find(browser,"Plugin").getParent();assertEquals("Library",((TextView)rail.getChildAt(rail.indexOfChild(find(rail,"Settings"))+2)).getText().toString());
    }
    @Test public void sidebarButtonsHaveEightDpGapsAndReturnSaysNowPlaying(){
        View albums=find(browser,"Albums"),tracks=find(browser,"Tracks");
        assertEquals(8,((LinearLayout.LayoutParams)albums.getLayoutParams()).bottomMargin);
        assertNotNull(find(browser,"Return"));assertEquals(8,((LinearLayout.LayoutParams)tracks.getLayoutParams()).bottomMargin);
    }
    @Test public void libraryReturnLabelsRetainOneRecheckingAction(){
        List<String> calls=new ArrayList<>();BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(proxy,method,args)->{if(method.getName().equals("close"))calls.add("return");return null;});
        BrowserView view=new BrowserView(activity,theme(),actions);
        for(String label:Arrays.asList("Now playing","Back to player","Close")){view.returnLabel(label);view.style(theme());View button=find(view,label);assertNotNull(button);assertEquals(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL,((TextView)button).getGravity());assertSame(find(view,"Player").getParent(),button.getParent());button.performClick();}
        assertEquals(3,calls.size());view.returnLabel("");assertNotNull(find(view,"Return · checking player state"));
    }
    @Test public void sidebarScrollIsInsetBelowHeaderAndAboveFixedBottomAtReferenceSizes()throws Exception{
        for(int[] size:new int[][]{{788,394},{960,480},{1280,800}}){
            browser.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));browser.layout(0,0,size[0],size[1]);shadowOf(Looper.getMainLooper()).idle();
            browser.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));browser.layout(0,0,size[0],size[1]);
            ScrollView rail=(ScrollView)find(browser,"Library navigation · scroll for more");View header=(View)get(browser,"heading");int[] top=new int[2],heading=new int[2],origin=new int[2];rail.getLocationOnScreen(top);header.getLocationOnScreen(heading);browser.getLocationOnScreen(origin);
            assertEquals("Rail starts 4 dp below header",heading[1]+header.getHeight()+4,top[1]);assertEquals("No reserved bottom strip",size[1],top[1]-origin[1]+rail.getHeight());assertEquals(View.SCROLLBARS_INSIDE_INSET,rail.getScrollBarStyle());assertTrue("Scrollbar gutter retained",rail.getPaddingRight()>=8);
            rail.fullScroll(View.FOCUS_DOWN);shadowOf(Looper.getMainLooper()).idle();assertTrue(rail.getScrollY()>0||size[1]>=800);
        }
    }
    @Test public void dotsAndHoldOpenTheSameAnchoredMenuWithoutPlayback()throws Exception{
        queuedWorker();Method create=MusicAssistantPlugin.class.getDeclaredMethod("createPlayerController");create.setAccessible(true);create.invoke(plugin);
        BrowserPage rows=page("Saved track");set(plugin,"page",rows);browser.render(rows,true);layout();
        find(browser,"Actions for Saved track").performClick();
        shadowOf(Looper.getMainLooper()).idle();AnchoredItemMenu menu=(AnchoredItemMenu)get(browser,"anchoredMenu");assertTrue(menu.popup.isShowing());assertNull(overlay);assertNotNull(find(menu.rows,"Play on…"));assertNull(find(menu.rows,"Open"));
        Method hide=MusicAssistantPlugin.class.getDeclaredMethod("hideMenu");hide.setAccessible(true);hide.invoke(plugin);
        View tile=(View)find(browser,"Saved track artwork").getParent();assertTrue(tile.performLongClick());
        shadowOf(Looper.getMainLooper()).idle();menu=(AnchoredItemMenu)get(browser,"anchoredMenu");assertTrue("Hold menu stays anchored",menu.popup.isShowing());assertNull(overlay);assertNotNull(find(menu.rows,"Play on…"));assertEquals("Only read-only connection discovery, never playback",Arrays.asList("getDashboardState","getDashboardState"),commands);assertFalse(find(menu.rows,"Loading library actions…").isEnabled());
    }
    @Test public void gridAndSidebarHaveIndependentBoundedViewportsAtEveryReferenceSize()throws Exception{
        JSONArray albums=new JSONArray();for(int i=0;i<40;i++)albums.put(new JSONObject().put("name","Album "+i).put("uri","library://album/"+i).put("media_type","album"));
        for(int[] size:new int[][]{{360,480},{788,394},{960,480},{1280,800}}){
            browser.render(BrowserPage.library("Albums",albums,0),true);
            for(int i=0;i<3;i++){browser.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));browser.layout(0,0,size[0],size[1]);shadowOf(Looper.getMainLooper()).idle();}
            browser.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));browser.layout(0,0,size[0],size[1]);
            ScrollView grid=(ScrollView)find(browser,"Library music grid"),rail=(ScrollView)find(browser,"Library navigation · scroll for more");View header=(View)get(browser,"heading");
            int[] g=new int[2],r=new int[2],h=new int[2],origin=new int[2];grid.getLocationOnScreen(g);rail.getLocationOnScreen(r);header.getLocationOnScreen(h);browser.getLocationOnScreen(origin);
            assertEquals("Grid starts 4 dp below header",h[1]+header.getHeight()+4,g[1]);assertEquals("Grid reaches bottom without a reserved strip",size[1],g[1]-origin[1]+grid.getHeight());assertEquals(r[1]+rail.getHeight(),g[1]+grid.getHeight());
            assertEquals(View.SCROLLBARS_INSIDE_INSET,grid.getScrollBarStyle());assertEquals(View.OVER_SCROLL_NEVER,grid.getOverScrollMode());assertTrue(grid.getPaddingRight()>=8);assertTrue(grid.getClipToPadding());
            rail.setSmoothScrollingEnabled(false);grid.setSmoothScrollingEnabled(false);
            rail.scrollTo(0,0);grid.fullScroll(View.FOCUS_DOWN);shadowOf(Looper.getMainLooper()).idle();assertTrue("Grid must scroll at "+size[0]+"x"+size[1]+"; content="+grid.getChildAt(0).getHeight()+" viewport="+grid.getHeight(),grid.getScrollY()>0);assertEquals(0,rail.getScrollY());
            int before=grid.getScrollY();rail.fullScroll(View.FOCUS_DOWN);shadowOf(Looper.getMainLooper()).idle();assertEquals(before,grid.getScrollY());
        }
    }
    @Test public void filterIsACentredCardWithLibraryUnderneath()throws Exception{
        queuedWorker();plugin.filters();shadowOf(Looper.getMainLooper()).idle();assertNotNull(overlay);
        FrameLayout root=(FrameLayout)overlay.getParent();assertEquals("Popup: Library filter / sort",String.valueOf(root.getContentDescription()));
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,960,480);
        assertTrue(overlay.getWidth()<root.getWidth());assertTrue(overlay.getHeight()<root.getHeight());assertEquals((root.getWidth()-overlay.getWidth())/2,overlay.getLeft());
        find(overlay,"Cancel").performClick();assertNull(overlay);assertNotNull(find(browser,"Saved track"));
    }
    @Test public void navigationRailIsVerticalAndKeepsAScrollCue(){
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(p,m,a)->null);
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);
        android.widget.LinearLayout rail=(android.widget.LinearLayout)find(view,"Player").getParent();
        assertEquals(android.widget.LinearLayout.VERTICAL,rail.getOrientation());
        android.widget.ScrollView scroller=(android.widget.ScrollView)rail.getParent();
        assertTrue(scroller.isVerticalScrollBarEnabled());assertFalse(scroller.isScrollbarFadingEnabled());
        assertEquals("Library navigation · scroll for more",String.valueOf(scroller.getContentDescription()));
    }
    @Test public void albumCaptionDoesNotShareItsWidthWithActionButtons()throws Exception{
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(p,m,a)->null);
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);view.setCategory("albums");
        view.render(BrowserPage.library("Library",new JSONArray().put(new JSONObject().put("name","A long album title that needs readable width").put("uri","library://album/1").put("media_type","album")),0),true);
        for(int[] size:new int[][]{{960,480},{1280,800},{1920,1200}}){
            view.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));view.layout(0,0,size[0],size[1]);shadowOf(Looper.getMainLooper()).idle();
            View label=find(view,"A long album title that needs readable width"),heart=find(view,"Add favourite: A long album title that needs readable width");
            View caption=(View)label.getParent().getParent(),actionsRow=(View)heart.getParent();
            assertNotSame(caption,actionsRow);assertSame(caption.getParent(),actionsRow.getParent());
            assertTrue(caption.getWidth()>0);assertTrue(label.getWidth()>caption.getWidth()-40);
        }
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) public void shortAlbumTitlesDoNotReserveAnEmptyLineAboveTheArtist()throws Exception{
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(p,m,a)->null);
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);view.setCategory("albums");
        JSONArray albums=new JSONArray();for(String name:new String[]{"Short","A much longer album title which wraps onto another line"})albums.put(new JSONObject().put("name",name).put("uri","library://album/"+albums.length()).put("media_type","album").put("artists",new JSONArray().put(new JSONObject().put("name","Artist"))));
        view.render(BrowserPage.library("Library",albums,0),true);view.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));view.layout(0,0,960,480);shadowOf(Looper.getMainLooper()).idle();
        TextView shortTitle=(TextView)find(view,"Short"),longTitle=(TextView)find(view,"A much longer album title which wraps onto another line");LinearLayout labels=(LinearLayout)shortTitle.getParent();TextView artist=(TextView)labels.getChildAt(1);
        assertEquals(1,shortTitle.getLineCount());assertTrue("Long title lines="+longTitle.getLineCount()+" width="+longTitle.getWidth(),longTitle.getLineCount()>1);assertTrue("Title heights "+shortTitle.getHeight()+" / "+longTitle.getHeight(),shortTitle.getHeight()<longTitle.getHeight());assertEquals(shortTitle.getBottom(),artist.getTop());assertFalse(shortTitle.getIncludeFontPadding());
        LinearLayout shortTile=(LinearLayout)shortTitle.getParent().getParent().getParent(),longTile=(LinearLayout)longTitle.getParent().getParent().getParent();assertEquals(shortTile.getHeight(),longTile.getHeight());assertEquals(shortTile.getChildAt(shortTile.getChildCount()-1).getTop(),longTile.getChildAt(longTile.getChildCount()-1).getTop());
    }
    @Test public void libraryPanelsUseTheSameSurfaceAndHeaderNavigationAsPlayerPanels()throws Exception{
        List<String> calls=new ArrayList<>();Method show=MusicAssistantPlugin.class.getDeclaredMethod("showPanel",String.class,java.util.function.Consumer.class);show.setAccessible(true);
        show.invoke(plugin,"Nested screen",(java.util.function.Consumer<TouchPanel>)p->{p.closeAction("‹ Back",()->calls.add("back"));p.add("An action",()->{});});shadowOf(Looper.getMainLooper()).idle();
        assertTrue(overlay.getParent() instanceof MusicPanelSurface);View back=find(overlay,"‹ Back");assertSame(get(overlay,"header"),back.getParent());back.performClick();assertEquals(Collections.singletonList("back"),calls);
    }
    @Test public void albumHeaderHasIconPlayAndOneActionsMenu()throws Exception{
        List<String> calls=new ArrayList<>();
        BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(p,m,a)->{if(m.getName().equals("playOption"))calls.add((String)a[1]);if(m.getName().equals("menu"))calls.add("menu");return null;});
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);view.setCategory("detail");BrowserItem album=new BrowserItem(new JSONObject().put("name","Album").put("media_type","album").put("uri","library://album/1"));
        view.render(new BrowserPage("Album","",Collections.emptyList(),false,0,false,false,album),true);
        Button play=(Button)find(view,"Play album: Album · replace queue");assertFalse(play.getText().toString().contains("Play"));play.performClick();find(view,"Actions for Album").performClick();assertEquals(Arrays.asList("replace","menu"),calls);assertNull(find(view,"Add to queue"));assertNull(find(view,"Playback actions for Album"));
    }
    @Test public void tracksUseTapAndOneMenuWithUntruncatedDurations()throws Exception{
        assertEquals("03:07",BrowserView.trackTime(187));assertEquals("12:34",BrowserView.trackTime(754));assertEquals("125:06",BrowserView.trackTime(7506));assertEquals("—",BrowserView.trackTime(0));
        List<String> calls=new ArrayList<>();BrowserView.Actions actions=(BrowserView.Actions)java.lang.reflect.Proxy.newProxyInstance(BrowserView.Actions.class.getClassLoader(),new Class<?>[]{BrowserView.Actions.class},(p,m,a)->{if(m.getName().equals("play"))calls.add("play:"+a[1]);if(m.getName().equals("menu"))calls.add("menu");return null;});
        BrowserView view=new BrowserView(activity,theme(),actions);activity.setContentView(view);view.setCategory("tracks");view.render(BrowserPage.library("Tracks",new JSONArray().put(new JSONObject().put("name","Long track").put("uri","library://track/1").put("media_type","track").put("duration",754)),0),true);
        assertEquals("12:34",((TextView)find(view,"Duration: Long track")).getText().toString());view.playbackPlayer("Kitchen");assertNull(find(view,"Play track: Long track · replace queue"));assertNull(find(view,"Playback actions for Long track"));
        for(int width:new int[]{360,788,960,1280}){for(int i=0;i<3;i++){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));view.layout(0,0,width,480);shadowOf(Looper.getMainLooper()).idle();}
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));view.layout(0,0,width,480);
            view.render((BrowserPage)get(view,"page"),false);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));view.layout(0,0,width,480);
            View art=find(view,"Long track artwork"),tile=(View)art.getParent();TextView name=(TextView)find(view,"Long track");View labels=(View)name.getParent();assertEquals("Only text/art/control height, no second controls row",Math.max(48,Math.max(labels.getHeight(),art.getVisibility()==View.GONE?0:art.getHeight()))+16,tile.getHeight());View menu=find(view,"Actions for Long track");int[] at=new int[2];menu.getLocationOnScreen(at);assertTrue("Menu right="+(at[0]+menu.getWidth())+" at width="+width,at[0]+menu.getWidth()<=width);assertTrue("Title width="+name.getWidth()+" at viewport="+width,name.getWidth()>0);
        }
        find(view,"Actions for Long track").performClick();View tile=(View)find(view,"Long track artwork").getParent();tile.performClick();assertTrue(tile.performLongClick());assertEquals(Arrays.asList("menu","play:false","menu"),calls);
    }
    private EditText query(View view){
        if(view instanceof EditText)return(EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText child=query(((ViewGroup)view).getChildAt(i));if(child!=null)return child;}
        return null;
    }
    @Test public void clearSearchRestoresPreviousRowsAndNavigationHistory()throws Exception{
        BrowserPage previous=page("Saved track"),results=page("Search result");
        Class<?> location=Class.forName(MusicAssistantPlugin.class.getName()+"$Location");Constructor<?> ctor=location.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object origin=ctor.newInstance("tracks","",null,0,0,previous,session);
        set(plugin,"searchOrigin",origin);set(plugin,"category","search");set(plugin,"query","a query");set(plugin,"page",results);
        ((Deque)get(plugin,"history")).add(origin);
        ((Deque)get(plugin,"searchHistory")).add(origin);
        browser.setCategory("search");browser.render(results,true);query(browser).setText("a query");layout();
        find(browser,"Clear search").performClick();shadowOf(Looper.getMainLooper()).idle();layout();
        assertEquals("",query(browser).getText().toString());assertEquals("tracks",get(plugin,"category"));
        assertNotNull(find(browser,"Saved track"));assertNull(find(browser,"Search result"));
        assertEquals(1,((Deque)get(plugin,"history")).size());assertNull(get(plugin,"searchOrigin"));
        assertFalse(commands.contains("loadStartUrl"));
    }
    @Test public void clearingUnsubmittedTextDoesNotNavigateOrDiscardLibrary()throws Exception{
        set(plugin,"category","tracks");query(browser).setText("not submitted");
        find(browser,"Clear search").performClick();layout();
        assertEquals("",query(browser).getText().toString());assertNotNull(find(browser,"Saved track"));
        assertEquals("tracks",get(plugin,"category"));assertTrue(commands.isEmpty());
    }
    @Test public void routineAcknowledgementsAreHiddenButErrorsRemainVisible()throws Exception{
        browser.activityMessage("Group update acknowledged.");assertNull(find(browser,"Group update acknowledged."));
        browser.message("Connection error");browser.activityMessage("Queue update acknowledged.");
        View error=find(browser,"Connection error");assertNotNull(error);assertEquals(View.VISIBLE,error.getVisibility());
    }
    private void queuedWorker()throws Exception{
        set(plugin,"worker",new java.util.concurrent.AbstractExecutorService(){
            public void execute(Runnable task){} // Reads are deliberately not run: no HTTP in this fixture.
            public void shutdown(){}public List<Runnable> shutdownNow(){return Collections.emptyList();}
            public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}
            public boolean awaitTermination(long time,java.util.concurrent.TimeUnit unit){return true;}
        });
    }
    @Test public void singleMenuCommandTogglesVisibilityWithoutAPlaybackCommand()throws Exception{
        queuedWorker();set(plugin,"settings",new HashMap<String,Object>(){{put("defaultPlayer","speaker");}});
        Method create=MusicAssistantPlugin.class.getDeclaredMethod("createPlayerController");create.setAccessible(true);create.invoke(plugin);
        plugin.execute("toggle",Collections.emptyMap());assertEquals(false,get(plugin,"showing"));assertTrue(commands.isEmpty());
        plugin.execute("toggle",Collections.emptyMap());assertEquals(true,get(plugin,"showing"));
        assertTrue(commands.stream().allMatch(command->command.equals("getDashboardState")));
    }
    @Test public void filterSelectionAppliesAndDismissesWithoutApplyOrBack()throws Exception{
        queuedWorker();set(plugin,"category","albums");plugin.filters();shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(overlay);assertNull(find(overlay,"Apply"));
        find(overlay,"Favourites: All items ▾").performClick();
        find(overlay,"Favourites only").performClick();shadowOf(Looper.getMainLooper()).idle();
        assertNull(overlay);assertEquals(true,get(plugin,"favoritesOnly"));assertEquals("albums",get(plugin,"category"));
    }
    @Test public void successfulPlaybackResetsTemporaryLibraryFilters()throws Exception{
        queuedWorker();set(plugin,"libraryFilter","Ed Sheeran");set(plugin,"libraryOrder","last_played_desc");set(plugin,"favoritesOnly",true);
        set(plugin,"settings",new HashMap<String,Object>(){{put("defaultPlayer","speaker");put("playerMode","Fixed default player");}});
        Method create=MusicAssistantPlugin.class.getDeclaredMethod("createPlayerController");create.setAccessible(true);create.invoke(plugin);
        PlayerController players=(PlayerController)get(plugin,"players");
        ((PlayerController.Ui)get(players,"ui")).playbackStarted();shadowOf(Looper.getMainLooper()).idle();
        assertEquals("",get(plugin,"libraryFilter"));assertEquals("name",get(plugin,"libraryOrder"));assertEquals(false,get(plugin,"favoritesOnly"));
    }
}
