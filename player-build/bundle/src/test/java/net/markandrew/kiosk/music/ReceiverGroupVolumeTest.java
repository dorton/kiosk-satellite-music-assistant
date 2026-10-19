package net.markandrew.kiosk.music;

import android.app.Activity;
import android.media.VolumeProvider;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.IntConsumer;
import net.markandrew.kiosk.music.receiver.PluginReceiver;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real provider callbacks; receiver/connection eligibility remains an explicit fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class ReceiverGroupVolumeTest {
    PluginReceiver receiver;VolumeProvider provider;List<Integer> delta=new ArrayList<>(),absolute=new ArrayList<>();
    private void set(String name,Object value)throws Exception{Field f=PluginReceiver.class.getDeclaredField(name);f.setAccessible(true);f.set(receiver,value);}
    @Before public void setup()throws Exception{
        receiver=new PluginReceiver(Robolectric.buildActivity(Activity.class).setup().get());set("running",true);set("playerActive",true);set("playback","playing");
        Method m=PluginReceiver.class.getDeclaredMethod("createGroupVolumeProvider",int.class,IntConsumer.class,IntConsumer.class);m.setAccessible(true);
        provider=(VolumeProvider)m.invoke(receiver,40,(IntConsumer)delta::add,(IntConsumer)absolute::add);set("groupVolumeProvider",provider);
    }
    @After public void stop(){receiver.stop();}
    @Test public void remotePanelUsesGroupRangeAndCommandsNotLocalDelta(){assertEquals(100,provider.getMaxVolume());assertEquals(40,provider.getCurrentVolume());assertEquals(VolumeProvider.VOLUME_CONTROL_ABSOLUTE,provider.getVolumeControl());provider.onAdjustVolume(1);provider.onSetVolumeTo(63);shadowOf(Looper.getMainLooper()).idle();assertEquals(Arrays.asList(1),delta);assertEquals(Arrays.asList(63),absolute);}
    @Test public void staleProviderCannotWriteAfterRouteClears(){receiver.clearGroupVolumeRoute();provider.onAdjustVolume(-1);shadowOf(Looper.getMainLooper()).idle();assertTrue(delta.isEmpty());}
    @Test public void speechRevokesRouteBeforeQueuedKeysRun(){provider.onAdjustVolume(1);receiver.voiceActivity("intercom",true);shadowOf(Looper.getMainLooper()).idle();assertTrue(delta.isEmpty());assertFalse(receiver.groupVolumeRouted());}
    @Test public void stoppedPlaybackRejectsProviderCallbacks()throws Exception{set("playback","stopped");provider.onSetVolumeTo(90);shadowOf(Looper.getMainLooper()).idle();assertTrue(absolute.isEmpty());}
    @Test public void unpairedReceiverCannotAcquireRemoteRoute(){receiver.clearGroupVolumeRoute();assertFalse(receiver.routeGroupVolume(40,delta::add,absolute::add));}
}
