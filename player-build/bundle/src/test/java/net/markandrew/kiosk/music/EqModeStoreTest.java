package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class EqModeStoreTest {
    private Context context;
    private EqApi.State state(String server,String user,String player,JSONObject raw)throws Exception{
        return new EqApi.State(null,player,FeatureApi.args("name",player),FeatureApi.args("user_id",user),FeatureApi.args("server_id",server),true,new EqDraft(raw));
    }
    @Before public void setup(){context=RuntimeEnvironment.getApplication();new AtomicFile(new File(context.getNoBackupFilesDir(),"musicassistantplugin-eq-modes-v1.json")).delete();}
    @Test public void distinctPreampsSurviveReopenWithoutChangingServerCurves()throws Exception{
        EqApi.State s=state("server","user","player",EqTest.config());EqModeStore store=new EqModeStore(context,s);
        s.draft.select(true);new EqGraphic(s.draft).preamp(-9.5);new EqGraphic(s.draft).band(2,4.5);new EqGraphic(s.draft).activate(true);
        s.draft.select(false);new EqGraphic(s.draft).preamp(-1.5);new EqGraphic(s.draft).activate(false);JSONObject applied=s.draft.candidate();store.commit(s.draft,applied);
        EqApi.State reopened=state("server","user","player",applied);new EqModeStore(context,reopened).restore(reopened.draft);
        reopened.draft.select(true);assertEquals(-9.5,new EqGraphic(reopened.draft).preamp(),0);assertEquals(4.5,new EqGraphic(reopened.draft).gains()[2],0);
        reopened.draft.select(false);assertEquals(-1.5,new EqGraphic(reopened.draft).preamp(),0);
    }
    @Test public void remoteDspChangesInvalidateOldPreampMemories()throws Exception{
        EqApi.State s=state("s","u","p",EqTest.config());s.draft.basicPreamp=-10;new EqModeStore(context,s).commit(s.draft,s.draft.baseline);
        EqApi.State changed=state("s","u","p",EqTest.config().put("input_gain",-3));new EqModeStore(context,changed).restore(changed.draft);changed.draft.select(false);assertEquals(-3,new EqGraphic(changed.draft).preamp(),0);
    }
    @Test public void memoriesAreIsolatedByServerUserAndPlayer()throws Exception{
        EqApi.State s=state("s","u","p",EqTest.config());s.draft.basicPreamp=-10;new EqModeStore(context,s).commit(s.draft,s.draft.baseline);
        for(String[] ids:new String[][]{{"other","u","p"},{"s","other","p"},{"s","u","other"}}){EqApi.State other=state(ids[0],ids[1],ids[2],EqTest.config());new EqModeStore(context,other).restore(other.draft);assertEquals(-4,other.draft.basicPreamp,0);}
    }
    @Test public void concurrentLocalChangesAreNotOverwritten()throws Exception{
        EqApi.State s=state("s","u","p",EqTest.config());EqModeStore first=new EqModeStore(context,s),stale=new EqModeStore(context,s);s.draft.basicPreamp=-10;first.commit(s.draft,s.draft.baseline);
        try{stale.commit(s.draft,s.draft.baseline);fail();}catch(IllegalArgumentException expected){}EqApi.State reopened=state("s","u","p",EqTest.config());new EqModeStore(context,reopened).restore(reopened.draft);assertEquals(-10,reopened.draft.basicPreamp,0);
    }
    @Test public void absentGraphicBankKeepsTheNewAdvancedFlatDefaultDespiteOldUnusedMemory()throws Exception{EqApi.State s=state("s","u","p",EqTest.config());s.draft.advancedPreamp=-4;new EqModeStore(context,s).commit(s.draft,s.draft.baseline);EqApi.State reopened=state("s","u","p",EqTest.config());new EqModeStore(context,reopened).restore(reopened.draft);reopened.draft.select(true);assertEquals(0,new EqGraphic(reopened.draft).preamp(),0);assertNull(new EqGraphic(reopened.draft).bank());}
}
