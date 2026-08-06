package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.validation.PairRule;
import se.deversity.vibetags.processor.internal.validation.ValidationRule;
import se.deversity.vibetags.processor.internal.validation.ValidationRules;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the rule registry itself, as opposed to what any individual rule reports.
 *
 * <p>These are the invariants that stop the registry rotting as rules are added: a rule bound to an
 * annotation that is not in {@link GuardrailAnnotations#ALL} would silently never fire, and the
 * one-query-per-annotation grouping is the whole reason the dispatcher exists.
 */
class ValidationRegistryTest {

    @Test
    void everyRuleScansARegisteredGuardrailAnnotation() {
        Set<Class<? extends Annotation>> known = new HashSet<>(GuardrailAnnotations.ALL);

        List<String> strays = new ArrayList<>();
        for (ValidationRule rule : ValidationRules.all()) {
            if (!known.contains(rule.scans())) {
                strays.add(rule.getClass().getSimpleName() + " → @" + rule.scans().getSimpleName());
            }
        }

        assertTrue(strays.isEmpty(),
            "A rule bound to an annotation outside GuardrailAnnotations.ALL never fires: " + strays);
    }

    @Test
    void everyScannedAnnotationIsQueriedExactlyOnce() {
        // The dispatcher's contract: however many rules share an annotation, the round is queried
        // once for it. Before the registry existed, @AITestDriven was queried four times per round.
        Set<Class<? extends Annotation>> scanned = ValidationRules.scannedAnnotations();

        assertEquals(scanned.size(), new HashSet<>(scanned).size(),
            "scannedAnnotations() must be a set — one query per annotation type");

        Set<Class<? extends Annotation>> fromRules = new HashSet<>();
        for (ValidationRule rule : ValidationRules.all()) {
            fromRules.add(rule.scans());
        }
        assertEquals(fromRules, new HashSet<>(scanned),
            "Every rule's annotation must appear in the query set, and nothing else");
    }

    @Test
    void noPairRuleChecksTheAnnotationItScans() {
        // A self-pair fires on every element carrying the annotation, unconditionally, which reads
        // to a consumer as "VibeTags warns about @AILocked" rather than as a real contradiction.
        List<String> selfPairs = new ArrayList<>();
        for (ValidationRule rule : ValidationRules.all()) {
            if (rule instanceof PairRule pair && pair.scans().equals(pair.other())) {
                selfPairs.add("@" + pair.scans().getSimpleName());
            }
        }

        assertTrue(selfPairs.isEmpty(),
            "A PairRule paired with itself always fires and says nothing: " + selfPairs);
    }

    @Test
    void groupingIsStrictlyFewerQueriesThanRules() {
        assertTrue(ValidationRules.scannedAnnotations().size() < ValidationRules.all().size(),
            "Rules must share round queries; if they no longer do, the dispatcher has stopped grouping");
    }

    @Test
    void theRegistryIsNotEmpty() {
        assertFalse(ValidationRules.all().isEmpty(),
            "An empty registry would mean no validation at all, silently");
    }

    @Test
    void aCallerCannotEmptyTheRegistryForEverySubsequentCompilation() {
        // The registry is static and a Gradle daemon reuses it across unrelated compilations, so
        // handing out the live list would let one caller's clear() silence validation for the rest
        // of the JVM's life. all() returns a copy; this is what says so.
        int before = ValidationRules.all().size();

        List<ValidationRule> handedOut = ValidationRules.all();
        handedOut.clear();

        assertEquals(before, ValidationRules.all().size(),
            "Mutating the list all() returned must not change the registry");
    }
}
