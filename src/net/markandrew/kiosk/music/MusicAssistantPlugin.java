package net.markandrew.kiosk.music;

import android.content.Context;
import android.view.View;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Native multi-kiosk browser with explicit player binding and single-shot playback. */
public final class MusicAssistantPlugin implements KioskPlugin, BrowserView.Actions {
    private final AtomicLong generation = new AtomicLong();
    private final MusicApi api = new MusicApi();
    private final MusicApi playbackApi = new MusicApi();
    private final ArtworkLoader artwork = new ArtworkLoader();
    private final java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile IngressAccess.Session displaySession;
    private BrowserItem detail;
    private volatile boolean running, showing;
    private volatile PluginHost host;
    private volatile IngressAccess access;
    private volatile ExecutorService worker;
    private volatile Future<?> request;
    private volatile BrowserView view;
    private volatile BrowserPage page = BrowserPage.status("Library", "Choose your music", false, false);
    private volatile String addon = "";
    private String category = "albums", query = "";
    private int offset;
    private final java.util.ArrayDeque<Location> history = new java.util.ArrayDeque<>();
    private Location searchOrigin;
    private final java.util.ArrayDeque<Location> searchHistory = new java.util.ArrayDeque<>();
    private boolean pendingOpenRoute;
    private int restorePosition;
    private volatile PlayerController players;
    private volatile SetupController setupController;
    private volatile TouchPanel playerPanel;
    private volatile Map<String, Object> settings = Collections.emptyMap();
    private volatile long playerEpoch;
    private FeatureController features;
    private volatile HardwareVolumeController hardwareVolume;
    private LocalGroupVolume localGroupVolume;
    private volatile String hardwareVolumeRoute="Awaiting a hardware volume change.";
    private int nativeVolumeStep;
    private LocalVolumeLink localVolume;
    private ReceiverVolumeBootstrap volumeBootstrap;
    private boolean volumeEventsAvailable;
    private volatile boolean voiceVolumeHeld;
    private volatile NowPlayingController nowPlayingController;
    private IntercomController intercom;
    private volatile String intercomState="idle";
    private boolean callScreenPending;
    private String callScreenPlayer="";
    private long callScreenTouch;
    private String lastCallDiagnostic="No intercom playback intervention this session.";
    private KioskNameCache kioskNames;
    private volatile net.markandrew.kiosk.music.receiver.PluginReceiver receiver;
    private net.markandrew.kiosk.music.receiver.ReceiverManager receiverManager;
    private ReceiverRegistration registration;
    private final android.os.Handler receiverMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private final IdleRevert idleRevert=new IdleRevert();
    private final Runnable revertPoll=this::pollRevert;
    private volatile long revertEpoch,touchEpoch;
    private boolean revertReading;
    private String revertPlayer="";
    private final PlaybackLaunchPolicy playbackLaunch=new PlaybackLaunchPolicy();
    private final Runnable receiverPlaybackPoll=this::pollReceiverPlayback;
    private final java.util.Set<String> voiceActivity=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
    private final java.util.Map<String,Long> voiceVersions=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long integrationEpoch;
    private boolean checkingLaunch;
    private final MusicApi monitorApi=new MusicApi();
    private IngressAccess monitorAccess;
    private boolean monitorReading;
    private long nextMonitorRead,lastPostpone;
    private String monitoredTarget="";
    private String monitorSelection="";
    private long playbackConfirmedAt;
    private ExecutorService monitorWorker;
    private String libraryFilter = "", libraryOrder = "name";
    private boolean favoritesOnly, appendRequest;
    private JSONArray detailCache;
    private String detailCacheUri = "";
    private BrowserItem detailCacheParent;
    private IngressAccess.Session detailCacheSession;

    private void createPlayerController() {
        if(hardwareVolume!=null)hardwareVolume.cancel();
        playbackApi.ownReceiver(this::ownReceiverId);
        if (players != null) players.cancel();
        final long epoch = ++playerEpoch;
        players = new PlayerController(playbackApi, access, worker, sending,
            () -> running && showing && playerEpoch == epoch, new PlayerController.Ui() {
                public void message(String text) { BrowserView target = view; if (target != null) target.post(() -> {
                    if (running && showing && playerEpoch == epoch) {
                        target.activityMessage(text);
                        TouchPanel panel = playerPanel; if (panel != null) panel.message(text);
                        else if (nowPlayingSurface && nowPlayingController != null) nowPlayingController.notice(text);
                    }
                }); }
                public void error(String text) { BrowserView target = view; if (target != null) target.post(() -> {
                    if (running && showing && playerEpoch == epoch) {
                        target.message(text);
                        TouchPanel panel=playerPanel;if(panel!=null)panel.message(text);
                        else if(nowPlayingSurface&&nowPlayingController!=null)nowPlayingController.notice(text);
                    }
                }); }
                public void library() { receiverMain.post(()->{
                    if(running&&showing&&playerEpoch==epoch){pendingOpenRoute=false;navigateNowPlaying("library");if(displaySession==null)load(category,offset,query);}
                }); }
                public void home() { receiverMain.post(() -> { if(running&&showing&&playerEpoch==epoch)navigateNowPlaying("home"); }); }
                public void playerSettings(){receiverMain.post(()->features.playerSettings());}
                public void preferences(){receiverMain.post(()->setup(()->players.players(false)));}
                public void joinGroup(){receiverMain.post(()->{if(features!=null)features.joinLocalGroup(()->players.players(false));});}
                public void group(){receiverMain.post(()->{if(running&&showing&&playerEpoch==epoch&&features!=null)features.group(()->players.players(false));});}
                public void dismiss() { dismissMusicPanel(); }
                public void nativePlayer() { receiverMain.post(()->{
                    if(!running||!showing||playerEpoch!=epoch)return;
                    boolean opening=pendingOpenRoute;pendingOpenRoute=false;
                    showNativePlayer();if(opening&&displaySession==null)load(category,offset,query);
                }); }
                public void playbackStarted() {
                    BrowserView target = view;
                    final long ticket = generation.get();
                    if (target != null) target.post(() -> {
                        synchronized (MusicAssistantPlugin.this) {
                            if (!running || !showing || playerEpoch != epoch || view != target || generation.get() != ticket) return;
                            // Keep Library underneath the plugin-owned playback surface.
                            showNativePlayer();
                            target.clearSearch();
                            resetLibraryFilters();
                            browse("albums", 0);
                        }
                    });
                }
                public void panel(String title, java.util.function.Consumer<TouchPanel> populate) {
                    if (playerEpoch == epoch) showPanel(title, populate);
                }
                public void popup(String title,java.util.function.Consumer<TouchPanel> populate){if(playerEpoch==epoch)showPanel(title,populate,()->playerEpoch==epoch,true);}
                public void saveDefault(String player,IngressAccess.Session expected,BooleanSupplier current,Runnable saved,java.util.function.Consumer<String> failed){receiverMain.post(()->{
                    if(!current.getAsBoolean()||playerEpoch!=epoch)return;
                    access.ensure(expected.addon,current,latest->receiverMain.post(()->{
                        synchronized(MusicAssistantPlugin.this){
                            if(!current.getAsBoolean()||playerEpoch!=epoch)return;
                            if(latest!=expected||!DefaultPlayerPolicy.saved(settings).isEmpty()||Boolean.TRUE.equals(settings.get("receiverEnabled"))||player.equals(localIdentity)){
                                failed.accept("Connection or default-player settings changed. Reopen the chooser; no default was overwritten.");return;
                            }
                            Map<String,Object> values=new java.util.HashMap<>(settings);values.put("defaultPlayer",player);values.put("playerMode","Fixed default player");values.put("addon",expected.addon);
                            try{host.saveSettings(values);settings=values;addon=expected.addon;saved.run();}
                            catch(RuntimeException error){failed.accept("Saving was not confirmed. Reopen Plugin preferences to check before retrying.");}
                        }
                    }),error->receiverMain.post(()->{if(current.getAsBoolean())failed.accept(error);}));
                });}
            });
        players.configure(settings, addon);
        players.localIdentity(()->localIdentity);
        players.localEnabled(()->localReceiverEnabled());
        if (features != null) features.close();
        features = new FeatureController(host, access, worker, sending,
            () -> addon, () -> players.selectedId(), () -> running && showing && playerEpoch == epoch,
            new FeatureController.Ui() {
                public void panel(String title, BooleanSupplier current, java.util.function.Consumer<TouchPanel> build) { showPanel(title, build, current); }
                public void popup(String title,BooleanSupplier current,java.util.function.Consumer<TouchPanel> build){showPanel(title,build,current,true);}
                public void dismiss() { dismissMusicPanel(); }
                public void message(String text) { BrowserView target = view; if (target != null) target.post(() -> {
                    if (running && showing && playerEpoch == epoch) {
                        target.activityMessage(text);
                        TouchPanel panel = playerPanel; if (panel != null) panel.message(text);
                        else if (nowPlayingSurface && nowPlayingController != null) nowPlayingController.notice(text);
                    }
                }); }
                public void refresh() { retry(); }
                public void play(BrowserItem item, String option, boolean shuffle, String start) { playAdvanced(item, option, shuffle, start); }
                public void open(BrowserItem item) { MusicAssistantPlugin.this.open(item); }
                public void players(Runnable after) { players.players(false, after); }
                public void preferences(){receiverMain.post(()->setup());}
                public void preferences(Runnable back){receiverMain.post(()->setup(()->features.playerSettings()));}
                public void joined(String player){receiverMain.post(()->players.followJoined(player));}
                public void memberRemoved(String player,Runnable otherwise){receiverMain.post(()->players.memberRemoved(player,otherwise));}
                public void library(){receiverMain.post(()->{
                    if(running&&showing&&playerEpoch==epoch){pendingOpenRoute=false;navigateNowPlaying("library");if(displaySession==null)load(category,offset,query);}
                });}
            });
        features.ownReceiver(this::ownReceiverId);
        features.navigationHeld(this::callNavigationHeld);
        features.controllerOnly(this::controllerOnly);
        features.joinDiagnostics(text->lastJoinDiagnostic=text);
        features.lyricsDiagnostics(text->lastLyricsDiagnostic=text);
        receiverMain.post(()->{if(running&&showing&&!nowPlayingSurface&&view!=null)startLibraryReturn();});
    }

    private String ownReceiverId(IngressAccess.Session session) {
        net.markandrew.kiosk.music.receiver.PluginReceiver local = receiver;
        return OwnReceiverPolicy.id(local == null ? null : local.state(),
            running && localReceiverEnabled(), addon, session.addon);
    }

    private static final class Location {
        final String category, query;
        final BrowserItem detail;
        final int offset, scroll;
        final BrowserPage page;
        final IngressAccess.Session session;
        Location(String category, String query, BrowserItem detail, int offset, int scroll, BrowserPage page, IngressAccess.Session session) {
            this.category = category; this.query = query; this.detail = detail; this.offset = offset; this.scroll = scroll;
            this.page = page; this.session = session;
        }
    }

    private RemoteSetup remoteSetup;
    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host; this.access = new IngressAccess(host);
        monitorWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"music-plugin-monitor");t.setDaemon(true);return t;});
        monitorAccess=new IngressAccess(host);monitorApi.ownReceiver(session->{
            net.markandrew.kiosk.music.receiver.PluginReceiver local=receiver;
            return OwnReceiverPolicy.id(local==null?null:local.state(),running&&localReceiverEnabled(),addon.isEmpty()?session.addon:addon,session.addon);
        });
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "musicassistantplugin-read"); thread.setDaemon(true); return thread;
        });
        running = true; configure(settings);
        remoteSetup=new RemoteSetup(host,()->running,()->new java.util.HashMap<>(this.settings),this::ownReceiverId);
        hardwareVolume=new HardwareVolumeController(host,()->addon,()->players==null?"":players.selectedId(),()->running&&!voiceVolumeHeld&&legacyHardwareVolumeAllowed(),sending,(text,error)->{
            if(error||(!text.contains("confirmed ·")&&!text.contains("already at its limit")))receiverMain.post(()->{if(running){status(text,true);if(nowPlayingController!=null)nowPlayingController.notice(text);}});
        });
        hardwareVolume.ownReceiver(this::ownReceiverId);
        KioskNameAccess.read(host,name->receiverMain.post(()->{if(running&&!name.isEmpty()){receiverDeviceName=name;if(kioskNames!=null)kioskNames.remember(name);}}));
        observeVoice("voice.state","getVoiceState","voice");
        observeVoice("intercom.state","getIntercomState","intercom");
        try{host.subscribe("voice.interaction");}catch(RuntimeException|LinkageError ignored){/* Older host: use voice.state and audio focus. */}
        status("Ready · Music Assistant", false);
        try{host.subscribe("device.key");}catch(RuntimeException|LinkageError ignored){status("Hardware volume events unavailable on this Kiosk version.",true);}
        try{host.subscribe("device.volume");volumeEventsAvailable=true;}catch(RuntimeException|LinkageError ignored){volumeEventsAvailable=false;status("Master volume observation is unavailable; linked music will stay muted.",true);}
    }

    private boolean migrationAttempted;
    private String migrationWarning="";
    @Override public synchronized void configure(Map<String, Object> settings) {
        if(remoteSetup!=null)remoteSetup.cancel();
        StandaloneServer.configure(settings);
        Map<String,Object> normalized=DefaultPlayerPolicy.normalized(settings);
        if(setupController!=null&&playbackSettingsOnly(this.settings,normalized)){
            this.settings=normalized;
            if(receiver!=null)receiver.duckPercent(AudioGainPolicy.configuredPercent(settings.get("duckPercent")));
            if(showing)startRevertMonitor();return;
        }
        if(intercom!=null){intercom.close();intercom=null;callScreenPending=false;}
        integrationEpoch++;checkingLaunch=false;monitorReading=false;nextMonitorRead=0;monitoredTarget="";playbackLaunch.reset();
        monitorApi.cancel();if(monitorAccess!=null)monitorAccess.clear();
        stopRevertMonitor();
        if (nowPlayingController != null) { nowPlayingController.dismiss(); nowPlayingController = null; }
        String next = String.valueOf(settings.containsKey("addon") ? settings.get("addon") : "").trim();
        if (!next.isEmpty()) IngressPolicy.addon(next);
        if (setupController != null) setupController.cancel();
        invalidate(); addon = next;this.settings=DefaultPlayerPolicy.normalized(settings);
        if(!migrationAttempted&&!DefaultPlayerPolicy.saved(settings).isEmpty()&&(!DefaultPlayerPolicy.saved(settings).equals(settings.get("defaultPlayer"))||!"Fixed default player".equals(settings.get("playerMode")))){
            migrationAttempted=true;
            try{host.saveSettings(new java.util.HashMap<>(this.settings));}
            catch(RuntimeException|LinkageError error){migrationWarning="Legacy default migration was not confirmed. Check the displayed default before saving; no automatic retry.";status(migrationWarning,true);}
        }
        createPlayerController();
        setupController = new SetupController(host, access, worker, () -> running && showing, new SetupController.Ui() {
            public Map<String, Object> settings() { return new java.util.HashMap<>(MusicAssistantPlugin.this.settings); }
            public void panel(String title, java.util.function.Consumer<TouchPanel> populate) { showPanel(title,p->{populate.accept(p);if(!migrationWarning.isEmpty())p.message(migrationWarning);}); }
            public void dismiss() { Runnable back=preferencesBack;preferencesBack=null;hideMenu();if(back!=null)back.run(); }
            public void receiverSettings(TouchPanel panel,Map<String,Object> draft){populateReceiverSettings(panel,draft);}
            public void diagnostics(TouchPanel panel,Map<String,Object> draft){populateReceiverDiagnostics(panel,draft);}
            public void connectionResult(TouchPanel panel,PlayerPolicy.Snapshot snapshot,Runnable back){populateConnectionResult(panel,snapshot,back);}
            public String localName(){return receiverDeviceName;}
            public boolean controllerOnly(Map<String,Object> draft){return OwnReceiverPolicy.controllerOnly(DefaultPlayerPolicy.saved(draft),localIdentity);}
            public String localIdentity(){return localIdentity;}
            public void save(Map<String, Object> values) {
                if(!OwnReceiverPolicy.controllerOnly(DefaultPlayerPolicy.saved(values),localIdentity)){
                String endpoint=String.valueOf(values.getOrDefault("receiverEndpoint","")).trim();
                if(!endpoint.isEmpty())net.markandrew.kiosk.music.receiver.ReceiverEndpoint.Companion.parse(endpoint);
                String name=String.valueOf(values.getOrDefault("receiverName","")).trim();
                if(name.length()>80||!name.equals(ReceiverNamePolicy.clean(name)))throw new IllegalArgumentException("Use a receiver name of up to 80 characters, or blank to follow Kiosk.");
                values.put("receiverEndpoint",endpoint);values.put("receiverName",name);
                }
                host.saveSettings(values);
                migrationWarning="";
                Runnable back=preferencesBack;preferencesBack=null;hideMenu(); configure(values);if(back!=null)back.run();
            }
        });
        if (access != null) access.clear();
        setupController.ownReceiver(this::ownReceiverId);
        if (showing) load(category, offset, query);
        receiverMain.post(() -> configureReceiver());
        if(showing)startRevertMonitor();
        receiverMain.removeCallbacks(receiverPlaybackPoll);receiverMain.post(receiverPlaybackPoll);
        if(running&&monitorAccess!=null)createIntercomController();
    }

    static boolean playbackSettingsOnly(Map<String,Object> old,Map<String,Object> next){
        Map<String,Object> a=new java.util.HashMap<>(old),b=new java.util.HashMap<>(next);
        for(String key:java.util.Arrays.asList("hardwareGroupVolume","idleRevertSeconds","duckPercent","preventScreensaverWhilePlaying")){a.remove(key);b.remove(key);}
        return a.equals(b);
    }

    private long receiverNameEpoch;
    private volatile String receiverNaming = "Player name: awaiting Kiosk";
    private volatile String receiverDeviceName="This kiosk";
    private volatile String localIdentity="";
    private void createIntercomController(){
        if(intercom!=null)return;
        intercom=new IntercomController(host,()->addon,()->playerEpoch+":"+touchEpoch+":"+(players==null?"":players.selectedId()),this::ownReceiverId,text->receiverMain.post(()->{
            lastCallDiagnostic=text;if(running){status(text,false);if(nowPlayingController!=null)nowPlayingController.notice(text);}
        }),()->receiverMain.post(this::restoreCallScreen));
    }
    private boolean callNavigationHeld(){return IntercomPlayback.call(intercomState)||callScreenPending||intercom!=null&&intercom.busy();}
    private void intercomState(String state){
        boolean starting=IntercomPlayback.call(state)&&!IntercomPlayback.call(intercomState);
        intercomState=state;
        if(starting){
            callScreenPending=showing&&nowPlayingSurface&&nowPlayingController!=null&&nowPlayingController.visible()&&!nowPlayingController.hasPanel();
            callScreenPlayer=players==null?"":players.selectedId();callScreenTouch=touchEpoch;
        }
        idleRevert.reset();
        if(intercom!=null)intercom.state(state);
        if("idle".equals(state))restoreCallScreen();
    }
    private void restoreCallScreen(){
        if(!callScreenPending||!"idle".equals(intercomState)||intercom!=null&&intercom.busy())return;
        callScreenPending=false;
        if(!running||!showing||players==null||touchEpoch!=callScreenTouch||!callScreenPlayer.equals(players.selectedId())||sending.get()||playerPanel!=null)return;
        showNativePlayer();idleRevert.reset();
    }
    private boolean controllerOnly(){return OwnReceiverPolicy.controllerOnly(DefaultPlayerPolicy.saved(settings),localIdentity);}
    private boolean localReceiverEnabled(){return Boolean.TRUE.equals(settings.get("receiverEnabled"))&&!controllerOnly();}
    private boolean legacyHardwareVolumeAllowed(){
        // Unknown/stopped/buffering local state must never reactivate the old 5% path.
        if(Boolean.TRUE.equals(settings.get("receiverEnabled"))&&(localIdentity.isEmpty()||!controllerOnly()))return false;
        if(localVolume!=null&&localVolume.ownsMaster())return false;
        return localIdentity.isEmpty()||players==null||!localIdentity.equals(players.selectedId());
    }
    private void configureReceiver() {
        if (!running) return;
        if (receiverManager == null) {
            if (!Boolean.TRUE.equals(settings.get("receiverEnabled"))) return;
            // SDK 1 supplies a Context only through a native view factory. Transparent,
            // non-touchable bootstrap is removed immediately; Library is never opened.
            host.showOverlay("receiver_bootstrap", OverlaySpec.at(OverlaySpec.TOP_LEFT).size(1, 1).touchable(false).closeOnBack(false), new OverlayFactory() {
                public View create(Context context, KsTheme theme) {
                    View empty = new View(context);
                    if (running && receiverManager == null) initializeReceiver(context);
                    receiverMain.post(() -> { if (running) host.hideOverlay("receiver_bootstrap"); });
                    return empty;
                }
            });
            return;
        }
        final long ticket = ++receiverNameEpoch;
        final Map<String, Object> desired = new java.util.HashMap<>(settings);
        final boolean enable = localReceiverEnabled();
        final String custom = ReceiverNamePolicy.override(desired.get("receiverName"));
        final java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<String> apply = kioskName -> receiverMain.post(() -> {
            if (!running || receiverNameEpoch != ticket || receiverManager == null) return;
            if(kioskName.isEmpty()&&answered.get())return;
            if(!kioskName.isEmpty()&&kioskNames!=null)kioskNames.remember(kioskName);
            String known=!kioskName.isEmpty()?kioskName:kioskNames==null?"":kioskNames.read();
            if(enable&&custom.isEmpty()&&known.isEmpty()){receiverNaming="Player name: waiting for Kiosk device name";return;}
            answered.set(true);
            String name = ReceiverNamePolicy.resolve(custom, known);
            if(!known.isEmpty())receiverDeviceName=known;
            receiverNaming = !custom.isEmpty() ? "Player name: custom override" : kioskName.isEmpty()
                ? "Player name: cached Kiosk name" : "Player name: follows Kiosk";
            receiverManager.configure(enable, String.valueOf(desired.getOrDefault("receiverEndpoint", "")), name,
                String.valueOf(desired.getOrDefault("receiverServer", "")));
        });
        if (!enable || !custom.isEmpty()) { apply.accept(""); return; }
        receiverNaming = "Player name: reading Kiosk device name…";
        receiverMain.postDelayed(() -> apply.accept(""), 5000);
        KioskNameAccess.read(host, apply);
        for(long delay:new long[]{5000,15000,30000})receiverMain.postDelayed(()->{
            if(running&&receiverNameEpoch==ticket&&!answered.get())KioskNameAccess.read(host,apply);
        },delay);
    }
    private void initializeReceiver(Context context) {
        try{android.media.AudioManager audio=(android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int steps=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);nativeVolumeStep=steps>0?Math.max(1,(int)Math.round(100d/steps)):0;}catch(RuntimeException ignored){nativeVolumeStep=0;}
        kioskNames=new KioskNameCache(context);
        receiverManager = new net.markandrew.kiosk.music.receiver.ReceiverManager(context, key -> {
            Map<String, Object> next = new java.util.HashMap<>(settings); next.put("receiverServer", key);
            host.saveSettings(next); settings = next;
        });
        receiver = receiverManager.getReceiver();
        initializeVolumeLink();
        volumeBootstrap=new ReceiverVolumeBootstrap(host,monitorWorker);
        receiverManager.prepareVolume((endpoint,ready)->{
            final net.markandrew.kiosk.music.receiver.PluginReceiver local=receiver;
            final long requested=receiverNameEpoch;
            final String app=addon;
            String trusted="";
            try{trusted=local.trustedVolumeServer();}catch(RuntimeException ignored){}
            final String server=trusted;
            if(app.isEmpty()||server.isEmpty()||localIdentity.isEmpty()){ready.run();return;}
            final java.util.concurrent.atomic.AtomicBoolean answered=new java.util.concurrent.atomic.AtomicBoolean();
            BooleanSupplier current=()->running&&receiver==local&&requested==receiverNameEpoch&&localReceiverEnabled()&&app.equals(addon)&&!answered.get();
            receiverMain.postDelayed(()->{if(answered.compareAndSet(false,true))ready.run();},11000);
            volumeBootstrap.read(app,localIdentity,current,level->receiverMain.post(()->{
                if(!current.getAsBoolean()||!answered.compareAndSet(false,true))return;
                if(level!=null)local.seedMusicVolume(endpoint,server,level);
                ready.run();
            }));
        });
        try{localIdentity=receiver.persistentClientId();}catch(RuntimeException error){localIdentity="";receiverNaming="Local identity unavailable; receiver will not start for a saved external default.";}
        receiver.duckPercent(AudioGainPolicy.configuredPercent(settings.get("duckPercent")));
        for(String source:voiceActivity)receiver.voiceActivity(source,true);
        receiverMain.removeCallbacks(receiverPlaybackPoll);receiverMain.post(receiverPlaybackPoll);
        registration = new ReceiverRegistration(host, () -> receiver, () -> addon,
            () -> running && localReceiverEnabled());
        registration.start(); configureReceiver();
    }

    @Override public synchronized void execute(String command, Map<String, Object> arguments) {
        if (!running) return;
        if("toggle".equals(command)){if(showing){hideBrowser();return;}command="open";}
        if ("close".equals(command)) { hideBrowser(); return; }
        if (!"open".equals(command)) return;
        if (!showing) {
            showing = true;
            try {
                host.showOverlay("browser", MusicOverlay.screen(), new OverlayFactory() {
                    @Override public View create(Context context, KsTheme theme) {
                        if (receiverManager == null) initializeReceiver(context);
                        BrowserView created = new BrowserView(context, theme, MusicAssistantPlugin.this);
                        view = created; created.setCategory(category); created.render(page, true);
                        startLibraryReturn();
                        String visible=created.visibleCategory(category);if(!visible.equals(category))receiverMain.post(()->{if(running&&showing&&view==created)browse(visible,0);});
                        return created;
                    }
                    @Override public void onThemeChanged(View target, KsTheme theme) { ((BrowserView) target).style(theme); }
                    @Override public void onDestroy(View target) { if (view == target) view = null; }
                });
            } catch (RuntimeException | LinkageError ignored) {
                showing = false; status("Native overlays are unavailable on this Kiosk build.", true); return;
            }
        }
        pendingOpenRoute=true;
        startRevertMonitor();
        if(addon.isEmpty())load(category, offset, query);else players.route(false);
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if(remoteSetup!=null&&remoteSetup.event(event,payload))return;
        if("ks.device.volume".equals(event)){
            receiverMain.post(()->{if(running&&localVolume!=null){localVolume.deviceChanged();}});return;
        }
        if("ks.device.key".equals(event)){
            Map<String,Object> key=new java.util.HashMap<>(payload);
            receiverMain.post(()->{
                HardwareVolumeController controller=hardwareVolume;if(running&&controller!=null&&legacyHardwareVolumeAllowed())controller.key(key);
                int direction=HardwareVolumePolicy.direction(key,System.currentTimeMillis());
                if(direction!=0&&running&&Boolean.TRUE.equals(settings.get("hardwareGroupVolume"))&&localVolume!=null&&!voiceVolumeHeld){
                    LocalVolumeLink link=localVolume;long stamp=link.edgeStamp(direction);
                    receiverMain.postDelayed(()->{if(running&&localVolume==link&&!voiceVolumeHeld)link.edgeKey(direction,nativeVolumeStep,stamp);},150);
                }
            });return;
        }
        if("ks.voice.state".equals(event)||"ks.intercom.state".equals(event)||"ks.voice.interaction".equals(event)){
            receiverMain.post(()->{
                if(!running)return;
                boolean interactionEvent="ks.voice.interaction".equals(event);
                String origin=String.valueOf(payload.get("source"));
                if(!java.util.Arrays.asList("page","sendspin","command").contains(origin))origin="other";
                String source="ks.intercom.state".equals(event)?"intercom":interactionEvent?"interaction:"+origin:"voice";
                voiceVersions.put(source,voiceVersions.getOrDefault(source,0L)+1);
                updateVoice(source,interactionEvent?Boolean.TRUE.equals(payload.get("active")):voiceBusy(source,payload.get("state")));
                if("intercom".equals(source))intercomState(String.valueOf(payload.get("state")));
            });
        }
        if ("overlay.closed".equals(event) && "now_playing".equals(payload.get("key")) && nowPlayingController != null) {
            nowPlayingController.close(); nowPlayingController = null;
        }
        if ("overlay.closed".equals(event) && "browser".equals(payload.get("key"))) stopBrowsing();
        if ("overlay.closed".equals(event) && "item_actions".equals(payload.get("key"))) {
            if (setupController != null) setupController.cancel();
            if (features != null) features.cancel();
        }
    }
    @Override public synchronized void browse(String category, int offset) {
        if (sending.get()) return;
        pendingOpenRoute=false;searchOrigin=null;searchHistory.clear();
        if(view!=null)view.clearSearch();
        if (features != null) features.cancel();
        hideMenu(); restorePosition = 0;
        if (!"detail".equals(category)) { detail = null; history.clear(); }
        load(category, Math.max(0, offset), "");
    }
    @Override public synchronized void search(String query) {
        if (sending.get()) return;
        pendingOpenRoute=false;
        if(searchOrigin==null&&!"search".equals(category)){
            searchOrigin=new Location(category,this.query,detail,offset,view==null?0:view.scrollPosition(),page,displaySession);
            searchHistory.clear();searchHistory.addAll(history);
        }
        if (features != null) features.cancel();
        hideMenu(); detail = null; restorePosition = 0; load("search", 0, query);
    }
    @Override public synchronized void clearSearch() {
        if(sending.get())return;
        pendingOpenRoute=false;
        if(view!=null)view.clearSearch();
        if(!"search".equals(category)&&searchOrigin==null)return;
        if(features!=null)features.cancel();hideMenu();
        Location previous=searchOrigin;searchOrigin=null;
        if(previous==null){browse("albums",0);return;}
        history.clear();history.addAll(searchHistory);searchHistory.clear();
        detail=previous.detail;restorePosition=previous.scroll;
        invalidate();final long ticket=generation.get();
        category=previous.category;offset=previous.offset;query=previous.query;appendRequest=false;
        access.ensure(addon,()->running&&showing&&generation.get()==ticket,session->{
            if(session==previous.session){displaySession=session;publish(previous.page,ticket);}
            else load(previous.category,previous.offset,previous.query);
        },error->fail("Library",error,ticket));
    }
    @Override public synchronized void retry() {
        detailCache = null; detailCacheSession = null;
        if (page.error && appendRequest && !page.rows.isEmpty()) load(category, page.offset + IngressPolicy.PAGE_SIZE, query, true);
        else load(category, 0, query);
    }
    @Override public synchronized void more() {
        if (!page.next || page.busy || sending.get()) return;
        restorePosition = view == null ? 0 : view.scrollPosition();
        load(category, page.offset + IngressPolicy.PAGE_SIZE, query, true);
    }
    @Override public synchronized void close() {
        if(!running||!showing||sending.get())return;
        interaction();pendingOpenRoute=false;hideMenu();
        if(addon.isEmpty())navigateNowPlaying("home");else if(players!=null)players.route(true);
    }
    private synchronized void hideBrowser() {
        playbackLaunch.consume();
        stopRevertMonitor();
        pendingOpenRoute=false;
        hideMenu();
        stopBrowsing();
        if (running) try { host.hideOverlay("browser"); } catch (RuntimeException | LinkageError ignored) { /* Host may already be revoked. */ }
    }

    @Override public synchronized void open(BrowserItem item) {
        if (item == null || !page.rows.contains(item) || !item.expandable() || sending.get()) return;
        if (features != null) features.cancel();
        hideMenu();
        BrowserView target = view;
        if (history.size() == 20) history.removeFirst();
        history.addLast(new Location(category, query, detail, offset, target == null ? 0 : target.scrollPosition(), page, displaySession));
        restorePosition = 0;
        detail = item; load("detail", 0, "");
    }

    @Override public synchronized void back() {
        if (sending.get()) return;
        if (features != null) features.cancel();
        hideMenu();
        if (history.isEmpty()) { browse("albums", 0); return; }
        Location previous = history.removeLast();
        detail = previous.detail; restorePosition = previous.scroll;
        invalidate(); final long ticket = generation.get();
        category = previous.category; offset = previous.offset; query = previous.query; appendRequest = false;
        access.ensure(addon, () -> running && showing && generation.get() == ticket, session -> {
            if (session == previous.session) { displaySession = session; publish(previous.page, ticket); }
            else load(previous.category, 0, previous.query);
        }, error -> fail("Back", error, ticket));
    }

    @Override public synchronized void menu(BrowserItem item) {
        if (!running || !showing || item == null || sending.get() || !(page.rows.contains(item) || page.parent == item)) return;
        features.item(item, page.parent, page.rows, detail != null && detail.uri.equals(detailCacheUri) ? detailCache : null);
    }
    private android.widget.PopupMenu itemMenu;
    private long itemMenuRequest;
    @Override public synchronized void menu(BrowserItem item,View anchor){
        anchoredItemMenu(item,anchor,false);
    }
    @Override public synchronized void playbackMenu(BrowserItem item,View anchor){anchoredItemMenu(item,anchor,true);}
    private void anchoredItemMenu(BrowserItem item,View anchor,boolean playback){
        if(!running||!showing||sending.get()||item==null||anchor==null||!anchor.isAttachedToWindow()||!(page.rows.contains(item)||page.parent==item))return;
        if(itemMenu!=null)itemMenu.dismiss();
        final BrowserView target=view;final long ticket=generation.get(),request=++itemMenuRequest;final String player=players.selectedId(),server=addon;
        if(target==null||features==null)return;
        java.util.function.BooleanSupplier current=()->running&&showing&&!sending.get()&&view==target&&generation.get()==ticket&&request==itemMenuRequest&&players.selectedId().equals(player)&&addon.equals(server)&&(page.rows.contains(item)||page.parent==item);
        if(playback){target.anchoredActions(anchor,features.playbackActions(item),current,true);return;}
        final java.util.List<FeatureController.ItemAction> base=features.itemActions(item,page.parent,page.rows,detail!=null&&detail.uri.equals(detailCacheUri)?detailCache:null);
        java.util.List<FeatureController.ItemAction> initial=FeatureController.mergeLibraryActions(base,java.util.Collections.emptyList());
        if(!item.type.equals("podcast_episode"))initial.add(new FeatureController.ItemAction("Loading library actions…",null));
        target.anchoredActions(anchor,initial,current,false);
        features.resolveItemActions(item,page.parent,page.rows,detail!=null&&detail.uri.equals(detailCacheUri)?detailCache:null,current,actions->target.post(()->{
            if(current.getAsBoolean()&&anchor.isAttachedToWindow()&&target.itemMenuShowing())target.anchoredActions(anchor,actions,current,false);
        }),error->target.post(()->{
            if(current.getAsBoolean()&&anchor.isAttachedToWindow()&&target.itemMenuShowing()){
                java.util.List<FeatureController.ItemAction> retry=FeatureController.mergeLibraryActions(base,java.util.Collections.emptyList());retry.add(new FeatureController.ItemAction(error,()->anchoredItemMenu(item,anchor,false)));target.anchoredActions(anchor,retry,current,false);
            }
        }));
    }

    private void hideMenu() {
        itemMenuRequest++;
        if(view!=null)view.dismissItemMenu();
        if(itemMenu!=null){itemMenu.dismiss();itemMenu=null;}
        if (setupController != null) setupController.cancel();
        playerPanel = null;
        if (nowPlayingController != null) nowPlayingController.clearPanel();
        if (running) try { host.hideOverlay("item_actions"); }
        catch (RuntimeException | LinkageError ignored) { /* Host stopped or overlay not supported. */ }
    }
    private boolean nowPlayingSurface;
    private String musicPanelMode = "full";
    private void dismissMusicPanel() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { receiverMain.post(this::dismissMusicPanel); return; }
        if (features != null) features.cancel();
        if (players != null) players.cancel();
        hideMenu();
        if (nowPlayingSurface && nowPlayingController != null && nowPlayingController.visible() && running && showing
            && !nowPlayingController.boundTo(players.selectedId())) showNativePlayer();
    }
    private synchronized void navigateNowPlaying(String destination) {
        if (!running || !showing || sending.get()) return;
        if ("home".equals(destination)) {
            hideBrowser();
            return;
        }
        if ("library".equals(destination)) {
            nowPlayingSurface = false; musicPanelMode = "full";
            if (features != null) features.cancel();
            hideMenu();
            if (nowPlayingController != null) { nowPlayingController.dismiss(); nowPlayingController = null; }
            startLibraryReturn();
            return;
        }
        musicPanelMode = "lyrics".equals(destination) ? "center" : "right";
        switch (destination) {
            case "players": players.players(false); break;
            case "queue": features.queue(100); break;
            case "lyrics": features.lyrics(); break;
            case "equaliser": features.equaliser(); break;
            case "transfer": players.players(true); break;
            case "group": features.group(); break;
            default: break;
        }
    }
    private void navigationError(String message) {
        if(nowPlayingController!=null)nowPlayingController.notice(message);
        else if(view!=null)view.message(message);
    }

    @Override public void artwork(BrowserItem item, BooleanSupplier visible, java.util.function.Consumer<android.graphics.Bitmap> done) {
        IngressAccess.Session session = displaySession;
        long ticket = generation.get();
        if (session != null) artwork.load(session, item.imagePath,
            () -> running && showing && generation.get() == ticket && displaySession == session && visible.getAsBoolean(), done);
    }

    @Override public synchronized void play(BrowserItem item, boolean enqueue) {
        playOption(item, enqueue ? "add" : "play");
    }

    @Override public synchronized void playOption(BrowserItem item, String option) {
        if (!running || !showing || sending.get() || item == null || !(page.rows.contains(item) || page.parent == item) || !item.playable()) return;
        hideMenu(); players.play(item, option);
    }
    private synchronized void playAdvanced(BrowserItem item, String option, boolean shuffle, String start) {
        if (!running || !showing || sending.get() || item == null || !(page.rows.contains(item) || page.parent == item) || !item.playable()) return;
        if (!start.isEmpty() && (page.parent != item || page.rows.stream().noneMatch(row -> row.id.equals(start)))) return;
        hideMenu(); players.play(item, option, shuffle, start);
    }
    @Override public synchronized void createPlaylist() {
        if (!running || !showing || sending.get()) return;
        pendingOpenRoute=false;features.createPlaylist(new JSONArray());
    }
    @Override public synchronized void joinGroup(){if(!running||!showing||sending.get())return;pendingOpenRoute=false;features.joinLocalGroup();}
    @Override public synchronized void playerSettings(){if(features!=null)features.playerSettings();}
    @Override public synchronized void librarySettings(){
        if(!running||!showing||sending.get())return;
        showPanel("Library settings",p->{
            p.closeAction("Close",this::hideMenu);
            try{
                LibraryTabs draft=new LibraryTabs(p.getContext());
                for(String key:LibraryTabs.KEYS)p.toggle(LibraryTabs.label(key),draft.visible.contains(key),visible->{if(visible)draft.visible.add(key);else draft.visible.remove(key);});
                p.formActions("Save",()->{
                    try{draft.commit();if(view!=null)view.libraryTabs(draft.visible);String next=view==null?category:view.visibleCategory(category);hideMenu();if(!next.equals(category))browse(next,0);}
                    catch(Exception error){p.message(error.getMessage()==null?"Library settings could not be saved. Reopen to check before retrying.":error.getMessage());}
                },this::hideMenu);
            }catch(Exception error){p.message("Library settings could not be read. Nothing was changed.");}
        },()->true,true);
    }
    @Override public synchronized void filters() {
        if (!running || !showing || sending.get()) return;
        showPanel("Library filter / sort", p -> {
            p.closeAction("Cancel",this::hideMenu);
            p.simplePicker("Sort", java.util.Arrays.asList(new DiscoveryPolicy.Choice("name", "Name"),
                new DiscoveryPolicy.Choice("timestamp_added_desc", "Recently added"), new DiscoveryPolicy.Choice("last_played_desc", "Recently played")), libraryOrder,
                id->applyLibraryFilters("",id,favoritesOnly));
            p.simplePicker("Favourites", java.util.Arrays.asList(new DiscoveryPolicy.Choice("all", "All items"), new DiscoveryPolicy.Choice("favorites", "Favourites only")), favoritesOnly ? "favorites" : "all",
                id->applyLibraryFilters("",libraryOrder,id.equals("favorites")));
            p.note("Selections update the library and close this panel. Use the main Search field for text search.");
            p.add("Clear filters", () -> applyLibraryFilters("","name",false));
            p.add("Cancel", this::hideMenu);
        },()->true,true);
    }
    private synchronized void applyLibraryFilters(String text,String order,boolean favorites){
        if(!running||!showing||sending.get())return;
        libraryFilter=text;libraryOrder=order;favoritesOnly=favorites;
        TouchPanel panel=playerPanel;
        if(panel!=null){
            View focus=panel.findFocus();if(focus!=null)focus.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)panel.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(panel.getWindowToken(),0);
        }
        hideMenu();browse(category.equals("detail")||category.equals("search")?"albums":category,0);
    }
    private void resetLibraryFilters(){libraryFilter="";libraryOrder="name";favoritesOnly=false;}
    @Override public void players() { if (players != null) players.players(false); }
    @Override public synchronized void setup() {
        setup(null);
    }
    private Runnable preferencesBack;
    private synchronized void setup(Runnable back){
        if (!running || !showing || sending.get()) return;
        preferencesBack=back;
        invalidate(); setupController.open();
    }
    private void showPanel(String title, java.util.function.Consumer<TouchPanel> populate) {
        showPanel(title, populate, () -> true);
    }
    private void showPanel(String title, java.util.function.Consumer<TouchPanel> populate, BooleanSupplier current) {
        showPanel(title,populate,current,false);
    }
    private void showPanel(String title,java.util.function.Consumer<TouchPanel> populate,BooleanSupplier current,boolean popup){
        final long epoch = playerEpoch;
        final NowPlayingController playback = nowPlayingController;
        final boolean attached = nowPlayingSurface && playback != null && playback.visible();
        final String mode = musicPanelMode;
        receiverMain.post(() -> {
            if (!running || !showing || playerEpoch != epoch || !current.getAsBoolean()) return;
            if (attached) {
                if (nowPlayingController != playback || !playback.visible()) return;
                playerPanel = playback.panel(title, popup||"center".equals(mode), populate, this::dismissMusicPanel);
                return;
            }
            host.showOverlay("item_actions", MusicOverlay.screen(), new OverlayFactory() {
                private TouchPanel owned;
                public View create(Context context, KsTheme theme) {
                    TouchPanel panel = new TouchPanel(context, theme, title);panel.interaction(MusicAssistantPlugin.this::interaction); owned = panel; playerPanel = panel;
                    panel.compact(MusicAssistantPlugin.this::dismissMusicPanel);
                    populate.accept(panel);
                    if(!panel.expandedEq()){
                        MusicPanelSurface surface=new MusicPanelSurface(context,theme,panel,popup||"center".equals(mode),MusicAssistantPlugin.this::dismissMusicPanel);
                        surface.setContentDescription((popup?"Popup: ":"Panel: ")+title);return surface;
                    }
                    panel.setBackground(NativeStyle.card(theme,false));panel.setClickable(true);
                    android.widget.FrameLayout scrim=new android.widget.FrameLayout(context){
                        @Override protected void onMeasure(int width,int height){
                            android.widget.FrameLayout.LayoutParams slot=(android.widget.FrameLayout.LayoutParams)panel.getLayoutParams();
                             slot.width=Math.max(1,Math.min(theme.px(panel.expandedEq()?900:560),View.MeasureSpec.getSize(width)-theme.px(32)));
                             slot.height=Math.max(1,Math.min(theme.px(panel.expandedEq()?640:480),View.MeasureSpec.getSize(height)-theme.px(32)));
                            super.onMeasure(width,height);
                        }
                    };
                    scrim.setBackgroundColor(0x77000000);scrim.setOnClickListener(v->dismissMusicPanel());scrim.setContentDescription("Popup: "+title);
                    scrim.addView(panel,new android.widget.FrameLayout.LayoutParams(1,1,android.view.Gravity.CENTER));return scrim;
                }
                public void onDestroy(View target) { if (playerPanel == owned) playerPanel = null; }
                public void onThemeChanged(View target, KsTheme theme) { if (features != null) features.cancel(); hideMenu(); }
            });
        });
    }

    @Override public void nowPlaying() {
        if (players != null) players.nowPlaying();
    }
    private void showNativePlayer() {
        if (!running || !showing) return;
        if(addon.isEmpty()){
            pendingOpenRoute=true;load(category,offset,query);return;
        }
        clearSearchOnPlayerExit();
        hideMenu();
        nowPlayingSurface = true; musicPanelMode = "right";
        try {
            if (nowPlayingController != null) nowPlayingController.dismiss();
            nowPlayingController = new NowPlayingController(host, access, addon, players.selectedId(),
                () -> running && showing, () -> players == null ? "" : players.selectedId());
            nowPlayingController.ownReceiver(this::ownReceiverId);
            nowPlayingController.diagnostics(text->lastPlaybackDiagnostic=text);
            nowPlayingController.deviceName(()->receiverDeviceName);
            nowPlayingController.navigation(this::navigateNowPlaying);
            nowPlayingController.interaction(this::interaction);
            nowPlayingController.navigationHeld(this::callNavigationHeld);
            nowPlayingController.show();
        } catch (RuntimeException | LinkageError ignored) { status("Plugin Now Playing unavailable", true); }
    }

    private void clearSearchOnPlayerExit(){
        if(searchOrigin==null&&!"search".equals(category))return;
        Location previous=searchOrigin;searchOrigin=null;
        invalidate();history.clear();history.addAll(searchHistory);searchHistory.clear();
        category=previous==null?"albums":previous.category;query="";offset=previous==null?0:previous.offset;
        detail=previous==null?null:previous.detail;restorePosition=previous==null?0:previous.scroll;appendRequest=false;
        if(view!=null)view.clearSearch();
        publish(previous==null?BrowserPage.status("Library","",false,false):previous.page,generation.get());
        // No cached identity is restored: Library re-entry performs a fresh read.
    }

    @Override public synchronized void interaction() {
        callScreenPending=false;
        touchEpoch++;idleRevert.touch(android.os.SystemClock.elapsedRealtime());
    }
    @Override public void favorite(BrowserItem item,java.util.function.Consumer<Boolean> done,java.util.function.Consumer<String> fail){
        if(!running||!showing||features==null){fail.accept("Library is not available.");return;}
        features.favorite(item,done,fail);
    }
    private synchronized void startRevertMonitor() {
        stopRevertMonitor();
        if(running&&showing&&IdleRevert.seconds(settings.get("idleRevertSeconds"))>0)receiverMain.post(revertPoll);
    }
    private static boolean voiceBusy(String source,Object value){
        String state=String.valueOf(value);
        return "intercom".equals(source)?java.util.Arrays.asList("calling","ringing","in_call","broadcasting","listening").contains(state):
            java.util.Arrays.asList("listening","processing","responding").contains(state);
    }
    private void updateVoice(String source,boolean active){
        if(active)voiceActivity.add(source);else voiceActivity.remove(source);
        voiceVolumeHeld=!voiceActivity.isEmpty();
        if(receiver!=null)receiver.voiceActivity(source,active);
        if(localVolume!=null)localVolume.speech(voiceVolumeHeld);
    }
    private void initializeVolumeLink(){
        final net.markandrew.kiosk.music.receiver.PluginReceiver local=receiver;
        if(localGroupVolume!=null)localGroupVolume.close();
        local.clearGroupVolumeRoute();
        hardwareVolumeRoute="Awaiting a hardware volume change.";
        localGroupVolume=new LocalGroupVolume(host,()->addon,()->localIdentity,()->String.valueOf(local.state().get("serverId"))+":"+local.state().get("serial"),()->running&&receiver==local&&localReceiverEnabled()&&Boolean.TRUE.equals(settings.get("hardwareGroupVolume"))&&!voiceVolumeHeld&&"playing".equals(local.state().get("playback")));
        localVolume=new LocalVolumeLink(new LocalVolumeLink.Port(){
            public void gate(boolean mute){local.linkedVolumeGate(mute);}
            public boolean report(int level){return receiver==local&&running&&localReceiverEnabled()&&local.reportMasterVolume(level);}
            public boolean reportHardware(int level,boolean unmute){return receiver==local&&running&&localReceiverEnabled()&&local.reportMasterVolume(level,unmute);}
            public boolean groupHardwareEnabled(){return Boolean.TRUE.equals(settings.get("hardwareGroupVolume"));}
            public boolean cancelGroupHardware(){hardwareVolumeRoute="Group request cancelled for speech/call; inspect Group if a command was in flight.";return localGroupVolume!=null&&localGroupVolume.cancel();}
            public void groupRoute(String text){String evidence=localGroupVolume==null?"":localGroupVolume.memberEvidence();hardwareVolumeRoute=text+(evidence.isEmpty()?"":" "+evidence);}
            public void groupHardware(int previous,int actual,java.util.function.Consumer<Integer> done,java.util.function.Consumer<String> fail){
                hardwareVolumeRoute="Checking the local receiver's active group before submitting volume.";
                if(localGroupVolume==null){fail.accept("Group volume unavailable; no command sent.");return;}localGroupVolume.adjust(previous,actual,Math.max(1,nativeVolumeStep),done,fail);
            }
            public void later(Runnable task,long delay){receiverMain.postDelayed(task,delay);}
            public void error(String text){hardwareVolumeRoute=text;if(running){status(text,true);if(nowPlayingController!=null)nowPlayingController.notice(text);}}
            public void read(java.util.function.Consumer<Integer> done,java.util.function.Consumer<String> fail){
                if(!volumeEventsAvailable){fail.accept("Volume events unavailable.");return;}
                try{host.executeCommand("getVolume",Collections.emptyMap(),(ok,data,error)->receiverMain.post(()->{
                    if(!running)return;
                    if(ok&&data instanceof Number&&Double.isFinite(((Number)data).doubleValue())&&((Number)data).doubleValue()>=0&&((Number)data).doubleValue()<=100){done.accept((int)Math.round(((Number)data).doubleValue()));}else fail.accept("Master volume read failed.");
                }));}catch(RuntimeException|LinkageError error){fail.accept("Master volume read unavailable.");}
            }
            public void write(int level,Runnable done,java.util.function.Consumer<String> fail){
                Map<String,Object> args=new java.util.HashMap<>();args.put("percent",String.valueOf(level));args.put("channel","master");
                try{host.executeCommand("setVolume",args,(ok,data,error)->receiverMain.post(()->{if(!running)return;if(ok)done.run();else fail.accept("Master volume write was not confirmed.");}));}
                catch(RuntimeException|LinkageError error){fail.accept("Master volume control unavailable.");}
            }
        });
        local.observeVolume(()->{
            if(!running||receiver!=local||localVolume==null)return;
            Map<String,Object> state=local.state();
            String own=OwnReceiverPolicy.id(state,localReceiverEnabled(),"local","local");
            String token=own.isEmpty()||!Boolean.TRUE.equals(state.get("active"))?"":own+":"+state.get("serverId")+":"+state.get("serial");
            Object value=state.get("volume"),revision=state.get("volumeRevision");
            Integer level=value instanceof Number&&((Number)value).intValue()>=0?((Number)value).intValue():null;
            localVolume.observe(token,String.valueOf(state.get("playback")),level,revision instanceof Number?((Number)revision).longValue():-1,Boolean.TRUE.equals(state.get("muted")));
        });
        localVolume.speech(voiceVolumeHeld);
    }
    private void observeVoice(String event,String read,String source){
        try{
            host.subscribe(event);
            final long version=voiceVersions.getOrDefault(source,0L);
            host.executeCommand(read,Collections.emptyMap(),(ok,data,error)->receiverMain.post(()->{
                if(!running||voiceVersions.getOrDefault(source,0L)!=version||!ok||!(data instanceof Map))return;
                updateVoice(source,voiceBusy(source,((Map<?,?>)data).get("state")));
                if("intercom".equals(source))intercomState(String.valueOf(((Map<?,?>)data).get("state")));
            }));
        }catch(RuntimeException|LinkageError ignored){/* Audio focus still works on older hosts. */}
    }
    private synchronized void pollReceiverPlayback(){
        if(!running)return;
        receiverMain.postDelayed(receiverPlaybackPoll,1000);
        net.markandrew.kiosk.music.receiver.PluginReceiver local=receiver;
        if(local!=null)local.duckPercent(AudioGainPolicy.configuredPercent(settings.get("duckPercent")));
        final String selected=players==null?"":players.selectedId();
        if(!selected.equals(monitorSelection)){
            monitorSelection=selected;integrationEpoch++;monitorReading=false;checkingLaunch=false;nextMonitorRead=0;playbackLaunch.reset();
        }
        if(monitorReading||sending.get()||android.os.SystemClock.elapsedRealtime()<nextMonitorRead)return;
        final long ticket=integrationEpoch;
        final String instance=addon;
        final String own=local==null?"":OwnReceiverPolicy.id(local.state(),localReceiverEnabled(),"monitor","monitor");
        final String transport=local==null?"":String.valueOf(local.state().get("serverId"));
        final Object receiverSerial=local==null?null:local.state().get("serial");
        if(selected.isEmpty()&&own.isEmpty())return;
        monitorReading=true;nextMonitorRead=android.os.SystemClock.elapsedRealtime()+3000;
        BooleanSupplier current=()->running&&ticket==integrationEpoch&&players!=null&&selected.equals(players.selectedId());
        java.util.function.Consumer<String> resolved=app->{
            monitorAccess.ensure(app,current,session->{
                try{monitorWorker.submit(()->{
                    String state="",verifiedDefault="";
                    try{
                        if(selected.isEmpty()){
                            FeatureApi.require(local==receiver&&!transport.isEmpty()&&!"null".equals(transport),"Receiver transport identity is unavailable.");
                            monitorApi.verifyInitialReceiver(session,own);verifiedDefault=own;
                        }else monitorApi.identity(session);
                        String target=selected.isEmpty()?own:selected;
                        PlayerPolicy.Snapshot snapshot=monitorApi.playbackSnapshot(session,target);
                        PlayerPolicy.Player p=DefaultPlayerPolicy.selected(snapshot,target),leader=snapshot.leader(p.id);
                        if(leader!=null&&leader.available)state=leader.state;
                    }catch(Exception error){
                        if(error instanceof PairingDiagnostics.Failure&&(((PairingDiagnostics.Failure)error).http==401||((PairingDiagnostics.Failure)error).http==403)){monitorAccess.clear();verifiedDefault="";}
                        /* Failed reads never prevent the screensaver or imply idle. */
                    }
                    final String playback=state,defaultId=verifiedDefault;
                    Runnable complete=()->{
                        synchronized(MusicAssistantPlugin.this){
                        if(!current.getAsBoolean())return;
                        monitorReading=false;
                        if(addon.isEmpty()){addon=app;if(!selected.isEmpty())createPlayerController();}
                        if(!defaultId.isEmpty()&&DefaultPlayerPolicy.saved(settings).isEmpty()){
                             if(receiver!=local||!defaultId.equals(OwnReceiverPolicy.id(local.state(),localReceiverEnabled(),app,app))||!transport.equals(String.valueOf(local.state().get("serverId")))||!java.util.Objects.equals(receiverSerial,local.state().get("serial")))return;
                            Map<String,Object> values=new java.util.HashMap<>(settings);
                             values.put("defaultPlayer",defaultId);values.put("playerMode","Fixed default player");
                             values.put("addon",app);
                            // Preserve all other settings and do not restart/re-pair the receiver.
                            try{host.saveSettings(values);settings=values;createPlayerController();monitorSelection=defaultId;}catch(RuntimeException ignored){return;}
                        }
                        acceptMonitoredPlayback(selected.isEmpty()?defaultId:selected,playback,ticket);
                        }
                    };
                    receiverMain.post(()->{
                        if(!current.getAsBoolean())return;
                        if(defaultId.isEmpty()){complete.run();return;}
                        monitorAccess.ensure(app,current,latest->receiverMain.post(()->{
                            if(!current.getAsBoolean())return;
                            if(latest!=session){monitorReading=false;return;}complete.run();
                        }),error->receiverMain.post(()->{if(current.getAsBoolean())monitorReading=false;}));
                    });
                });}catch(RuntimeException ignored){receiverMain.post(()->{if(current.getAsBoolean())monitorReading=false;});}
            },error->receiverMain.post(()->{if(current.getAsBoolean())monitorReading=false;}));
        };
        if(!instance.isEmpty())resolved.accept(instance);
        else new DiscoveryAccess(host).resolveAddon("",current,resolved,error->receiverMain.post(()->{if(current.getAsBoolean())monitorReading=false;}));
    }
    private synchronized void acceptMonitoredPlayback(String target,String state,long ticket){
        if(!running||ticket!=integrationEpoch||target.isEmpty()||players==null||!target.equals(players.selectedId()))return;
        playbackConfirmedAt="playing".equals(state)?android.os.SystemClock.elapsedRealtime():-1;
        if(state.isEmpty())return; // Unknown is not a new playback transition.
        monitoredTarget=target;
        playbackLaunch.observe(target,"playing".equals(state));
        if(callNavigationHeld()){playbackLaunch.consume();return;}
        if("playing".equals(state)&&Boolean.TRUE.equals(settings.get("preventScreensaverWhilePlaying"))&&
            android.os.SystemClock.elapsedRealtime()-lastPostpone>=2000){
            lastPostpone=android.os.SystemClock.elapsedRealtime();
            try{host.executeCommand("postponeScreensaver",Collections.emptyMap(),(ok,data,error)->{});}catch(RuntimeException|LinkageError ignored){/* No fallback or saved native settings. */}
        }
        if(!playbackLaunch.pending()||checkingLaunch||sending.get()||!voiceActivity.isEmpty())return;
        if(nowPlayingController!=null&&nowPlayingController.visible()){playbackLaunch.consume();return;}
        checkingLaunch=true;
        try{host.executeCommand("isScreensaverActive",Collections.emptyMap(),(ok,data,error)->receiverMain.post(()->{
            if(!running||ticket!=integrationEpoch)return;
            checkingLaunch=false;
            if(!playbackLaunch.pending()||playbackConfirmedAt<0||android.os.SystemClock.elapsedRealtime()-playbackConfirmedAt>5000||!target.equals(monitoredTarget)||!target.equals(players.selectedId()))return;
            if(!voiceActivity.isEmpty()||sending.get())return;
            if(ok&&Boolean.FALSE.equals(data)){
                playbackLaunch.consume();
                if(showing)showNativePlayer();else execute("open",Collections.emptyMap());
            }
        }));}catch(RuntimeException|LinkageError ignored){checkingLaunch=false;playbackLaunch.consume();}
    }
    private synchronized void stopRevertMonitor() {
        receiverMain.removeCallbacks(revertPoll);revertEpoch++;revertReading=false;revertPlayer="";idleRevert.reset();
    }
    private synchronized void pollRevert() {
        if(!running||!showing||players==null)return;
        int seconds=IdleRevert.seconds(settings.get("idleRevertSeconds"));
        if(seconds==0){idleRevert.reset();return;}
        receiverMain.postDelayed(revertPoll,2000);
        String selected=players.selectedId();
        if(!selected.equals(revertPlayer)){revertEpoch++;revertReading=false;revertPlayer=selected;idleRevert.reset();}
        if(sending.get()||addon.isEmpty()||callNavigationHeld()){idleRevert.reset();return;}
        if(revertReading)return;
        revertReading=true;final long ticket=revertEpoch;final PlayerController owner=players;
        owner.readState(state->receiverMain.post(()->{
            if(!running||!showing||players!=owner||ticket!=revertEpoch||!selected.equals(owner.selectedId()))return;
            revertReading=false;
            if(sending.get()||callNavigationHeld()){idleRevert.reset();return;}
            if(idleRevert.observe(state,android.os.SystemClock.elapsedRealtime(),seconds)){
                final long touched=touchEpoch;
                owner.revertIfIdle(()->running&&showing&&players==owner&&ticket==revertEpoch&&touched==touchEpoch);
            }
        }));
    }
    private volatile String lastPlaybackDiagnostic="No playback command observed in this session.";
    private volatile String lastLyricsDiagnostic="No lyrics lookup observed in this session.";
    private volatile String lastJoinDiagnostic="No verified join observed in this session.";
    private void populateReceiverSettings(TouchPanel p,Map<String,Object> draft){
        if(OwnReceiverPolicy.controllerOnly(DefaultPlayerPolicy.saved(draft),localIdentity)){
            p.note("Local receiver inactive · controller-only");return;
        }
        p.draftInput("Receiver name · blank follows Kiosk",ReceiverNamePolicy.override(draft.get("receiverName")),name->draft.put("receiverName",name));
        p.toggle("Enable this kiosk's receiver",Boolean.TRUE.equals(draft.get("receiverEnabled")),enabled->draft.put("receiverEnabled",enabled));
    }
    private void populateReceiverDiagnostics(TouchPanel p,Map<String,Object> draft){
        p.note(receiver==null?"Native media volume range · receiver context unavailable":receiver.nativeVolumeDiagnostic());
        p.note("Hardware volume · "+(Boolean.TRUE.equals(settings.get("hardwareGroupVolume"))?"group option enabled · "+hardwareVolumeRoute:"individual receiver (group option off)"));
        if(!OwnReceiverPolicy.controllerOnly(DefaultPlayerPolicy.saved(draft),localIdentity))
            p.draftInput("Server override · blank discovers automatically",String.valueOf(draft.getOrDefault("receiverEndpoint","")),endpoint->draft.put("receiverEndpoint",endpoint));
    }
    private void populateConnectionResult(TouchPanel p,PlayerPolicy.Snapshot snapshot,Runnable back){
        final net.markandrew.kiosk.music.receiver.PluginReceiver local=receiver;
        boolean enabled=localReceiverEnabled();
        if(!enabled){p.note("This kiosk's receiver · disabled");return;}
        if(local==null){p.message("This kiosk's receiver is unavailable. Reopen Music Assistant.");return;}
        Map<String,Object> state=local.state();
        boolean connected=Boolean.TRUE.equals(state.get("connected")),paired=Boolean.TRUE.equals(state.get("paired")),active=Boolean.TRUE.equals(state.get("active"));
        String id=OwnReceiverPolicy.id(state,enabled,addon,addon);
        PlayerPolicy.Player player=snapshot.players.get(id);
        BooleanSupplier same=()->receiver==local&&java.util.Objects.equals(state.get("serial"),local.state().get("serial"))&&java.util.Objects.equals(state.get("serverId"),local.state().get("serverId"));
        p.note("Receiver connection · "+(connected?"OK":"offline"));
        p.note(local.nativeVolumeDiagnostic());
        p.note("Secure registration · "+(paired?"OK":"not ready"));
        p.note("Local player · "+(paired&&active&&player!=null&&player.available?"ready":"not ready"));
        p.note("Hardware volume · "+(Boolean.TRUE.equals(settings.get("hardwareGroupVolume"))?hardwareVolumeRoute:"individual receiver (group option off)"));
        if(!connected&&receiverManager!=null)p.add("Reconnect",()->{if(same.getAsBoolean())receiverManager.retry();else p.message("Receiver changed. Run Check connection again.");});
        else if(!paired&&registration!=null)p.add("Retry registration",()->showPanel("Retry registration?",confirm->{
            confirm.note("Check Music Assistant for an existing registration first. Continuing permits a new registration attempt using this kiosk's retained identity; pairing is not reset.");
            confirm.add("Retry registration",()->{if(same.getAsBoolean()){registration.retry();hideMenu();}else confirm.message("Receiver changed. Run Check connection again.");});
        }));
        if(paired&&active&&player!=null&&player.available&&localVolume!=null&&localVolume.needsRecovery())p.add("Restore volume link",()->{
            if(same.getAsBoolean()&&localVolume!=null)localVolume.recover();else p.message("Receiver changed. Run Check connection again.");
        });
        p.disclosure("Details",details->{
            details.note(lastCallDiagnostic);
            details.note(receiverDeviceName+" · "+(receiverManager==null?"Receiver unavailable":receiverManager.state().get("status")));
            if(registration!=null)details.note(registration.status());
            details.note(lastPlaybackDiagnostic);details.note(lastLyricsDiagnostic);details.note(lastJoinDiagnostic);
            details.add("Player details",()->features.visibility(String.valueOf(state.get("clientId")),back));
        });
    }

    private synchronized void load(String category, int offset, String query) {
        load(category, offset, query, false);
    }
    private synchronized void load(String category, int offset, String query, boolean append) {
        if (!running || !showing) return;
        if (sending.get()) return;
        if(view!=null){String visible=view.visibleCategory(category);if(!visible.equals(category)){load(visible,0,"",false);return;}}
        if (addon.isEmpty()) {
            invalidate(); final long ticket = generation.get();
            status("Discovering Music Assistant", false);
            new DiscoveryAccess(host).resolveAddon("", () -> running && showing && generation.get() == ticket, selected -> {
                synchronized (MusicAssistantPlugin.this) {
                    if (!running || !showing || generation.get() != ticket) return;
                    addon = selected; createPlayerController();if(DefaultPlayerPolicy.saved(settings).isEmpty()&&!Boolean.TRUE.equals(settings.get("receiverEnabled")))players.chooseDefault();else load(category, offset, query);
                }
            }, error -> fail("Player", error + " Open Player, then Plugin preferences.", ticket));
            return;
        }
        if (!(category.equals("albums") || category.equals("playlists") || category.equals("artists") || category.equals("tracks")
            || category.equals("radio") || category.equals("podcasts") || category.equals("audiobooks") || category.equals("search") || category.equals("detail"))) return;
        if (offset > 10000) return;
        final BrowserPage retained = page;
        appendRequest = append;
        this.category = category; this.offset = offset;
        this.query = query == null ? "" : query.trim().substring(0, Math.min(query.trim().length(), 200));
        invalidate(append);
        long ticket = generation.get();
        BooleanSupplier current = () -> running && showing && generation.get() == ticket;
        final BrowserItem requestedDetail = detail;
        String heading = category.equals("detail") && requestedDetail != null ? requestedDetail.name
            : category.equals("search") ? "Search" : "Library";
        if (append) publish(new BrowserPage(heading, "Loading more…", retained.rows, true, retained.offset, true, false, retained.parent), ticket);
        else publish(new BrowserPage(heading,"Loading…",retained.rows,retained.next,retained.offset,true,false,retained.parent),ticket);
        final String requestedAddon = addon, requestedCategory = category, requestedQuery = this.query;
        final String filter = libraryFilter, order = libraryOrder; final boolean favorite = favoritesOnly;
        access.ensure(requestedAddon, current, session -> {
            if (!current.getAsBoolean()) return;
            try {
                request = worker.submit(() -> {
                    if (!current.getAsBoolean()) return;
                    try {
                        api.identity(session);
                        if (!current.getAsBoolean()) return;
                         JSONObject args = new JSONObject();
                         BrowserItem resolvedDetail = requestedDetail;
                        final String command;
                        if (requestedCategory.equals("search")) {
                            command = "music/search";
                             args.put("search_query", requestedQuery); args.put("limit", 25);
                            args.put("media_types", new JSONArray(new String[] {"album", "playlist", "artist", "radio", "track", "podcast", "audiobook"}));
                         } else if (requestedCategory.equals("detail") && requestedDetail != null) {
                             boolean cached = detailCache != null && detailCacheSession == session && detailCacheUri.equals(requestedDetail.uri);
                             if (cached) resolvedDetail = detailCacheParent;
                             else {
                                 Object raw = api.command(session, "music/item_by_uri", new JSONObject().put("uri", requestedDetail.uri));
                                 if (!(raw instanceof JSONObject)) throw new IllegalArgumentException();
                                 resolvedDetail = new BrowserItem((JSONObject) raw);
                                 if (!resolvedDetail.type.equals(requestedDetail.type) || resolvedDetail.uri.isEmpty()) throw new IllegalArgumentException();
                             }
                            switch (requestedDetail.type) {
                                case "album": command = "music/albums/album_tracks"; break;
                                case "playlist": command = "music/playlists/playlist_tracks"; break;
                                case "artist": command = "music/artists/artist_albums"; break;
                                case "podcast": command = "music/podcasts/podcast_episodes"; break;
                                default: throw new IllegalArgumentException();
                            }
                             args.put("item_id", resolvedDetail.id); args.put("provider_instance_id_or_domain", resolvedDetail.provider);
                             if (requestedDetail.type.equals("playlist")) args.put("force_refresh", true);
                            // These APIs do not share the library paging contract. Bound presentation locally.
                        } else {
                            String controller = requestedCategory.equals("radio") ? "radios" : requestedCategory;
                            command = "music/" + controller + "/library_items";
                            args.put("limit", IngressPolicy.PAGE_SIZE); args.put("offset", offset);
                             args.put("order_by", order); args.put("summary", false);
                             if (!filter.isEmpty()) args.put("search", filter);
                             if (favorite) args.put("favorite", true);
                        }
                         Object response;
                         if (requestedCategory.equals("detail") && detailCache != null && detailCacheSession == session && detailCacheUri.equals(requestedDetail.uri)) response = detailCache;
                         else response = api.command(session, command, args);
                        BrowserPage result;
                        if (requestedCategory.equals("search")) result = BrowserPage.search(response);
                        else if (requestedCategory.equals("detail")) {
                            if (!(response instanceof JSONArray)) throw new IllegalArgumentException();
                             JSONArray all = (JSONArray) response, slice = new JSONArray();
                             if (current.getAsBoolean()) { detailCache = all; detailCacheUri = requestedDetail.uri; detailCacheSession = session; detailCacheParent = resolvedDetail; }
                            for (int i = offset; i < Math.min(all.length(), offset + IngressPolicy.PAGE_SIZE); i++) slice.put(all.get(i));
                            BrowserPage values = BrowserPage.detail(heading, slice, offset);
                             result = new BrowserPage(resolvedDetail.name, values.message, values.rows, all.length() > offset + slice.length(), offset, false, false, resolvedDetail);
                         } else result = BrowserPage.library(heading, response, offset);
                         if (append) {
                             result = retained.append(result);
                         }
                         final BrowserPage completed = result;
                        // Verify the cached session still belongs to the same configured HA account before presenting data.
                        access.ensure(requestedAddon, current, latest -> {
                            if (latest == session) {
                                displaySession = session;
                                 publish(completed, ticket);
                            }
                            else fail(heading, "HA session changed. Retry the read.", ticket);
                        }, error -> fail(heading, error, ticket));
                    } catch (Exception ignored) {
                        if (current.getAsBoolean()) {
                            access.clear();
                            fail(heading, "Music Assistant read failed. Check HA app permissions, trusted HTTPS and the Ingress API, then Retry. No playback was requested.", ticket);
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) { /* Stopping or disabled. */ }
        }, error -> fail(heading, error, ticket));
    }

    private void fail(String heading, String message, long ticket) {
        BrowserPage existing = page;
        if (appendRequest && !existing.rows.isEmpty()) publish(new BrowserPage(heading, message, existing.rows, true, existing.offset, false, true, existing.parent), ticket);
        else publish(BrowserPage.status(heading, message, false, true), ticket);
    }
    private synchronized void publish(BrowserPage value, long ticket) {
        if (!running || !showing || generation.get() != ticket) return;
        page = value;
        BrowserView target = view;
        final int position = restorePosition;
        if (!value.busy && !value.error) restorePosition = 0;
        if (target != null) target.post(() -> {
            if (running && showing && generation.get() == ticket && view == target) {
                target.setCategory(category); target.render(value, !appendRequest);
                if (!value.busy && !value.error && position > 0) target.restoreScroll(position);
                if(pendingOpenRoute&&!value.busy&&!value.error&&!sending.get()){
                    pendingOpenRoute=false;if(players!=null)players.route(false);
                }
            }
        });
        status(value.busy ? "Loading music" : value.error ? "Read failed · See browser" : "Connected · Music browser", value.error);
    }
    private void status(String text, boolean error) {
        if (!running) return;
        try { host.status(text, error); } catch (RuntimeException | LinkageError ignored) { /* Unsupported or revoked. */ }
    }
    private void invalidate() {
        invalidate(false);
    }
    private void invalidate(boolean preserveArtwork) {
        generation.incrementAndGet();
        displaySession = null;
        if (preserveArtwork) artwork.cancelPending(); else artwork.clear();
        Future<?> old = request; request = null;
        if (old != null) old.cancel(true);
        api.cancel();
    }
    private long libraryReturnEpoch;
    private final Runnable libraryReturnPoll=this::pollLibraryReturn;
    private void startLibraryReturn(){
        libraryReturnEpoch++;receiverMain.removeCallbacks(libraryReturnPoll);
        if(view!=null){view.returnLabel("");view.playbackPlayer("");}receiverMain.post(libraryReturnPoll);
    }
    private void pollLibraryReturn(){
        if(!running||!showing||nowPlayingSurface||view==null||players==null)return;
        final BrowserView target=view;final PlayerController owner=players;final long ticket=libraryReturnEpoch;
        if(sending.get()||addon.isEmpty()){receiverMain.postDelayed(libraryReturnPoll,3000);return;}
        owner.readLibraryReturn(label->receiverMain.post(()->{
            if(!running||!showing||nowPlayingSurface||view!=target||players!=owner||ticket!=libraryReturnEpoch)return;
            target.returnLabel(label);target.playbackPlayer(owner.selectedName());receiverMain.postDelayed(libraryReturnPoll,3000);
        }));
    }
    private synchronized void stopBrowsing() {
        libraryReturnEpoch++;receiverMain.removeCallbacks(libraryReturnPoll);
        if(itemMenu!=null){itemMenu.dismiss();itemMenu=null;}
        stopRevertMonitor();
        resetLibraryFilters();
        nowPlayingSurface = false; musicPanelMode = "full";
        if (nowPlayingController != null) {
            if (running) nowPlayingController.dismiss(); else nowPlayingController.close();
            nowPlayingController = null;
        }
        if (setupController != null) setupController.cancel();
        showing = false; if (players != null) players.cancel(); invalidate();
        page = BrowserPage.status("Library", "Choose your music", false, false);
        history.clear();searchHistory.clear();searchOrigin=null;pendingOpenRoute=false; detail = null; category = "albums"; offset = 0; query = ""; restorePosition = 0;
        if (access != null) access.clear();
        detailCache = null; detailCacheSession = null;
        if (features != null) features.cancel();
    }
    @Override public synchronized void stop() {
        if(remoteSetup!=null){remoteSetup.stop();remoteSetup=null;}
        if(localGroupVolume!=null){localGroupVolume.close();localGroupVolume=null;}
        if(intercom!=null){intercom.close();intercom=null;}callScreenPending=false;
        if(volumeBootstrap!=null){volumeBootstrap.close();volumeBootstrap=null;}
        if(localVolume!=null){localVolume.close();localVolume=null;}
        if(hardwareVolume!=null){hardwareVolume.close();hardwareVolume=null;}
        monitorApi.cancel();if(monitorWorker!=null)monitorWorker.shutdownNow();
        running=false;integrationEpoch++;playbackLaunch.reset();voiceActivity.clear();voiceVersions.clear();
        if (features != null) features.close();
        receiverMain.removeCallbacksAndMessages(null);
        if (registration != null) { registration.close(); registration = null; }
        if (receiverManager != null) { receiverManager.stop(); receiverManager = null; }
        running = false; stopBrowsing(); view = null;
        if (receiver != null) { receiver.stop(); receiver = null; }
        playbackApi.cancel(); artwork.stop();
        ExecutorService old = worker;
        if (old != null) old.shutdownNow();
        // The host revokes access before stop; never call it here or wait on a worker.
    }
}
