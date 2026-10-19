package net.markandrew.kiosk.music;

/** Pure layout/interaction rules shared with offline tests. All sizes are dp. */
public final class TouchLayout {
    public static final int TARGET = 48;
    private TouchLayout() {}

    public static boolean stackedHeader(int width) { return width < 560; }
    public static int columns(int width, int height) {
        int limit = width > height && height < 600 ? 4 : 6;
        return Math.max(1, Math.min(limit, width / 156));
    }
    public static String tap(String type) {
        if ("album".equals(type) || "playlist".equals(type) || "artist".equals(type) || "podcast".equals(type)) return "open";
        if ("track".equals(type) || "radio".equals(type) || "audiobook".equals(type) || "podcast_episode".equals(type)) return "play";
        return "menu";
    }
}
