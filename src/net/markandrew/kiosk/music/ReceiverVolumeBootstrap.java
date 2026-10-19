package net.markandrew.kiosk.music;

import java.util.concurrent.*;
import java.util.function.*;
import org.json.JSONObject;
import me.jxl.kiosk.plugins.PluginHost;

/** Read the exact existing local player's saved MA volume BEFORE registration can
 * advertise a default. No volume mutations, discovery, or availability fallback. */
final class ReceiverVolumeBootstrap {
    private final MusicApi api=new MusicApi();
    private final IngressAccess access;
    private final ExecutorService worker;
    ReceiverVolumeBootstrap(PluginHost host,ExecutorService worker){access=new IngressAccess(host);this.worker=worker;}
    void read(String addon,String player,BooleanSupplier current,Consumer<Integer> done){
        access.ensure(addon,current,session->{try{worker.submit(()->{
            Integer level=null;
            try{
                String user=((JSONObject)api.identity(session)).getString("user_id"),server=api.serverInfo(session).getString("server_id");
                if(user.isEmpty()||server.isEmpty())throw new IllegalArgumentException();
                Object raw=api.command(session,"players/get",new JSONObject().put("player_id",player));
                if(!(raw instanceof JSONObject))throw new IllegalArgumentException();JSONObject row=(JSONObject)raw;
                Object volume=row.opt("volume_level");
                if(!player.equals(row.optString("player_id"))||!"player".equals(row.optString("type"))||!(volume instanceof Number))throw new IllegalArgumentException();
                double value=((Number)volume).doubleValue();
                if(!Double.isFinite(value)||value<0||value>100||!server.equals(api.serverInfo(session).getString("server_id"))||!user.equals(((JSONObject)api.identity(session)).optString("user_id")))throw new IllegalArgumentException();
                level=(int)Math.round(value);
            }catch(Exception ignored){/* Unknown stays unknown, never 100%. */}
            final Integer result=level;
            if(!current.getAsBoolean())return;
            access.ensure(addon,current,latest->done.accept(latest==session?result:null),error->done.accept(null));
        });}catch(RejectedExecutionException ignored){done.accept(null);}},error->done.accept(null));
    }
    void close(){try{api.cancel();}catch(RuntimeException ignored){}access.clear();}
}
