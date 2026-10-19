package net.markandrew.kiosk.music;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import me.jxl.kiosk.plugins.KsTheme;

/** Themed modal/drawer above Now Playing, leaving the playback surface underneath. */
final class MusicPanelSurface extends FrameLayout {
    private final TouchPanel panel;
    private final KsTheme theme;
    private final boolean centered;
    MusicPanelSurface(Context context, KsTheme theme, TouchPanel panel, boolean centered, Runnable dismiss) {
        super(context); this.panel = panel; this.theme = theme; this.centered = centered;
        setBackgroundColor(0x66000000); setOnClickListener(v -> dismiss.run());
        android.graphics.drawable.GradientDrawable background=NativeStyle.background();background.setCornerRadius(theme.px(20));
        background.setStroke(theme.px(1),NativeStyle.color("outlineVariant"));panel.setBackground(background);panel.setClipToOutline(true);
        panel.setOnClickListener(v -> {});
        addView(panel, new FrameLayout.LayoutParams(1, 1, centered ? Gravity.CENTER : Gravity.END | Gravity.CENTER_VERTICAL));
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        float density = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)panel.getLayoutParams();
        lp.width = theme.px(centered&&panel.expandedEq()?MusicPanelLayout.eqWidth(Math.round(w/density)):MusicPanelLayout.width(Math.round(w / density), centered));
        lp.height = theme.px(MusicPanelLayout.height(Math.round(h / density), centered));
        lp.setMargins(theme.px(8), theme.px(8), theme.px(8), theme.px(8));
        super.onMeasure(widthSpec, heightSpec);
    }
}
