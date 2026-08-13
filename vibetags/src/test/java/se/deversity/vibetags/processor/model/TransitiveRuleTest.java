package se.deversity.vibetags.processor.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inherited-rule value type.
 *
 * <p>Immutability is asserted here rather than assumed: {@code spotbugs-exclude.xml} suppresses
 * {@code EI_EXPOSE_REP} on {@link TransitiveRule#members()} on the grounds that the constructor
 * already wrapped the map, and an exclusion is only earned while a test proves the claim.
 */
class TransitiveRuleTest {

    private static TransitiveRule rule(String pkg, String label, String origin, Map<String, String> members) {
        return new TransitiveRule(origin, pkg, label, TransitiveRule.Tier.ADVISORY, members);
    }

    @Test
    void membersCannotBeMutatedThroughTheAccessor() {
        TransitiveRule rule = rule("com.acme.api", "@AIContext", "o", Map.of("note", "x"));
        assertThrows(UnsupportedOperationException.class, () -> rule.members().put("note", "tampered"));
        assertEquals("x", rule.members().get("note"));
    }

    @Test
    void membersCannotBeMutatedThroughTheMapPassedIn() {
        // A caller keeping a reference to the map it constructed must not be able to rewrite a
        // library's published rule after the fact.
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("note", "original");
        TransitiveRule rule = rule("com.acme.api", "@AIContext", "o", mutable);
        mutable.put("note", "tampered");
        mutable.put("extra", "injected");

        assertEquals("original", rule.members().get("note"));
        assertEquals(1, rule.members().size());
    }

    @Test
    void aRuleWithNoMembersHasAnEmptyMapNotNull() {
        assertEquals(Map.of(), rule("com.acme.api", "@AIContext", "o", null).members());
        assertEquals(Map.of(), rule("com.acme.api", "@AIContext", "o", Map.of()).members());
    }

    @Test
    void memberSummaryKeepsDeclarationOrder() {
        Map<String, String> members = new LinkedHashMap<>();
        members.put("focus", "f");
        members.put("avoids", "a");
        assertEquals("focus=f; avoids=a",
            rule("com.acme.api", "@AIContext", "o", members).memberSummary());
    }

    @Test
    void memberSummaryIsEmptyWithoutMembers() {
        assertEquals("", rule("com.acme.api", "@AIContext", "o", Map.of()).memberSummary());
    }

    @Test
    void sortsByPackageThenAnnotationThenOriginThenMembers() {
        // The order rules are rendered and fingerprinted in. Discovery order is import-walk order,
        // which differs between Maven and Gradle and between machines, so this comparator is what
        // makes the generated files a function of the dependency set alone.
        TransitiveRule a = rule("com.a.api", "@AIContext", "o", Map.of());
        TransitiveRule b = rule("com.b.api", "@AIContext", "o", Map.of());
        TransitiveRule c = rule("com.a.api", "@AIThreadSafe", "o", Map.of());
        TransitiveRule d = rule("com.a.api", "@AIContext", "p", Map.of());

        List<TransitiveRule> sorted = new ArrayList<>(List.of(b, d, c, a));
        java.util.Collections.sort(sorted);
        assertEquals(List.of(a, d, c, b), sorted);
    }

    @Test
    void equalityCoversEveryFieldThatIsRendered() {
        TransitiveRule base = rule("com.acme.api", "@AIContext", "o", Map.of("note", "x"));
        assertEquals(base, rule("com.acme.api", "@AIContext", "o", Map.of("note", "x")));
        assertEquals(base.hashCode(), rule("com.acme.api", "@AIContext", "o", Map.of("note", "x")).hashCode());

        assertNotEquals(base, rule("com.acme.spi", "@AIContext", "o", Map.of("note", "x")));
        assertNotEquals(base, rule("com.acme.api", "@AISecure", "o", Map.of("note", "x")));
        assertNotEquals(base, rule("com.acme.api", "@AIContext", "p", Map.of("note", "x")));
        assertNotEquals(base, rule("com.acme.api", "@AIContext", "o", Map.of("note", "y")));
        assertNotEquals(base, new TransitiveRule("o", "com.acme.api", "@AIContext",
            TransitiveRule.Tier.SAFETY, Map.of("note", "x")));
    }

    @Test
    void rejectsNullIdentity() {
        assertThrows(NullPointerException.class,
            () -> new TransitiveRule(null, "p", "@AIContext", TransitiveRule.Tier.ADVISORY, Map.of()));
        assertThrows(NullPointerException.class,
            () -> new TransitiveRule("o", null, "@AIContext", TransitiveRule.Tier.ADVISORY, Map.of()));
        assertThrows(NullPointerException.class,
            () -> new TransitiveRule("o", "p", null, TransitiveRule.Tier.ADVISORY, Map.of()));
        assertThrows(NullPointerException.class,
            () -> new TransitiveRule("o", "p", "@AIContext", null, Map.of()));
    }

    @Test
    void toStringNamesTheArtifactOrSaysItIsUnnamed() {
        assertTrue(rule("com.acme.api", "@AIContext", "com.acme:core:1.0", Map.of())
            .toString().contains("com.acme:core:1.0"));
        assertTrue(rule("com.acme.api", "@AIContext", "", Map.of())
            .toString().contains("unnamed artifact"));
    }

    @Test
    void theModelDeduplicatesAndSortsWhatItIsGiven() {
        TransitiveRule a = rule("com.b.api", "@AIContext", "o", Map.of());
        TransitiveRule b = rule("com.a.api", "@AIContext", "o", Map.of());
        GuardrailModel model = GuardrailModel.builder()
            .transitiveRule(a).transitiveRule(b).transitiveRule(a)
            .transitiveRule(null)
            .build();

        assertEquals(List.of(b, a), model.transitiveRules(),
            "the same manifest is reachable through several import prefixes, so duplicates are "
                + "the normal case rather than a bug upstream");
        assertTrue(model.anyTransitiveRules());
        assertThrows(UnsupportedOperationException.class,
            () -> model.transitiveRules().add(a));
    }

    @Test
    void anEmptyModelReportsNoTransitiveRules() {
        assertEquals(List.of(), GuardrailModel.EMPTY.transitiveRules());
        assertTrue(!GuardrailModel.EMPTY.anyTransitiveRules());
    }
}
