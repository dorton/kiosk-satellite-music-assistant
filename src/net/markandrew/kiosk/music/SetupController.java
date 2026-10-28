package net.markandrew.kiosk.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.PluginHost;

/** Native staged setup. Discovery is read-only; only Save persists plugin settings. */
final class SetupController {
    interface Ui {
        Map<String, Object> settings();
        void panel(String title, Consumer<TouchPanel> populate);
        void save(Map<String, Object> values);
        void dismiss();
        default void receiverSettings(TouchPanel panel,Map<String,Object> draft) {}
        default void diagnostics(TouchPanel panel,Map<String,Object> draft) {}
        default void connectionResult(TouchPanel panel,PlayerPolicy.Snapshot snapshot,Runnable back) {}
        default String localName(){return "Local receiver";}
        default boolean controllerOnly(Map<String,Object> draft){return false;}
        default String localIdentity(){return "";}
    }
    private final DiscoveryAccess discovery;
    private final IngressAccess ingress;
    private final MusicApi api = new MusicApi();
    private final ExecutorService worker;
    private final BooleanSupplier alive;
    private final Ui ui;
    private volatile long generation;
    private volatile String account = "";
    SetupController(PluginHost host, IngressAccess ingress, ExecutorService worker, BooleanSupplier alive, Ui ui) {
        discovery = new DiscoveryAccess(host, worker);
        this.ingress = ingress; this.worker = worker; this.alive = alive; this.ui = ui;
    }
    void cancel() { generation++; api.cancel(); discovery.cancel(); }
    void ownReceiver(java.util.function.Function<IngressAccess.Session, String> identity) { api.ownReceiver(identity); }
    void open() { open(defaults(ui.settings())); }
    private static Map<String, Object> defaults(Map<String, Object> settings) {
        Map<String, Object> values = new HashMap<>();
        values.put("addon", ""); values.put("playerMode", "Fixed default player");
        values.put("defaultPlayer", "");values.put("localPlayer", "");
        values.put("idleRevertSeconds","60");
        values.putAll(settings);
        values.remove("advanced");values.remove("kioskNode");
        values.put("defaultPlayer", DefaultPlayerPolicy.saved(settings));
        values.put("playerMode", "Fixed default player");
        return values;
    }
    private static String value(Map<String, Object> draft, String key) { return String.valueOf(draft.get(key)); }
    private void open(Map<String, Object> draft) {
        cancel(); account = ""; ingress.clear(); final long ticket = generation;
        BooleanSupplier current = () -> alive.getAsBoolean() && generation == ticket;
        ui.panel("Setup · discovering Music Assistant", p -> {
            p.guard(current);p.closeAction("Close · discard unsaved changes",()->{cancel();ui.dismiss();});
            p.message("Reading Music Assistant players. No playback or Kiosk settings will change.");
            p.add("Cancel", () -> { cancel(); ui.dismiss(); });
        });
        Consumer<List<DiscoveryPolicy.Choice>> discovered=addons -> {
            String chosen = value(draft, "addon");
            if (chosen.isEmpty() && addons.size() == 1) chosen = addons.get(0).id;
            if (chosen.isEmpty()) {
                discoverNodes(draft, addons, null, "", addons.isEmpty() ? "No installed MA app found." : "Choose which MA instance to use.", current); return;
            }
            final String selected = chosen;
            ingress.ensure(selected, current, session -> {
                try { worker.submit(() -> {
                    try {
                        if (!current.getAsBoolean()) return;
                        String signedIn = api.accountName(session); PlayerPolicy.Snapshot snapshot = api.snapshot(session);
                        if (current.getAsBoolean()) account = signedIn;
                        discoverNodes(draft, addons, snapshot, "", "", current);
                    } catch (Exception ignored) {
                        discoverNodes(draft, addons, null, "", "MA players could not be read. Check app permissions and availability, then Refresh.", current);
                    }
                }); } catch (RuntimeException ignored) { discoverNodes(draft, addons, null, "", "Setup worker unavailable.", current); }
            }, error -> discoverNodes(draft, addons, null, "", error, current));
        };
        discovery.addons(current,discovered,error->{
            if(!value(draft,"addon").isEmpty())discovered.accept(Collections.singletonList(new DiscoveryPolicy.Choice(value(draft,"addon"),"Saved Music Assistant app")));
            else discoverNodes(draft,Collections.emptyList(),null,"",error,current);
        });
    }
    private void discoverNodes(Map<String, Object> draft, List<DiscoveryPolicy.Choice> addons,
                               PlayerPolicy.Snapshot snapshot, String detectedLocal, String warning, BooleanSupplier current) {
        if (!current.getAsBoolean()) return;
        render(draft, addons, Collections.emptyList(), snapshot, detectedLocal, warning, current);
    }
    private void render(Map<String, Object> draft, List<DiscoveryPolicy.Choice> addons,
                        List<DiscoveryPolicy.Choice> nodes, PlayerPolicy.Snapshot snapshot, String detectedLocal,
                        String warning, BooleanSupplier current) {
        if (!current.getAsBoolean()) return;
        List<DiscoveryPolicy.Choice> instances = new ArrayList<>();
        List<DiscoveryPolicy.Choice> names=DiscoveryPolicy.serverNames(addons);
        instances.add(new DiscoveryPolicy.Choice("", names.size() == 1 ? "Auto · " + names.get(0).label : "Auto · requires one accessible MA sidebar app"));
        instances.addAll(names);
        String savedInstance=value(draft,"addon");
        if(!savedInstance.isEmpty()&&instances.stream().noneMatch(choice->choice.id.equals(savedInstance)))
            instances.add(new DiscoveryPolicy.Choice(savedInstance,"Saved Music Assistant server"));
        List<DiscoveryPolicy.Choice> players = new ArrayList<>();
        players.add(new DiscoveryPolicy.Choice("", "Choose your default player"));
        String own=ui.localIdentity();
        if(!own.isEmpty()){
            PlayerPolicy.Player local=snapshot==null?null:snapshot.players.get(own);
            players.add(new DiscoveryPolicy.Choice(own,"This kiosk · "+(local==null?ui.localName():local.name)));
        }
        if (snapshot != null) for (PlayerPolicy.Player p : snapshot.players.values()) {
            if(p.id.equals(own))continue;
            if (!p.audio() || !p.visible) continue;
            String label = p.name + (p.available ? "" : " (offline)");
            if (snapshot.players.values().stream().filter(other -> other.name.equals(p.name)).count() > 1) label += " · " + p.id;
            if (p.available || p.id.equals(value(draft, "defaultPlayer"))) players.add(new DiscoveryPolicy.Choice(p.id, label));
        }
        ui.panel("Plugin preferences", panel -> {
            if (!current.getAsBoolean()) return;
            panel.guard(current);
            panel.message(warning);
            panel.section("Connection");
            panel.picker("Music Assistant server",instances,value(draft,"addon"),id->{if(!id.equals(value(draft,"addon"))){draft.put("addon",id);draft.put("defaultPlayer","");draft.put("localPlayer","");open(draft);}},true);
            panel.picker("Default player", players, value(draft, "defaultPlayer"), id -> {
                draft.put("defaultPlayer", id); render(draft, addons, nodes, snapshot, detectedLocal, warning, current);
            });
            panel.add("Refresh choices",()->open(draft));
            panel.section("Playback");
            panel.numberInput("Auto-revert after idle (seconds) · 0 = disabled",value(draft,"idleRevertSeconds"),id->draft.put("idleRevertSeconds",id));
            if(!ui.controllerOnly(draft))panel.boundedSlider("Ducking · % of current local music volume",1,10,AudioGainPolicy.configuredPercent(draft.get("duckPercent")),percent->draft.put("duckPercent",percent));
            panel.toggle("Prevent screensaver while playing",Boolean.TRUE.equals(draft.get("preventScreensaverWhilePlaying")),enabled->draft.put("preventScreensaverWhilePlaying",enabled));
            if(!ui.controllerOnly(draft))panel.toggle("Hardware buttons control group volume",Boolean.TRUE.equals(draft.get("hardwareGroupVolume")),enabled->draft.put("hardwareGroupVolume",enabled));
            panel.section("This device");ui.receiverSettings(panel,draft);
            panel.disclosure("Diagnostics",advanced->{
                advanced.note("Music Assistant · 1.0.2");
                advanced.add("Check connection",()->checkConnection(draft));
                ui.diagnostics(advanced,draft);
            });
            panel.formActions("Save", () -> {
                if (!current.getAsBoolean()) return;
                try {
                    int seconds;
                    try{seconds=Integer.parseInt(value(draft,"idleRevertSeconds"));}catch(NumberFormatException error){throw new IllegalArgumentException("Enter an idle duration from 0 to 86400 seconds; 0 disables auto-revert.");}
                    if(seconds<0||seconds>86400)throw new IllegalArgumentException("Enter an idle duration from 0 to 86400 seconds; 0 disables auto-revert.");
                    draft.put("idleRevertSeconds",String.valueOf(seconds));
                    String selected = value(draft, "addon");
                    if (selected.isEmpty() && addons.size() != 1) throw new IllegalArgumentException("Choose an MA instance before saving.");
                    if (!selected.isEmpty()) IngressPolicy.addon(selected);
                    String chosen=value(draft,"defaultPlayer");
                    if(own.isEmpty()||!own.equals(chosen)){
                        if (snapshot == null) throw new IllegalArgumentException("Read the player list before saving a default.");
                        chosen=DefaultPlayerPolicy.selected(snapshot,chosen).id;
                    }
                    draft.put("defaultPlayer", chosen); draft.put("playerMode", "Fixed default player");
                    ui.save(new HashMap<>(draft)); cancel();
                } catch (IllegalArgumentException error) { panel.message(error.getMessage()); }
                catch (RuntimeException | LinkageError ignored) { panel.message("Saving was not confirmed. Reopen Setup to check before retrying."); }
            }, () -> { cancel(); ui.dismiss(); });
            panel.closeAction("Close · discard unsaved changes", () -> { cancel(); ui.dismiss(); });
        });
    }
    private void checkConnection(Map<String,Object> draft){
        cancel();final long ticket=generation;Map<String,Object> saved=ui.settings();
        BooleanSupplier current=()->alive.getAsBoolean()&&generation==ticket;
        Consumer<String> failed=stage->{if(current.getAsBoolean())ui.panel("Check connection",p->{
            p.guard(current);p.closeAction("‹ Back",()->open(draft));p.message(stage+" could not be verified. Check Music Assistant availability and account permissions.");
            p.add("Review server and player",()->open(draft));
        });};
        ui.panel("Check connection",p->{p.guard(current);p.closeAction("‹ Back",()->open(draft));p.note("Checking saved server, sign-in and players…");});
        discovery.addons(current,choices->{
            String selected=String.valueOf(saved.getOrDefault("addon",""));
            if(selected.isEmpty()&&choices.size()==1)selected=choices.get(0).id;
            if(selected.isEmpty()){failed.accept("Server selection");return;}
            ingress.ensure(selected,current,session->{try{worker.submit(()->{
                final String[] stage={"Server"};
                try{
                    PlayerPolicy.Snapshot snapshot=api.connectionSnapshot(session,s->stage[0]=s);
                    if(!current.getAsBoolean())return;
                    ui.panel("Check connection",p->{
                        p.guard(current);p.closeAction("‹ Back",()->open(draft));
                        p.note("Server and sign-in · OK");p.note("Player access · OK");
                        String id=DefaultPlayerPolicy.saved(saved);PlayerPolicy.Player player=snapshot.players.get(id);
                        p.note(player==null?"Default player · not found":("Default player · "+player.name+(player.available?" · ready":" · offline")));
                        ui.connectionResult(p,snapshot,()->checkConnection(draft));
                    });
                }catch(Exception error){failed.accept(stage[0]);}
            });}catch(RuntimeException error){failed.accept("Connection check");}},error->failed.accept("Sign-in"));
        },error->failed.accept("Server discovery"));
    }
}
