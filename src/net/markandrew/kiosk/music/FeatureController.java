package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.*;

/** On-demand native tools; writes are explicit, identity-bound and never retried. */
final class FeatureController {
    private BooleanSupplier navigationHeld=()->false;
    void navigationHeld(BooleanSupplier held){navigationHeld=held;}
    interface Ui {
        void panel(String title, BooleanSupplier current, Consumer<TouchPanel> build);
        default void popup(String title,BooleanSupplier current,Consumer<TouchPanel> build){panel(title,current,build);}
        void dismiss(); void message(String text); void refresh();
        void play(BrowserItem item, String option, boolean shuffle, String start);
        void open(BrowserItem item); void players(Runnable after);
        default void preferences() {}
        default void preferences(Runnable back){preferences();}
        default void joined(String player) {}
        default void memberRemoved(String player,Runnable otherwise){otherwise.run();}
        default void library(){dismiss();}
    }
    private final FeatureApi api = new FeatureApi();
    private final ArtworkLoader artwork = new ArtworkLoader();
    private final IngressAccess access;
    private final ExecutorService worker;
    private final AtomicBoolean busy;
    private final Supplier<String> addon, selected;
    private final BooleanSupplier alive;
    private final Ui ui;
    private volatile long generation;
    private volatile boolean ownsBusy;
    private final ThreadLocal<BooleanSupplier> taskCurrent = new ThreadLocal<>();
    private final java.util.concurrent.ThreadPoolExecutor lyricsWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"music-lyrics");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile LyricsWatch activeLyrics;
    private Consumer<String> lyricsDiagnostic=text->{};
    void lyricsDiagnostics(Consumer<String> sink){lyricsDiagnostic=sink;}
    FeatureController(PluginHost host, IngressAccess access, ExecutorService worker, AtomicBoolean busy, Supplier<String> addon,
                      Supplier<String> selected, BooleanSupplier alive, Ui ui) {
        this.access = access; this.worker = worker; this.busy = busy; this.addon = addon; this.selected = selected; this.alive = alive; this.ui = ui;
    }
    void cancel() { generation++;eqMemoryState=null;eqModes=null; api.api.cancel();LyricsWatch watch=activeLyrics;if(watch!=null){watch.epoch++;watch.metadata.api.cancel();}lyricsWorker.getQueue().clear(); artwork.cancelPending(); if (ownsBusy) { ownsBusy = false; busy.set(false); } }
    void close() { cancel();lyricsWorker.shutdownNow(); artwork.stop(); }
    private Function<IngressAccess.Session,String> ownIdentity=session->"";
    private BooleanSupplier controllerMode=()->false;
    void controllerOnly(BooleanSupplier mode){controllerMode=mode;}
    private Consumer<String> joinDiagnostic=text->{};
    void joinDiagnostics(Consumer<String> sink){joinDiagnostic=sink;}
    void ownReceiver(Function<IngressAccess.Session, String> identity) { ownIdentity=identity;api.api.ownReceiver(identity); }
    void favorite(BrowserItem item,Consumer<Boolean> done,Consumer<String> fail){
        if(!alive.getAsBoolean()||!busy.compareAndSet(false,true)){fail.accept("Another action is pending. No duplicate was sent.");return;}
        ownsBusy=true;final long ticket=++generation;final String player=selected.get();
        BooleanSupplier current=()->alive.getAsBoolean()&&generation==ticket&&selected.get().equals(player);
        access.ensure(addon.get(),current,session->{try{worker.submit(()->{
            FeatureApi.Connection c=api.new Connection(session,current,()->verifySession(session,current));
            try{
                api.api.identity(session);FeatureApi.controller(item.type);
                boolean enabled=!api.favoriteItem(c,item.uri).optBoolean("favorite");
                api.favorite(c,item.uri,enabled);if(current.getAsBoolean())done.accept(enabled);
            }catch(Exception error){if(current.getAsBoolean())fail.accept(c.submitted?"Favourite may already have changed. Check MA before retrying; no automatic retry was made.":"Favourite was not changed. Check the connection and item availability.");}
            finally{if(generation==ticket&&ownsBusy){ownsBusy=false;busy.set(false);}}
        });}catch(RuntimeException ignored){if(current.getAsBoolean()){ownsBusy=false;busy.set(false);fail.accept("Library is stopping.");}}},error->{if(current.getAsBoolean()){ownsBusy=false;busy.set(false);fail.accept(error);}});
    }
    private EqApi.State eqMemoryState;
    private EqModeStore eqModes;
    void equaliser(){
        runPopup("Equaliser",(c,player)->{
            EqApi.State state=new EqApi().load(api,c,player);
            FeatureApi.require(state.writable,"EQ requires config.players.write. MA 2.10.x normally requires an administrator-authorised Ingress identity. No permissions were changed.");
            eqPanel(state,null,null);
        });
    }
    private void eqPanel(EqApi.State state,Boolean advanced,EqPresetStore library){
        renderPopup("Equaliser · "+state.name,p->{
            p.eqPresentation();
            p.closeAction("Close",()->{cancel();ui.dismiss();});
            if(!state.writable){
                p.note("This account can read but cannot save EQ. config.players.write is required; permissions were not changed.");
                p.note("DSP: "+(state.draft.config.optBoolean("enabled")?"enabled":"disabled"));
                JSONArray filters=state.draft.config.optJSONArray("filters");
                for(int i=0;i<filters.length();i++){
                    JSONObject f=filters.optJSONObject(i);if(f==null)continue;
                    p.section("Filter "+(i+1)+" · "+f.optString("type")+" · "+(f.optBoolean("enabled")?"on":"off"));
                    if("tone_control".equals(f.optString("type")))p.note("Bass "+f.optDouble("bass_level")+" · Mid "+f.optDouble("mid_level")+" · Treble "+f.optDouble("treble_level")+" dB");
                    if("parametric_eq".equals(f.optString("type"))){
                        p.note("Preamp: "+f.opt("preamp")+" dB");JSONArray bands=f.optJSONArray("bands");
                        if(bands!=null)for(int j=0;j<bands.length();j++){JSONObject b=bands.optJSONObject(j);if(b!=null)p.note("Band "+(j+1)+": "+b.optString("type")+" · "+b.optDouble("frequency")+" Hz · "+b.optDouble("gain")+" dB · Q "+b.optDouble("q"));}
                    }
                }
                return;
            }
            try{
                EqPresetStore presets=library==null?new EqPresetStore(p.getContext()):library;
                if(eqMemoryState!=state){eqModes=new EqModeStore(p.getContext(),state);eqModes.restore(state.draft);eqMemoryState=state;}
                boolean view=advanced==null?EqViewPreference.advanced(p.getContext()):advanced;
                new EqEditor(p,state.draft,view,presets,next->{
                    try{EqViewPreference.save(p.getContext(),next);eqPanel(state,next,presets);}
                    catch(Exception error){p.message("EQ view could not be remembered. No EQ values were changed.");}
                },()->makeEqPreset(state,presets),()->editEqPresets(state,presets),()->applyEq(state,presets,view),()->{cancel();ui.dismiss();});
            }
            catch(Exception error){p.message(error instanceof IllegalArgumentException?error.getMessage():"EQ or custom preset settings could not be loaded. Nothing was changed; check MA or reopen the equaliser.");}
        });
    }
    private void makeEqPreset(EqApi.State state,EqPresetStore presets){
        renderPopup("Make Preset",p->{
            p.note("Custom curves are stored on this kiosk only, after Apply. This captures the 10-band curve and shared preamp, not other DSP filters.");
            android.widget.EditText name=p.input("Preset name","");name.setContentDescription("Preset name");
            p.formActions("Create preset",()->{try{
                EqGraphic graphic=new EqGraphic(state.draft);presets.make(name.getText().toString(),new EqPresets.Curve(graphic.preamp(),graphic.gains()));eqPanel(state,true,presets);
            }catch(Exception error){p.message(error.getMessage());}},"Cancel",()->eqPanel(state,true,presets));
        });
    }
    private void editEqPresets(EqApi.State state,EqPresetStore presets){
        renderPopup("Edit Presets",p->{
            p.note("Built-in presets are protected. Rename/delete changes to custom presets are staged until Apply.");
            if(presets.custom.isEmpty())p.note("No custom presets yet.");
            for(String key:new ArrayList<>(presets.custom.keySet()))p.add(key,()->{
                renderPopup("Edit Preset · "+key,form->{
                    android.widget.EditText name=form.input("Preset name",key);
                    form.formActions("Rename",()->{try{presets.rename(key,name.getText().toString());editEqPresets(state,presets);}catch(Exception e){form.message(e.getMessage());}},"Cancel",()->editEqPresets(state,presets));
                    form.add("Delete",()->renderPopup("Delete Preset",confirm->{confirm.note("Delete custom preset “"+key+"”? This is staged until Apply.");confirm.formActions("Confirm delete",()->{presets.delete(key);editEqPresets(state,presets);},"Cancel",()->editEqPresets(state,presets));}));
                });
            });
            p.add("Back to equaliser",()->eqPanel(state,true,presets));
        });
    }
    private void applyEq(EqApi.State state,EqPresetStore presets,boolean advanced){
        final EqModeStore modes=eqModes;
        final EqApi.State applying;
        try{applying=state.withDraft(state.draft.applied(advanced));}catch(Exception error){ui.message("EQ could not be prepared. Nothing was submitted.");return;}
        runPopup("Apply equaliser",(c,player)->{
            presets.verifyUnchanged();
            EqApi.State verified;
            if(applying.draft.changed())verified=new EqApi().save(api,c,player,applying);
            else {new EqApi().verify(api,c,player,applying);verified=applying;}
            FeatureApi.require(c.current.getAsBoolean(),"View changed before custom presets were saved.");
            verifySession(c.session,c.current);presets.commit();
            modes.commit(applying.draft,verified.draft.baseline);
            // Rebase to the verified MA document. Another Apply must compare with
            // this baseline, not with the settings from when EQ was first opened.
            eqMemoryState=null;eqPanel(verified,advanced,null);
        });
    }
    void visibility(String receiverId) {
        visibility(receiverId,null);
    }
    void visibility(String receiverId,Runnable back) {
        run("Player details",back, (c, player) -> {
            JSONArray rows = c.array("players/all", FeatureApi.args("return_unavailable", true, "return_protocol_players", true));
            String report = VisibilityDiagnostics.summary(rows, receiverId);
            render("Player details",back, p -> p.note(report));
        });
    }
    interface Work { void run(FeatureApi.Connection c, String player) throws Exception; }
    private void run(String title, Work work) {
        run(title,null,work);
    }
    private void run(String title,Runnable back,Work work){
        run(title,back,work,false);
    }
    private void runPopup(String title,Work work){run(title,null,work,true);}
    private void run(String title,Runnable back,Work work,boolean popup){
        if (!alive.getAsBoolean() || !busy.compareAndSet(false, true)) return;
        ownsBusy = true; final long ticket = ++generation; final String player = selected.get();
        BooleanSupplier current = () -> alive.getAsBoolean() && generation == ticket && selected.get().equals(player);
        Consumer<TouchPanel> waiting=p -> { p.guard(current);p.note("Loading…");p.closeAction(back==null?"Cancel":"‹ Back", () -> { cancel(); if(back==null)ui.dismiss();else back.run(); }); };
        if(popup)ui.popup(title,current,waiting);else ui.panel(title,current,waiting);
        access.ensure(addon.get(), current, session -> {
            try { worker.submit(() -> {
                FeatureApi.Connection c = api.new Connection(session, current, () -> verifySession(session, current));
                taskCurrent.set(current);
                try { if (current.getAsBoolean()) { api.api.identity(session); work.run(c, player); } }
                catch (Exception error) {
                    if (current.getAsBoolean()) render(title,back, p -> p.note(c.submitted
                        ? "Action may already have been submitted. Check MA before retrying; no automatic retry was made."
                        : error instanceof IllegalArgumentException ? error.getMessage() : "Music Assistant could not complete the read. Check permissions and connection, then retry explicitly."),popup);
                } finally { taskCurrent.remove(); if (generation == ticket && ownsBusy) { ownsBusy = false; busy.set(false); } }
            }); } catch (RuntimeException error) { cancel(); ui.message("Browser is stopping."); }
        }, error -> { if (current.getAsBoolean()) { ownsBusy = false; busy.set(false); render(title,back, p -> p.note(error),popup); } });
    }
    private void verifySession(IngressAccess.Session expected, BooleanSupplier current) throws Exception {
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean same = new AtomicBoolean();
        access.ensure(addon.get(), current, latest -> { same.set(latest == expected); finished.countDown(); }, error -> finished.countDown());
        FeatureApi.require(finished.await(8, java.util.concurrent.TimeUnit.SECONDS) && same.get() && current.getAsBoolean(), "HA user, server or connection changed. Nothing was submitted; reopen the action.");
    }
    private void render(String title, Consumer<TouchPanel> build) {
        render(title,null,build);
    }
    private void render(String title,Runnable back,Consumer<TouchPanel> build){
        render(title,back,build,false);
    }
    private void renderPopup(String title,Consumer<TouchPanel> build){render(title,null,build,true);}
    private void render(String title,Runnable back,Consumer<TouchPanel> build,boolean popup){
        if (!validTask()) return;
        final long ticket = generation; final String player = selected.get();
        BooleanSupplier current = () -> alive.getAsBoolean() && generation == ticket && selected.get().equals(player);
        Consumer<TouchPanel> content=p -> {
            if (!current.getAsBoolean()) return;
            p.guard(current); p.closeAction(back==null?"Close":"‹ Back", () -> { cancel(); if(back==null)ui.dismiss();else back.run(); }); build.accept(p);
        };
        if(popup)ui.popup(title,current,content);else ui.panel(title,current,content);
    }
    private void confirm(String title, String explanation, Runnable action) {
        render(title, p -> { p.note(explanation); p.formActions("Confirm", action,"Cancel", () -> { cancel(); ui.dismiss(); }); });
    }
    private void confirm(String title,String explanation,Runnable action,Runnable back){
        render(title,back,p->{p.note(explanation);p.formActions("Confirm",action,"Cancel",()->{cancel();back.run();});});
    }
    private boolean validTask() { BooleanSupplier current = taskCurrent.get(); return alive.getAsBoolean() && (current == null || current.getAsBoolean()); }
    private void complete(String message, Runnable after, boolean dismiss) {
        if (!validTask()) return;
        generation++; if (ownsBusy) { ownsBusy = false; busy.set(false); }
        ui.message(message); if (dismiss) ui.dismiss(); if (after != null) after.run();
    }
    private void done(String message) { complete(message, null, true); }
    private void doneRefresh(String message) { complete(message, ui::refresh, true); }
    private void updated(String message, Runnable refresh) { complete(message, refresh, false); }
    private interface PanelRead{void read(FeatureApi.Connection connection)throws Exception;}
    private void watchPanel(TouchPanel panel,BooleanSupplier owner,PanelRead read){
        watchPanel(panel,owner,read,null);
    }
    private void watchPanel(TouchPanel panel,BooleanSupplier owner,PanelRead read,Runnable retry){
        AtomicBoolean loading=new AtomicBoolean();
        final long ticket=generation;final String player=selected.get();
        BooleanSupplier current=()->owner.getAsBoolean()&&alive.getAsBoolean()&&ticket==generation&&player.equals(selected.get())&&panel.isAttachedToWindow();
        panel.every(1000,()->{
            if(!current.getAsBoolean()||busy.get()||panel.interacting()||!loading.compareAndSet(false,true))return;
            access.ensure(addon.get(),current,session->{try{worker.submit(()->{
                try{if(current.getAsBoolean()&&!busy.get()){api.api.identity(session);read.read(api.new Connection(session,current));panel.post(()->{if(current.getAsBoolean()&&!busy.get())panel.liveRecovered();});}}
                catch(Exception ignored){panel.post(()->{if(current.getAsBoolean()&&!busy.get()){panel.liveError(retry==null?"Live update unavailable. Refresh to reconnect.":"Live update unavailable. Check the connection or Retry.");if(retry!=null)panel.liveRetry(retry);}});}
                finally{loading.set(false);}
            });}catch(RuntimeException ignored){loading.set(false);}},error->{loading.set(false);panel.post(()->{if(current.getAsBoolean()){panel.liveError("Live connection unavailable. Check Player settings.");if(retry!=null)panel.liveRetry(retry);}});});
        });
    }
    void item(BrowserItem item, BrowserItem parent, List<BrowserItem> rows) { item(item, parent, rows, null); }
    void item(BrowserItem item, BrowserItem parent, List<BrowserItem> rows, JSONArray snapshot) {
        renderPopup(item.name,p->{for(ItemAction action:itemActions(item,parent,rows,snapshot))p.add(action.label,action.action);});
    }
    static final class ItemAction{final String label;final Runnable action;ItemAction(String label,Runnable action){this.label=label;this.action=action;}}
    List<ItemAction> playbackActions(BrowserItem item){
        List<ItemAction> all=itemActions(item,null,java.util.Collections.emptyList(),null),result=new ArrayList<>();
        for(ItemAction action:all)if(action.label.startsWith("Play ")||action.label.equals("Add to queue"))result.add(action);
        if(item.type.equals("track")&&item.playable())result.add(new ItemAction("Play shuffled · replace queue",()->ui.play(item,"replace",true,"")));
        return result;
    }
    List<ItemAction> itemActions(BrowserItem item,BrowserItem parent,List<BrowserItem> rows,JSONArray snapshot){
            List<ItemAction> result=new ArrayList<>();
            BiConsumer<String,Runnable> add=(label,action)->result.add(new ItemAction(label,action));
            if(item.playable())add.accept("Play on…", () -> ui.players(() -> ui.play(item,item.type.equals("album")?"replace":"play",false,"")));
            if (item.playable()) {
                String[] options = {"play", "next", "add", "replace", "replace_next"};
                String[] labels = {"Play now · keep queue", "Play next · keep queue", "Add to queue", "Play now · replace queue", "Play next · replace queue"};
                for (int i = 0; i < options.length; i++) { final String option = options[i]; add.accept(labels[i], () -> ui.play(item, option, false, "")); }
                if (item.type.equals("album") || item.type.equals("playlist") || item.type.equals("artist")) add.accept("Play shuffled · replace queue", () -> ui.play(item, "replace", true, ""));
            }
            if (parent != null && (parent.type.equals("album") || parent.type.equals("playlist")) && item.type.equals("track"))
                add.accept("Play " + parent.type + " from here", () -> ui.play(parent, "play", false, item.id));
            if (item.type.equals("track")) add.accept("Add to playlist…", () -> playlists(new JSONArray().put(item.uri)));
            if (parent != null && parent.type.equals("playlist") && parent.editable && !parent.dynamic && item.position > 0 && snapshot != null)
                add.accept("Remove this playlist occurrence…", () -> confirm("Remove from playlist?", "Provider content and other occurrences are kept. Playlist positions will be checked again.",
                    () -> run("Remove playlist occurrence", (c, player) -> { api.removePlaylistEntry(c, parent.uri, item, snapshot); doneRefresh("Playlist occurrence removed."); })));
             if (!item.type.equals("podcast_episode")) add.accept("Manage library…", () -> library(item));
            return result;
    }
    private void library(BrowserItem item) {
        run("Manage library · " + item.name, (c, player) -> {
            FeatureApi.LibraryInfo info = api.library(c, item.uri);
            render("Manage library · " + item.name, p -> {
                List<ItemAction> choices=libraryActions(item,info);for(ItemAction choice:choices)p.add(choice.label,choice.action);
                if(choices.isEmpty())p.note("No library-management actions are available for this account/item.");
            });
        });
    }
    List<ItemAction> libraryActions(BrowserItem item,FeatureApi.LibraryInfo info){
        List<ItemAction> result=new ArrayList<>();
        if (info.canAdd) result.add(new ItemAction("Add to library", () -> run("Add to library", (next, id) -> {
                     FeatureApi.LibraryInfo fresh = api.library(next, item.uri); FeatureApi.require(fresh.canAdd, "Library permission or identity changed.");
                     Object added = next.write("music/library/add_item", FeatureApi.args("item", item.uri));
                     FeatureApi.require(added instanceof JSONObject && "library".equals(next.item(((JSONObject) added).getString("uri")).optString("provider")), "Addition is unverified. Inspect MA before retrying.");
                     doneRefresh("Added to library.");
                })));
        if (info.canRemove) {
            if(item.type.equals("album"))result.add(new ItemAction("Remove from library…", () -> run("Preview affected tracks", (next, id) -> {
                         JSONArray tracks = api.albumTracks(next, info);
                         confirm("Remove “"+item.name+"” and " + tracks.length() + " tracks?", "Remove the album and all its linked library tracks, including favourites, history and their links to other albums. Provider content and the playback queue are kept. The complete scope is rechecked before removal.",
                             () -> run("Remove album and tracks", (latest, target) -> { api.removeLibrary(latest, info, tracks, false); doneRefresh("Album and linked MA tracks removed."); }));
                     })));
            else result.add(new ItemAction("Remove from library…",()->confirm("Remove from library?","Remove “"+item.name+"” and its library favourites/history. Provider content and the playback queue are kept. Provider sync may restore the item.",()->run("Remove from library",(next,id)->{api.removeLibrary(next,info,null,false);doneRefresh("Removed from library.");}))));
        }
        if(info.deletion!=null)result.add(new ItemAction("Delete provider playlist…",()->confirm("Delete from "+info.deletion.optString("name")+"?","Permanently deletes “"+item.name+"” from its owning provider. This cannot be undone here. Owner and sync-back support will be checked again.",()->run("Delete provider playlist",(next,id)->{api.removeLibrary(next,info,null,true);doneRefresh("Provider playlist deletion verified.");}))));
        return result;
    }
    static List<ItemAction> mergeLibraryActions(List<ItemAction> base,List<ItemAction> management){
        List<ItemAction> result=new ArrayList<>();for(ItemAction action:base)if(!action.label.equals("Manage library…"))result.add(action);
        if(management.size()==1)result.add(management.get(0));else if(management.size()>1)for(ItemAction action:base)if(action.label.equals("Manage library…"))result.add(action);
        return result;
    }
    void resolveItemActions(BrowserItem item,BrowserItem parent,List<BrowserItem> rows,JSONArray snapshot,BooleanSupplier current,Consumer<List<ItemAction>> done,Consumer<String> failed){
        final String server=addon.get(),player=selected.get();BooleanSupplier valid=()->alive.getAsBoolean()&&current.getAsBoolean()&&server.equals(addon.get())&&player.equals(selected.get());
        if(item.type.equals("podcast_episode")){done.accept(itemActions(item,parent,rows,snapshot));return;}
        access.ensure(server,valid,session->{try{worker.submit(()->{
            try{if(!valid.getAsBoolean())return;FeatureApi.Connection c=api.new Connection(session,valid);FeatureApi.LibraryInfo info=api.library(c,item.uri);List<ItemAction> merged=mergeLibraryActions(itemActions(item,parent,rows,snapshot),libraryActions(item,info));if(valid.getAsBoolean())done.accept(merged);}
            catch(Exception ignored){if(valid.getAsBoolean())failed.accept("Library actions unavailable · Retry…");}
        });}catch(RuntimeException ignored){if(valid.getAsBoolean())failed.accept("Library actions unavailable · Retry…");}},error->{if(valid.getAsBoolean())failed.accept("Library actions unavailable · Retry…");});
    }
    void playlists(JSONArray uris) {
        run("Add to playlist", (c, player) -> {
            JSONArray playlists = api.editablePlaylists(c);
            render("Add " + uris.length() + " track(s) to playlist", p -> {
                p.note("MA may skip duplicates or tracks unavailable on the destination provider.");
                p.add("Create new playlist…", () -> createPlaylist(uris));
                for (int i = 0; i < playlists.length(); i++) {
                    JSONObject playlist = playlists.optJSONObject(i); if (playlist == null) continue;
                    p.add(playlist.optString("name"), () -> run("Add tracks to playlist", (next, id) -> { api.addTracks(next, playlist.getString("uri"), uris); done("Add tracks acknowledged. Check the destination playlist for provider skips."); }));
                }
                if (playlists.length() == 0) p.note("No editable track playlists available.");
            });
        });
    }
    void createPlaylist(JSONArray uris) {
        runPopup("Create playlist", (c, player) -> {
            JSONArray providers = api.playlistProviders(c);
            playlistForm(uris,providers,"","","");
        });
    }
    private void playlistForm(JSONArray uris,JSONArray providers,String savedName,String savedDestination,String warning){
            renderPopup("Create playlist", p -> {
                if(!warning.isEmpty())p.message(warning);
                android.widget.EditText name = p.input("Playlist name", savedName);
                List<DiscoveryPolicy.Choice> choices = new ArrayList<>(); choices.add(new DiscoveryPolicy.Choice("", "Music Assistant (local)"));
                for (int i = 0; i < providers.length(); i++) { JSONObject row = providers.optJSONObject(i); if (row != null) choices.add(new DiscoveryPolicy.Choice(row.optString("instance_id"), row.optString("name"))); }
                String[] destination = {savedDestination}; p.picker("Save to", choices, savedDestination, id -> destination[0] = id);
                p.note(uris.length() == 0 ? "Create an empty playlist." : "Create and add " + uris.length() + " tracks in the captured order. Providers may skip duplicates/unavailable tracks. Creation and addition are separate operations; a partial result is never retried automatically.");
                p.formActions(uris.length() == 0 ? "Create playlist" : "Create and add tracks", () -> {
                    String title = name.getText().toString().trim();
                    if(title.isEmpty()){name.setError("Enter a playlist name.");name.requestFocus();return;}
                    runPopup("Create playlist", (next, id) -> {
                        try{
                        JSONObject created = api.create(next, title, destination[0]);
                        if (uris.length() > 0) api.addTracks(next, created.getString("uri"), uris);
                        doneRefresh("Playlist creation acknowledged" + (uris.length() > 0 ? " and tracks submitted." : "."));
                        }catch(Exception error){
                            if(next.submitted)renderPopup("Create playlist",form->form.note("Playlist creation or track addition may already have happened. Check the destination in MA before creating again; no automatic retry was made."));
                            else playlistForm(uris,providers,title,destination[0],error instanceof IllegalArgumentException?error.getMessage():"Nothing was submitted. Check the connection and destination, then try explicitly.");
                        }
                    });
                },()->{cancel();ui.dismiss();});
            });
    }
    void queue(int limit) {
        run("Queue", (c, player) -> {
            QueueDisplay initial=readQueue(c,player,limit);
            render("Queue · "+initial.queue.optString("display_name",initial.queue.optString("queue_id")),p->{
                final java.util.concurrent.atomic.AtomicReference<QueueDisplay> shown=new java.util.concurrent.atomic.AtomicReference<>(initial);
                final long[] lastFull={System.nanoTime()};
                p.liveContent(()->populateQueue(p,initial,limit,c,true));
                watchPanel(p,c.current,next->{
                    JSONObject summary=next.queue(player);
                    if(summary.optInt("items",-1)==0){
                        PlayerPolicy.Snapshot scope=api.api.playbackSnapshot(next.session,player);String leader=scope.leader(player).id;
                        JSONObject status=next.object("players/get",FeatureApi.args("player_id",leader));
                        if(!navigationHeld.getAsBoolean()&&EmptyPlayback.confirmed(status,summary,scope.queue(player))){p.post(()->{if(!navigationHeld.getAsBoolean()&&next.current.getAsBoolean()&&!busy.get()&&!p.interacting()){cancel();ui.dismiss();ui.library();}});return;}
                    }
                    if(queueDisplayMark(summary).equals(queueDisplayMark(shown.get().queue))&&System.nanoTime()-lastFull[0]<java.util.concurrent.TimeUnit.SECONDS.toNanos(5))return;
                    QueueDisplay fresh=readQueue(next,player,limit);
                    lastFull[0]=System.nanoTime();
                    p.post(()->{
                        if(!next.current.getAsBoolean()||busy.get()||p.interacting())return;
                        QueueDisplay before=shown.get();
                        if(!queueDisplayMark(before.queue).equals(queueDisplayMark(fresh.queue))||
                           !FeatureApi.sameQueueRows(before.entries,fresh.entries)||!FeatureApi.sameQueueRows(before.upcoming,fresh.upcoming)){
                            p.liveContent(()->populateQueue(p,fresh,limit,next,false));shown.set(fresh);
                        }
                    });
                });
            });
        });
    }
    private static final class QueueDisplay{
        JSONObject queue;JSONArray entries,upcoming;int current;
    }
    private static String queueDisplayMark(JSONObject q){
        return FeatureApi.queueMark(q)+":"+q.optString("state")+":"+q.optBoolean("ended")+":"+q.opt("index_in_buffer")+":"+q.optBoolean("shuffle_enabled")+":"+q.optString("repeat_mode");
    }
    private QueueDisplay readQueue(FeatureApi.Connection c,String player,int limit)throws Exception{
            JSONObject q = c.queue(player); String queue = q.getString("queue_id");
            JSONArray entries = c.array("player_queues/items", FeatureApi.args("queue_id", queue, "offset", 0, "limit", limit));
            int current = q.optInt("current_index", -1);
            JSONArray upcoming = current >= entries.length() && !q.optBoolean("ended")
                ? c.array("player_queues/items", FeatureApi.args("queue_id", queue, "offset", current, "limit", 100)) : new JSONArray();
            JSONObject verified = c.object("player_queues/get", FeatureApi.args("queue_id", queue));
            FeatureApi.require(FeatureApi.queueMark(q).equals(FeatureApi.queueMark(verified)), "Queue changed while loading. Refresh it.");
            QueueDisplay result=new QueueDisplay();result.queue=q;result.entries=entries;result.upcoming=upcoming;result.current=current;return result;
    }
    private void populateQueue(TouchPanel p,QueueDisplay data,int limit,FeatureApi.Connection c,boolean focus){
                JSONObject q=data.queue;String queue=q.optString("queue_id");JSONArray entries=data.entries,upcoming=data.upcoming;int current=data.current;
                p.note(q.optInt("items") + " items · " + (current < 0 ? "No current item" : "Current: " + (current + 1)));
                p.tool("refresh","Refresh queue",false, () -> queue(limit));
                p.tool("shuffle","Shuffle",q.optBoolean("shuffle_enabled"), () -> queueSetting(queue,"player_queues/shuffle","shuffle_enabled",!q.optBoolean("shuffle_enabled")));
                p.tool("one".equals(q.optString("repeat_mode"))?"repeat-once":"repeat","Repeat · "+q.optString("repeat_mode","off"),!"off".equals(q.optString("repeat_mode","off")),()->queueSetting(queue,"player_queues/repeat","repeat_mode","off".equals(q.optString("repeat_mode","off"))?"all":"all".equals(q.optString("repeat_mode"))?"one":"off"));
                Runnable save = () -> run("Read full queue", (next, id) -> {
                    JSONObject fresh = next.queue(id); FeatureApi.require(queue.equals(fresh.optString("queue_id")), "Queue changed.");
                    JSONArray all = api.allQueue(next, fresh), tracks = new JSONArray(); int excluded = 0;
                    for (int i = 0; i < all.length(); i++) { JSONObject row = all.getJSONObject(i), media = row.optJSONObject("media_item"); if (row.optBoolean("available", true) && media != null && media.optString("media_type").equals("track") && !media.optString("uri").isEmpty()) tracks.put(media.getString("uri")); else excluded++; }
                    FeatureApi.require(tracks.length() > 0, "No available identifiable music tracks in the queue.");
                    confirm("Save " + tracks.length() + " tracks?", "Captured order includes previously played tracks. " + excluded + " unavailable/non-track items excluded.", () -> playlists(tracks));
                });
                Runnable clear = () -> confirm("Clear queue?", "Remove all items from this queue?", () -> run("Clear queue", (next, id) -> {
                    JSONObject fresh = next.queue(id); FeatureApi.require(FeatureApi.queueMark(fresh).equals(FeatureApi.queueMark(q)), "Queue changed. Review and confirm again.");
                    JSONArray visible = next.array("player_queues/items", FeatureApi.args("queue_id", queue, "offset", 0, "limit", limit));
                    FeatureApi.require(FeatureApi.sameQueueRows(visible, entries), "Displayed queue contents changed. Review and confirm again.");
                    if (upcoming.length() > 0) FeatureApi.require(FeatureApi.sameQueueRows(upcoming, next.array("player_queues/items", FeatureApi.args("queue_id", queue, "offset", current, "limit", 100))), "Upcoming queue contents changed. Review and confirm again.");
                    next.write("player_queues/clear", FeatureApi.args("queue_id", queue));
                    boolean empty=false;
                    for(int delay:new int[]{0,150,350,700}){
                        if(delay>0)Thread.sleep(delay);
                        JSONObject checked=next.queue(id);FeatureApi.require(queue.equals(checked.optString("queue_id")),"Queue changed after clearing. Nothing else was submitted.");
                        if(checked.optInt("items",-1)==0){empty=true;break;}
                    }
                    FeatureApi.require(empty,"Clear submitted; empty queue not confirmed. Check playback before retrying.");
                    complete("",ui::library,true);
                }));
                p.tool("content-save","Save queue as playlist",false,save);
                p.tool("playlist-remove","Clear queue",false,clear);
                queueRows(p, entries, 0, current, q, queue, limit,c);
                if (upcoming.length() > 0) queueRows(p, upcoming, current, current, q, queue, limit,c);
                if (limit < q.optInt("items") && limit < 10000) p.add("Load more tracks", () -> queue(Math.min(10000, limit + 100)));
                if (q.optInt("items") == 0) p.note("Queue is empty. Choose music from Library.");
                if(focus)p.focusCurrent();
    }
    private void queueRows(TouchPanel p, JSONArray rows, int offset, int current, JSONObject q, String queue, int limit,FeatureApi.Connection context) {
        String lastSection="";
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            String id = row.optString("queue_item_id"); int index = offset + i;
            JSONObject media = row.optJSONObject("media_item"); String name = media == null ? row.optString("name") : media.optString("name");
            boolean now=index==current&&!q.optBoolean("ended");
            String section=q.optBoolean("ended")||index<current?"Previously played":now?"Now playing":"Playing next";
            if(!lastSection.equals(section)){p.section(section);lastSection=section;}
            BrowserItem display=new BrowserItem(media==null?new JSONObject():media);
            String duration=String.format(java.util.Locale.ROOT,"%d:%02d",row.optInt("duration",display.duration)/60,row.optInt("duration",display.duration)%60);
            String imagePath="";try{imagePath=new NowPlayingData(new JSONObject(),new JSONObject().put("current_item",row)).image;}catch(Exception ignored){}
            final String path=imagePath;
            boolean removable=FeatureApi.canRemoveQueueItem(q,index)&&row.optBoolean("available",true);
            Runnable remove=()->confirm("Remove queue item?",name+"\nCurrent item and MA committed-buffer restrictions are checked again.",
                ()->run("Remove queue item",(c,player)->{api.queueItem(c,player,queue,id,true);queueAfterWrite(limit);}));
            Runnable playlist=media!=null&&"track".equals(media.optString("media_type"))&&!media.optString("uri").isEmpty()?()->playlists(new JSONArray().put(media.optString("uri"))):null;
            p.queueActionRow(name,display.subtitle+" · "+duration,now?"▶":String.valueOf(index+1),now,row.optBoolean("available",true),
                done->artwork.load(context.session,path,()->context.current.getAsBoolean()&&p.isAttachedToWindow(),done),
                ()->run("Play queue item",(next,player)->{api.queueItem(next,player,queue,id,false);done("Queue item playback acknowledged.");}),playlist,remove,removable);
        }
    }
    private void queueAfterWrite(int limit) { updated("Queue update acknowledged.", () -> queue(limit)); }
    private void queueSetting(String queue, String command, String field, Object value) {
        run("Update queue", (c, player) -> { FeatureApi.require(queue.equals(c.queue(player).optString("queue_id")), "Queue changed."); c.write(command, FeatureApi.args("queue_id", queue, field, value)); updated("Queue setting acknowledged.", () -> queue(100)); });
    }
    void group() {
        group(null);
    }
    void group(Runnable back){
        run("Manage group / volume",back, (c, player) -> {
            JSONArray rows = c.array("players/all", FeatureApi.args("return_unavailable", true));
            JSONObject leader = FeatureApi.groupLeader(rows, player); String leaderId = leader.getString("player_id");
            PlayerPolicy.Snapshot display=MusicApi.parseSnapshot(rows,c.array("player_queues/all",new JSONObject()),"");
            Set<String> members = FeatureApi.members(rows, leader);
            String layoutKey=groupLayoutKey(rows,leader);
            render("Group · " + leader.optString("name"),back, p -> {
                java.util.Map<String,TouchPanel.VolumeControl> controls=new java.util.LinkedHashMap<>();
                java.util.Map<String,android.widget.LinearLayout> cards=new java.util.LinkedHashMap<>();
                java.util.Map<String,String> roles=new java.util.LinkedHashMap<>();
                p.navigation("speaker-multiple","Join group",()->joinLocalGroup(()->group(back)));
                boolean grouped = leader.optString("type").equals("group") || members.stream().anyMatch(id -> !id.equals(leaderId));
                if(grouped)volume(p, leader, leaderId, true,controls,members,null);
                boolean supported = FeatureApi.contains(leader.optJSONArray("supported_features"), "set_members");
                if (!supported) p.note("This player does not expose editable group membership.");
                p.section("In this group");boolean availableSection=false;
                for (JSONObject child : FeatureApi.groupRows(rows,leaderId,members)) {
                    String id = child.optString("player_id");
                    boolean member = members.contains(id), fixed = FeatureApi.contains(leader.optJSONArray("static_group_members"), id);
                    boolean add = !member && child.optBoolean("available") && child.optBoolean("enabled",true) && !child.optBoolean("hidden") && !child.optBoolean("hide_in_ui") && (child.optString("type").equals("player") || child.optString("type").equals("stereo_pair")) && FeatureApi.contains(leader.optJSONArray("can_group_with"), id);
                    boolean editable=supported&&!id.equals(leaderId)&&child.optBoolean("available")&&(add||(member&&!fixed));
                    Runnable change=()->run("Update group",()->group(back),(next,selected)->{api.member(next,selected,leaderId,id,member);updated("",()->{if(member)ui.memberRemoved(id,()->group(back));else group(back);});});
                    if(member){
                        String role=id.equals(leaderId)?grouped?"Group leader":"Independent player":fixed?"Fixed group member":"Group member";
                        android.widget.LinearLayout card=p.memberCard(child.optString("name"),role+"\n"+display.playbackDescription(id)+(child.optBoolean("available")?"":" · offline"),id.equals(leaderId),editable?change:null);
                        cards.put(id,card);roles.put(id,role);
                        volume(p,child,leaderId,false,controls,members,card);
                    }else if(add&&supported){if(!availableSection){p.section("Available to add");availableSection=true;}p.row(child.optString("name"),display.playbackDescription(id),"+",false,true,change,null);}
                }
                watchPanel(p,c.current,next->{
                    JSONArray fresh=next.array("players/all",FeatureApi.args("return_unavailable",true));
                    JSONObject liveLeader=FeatureApi.groupLeader(fresh,player);
                    boolean same=liveLeader.optString("player_id").equals(leaderId)&&FeatureApi.members(fresh,liveLeader).equals(members)&&layoutKey.equals(groupLayoutKey(fresh,liveLeader));
                    PlayerPolicy.Snapshot status=MusicApi.parseSnapshot(fresh,next.array("player_queues/all",new JSONObject()),"");
                    p.post(()->{
                        if(!next.current.getAsBoolean()||busy.get()||p.interacting())return;
                        if(!same){group(back);return;}
                        p.preserveScroll(()->{
                            controls.forEach((key,widget)->{boolean group=key.startsWith("group:");JSONObject row=FeatureApi.byId(fresh,"player_id",key.substring(key.indexOf(':')+1));
                                if(row!=null)widget.update(row.optInt(group?"group_volume":"volume_level"),row.optBoolean(group?"group_volume_muted":"volume_muted"),VolumeDisplay.silent(row,group));});
                            cards.forEach((id,card)->p.memberDetail(card,roles.get(id)+"\n"+status.playbackDescription(id)));
                        });
                    });
                },()->group(back));
            });
        });
    }
    private static String groupLayoutKey(JSONArray rows,JSONObject leader)throws Exception{
        JSONArray key=new JSONArray();key.put(leader.optString("player_id"));key.put(FeatureApi.members(rows,leader).toString());
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),fields=new JSONObject();
            for(String field:new String[]{"player_id","name","type","available","enabled","hidden","hide_in_ui","volume_control","mute_control","static_group_members","can_group_with","supported_features"})fields.put(field,row.opt(field));
            key.put(fields);
        }
        return key.toString();
    }
    void joinLocalGroup(){
        joinLocalGroup(null);
    }
    void joinLocalGroup(Runnable back){
        run("Join group",back,(c,selected)->{
            boolean external=controllerMode.getAsBoolean();
            String local=selected;
            FeatureApi.require(!local.isEmpty(),"No join source is available. Select a Player or enable this kiosk's receiver in Plugin preferences.");
            JSONArray rows=c.array("players/all",FeatureApi.args("return_unavailable",true));
            JSONObject child=FeatureApi.byId(rows,"player_id",local),source=FeatureApi.groupLeader(rows,local);
            FeatureApi.require(child!=null&&child.optBoolean("available")&&(!child.optBoolean("hidden")&&!child.optBoolean("hide_in_ui")||local.equals(ownIdentity.apply(c.session))),"The joining player is unavailable or hidden.");
            String sourceId=source.getString("player_id");Set<String> previous=FeatureApi.members(rows,source);
            boolean moving=!sourceId.equals(local)||previous.size()>1;
            render("Join group · "+child.optString("name"),back,p->{
                p.note("Add "+child.optString("name")+" to a compatible leader and follow its playback. No queue is transferred. The saved default stays unchanged; Now Playing follows the joined group after verification.");
                int count=0;
                for(int i=0;i<rows.length();i++){
                    JSONObject target=rows.optJSONObject(i);
                    if(!FeatureApi.localJoinCandidate(rows,target,child,sourceId))continue;
                    String leader=target.optString("player_id");Set<String> members=FeatureApi.members(rows,target);
                    Runnable join=()->run("Join group",()->joinLocalGroup(back),(next,ignored)->{
                        FeatureApi.require(external==controllerMode.getAsBoolean()&&local.equals(ignored),"Join source or operating mode changed. Reopen Join group.");
                        joinDiagnostic.accept(api.joinLocal(next,local,sourceId,previous,leader,members));complete("",()->ui.joined(leader),false);
                    });
                    p.row(target.optString("name"),"Join this leader's playback","+",false,true,()->{
                        if(moving)confirm("Move player to another group?","Only "+child.optString("name")+" will move. It leaves its current group and follows "+target.optString("name")+". No queue transfer or automatic retry.",join,()->joinLocalGroup(back));
                        else join.run();
                    },null);count++;
                }
                if(count==0)p.note("No compatible available destination leaders. Existing membership is unchanged.");
            });
        });
    }
    void playerSettings(){
        run("Player",()->ui.players(null),(c,selected)->{
            JSONArray rows=c.array("players/all",FeatureApi.args("return_unavailable",true));
            JSONObject child=FeatureApi.byId(rows,"player_id",selected),leader=FeatureApi.groupLeader(rows,selected);
            FeatureApi.require(child!=null&&child.optBoolean("available"),"Selected player unavailable. Select another Player.");
            Set<String> members=FeatureApi.members(rows,leader);
            render("Player settings · "+child.optString("name"),()->ui.players(null),p->{
                p.note("Supported controls for the selected player: "+child.optString("name")+". Plugin and local receiver preferences are separate.");
                java.util.Map<String,TouchPanel.VolumeControl> controls=new java.util.LinkedHashMap<>();
                volume(p,child,leader.optString("player_id"),false,controls,members,null);
                if(FeatureApi.contains(leader.optJSONArray("supported_features"),"set_members"))p.navigation("speaker-multiple","Group / volume",()->group(this::playerSettings));
                p.navigation("speaker","Select player",()->ui.players(this::playerSettings));
            });
        });
    }
    private void volume(TouchPanel p, JSONObject row, String leader, boolean group,java.util.Map<String,TouchPanel.VolumeControl> controls,Set<String> members,android.widget.LinearLayout card) {
        String id = row.optString("player_id"); if (!row.optBoolean("available")) return;
        boolean volume = group || (!row.optString("volume_control").isEmpty() && !row.optString("volume_control").equals("none"));
        boolean mute=group||(!row.optString("mute_control").isEmpty()&&!row.optString("mute_control").equals("none"));
        final TouchPanel.VolumeControl[] widget={null};
        if(volume||mute){widget[0]=p.volumeIn(card,group?"Group volume":row.optString("name")+" volume",row.optInt(group?"group_volume":"volume_level"),row.optBoolean(group?"group_volume_muted":"volume_muted"),volume,mute,
            level->volumeAction(p,controls,members,id,leader,group,level,null),()->volumeAction(p,controls,members,id,leader,group,null,!widget[0].muted()));
            widget[0].update(row.optInt(group?"group_volume":"volume_level"),row.optBoolean(group?"group_volume_muted":"volume_muted"),VolumeDisplay.silent(row,group));
            controls.put((group?"group:":"player:")+id,widget[0]);}
    }
    private void volumeAction(TouchPanel panel,java.util.Map<String,TouchPanel.VolumeControl> controls,Set<String> members,String id, String leader, boolean group, Integer level, Boolean muted) {
        if(!alive.getAsBoolean()||!panel.isAttachedToWindow()||!busy.compareAndSet(false,true))return;
        ownsBusy=true;final long ticket=generation;final String player=selected.get();
        BooleanSupplier current=()->alive.getAsBoolean()&&generation==ticket&&selected.get().equals(player)&&panel.isAttachedToWindow();
        controls.values().forEach(v->v.busy(true));panel.message("");
        java.util.function.Consumer<String> finish=message->panel.post(()->{
            if(generation==ticket&&ownsBusy){ownsBusy=false;busy.set(false);}
            if(current.getAsBoolean()){controls.values().forEach(v->v.busy(false));panel.message(message);}
        });
        access.ensure(addon.get(),current,session->{try{worker.submit(()->{
            FeatureApi.Connection c=api.new Connection(session,current,()->verifySession(session,current));
            try{
            api.api.identity(session);
            JSONArray rows = c.array("players/all", FeatureApi.args("return_unavailable", true)); JSONObject target = FeatureApi.byId(rows, "player_id", id);
            JSONObject liveLeader=FeatureApi.groupLeader(rows,player);
            FeatureApi.require(target != null && target.optBoolean("available") && liveLeader.optString("player_id").equals(leader)&&FeatureApi.members(rows,liveLeader).equals(members), "Player/group changed. Refresh volume controls.");
            if (!group) FeatureApi.require(!target.optString(level != null ? "volume_control" : "mute_control", "none").equals("none"), "This player no longer exposes that volume control.");
            boolean unmute=level!=null&&level>0&&target.optBoolean(group?"group_volume_muted":"volume_muted");
            if(unmute&&!group)FeatureApi.require(!target.optString("mute_control","none").equals("none"),"Unmute unavailable. No volume was changed.");
            c.write("players/cmd/" + (level != null ? group ? "group_volume" : "volume_set" : group ? "group_volume_mute" : "volume_mute"), FeatureApi.args("player_id", id, level != null ? "volume_level" : "muted", level != null ? level : muted));
            if(unmute){
                JSONObject staged=null;
                for(int delay:new int[]{0,150,350,700}){
                    if(delay>0)Thread.sleep(delay);
                    JSONArray scoped=c.array("players/all",FeatureApi.args("return_unavailable",true));
                    JSONObject scopedLeader=FeatureApi.groupLeader(scoped,player);staged=FeatureApi.byId(scoped,"player_id",id);
                    FeatureApi.require(scopedLeader.optString("player_id").equals(leader)&&FeatureApi.members(scoped,scopedLeader).equals(members)&&staged!=null&&staged.optBoolean("available"),"Player/group changed. Unmute was not sent.");
                    if(Math.abs(staged.optInt(group?"group_volume":"volume_level")-level)<=2)break;
                }
                FeatureApi.require(staged!=null&&Math.abs(staged.optInt(group?"group_volume":"volume_level")-level)<=2,"Volume level was not confirmed. Unmute was not sent.");
                if(staged.optBoolean(group?"group_volume_muted":"volume_muted")){
                    if(!group)FeatureApi.require(!staged.optString("mute_control","none").equals("none"),"Unmute unavailable.");
                    c.write(group?"players/cmd/group_volume_mute":"players/cmd/volume_mute",FeatureApi.args("player_id",id,"muted",false));
                }
            }
            JSONArray fresh=c.array("players/all",FeatureApi.args("return_unavailable",true));
            FeatureApi.require(FeatureApi.groupLeader(fresh,player).optString("player_id").equals(leader)&&FeatureApi.members(fresh,FeatureApi.groupLeader(fresh,player)).equals(members),"Group changed; refresh volume controls.");
            JSONObject result=FeatureApi.byId(fresh,"player_id",id);
            if(unmute)FeatureApi.require(result!=null&&!result.optBoolean(group?"group_volume_muted":"volume_muted")&&Math.abs(result.optInt(group?"group_volume":"volume_level")-level)<=2,"Volume/unmute state not confirmed. Check MA before retrying.");
            panel.post(()->{if(current.getAsBoolean())controls.forEach((key,widget)->{
                boolean grouped=key.startsWith("group:");JSONObject value=FeatureApi.byId(fresh,"player_id",key.substring(key.indexOf(':')+1));
                if(value!=null)widget.update(value.optInt(grouped?"group_volume":"volume_level"),value.optBoolean(grouped?"group_volume_muted":"volume_muted"),VolumeDisplay.silent(value,grouped));
            });});
            finish.accept("");
            }catch(Exception error){finish.accept(c.submitted?"Volume may already have changed. Check MA before retrying; no automatic retry was made.":error instanceof IllegalArgumentException?error.getMessage():"Volume read failed. Refresh to reconnect.");}
        });}catch(RuntimeException ignored){finish.accept("Browser is stopping.");}},finish);
    }
    void lyrics() {
        run("Lyrics", (c, player) -> {
            render("Lyrics", p -> {
                LyricsWatch watch=new LyricsWatch();
                LyricsWatch old=activeLyrics;if(old!=null){old.epoch++;old.metadata.api.cancel();}activeLyrics=watch;watch.metadata.api.ownReceiver(ownIdentity);
                BooleanSupplier current=()->c.current.getAsBoolean()&&p.isAttachedToWindow();
                p.tool("refresh","Refresh lyrics",false,()->refreshLyrics(p,player,current,watch,true));
                p.every(1000,()->refreshLyrics(p,player,current,watch,false));
                p.post(()->refreshLyrics(p,player,current,watch,true));
            });
        });
    }
    private static final class LyricsWatch{
        final AtomicBoolean loading=new AtomicBoolean();volatile String key="";
        volatile long epoch;
        final FeatureApi metadata=new FeatureApi();
    }
    static String lyricsKey(JSONObject queue){
        JSONObject item=queue.optJSONObject("current_item"),media=item==null?null:item.optJSONObject("media_item");
        return queue.optString("queue_id")+"\n"+(item==null?"":item.optString("queue_item_id"))+"\n"+(media==null?"":media.optString("uri"));
    }
    private void refreshLyrics(TouchPanel panel,String player,BooleanSupplier current,LyricsWatch watch,boolean force){
        if(!current.getAsBoolean()||!watch.loading.compareAndSet(false,true))return;
        access.ensure(addon.get(),current,session->{try{worker.submit(()->{
            try{
                FeatureApi.Connection c=api.new Connection(session,current);
                api.api.identity(session);
                JSONObject queue=c.queue(player);String key=lyricsKey(queue);
                if(!force&&key.equals(watch.key)){
                    panel.post(()->{if(current.getAsBoolean())panel.lyricPosition(FeatureApi.elapsed(queue,System.currentTimeMillis()/1000d),"playing".equals(queue.optString("state")));});return;
                }
                watch.key=key;final long epoch=++watch.epoch;watch.metadata.api.cancel();
                BooleanSupplier valid=()->current.getAsBoolean()&&watch.epoch==epoch&&key.equals(watch.key);
                // Remove the previous track before metadata work, including during gaps.
                panel.post(()->{if(valid.getAsBoolean())panel.clearLyrics("");});
                JSONObject item=queue.optJSONObject("current_item"),media=item==null?null:item.optJSONObject("media_item");
                if(media==null||!"track".equals(media.optString("media_type"))||media.optString("uri").isEmpty()){
                    String message=lyricsUnavailable(media);
                    panel.post(()->{if(valid.getAsBoolean())panel.clearLyrics(message);});return;
                }
                lyricsWorker.execute(()->loadLyrics(panel,player,session,media,key,valid,watch));
            }catch(Exception error){
                watch.key="";++watch.epoch;watch.metadata.api.cancel();
                panel.post(()->{if(current.getAsBoolean())panel.clearLyrics("Could not read the current track. Lyrics will refresh when the queue is readable.");});
            }finally{watch.loading.set(false);}
        });}catch(RuntimeException ignored){watch.key="";watch.loading.set(false);}},error->{watch.key="";++watch.epoch;watch.metadata.api.cancel();watch.loading.set(false);panel.post(()->{if(current.getAsBoolean())panel.clearLyrics("Lyrics connection unavailable. Check Player settings.");});});
    }
    static String lyricsUnavailable(JSONObject media){
        return media!=null&&"radio".equals(media.optString("media_type"))
            ?"Lyrics are not available for radio playback."
            :"No music track available. Lyrics will follow the next track.";
    }
    private void loadLyrics(TouchPanel panel,String player,IngressAccess.Session session,JSONObject media,String key,BooleanSupplier valid,LyricsWatch watch){
        long started=System.nanoTime();
        try{
                if(!valid.getAsBoolean())return;
                FeatureApi.Connection c=watch.metadata.new Connection(session,valid);
                watch.metadata.api.identity(session);
                JSONObject track=c.item(media.getString("uri"));
                Object response=c.read("metadata/get_track_lyrics",FeatureApi.args("track",track));
                JSONArray reply=response instanceof JSONArray?(JSONArray)response:null;
                FeatureApi.require(response==null||response==JSONObject.NULL||(reply!=null&&reply.length()==2),"Lyrics provider returned an incomplete result.");
                LyricsData lyrics=LyricsData.parse(reply==null||reply.length()!=2||reply.isNull(0)?"":reply.optString(0),reply==null||reply.length()!=2||reply.isNull(1)?"":reply.optString(1));
                long lookupMs=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                JSONObject verified=c.queue(player);
                if(!key.equals(lyricsKey(verified))){if(valid.getAsBoolean())watch.key="";return;}
                panel.post(()->{if(valid.getAsBoolean()){
                    lyricsDiagnostic.accept("Lyrics lookup: "+lookupMs+" ms · timed lines="+lyrics.lines.size()+" · playing="+"playing".equals(verified.optString("state")));
                    panel.clearLyrics("");panel.lyrics(lyrics,FeatureApi.elapsed(verified,System.currentTimeMillis()/1000d),"playing".equals(verified.optString("state")));
                }});
            }catch(Exception error){
                if(!valid.getAsBoolean())return;
                lyricsDiagnostic.accept("Lyrics lookup failed after "+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" ms. No provider payload recorded.");
                String message=error instanceof PairingDiagnostics.Failure?"Lyrics connection failed · "+PairingDiagnostics.describe(error)+". Refresh to reconnect.":
                    "Lyrics unavailable from the provider for this track. Refresh to try again.";
                panel.post(()->{if(valid.getAsBoolean())panel.clearLyrics(message);});
            }
    }
}
