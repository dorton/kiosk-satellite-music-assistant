package net.markandrew.kiosk.music;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class RadioMetadataTest {
    private JSONObject media()throws Exception{return new JSONObject().put("uri","provider://radio/station").put("media_type","radio").put("name","Station");}
    private JSONObject queue()throws Exception{return new JSONObject().put("queue_id","speaker").put("current_item",new JSONObject().put("queue_item_id","item1").put("media_item",media()));}
    private JSONObject player(String title,String artist)throws Exception{return new JSONObject().put("current_media",new JSONObject().put("source_id","speaker").put("queue_item_id","item1").put("title",title).put("artist",artist).put("image_url","https://art.example/cover.png"));}
    @Test public void liveRadioFieldsOverrideStationWithoutChangingStationIdentity()throws Exception{
        NowPlayingData data=new NowPlayingData(player("Live track","Live artist"),queue());
        assertEquals("Live track",data.title);assertEquals("Live artist",data.artist);assertEquals("https://art.example/cover.png",data.image);
        assertEquals("provider://radio/station",data.mediaUri);assertTrue(data.live);assertFalse(data.favoriteControl);assertEquals("item1",data.itemId);
    }
    @Test public void radioUpdatesAreNotCachedToTheStation()throws Exception{
        assertEquals("First",new NowPlayingData(player("First","A"),queue()).title);
        assertEquals("Second",new NowPlayingData(player("Second","B"),queue()).title);
    }
    @Test public void staleOtherSourceOrQueueItemCannotReplaceCurrentStation()throws Exception{
        JSONObject p=player("Wrong","Wrong");p.getJSONObject("current_media").put("source_id","other");assertEquals("Station",new NowPlayingData(p,queue()).title);
        p.getJSONObject("current_media").put("source_id","speaker").put("queue_item_id","old");assertEquals("Station",new NowPlayingData(p,queue()).title);
    }
    @Test public void missingLiveMetadataFallsBackToStation()throws Exception{
        assertEquals("Station",new NowPlayingData(new JSONObject(),queue()).title);
        assertEquals("Station",new NowPlayingData(player("",""),queue()).title);
    }
    @Test public void streamDetailsAreAValidFallback()throws Exception{
        JSONObject q=queue();q.getJSONObject("current_item").put("streamdetails",new JSONObject().put("stream_metadata",new JSONObject().put("title","Stream title").put("artist","Stream artist")));
        assertEquals("Stream title",new NowPlayingData(new JSONObject(),q).title);
    }
    @Test public void ordinaryTracksDoNotUseRadioOverrides()throws Exception{
        JSONObject q=queue();q.getJSONObject("current_item").getJSONObject("media_item").put("media_type","track");assertEquals("Station",new NowPlayingData(player("Wrong","Wrong"),q).title);
    }
    @Test public void proxyArtworkIsRoutedThroughIngressNotItsAdvertisedHost(){
        String id=String.join("",java.util.Collections.nCopies(64,"a"));
        assertEquals("imageproxy/"+id+"?size=256",RadioMetadata.image("http://ma.local:8095/imageproxy/"+id+"?size=512&fmt=jpeg"));
    }
    @Test public void unsafeArtworkUrlsAreRejected(){
        for(String value:new String[]{"http://example/art.jpg","https://user:pass@example/art.jpg","file:///etc/passwd","//example/art.jpg","https://example/art.jpg#fragment","data:image/png;base64,xx"})assertEquals(value,"",RadioMetadata.image(value));
    }
}
