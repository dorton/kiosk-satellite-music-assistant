package net.markandrew.kiosk.music;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class VolumeDisplayTest {
    private JSONObject player(String id,int level)throws Exception{return new JSONObject().put("player_id",id).put("type","player").put("volume_level",level);}
    @Test public void zeroIsSilentWithoutRewritingMuteFlag()throws Exception{JSONObject p=player("a",0);assertTrue(VolumeDisplay.silent(p,false));assertFalse(p.optBoolean("volume_muted"));assertTrue(new NowPlayingData(p,new JSONObject()).silent);}
    @Test public void missingLevelIsNotAssumedZero()throws Exception{assertFalse(VolumeDisplay.silent(new JSONObject(),false));}
    @Test public void oneZeroMemberNeverMutesWholeGroup()throws Exception{JSONObject a=player("a",0).put("group_members",new JSONArray().put("a").put("b")).put("group_volume",0);JSONArray rows=new JSONArray().put(a).put(player("b",1));VolumeDisplay.annotate(rows);assertFalse(VolumeDisplay.silent(a,true));}
    @Test public void everyMemberMustBeVerifiedZero()throws Exception{JSONObject a=player("a",0).put("group_members",new JSONArray().put("a").put("b"));JSONArray rows=new JSONArray().put(a).put(player("b",0));VolumeDisplay.annotate(rows);assertTrue(VolumeDisplay.silent(a,true));rows.getJSONObject(1).remove("volume_level");VolumeDisplay.annotate(rows);assertFalse(VolumeDisplay.silent(a,true));}
    @Test public void explicitGroupMuteAlwaysShowsSilent()throws Exception{assertTrue(VolumeDisplay.silent(new JSONObject().put("group_volume_muted",true),true));}
    @Test public void playbackSettingsDoNotRequireReconfiguration(){java.util.Map<String,Object> a=new java.util.HashMap<>(),b=new java.util.HashMap<>();a.put("addon","a");b.putAll(a);b.put("hardwareGroupVolume",true);assertTrue(MusicAssistantPlugin.playbackSettingsOnly(a,b));b.put("defaultPlayer","other");assertFalse(MusicAssistantPlugin.playbackSettingsOnly(a,b));}
}
