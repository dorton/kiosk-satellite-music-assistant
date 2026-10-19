package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class EqViewPreferenceTest {
    private Context context;
    private File file(){return new File(context.getNoBackupFilesDir(),"musicassistantplugin-eq-view-v1.txt");}
    @Before public void setup(){context=RuntimeEnvironment.getApplication();new AtomicFile(file()).delete();}
    @Test public void firstOpenDefaultsToBasicWithoutCreatingAFile()throws Exception{assertFalse(EqViewPreference.advanced(context));assertFalse(file().exists());}
    @Test public void advancedSurvivesCloseAndNewContextRead()throws Exception{EqViewPreference.save(context,true);assertTrue(EqViewPreference.advanced(context.getApplicationContext()));}
    @Test public void explicitReturnToBasicIsRemembered()throws Exception{EqViewPreference.save(context,true);EqViewPreference.save(context,false);assertFalse(EqViewPreference.advanced(context));}
    @Test public void rememberingViewDoesNotResetDspOrPresets()throws Exception{
        EqDraft draft=new EqDraft(EqTest.config());draft.add("tone_control").put("bass_level",3.5);String before=draft.config.toString();EqPresetStore presets=new EqPresetStore(context);boolean changed=presets.changed();
        EqViewPreference.save(context,true);EqViewPreference.save(context,false);assertEquals(before,draft.config.toString());assertEquals(changed,presets.changed());
    }
    @Test public void corruptViewPreferenceIsNotOverwrittenOnRead()throws Exception{
        try(FileOutputStream out=new FileOutputStream(file())){out.write("bad".getBytes(StandardCharsets.UTF_8));}
        try{EqViewPreference.advanced(context);fail();}catch(IOException expected){assertEquals("bad",new String(java.nio.file.Files.readAllBytes(file().toPath()),StandardCharsets.UTF_8));}
    }
}
