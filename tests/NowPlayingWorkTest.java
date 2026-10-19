package net.markandrew.kiosk.music;

public final class NowPlayingWorkTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("NowPlayingWork " + checks); }
    public static void main(String[] args) {
        NowPlayingWork work = new NowPlayingWork();
        check(work.beginRead()); check(!work.beginRead());
        check(work.beginWrite()); // Regression: an in-flight read must not eat an explicit tap.
        check(work.writing()); check(!work.beginWrite()); check(!work.beginRead());
        work.endRead(); check(work.writing()); check(!work.beginRead());
        work.endWrite(); check(!work.writing()); check(work.beginRead());
        work.endRead(); check(work.beginWrite()); check(!work.beginRead());
        work.endWrite(); check(work.beginRead()); work.endRead();
        check(work.beginWrite()); work.endWrite(); check(work.beginWrite());
        System.out.println("NowPlayingWork: " + checks + " tap-during-refresh, duplicate and resume checks passed");
    }
}
