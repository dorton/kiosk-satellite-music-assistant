package net.markandrew.kiosk.music;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.function.*;
import me.jxl.kiosk.plugins.KsTheme;

/** Native vertical controls with stable touch targets and keyboard/accessibility adjustment. */
final class GraphicEqView extends ViewGroup {
    private final KsTheme theme;
    private int columns;
    GraphicEqView(Context context,KsTheme theme,String[] labels,double[] values,BiConsumer<Integer,Double> change,BooleanSupplier current){
        super(context);this.theme=theme;
        for(int i=0;i<labels.length;i++){
            final int index=i;Slider slider=new Slider(context,theme,labels[i],values[i],value->change.accept(index,value),current);
            addView(slider,new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT));
        }
    }
    @Override protected void onMeasure(int w,int h){
        int width=MeasureSpec.getSize(w),height=MeasureSpec.getSize(h);
        if(MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED)height=theme.px(248);
        columns=Math.max(1,Math.min(getChildCount(),width/Math.max(1,theme.px(48))));
        int rows=(getChildCount()+columns-1)/columns;
        int cell=Math.min(theme.px(72),width/columns),tall=Math.max(0,height/Math.max(1,rows));
        for(int i=0;i<getChildCount();i++)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(tall,MeasureSpec.EXACTLY));
        setMeasuredDimension(width,height);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);int row=i/columns,col=i%columns,count=Math.min(columns,getChildCount()-row*columns);
            int x=(getWidth()-count*child.getMeasuredWidth())/2+col*child.getMeasuredWidth(),y=row*child.getMeasuredHeight();
            child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());
        }
    }
    static final class Scale extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final KsTheme theme;
        Scale(Context c,KsTheme t){super(c);theme=t;}
        @Override protected void onDraw(Canvas canvas){paint.setColor(NativeStyle.color("onSurfaceVariant"));paint.setTextSize(theme.px(11));paint.setTextAlign(Paint.Align.CENTER);for(int i=0;i<3;i++)canvas.drawText(new String[]{"+12 dB","0 dB","−12 dB"}[i],getWidth()/2f,theme.px(29+i*96),paint);}
    }
    static final class Slider extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final KsTheme theme;final String label;
        final DoubleConsumer change;final BooleanSupplier current;double value;float startX,startY;boolean vertical;
        Slider(Context context,KsTheme theme,String label,double value,DoubleConsumer change,BooleanSupplier current){
            super(context);this.theme=theme;this.label=label;this.value=value;this.change=change;this.current=current;
            setFocusable(true);setClickable(true);describe();
        }
        private String display(){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
        private void describe(){setContentDescription(label+" · "+display()+" dB");}
        private float top(){return Math.min(theme.px(24),getHeight()/3f);}private float bottom(){return Math.max(top()+1,getHeight()-theme.px(32));}
        @Override protected void onDraw(Canvas c){
            float x=getWidth()/2f,zero=(top()+bottom())/2;
            paint.setStrokeWidth(theme.px(1));paint.setColor(NativeStyle.color("outlineVariant"));
            for(int i=0;i<=8;i++){float y=top()+(bottom()-top())*i/8;c.drawLine(theme.px(2),y,getWidth()-theme.px(2),y,paint);}
            paint.setColor(0xff111214);paint.setStrokeWidth(theme.px(4));c.drawLine(x,top(),x,bottom(),paint);
            float y=zero-(float)Math.max(-12,Math.min(12,value))*(bottom()-top())/24;
            paint.setColor(NativeStyle.color("primary"));paint.setStrokeWidth(theme.px(3));c.drawLine(x,zero,x,y,paint);
            paint.setColor(isFocused()||isPressed()?NativeStyle.color("primary"):NativeStyle.color("onSurface"));c.drawCircle(x,y,theme.px(12),paint);
            paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(theme.px(12));c.drawText(display(),x,theme.px(13),paint);
            paint.setColor(NativeStyle.color("onSurfaceVariant"));c.drawText(label,x,getHeight()-theme.px(8),paint);
        }
        private void set(double gain){if(!current.getAsBoolean()||!Double.isFinite(gain))return;value=Math.round(Math.max(-12,Math.min(12,gain)));describe();invalidate();change.accept(value);}
        private void step(int direction){set(direction>0?Math.floor(value)+1:Math.ceil(value)-1);}
        private void at(float y){set(12-24*(y-top())/(bottom()-top()));}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(!current.getAsBoolean())return false;
            switch(e.getActionMasked()){
                // Requesting focus here can scroll a tall fader into view mid-drag,
                // moving its coordinate origin underneath the user's finger.
                case MotionEvent.ACTION_DOWN:startX=e.getX();startY=e.getY();vertical=false;setPressed(true);return true;
                case MotionEvent.ACTION_MOVE:
                    if(Math.abs(e.getY()-startY)>theme.px(5)&&Math.abs(e.getY()-startY)>Math.abs(e.getX()-startX))vertical=true;
                    if(vertical){getParent().requestDisallowInterceptTouchEvent(true);at(e.getY());}return true;
                case MotionEvent.ACTION_UP:if(vertical||Math.abs(e.getX()-startX)<theme.px(6)){at(e.getY());performClick();}setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);return true;
                case MotionEvent.ACTION_CANCEL:setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);return true;
                default:return super.onTouchEvent(e);
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onKeyDown(int key,android.view.KeyEvent event){
            if(key==android.view.KeyEvent.KEYCODE_DPAD_UP||key==android.view.KeyEvent.KEYCODE_DPAD_DOWN){step(key==android.view.KeyEvent.KEYCODE_DPAD_UP?1:-1);return true;}return super.onKeyDown(key,event);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,-12,12,(float)Math.max(-12,Math.min(12,value))));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args){
            if(!current.getAsBoolean())return false;
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){step(1);return true;}if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){step(-1);return true;}
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null){float gain=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);if(Float.isFinite(gain)){set(gain);return true;}}
            return super.performAccessibilityAction(action,args);
        }
    }
}
