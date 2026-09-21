package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.Platform;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Platform} and {@code ServiceRegistry.buildServiceFileMap} are two hand-maintained lists of
 * the same keys, in two layers that may not import each other: the enum says a key can be rendered,
 * the map says where its output lives. Nothing connects them
 * (<a href="https://github.com/PIsberg/vibetags/issues/762">issue #762</a>), so a key added to one
 * and not the other compiles and ships.
 *
 * <p>Each direction fails differently, and neither fails loudly. A {@code Platform} with no path is
 * rendered and then dropped at the write step with a {@code write.skip reason=no-service-path} log
 * line nobody reads. A service key with no {@code Platform} is an opt-in the user can create and
 * nothing will ever fill.
 *
 * <p>The exemption is by name, with the reason, so a second one is a decision and not drift.
 */
class PlatformServiceMapParityTest {

    /**
     * Service keys that deliberately have no {@link Platform}: {@code root_index} is a marker whose
     * presence flips the reactor-root merge, and it has no renderer at all (#788).
     */
    private static final Set<String> KEYS_WITH_NO_PLATFORM = Set.of("root_index");

    @Test
    void everyPlatformHasAPath_andEveryPathHasAPlatform() {
        Set<String> platformKeys = new TreeSet<>();
        for (Platform p : Platform.values()) {
            platformKeys.add(p.getServiceKey());
        }
        Set<String> serviceKeys = new TreeSet<>(ServiceRegistry.buildServiceFileMap(Path.of(".")).keySet());
        serviceKeys.removeAll(KEYS_WITH_NO_PLATFORM);

        assertEquals(platformKeys, serviceKeys,
            "Platform and buildServiceFileMap disagree. A Platform with no path renders content that "
                + "is dropped at the write step; a path with no Platform is an opt-in nothing fills. "
                + "Add the missing half, or record the key in KEYS_WITH_NO_PLATFORM with its reason.");
    }

    /** A stale exemption would let a real gap on that key pass, so each must still be needed. */
    @Test
    void everyExemptionIsStillNeeded() {
        Set<String> serviceKeys = ServiceRegistry.buildServiceFileMap(Path.of(".")).keySet();
        for (String key : KEYS_WITH_NO_PLATFORM) {
            assertTrue(serviceKeys.contains(key) && Platform.fromServiceKey(key) == null,
                key + " is exempted but is no longer a service key without a Platform; delete the entry");
        }
    }
}
