package net.markandrew.kiosk.music;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import me.jxl.kiosk.plugins.KsTheme;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30,qualifiers="w960dp-h480dp-land-mdpi")
public class EqTest {
    static JSONObject config()throws Exception{return FeatureApi.args("enabled",true,"input_gain",-4.0,"output_gain",-1.5,"preset_id","kept-preset","filters",new JSONArray().put(FeatureApi.args("type","gain","enabled",true,"gain",-2.0)));}
    private static void rejects(Runnable task){try{task.run();fail("Expected validation rejection");}catch(IllegalArgumentException expected){}}
    @Test public void draftIsDeepAndPreservesOtherFiltersAndGains()throws Exception{
        JSONObject raw=config();EqDraft d=new EqDraft(raw);d.add("tone_control").put("bass_level",2.3);
        assertEquals(1,raw.getJSONArray("filters").length());assertEquals(-4,d.config.getDouble("input_gain"),0);
        assertTrue(EqDraft.same(raw.getJSONArray("filters").get(0),d.filters().get(0)));assertEquals("kept-preset",d.baseline.getString("preset_id"));assertTrue(d.candidate().isNull("preset_id"));
    }
    @Test public void parametricBandUsesExactMaChannelAndDefaults()throws Exception{JSONObject b=EqDraft.band();assertEquals("ALL",b.getString("channel"));assertEquals("peak",b.getString("type"));assertEquals(1,b.getDouble("q"),0);}
    @Test public void allSixTypesValidateAndChannelDataSurvives()throws Exception{
        EqDraft d=new EqDraft(config());JSONObject f=d.add("parametric_eq");f.getJSONObject("per_channel_preamp").put("FL",-3);
        for(String t:EqDraft.TYPES)f.getJSONArray("bands").put(EqDraft.band().put("type",t).put("channel","FR"));
        d.validate();assertEquals(-3,d.candidate().getJSONArray("filters").getJSONObject(1).getJSONObject("per_channel_preamp").getInt("FL"));
    }
    @Test public void numericRejectsNanInfinityBlankAndOutsideRanges(){for(String s:new String[]{"NaN","Infinity","","junk","61","-61"})rejects(()->EqDraft.number(s,-60,60));assertEquals(-0.25,EqDraft.number("-0.25",-60,60),0);}
    @Test public void structuralEqualityIgnoresKeyOrderButNotFilterOrder()throws Exception{
        assertTrue(EqDraft.same(new JSONObject("{a:1,b:2}"),new JSONObject("{b:2.0,a:1.0}")));
        assertFalse(EqDraft.same(new JSONArray("[1,2]"),new JSONArray("[2,1]")));
    }
    @Test public void groupAndWrongPlayerAreRejected()throws Exception{
        JSONObject p=FeatureApi.args("player_id","p","provider","sendspin","available",true,"type","group");rejects(()->EqApi.target(p,"p"));
        p.put("type","player");EqApi.target(p,"p");rejects(()->EqApi.target(p,"other"));p.put("available",false);rejects(()->EqApi.target(p,"p"));
    }
    @Test public void permissionRequiresExplicitReadAndWriteScopes()throws Exception{
        JSONObject user=FeatureApi.args("role","user"),scopes=FeatureApi.args("user",new JSONArray().put("players.control").put("config.players.read"));
        assertTrue(EqApi.permitted(scopes,user,"config.players.read"));assertFalse(EqApi.permitted(scopes,user,"config.players.write"));
        user.put("role","missing");assertFalse(EqApi.permitted(scopes,user,"config.players.read"));
    }
    private TouchPanel panel() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();Map<String,Integer> colors=new HashMap<>();for(String k:new String[]{"surface","surfaceContainerHigh","surfaceContainerHighest","onSurface","onSurfaceVariant","primary","onPrimary","outlineVariant"})colors.put(k,0xff556677);
        TouchPanel p=new TouchPanel(a,new KsTheme(true,1,colors,w->null,n->new android.graphics.Path()),"Equaliser");a.setContentView(p);return p;
    }
    private View find(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText())||label.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),label);if(v!=null)return v;}return null;}
    private void editor(TouchPanel p,EqDraft d,boolean advanced,java.util.function.Consumer<Boolean> redraw,Runnable apply)throws Exception{
        new EqEditor(p,d,advanced,new EqPresetStore(p.getContext()),redraw,()->{},()->{},apply,()->{});
    }
    @Test public void switchingViewsPreservesBothFiltersWithoutApplying()throws Exception{
        EqDraft d=new EqDraft(config());d.add("tone_control").put("bass_level",3.2);d.add("parametric_eq").getJSONArray("bands").put(EqDraft.band());
        JSONObject snapshot=EqDraft.copy(d.config);boolean[] advanced={false};int[] applies={0};TouchPanel p=panel();
        editor(p,d,false,v->advanced[0]=v,()->applies[0]++);find(p,"Switch to Advanced").performClick();
        assertTrue(advanced[0]);assertEquals(0,applies[0]);assertTrue(EqDraft.same(snapshot,d.config));
        TouchPanel advancedPanel=panel();editor(advancedPanel,d,true,v->advanced[0]=v,()->applies[0]++);find(advancedPanel,"Switch to Basic").performClick();
        assertFalse(advanced[0]);assertTrue(EqDraft.same(snapshot.getJSONArray("filters"),d.filters()));assertTrue(EqDraft.same(config(),d.baseline));assertEquals(0,d.config.getDouble("input_gain"),0);
    }
    @Test public void movingGraphicBandCreatesOnlyStagedBank()throws Exception{
        EqDraft d=new EqDraft(config());int[] applies={0};TouchPanel p=panel();editor(p,d,true,v->{},()->applies[0]++);
        View slider=find(p,"32 · 0 dB");assertNotNull(slider);slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);
        assertEquals(10,new EqGraphic(d).bank().getJSONArray("bands").length());assertEquals(1,new EqGraphic(d).gains()[0],0.00001);assertEquals(0,applies[0]);
    }
    @Test public void advancedHasNoNestedParametricEditor()throws Exception{
        EqDraft d=new EqDraft(config());d.add("parametric_eq").getJSONArray("bands").put(EqDraft.band());int[] applies={0};TouchPanel p=panel();
        editor(p,d,true,v->{},()->applies[0]++);assertNull(find(p,"Q / bandwidth"));assertNull(find(p,"Add band"));assertNull(find(p,"Filter type"));assertEquals(0,applies[0]);
    }
    @Test public void disabledAndNullPreampRemainUntouchedOnViewSwitch()throws Exception{
        EqDraft d=new EqDraft(config());d.config.put("enabled",false);d.add("parametric_eq").put("preamp",JSONObject.NULL);JSONObject before=EqDraft.copy(d.config);
        TouchPanel p=panel();editor(p,d,true,v->{},()->{});find(p,"Switch to Basic").performClick();assertTrue(EqDraft.same(before.getJSONArray("filters"),d.filters()));assertFalse(d.config.getBoolean("enabled"));assertTrue(d.filters().getJSONObject(1).isNull("preamp"));
    }
    @Test public void modesAreExclusiveAndUnrelatedFiltersAreUntouched()throws Exception{
        EqDraft d=new EqDraft(config());EqGraphic g=new EqGraphic(d);g.band(2,4.5);g.ensureTone().put("bass_level",3.5);
        JSONObject arbitrary=d.add("parametric_eq");arbitrary.getJSONArray("bands").put(EqDraft.band().put("gain",2));String before=arbitrary.toString(),gain=d.filters().getJSONObject(0).toString();
        d.select(false);g.activate(false);assertTrue(g.tone().getBoolean("enabled"));assertFalse(g.bank().getBoolean("enabled"));
        d.select(true);g.activate(true);assertFalse(g.tone().getBoolean("enabled"));assertTrue(g.bank().getBoolean("enabled"));assertEquals(4.5,g.gains()[2],0);assertEquals(3.5,g.tone().getDouble("bass_level"),0);
        assertEquals(before,arbitrary.toString());assertEquals(gain,d.filters().getJSONObject(0).toString());
    }
    @Test public void eachModeRestoresItsPreampWithoutReselectingPreset()throws Exception{
        EqDraft d=new EqDraft(config());EqGraphic g=new EqGraphic(d);d.select(true);g.preamp(-8.5);g.band(0,4);g.activate(true);
        d.select(false);assertEquals(-4,g.preamp(),0);g.preamp(-2.5);g.activate(false);
        d.select(true);assertEquals(-8.5,g.preamp(),0);g.activate(true);assertEquals(4,g.gains()[0],0);
        d.select(false);assertEquals(-2.5,g.preamp(),0);
    }
    @Test public void applyingBasicOrAdvancedDoesNotTurnDisabledDspOn()throws Exception{EqDraft d=new EqDraft(config().put("enabled",false));new EqGraphic(d).activate(true);assertFalse(d.config.getBoolean("enabled"));new EqGraphic(d).activate(false);assertFalse(d.config.getBoolean("enabled"));}
    @Test public void disabledDuplicateFiltersAreNotEnabledAsASideEffect()throws Exception{
        EqDraft d=new EqDraft(config());EqGraphic g=new EqGraphic(d);g.ensureBank();JSONObject duplicate=EqDraft.copy(g.bank()).put("enabled",false);d.filters().put(duplicate);g.ensureTone();JSONObject extraTone=EqDraft.copy(g.tone()).put("enabled",false);d.filters().put(extraTone);
        g.activate(true);assertFalse(duplicate.getBoolean("enabled"));assertFalse(extraTone.getBoolean("enabled"));g.activate(false);assertFalse(duplicate.getBoolean("enabled"));assertFalse(extraTone.getBoolean("enabled"));
    }
    @Test public void switchingApplyResetsOnlyLeavingTypeIncludingPreampWithoutChangingVisibleDraft()throws Exception{
        EqDraft initial=new EqDraft(config());EqGraphic original=new EqGraphic(initial);original.band(0,5.5);original.ensureTone().put("bass_level",3.5).put("enabled",false);original.preamp(-8);
        EqDraft d=new EqDraft(initial.candidate());d.basicPreamp=-2;d.advancedPreamp=-8;d.select(false);new EqGraphic(d).ensureTone().put("mid_level",2);String before=d.config.toString();
        EqDraft applied=d.applied(false);EqGraphic result=new EqGraphic(applied);assertEquals(before,d.config.toString());assertEquals(-8,d.advancedPreamp,0);assertEquals(5.5,new EqGraphic(d).gains()[0],0);
        assertEquals(0,result.gains()[0],0);assertFalse(result.bank().getBoolean("enabled"));assertTrue(result.tone().getBoolean("enabled"));assertEquals(3.5,result.tone().getDouble("bass_level"),0);assertEquals(2,result.tone().getDouble("mid_level"),0);assertEquals(-2,result.preamp(),0);assertEquals(0,applied.advancedPreamp,0);
        assertTrue(EqDraft.same(d.filters().get(0),applied.filters().get(0)));
    }
    @Test public void disablingApplyZerosOnlyActiveModeAndItsPreamp()throws Exception{
        EqDraft initial=new EqDraft(config());EqGraphic g=new EqGraphic(initial);g.ensureTone().put("bass_level",3);g.band(0,6);g.bank().put("enabled",false);
        EqDraft d=new EqDraft(initial.candidate());d.select(false);d.advancedPreamp=-7;d.config.put("enabled",false);EqDraft applied=d.applied(false);EqGraphic after=new EqGraphic(applied);
        assertEquals(3,new EqGraphic(d).tone().getDouble("bass_level"),0);assertEquals(0,after.tone().getDouble("bass_level"),0);assertEquals(0,after.preamp(),0);assertEquals(0,applied.basicPreamp,0);assertEquals(-7,applied.advancedPreamp,0);assertEquals(6,after.gains()[0],0);assertFalse(applied.config.getBoolean("enabled"));
    }
    @Test public void editingSameTypeDoesNotResetItsProfile()throws Exception{
        EqDraft initial=new EqDraft(config());new EqGraphic(initial).band(1,5);EqDraft d=new EqDraft(initial.candidate());d.select(true);new EqGraphic(d).band(3,2);EqGraphic after=new EqGraphic(d.applied(true));assertEquals(5,after.gains()[1],0);assertEquals(2,after.gains()[3],0);
    }
    @Test public void missingGraphicBankDefaultsToFlatSelectorWithoutAddingABank()throws Exception{EqDraft d=new EqDraft(config());TouchPanel p=panel();editor(p,d,true,v->{},()->{});assertNotNull(find(p,"Flat ▾"));assertNotNull(find(p,"Preamp · 0 dB"));assertNull(new EqGraphic(d).bank());assertEquals("Flat",EqPresets.match(new EqPresets.Curve(new EqGraphic(d).preamp(),new EqGraphic(d).gains()),Collections.emptyMap()));assertTrue(EqDraft.same(config(),d.baseline));}
}
