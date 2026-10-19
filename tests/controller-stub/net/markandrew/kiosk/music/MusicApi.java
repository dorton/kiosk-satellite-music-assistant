package net.markandrew.kiosk.music;
import java.util.function.BooleanSupplier;
final class MusicApi {
    static PlayerPolicy.Snapshot setupSnapshot;
    final PlayerPolicy.Snapshot snapshot = setupSnapshot == null ? new PlayerPolicy.Snapshot() : setupSnapshot;
    void cancel() {}
    void ownReceiver(java.util.function.Function<IngressAccess.Session, String> identity) {}
    int plays, transfers, fullReads, targetedReads;
    String target;
    boolean failPlay;
    void identity(IngressAccess.Session session) {}
    String accountName(IngressAccess.Session session) { return "Test user"; }
    PlayerPolicy.Snapshot snapshot(IngressAccess.Session session) { fullReads++; return snapshot; }
    PlayerPolicy.Snapshot connectionSnapshot(IngressAccess.Session session,java.util.function.Consumer<String> stage){stage.accept("Player access");return snapshot(session);}
    PlayerPolicy.Snapshot playbackSnapshot(IngressAccess.Session session, String player) {
        targetedReads++;
        if (player == null || player.isEmpty()) throw new IllegalArgumentException("Choose a default player in Setup.");
        return snapshot;
    }
    void play(IngressAccess.Session session, PlayerPolicy.Snapshot fresh, String player, String queue, boolean grouped, String uri, String option, BooleanSupplier current) {
        if (failPlay) throw new IllegalArgumentException("Playback was not confirmed.");
        if (current.getAsBoolean()) { plays++; target = player; }
    }
    void play(IngressAccess.Session session, PlayerPolicy.Snapshot fresh, String player, String queue, boolean grouped, String uri, String option, boolean shuffle, String start, BooleanSupplier current) {
        play(session, fresh, player, queue, grouped, uri, option, current);
    }
    void transfer(IngressAccess.Session session, String source, String target, String queue, BooleanSupplier current) { if (current.getAsBoolean()) transfers++; }
}
