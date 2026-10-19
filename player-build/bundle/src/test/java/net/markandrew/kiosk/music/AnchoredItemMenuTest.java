package net.markandrew.kiosk.music;

import android.app.Activity;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KsTheme;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30,qualifiers="w960dp-h480dp-land-mdpi")
public class AnchoredItemMenuTest {
    private Activity activity;private FrameLayout root;private Button anchor;private KsTheme theme;
    @Before public void setup(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();root=new FrameLayout(activity);activity.setContentView(root);
        Map<String,Integer> colors=new HashMap<>();for(String role:new String[]{"surfaceContainerHigh","onSurface","primary","onPrimary","onSurfaceVariant"})colors.put(role,0xff556677);
        theme=new KsTheme(true,1,colors,w->null,n->new android.graphics.Path());anchor=new Button(activity);root.addView(anchor,new FrameLayout.LayoutParams(48,48));
    }
    @Test public void anchoredMenuStaysInWindowAndFlipsAboveBottomButton(){
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,960,480);anchor.layout(900,400,948,448);
        List<FeatureController.ItemAction> actions=new ArrayList<>();for(int i=0;i<8;i++)actions.add(new FeatureController.ItemAction("Action "+i,()->{}));
        AnchoredItemMenu menu=new AnchoredItemMenu(anchor,theme,actions,()->true,null);menu.show();assertTrue(menu.popup.isShowing());Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);
        int[] xy=new int[2];anchor.getLocationOnScreen(xy);assertTrue(menu.top+menu.height<=xy[1]);assertTrue(menu.left>=frame.left&&menu.left+menu.width<=frame.right);assertTrue(menu.top>=frame.top&&menu.top+menu.height<=frame.bottom);menu.dismiss();
    }
    @Test public void staleMenuCannotSubmitAndAnchorDetachDismisses(){
        AtomicBoolean current=new AtomicBoolean(true);List<String> calls=new ArrayList<>();AnchoredItemMenu menu=new AnchoredItemMenu(anchor,theme,Arrays.asList(new FeatureController.ItemAction("Play",()->calls.add("play"))),current::get,null);
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,960,480);menu.show();assertTrue(menu.popup.isShowing());current.set(false);menu.rows.getChildAt(0).performClick();assertTrue(calls.isEmpty());assertFalse(menu.popup.isShowing());
        current.set(true);menu.show();root.removeView(anchor);assertFalse(menu.popup.isShowing());
    }
    @Test public void oneShotChoiceNeverChangesCheckedReplacementDefault(){
        List<String> calls=new ArrayList<>();List<FeatureController.ItemAction> choices=Arrays.asList(new FeatureController.ItemAction("Play on…",()->{}),new FeatureController.ItemAction("Play now · replace queue",()->calls.add("replace")),new FeatureController.ItemAction("Play next · keep queue",()->calls.add("next")));
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,960,480);
        AnchoredItemMenu menu=new AnchoredItemMenu(anchor,theme,choices,()->true,"Kitchen");menu.show();assertTrue(((Button)menu.rows.getChildAt(0)).getText().toString().contains("Kitchen"));menu.rows.getChildAt(2).performClick();assertEquals(Collections.singletonList("next"),calls);
        menu=new AnchoredItemMenu(anchor,theme,choices,()->true,"Kitchen");assertTrue(((Button)menu.rows.getChildAt(1)).getText().toString().contains("✓"));assertFalse(((Button)menu.rows.getChildAt(2)).getText().toString().contains("✓"));menu.dismiss();
    }
    @Test public void shortMenusShrinkAndLongLabelsWrapWithoutShrinkingTouchTargets(){
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,960,480);
        AnchoredItemMenu shortMenu=new AnchoredItemMenu(anchor,theme,Arrays.asList(new FeatureController.ItemAction("Next",()->{})),()->true,null);shortMenu.show();int shortWidth=shortMenu.width;assertTrue(shortWidth<320);assertEquals(shortMenu.rows.getMeasuredHeight(),shortMenu.height);assertTrue(shortMenu.height>=48);shortMenu.dismiss();
        AnchoredItemMenu longMenu=new AnchoredItemMenu(anchor,theme,Arrays.asList(new FeatureController.ItemAction("A much longer library management action with text that must wrap safely onto multiple lines without clipping.\nThis extra sentence ensures there is enough content to require a taller touch target at the maximum menu width.\nAdditional action details.",()->{})),()->true,null);longMenu.show();assertTrue("Widths "+longMenu.width+" / "+shortWidth,longMenu.width>shortWidth);assertTrue(longMenu.width<=320);assertTrue("Height "+longMenu.rows.getMeasuredHeight(),longMenu.rows.getMeasuredHeight()>48);longMenu.dismiss();
    }
}
