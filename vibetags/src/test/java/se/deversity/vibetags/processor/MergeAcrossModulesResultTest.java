package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What {@code mergeAcrossModules} hands back: for each service, either every module's merged view
 * or this module's own rendering, and never anything it was not given.
 *
 * <p>{@code MergeAcrossModulesLogContractTest} holds what the merge says about its choices. This
 * holds the choices: the map is what the round writes, so a service that merges when it should not,
 * or keeps its own rendering when it should not, is a wrong file with nothing malformed about it.
 */
class MergeAcrossModulesResultTest {

    private static final Map<String, Path> SERVICE_FILES = ServiceRegistry.buildServiceFileMap(Path.of("."));

    private static ModuleSidecar sidecar(String moduleId, Map<String, String> bodies) {
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, moduleId);
        bodies.forEach(sidecar::putBody);
        return sidecar;
    }

    private static Map<String, String> ordered(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a single module's content comes back as the very map it handed in")
    void aSingleModuleKeepsItsOwnMap() {
        Map<String, String> own = ordered("claude", "rule from solo");

        Map<String, String> result = AIGuardrailProcessor.mergeAcrossModules(
            own, SERVICE_FILES, List.of(sidecar("solo", own)));

        assertSame(own, result);
    }

    @Test
    @DisplayName("marker services get every module's view; the rest of the map is left as given, in order")
    void markerServicesAreMergedAndTheRestIsLeftAlone() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("alpha", Map.of("claude", "rule from alpha", "cursor", "cursor rule from alpha")),
            sidecar("beta", Map.of("claude", "rule from beta")));
        Map<String, String> own = ordered(
            "not-a-service", "content for a key no file is registered for",
            "cursor", "cursor rule from alpha",
            "claude", "rule from alpha");

        Map<String, String> result = AIGuardrailProcessor.mergeAcrossModules(own, SERVICE_FILES, sidecars);

        assertNotSame(own, result, "the merge answers with a map of its own");
        assertEquals(ordered(
                "not-a-service", "content for a key no file is registered for",
                "cursor", "cursor rule from alpha",
                "claude", ModuleSidecar.mergeFor("claude", sidecars, true)),
            result);
        assertEquals(List.copyOf(own.keySet()), List.copyOf(result.keySet()), "in the order it was given");
        assertEquals("rule from alpha", own.get("claude"), "and the map handed in is not changed");
    }

    @Test
    @DisplayName("a service this module rendered nothing for is not added, whatever its siblings hold")
    void aServiceThisModuleDidNotRenderIsNotAdded() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("alpha", Map.of("claude", "rule from alpha")),
            sidecar("beta", Map.of("claude", "rule from beta", "cursor", "cursor rule from beta")));

        Map<String, String> result = AIGuardrailProcessor.mergeAcrossModules(
            ordered("claude", "rule from alpha"), SERVICE_FILES, sidecars);

        assertEquals(List.of("claude"), List.copyOf(result.keySet()),
            "a file this module did not opt into is not written because a sibling contributes to it");
    }

    @Test
    @DisplayName("a marker service no module has a body for, and a whole file its merger declines, keep this module's rendering")
    void whatCannotBeMergedKeepsThisModulesRendering() {
        List<ModuleSidecar> sidecars = List.of(
            sidecar("alpha", Map.of("greptile", "not json at all")),
            sidecar("beta", Map.of("greptile", "also not json")));
        // No sidecar holds a claude body, so the merged view of it is blank. Publishing that would
        // replace what this module rendered with nothing.
        Map<String, String> own = ordered("claude", "this module's own claude rendering", "greptile", "not json at all");

        Map<String, String> result = AIGuardrailProcessor.mergeAcrossModules(own, SERVICE_FILES, sidecars);

        assertEquals(own, result);
    }
}
