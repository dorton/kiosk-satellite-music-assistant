package net.markandrew.kiosk.music;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Test-only HTTP stand-in; never in the plugin source set or DEX. */
final class ServiceCatalogue {
    static final class ReadFailure extends Exception { ReadFailure(String message) { super(message); } }
    static int reads;
    List<DiscoveryPolicy.Choice> read(URI origin, String path, BooleanSupplier current) throws Exception {
        DiscoveryPolicy.signedServices(origin, path); reads++;
        return Collections.singletonList(new DiscoveryPolicy.Choice("test_kiosk", "Test kiosk"));
    }
    void cancel() {}
}
