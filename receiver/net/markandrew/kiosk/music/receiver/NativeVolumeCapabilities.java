package net.markandrew.kiosk.music.receiver;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import net.markandrew.kiosk.music.VolumeCapability;

/** Reads only: never requests permission or changes any stream, route or setting. */
public final class NativeVolumeCapabilities {
    private NativeVolumeCapabilities(){}
    public static VolumeCapability.Range read(Context context){
        try{
            AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(audio==null||audio.isVolumeFixed())return null;
            int min=Build.VERSION.SDK_INT>=28?audio.getStreamMinVolume(AudioManager.STREAM_MUSIC):0;
            return VolumeCapability.range(min,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }catch(RuntimeException error){return null;}
    }
    public static String describe(Context context,String advertised){
        VolumeCapability.Range current=read(context),announced=VolumeCapability.parse(advertised);
        String text="Native media volume range · "+(current==null?"unknown or fixed":current.describe());
        if(announced==null)return text+" · no range advertised on the current receiver connection";
        return text+" · connection advertises "+announced.describe()+
            (announced.equals(current)?" · matches current range":" · range changed; reconnect needed to refresh metadata");
    }
}
