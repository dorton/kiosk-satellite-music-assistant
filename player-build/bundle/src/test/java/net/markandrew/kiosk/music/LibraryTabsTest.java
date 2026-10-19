package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class LibraryTabsTest {
    private Context context;
    private File file(){return new File(context.getNoBackupFilesDir(),"musicassistantplugin-library-tabs-v1.json");}
    @Before public void setup(){context=RuntimeEnvironment.getApplication();new AtomicFile(file()).delete();}
    @Test public void allTabsVisibleByDefaultWithoutCreatingAFile()throws Exception{assertEquals(new LinkedHashSet<>(LibraryTabs.KEYS),new LibraryTabs(context).visible);assertFalse(file().exists());}
    @Test public void editsAreStagedUntilSaveAndSurviveRecreation()throws Exception{LibraryTabs tabs=new LibraryTabs(context);tabs.visible.remove("radio");assertTrue(new LibraryTabs(context).visible.contains("radio"));tabs.commit();assertFalse(new LibraryTabs(context).visible.contains("radio"));assertEquals(6,new LibraryTabs(context).visible.size());}
    @Test public void cannotHideEveryTab()throws Exception{LibraryTabs tabs=new LibraryTabs(context);tabs.visible.clear();try{tabs.commit();fail();}catch(IllegalArgumentException expected){}assertFalse(file().exists());}
    @Test public void concurrentSaveIsNotOverwritten()throws Exception{LibraryTabs one=new LibraryTabs(context),two=new LibraryTabs(context);one.visible.remove("radio");one.commit();two.visible.remove("tracks");try{two.commit();fail();}catch(IllegalArgumentException expected){}assertTrue(new LibraryTabs(context).visible.contains("tracks"));}
    @Test public void malformedChoicesAreNotOverwrittenOrSilentlyDefaulted()throws Exception{try(FileOutputStream out=new FileOutputStream(file())){out.write("{\"visible\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}try{new LibraryTabs(context);fail();}catch(IllegalArgumentException expected){}assertTrue(file().exists());}
}
