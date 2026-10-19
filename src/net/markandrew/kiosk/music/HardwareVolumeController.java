package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.*;

/** One fresh, bounded adjustment per accepted press. No delayed backlog or mutation retry. */
final class HardwareVolumeController {
    private final FeatureApi api=new FeatureApi();
    private final IngressAccess access;
    private final Supplier<String> addon,selected;
    private final BooleanSupplier allowed;
    private final AtomicBoolean busy;
    private final BiConsumer<String,Boolean> diagnostic;
    private final LongSupplier wall,elapsed;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"music-hardware-volume");t.setDaemon(true);return t;});
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"music-volume-deadline");t.setDaemon(true);return t;});
    private volatile boolean open=true;
    private volatile Request active;
    private boolean uncertainHold;
    private long lastAccepted=Long.MIN_VALUE;
    private Function<IngressAccess.Session,String> own=session->"";
    HardwareVolumeController(PluginHost host,Supplier<String> addon,Supplier<String> selected,BooleanSupplier allowed,AtomicBoolean busy,BiConsumer<String,Boolean> diagnostic){
        this(host,addon,selected,allowed,busy,diagnostic,System::currentTimeMillis,()->System.nanoTime()/1000000);
    }
    HardwareVolumeController(PluginHost host,Supplier<String> addon,Supplier<String> selected,BooleanSupplier allowed,AtomicBoolean busy,BiConsumer<String,Boolean> diagnostic,LongSupplier wall,LongSupplier elapsed){
        access=new IngressAccess(host);this.addon=addon;this.selected=selected;this.allowed=allowed;this.busy=busy;this.diagnostic=diagnostic;this.wall=wall;this.elapsed=elapsed;
    }
    void ownReceiver(Function<IngressAccess.Session,String> identity){own=identity;api.api.ownReceiver(identity);}
    private final class Request {
        final String app,player;final long started;volatile boolean submitted;volatile Future<?> job;volatile ScheduledFuture<?> deadline;
        Request(String app,String player,long started){this.app=app;this.player=player;this.started=started;}
        boolean current(){long age=elapsed.getAsLong()-started;return open&&active==this&&allowed.getAsBoolean()&&app.equals(addon.get())&&player.equals(selected.get())&&age>=0&&age<4000;}
    }
    synchronized boolean key(Map<String,Object> event){
        int direction=HardwareVolumePolicy.direction(event,wall.getAsLong());
        if(direction==0||!open||!allowed.getAsBoolean())return false;
        int repeat=((Number)event.get("repeat")).intValue();
        if(repeat==0)uncertainHold=false;else if(uncertainHold)return false;
        long now=elapsed.getAsLong();
        if(lastAccepted!=Long.MIN_VALUE&&now-lastAccepted<250)return false;
        String app=addon.get(),player=selected.get();
        if(app.isEmpty()||player.isEmpty()||active!=null||!busy.compareAndSet(false,true))return false;
        Request request=new Request(app,player,now);active=request;lastAccepted=now;
        request.deadline=timer.schedule(()->expire(request),4,TimeUnit.SECONDS);
        access.ensure(app,request::current,session->{
            try{request.job=worker.submit(()->adjust(request,session,direction));}
            catch(RejectedExecutionException stopped){finish(request,"Hardware volume was not sent; controller unavailable.",false);}
        },error->finish(request,"Hardware volume unavailable: check the HA / MA connection.",false));
        return true;
    }
    private void verifySession(Request request,IngressAccess.Session expected)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);AtomicBoolean same=new AtomicBoolean();
        access.ensure(request.app,request::current,latest->{same.set(latest==expected);latch.countDown();},error->latch.countDown());
        FeatureApi.require(latch.await(1500,TimeUnit.MILLISECONDS)&&same.get()&&request.current(),"HA session or control target changed.");
    }
    private void adjust(Request request,IngressAccess.Session session,int direction){
        FeatureApi.Connection connection=api.new Connection(session,request::current);
        try{
            FeatureApi.require(request.current(),"Hardware press expired.");
            String user=((JSONObject)api.api.identity(session)).optString("user_id"),instance=api.api.serverInfo(session).getString("server_id");
            FeatureApi.require(!user.isEmpty(),"MA account identity unavailable.");
            JSONArray rows=connection.array("players/all",FeatureApi.args("return_unavailable",true));
            String queue=HardwareVolumePolicy.queueId(rows,request.player);
            HardwareVolumePolicy.Target before=new HardwareVolumePolicy.Target(rows,connection.object("player_queues/get",FeatureApi.args("queue_id",queue)),request.player,own.apply(session));
            verifySession(request,session);
            FeatureApi.require(user.equals(((JSONObject)api.api.identity(session)).optString("user_id"))&&instance.equals(api.api.serverInfo(session).getString("server_id")),"MA account or instance changed.");
            JSONArray fresh=connection.array("players/all",FeatureApi.args("return_unavailable",true));
            HardwareVolumePolicy.Target target=new HardwareVolumePolicy.Target(fresh,connection.object("player_queues/get",FeatureApi.args("queue_id",queue)),request.player,own.apply(session));
            FeatureApi.require(before.same(target),"Player, queue or group membership changed.");
            int volume=target.next(direction);
            if(volume==target.level){finish(request,"Hardware volume already at its limit.",false);return;}
            FeatureApi.require(request.current(),"Hardware press expired.");request.submitted=true;
            connection.write("players/cmd/"+(target.group?"group_volume":"volume_set"),FeatureApi.args("player_id",target.id,"volume_level",volume));
            JSONArray readback=connection.array("players/all",FeatureApi.args("return_unavailable",true));
            HardwareVolumePolicy.Target result=new HardwareVolumePolicy.Target(readback,connection.object("player_queues/get",FeatureApi.args("queue_id",queue)),request.player,own.apply(session));
            FeatureApi.require(target.same(result)&&Math.abs(result.level-volume)<=1,"Volume readback is not confirmed.");
            finish(request,"Hardware "+(target.group?"group":"player")+" volume confirmed · "+result.level+"%",false);
        }catch(Exception failure){finish(request,request.submitted?"Hardware volume may already have changed. Check MA before pressing again; no automatic retry.":"Hardware volume was not sent. Check playback, player volume support and connection.",request.submitted);}
    }
    private synchronized void finish(Request request,String message,boolean uncertain){
        if(active!=request)return;
        active=null;if(request.deadline!=null)request.deadline.cancel(false);busy.set(false);
        if(uncertain)uncertainHold=true;
        diagnostic.accept(message,uncertain);
    }
    private synchronized void expire(Request request){
        if(active!=request)return;
        finish(request,request.submitted?"Hardware volume outcome is uncertain. Check MA before pressing again; no automatic retry.":"Hardware volume timed out before submission; no command was retried.",request.submitted);
        disconnect();if(request.job!=null)request.job.cancel(true);
    }
    private void disconnect(){try{api.api.cancel();}catch(RuntimeException ignored){/* A closing HTTP connection can race with completion. */}}
    synchronized void cancel(){Request request=active;active=null;if(request!=null){if(request.deadline!=null)request.deadline.cancel(false);if(request.submitted)uncertainHold=true;disconnect();if(request.job!=null)request.job.cancel(true);busy.set(false);}access.clear();worker.getQueue().clear();}
    void close(){open=false;cancel();worker.shutdownNow();timer.shutdownNow();}
}
