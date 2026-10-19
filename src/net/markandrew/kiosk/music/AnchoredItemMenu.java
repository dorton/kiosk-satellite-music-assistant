package net.markandrew.kiosk.music;

import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.KsTheme;

/** Themed item menu bounded to the visible window and tied to its initiating view. */
final class AnchoredItemMenu {
    private final View anchor;
    private final BooleanSupplier current;
    final PopupWindow popup;
    final LinearLayout rows;
    int left,top,width,height;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener(){
        public void onViewAttachedToWindow(View v){}
        public void onViewDetachedFromWindow(View v){dismiss();}
    };
    AnchoredItemMenu(View anchor,KsTheme theme,List<FeatureController.ItemAction> actions,BooleanSupplier current,String player){
        this.anchor=anchor;this.current=current;
        ScrollView scroll=new ScrollView(anchor.getContext());scroll.setFillViewport(false);
        rows=new LinearLayout(anchor.getContext());rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
        for(FeatureController.ItemAction action:actions){
            Button button=new Button(anchor.getContext());NativeStyle.button(button,theme,false);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            String label=action.label;
            if(player!=null&&label.equals("Play on…"))label="Play on…"+(player.isEmpty()?"":"\n"+player)+"   ›";
            if(player!=null&&label.equals("Play now · replace queue"))label+="   ✓";
            button.setText(label);button.setContentDescription(action.label);button.setTextSize(13);button.setSingleLine(false);button.setMaxLines(Integer.MAX_VALUE);button.setEllipsize(null);button.setMinHeight(theme.px(48));button.setMinimumHeight(theme.px(48));button.setMinWidth(theme.px(48));button.setMinimumWidth(theme.px(48));
            button.setEnabled(action.action!=null);
            rows.addView(button,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
            button.setOnClickListener(v->{boolean valid=action.action!=null&&anchor.isAttachedToWindow()&&current.getAsBoolean();dismiss();if(valid)action.action.run();});
        }
        popup=new PopupWindow(scroll,1,1,true);popup.setBackgroundDrawable(NativeStyle.card(theme,false));popup.setElevation(theme.px(12));popup.setOutsideTouchable(true);popup.setClippingEnabled(true);
        popup.setOnDismissListener(()->anchor.removeOnAttachStateChangeListener(attachment));
        width=theme.px(320);
    }
    void show(){
        if(!anchor.isAttachedToWindow()||!current.getAsBoolean())return;
        Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);
        if(frame.width()<=0||frame.height()<=0)return;
        int[] at=new int[2];anchor.getLocationOnScreen(at);
        int maximum=Math.min(width,frame.width()),desired=0;
        for(int i=0;i<rows.getChildCount();i++){View row=rows.getChildAt(i);row.measure(View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));desired=Math.max(desired,row.getMeasuredWidth());}
        width=Math.min(maximum,desired);
        rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int below=Math.max(0,frame.bottom-at[1]-anchor.getHeight()),above=Math.max(0,at[1]-frame.top);
        boolean down=rows.getMeasuredHeight()<=below||below>=above;
        height=Math.min(rows.getMeasuredHeight(),down?below:above);if(height<=0)return;
        left=Math.max(frame.left,Math.min(at[0]+anchor.getWidth()-width,frame.right-width));
        top=down?at[1]+anchor.getHeight():at[1]-height;top=Math.max(frame.top,Math.min(top,frame.bottom-height));
        popup.setWidth(width);popup.setHeight(height);anchor.addOnAttachStateChangeListener(attachment);
        popup.showAtLocation(anchor,Gravity.TOP|Gravity.LEFT,left,top);
    }
    void dismiss(){popup.dismiss();anchor.removeOnAttachStateChangeListener(attachment);}
}
