package net.markandrew.kiosk.music;

import android.os.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Redirect the existing native master delta, never add a competing device.key writer. */
final class LocalGroupVolume {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final FeatureApi api=new FeatureApi();
    private final IngressAccess access;
    private final Supplier<String> addon,identity,context;
    private final BooleanSupplier allowed;
    private volatile long epoch;
    private volatile boolean closed;
    private volatile boolean submitted;
    private volatile boolean pending;
    private volatile String lastMemberEvidence="";
    String memberEvidence(){return lastMemberEvidence;}
    LocalGroupVolume(PluginHost host,Supplier<String> addon,Supplier<String> identity,Supplier<String> context,BooleanSupplier allowed){this.addon=addon;this.identity=identity;this.context=context;this.allowed=allowed;access=new IngressAccess(host);api.api.ownReceiver(s->identity.get());}
    void adjust(int previous,int actual,Consumer<Integer> done,Consumer<String> fail){
        adjust(previous,actual,1,done,fail);
    }
    void adjust(int previous,int actual,int nativeStep,Consumer<Integer> done,Consumer<String> fail){
        adjustScoped("",actual-previous,false,nativeStep,done,fail);
    }
    void adjustScoped(String scope,int value,boolean absolute,Consumer<Integer> done,Consumer<String> fail){
        adjustScoped(scope,value,absolute,1,done,fail);
    }
    private void adjustScoped(String scope,int value,boolean absolute,int nativeStep,Consumer<Integer> done,Consumer<String> fail){
        submitted=false;pending=true;lastMemberEvidence="";
        final long ticket=++epoch,started=SystemClock.elapsedRealtime();final String app=addon.get(),own=identity.get(),owner=context.get();
        BooleanSupplier valid=()->!closed&&epoch==ticket&&allowed.getAsBoolean()&&app.equals(addon.get())&&own.equals(identity.get())&&owner.equals(context.get())&&SystemClock.elapsedRealtime()-started<7000;
        if(app.isEmpty()||own.isEmpty()||!valid.getAsBoolean()){pending=false;fail.accept("Local group-volume identity unavailable. No command sent.");return;}
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.BiConsumer<Integer,String> finish=(level,error)->main.post(()->{if(closed||epoch!=ticket||!completed.compareAndSet(false,true))return;pending=false;if(error==null)done.accept(level);else fail.accept(error);});
        access.ensure(app,valid,session->{try{worker.submit(()->{
            final String[] stage={"identity and permission checks"};
            FeatureApi.Connection c=api.new Connection(session,valid,()->{
                CountDownLatch latch=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean same=new java.util.concurrent.atomic.AtomicBoolean();
                access.ensure(app,valid,latest->{same.set(latest==session);latch.countDown();},e->latch.countDown());
                check(latch.await(1500,TimeUnit.MILLISECONDS)&&same.get()&&valid.getAsBoolean(),"Ingress session or playback context changed before submission");
                submitted=true;
            });
            try{finish.accept(change(api,c,own,value,scope,absolute,nativeStep,text->{stage[0]=text;if(!closed&&epoch==ticket&&text.startsWith("group readback ("))lastMemberEvidence=text;}),null);}
            catch(Exception error){finish.accept(null,failure(c.submitted,stage[0],error));}
        });}catch(RejectedExecutionException error){finish.accept(null,"Group volume worker unavailable; no command sent.");}},error->finish.accept(null,"Group volume connection unavailable; no command sent."));
        main.postDelayed(()->{if(!completed.get()&&epoch==ticket){finish.accept(null,"Group volume timed out; outcome may be uncertain. Check Group before retrying.");api.api.cancel();}},7500);
    }
    private static final class VerificationFailure extends IllegalArgumentException {VerificationFailure(String reason){super(reason);}}
    private static void check(boolean valid,String reason){if(!valid)throw new VerificationFailure(reason);}
    static String failure(boolean submitted,String stage,Exception error){
        String reason=error instanceof VerificationFailure?error.getMessage():safeReason(error);
        if(error instanceof VerificationFailure&&stage.startsWith("group readback ("))stage="group volume readback";
        return (submitted?"Group volume outcome uncertain":"Group volume not sent")+" · "+stage+": "+reason+". Check Group; use Check connection. No automatic retry.";
    }
    private static String safeReason(Exception error){
        String text=error.getMessage();
        // Only locally-authored validation text; never display arbitrary HTTP/API errors.
        for(String allowed:new String[]{"No active MA music queue.","Player/group unavailable.","Hardware volume only controls confirmed MA music playback.","This player/group does not expose adjustable volume.","Current player volume is unavailable.","Current player volume is invalid.","Invalid or cyclic player group.","The group leader is unavailable."})if(allowed.equals(text))return allowed;
        return "read, transport or playback-context verification failed";
    }
    private static void authority(FeatureApi api,FeatureApi.Connection c,String user,String server,String version)throws Exception{
        authority(api,c,user,server,version,text->{});
    }
    private static void authority(FeatureApi api,FeatureApi.Connection c,String user,String server,String version,Consumer<String> stage)throws Exception{
        stage.accept("account/server verification");
        JSONObject account=(JSONObject)api.api.identity(c.session),info=api.api.serverInfo(c.session);
        check(!user.isEmpty()&&user.equals(account.optString("user_id")),"MA account identity missing or changed");
        check(!server.isEmpty()&&server.equals(info.optString("server_id")),"MA server identity missing or changed");
        check(version.equals(info.optString("server_version"))&&version.matches("2\\.10\\.[0-9]+(?:[.-].*)?"),"MA version unsupported or changed");
        stage.accept("players.control permission verification");
        check(PairingDiagnostics.grant(account,c.object("auth/scopes",FeatureApi.args()),"players.control")==PairingDiagnostics.Grant.GRANTED,"players.control permission missing or unverifiable");
    }
    static Integer change(FeatureApi api,FeatureApi.Connection c,String own,int delta)throws Exception{
        return change(api,c,own,delta,"",false);
    }
    static String scope(HardwareVolumePolicy.Target target){return target.id+"\n"+target.queue+"\n"+new java.util.TreeSet<>(target.members);}
    static final class Plan {
        final String scope;final int level;
        Plan(HardwareVolumePolicy.Target target){scope=scope(target);level=target.level;}
        Plan(String scope,int level){this.scope=scope;this.level=level;}
    }
    static Plan plan(FeatureApi api,FeatureApi.Connection c,String own)throws Exception{
        JSONObject account=(JSONObject)api.api.identity(c.session),info=api.api.serverInfo(c.session);
        String user=account.optString("user_id"),server=info.optString("server_id"),version=info.optString("server_version");authority(api,c,user,server,version);
        JSONArray rows=c.array("players/all",FeatureApi.args("return_unavailable",true));String queue=HardwareVolumePolicy.queueId(rows,own);
        HardwareVolumePolicy.Target target=new HardwareVolumePolicy.Target(rows,c.object("player_queues/get",FeatureApi.args("queue_id",queue)),own,own);
        authority(api,c,user,server,version);return target.group?new Plan(target):null;
    }
    static Integer change(FeatureApi api,FeatureApi.Connection c,String own,int delta,String expectedScope,boolean absolute)throws Exception{
        return change(api,c,own,delta,expectedScope,absolute,1,text->{});
    }
    static Integer change(FeatureApi api,FeatureApi.Connection c,String own,int delta,String expectedScope,boolean absolute,int nativeStep,Consumer<String> stage)throws Exception{
        JSONObject account=(JSONObject)api.api.identity(c.session),info=api.api.serverInfo(c.session);String user=account.optString("user_id"),server=info.optString("server_id"),version=info.optString("server_version");
        authority(api,c,user,server,version,stage);
        stage.accept("active local-player/group lookup");
        JSONArray rows=c.array("players/all",FeatureApi.args("return_unavailable",true));String queue=HardwareVolumePolicy.queueId(rows,own);
        HardwareVolumePolicy.Target before=new HardwareVolumePolicy.Target(rows,c.object("player_queues/get",FeatureApi.args("queue_id",queue)),own,own);
        authority(api,c,user,server,version,stage);
        stage.accept("fresh playback and membership verification");
        JSONArray fresh=c.array("players/all",FeatureApi.args("return_unavailable",true));
        HardwareVolumePolicy.Target target=new HardwareVolumePolicy.Target(fresh,c.object("player_queues/get",FeatureApi.args("queue_id",queue)),own,own);
        // A level echo is not a membership change. Use the freshest verified level.
        check(before.same(target),"group leader, queue or members changed before submission");
        check(expectedScope.isEmpty()||target.group&&expectedScope.equals(scope(target)),"requested group scope changed; no fallback selected");
        if(!target.group)return null;
        JSONObject leader=FeatureApi.byId(fresh,"player_id",target.id),local=FeatureApi.byId(fresh,"player_id",own);
        check(local!=null&&target.members.contains(own),"local receiver missing from the verified group");
        check(!local.optBoolean("volume_muted")&&!leader.optBoolean("group_volume_muted"),"explicit player/group mute is active; unmute before adjusting");
        GroupVolumeEvidence baseline=new GroupVolumeEvidence(fresh,target.members);
        check(baseline.valid(),"pre-command member evidence incomplete: "+baseline.problem);
        check(baseline.levels.containsKey(own),"local receiver is not a powered adjustable group member");
        // The reported group summary can lag even at 0/100. Use the same fresh
        // member maximum for relative intent and no-op detection as for readback.
        int volume=absolute?Math.max(0,Math.min(100,delta)):baseline.relativeTarget(delta,own,nativeStep);
        stage.accept("group command submission");
        if(volume!=baseline.maximum)c.write("players/cmd/group_volume",FeatureApi.args("player_id",target.id,"volume_level",volume));
        stage.accept("group volume readback");
        String stable=null,last="no group reading",previousReading=null,rejected="no completed readback";
        int matchingSamples=0;
        for(int delay:new int[]{0,100,200,350,550,800}){
            if(delay>0)Thread.sleep(delay);
            JSONArray after=c.array("players/all",FeatureApi.args("return_unavailable",true));
            HardwareVolumePolicy.Target result=new HardwareVolumePolicy.Target(after,c.object("player_queues/get",FeatureApi.args("queue_id",queue)),own,own);
            check(target.same(result),"group leader, queue or members changed after submission");
            JSONObject member=FeatureApi.byId(after,"player_id",own);Object level=member==null?null:member.opt("volume_level");
            boolean validLevel=level instanceof Number&&Double.isFinite(((Number)level).doubleValue())&&((Number)level).doubleValue()>=0&&((Number)level).doubleValue()<=100;
            GroupVolumeEvidence evidence=new GroupVolumeEvidence(after,target.members);
            int direction=Double.compare(volume,baseline.maximum);
            boolean roundedUnchanged=evidence.unchangedMaximumExplained(baseline,volume,own,nativeStep);
            boolean moved=direction==0||direction<0&&evidence.maximum<baseline.maximum||direction>0&&evidence.maximum>baseline.maximum||roundedUnchanged;
            String memberFailure=evidence.directionFailure(baseline,own,direction);
            boolean coherent=memberFailure.isEmpty();
            String capabilityFailure=evidence.capabilityFailure(baseline);
            String resolutionFailure=evidence.resolutionFailure(baseline,volume,own,nativeStep);
            boolean maximumMatches=evidence.maximumMatches(baseline,volume,own,nativeStep);
            boolean acceptable=validLevel&&evidence.valid()&&moved&&coherent&&capabilityFailure.isEmpty()&&resolutionFailure.isEmpty()&&maximumMatches;
            String reading=evidence.signature();
            matchingSamples=reading.equals(previousReading)?matchingSamples+1:1;
            previousReading=reading;
            last="before reported group "+target.level+"%, before member max "+GroupVolumeEvidence.percent(baseline.maximum)+", requested "+volume+"%, observed group "+result.level+"%, member max "+GroupVolumeEvidence.percent(evidence.maximum)+", local "+(validLevel?((Number)level).intValue()+"%":"unknown")+", native step "+nativeStep+"%, "+evidence.maximumRule(baseline,volume,own,nativeStep)+(roundedUnchanged?"; unchanged maximum explained by native rounding and another member's directional movement":"")+"; "+evidence.summary(baseline,own);
            java.util.List<String> reasons=new java.util.ArrayList<>();
            if(!validLevel)reasons.add("local level missing/invalid");
            if(!moved)reasons.add(evidence.maximum==baseline.maximum?"member maximum unchanged":"member maximum moved opposite requested direction");
            if(!evidence.valid())reasons.add(evidence.problem);
            if(!coherent)reasons.add(memberFailure);
            if(!capabilityFailure.isEmpty())reasons.add(capabilityFailure);
            if(!resolutionFailure.isEmpty())reasons.add(resolutionFailure);
            if(!maximumMatches)reasons.add("member maximum outside supported tolerance");
            // Stability diagnostics are independent of acceptance: repeated but
            // unchanged or wrong-direction readings must not be called unstable.
            if(matchingSamples<2)reasons.add("readings not stable across two samples");
            rejected=String.join("; ",reasons);
            stage.accept("group readback ("+(rejected.isEmpty()?"member checks passed":rejected)+"; "+last+")");
            if(acceptable&&reading.equals(stable))return (int)Math.round(((Number)level).doubleValue());
            stable=acceptable?reading:null;
        }
        throw new VerificationFailure("readback rejected: "+rejected+"; matching samples "+matchingSamples+"; "+last);
    }
    boolean cancel(){boolean uncertain=pending&&submitted;pending=false;epoch++;api.api.cancel();access.clear();return uncertain;}
    void close(){closed=true;cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);}
}
