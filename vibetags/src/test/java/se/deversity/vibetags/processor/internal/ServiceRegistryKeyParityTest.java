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
     * {@code isIgnoreService} answers for the keys it is meant to and not for the rest.
     *
     * <p>This replaces a parity case that compared the helper against a hand-written copy of the
     * predicate {@code generateFiles()} spelled inline, because that method was locked and could
     * not be made to call it. Both rounds reach the helper now, through
     * {@code AIGuardrailProcessor.hasNewRules} (#766), so there is no second spelling to hold it
     * to and a test that kept one would be testing the copy in the test.
     *
     * <p>What still needs saying is what the helper is for: an exclusion list is rewritten on
     * every build whether or not the round had annotations, and a rule file is not. So the answer
     * is asserted against the registry's own keys rather than a literal list, and against both
     * shapes an exclusion list comes in: the {@code *_ignore} suffix and {@code .aiexclude}, which
     * has neither the suffix nor anything else in common with them.
     */
    @Test
    void isIgnoreService_picksOutTheExclusionLists_andNothingElse() {
        Set<String> exclusionLists = new TreeSet<>();
        Set<String> ruleFiles = new TreeSet<>();
        for (String service : ServiceRegistry.buildServiceFileMap(Path.of(".")).keySet()) {
            (ServiceRegistry.isIgnoreService(service) ? exclusionLists : ruleFiles).add(service);
        }

        assertTrue(exclusionLists.contains("aiexclude"),
            ".aiexclude is an exclusion list and carries no _ignore suffix to be recognised by: "
                + exclusionLists);
        assertTrue(exclusionLists.contains("aider_ignore"),
            "the suffix form is not recognised either, so nothing here is: " + exclusionLists);
        assertTrue(exclusionLists.size() > 2,
            "only the two named keys were classified as exclusion lists, so this ran over a"
                + " registry that has lost its others: " + exclusionLists);

        Set<String> suffixed = new TreeSet<>();
        for (String service : ruleFiles) {
            if (service.endsWith("_ignore")) {
                suffixed.add(service);
            }
        }
        assertTrue(suffixed.isEmpty(),
            "these carry the exclusion-list suffix and were classified as rule files, so a build"
                + " would stop rewriting them on a round with no annotations: " + suffixed);
        assertTrue(ruleFiles.contains("claude"),
            "no ordinary rule-file key was classified as one, so the negative half proved nothing");
    }
}
