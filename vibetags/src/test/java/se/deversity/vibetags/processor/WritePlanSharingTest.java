package se.deversity.vibetags.processor;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * generateFiles() writes, checkFiles() reports what generateFiles() would write, and for years the
 * two were kept in step by copying: the ignore-file predicate, the has-new-rules decision and the
 * root-sweep predicate each existed twice, the copy in generateFiles() frozen by its lock (#766).
 * A behavioural test can only catch the copies once they have already drifted. This one fails the
 * moment either method decides a per-file question for itself instead of reading the shared
 * {@code WritePlan} and {@code maySweepRoot}, or resolves its targets or builds this round's sidecar
 * itself instead of through {@code resolveRoundTargets} and {@code assembleSidecar}.
 *
 * <p>Calls inside a lambda are compiled into a synthetic {@code lambda$method$n}, and the inline
 * predicate lived in exactly such a lambda, so those count as calls from the method too.
 */
class WritePlanSharingTest {

    private static final JavaClass PROCESSOR = new ClassFileImporter()
        .importClasses(AIGuardrailProcessor.class).get(AIGuardrailProcessor.class);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"generateFiles", "checkFiles"})
    void eachWriterReadsTheSharedDecisions(String writer) {
        Set<String> calls = callsFrom(writer);

        assertTrue(calls.contains("WritePlan.of"), writer + " does not plan its writes through WritePlan: " + calls);
        assertTrue(calls.contains("AIGuardrailProcessor.maySweepRoot"),
            writer + " decides whether it may sweep the root without maySweepRoot: " + calls);
        assertFalse(calls.contains("ServiceRegistry.isIgnoreService"),
            writer + " asks which files are ignore files itself; that is WritePlan's decision");
        assertFalse(calls.contains("String.endsWith"),
            writer + " carries an inline service-name predicate again");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"generateFiles", "checkFiles"})
    void eachWriterResolvesItsTargetsAndBuildsItsSidecarThroughTheSharedMethods(String writer) {
        Set<String> calls = callsFrom(writer);

        assertTrue(calls.contains("AIGuardrailProcessor.resolveRoundTargets"),
            writer + " does not resolve its targets through resolveRoundTargets: " + calls);
        assertTrue(calls.contains("AIGuardrailProcessor.assembleSidecar"),
            writer + " does not build its sidecar through assembleSidecar: " + calls);
        for (String own : Set.of("ServiceRegistry.buildServiceFileMap", "RoleConfig.load",
                "AIGuardrailProcessor.populateSidecarBodies", "ModuleSidecar.setGranularStems",
                "ModuleSidecar.setElementIds", "AIGuardrailProcessor.buildSafetyDigests")) {
            assertFalse(calls.contains(own), writer + " calls " + own + " itself again; the copy drifts from the other mode's");
        }
    }

    private static Set<String> callsFrom(String method) {
        Set<String> calls = PROCESSOR.getMethods().stream()
            .filter(m -> m.getName().equals(method) || m.getName().startsWith("lambda$" + method + "$"))
            .flatMap(m -> m.getMethodCallsFromSelf().stream())
            .map(c -> c.getTargetOwner().getSimpleName() + "." + c.getName())
            .collect(Collectors.toSet());
        assertFalse(calls.isEmpty(), "found no calls from " + method + "; was it renamed?");
        return calls;
    }
}
