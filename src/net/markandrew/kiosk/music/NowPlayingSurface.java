package net.markandrew.kiosk.music;

import android.content.Context;
import android.widget.FrameLayout;
import me.jxl.kiosk.plugins.KsTheme;

/** One native host surface: playback first, at most one attached panel above it. */
final class NowPlayingSurface extends FrameLayout {
    private final KsTheme theme;
    private MusicPanelSurface panel;
    private Runnable interaction=()->{};
    void interaction(Runnable handler){interaction=handler;}
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN||event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE)interaction.run();
        return super.dispatchTouchEvent(event);
    }
    NowPlayingSurface(Context context, KsTheme theme, NowPlayingView playback) {
        super(context); this.theme = theme;
        addView(playback, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    TouchPanel panel(String title, boolean centered, java.util.function.Consumer<TouchPanel> populate, Runnable dismiss) {
        clearPanel();
        TouchPanel content = new TouchPanel(getContext(), theme, title);
        content.compact(dismiss);
        populate.accept(content);
        panel = new MusicPanelSurface(getContext(), theme, content, centered, dismiss);
        addView(panel, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        panel.bringToFront();
        return content;
    }
    void clearPanel() {
        if (panel != null) { removeView(panel); panel = null; }
    }
    boolean hasPanel(){return panel!=null;}
}
