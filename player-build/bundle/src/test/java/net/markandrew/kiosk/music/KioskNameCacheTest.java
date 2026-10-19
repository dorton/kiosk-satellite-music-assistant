package net.markandrew.kiosk.music;

import android.app.Activity;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class KioskNameCacheTest {
    private Activity activity;
    private KioskNameCache cache;
    @Before public void setup(){activity=Robolectric.buildActivity(Activity.class).setup().get();new android.util.AtomicFile(new java.io.File(activity.getNoBackupFilesDir(),"musicassistantplugin-kiosk-name-v1.txt")).delete();cache=new KioskNameCache(activity);}
    @Test public void lastGoodNameSurvivesANewCacheInstance(){cache.remember("Bedroom 1");assertEquals("Bedroom 1",new KioskNameCache(activity).read());}
    @Test public void failedOrGenericLookupNeverReplacesGoodName(){cache.remember("Kitchen");cache.remember("");cache.remember("Music Assistant Plugin");assertEquals("Kitchen",cache.read());}
    @Test public void realRenameUpdatesOnlyDisplayNameCache(){cache.remember("Kitchen");cache.remember("Bedroom 1");assertEquals("Bedroom 1",cache.read());assertEquals("Custom",ReceiverNamePolicy.resolve("Custom",cache.read()));}
    @Test public void emptyCacheDoesNotInventADeviceName(){assertEquals("",cache.read());}
}
