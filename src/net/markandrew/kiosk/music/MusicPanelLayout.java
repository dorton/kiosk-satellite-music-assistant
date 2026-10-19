package net.markandrew.kiosk.music;

/** Responsive dimensions in dp, independent of host screen density. */
final class MusicPanelLayout {
    static int width(int available, boolean centered) {
        return Math.max(0, Math.min(Math.max(0, available - (centered ? 24 : 16)), centered ? 560 : Math.max(260, Math.min(400, available / 2))));
    }
    static int height(int available, boolean centered) { return Math.max(0, available - (centered ? 24 : 16)); }
    static int eqWidth(int available){return Math.max(0,Math.min(900,available-24));}
}
