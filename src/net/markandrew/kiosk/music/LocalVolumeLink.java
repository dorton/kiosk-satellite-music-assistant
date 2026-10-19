package net.markandrew.kiosk.music;

import java.util.function.Consumer;

/** Main-thread, single-flight ownership of master for one local music session.
 * Host writes are never retried after failure/timeout. Temporary speech/restoration
 * levels are never reported as player volume. Audio stays gated until readback. */
final class LocalVolumeLink {
    interface Port {
        void read(Consumer<Integer> done,Consumer<String> fail);
        void write(int level,Runnable done,Consumer<String> fail);
        void gate(boolean muted);
        boolean report(int level);
        default boolean reportHardware(int level,boolean unmute){return !unmute&&report(level);}
        default boolean groupHardwareEnabled(){return false;}
        default boolean smoothGroupChange(){return false;}
        /** null result means verified standalone: use the unchanged local reporting path. */
        default void groupHardware(int previous,int actual,Consumer<Integer> done,Consumer<String> fail){done.accept(null);}
        default boolean cancelGroupHardware(){return false;}
        default void groupRoute(String text){}
        void later(Runnable task,long millis);
        void error(String text);
    }
    private final Port port;
    private String identity="",playback="";
    private Integer original,master,music;
    private long intent,operation,hardwareRevision,volumeRevision=-1;
    private boolean speech,busy,blocked,closed,reread,muted,groupReconcile,routingGroup;
    LocalVolumeLink(Port port){this.port=port;port.gate(true);}
    boolean ownsMaster(){return original!=null||busy||!identity.isEmpty()&&"playing".equals(playback);}
    boolean needsRecovery(){return blocked;}
    /** Explicit user recovery only; a failed operation is never automatically retried. */
    void recover(){if(closed||!blocked)return;hardwareRevision++;blocked=false;master=null;port.gate(true);if(original==null)drive();else deviceChanged();}
    void observe(String token,String state,Integer volume,long revision){
        observe(token,state,volume,revision,false);
    }
    void observe(String token,String state,Integer volume,long revision,boolean mute){
        if(closed)return;
        boolean structural=!identity.equals(token)||!playback.equals(state)||muted!=mute;
        if(structural)hardwareRevision++;
        boolean changed=structural;
        muted=mute;
        if(!identity.equals(token)){volumeRevision=-1;music=null;}
        identity=token;playback=state;
        if(!token.isEmpty()&&revision!=volumeRevision){
            volumeRevision=revision;
            if(volume!=null&&volume>=0&&volume<=100){music=volume;changed=true;}
        }
        if(changed){intent++;if(structural||speech||original==null||!smoothGroupChange())port.gate(true);}
        drive();
    }
    void speech(boolean active){if(closed||speech==active)return;hardwareRevision++;speech=active;intent++;if(ownsMaster())port.gate(true);
        if(active&&routingGroup){routingGroup=false;operation++;busy=false;reread=false;if(port.cancelGroupHardware())port.error("Group volume was in flight when speech started; inspect Group. No automatic retry.");}
        drive();}
    private boolean playing(){return !identity.isEmpty()&&("playing".equals(playback)||"paused".equals(playback)&&original!=null);}
    private long begin(){busy=true;long ticket=++operation;port.later(()->{if(!closed&&busy&&operation==ticket)fail(ticket,"Master volume operation timed out; music remains muted. Check device volume before restarting the plugin.");},9000);return ticket;}
    private boolean current(long ticket){return !closed&&busy&&operation==ticket;}
    private void fail(long ticket,String ignored){
        if(!current(ticket))return;busy=false;blocked=true;port.gate(true);
        port.error("Volume linking could not be confirmed. Music stays muted; master may have changed. Check device volume. No automatic write retry.");
    }
    private void read(long ticket,Consumer<Integer> done){
        try{port.read(level->{if(!current(ticket))return;if(level==null||level<0||level>100){fail(ticket,"");return;}done.accept(level);},error->fail(ticket,error));}
        catch(RuntimeException error){fail(ticket,"");}
    }
    private void drive(){
        if(closed||blocked||busy)return;
        boolean playing=playing();
        if(!playing){
            port.gate(true);
            if(original==null)return;
            // Do not trust a cached level: a final hardware event can race with Stop.
            long ticket=begin(),version=intent;
            read(ticket,actual->{master=actual;busy=false;if(version!=intent||playing()){drive();return;}
                if(actual.equals(original)){original=null;master=null;reread=false;return;}
                set(original,true);
            });return;
        }
        if(music==null){port.gate(true);return;}
        if(original==null){
            port.gate(true);long ticket=begin();
            read(ticket,level->{original=level;master=level;busy=false;drive();});return;
        }
        int desired=speech?original:muted?0:music;
        if(master==null||master!=desired){set(desired,false);return;}
        // Keep speech muted: raising master must never amplify the music underneath it.
        groupReconcile=false;
        port.gate(speech||muted);
        if(reread){reread=false;deviceChanged();}
    }
    private void set(int desired,boolean restoring){
        if(restoring||speech||muted||master==null||!smoothGroupChange())port.gate(true);long ticket=begin(),version=intent;
        try{port.write(desired,()->{
            if(!current(ticket))return;
            read(ticket,actual->{
                master=actual;busy=false;
                if(version==intent&&!restoring&&!speech&&muted&&actual!=0){
                    if(reread){reread=false;hardware(actual);return;}
                    blocked=true;port.gate(true);port.error("Muted master volume was not confirmed. Check device volume; no retry was sent.");return;
                }
                // Native volume steps are canonical. Never report speech or end-session restoration.
                if(version==intent&&!restoring&&!speech&&!muted&&playing()){
                    music=actual;
                    if(!groupReconcile&&!port.smoothGroupChange()&&!port.report(actual)){blocked=true;port.gate(true);port.error("Music volume could not be reported to the verified local receiver; music remains muted.");return;}
                    groupReconcile=false;
                }
                if(restoring&&version==intent&&!playing()){
                    if(Math.abs(actual-original)>1){blocked=true;port.gate(true);port.error("Original master volume restoration was not confirmed. Check device volume; no retry was sent.");return;}
                    original=null;master=null;reread=false;return;
                }
                drive();
            });
        },error->fail(ticket,error));}catch(RuntimeException error){fail(ticket,"");}
    }
    void deviceChanged(){
        if(closed||blocked||original==null||!playing())return;
        if(busy){reread=true;return;}
        Integer previous=master;
        long ticket=begin(),version=intent;
        read(ticket,actual->{
            boolean changed=master==null||master!=actual;master=actual;busy=false;
            if(changed)hardwareRevision++;
            if(version!=intent){drive();return;}
            if(changed&&!speech&&(!muted||actual>0)){
                if(previous!=null&&"playing".equals(playback)&&port.groupHardwareEnabled()){
                    if(muted)port.gate(true);long groupTicket=begin(),groupVersion=intent;String groupOwner=identity;routingGroup=true;
                    try{port.groupHardware(previous,actual,level->{
                        if(!current(groupTicket))return;routingGroup=false;busy=false;
                        if(level!=null){groupReconcile=true;port.groupRoute("Group volume verified; local receiver assigned "+level+"%.");}
                        else port.groupRoute("Verified standalone playback; unchanged individual receiver route.");
                        if(groupVersion!=intent){
                            if(level!=null&&groupOwner.equals(identity)&&!speech&&"playing".equals(playback)&&reread){reread=false;deviceChanged();return;}
                            drive();return;
                        }
                        if(level==null){hardware(actual);return;}
                        if(level<0||level>100){blocked=true;port.gate(true);port.error("Group volume returned an invalid level. Use Check connection before retrying.");return;}
                        music=level;intent++;if(reread){reread=false;deviceChanged();}else drive();
                    },error->{if(!current(groupTicket))return;routingGroup=false;if(groupVersion!=intent){busy=false;port.error(error);drive();return;}fail(groupTicket,error);port.error(error);});}
                    catch(RuntimeException error){routingGroup=false;fail(groupTicket,"");}return;
                }
                hardware(actual);return;
            }
            // Hardware adjustments during speech belong to speech, not MA music.
            if(speech){port.gate(true);return;}
            drive();
        });
    }
    /** At a native-volume endpoint no device.volume delta exists; only then use the key. */
    // A no-op native volume notification is not a consumed volume press.
    long edgeStamp(int direction){return !routingGroup&&master!=null&&(master==0&&direction<0||master==100&&direction>0)?hardwareRevision:-1;}
    void edgeKey(int direction,int step,long stamp){
        edgeKey(direction,step,stamp,0);
    }
    private void edgeKey(int direction,int step,long stamp,int waits){
        if(stamp<0||stamp!=hardwareRevision)return;
        if(closed||blocked||speech||muted||original==null||master==null||!"playing".equals(playback)||!port.groupHardwareEnabled())return;
        if(!(master==0&&direction<0||master==100&&direction>0)||step<1||step>100)return;
        // Wait only for an already-running native read, never retry a mutation.
        if(busy){if(!routingGroup&&waits<6)port.later(()->edgeKey(direction,step,stamp,waits+1),50);return;}
        hardwareRevision++;
        long ticket=begin(),version=intent;int previous=master;routingGroup=true;
        try{port.groupHardware(previous,previous+direction*step,level->{
            if(!current(ticket))return;routingGroup=false;busy=false;
            if(level!=null){groupReconcile=true;port.groupRoute("Group volume verified at native endpoint; local receiver assigned "+level+"%.");}
            else port.groupRoute("Verified standalone playback at native volume endpoint.");
            if(version!=intent){drive();return;}
            if(level!=null){if(level<0||level>100){blocked=true;port.gate(true);port.error("Invalid group volume; use Check connection.");return;}music=level;intent++;}
            drive();
        },error->{if(!current(ticket))return;routingGroup=false;if(version!=intent){busy=false;port.error(error);drive();return;}fail(ticket,error);port.error(error);});}catch(RuntimeException error){routingGroup=false;fail(ticket,"");}
    }
    private void hardware(int actual){
        boolean unmute=muted&&actual>0;
        music=actual;
        // Gate until both the receiver's PLAYER mute and its reported volume agree.
        port.gate(true);
        if(!port.reportHardware(actual,unmute)){blocked=true;port.error("Hardware volume report was not confirmed; music remains muted. Use Check connection to restore the volume link.");return;}
        muted=unmute?false:muted;intent++;drive();
    }
    private boolean smoothGroupChange(){return port.smoothGroupChange()||routingGroup||groupReconcile;}
    void close(){closed=true;hardwareRevision++;operation++;busy=false;port.gate(true);/* Host access is revoked at plugin.stop. */}
}
