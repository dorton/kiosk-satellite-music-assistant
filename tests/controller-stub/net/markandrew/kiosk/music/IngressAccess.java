package net.markandrew.kiosk.music;
import java.util.function.*;
final class IngressAccess {
    static final class Session {}
    void clear() {}
    void ensure(String addon, BooleanSupplier current, Consumer<Session> done, Consumer<String> fail) { if (current.getAsBoolean()) done.accept(new Session()); }
}
