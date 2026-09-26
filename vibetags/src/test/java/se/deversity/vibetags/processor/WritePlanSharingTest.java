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
 * {@code WritePlan} and {@code maySweepRoot}.
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
