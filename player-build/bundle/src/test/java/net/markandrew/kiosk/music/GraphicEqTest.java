package net.markandrew.kiosk.music;

import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import me.jxl.kiosk.plugins.KsTheme;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class GraphicEqTest {
    private Context context;
    private java.io.File file(){return new java.io.File(context.getNoBackupFilesDir(),"musicassistantplugin-eq-presets-v1.json");}
    @Before public void setup(){context=RuntimeEnvironment.getApplication();new android.util.AtomicFile(file()).delete();}
    private static void rejects(Runnable r){try{r.run();fail("Expected rejection");}catch(IllegalArgumentException expected){}}
    @Test public void allTwentyTwoBuiltinsHaveValidTenBandCurves(){assertEquals(22,EqPresets.BUILTINS.size());for(EqPresets.Curve c:EqPresets.BUILTINS.values())c.validate();assertTrue(EqPresets.BUILTINS.containsKey("Small Speakers"));}
    @Test public void flatLeavesOtherFiltersUntouched()throws Exception{
        EqDraft d=new EqDraft(EqTest.config());JSONObject unrelated=d.filters().getJSONObject(0);EqGraphic g=new EqGraphic(d);g.preset(EqPresets.BUILTINS.get("Flat"));
        assertTrue(EqDraft.same(unrelated,d.filters().getJSONObject(0)));assertEquals(2,d.filters().length());assertEquals(0,g.preamp(),0);assertEquals("Flat",EqPresets.match(new EqPresets.Curve(g.preamp(),g.gains()),Collections.emptyMap()));
    }
    @Test public void arbitraryParametricIsNotMistakenForGraphicBank()throws Exception{
        EqDraft d=new EqDraft(EqTest.config());JSONObject arbitrary=d.add("parametric_eq");arbitrary.getJSONArray("bands").put(EqDraft.band());JSONObject before=EqDraft.copy(arbitrary);
        EqGraphic g=new EqGraphic(d);assertNull(g.bank());g.band(2,1.3);assertTrue(EqDraft.same(before,arbitrary));assertEquals(3,d.filters().length());assertEquals(1.3,g.gains()[2],0.000001);
    }
    @Test public void nearMatchingBankWithDifferentQIsPreserved()throws Exception{
        EqDraft d=new EqDraft(EqTest.config());EqGraphic g=new EqGraphic(d);JSONObject first=g.ensureBank();first.getJSONArray("bands").getJSONObject(0).put("q",1.0);assertNull(g.bank());g.band(0,2);assertEquals(3,d.filters().length());
    }
    @Test public void perChannelAndNonzeroFilterPreampAreNotConverted()throws Exception{
        EqDraft d=new EqDraft(EqTest.config());JSONObject bank=new EqGraphic(d).ensureBank();bank.put("preamp",-3);assertFalse(EqGraphic.canonical(bank));bank.put("preamp",0);bank.getJSONObject("per_channel_preamp").put("FL",-2);assertFalse(EqGraphic.canonical(bank));
    }
    @Test public void basicAndAdvancedShareInputPreampOnly()throws Exception{
        EqDraft d=new EqDraft(EqTest.config());EqGraphic g=new EqGraphic(d);g.preamp(-6);g.ensureTone().put("bass_level",3);g.band(0,4);assertEquals(-6,d.config.getDouble("input_gain"),0);assertEquals(-1.5,d.config.getDouble("output_gain"),0);assertEquals(3,g.tone().getDouble("bass_level"),0);
    }
    @Test public void loadingAndSwitchingDoNotInventFilters()throws Exception{EqDraft d=new EqDraft(EqTest.config());EqGraphic g=new EqGraphic(d);g.gains();g.tone();g.preamp();assertFalse(d.changed());assertEquals(1,d.filters().length());}
    @Test public void manualAndCustomCurveDetection()throws Exception{
        EqGraphic g=new EqGraphic(new EqDraft(EqTest.config()));g.band(0,0.7);EqPresets.Curve c=new EqPresets.Curve(g.preamp(),g.gains());assertEquals("Manual",EqPresets.match(c,Collections.emptyMap()));Map<String,EqPresets.Curve> custom=new LinkedHashMap<>();custom.put("Mine",c);assertEquals("Mine",EqPresets.match(c,custom));
    }
    @Test public void customCreationIsStagedUntilCommit()throws Exception{
        EqPresetStore store=new EqPresetStore(context);store.make("Mine",EqPresets.BUILTINS.get("Rock"));assertTrue(store.changed());assertTrue(new EqPresetStore(context).custom.isEmpty());store.commit();assertTrue(new EqPresetStore(context).custom.containsKey("Mine"));
    }
    @Test public void cancellationDoesNotPersistRenameOrDelete()throws Exception{
        EqPresetStore first=new EqPresetStore(context);first.make("Mine",EqPresets.BUILTINS.get("Rock"));first.commit();EqPresetStore edit=new EqPresetStore(context);edit.rename("Mine","Renamed");edit.delete("Renamed");assertTrue(new EqPresetStore(context).custom.containsKey("Mine"));
    }
    @Test public void renameAndDeletePersistOnlyCustomPresets()throws Exception{
        EqPresetStore s=new EqPresetStore(context);s.make("Mine",EqPresets.BUILTINS.get("Jazz"));s.commit();s=new EqPresetStore(context);s.rename("Mine","New");s.commit();s=new EqPresetStore(context);s.delete("New");s.commit();assertTrue(new EqPresetStore(context).custom.isEmpty());assertEquals(22,EqPresets.BUILTINS.size());
    }
    @Test public void builtinsAndCaseInsensitiveNamesAreProtected()throws Exception{
        EqPresetStore s=new EqPresetStore(context);rejects(()->s.make("flat",EqPresets.BUILTINS.get("Rock")));rejects(()->s.delete("Flat"));rejects(()->s.rename("Flat","Other"));s.make("Mine",EqPresets.BUILTINS.get("Rock"));rejects(()->s.make("mine",EqPresets.BUILTINS.get("Jazz")));rejects(()->s.rename("Mine","Manual"));
    }
    @Test public void concurrentPresetEditRefusesOverwrite()throws Exception{
        EqPresetStore first=new EqPresetStore(context),second=new EqPresetStore(context);first.make("First",EqPresets.BUILTINS.get("Jazz"));first.commit();second.make("Second",EqPresets.BUILTINS.get("Rock"));try{second.commit();fail();}catch(IllegalArgumentException expected){assertFalse(new EqPresetStore(context).custom.containsKey("Second"));}
    }
    @Test public void malformedLocalLibraryIsNotSilentlyReset()throws Exception{
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file())){out.write("broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        try{new EqPresetStore(context);fail();}catch(Exception expected){assertEquals("broken",new String(java.nio.file.Files.readAllBytes(file().toPath()),java.nio.charset.StandardCharsets.UTF_8));}
    }
    private GraphicEqView.Slider slider(double value,java.util.function.DoubleConsumer change,java.util.function.BooleanSupplier current){Map<String,Integer> colors=new HashMap<>();return new GraphicEqView.Slider(context,new KsTheme(true,1,colors,w->null,n->new android.graphics.Path()),"32",value,change,current);}
    @Test public void accessibilityAndHardwareKeyboardStepInWholeDb(){double[] value={0};GraphicEqView.Slider s=slider(0,v->value[0]=v,()->true);s.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);assertEquals(1,value[0],0.00001);s.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));assertEquals(0,value[0],0.00001);}
    @Test public void accessibilityClampsBoundsAndRejectsNan(){double[] value={0};GraphicEqView.Slider s=slider(0,v->value[0]=v,()->true);Bundle b=new Bundle();b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,100);s.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b);assertEquals(12,value[0],0);b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN);assertFalse(s.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b));assertEquals(12,value[0],0);}
    @Test public void staleSliderCannotChangeDraft(){double[] value={0};GraphicEqView.Slider s=slider(0,v->value[0]=v,()->false);assertFalse(s.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));assertEquals(0,value[0],0);}
    @Test public void existingOutOfRangeValueIsNotRoundedOnLoad(){GraphicEqView.Slider s=slider(-24.35,v->fail("Unexpected change"),()->true);assertTrue(s.getContentDescription().toString().contains("-24.35"));}
    @Test public void fractionIsPreservedUntilMovedThenGoesToNextWholeDb(){double[] value={3.5};GraphicEqView.Slider s=slider(3.5,v->value[0]=v,()->true);assertEquals(3.5,value[0],0);assertTrue(s.getContentDescription().toString().contains("3.5"));s.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);assertEquals(4,value[0],0);s.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null);assertEquals(3,value[0],0);}
    @Test public void explicitFractionalAccessibilityValueSnapsToInteger(){double[] value={0};GraphicEqView.Slider s=slider(0,v->value[0]=v,()->true);Bundle b=new Bundle();b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,-2.7f);s.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b);assertEquals(-3,value[0],0);}
}
