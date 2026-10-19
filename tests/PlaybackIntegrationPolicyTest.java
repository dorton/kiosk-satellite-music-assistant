package net.markandrew.kiosk.music;

public final class PlaybackIntegrationPolicyTest {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("Playback integration "+checks);}
    static void near(float a,float b){check(Math.abs(a-b)<.00001f);}
    public static void main(String[] args){
        check(AudioGainPolicy.configuredPercent(null)==10);check(AudioGainPolicy.configuredPercent("NaN")==10);
        check(AudioGainPolicy.configuredPercent(0)==10);check(AudioGainPolicy.configuredPercent(11)==10);
        for(int p=1;p<=10;p++){
            AudioGainPolicy gain=new AudioGainPolicy();gain.volume(.4f);gain.percent(p);near(gain.gain(),.4f);
            gain.interaction("voice",true);near(gain.gain(),.4f*p/100);check(!gain.interrupted());
            gain.interaction("announcement",true);gain.interaction("voice",false);near(gain.gain(),.4f*p/100);
            gain.volume(.7f);near(gain.gain(),.7f*p/100);
            gain.interaction("announcement",false);near(gain.gain(),.7f);
            gain.focus(-3);near(gain.gain(),.7f*p/100);check(!gain.interrupted());
            gain.focus(-2);check(gain.interrupted());gain.interaction("voice",true);check(!gain.interrupted());near(gain.gain(),.7f*p/100);
            gain.focus(-1);check(gain.interrupted()); // Never ignore a permanent focus loss.
            gain.focus(1);gain.interaction("voice",false);near(gain.gain(),.7f);
        }
        PlaybackLaunchPolicy launch=new PlaybackLaunchPolicy();
        launch.observe("local:1",false);check(!launch.pending());launch.observe("local:1",true);check(launch.pending());
        launch.consume();launch.observe("local:1",true);check(!launch.pending()); // Track change / dismissed UI.
        launch.observe("local:1",false);launch.observe("local:1",true);check(launch.pending());
        launch.observe("local:1",false);check(!launch.pending());
        launch.observe("local:2",true);check(launch.pending());launch.reset();check(!launch.pending());
        System.out.println("PASS: "+checks+" ducking and playback-launch policy checks");
    }
}
