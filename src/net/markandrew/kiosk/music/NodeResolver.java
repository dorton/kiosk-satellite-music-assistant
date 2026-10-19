package net.markandrew.kiosk.music;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional local-network identity check; never infers identity from display names. */
interface NodeResolver {
    void resolve(String ip, List<DiscoveryPolicy.Choice> nodes, BooleanSupplier current,
                 Consumer<String> done, Consumer<String> fail);
    void cancel();
}
