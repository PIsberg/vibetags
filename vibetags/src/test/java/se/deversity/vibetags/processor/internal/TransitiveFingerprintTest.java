package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Inherited guardrails are part of the build fingerprint.
 *
 * <p>This is the check for the way the feature would most plausibly have shipped broken and green.
 * {@code generateFiles()} opens with a short-circuit that compares a fingerprint of the collected
 * annotations and the active services; a dependency upgrade changes what the generated files should
 * say while every annotation in the consuming project stays byte-identical. A fingerprint blind to
 * inherited rules therefore matches, the whole generate phase is skipped, and the committed files
 * keep describing the previous version of the dependency. Nothing fails, nothing is logged, and the
 * only symptom is a file that quietly stopped tracking reality.
 *
 * <p>Asserted here rather than only through the end-to-end test on purpose: the end-to-end path
 * also rewrites the module sidecar on every run, whose mtime feeds a second half of the same
 * short-circuit, so a dependency upgrade reaches the file there whether or not the fingerprint
 * notices. That makes the integration test a fine check that upgrades work and a useless one for
 * whether this contribution exists.
 */
class TransitiveFingerprintTest {

    private static final Set<String> SERVICES = Set.of("claude");

    private static TransitiveRule rule(String pkg, String label, String origin, String note) {
        return new TransitiveRule(origin, pkg, label, TransitiveManifest.tierOf(label),
            note.isEmpty() ? Map.of() : Map.of("note", note));
    }

    private static String fingerprintOf(TransitiveRule... rules) {
        AnnotationCollector collector = new AnnotationCollector();
        collector.addTransitiveRules(List.of(rules));
        return BuildFingerprint.compute(collector, SERVICES, "1.0.0");
    }

    @Test
    void anInheritedRuleChangesTheFingerprint() {
        assertNotEquals(
            fingerprintOf(),
            fingerprintOf(rule("com.acme.api", "@AISecure", "com.acme:core:1.0", "x")),
            "a project that started inheriting a guardrail must not look unchanged");
    }

    @Test
    void upgradingTheOriginChangesTheFingerprint() {
        // The version is rendered into the file as the rule's attribution, so a bump is a content
        // change even when every word of the rule is identical.
        assertNotEquals(
            fingerprintOf(rule("com.acme.api", "@AISecure", "com.acme:core:1.0", "x")),
            fingerprintOf(rule("com.acme.api", "@AISecure", "com.acme:core:2.0", "x")),
            "a dependency upgrade must not be short-circuited past");
    }

    @Test
    void editingARulesTextChangesTheFingerprint() {
        assertNotEquals(
            fingerprintOf(rule("com.acme.api", "@AISecure", "o", "before")),
            fingerprintOf(rule("com.acme.api", "@AISecure", "o", "after")));
    }

    @Test
    void movingARuleToAnotherPackageChangesTheFingerprint() {
        assertNotEquals(
            fingerprintOf(rule("com.acme.api", "@AISecure", "o", "x")),
            fingerprintOf(rule("com.acme.spi", "@AISecure", "o", "x")));
    }

    @Test
    void changingTheAnnotationChangesTheFingerprint() {
        assertNotEquals(
            fingerprintOf(rule("com.acme.api", "@AISecure", "o", "x")),
            fingerprintOf(rule("com.acme.api", "@AIContext", "o", "x")),
            "the annotation decides both the wording and the tier the rule is rendered under");
    }

    @Test
    void droppingARuleChangesTheFingerprint() {
        assertNotEquals(
            fingerprintOf(rule("com.acme.api", "@AISecure", "o", "x"),
                          rule("com.acme.api", "@AIContext", "o", "y")),
            fingerprintOf(rule("com.acme.api", "@AISecure", "o", "x")),
            "a dependency that stopped publishing a rule must regenerate the files too");
    }

    @Test
    void anUnchangedDependencySetKeepsTheFingerprintStable() {
        // The other half of the contract. If inherited rules made every build look different, the
        // cache would never hit and committed files would churn on every colleague's machine.
        TransitiveRule a = rule("com.acme.api", "@AISecure", "com.acme:core:1.0", "x");
        TransitiveRule b = rule("com.other.api", "@AIContext", "com.other:lib:3.1", "y");
        assertEquals(fingerprintOf(a, b), fingerprintOf(a, b));
    }

    @Test
    void discoveryOrderDoesNotAffectTheFingerprint() {
        // Manifests are found in import-walk order, which differs between Maven and Gradle and
        // between machines. Order reaching the fingerprint would churn files for no reason.
        TransitiveRule a = rule("com.acme.api", "@AISecure", "o", "x");
        TransitiveRule b = rule("com.other.api", "@AIContext", "o", "y");
        assertEquals(fingerprintOf(a, b), fingerprintOf(b, a));
    }

    @Test
    void aProjectWithNoInheritedRulesFingerprintsAsBefore() {
        // Pins that the feature is inert when unused: an empty rule set must not perturb the value
        // for the overwhelming majority of projects, which never opt in.
        AnnotationCollector empty = new AnnotationCollector();
        assertEquals(BuildFingerprint.compute(empty, SERVICES, "1.0.0"), fingerprintOf());
    }
}
