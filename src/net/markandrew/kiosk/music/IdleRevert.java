package net.markandrew.kiosk.music;

/** Monotonic, continuous-idle-only inactivity policy. Unknown and paused never count. */
final class IdleRevert {
    private long since = -1;
    private boolean fired;
    static int seconds(Object value) {
        try { int seconds=Integer.parseInt(String.valueOf(value));return seconds>=0&&seconds<=86400?seconds:60; }
        catch(RuntimeException ignored){return 60;}
    }
    void reset() { since=-1;fired=false; }
    void touch(long now) { if(since>=0)since=now;fired=false; }
    boolean observe(String state,long now,int seconds) {
        if(seconds<=0||!"idle".equals(state)){reset();return false;}
        if(since<0)since=now;
        if(!fired&&now-since>=seconds*1000L){fired=true;return true;}
        return false;
    }
}
