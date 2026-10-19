package net.markandrew.kiosk.music;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class EmptyPlaybackTest {
    @Test public void emptyRequiresExplicitIdentityAvailabilityAndBothStoppedStates()throws Exception{
        JSONObject p=new JSONObject().put("available",true).put("playback_state","idle").put("active_source","queue"),q=new JSONObject().put("queue_id","queue").put("available",true).put("items",0).put("state","idle");
        assertTrue(EmptyPlayback.confirmed(p,q,"queue"));assertFalse(EmptyPlayback.confirmed(p,q,"other"));
        for(String state:new String[]{"playing","paused","","unknown"}){p.put("playback_state",state);assertFalse(EmptyPlayback.confirmed(p,q,"queue"));}p.put("playback_state","idle");
        for(String state:new String[]{"playing","paused","","unknown"}){q.put("state",state);assertFalse(EmptyPlayback.confirmed(p,q,"queue"));}q.put("state","idle");
        q.remove("items");assertFalse(EmptyPlayback.confirmed(p,q,"queue"));q.put("items",1);assertFalse(EmptyPlayback.confirmed(p,q,"queue"));q.put("items",0);
        p.put("available",false);assertFalse(EmptyPlayback.confirmed(p,q,"queue"));p.put("available",true);q.remove("available");assertFalse(EmptyPlayback.confirmed(p,q,"queue"));q.put("available",true);
        p.put("active_source","hdmi");assertFalse(EmptyPlayback.confirmed(p,q,"queue"));p.put("active_source","").put("playback_state","stopped");q.put("state","stopped");assertTrue(EmptyPlayback.confirmed(p,q,"queue"));
    }
}
