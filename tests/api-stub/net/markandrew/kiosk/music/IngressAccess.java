package net.markandrew.kiosk.music;
import java.net.URI;
final class IngressAccess {
    static final class Session {
        final URI base;
        final String header = "Cookie", credential = "test-only";
        Session(URI base) { this.base = base; }
    }
}
