package net.markandrew.kiosk.music;

/** Main-thread admission: reads never consume an explicit write; one write at a time. */
final class NowPlayingWork {
    private boolean reading, writing;
    boolean beginRead() {
        if (reading || writing) return false;
        reading = true; return true;
    }
    boolean beginWrite() {
        if (writing) return false;
        writing = true; return true;
    }
    boolean writing() { return writing; }
    void endRead() { reading = false; }
    void endWrite() { writing = false; }
}
