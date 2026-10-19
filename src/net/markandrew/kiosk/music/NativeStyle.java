package net.markandrew.kiosk.music;

import android.view.Gravity;
import android.widget.Button;
import me.jxl.kiosk.plugins.KsTheme;

/** Echo overview visual tokens, scoped to plugin-owned views. Host supplies fonts/icons. */
final class NativeStyle {
    static final int EDGE = 12, GAP = 8, SMALL = 4, TARGET = 48;
    private NativeStyle() {}
    static int color(String role) {
        switch (role) {
            case "primary": return 0xff488284;
            case "favorite": return 0xffef5350;
            case "muted": return 0xffd32f2f;
            case "onPrimary": case "onPrimaryContainer": return 0xffffffff;
            case "primaryContainer": return 0xff488284;
            case "onSurface": return 0xffe8eaed;
            case "onSurfaceVariant": return 0xffb6babf;
            case "outline": case "outlineVariant": return 0xff3c3e43;
            case "surfaceContainerHigh": return 0xff2a2b2f;
            case "surfaceContainerHighest": return 0xff303136;
            case "error": return 0xffffb4ab;
            default: return 0xff202124;
        }
    }
    static void text(android.widget.TextView view,KsTheme theme,float size,int weight,String role) {
        theme.styleText(view,size,weight,role); view.setTextColor(color(role));
    }
    static android.graphics.drawable.GradientDrawable background() {
        return new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff333539,0xff121315});
    }
    static android.graphics.drawable.GradientDrawable card(KsTheme theme,boolean active) {
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(color(active?"primary":"surfaceContainerHigh"));bg.setCornerRadius(theme.px(14));
        bg.setStroke(Math.max(1,theme.px(1)),color("outlineVariant"));return bg;
    }
    static void slider(android.widget.SeekBar bar,KsTheme theme) {
        theme.styleSlider(bar);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color("primary")));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(color("primary")));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(color("outlineVariant")));
    }
    /** Keep the full volume thumb inside its own view at both ends. */
    static void volumeSlider(android.widget.SeekBar bar,KsTheme theme){
        slider(bar,theme);
        int inset=Math.max(theme.px(14),bar.getThumbOffset()+theme.px(2));
        if(bar.getThumb()!=null)inset=Math.max(inset,bar.getThumb().getIntrinsicWidth()-bar.getThumbOffset()+theme.px(2));
        bar.setPadding(Math.max(bar.getPaddingLeft(),inset),bar.getPaddingTop(),Math.max(bar.getPaddingRight(),inset),bar.getPaddingBottom());
    }
    static android.graphics.drawable.Drawable icon(KsTheme theme,String name,String role) {
        android.graphics.drawable.Drawable icon=theme.icon(name,role.equals("favorite")?"primary":role.equals("error")||role.equals("muted")?"onSurface":role);
        if(icon!=null){icon=icon.mutate();icon.setTint(color(role));}return icon;
    }
    static void iconButton(Button button,KsTheme theme,String name,String label,boolean active) {
        button(button,theme,active);button.setPadding(0,0,0,0);
        button.setContentDescription(label);button.setTooltipText(label);
        android.graphics.drawable.Drawable glyph=icon(theme,"mdi:"+name,active?"onPrimary":"onSurfaceVariant");
        if(glyph==null){button.setText(label);return;}
        android.graphics.drawable.LayerDrawable layer=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{glyph});
        layer.setLayerSize(0,theme.px(22),theme.px(22));layer.setLayerGravity(0,Gravity.CENTER);
        button.setText("");button.setForeground(layer);
    }
    static void button(Button button, KsTheme theme, boolean filled) {
        android.graphics.drawable.RippleDrawable bg=new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33ffffff),card(theme,filled),null);
        button.setBackgroundTintList(null);button.setBackground(bg);
        button.setMinHeight(0); button.setMinimumHeight(0);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(theme.px(12), 0, theme.px(12), 0);
        button.setIncludeFontPadding(false); button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        text(button, theme, 14, 600, filled ? "onPrimary" : "onSurfaceVariant");
    }
    static void input(android.widget.EditText input, KsTheme theme) {
        text(input, theme, 15, 400, "onSurface"); input.setHintTextColor(color("onSurfaceVariant"));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color("surfaceContainerHigh")); background.setCornerRadius(theme.px(14));
        background.setStroke(Math.max(1,theme.px(1)),color("outlineVariant"));input.setBackgroundTintList(null);
        input.setBackground(background); input.setPadding(theme.px(12), theme.px(4), theme.px(12), theme.px(4));
        input.setIncludeFontPadding(false); input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }
}
