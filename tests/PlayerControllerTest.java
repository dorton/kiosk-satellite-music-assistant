package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

/** Saved default and temporary selections, mocked IO/UI. No playback or network. */
public final class PlayerControllerTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Controller check " + checks); }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> m = new HashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i+1]); return m;
    }
    static final class Inline extends AbstractExecutorService {
        public void shutdown() {} public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long n, TimeUnit unit) { return true; } public void execute(Runnable run) { run.run(); }
    }
    static final class Host implements PluginHost {
        String source = "device", id = "";
        int reads, sets;
        boolean changeOnRead, offline;
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            String action = (String) message.get("service");
            if (action.endsWith("_media_player_list")) {
                reads++;
                if (offline) { callback.onResult(false, null, "not available"); return; }
                if (changeOnRead && reads > 1) { source = "home_assistant"; id = "media_player.other"; }
                callback.onResult(true, map("response", map("current", map("source", source, "id", id), "players", Arrays.asList(
                    map("source", "music_assistant", "id", "remote", "available", true),
                    map("source", "music_assistant", "id", "local", "available", true)))), null);
            } else if (action.endsWith("_media_player_set")) {
                sets++; Map<?, ?> data = (Map<?, ?>) message.get("service_data");
                source = (String) data.get("source"); id = (String) data.get("player");
                if (source.equals("device") && id.equals("session:*")) id = "*";
                else if (source.equals("device") && id.equals("*")) throw new AssertionError("Session action requires session: prefix");
                callback.onResult(true, map("response", map()), null);
            } else throw new AssertionError("Unexpected service");
        }
    }
    static final class Ui implements PlayerController.Ui {
        String message;
        int nativeCalls, libraryResets, libraryCalls, homeCalls,groupCalls;
        public void group(){groupCalls++;}public void joinGroup(){groupCalls++;}
        int defaultSaves;String savedDefault="",title="";
        TouchPanel panel;
        public void message(String value) { message = value; }
        public void panel(String title, Consumer<TouchPanel> build) { this.title=title;panel = new TouchPanel(); build.accept(panel); }
        public void saveDefault(String player,IngressAccess.Session session,java.util.function.BooleanSupplier current,Runnable saved,Consumer<String> failed){if(current.getAsBoolean()){defaultSaves++;savedDefault=player;saved.run();}}
        public void dismiss() {} public void nativePlayer() { nativeCalls++; }
        public void playbackStarted() { libraryResets++; nativePlayer(); }
        public void library() { libraryCalls++; }
        public void home() { homeCalls++; }
    }
    static final class Fixture {
        final Host host = new Host(); final MusicApi api = new MusicApi(); final Ui ui = new Ui();
        final AtomicBoolean alive = new AtomicBoolean(true), busy = new AtomicBoolean();
        final PlayerController controller;
        Fixture(String mode) {
            DiscoveryAccess.failed = false;
            for (String id : Arrays.asList("local", "remote")) {
                api.snapshot.players.put(id, new PlayerPolicy.Player(id, id, "player", true, true, "", "", "", "idle"));
                api.snapshot.queues.put(id, new PlayerPolicy.Queue(id, true, 1, "idle"));
            }
            api.snapshot.providers.put("local", "sendspin"); api.snapshot.addresses.put("local", "192.0.2.10");
            controller = new PlayerController(api, new IngressAccess(), new Inline(), busy, alive::get, ui);
            controller.configure(map("playerMode", mode, "defaultPlayer", "remote", "localPlayer", "local"), "ma");
        }
    }
    public static void main(String[] args) {
        Fixture local = new Fixture("Follow Kiosk Satellite"); local.controller.play(new BrowserItem(), "play");
        check(local.api.plays == 1 && local.api.target.equals("local")); check(local.host.sets == 0);
        check(local.host.reads == 0); check(local.ui.nativeCalls == 1); check(!local.busy.get());
        check(local.api.fullReads == 0 && local.api.targetedReads == 1);
        check(local.ui.libraryResets == 1);
        Fixture grouped = new Fixture("Follow Kiosk Satellite");
        grouped.api.snapshot.players.put("local", new PlayerPolicy.Player("local", "local", "player", true, true,
            "", "remote", "", "idle"));
        grouped.controller.play(new BrowserItem(), "play");
        check(grouped.api.plays == 1 && grouped.host.sets == 0);
        check(grouped.api.target.equals("local"));check(!grouped.busy.get());
        check(grouped.ui.libraryResets == 1);
        Fixture remote = new Fixture("Fixed default player");
        remote.controller.play(new BrowserItem(), "add"); check(remote.api.plays == 1 && remote.api.target.equals("remote"));
        check(remote.host.sets == 0 && remote.ui.nativeCalls == 0);
        check(remote.api.fullReads == 0 && remote.api.targetedReads == 1);
        check(remote.ui.libraryResets == 0);
        for (String option : Arrays.asList("next", "replace_next")) {
            Fixture queued = new Fixture("Fixed default player"); queued.controller.play(new BrowserItem(), option);
            check(queued.ui.panel==null);
            check(queued.api.plays == 1 && queued.ui.nativeCalls == 0 && queued.ui.libraryResets == 0);
        }
        Fixture missing = new Fixture("Follow Kiosk Satellite"); DiscoveryAccess.failed = true;
        missing.controller.configure(map("defaultPlayer", "", "localPlayer", ""), "ma");
        missing.controller.play(new BrowserItem(), "play"); check(missing.api.plays == 0 && missing.host.reads == 0);
        check(missing.ui.message.contains("default player in Setup"));
        Fixture fixed = new Fixture("Fixed default player"); DiscoveryAccess.failed = true;
        fixed.controller.play(new BrowserItem(), "play"); check(fixed.api.plays == 1 && fixed.api.target.equals("remote"));
        check(fixed.ui.nativeCalls == 1 && fixed.host.sets == 0); check(fixed.ui.message.startsWith("Queue updated"));
        Fixture temporary = new Fixture("Follow Kiosk Satellite"); temporary.controller.players(false);
        temporary.ui.panel.buttons.get("remote").run(); check(temporary.host.sets == 0 && temporary.host.reads == 0);
        check(temporary.ui.message.isEmpty());
        check(temporary.api.plays == 0 && temporary.api.transfers == 0);
        temporary.controller.play(new BrowserItem(), "play"); check(temporary.api.target.equals("remote"));
         temporary.controller.players(false); temporary.ui.panel.buttons.get("local").run();
        temporary.controller.play(new BrowserItem(), "add"); check(temporary.api.target.equals("local"));
        check(temporary.api.plays == 2 && temporary.ui.nativeCalls == 3);
        Fixture joined=new Fixture("Fixed default player");joined.controller.followJoined("local");
        check(joined.controller.selectedId().equals("local")&&joined.ui.nativeCalls==1);
        check(joined.host.sets==0&&joined.api.plays==0&&joined.api.transfers==0);
         joined.controller.players(false);joined.ui.panel.buttons.get("remote").run();check(joined.controller.selectedId().equals("remote"));
        Fixture invalid = new Fixture("Fixed default player"); invalid.api.snapshot.players.remove("remote");
        invalid.controller.play(new BrowserItem(), "play"); check(invalid.api.plays == 0);
        check(invalid.ui.message.contains("no fallback"));
        Fixture replacing = new Fixture("Fixed default player"); replacing.controller.play(new BrowserItem(), "replace");
        check(replacing.ui.panel==null);
        check(replacing.api.plays == 1 && replacing.api.target.equals("remote"));
        check(replacing.ui.libraryResets == 1);
        Fixture staleReplacement = new Fixture("Fixed default player");staleReplacement.api.snapshot.players.remove("remote");staleReplacement.controller.play(new BrowserItem(), "replace");
        check(staleReplacement.api.plays == 0 && staleReplacement.ui.message.contains("no fallback"));
        Fixture shuffled=new Fixture("Fixed default player");shuffled.controller.play(new BrowserItem(),"replace",true,"");check(shuffled.api.plays==1&&shuffled.ui.panel==null&&shuffled.api.target.equals("remote"));
        Fixture failedPlay = new Fixture("Fixed default player"); failedPlay.api.failPlay = true;
        failedPlay.controller.play(new BrowserItem(), "play");
        check(failedPlay.ui.libraryResets == 0 && failedPlay.ui.nativeCalls == 0);
        Fixture transfer = new Fixture("Follow Kiosk Satellite"); transfer.controller.players(true);
        transfer.ui.panel.buttons.get("remote").run(); check(transfer.api.transfers == 1);
        check(!transfer.ui.panel.buttons.containsKey("Confirm transfer"));
        check(transfer.host.sets == 0 && transfer.host.reads == 0);
        Fixture staleTransfer = new Fixture("Follow Kiosk Satellite"); staleTransfer.controller.players(true);
        Runnable transferTap = staleTransfer.ui.panel.buttons.get("remote");
        staleTransfer.api.snapshot.players.remove("remote"); transferTap.run();
        check(staleTransfer.api.transfers == 0);
        Fixture nativeOnly = new Fixture("Follow Kiosk Satellite"); DiscoveryAccess.failed = true;
        nativeOnly.controller.nowPlaying(); check(nativeOnly.ui.nativeCalls == 1 && nativeOnly.api.plays == 0 && nativeOnly.host.reads == 0);
        check(nativeOnly.ui.libraryResets == 0);
        Fixture stopped = new Fixture("Follow Kiosk Satellite"); stopped.alive.set(false);
        stopped.controller.play(new BrowserItem(), "play"); check(stopped.api.plays == 0 && stopped.host.reads == 0);
        for(String state:Arrays.asList("playing","paused","idle")) {
            Fixture routing=new Fixture("Follow Kiosk Satellite");
            routing.api.snapshot.players.put("local",new PlayerPolicy.Player("local","local","player",true,true,"","","local",state));
            routing.controller.route(false);
            check(routing.ui.nativeCalls==1); // A retained queue opens even when idle/paused.
            check(routing.ui.libraryCalls==0);
            routing.controller.route(true);
            check(routing.ui.homeCalls==0); // Paused or retained queues return to player too.
            check(routing.api.plays==0&&routing.api.transfers==0&&routing.host.sets==0);
        }
        Fixture empty=new Fixture("Follow Kiosk Satellite");empty.api.snapshot.queues.put("local",new PlayerPolicy.Queue("local",true,0,"idle"));
        empty.controller.route(false);check(empty.ui.libraryCalls==1&&empty.ui.nativeCalls==0);
        Fixture unreadable=new Fixture("Follow Kiosk Satellite");unreadable.api.snapshot.queues.remove("local");
        unreadable.controller.route(false);check(unreadable.ui.libraryCalls==0&&unreadable.ui.message.contains("not assumed empty"));
        Fixture removed=new Fixture("Fixed default player");removed.controller.followJoined("local");
        removed.api.snapshot.queues.put("remote",new PlayerPolicy.Queue("remote",true,0,"idle"));
        removed.controller.memberRemoved("remote",()->{throw new AssertionError("Expected queue routing");});
        check(removed.controller.selectedId().equals("remote")&&removed.ui.libraryCalls==1);
        check(removed.host.sets==0&&removed.api.plays==0&&removed.api.transfers==0);
        Fixture removalUnreadable=new Fixture("Fixed default player");removalUnreadable.controller.followJoined("local");removalUnreadable.api.snapshot.queues.remove("remote");
        removalUnreadable.controller.memberRemoved("remote",()->{throw new AssertionError("Expected queue read");});
        check(removalUnreadable.ui.libraryCalls==0&&removalUnreadable.ui.message.contains("not assumed empty"));
        final boolean[] refreshed={false};removed.controller.memberRemoved("another",()->refreshed[0]=true);check(refreshed[0]);
        Fixture pausedEmpty=new Fixture("Follow Kiosk Satellite");pausedEmpty.api.snapshot.queues.put("local",new PlayerPolicy.Queue("local",true,0,"paused"));
        pausedEmpty.api.snapshot.players.put("local",new PlayerPolicy.Player("local","local","player",true,true,"","","local","paused"));
        pausedEmpty.controller.route(false);check(pausedEmpty.ui.nativeCalls==1);
        Fixture groupedRouting=new Fixture("Follow Kiosk Satellite");
        groupedRouting.api.snapshot.players.put("local",new PlayerPolicy.Player("local","local","player",true,true,"","remote","","idle"));
        groupedRouting.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","playing"));
        groupedRouting.api.snapshot.queueMedia.put("remote","Track · Artist");
        groupedRouting.controller.route(false);check(groupedRouting.ui.nativeCalls==1);
        groupedRouting.controller.players(false);check(groupedRouting.ui.panel.subtitles.get("local").contains("Track · Artist"));
        Fixture descriptions=new Fixture("Follow Kiosk Satellite");
        descriptions.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","playing"));
        descriptions.api.snapshot.queueMedia.put("remote","Destination track · Artist");
        descriptions.controller.players(true);check(descriptions.ui.panel.subtitles.get("remote").contains("Destination track · Artist"));
        Fixture revert=new Fixture("Follow Kiosk Satellite");
        revert.api.snapshot.players.put("local",new PlayerPolicy.Player("local","local","player",true,true,"","","local","playing"));
        revert.controller.players(false);revert.ui.panel.buttons.get("remote").run();check(revert.controller.selectedId().equals("remote"));
        revert.controller.route(true);check(revert.controller.selectedId().equals("remote"));check(revert.ui.nativeCalls==2&&revert.ui.homeCalls==0);
        revert.controller.players(false);revert.ui.panel.buttons.get("remote").run();
        revert.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","paused"));
        revert.controller.revertIfIdle(()->true);check(revert.controller.selectedId().equals("remote"));
        revert.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","idle"));
        revert.controller.revertIfIdle(()->false);check(revert.controller.selectedId().equals("remote"));
        revert.controller.revertIfIdle(()->true);check(revert.controller.selectedId().equals("local")&&revert.ui.nativeCalls==4);
        check(revert.api.plays==0&&revert.api.transfers==0&&revert.host.sets==0);
        for(String state:Arrays.asList("idle","playing","paused")){
            Fixture first=new Fixture("Fixed default player");first.controller.configure(map("defaultPlayer","","playerMode","Fixed default player","receiverEnabled",false),"ma");first.controller.localIdentity(()->"local");
            first.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote",state));
            first.api.snapshot.queues.put("remote",new PlayerPolicy.Queue("remote",true,0,state));first.controller.route(false);
            check(first.ui.title.equals("Choose default player"));check(!first.ui.panel.buttons.containsKey("local")&&!first.ui.panel.buttons.containsKey("Use default"));
            first.ui.panel.buttons.get("remote").run();check(first.ui.defaultSaves==1&&first.ui.savedDefault.equals("remote"));check(first.controller.selectedId().equals("remote"));
            check(first.ui.libraryCalls==("idle".equals(state)?1:0));check(first.ui.nativeCalls==("idle".equals(state)?0:1));
            first.controller.localEnabled(()->false);first.controller.players(false);check(!first.ui.panel.buttons.containsKey("local"));
            first.ui.panel.buttons.get("remote").run();check(first.controller.selectedId().equals("remote")&&first.ui.defaultSaves==1);check(first.api.plays==0&&first.api.transfers==0&&first.host.sets==0);
        }
        Fixture cancelDefault=new Fixture("Fixed default player");cancelDefault.controller.configure(map("defaultPlayer","","receiverEnabled",false),"ma");cancelDefault.controller.route(false);cancelDefault.ui.panel.buttons.get("Cancel").run();check(cancelDefault.ui.homeCalls==1&&cancelDefault.ui.defaultSaves==0);
        Fixture staleDefault=new Fixture("Fixed default player");staleDefault.controller.configure(map("defaultPlayer","","receiverEnabled",false),"ma");staleDefault.controller.route(false);Runnable staleChoice=staleDefault.ui.panel.buttons.get("remote");staleDefault.api.snapshot.players.remove("remote");staleChoice.run();check(staleDefault.ui.defaultSaves==0&&staleDefault.ui.message.contains("unavailable"));
        Fixture menu=new Fixture("Fixed default player");menu.controller.localIdentity(()->"local");menu.controller.players(false);
        check(menu.ui.panel.sections.equals(Arrays.asList("This device","Other devices")));check(menu.ui.panel.checked.contains("remote"));check(menu.ui.panel.subtitles.get("remote").startsWith("Default"));
        for(String removedEntry:Arrays.asList("Use default","Group","Player settings","Plugin preferences"))check(!menu.ui.panel.buttons.containsKey(removedEntry));
        check(menu.ui.panel.buttons.containsKey("Join group"));menu.ui.panel.buttons.get("Join group").run();check(menu.ui.groupCalls==1&&menu.api.plays==0&&menu.api.transfers==0);
        menu.api.snapshot.queues.put("local",new PlayerPolicy.Queue("local",true,0,"idle"));menu.ui.panel.buttons.get("local").run();check(menu.ui.nativeCalls==0&&menu.ui.libraryCalls==1);check(menu.api.plays==0&&menu.api.transfers==0&&menu.ui.defaultSaves==0);
        menu.controller.players(false);check(menu.ui.panel.checked.contains("local"));int calls=menu.ui.nativeCalls;menu.ui.panel.buttons.get("Close").run();check(menu.ui.nativeCalls==calls&&menu.ui.homeCalls==0);
        menu.controller.localEnabled(()->false);menu.controller.players(false);check(!menu.ui.panel.buttons.containsKey("local")&&!menu.ui.panel.sections.contains("This device"));
        Fixture labels=new Fixture("Fixed default player");final String[] label={""};labels.controller.readLibraryReturn(v->label[0]=v);check(label[0].equals("Back to player"));
        labels.api.snapshot.queues.put("remote",new PlayerPolicy.Queue("remote",true,0,"idle"));labels.controller.readLibraryReturn(v->label[0]=v);check(label[0].equals("Close"));
        labels.controller.route(true);check(labels.ui.homeCalls==1);
        labels.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","paused"));labels.controller.readLibraryReturn(v->label[0]=v);check(label[0].equals("Back to player"));labels.controller.route(true);check(labels.ui.nativeCalls==1);
        labels.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","playing"));labels.controller.readLibraryReturn(v->label[0]=v);check(label[0].equals("Now playing"));
        labels.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","idle"));labels.api.snapshot.queues.remove("remote");labels.controller.readLibraryReturn(v->label[0]=v);check(label[0].isEmpty());labels.controller.route(true);check(labels.ui.homeCalls==1&&labels.ui.message.contains("not assumed empty"));
        check(labels.api.plays==0&&labels.api.transfers==0&&labels.ui.defaultSaves==0);
        Fixture returnSelected=new Fixture("Follow Kiosk Satellite");returnSelected.controller.players(false);returnSelected.ui.panel.buttons.get("remote").run();
        returnSelected.api.snapshot.players.put("remote",new PlayerPolicy.Player("remote","remote","player",true,true,"","","remote","playing"));returnSelected.controller.readLibraryReturn(v->label[0]=v);check(label[0].equals("Now playing"));
        returnSelected.controller.route(true);check(returnSelected.controller.selectedId().equals("remote")&&returnSelected.ui.defaultSaves==0);check(returnSelected.api.plays==0&&returnSelected.api.transfers==0);
        Fixture emptySelection=new Fixture("Follow Kiosk Satellite");emptySelection.api.snapshot.queues.put("remote",new PlayerPolicy.Queue("remote",true,0,"idle"));emptySelection.controller.players(false);emptySelection.ui.panel.buttons.get("remote").run();check(emptySelection.ui.libraryCalls==1&&emptySelection.ui.nativeCalls==0);check(emptySelection.controller.selectedId().equals("remote")&&emptySelection.ui.defaultSaves==0);
        Fixture unknownSelection=new Fixture("Follow Kiosk Satellite");unknownSelection.api.snapshot.queues.clear();unknownSelection.controller.players(false);unknownSelection.ui.panel.buttons.get("remote").run();check(unknownSelection.ui.nativeCalls==1&&unknownSelection.ui.libraryCalls==0);
        System.out.println("PASS: " + checks + " mocked player controller checks (no network)");
    }
}
