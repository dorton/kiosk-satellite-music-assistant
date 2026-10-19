package net.markandrew.kiosk.music;

/** One launch per local playback start; track changes and UI dismissal never relaunch. */
final class PlaybackLaunchPolicy {
    private String receiver="";
    private boolean playing,pending;
    void observe(String identity,boolean active){
        if(!identity.equals(receiver)){receiver=identity;playing=false;pending=false;}
        if(active&&!playing)pending=true;
        if(!active)pending=false;
        playing=active;
    }
    boolean pending(){return pending;}
    void consume(){pending=false;}
    void reset(){receiver="";playing=false;pending=false;}
}
