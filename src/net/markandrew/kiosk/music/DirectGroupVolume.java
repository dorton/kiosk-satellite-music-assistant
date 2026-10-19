package net.markandrew.kiosk.music;

import android.os.*;
import java.util.concurrent.*;
import java.util.function.*;
import me.jxl.kiosk.plugins.PluginHost;
import net.markandrew.kiosk.music.receiver.PluginReceiver;

/** A leased, verified group owns the receiver's remote MediaSession volume provider. */
final class DirectGroupVolume {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final FeatureApi api=new FeatureApi();
    private final IngressAccess access;
    private final LocalGroupVolume writes;
    private final Supplier<String> addon,identity,context;
    private final Supplier<PluginReceiver> receiver;
    private final BooleanSupplier enabled;
    private final IntSupplier step;
    private final Consumer<String> diagnostic;
    private volatile boolean closed;
    private volatile long epoch;
    private String owner="";
    private LocalGroupVolume.Plan plan;
    private long checked,nextRead,lastInput;
    private boolean reading,busy;
    private int wanted;
    DirectGroupVolume(PluginHost host,Supplier<String> addon,Supplier<String> identity,Supplier<String> context,Supplier<PluginReceiver> receiver,BooleanSupplier enabled,IntSupplier step,Consumer<String> diagnostic){
        access=new IngressAccess(host);this.addon=addon;this.identity=identity;this.context=context;this.receiver=receiver;this.enabled=enabled;this.step=step;this.diagnostic=diagnostic;
        api.api.ownReceiver(s->identity.get());writes=new LocalGroupVolume(host,addon,identity,context,enabled);
    }
    void update(){
        if(closed)return;
        String next=addon.get()+":"+identity.get()+":"+context.get();
        if(!enabled.getAsBoolean()||!owner.equals(next)){
            boolean uncertain=writes.cancel();epoch++;plan=null;reading=false;busy=false;nextRead=0;owner=next;
            PluginReceiver local=receiver.get();if(local!=null)local.clearGroupVolumeRoute();
            if(uncertain)diagnostic.accept("Group volume was in flight when routing changed. Inspect Group; no automatic retry.");
            if(!enabled.getAsBoolean())return;
        }
        if(reading||busy||SystemClock.elapsedRealtime()<nextRead)return;
        String app=addon.get(),own=identity.get();if(app.isEmpty()||own.isEmpty())return;
        reading=true;nextRead=SystemClock.elapsedRealtime()+1500;long ticket=epoch;String expected=owner;
        BooleanSupplier valid=()->!closed&&ticket==epoch&&enabled.getAsBoolean()&&expected.equals(addon.get()+":"+identity.get()+":"+context.get());
        access.ensure(app,valid,session->{try{worker.submit(()->{
            LocalGroupVolume.Plan value=null;boolean success=false;
            try{value=LocalGroupVolume.plan(api,api.new Connection(session,valid),own);success=true;}catch(Exception ignored){/* Read failure never causes a local-volume fallback from an existing group route. */}
            LocalGroupVolume.Plan result=value;boolean verified=success;
            main.post(()->{if(!valid.getAsBoolean())return;reading=false;if(busy)return;if(!verified){plan=null;return;}plan=result;checked=SystemClock.elapsedRealtime();
                PluginReceiver local=receiver.get();if(local==null)return;
                if(result==null)local.clearGroupVolumeRoute();else publish(result.level);
            });
        });}catch(RejectedExecutionException error){reading=false;}},error->{if(valid.getAsBoolean()){reading=false;plan=null;}});
    }
    private void publish(int level){PluginReceiver local=receiver.get();if(local!=null&&!local.routeGroupVolume(level,direction->input(direction,false),value->input(value,true)))diagnostic.accept("This receiver could not take group hardware-volume control. No native settings changed.");}
    private void input(int value,boolean absolute){
        if(closed||!enabled.getAsBoolean()||plan==null||SystemClock.elapsedRealtime()-checked>5000||!owner.equals(addon.get()+":"+identity.get()+":"+context.get())){diagnostic.accept("Group volume is not freshly verified; no hardware command sent.");return;}
        if(!absolute&&value!=1&&value!=-1)return;
        if(!busy)wanted=plan.level;
        wanted=Math.max(0,Math.min(100,absolute?value:wanted+(value>0?1:-1)*Math.max(1,step.getAsInt())));lastInput=SystemClock.elapsedRealtime();
        if(!busy)submit();
    }
    private void submit(){
        if(plan==null||closed||!enabled.getAsBoolean())return;
        busy=true;final int target=wanted;final String scope=plan.scope;final long ticket=epoch;
        writes.adjustScoped(scope,target,true,localLevel->{
            if(ticket!=epoch||closed)return;busy=false;plan=new LocalGroupVolume.Plan(scope,target);checked=SystemClock.elapsedRealtime();publish(target);
            // Only fresh additional user input can submit another mutation, never an uncertain retry.
            if(wanted!=target&&SystemClock.elapsedRealtime()-lastInput<1000)submit();else{wanted=target;nextRead=0;update();}
        },error->{if(ticket!=epoch||closed)return;busy=false;plan=null;nextRead=0;diagnostic.accept(error);});
    }
    void close(){closed=true;epoch++;writes.close();api.api.cancel();access.clear();worker.shutdownNow();PluginReceiver local=receiver.get();if(local!=null)local.clearGroupVolumeRoute();main.removeCallbacksAndMessages(null);}
}
