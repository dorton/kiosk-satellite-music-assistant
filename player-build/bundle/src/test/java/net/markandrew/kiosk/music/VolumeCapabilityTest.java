package net.markandrew.kiosk.music;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class VolumeCapabilityTest {
    private final String version="Music Assistant Plugin 0.9.52-dev";
    @Test public void runtimeRangesRoundTripWithoutAModelDatabase(){for(int max:new int[]{15,30,32,100,150,1000}){VolumeCapability.Range r=VolumeCapability.range(0,max);assertEquals(r,VolumeCapability.parse(VolumeCapability.advertise(version,r)));}}
    @Test public void unknownOmitsTagAndOldVersionsRemainUnknown(){assertEquals(version,VolumeCapability.advertise(version,null));assertNull(VolumeCapability.parse(version));assertNull(VolumeCapability.parse("Music Assistant Plugin 0.9.51-dev"));}
    @Test public void invalidAndFutureMetadataIsNotInterpreted(){for(String s:new String[]{"",null,version+" [ks-volume-v2=0:15]",version+" [ks-volume-v1=0:0]",version+" [ks-volume-v1=16:15]",version+" [ks-volume-v1=0:1001]",version+" [ks-volume-v1=-1:15]",version+" [ks-volume-v1=0:15] trailing",version+" [ks-volume-v1=0:15] [ks-volume-v1=0:30]","Other App 0.9.52-dev [ks-volume-v1=0:15]"})assertNull(s,VolumeCapability.parse(s));}
    @Test public void nonzeroMinimumIsDescribedWithoutRenormalisingAndroidIndexPercentages(){VolumeCapability.Range r=VolumeCapability.range(2,15);assertNotNull(r);assertEquals(r,VolumeCapability.parse(VolumeCapability.advertise(version,r)));assertTrue(r.describe().contains("13 increments; 6.67%"));}
    @Test public void descriptorComesOnlyFromThatPlayerDeviceInfo()throws Exception{JSONObject a=new JSONObject().put("player_id","local").put("device_info",new JSONObject().put("software_version",VolumeCapability.advertise(version,VolumeCapability.range(0,30)))),b=new JSONObject().put("player_id","remote").put("device_info",new JSONObject().put("software_version",VolumeCapability.advertise(version,VolumeCapability.range(0,15))));assertEquals(30,VolumeCapability.fromPlayer(a).max);assertEquals(15,VolumeCapability.fromPlayer(b).max);assertNull(VolumeCapability.fromPlayer(new JSONObject().put("software_version",VolumeCapability.advertise(version,VolumeCapability.range(0,15)))));}
    @Test public void malformedDeviceInfoIsUnknownRatherThanAnException()throws Exception{assertNull(VolumeCapability.fromPlayer(null));assertNull(VolumeCapability.fromPlayer(new JSONObject().put("device_info","invalid")));assertNull(VolumeCapability.fromPlayer(new JSONObject().put("device_info",new JSONObject().put("software_version",15))));}
    @Test public void advertisingRejectsUnrecognisedVersionText(){try{VolumeCapability.advertise("private payload",VolumeCapability.range(0,15));fail();}catch(IllegalArgumentException expected){}}
    @Test public void reportedRangesPredictTheScreenshotsWithoutAFreeFormTolerance(){VolumeCapability.Range r=VolumeCapability.range(0,15);assertEquals(40,r.quantizedPercent(43));assertEquals(53,r.quantizedPercent(56));assertEquals(27,r.quantizedPercent(24));assertEquals(0,r.quantizedPercent(0));assertEquals(100,r.quantizedPercent(100));}
    @Test public void quantizationWorksAcrossRuntimeStepCountsAndClampsEndpoints(){for(int max:new int[]{15,30,32,100,150}){VolumeCapability.Range r=VolumeCapability.range(0,max);assertEquals(0,r.quantizedPercent(-1));assertEquals(100,r.quantizedPercent(101));for(int requested=0;requested<=100;requested++)assertTrue(Math.abs(r.quantizedPercent(requested)-requested)<=50d/max+0.50001);}}
}
