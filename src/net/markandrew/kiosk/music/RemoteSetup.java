package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Explicit, read-only setup runs. Never saves settings, registers or pairs a receiver. */
final class RemoteSetup {
    private final PluginHost host;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"music-remote-setup");t.setDaemon(true);return t;});
    private final DiscoveryAccess discovery;
    private final IngressAccess access;
    private final MusicApi api=new MusicApi();
    private final BooleanSupplier alive;
    private final Supplier<Map<String,Object>> settings;
    private volatile Run active;
    private static final class Run {
        final String id;final Map<String,Object> settings;volatile boolean canceled;
        Run(String id,Map<String,Object> settings){this.id=id;this.settings=new HashMap<>(settings);}
    }
    RemoteSetup(PluginHost host,BooleanSupplier alive,Supplier<Map<String,Object>> settings,Function<IngressAccess.Session,String> own){
        this.host=host;this.alive=alive;this.settings=settings;
        discovery=new DiscoveryAccess(host,worker);access=new IngressAccess(host);api.ownReceiver(own);
    }
    void cancel(){Run r=active;if(r!=null){r.canceled=true;report(r,"error","Settings changed or setup closed. Refresh explicitly; nothing was saved.");}active=null;api.cancel();access.clear();discovery.cancel();}
    void stop(){cancel();worker.shutdownNow();}
    private boolean current(Run r){return alive.getAsBoolean()&&active==r&&!r.canceled;}
    private boolean report(Run r,String state,String message){
        try{boolean accepted=host.setupResult(r.id,state,message);if(!accepted)r.canceled=true;return accepted;}
        catch(RuntimeException|LinkageError ignored){r.canceled=true;return false;}
    }
    boolean event(String event,Map<String,Object> payload){
        if(!event.startsWith("settings."))return false;
        Object id=payload.get("run");if(!(id instanceof String)||((String)id).isEmpty())return true;
        if("settings.cancel".equals(event)){Run r=active;if(r!=null&&r.id.equals(id)){r.canceled=true;api.cancel();access.clear();}return true;}
        boolean choices="settings.choices".equals(event),button="settings.button".equals(event);
        String key=String.valueOf(payload.get(choices?"key":"id"));
        if(!(choices&&("addon".equals(key)||"defaultPlayer".equals(key)))&&!(button&&"checkConnection".equals(key)))return true;
        cancel();Run r=new Run((String)id,settings.get());active=r;
        try{worker.submit(()->begin(r,choices?key:"checkConnection"));}
        catch(RejectedExecutionException ignored){report(r,"error","Setup is stopping. Nothing was changed.");}
        return true;
    }
    private void begin(Run r,String key){
        if(!current(r)||!report(r,"running","Reading accessible Music Assistant servers…"))return;
        BooleanSupplier current=()->current(r);
        Consumer<String> fail=message->{if(current(r))report(r,"error",message);};
        if("addon".equals(key)){
            discovery.addons(current,choices->{
                if(!current(r))return;
                try{List<DiscoveryPolicy.Choice> names=DiscoveryPolicy.serverNames(choices);publish("addon",names);report(r,"success","Found "+names.size()+" accessible servers. Choose one, or leave the saved blank value for automatic discovery. No selection was changed.");}
                catch(RuntimeException|LinkageError ignored){fail.accept("Server choices are unavailable. Use on-device Plugin preferences.");}
            },fail);return;
        }
        discovery.resolveAddon(String.valueOf(r.settings.getOrDefault("addon","")),current,addon->{
            if(!current(r)||!report(r,"running","Checking sign-in and player access…"))return;
            access.ensure(addon,current,session->{try{worker.submit(()->{
                try{
                    if(!current(r))return;
                    PlayerPolicy.Snapshot snapshot=api.connectionSnapshot(session,stage->{if(!current(r)||!report(r,"running","Checking "+stage+"…"))throw new CancellationException();});
                    if(!current(r))return;
                    if("defaultPlayer".equals(key)){
                        List<DiscoveryPolicy.Choice> players=new ArrayList<>();
                        String saved=DefaultPlayerPolicy.saved(r.settings);
                        for(PlayerPolicy.Player p:snapshot.players.values())if(p.audio()&&p.visible&&(p.available||p.id.equals(saved)))
                            players.add(new DiscoveryPolicy.Choice(p.id,p.name+(p.available?"":" (offline)")));
                        publish("defaultPlayer",players);
                        report(r,"success","Found "+players.size()+" accessible players. Choose a default explicitly; the current selection was not changed.");
                    }else{
                        PlayerPolicy.Player player=snapshot.players.get(DefaultPlayerPolicy.saved(r.settings));
                        report(r,"success","Server, sign-in and player access verified. Default player: "+(player==null?"not found — refresh and select a player":player.available?"available":"offline")+". No playback, pairing or settings changed.");
                    }
                }catch(Exception|LinkageError ignored){fail.accept("Connection could not be verified. Check the saved server, HA sign-in and MA player permissions. No changes were made.");}
            });}catch(RejectedExecutionException ignored){fail.accept("Setup is stopping. Nothing was changed.");}},fail);
        },fail);
    }
    private void publish(String key,List<DiscoveryPolicy.Choice> choices){
        if(choices.size()>200)throw new IllegalArgumentException("Too many choices");
        String[] ids=new String[choices.size()],labels=new String[choices.size()];
        for(int i=0;i<ids.length;i++){ids[i]=choices.get(i).id;String label=choices.get(i).label;labels[i]=label.substring(0,Math.min(80,label.length()));}
        host.setSettingChoices(key,ids,labels);
    }
}
