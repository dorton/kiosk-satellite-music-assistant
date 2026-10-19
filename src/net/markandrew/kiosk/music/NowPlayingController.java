package net.markandrew.kiosk.music;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;
import java.util.function.*;
import me.jxl.kiosk.plugins.*;
import android.content.Context;
import android.view.View;
import org.json.JSONObject;

/** Visible-only polling; actions bind to the rendered player, queue and track. */
final class NowPlayingController {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final MusicApi api = new MusicApi();
    private final ArtworkLoader artwork = new ArtworkLoader();
    private final FeatureApi favoriteApi = new FeatureApi();
    private final FeatureApi favoriteLookup = new FeatureApi();
    private final ExecutorService favoriteWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicLong favoriteEpoch = new java.util.concurrent.atomic.AtomicLong();
    private String favoriteUri = "";
    private IngressAccess.Session eqSession;
    private long eqChecked=-15000;
    private boolean eqReading;
    private final MusicApi eqApi=new MusicApi();
    private final ExecutorService eqWorker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicLong eqEpoch=new java.util.concurrent.atomic.AtomicLong();
    private void resolveEq(IngressAccess.Session session){
        long now=android.os.SystemClock.elapsedRealtime();
        if(eqSession!=session){eqSession=session;eqChecked=-15000;eqEpoch.incrementAndGet();view.equaliserAvailable(false);}
        if(eqReading||now-eqChecked<15000)return;
        eqReading=true;eqChecked=now;long ticket=eqEpoch.get();
        try{eqWorker.submit(()->{
            boolean allowed=false;
            try{
                JSONObject account=(JSONObject)eqApi.identity(session);
                JSONObject scopes=(JSONObject)eqApi.command(session,"auth/scopes",new JSONObject());
                allowed=!account.optString("user_id").isEmpty()&&EqApi.permitted(scopes,account,"config.players.read")&&EqApi.permitted(scopes,account,"config.players.write");
            }catch(Exception ignored){ /* Fail closed for EQ only; never delay playback polling. */ }
            final boolean result=allowed;main.post(()->{eqReading=false;if(!current()||eqEpoch.get()!=ticket||view==null)return;view.equaliserAvailable(result);});
        });}catch(RejectedExecutionException ignored){eqReading=false;}
    }
    private Boolean favoriteValue;
    private long favoriteChecked;
    private boolean favoriteReading;
    private final PluginHost host;
    private final IngressAccess access;
    private final String addon, selected;
    private final BooleanSupplier alive;
    private final Supplier<String> currentPlayer;
    private volatile boolean open = true;
    private final NowPlayingWork work = new NowPlayingWork();
    private final Runnable poll = this::refresh;
    private volatile NowPlayingView view;
    private volatile NowPlayingSurface surface;
    private NowPlayingData rendered;
    private long positionAt;
    private int positionBase;
    private boolean positionFresh;
    private final Runnable clock=new Runnable(){public void run(){
        if(!current()||view==null)return;
        updateClock();scheduleClock();
    }};
    static long clockDelay(long now,long anchor){return 1000-Math.max(0,now-anchor)%1000;}
    private void scheduleClock(){
        main.removeCallbacks(clock);
        if(current()&&view!=null&&positionFresh&&rendered!=null&&"playing".equals(rendered.state)&&!rendered.live)
            main.postDelayed(clock,clockDelay(android.os.SystemClock.elapsedRealtime(),positionAt));
    }
    private void present(NowPlayingData value){
        long now=android.os.SystemClock.elapsedRealtime();
        long predicted=(long)positionBase+Math.max(0,now-positionAt)/1000;
        boolean continuous=positionFresh&&rendered!=null&&"playing".equals(rendered.state)&&"playing".equals(value.state)&&rendered.queueId.equals(value.queueId)&&rendered.itemId.equals(value.itemId)&&rendered.mediaUri.equals(value.mediaUri)&&Math.abs(predicted-value.elapsed)<=1;
        if(!continuous){positionAt=now;positionBase=value.elapsed;}
        rendered=value;positionFresh=true;view.render(value);updateClock();scheduleClock();
    }
    private void updateClock(){
        if(!positionFresh||rendered==null||view==null)return;
        long delta="playing".equals(rendered.state)?Math.max(0,android.os.SystemClock.elapsedRealtime()-positionAt)/1000:0;
        view.position((int)Math.min(Integer.MAX_VALUE,(long)positionBase+delta));
    }
    private String art = "";
    private String feedback = "";
    private String pendingCommand="";
    private int pendingValue;
    private NowPlayingData pendingBefore;
    private Consumer<String> diagnostic=text->{};
    void diagnostics(Consumer<String> sink){diagnostic=sink;}
    private static String states(NowPlayingData d){
        if(d==null)return "unreadable";
        String state=java.util.Arrays.asList("playing","paused","idle").contains(d.state)?d.state:"other";
        String queue=java.util.Arrays.asList("playing","paused","idle").contains(d.queueState)?d.queueState:"other";
        return "player="+state+", queue="+queue+", active="+d.queueActive+", nativePause="+d.nativePause;
    }
    private Consumer<String> navigation = destination -> {};
    private Runnable interaction=()->{};
    private BooleanSupplier navigationHeld=()->false;
    void navigationHeld(BooleanSupplier held){navigationHeld=held;}
    boolean hasPanel(){return surface!=null&&surface.hasPanel();}
    void interaction(Runnable handler){interaction=handler;}
    void navigation(Consumer<String> handler) { navigation = handler; }
    boolean visible() { return open && view != null; }
    boolean boundTo(String player) { return selected.equals(player); }
    TouchPanel panel(String title, boolean centered, Consumer<TouchPanel> populate, Runnable dismiss) {
        if (!visible() || surface == null) return null;
        return surface.panel(title, centered, populate, dismiss);
    }
    void clearPanel() {
        NowPlayingSurface target = surface;
        if (target == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) main.post(target::clearPanel);
        else target.clearPanel();
    }
    NowPlayingController(PluginHost host, IngressAccess access, String addon, String selected,
                         BooleanSupplier alive, Supplier<String> currentPlayer) {
        this.host = host; this.access = access; this.addon = addon; this.selected = selected;
        this.alive = alive; this.currentPlayer = currentPlayer;
    }
    private boolean current() { return open && alive.getAsBoolean() && selected.equals(currentPlayer.get()); }
    private Function<IngressAccess.Session,String> ownIdentity=session->"";
    private Supplier<String> deviceName=()->"";
    void deviceName(Supplier<String> name){deviceName=name;}
    void ownReceiver(Function<IngressAccess.Session, String> identity) { ownIdentity=identity;api.ownReceiver(identity); }
    private NowPlayingData presentation(JSONObject player,JSONObject queue,PlayerPolicy.Snapshot snapshot,IngressAccess.Session session)throws Exception{
        PlayerPolicy.Player target=snapshot.players.get(selected);
        api.annotateDisplayVolume(session,player,this::current);
        return new NowPlayingData(player,queue).withSelection(selected,target==null?selected:target.name,ownIdentity.apply(session),deviceName.get());
    }
    void show() {
        host.showOverlay("now_playing", MusicOverlay.screen(), new OverlayFactory() {
            public View create(Context context, KsTheme theme) {
                NowPlayingView target = new NowPlayingView(context, theme, new NowPlayingView.Actions() {
                    public void close() { navigate("library"); }
                    public void control(String command, int value) { action(command, value); }
                    public void navigate(String destination) { NowPlayingController.this.navigate(destination); }
                });
                view = target; surface = new NowPlayingSurface(context, theme, target);
                surface.interaction(()->interaction.run());
                main.post(poll);main.post(clock); return surface;
            }
            public void onDestroy(View target) { if (surface == target) close(); }
            public void onThemeChanged(View target, KsTheme theme) { dismiss(); }
        });
    }
    private void navigate(String destination) {
        if (!current()) return;
        if (work.writing()) { notice("A control is pending. Please wait for its result."); return; }
        navigation.accept(destination);
    }
    private void refresh() {
        if (!current() || !work.beginRead()) return;
        access.ensure(addon, this::current, session -> {
            if (!current()) return;
            try { worker.submit(() -> {
                try {
                    PlayerPolicy.Snapshot snapshot = api.playbackSnapshot(session, selected);
                    String leader = snapshot.leader(selected).id;
                    JSONObject player = (JSONObject)api.command(session, "players/get", new JSONObject().put("player_id", leader));
                    JSONObject queue = (JSONObject)api.command(session, "player_queues/get", new JSONObject().put("queue_id", snapshot.queue(selected)));
                    NowPlayingData data = presentation(player,queue,snapshot,session);
                    boolean empty=EmptyPlayback.confirmed(player,queue,snapshot.queue(selected));
                    main.post(() -> {
                        if (!current() || view == null || work.writing()) return;
                        if(empty&&!navigationHeld.getAsBoolean()&&pendingBefore==null&&surface!=null&&!surface.hasPanel()){navigate("library");return;}
                         if(pendingBefore!=null&&NowPlayingResult.confirmed(pendingCommand,pendingValue,pendingBefore,data)){
                            diagnostic.accept("Late "+pendingCommand+" confirmation · "+states(data));
                            pendingBefore=null;pendingCommand="";feedback="";
                        }
                          present(data.withFavorite(data.mediaUri.equals(favoriteUri) ? favoriteValue : null));
                         resolveEq(session);
                        if (!feedback.isEmpty()) view.message(feedback);
                        resolveFavorite(session, data);
                        if (!art.equals(data.image)) {
                            art = data.image; view.artwork(null);
                            String expected = art;
                            artwork.load(session, expected, () -> current() && expected.equals(art), bitmap -> main.post(() -> {
                                if (current() && view != null && expected.equals(art)) view.artwork(bitmap);
                            }));
                        }
                    });
                } catch (Exception error) {
                    if(error instanceof PairingDiagnostics.Failure&&(((PairingDiagnostics.Failure)error).http==401||((PairingDiagnostics.Failure)error).http==403))access.clear();
                    String detail=PairingDiagnostics.describe(error);
                    main.post(() -> { if (!work.writing()) unavailable("Player read failed · "+detail+". Check Player settings."); });
                }
                finally { main.post(() -> { work.endRead(); if (current() && !work.writing()) schedulePoll(); }); }
            }); } catch (RejectedExecutionException ignored) { work.endRead(); }
        }, error -> { main.post(() -> { work.endRead(); if (!work.writing()) unavailable(error); if(current())schedulePoll(); }); });
    }
    private void resolveFavorite(IngressAccess.Session session, NowPlayingData data) {
        if (!data.favoriteControl || favoriteReading || work.writing()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (data.mediaUri.equals(favoriteUri) && now - favoriteChecked < 15000) return;
        favoriteReading = true; favoriteChecked = now;
        String uri = data.mediaUri; long ticket = favoriteEpoch.get();
        BooleanSupplier valid = () -> current() && favoriteEpoch.get() == ticket;
        try { favoriteWorker.submit(() -> {
            Boolean value = null;
            try { value = favoriteLookup.favoriteItem(favoriteLookup.new Connection(session, valid), uri).optBoolean("favorite"); }
            catch (Exception ignored) { /* Favourites must not block or disable playback controls. */ }
            final Boolean resolved = value;
            main.post(() -> {
                favoriteReading = false;
                if (!valid.getAsBoolean() || rendered == null || !uri.equals(rendered.mediaUri)) return;
                favoriteUri = uri; favoriteValue = resolved;
                if (!work.writing() && view != null) { rendered = rendered.withFavorite(resolved); view.render(rendered);updateClock(); if (!feedback.isEmpty()) view.message(feedback); }
            });
        }); } catch (RejectedExecutionException ignored) { favoriteReading = false; }
    }
    private void schedulePoll() { main.removeCallbacks(poll); main.postDelayed(poll, 2000); }
    private void unavailable(String message) { positionFresh=false;if (current() && view != null) { view.busy(true); view.message(message); } }
    private void action(String command, int value) {
        if (!current() || rendered == null || view == null) return;
        if (!work.beginWrite()) { notice("A control is already pending. No duplicate was sent."); return; }
        final NowPlayingData expected = rendered;
        pendingBefore=null;pendingCommand="";
        if ("favorite".equals(command)) favoriteEpoch.incrementAndGet();
        view.busy(true); notice(""); main.removeCallbacks(poll);
        access.ensure(addon, this::current, session -> {
            if (!current()) return;
            try { worker.submit(() -> {
                boolean submitted = false;
                String message;
                Boolean verifiedFavorite = null;
                NowPlayingData returned = null;
                NowPlayingData confirmationBefore=expected;
                try {
                    PlayerPolicy.Snapshot fresh = api.playbackSnapshot(session, selected);
                    if (!expected.queueId.equals(fresh.queue(selected))) throw new IllegalArgumentException("Queue changed. Refresh before controlling it.");
                    String id = fresh.leader(selected).id;
                    JSONObject raw = (JSONObject)api.command(session, "players/get", new JSONObject().put("player_id", id));
                    JSONObject q = (JSONObject)api.command(session, "player_queues/get", new JSONObject().put("queue_id", expected.queueId));
                    NowPlayingData latest = new NowPlayingData(raw, q);
                    confirmationBefore=latest;
                    if ("favorite".equals(command)) {
                        FeatureApi.require(latest.favoriteControl && expected.mediaUri.equals(latest.mediaUri) && expected.itemId.equals(latest.itemId), "Track changed. Refresh first.");
                        FeatureApi feature = favoriteApi;
                        FeatureApi.Connection c = feature.new Connection(session, this::current, () -> {
                            FeatureApi.require(current(), "Player changed before submission.");
                            FeatureApi.require(expected.queueId.equals(api.playbackSnapshot(session, selected).queue(selected)), "Queue changed.");
                            JSONObject liveQueue = (JSONObject)api.command(session, "player_queues/get", new JSONObject().put("queue_id", expected.queueId));
                            JSONObject item = liveQueue.optJSONObject("current_item"), media = item == null ? null : item.optJSONObject("media_item");
                            FeatureApi.require(item != null && expected.itemId.equals(item.optString("queue_item_id")) && media != null && expected.mediaUri.equals(media.optString("uri")), "Track changed before favourite submission.");
                        });
                        boolean enabled = !feature.favoriteItem(c, latest.mediaUri).optBoolean("favorite");
                        submitted = true; feature.favorite(c, latest.mediaUri, enabled);
                        verifiedFavorite = enabled;
                    } else {
                        NowPlayingAction action = new NowPlayingAction(command, value, id, expected, latest);
                        submitted = true;
                        api.mutate(session, action.command, action.args, this::current);
                        if("volume_set".equals(command)&&value>0&&latest.muted){
                            NowPlayingData staged=null;
                            for(int delay:new int[]{0,150,350,700}){
                                if(delay>0)Thread.sleep(delay);
                                FeatureApi.require(current(),"Player view closed.");
                                PlayerPolicy.Snapshot scope=api.playbackSnapshot(session,selected);
                                FeatureApi.require(id.equals(scope.leader(selected).id)&&expected.queueId.equals(scope.queue(selected)),"Volume target changed. Unmute was not sent.");
                                JSONObject scopedPlayer=(JSONObject)api.command(session,"players/get",new JSONObject().put("player_id",id));
                                FeatureApi.require(raw.optString("type").equals(scopedPlayer.optString("type"))&&String.valueOf(raw.optJSONArray("group_members")).equals(String.valueOf(scopedPlayer.optJSONArray("group_members"))),"Group changed. Unmute was not sent.");
                                staged=new NowPlayingData(scopedPlayer,q);
                                if(NowPlayingResult.levelConfirmed(value,latest,staged))break;
                            }
                            FeatureApi.require(staged!=null&&NowPlayingResult.levelConfirmed(value,latest,staged),"Volume level was not confirmed. Unmute was not sent.");
                            if(staged.muted){
                                FeatureApi.require(staged.muteControl,"Unmute unavailable.");
                                api.mutate(session,latest.groupVolume?"players/cmd/group_volume_mute":"players/cmd/volume_mute",new JSONObject().put("player_id",id).put("muted",false),this::current);
                            }
                        }
                        PlayerPolicy.Snapshot checked = api.playbackSnapshot(session, selected);
                        FeatureApi.require(expected.queueId.equals(checked.queue(selected)), "Queue changed after submission.");
                        JSONObject checkedPlayer = (JSONObject)api.command(session, "players/get", new JSONObject().put("player_id", checked.leader(selected).id));
                        JSONObject checkedQueue = (JSONObject)api.command(session, "player_queues/get", new JSONObject().put("queue_id", expected.queueId));
                         returned = presentation(checkedPlayer,checkedQueue,checked,session);
                         // Commands may acknowledge before MA publishes new state. Re-read only;
                         // never send the mutation again, even if confirmation stays uncertain.
                         for(int delay:new int[]{150,350,700}){
                             if(NowPlayingResult.confirmed(command,value,latest,returned))break;
                             Thread.sleep(delay);FeatureApi.require(current(),"Player view closed.");
                             PlayerPolicy.Snapshot retry=api.playbackSnapshot(session,selected);
                             FeatureApi.require(expected.queueId.equals(retry.queue(selected)),"Queue changed after submission.");
                             JSONObject p=(JSONObject)api.command(session,"players/get",new JSONObject().put("player_id",retry.leader(selected).id));
                             JSONObject queue=(JSONObject)api.command(session,"player_queues/get",new JSONObject().put("queue_id",expected.queueId));
                             returned=presentation(p,queue,retry,session);
                         }
                         if (!NowPlayingResult.confirmed(command, value, latest, returned))
                            throw new IllegalStateException("Returned state has not confirmed the command.");
                    }
                    message = "";
                } catch (Exception error) { message = submitted ? command + " submitted · state not confirmed. Check MA before retrying; no automatic retry."
                    : command + " not submitted · player, source or capability changed (HTTP " + api.lastHttpStatus() + "). Refresh and check MA."; }
                final String result = message;
                final Boolean favoriteResult = verifiedFavorite;
                final NowPlayingData checkedData = returned;
                final boolean uncertain=submitted&&!message.isEmpty()&&!"favorite".equals(command);
                final NowPlayingData before=confirmationBefore;
                final String report=command+" · submitted="+submitted+" · confirmed="+message.isEmpty()+" · before: "+states(before)+" · after: "+states(returned);
                main.post(() -> { work.endWrite(); if (current() && view != null) {
                    diagnostic.accept(report);
                    if(uncertain){pendingBefore=before;pendingCommand=command;pendingValue=value;}
                    if (favoriteResult != null && expected.mediaUri.equals(rendered.mediaUri)) {
                        favoriteUri = expected.mediaUri; favoriteValue = favoriteResult; favoriteChecked = android.os.SystemClock.elapsedRealtime();
                        rendered = rendered.withFavorite(favoriteResult); view.render(rendered);updateClock();
                    } else if (checkedData != null) {
                        present(checkedData.withFavorite(checkedData.mediaUri.equals(favoriteUri) ? favoriteValue : null));
                    }
                    notice(result); refresh();
                } });
            }); } catch (RejectedExecutionException ignored) { work.endWrite(); unavailable("Player is stopping. Nothing submitted."); }
        }, error -> main.post(() -> { work.endWrite(); feedback = error; unavailable(error); }));
    }
    void dismiss() { close(); try { host.hideOverlay("now_playing"); } catch (RuntimeException ignored) {} }
    void notice(String message) { feedback = message; if (view != null) view.message(message); }
    void close() {
        // The host stops plugins on a worker. Serialize view/artwork teardown with UI callbacks.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            open = false; main.post(() -> close()); return;
        }
        open = false; if (surface != null) surface.clearPanel(); surface = null; view = null; main.removeCallbacksAndMessages(null);
        favoriteEpoch.incrementAndGet();eqEpoch.incrementAndGet(); api.cancel(); favoriteApi.api.cancel(); favoriteLookup.api.cancel();eqApi.cancel();
        artwork.stop(); worker.shutdownNow(); favoriteWorker.shutdownNow();eqWorker.shutdownNow();
    }
}
