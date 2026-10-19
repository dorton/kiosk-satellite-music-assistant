package net.markandrew.kiosk.music;

import me.jxl.kiosk.plugins.OverlaySpec;

/** Full coverage; the documented host menu layer sits above plugin overlays. */
final class MusicOverlay {
    static OverlaySpec screen(){return OverlaySpec.fullScreen().closeOnBack(true);}
}
