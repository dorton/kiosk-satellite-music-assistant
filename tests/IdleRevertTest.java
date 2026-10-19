package net.markandrew.kiosk.music;

public final class IdleRevertTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Idle revert "+checks);}
    public static void main(String[] args){
        check(IdleRevert.seconds(null)==60);check(IdleRevert.seconds("0")==0);
        check(IdleRevert.seconds("120")==120);check(IdleRevert.seconds("bad")==60);
        check(IdleRevert.seconds("-1")==60);check(IdleRevert.seconds("86401")==60);
        IdleRevert timer=new IdleRevert();
        check(!timer.observe("idle",0,60));check(!timer.observe("idle",59999,60));
        check(timer.observe("idle",60000,60));check(!timer.observe("idle",120000,60));
        timer.reset();check(!timer.observe("idle",0,60));timer.touch(30000);
        check(!timer.observe("idle",60000,60));check(timer.observe("idle",90000,60));
        for(String state:new String[]{"playing","paused","","unavailable","off"}){
            timer.reset();timer.observe("idle",0,60);check(!timer.observe(state,60000,60));
            check(!timer.observe(state,120000,60));check(!timer.observe("idle",120000,60));
            check(timer.observe("idle",180000,60));
        }
        timer.reset();check(!timer.observe("idle",0,0));check(!timer.observe("idle",900000,0));
        System.out.println("IdleRevert: "+checks+" idle-only, touch-reset, disabled, unknown and one-shot checks passed");
    }
}
