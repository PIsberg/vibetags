package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code OPT_IN_KEYS} and {@code buildServiceFileMap} are two hand-maintained lists of the same
 * thing: the former says which keys a project may opt in to, the latter says where each key's file
 * lives. Nothing in the code connects them, so adding a key to one and not the other compiles,
 * passes every existing test, and ships.
 *
 * <p>The failure is not abstract. {@code vibetags init --platforms <key>} validates its argument
 * against {@code optInKeys()} and then looks the key up in {@code buildServiceFileMap}. A key
 * present in the first and absent from the second turns a valid command line into a
 * {@link NullPointerException} with no message, from user input, which is the worst place to
 * discover a two-list mismatch. {@code InitCommand} and {@code DoctorCommand} state this invariant
 * with {@code Objects.requireNonNull}; this test is what makes stating it honest.
 */
class ServiceRegistryKeyParityTest {

    @Test
    void everyOptInKey_hasAPathInTheServiceFileMap() {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(Path.of("."));
        Set<String> missing = new TreeSet<>(ServiceRegistry.optInKeys());
        missing.removeAll(serviceFiles.keySet());

        assertTrue(missing.isEmpty(),
            "opt-in key(s) with no entry in buildServiceFileMap: " + missing
                + ". `vibetags init --platforms " + String.join(",", missing)
                + "` would accept the key and then dereference a null path.");
    }

    /**
     * Which services are ignore files, stated independently of {@code isIgnoreService}: every
     * {@code *_ignore} key, {@code aider_ignore} named explicitly, and {@code aiexclude}.
     * {@code generateFiles()} used to spell this inline as well, and this test held the two to one
     * answer; since #766 both writers ask {@code WritePlan}, so what is left to pin is the
     * definition, for every key a build can see. An ignore file is rewritten even by a round that
     * contributed nothing, so a key wrongly left out stops its exclusion list updating.
     */
    @Test
    void isIgnoreService_isExactlyTheIgnoreAndExcludeFiles_forEveryServiceKey() {
        Set<String> disagreeing = new TreeSet<>();
        Set<String> ignoreKeys = new TreeSet<>();
        for (String service : ServiceRegistry.buildServiceFileMap(Path.of(".")).keySet()) {
            boolean inlined = service.endsWith("_ignore") || "aider_ignore".equals(service)
                || "aiexclude".equals(service);
            if (inlined != ServiceRegistry.isIgnoreService(service)) {
                disagreeing.add(service);
            }
            if (inlined) {
                ignoreKeys.add(service);
            }
        }

        assertTrue(disagreeing.isEmpty(), "isIgnoreService disagrees with the definition on: " + disagreeing);
        assertTrue(ignoreKeys.contains("aider_ignore") && ignoreKeys.contains("aiexclude"),
            "the comparison ran over no ignore keys, so it proved nothing: " + ignoreKeys);
    }
}
