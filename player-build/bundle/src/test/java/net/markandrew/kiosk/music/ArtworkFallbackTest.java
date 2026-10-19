package net.markandrew.kiosk.music;

import android.app.Activity;
import android.graphics.Bitmap;
import android.widget.ImageView;
import java.util.*;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import me.jxl.kiosk.plugins.KsTheme;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class ArtworkFallbackTest {
    private NowPlayingView view;private ImageView art;
    @Before public void setup()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();Map<String,Integer> colors=new HashMap<>();
        for(String key:new String[]{"surface","surfaceContainerHigh","surfaceContainerHighest","onSurface","onSurfaceVariant","primary","onPrimary","primaryContainer","onPrimaryContainer","outlineVariant"})colors.put(key,0xff556677);
        KsTheme theme=new KsTheme(true,1,colors,w->null,n->null);
        view=new NowPlayingView(activity,theme,new NowPlayingView.Actions(){public void close(){}public void control(String c,int v){}public void navigate(String d){}});
        Field f=NowPlayingView.class.getDeclaredField("artwork");f.setAccessible(true);art=(ImageView)f.get(view);
    }
    @Test public void initialArtworkIsNotBlankEvenIfHostHasNoMusicGlyph(){assertNotNull(art.getDrawable());}
    @Test public void missingNextCoverReplacesPreviousBitmapWithDefaultIcon(){
        Bitmap cover=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);view.artwork(cover);assertTrue(art.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
        view.artwork(null);assertNotNull(art.getDrawable());assertFalse(art.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
    }
    @Test public void failedLoadLeavesPlaceholderAndLaterCoverCanReplaceIt(){
        view.artwork(null);assertNotNull(art.getDrawable());
        view.artwork(Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888));assertTrue(art.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
    }
    @Test public void radioLyricsMessageDoesNotClaimThereIsNoMusic()throws Exception{
        String message=FeatureController.lyricsUnavailable(new JSONObject().put("media_type","radio"));assertEquals("Lyrics are not available for radio playback.",message);
        assertTrue(FeatureController.lyricsUnavailable(null).contains("No music track"));
    }
}
