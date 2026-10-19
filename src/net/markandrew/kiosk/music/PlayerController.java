package net.markandrew.kiosk.music;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Explicit selection/transfer. Settings remain independent of temporary targets. */
final class PlayerController {
    interface Ui {
        void message(String text);
        void panel(String title, Consumer<TouchPanel> populate);
        default void popup(String title,Consumer<TouchPanel> populate){panel(title,populate);}
        void dismiss();
        void nativePlayer();
        void playbackStarted();
        default void library() {}
        default void home() {}
        default void playerSettings() {}
        default void joinGroup() {}
        default void group() {}
        default void preferences() {}
        default void error(String text) { message(text); }
        default void saveDefault(String player,IngressAccess.Session session,BooleanSupplier current,Runnable saved,Consumer<String> failed){failed.accept("Saving a default is unavailable.");}
    }
    private final MusicApi api;
    private final IngressAccess access;
    private final ExecutorService worker;
    private final AtomicBoolean busy;
    private final Ui ui;
    private final BooleanSupplier alive;
    private volatile String addon, fixed, temporary = "";
    private volatile String namedId="",playerName="";
    String selectedName(){return namedId.equals(selectedId())?playerName:"";}
    private volatile Operation active;
    private Runnable afterSelection;
    private String joinedSource="";
    private boolean externalDefaultRequired;
    private java.util.function.Supplier<String> localIdentity=()->"";
    private BooleanSupplier localEnabled=()->true;
    void localEnabled(BooleanSupplier enabled){localEnabled=enabled;}
    void localIdentity(java.util.function.Supplier<String> identity){localIdentity=identity;}
    String selectedId() { return temporary.isEmpty() ? fixed : temporary; }
    private final class Operation {
        boolean current() { return alive.getAsBoolean() && active == this; }
    }
    void cancel() { active = null; busy.set(false); }
    PlayerController(MusicApi api, IngressAccess access, ExecutorService worker,
                     AtomicBoolean busy, BooleanSupplier alive, Ui ui) {
        this.api = api; this.access = access; this.worker = worker; this.busy = busy;
        this.alive = alive; this.ui = ui;
    }
    void configure(Map<String, Object> settings, String addon) {
        cancel(); this.addon = addon; fixed = DefaultPlayerPolicy.saved(settings); temporary = "";joinedSource="";namedId="";playerName="";
        externalDefaultRequired=fixed.isEmpty()&&!Boolean.TRUE.equals(settings.get("receiverEnabled"));
    }
    private interface Work { void run(Operation op, IngressAccess.Session session, PlayerPolicy.Snapshot snapshot) throws Exception; }
    private void begin(Work work) {
        begin(false, work);
    }
    private void begin(boolean playback, Work work) {
        if (!alive.getAsBoolean() || !busy.compareAndSet(false, true)) return;
        final Operation op = new Operation(); active = op;
        final String target = temporary.isEmpty() ? fixed : temporary;
        ui.message(playback ? "Sending to player…" : "");
        access.ensure(addon, op::current, session -> submit(op, () -> {
                api.identity(session);
                PlayerPolicy.Snapshot snapshot = playback ? api.playbackSnapshot(session, target) : api.snapshot(session);
                if (op.current()) work.run(op, session, snapshot);
            }), error -> fail(op, error));
    }
    private interface Job { void run() throws Exception; }
    private void submit(Operation op, Job job) {
        try { worker.submit(() -> {
            if (!op.current()) return;
            try { job.run(); }
            catch (IllegalArgumentException error) { fail(op, error.getMessage()); }
            catch (Exception ignored) { fail(op, "Not confirmed. Check players and queues before retrying. No automatic retry was made."); }
        }); } catch (RuntimeException ignored) { fail(op, "Browser is stopping."); }
    }
    private void fail(Operation op, String message) { if (op.current()) { busy.set(false); ui.error(message); } }
    private PlayerPolicy.Player selected(PlayerPolicy.Snapshot snapshot) {
        return DefaultPlayerPolicy.selected(snapshot, temporary.isEmpty() ? fixed : temporary);
    }
    void players(boolean transfer) { afterSelection = null; if(!transfer&&externalDefaultRequired){chooseDefault();return;}listPlayers(transfer); }
    void chooseDefault(){
        if(!alive.getAsBoolean()||busy.get()||!externalDefaultRequired||!fixed.isEmpty())return;
        afterSelection=null;
        ui.panel("Choose default player",panel->{panel.note("Loading available external players…");panel.closeAction("Cancel",ui::home);panel.add("Cancel",ui::home);});
        begin((op,session,snapshot)->{
            if(!op.current()||!externalDefaultRequired||!fixed.isEmpty())return;
            busy.set(false);ui.message("");
            ui.panel("Choose default player",panel->{
                panel.guard(op::current);panel.closeAction("Cancel",ui::home);
                panel.note("Choose an external player to save as this kiosk's default. Local playback stays disabled.");
                int count=0;
                for(PlayerPolicy.Player player:snapshot.players.values()){
                    if(!player.available||!player.visible||!player.audio()||player.id.equals(localIdentity.get()))continue;
                    panel.row(player.name,snapshot.playbackDescription(player.id),"○",false,true,()->saveInitialDefault(player.id),null);count++;
                }
                if(count==0)panel.note("No available external players. Check MA and refresh, or choose the MA instance in Plugin preferences.");
                panel.add("Refresh players",this::chooseDefault);
                panel.navigation("cog-outline","Plugin preferences",ui::preferences);
                panel.add("Cancel",ui::home);
            });
        });
    }
    private void saveInitialDefault(String id){
        if(!externalDefaultRequired||!fixed.isEmpty())return;
        begin((op,session,snapshot)->{
            PlayerPolicy.Player player=DefaultPlayerPolicy.selected(snapshot,id);
            if(!op.current()||!externalDefaultRequired||!fixed.isEmpty())return;
            if(player.id.equals(localIdentity.get()))throw new IllegalArgumentException("Local playback is disabled. Choose an external player.");
            ui.saveDefault(player.id,session,op::current,()->{
                if(!op.current())return;
                fixed=player.id;temporary="";joinedSource="";externalDefaultRequired=false;busy.set(false);ui.message("");route(false);
            },error->fail(op,error));
        });
    }
    private void listPlayers(boolean transfer) {
        begin((op, session, snapshot) -> {
            PlayerPolicy.Player source = null;
            try { source = selected(snapshot); } catch (IllegalArgumentException error) { if (transfer) throw error; }
            final PlayerPolicy.Player from = source;
            if (!op.current()) return;
            busy.set(false);
            ui.message("");
            ui.panel(transfer ? "Transfer queue" : "Player · " + (from == null ? "Choose a player" : from.name), panel -> {
                panel.menuSpacing();
                panel.guard(op::current);
                panel.closeAction(afterSelection==null?"Close":"‹ Back",()->{Runnable back=afterSelection;afterSelection=null;if(back!=null)back.run();else ui.dismiss();});
                if(transfer)panel.note("Move this queue to another available player.");
                else if(afterSelection==null&&from!=null)panel.navigation("speaker-multiple","Join group",ui::joinGroup);
                int count = 0;
                java.util.List<PlayerPolicy.Player> ordered=new java.util.ArrayList<>(snapshot.players.values());
                final String own=localIdentity.get();
                if(!transfer)ordered.sort(java.util.Comparator.comparingInt(p->p.id.equals(own)?0:1));
                boolean localSection=false,otherSection=false;
                for (PlayerPolicy.Player player : ordered) {
                    if (!player.available || !player.visible || !player.audio()) continue;
                    if (transfer && !snapshot.transferable(from.id, player.id)) continue;
                    if(!transfer){
                        if(player.id.equals(own)){
                            if(!localEnabled.getAsBoolean())continue;
                            if(!localSection){panel.section("This device");localSection=true;}
                        }else if(!otherSection){panel.section("Other devices");otherSection=true;}
                    }
                    String label = player.name;
                    String subtitle = snapshot.playbackDescription(player.id);
                    if(!transfer&&player.id.equals(fixed))subtitle="Default"+(subtitle.isEmpty()?"":" · "+subtitle);
                    if (transfer) {
                        String queue = snapshot.queue(from.id);
                        panel.row(label, subtitle, "→", false, true, () -> transfer(from.id, player.id, player.name, queue), null);
                    } else panel.row(label, subtitle, player.id.equals(selectedId()) ? "✓" : "○", player.id.equals(selectedId()), true, () -> choose(player.id), null);
                    count++;
                }
                if (count == 0) panel.add("No available Music Assistant players", () -> players(transfer));
            });
        });
    }
    void players(boolean transfer, Runnable after) { if(!transfer&&externalDefaultRequired){chooseDefault();return;}afterSelection = after; listPlayers(transfer); }
    void followJoined(String id){
        final String source=selectedId();
        begin((op,session,snapshot)->{
            DefaultPlayerPolicy.selected(snapshot,id);if(!op.current())return;
            joinedSource=source;temporary=id;afterSelection=null;busy.set(false);ui.message("");route(false);
        });
    }
    void memberRemoved(String id,Runnable otherwise){
        if(!id.equals(joinedSource)&&!id.equals(selectedId())){otherwise.run();return;}
        temporary=id;joinedSource="";afterSelection=null;route(false);
    }
    private void choose(String id) {
        final String previous = temporary;
        begin((op, session, snapshot) -> {
            temporary = id;
            final PlayerPolicy.Player player;
            try { player = selected(snapshot); } finally { temporary = previous; }
            if (!op.current()) return;
            boolean empty=false;
            if(afterSelection==null){
                // An unreadable queue is not empty; selection can still open its controller.
                try{empty="Close".equals(returnLabel(snapshot,player.id));}catch(IllegalArgumentException ignored){}
            }
            temporary = id; joinedSource="";busy.set(false); ui.message("");
            Runnable next = afterSelection; afterSelection = null;ui.dismiss();if (next != null)next.run();else if(empty)ui.library();else ui.nativePlayer();
        });
    }
    private void transfer(String source, String target, String targetName, String queue) {
        begin((op, session, snapshot) -> {
                snapshot.validateTransfer(source, target, queue);
                if (!selected(snapshot).id.equals(source)) throw new IllegalArgumentException("Controlled player changed. Reopen Transfer.");
                DefaultPlayerPolicy.selected(snapshot, target);
                api.transfer(session, source, target, queue, op::current);
                if (!op.current()) return;
                temporary = target;
                busy.set(false); ui.dismiss(); ui.message("Queue transferred to " + targetName + ". Now Playing follows this selection.");
                ui.nativePlayer();
        });
    }
    void play(BrowserItem item, String option) {
        play(item, option, false, "");
    }
    void play(BrowserItem item, String option, boolean shuffle, String start) {
        IngressPolicy.playback(item.uri, option);
        playback(item, option, shuffle, start);
    }
    private void playback(BrowserItem item, String option, boolean shuffle, String start) {
        begin(true, (op, session, snapshot) -> {
            PlayerPolicy.Player player = selected(snapshot);
            String queue = snapshot.queue(player.id);
            boolean allowGrouped = snapshot.grouped(player.id);
            if (snapshot.externalSource(player.id)) throw new IllegalArgumentException("External source is active. No queue was overwritten.");
            api.play(session, snapshot, player.id, queue, allowGrouped, item.uri, option, shuffle, start, op::current);
            if (!op.current()) return;
            busy.set(false); ui.message("Queue updated · " + player.name);
            if (PlayerPolicy.immediate(option)) ui.playbackStarted();
        });
    }
    void nowPlaying() {
        if (alive.getAsBoolean()) ui.nativePlayer();
    }
    /** Read-only navigation: only the selected player's resolved leader determines routing. */
    void route(boolean closing) {
        route(closing,false,()->true);
    }
    void revertIfIdle(BooleanSupplier unchanged) { route(true,true,unchanged); }
    private void route(boolean closing,boolean idleOnly,BooleanSupplier unchanged) {
        if(!closing&&externalDefaultRequired){chooseDefault();return;}
        begin((op, session, snapshot) -> {
            if(idleOnly){
                PlayerPolicy.Player controlled=selected(snapshot), currentLeader=snapshot.leader(controlled.id);
                if(currentLeader==null||!currentLeader.available||!"idle".equals(currentLeader.state)){busy.set(false);return;}
            }
            PlayerPolicy.Player player = null;
            if(idleOnly&&!fixed.isEmpty())player=DefaultPlayerPolicy.selected(snapshot,fixed);
            else if(!idleOnly&&!selectedId().isEmpty())player=selected(snapshot);
            PlayerPolicy.Player leader = player == null ? null : snapshot.leader(player.id);
            boolean playing = leader != null && leader.available && "playing".equals(leader.state);
            boolean showPlayer=playing;
            if(!idleOnly&&leader!=null)showPlayer=!"Close".equals(returnLabel(snapshot,player.id));
            if (!op.current()) return;
            if(!unchanged.getAsBoolean()){busy.set(false);return;}
            if(idleOnly)temporary="";
            busy.set(false); ui.message("");
            if (showPlayer) ui.nativePlayer(); else if (closing) ui.home(); else ui.library();
        });
    }
    static String returnLabel(PlayerPolicy.Snapshot snapshot,String id){
        if(id.isEmpty())return "Close";
        PlayerPolicy.Player p=DefaultPlayerPolicy.selected(snapshot,id),leader=snapshot.leader(p.id);
        if(leader==null||!leader.available)throw new IllegalArgumentException("Player unavailable. Refresh before returning.");
        if("playing".equals(leader.state))return "Now playing";
        if("paused".equals(leader.state))return "Back to player";
        if(!java.util.Arrays.asList("idle","stopped").contains(leader.state))throw new IllegalArgumentException("Player state could not be confirmed. Refresh before returning.");
        PlayerPolicy.Queue q=snapshot.queues.get(snapshot.queue(p.id));
        if(q==null||!q.available)throw new IllegalArgumentException("Player queue could not be read. Check the connection; it was not assumed empty.");
        return q.items>0?"Back to player":"Close";
    }
    /** Preview the selected-player return route; only idle auto-revert restores default. */
    void readLibraryReturn(Consumer<String> result){
        final String target=selectedId(),instance=addon;
        BooleanSupplier current=()->alive.getAsBoolean()&&instance.equals(addon)&&target.equals(selectedId());
        if(!current.getAsBoolean()||busy.get()){result.accept("");return;}
        if(target.isEmpty()){result.accept("Close");return;}
        access.ensure(instance,current,session->{try{worker.submit(()->{
            String label="";try{if(current.getAsBoolean()&&!busy.get()){api.identity(session);PlayerPolicy.Snapshot snapshot=api.playbackSnapshot(session,target);PlayerPolicy.Player player=DefaultPlayerPolicy.selected(snapshot,target);if(current.getAsBoolean()){playerName=player.name;namedId=target;}label=returnLabel(snapshot,target);}}catch(Exception ignored){/* Unknown is not idle. */}
            if(current.getAsBoolean())result.accept(label);
        });}catch(RuntimeException ignored){if(current.getAsBoolean())result.accept("");}},error->{if(current.getAsBoolean())result.accept("");});
    }
    /** Silent, read-only monitor. Never selects a fallback or takes ownership of a UI operation. */
    void readState(Consumer<String> result) {
        if(!alive.getAsBoolean()||busy.get()){result.accept("");return;}
        final String expected=selectedId(),instance=addon;
        BooleanSupplier current=()->alive.getAsBoolean()&&expected.equals(selectedId())&&instance.equals(addon);
        access.ensure(instance,current,session->{
            try{worker.submit(()->{
                String state="";
                try{
                    if(current.getAsBoolean()&&!busy.get()){
                        api.identity(session);PlayerPolicy.Snapshot snapshot=api.playbackSnapshot(session,expected);
                        PlayerPolicy.Player p=DefaultPlayerPolicy.selected(snapshot,expected),leader=snapshot.leader(p.id);
                        if(leader!=null&&leader.available)state=leader.state;
                    }
                }catch(Exception ignored){/* An unreadable player is not idle. */}
                result.accept(current.getAsBoolean()?state:"");
            });}catch(RuntimeException ignored){result.accept("");}
        },error->result.accept(""));
    }
}
