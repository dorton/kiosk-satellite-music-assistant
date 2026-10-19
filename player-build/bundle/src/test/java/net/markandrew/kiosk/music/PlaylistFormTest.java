package net.markandrew.kiosk.music;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.lang.reflect.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import me.jxl.kiosk.plugins.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PlaylistFormTest {
    @Test public void emptyNameKeepsTheUsableFormAndDraft()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        Map<String,Integer> colors=new HashMap<>();for(String k:new String[]{"surface","surfaceContainerHigh","onSurface","onSurfaceVariant","primary","onPrimary","outlineVariant","error"})colors.put(k,0xff556677);
        KsTheme theme=new KsTheme(true,1,colors,w->null,n->new android.graphics.Path());
        TouchPanel[] shown={null};AtomicBoolean dismissed=new AtomicBoolean();ExecutorService worker=Executors.newSingleThreadExecutor();
        FeatureController.Ui ui=(FeatureController.Ui)Proxy.newProxyInstance(FeatureController.Ui.class.getClassLoader(),new Class<?>[]{FeatureController.Ui.class},(proxy,method,args)->{
            if(method.getName().equals("panel")||method.getName().equals("popup")){TouchPanel p=new TouchPanel(activity,theme,(String)args[0]);p.compact(()->{});((Consumer<TouchPanel>)args[2]).accept(p);shown[0]=p;activity.setContentView(p);}
            if(method.getName().equals("dismiss"))dismissed.set(true);return null;
        });
        FeatureController controller=new FeatureController(null,null,worker,new AtomicBoolean(),()->"server",()->"speaker",()->true,ui);
        try{
            Method form=FeatureController.class.getDeclaredMethod("playlistForm",JSONArray.class,JSONArray.class,String.class,String.class,String.class);form.setAccessible(true);
            form.invoke(controller,new JSONArray(),new JSONArray(),"",""," ");TouchPanel initial=shown[0];EditText name=field(initial);assertNotNull(name);
            name.setText("   ");find(initial,"Create playlist").performClick();assertSame(initial,shown[0]);assertEquals("Enter a playlist name.",name.getError().toString());assertNotNull(find(initial,"Close"));
            name.setText("My playlist");assertEquals("My playlist",field(shown[0]).getText().toString());
        }finally{controller.close();worker.shutdownNow();}
    }
    private View find(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    private EditText field(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=field(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
}
