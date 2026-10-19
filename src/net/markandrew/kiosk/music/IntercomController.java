package net.markandrew.kiosk.music;

import android.os.*;
import java.util.concurrent.*;
import java.util.function.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Main-thread call lifecycle; FIFO begin/finish, bounded reads, never replays an uncertain write. */
final class IntercomController {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final FeatureApi api=new FeatureApi();
    private final IngressAccess access;
    private final Supplier<String> addon,context;
    private final Function<IngressAccess.Session,String> own;
    private final Consumer<String> diagnostic;
    private final Runnable settled;
    private volatile boolean active,closed;
    private boolean working,attempted,incoming;
    private String owner="",local="",instance="";
    private volatile long epoch;
    private IntercomPlayback.Receipt receipt;
    IntercomController(PluginHost host,Supplier<String> addon,Supplier<String> context,Function<IngressAccess.Session,String> own,Consumer<String> diagnostic,Runnable settled){
        access=new IngressAccess(host);this.addon=addon;this.context=context;this.own=own;this.diagnostic=diagnostic;this.settled=settled;api.api.ownReceiver(own);
    }
    boolean busy(){return active||working||receipt!=null;}
    void state(String state){
        if(closed)return;
        if(!java.util.Arrays.asList("ringing","in_call","calling","ended","idle","missed").contains(state))return;
        if("ringing".equals(state))incoming=true;
        if("calling".equals(state))incoming=false;
        boolean next=incoming&&IntercomPlayback.call(state);
        if(!IntercomPlayback.call(state))incoming=false;
        if(next&&!active){active=true;if(!working&&receipt==null){attempted=false;owner=context.get();instance=addon.get();}}
        else if(!next)active=false;
        drive();
    }
    private void drive(){
        if(closed||working)return;
        boolean begin=active&&!attempted,finish=!active&&receipt!=null;
        if(!begin&&!finish){if(!active)settled.run();return;}
        if(begin)attempted=true;
        if(!owner.equals(context.get())||!instance.equals(addon.get())||instance.isEmpty()){receipt=null;diagnostic.accept("Call playback unchanged: selection/settings changed or connection unavailable.");if(!active)settled.run();return;}
        working=true;final long ticket=epoch;
        BooleanSupplier valid=()->!closed&&ticket==epoch&&owner.equals(context.get())&&instance.equals(addon.get());
        access.ensure(instance,valid,session->{
            local=own.apply(session);
            if(local.isEmpty()||!begin&&!local.equals(receipt.local)){done(ticket,null,"Call receiver could not be verified; no playback action submitted.");return;}
            try{worker.submit(()->{
                FeatureApi.Connection c=api.new Connection(session,()->valid.getAsBoolean()&&local.equals(own.apply(session)),()->{
                    FeatureApi.require(begin?active:!active,"Call phase changed before submission.");
                    CountDownLatch checked=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean same=new java.util.concurrent.atomic.AtomicBoolean();
                    access.ensure(instance,valid,latest->{same.set(latest==session);checked.countDown();},error->checked.countDown());
                    FeatureApi.require(checked.await(8,TimeUnit.SECONDS)&&same.get()&&valid.getAsBoolean(),"Call HA identity changed; nothing submitted.");
                    FeatureApi.require(begin?active:!active,"Call phase changed before submission.");
                });
                try{
                    if(begin){IntercomPlayback.Receipt next=IntercomPlayback.begin(api,c,local);done(ticket,next,"Call handling · "+next.kind.name().toLowerCase(java.util.Locale.ROOT));}
                    else{IntercomPlayback.finish(api,c,receipt);done(ticket,null,"Call playback restoration verified.");}
                }catch(Exception error){
                    if(!begin&&!c.submitted&&active&&valid.getAsBoolean())done(ticket,receipt,"Call restoration deferred until the new incoming call ends.");
                    else done(ticket,null,c.submitted?(begin?"Call pause/leave sent but not verified; no automatic restore. Check Player/Group.":"Call resume/rejoin sent but not verified. Check Player/Group.")+" No automatic retry.":error instanceof IllegalArgumentException?error.getMessage():"Call playback read failed; local mute remains the fallback. No automatic retry.");
                }
            });}catch(RuntimeException error){done(ticket,null,"Call worker unavailable; playback unchanged.");}
        },error->done(ticket,null,"Call connection unavailable; playback unchanged. No automatic retry."));
        // A call ending while access.ensure is pending can suppress its callback.
        main.postDelayed(()->{if(!closed&&epoch==ticket&&working){epoch++;working=false;receipt=null;diagnostic.accept("Call handling timed out; inspect Player/Group. No automatic retry.");if(!active)settled.run();}},45000);
    }
    private void done(long ticket,IntercomPlayback.Receipt next,String message){main.post(()->{
        if(closed||epoch!=ticket)return;epoch++;working=false;receipt=next!=null&&next.kind!=IntercomPlayback.Kind.NONE?next:null;diagnostic.accept(message);drive();
    });}
    void close(){closed=true;epoch++;receipt=null;api.api.cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);}
}
