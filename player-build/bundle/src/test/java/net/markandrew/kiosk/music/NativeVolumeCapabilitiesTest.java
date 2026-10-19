package net.markandrew.kiosk.music;

import android.content.Context;
import android.media.AudioManager;
import net.markandrew.kiosk.music.receiver.NativeVolumeCapabilities;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class NativeVolumeCapabilitiesTest {
    private Context context;private AudioManager audio;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);}
    @Test public void readsActualRangeWithoutWritingVolume(){for(int max:new int[]{15,30,32}){Shadows.shadowOf(audio).setStreamMaxVolume(max);int before=audio.getStreamVolume(AudioManager.STREAM_MUSIC);VolumeCapability.Range r=NativeVolumeCapabilities.read(context);assertNotNull(r);assertEquals(max,r.max);assertEquals(before,audio.getStreamVolume(AudioManager.STREAM_MUSIC));}}
    @Test @Config(sdk=26) public void olderAndroidUsesZeroMinimumWithoutCallingNewerApi(){Shadows.shadowOf(audio).setStreamMaxVolume(15);assertEquals(VolumeCapability.range(0,15),NativeVolumeCapabilities.read(context));}
    @Test public void diagnosticsDistinguishCurrentFromConnectionSnapshot(){Shadows.shadowOf(audio).setStreamMaxVolume(30);String advertised=VolumeCapability.advertise("Music Assistant Plugin 0.9.52-dev",NativeVolumeCapabilities.read(context));assertTrue(NativeVolumeCapabilities.describe(context,advertised).contains("matches current range"));Shadows.shadowOf(audio).setStreamMaxVolume(15);String changed=NativeVolumeCapabilities.describe(context,advertised);assertTrue(changed.contains("range changed; reconnect needed"));assertTrue(changed.contains("6.67%"));}
    @Test public void invalidRangeIsNotAdvertised(){Shadows.shadowOf(audio).setStreamMaxVolume(0);assertNull(NativeVolumeCapabilities.read(context));}
}
