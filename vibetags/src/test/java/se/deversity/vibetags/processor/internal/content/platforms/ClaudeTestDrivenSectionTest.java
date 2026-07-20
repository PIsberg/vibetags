package se.deversity.vibetags.processor.internal.content.platforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Unit tests for {@link ClaudeTestDrivenSection} — the CLAUDE.md coalescing of homogeneous
 * {@code @AITestDriven} elements into a {@code <test_driven_default>} block plus an
 * {@code <applies-to>} member list (issue #283).
 */
class ClaudeTestDrivenSectionTest {

    private static String render(List<Element> elements) {
        StringBuilder sb = new StringBuilder();
        ClaudeTestDrivenSection.render(elements, sb);
        return sb.toString();
    }

    private static String conventionPath(String fqn) {
        return "src/test/java/" + fqn.replace('.', '/') + "Test.java";
    }

    private static Element td(String fqn, ElementKind kind, int coverage, String mockPolicy,
                              String testLocation, AITestDriven.Framework... frameworks) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(kind);
        Name simpleName = mock(Name.class);
        when(simpleName.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(simpleName);

        AITestDriven a = mock(AITestDriven.class);
        when(a.coverageGoal()).thenReturn(coverage);
        when(a.mockPolicy()).thenReturn(mockPolicy);
        when(a.testLocation()).thenReturn(testLocation);
        AITestDriven.Framework[] fw = frameworks.length == 0
            ? new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5}
            : frameworks;
        doReturn(fw).when(a).framework();
        when(e.getAnnotation(AITestDriven.class)).thenReturn(a);
        return e;
    }

    /** Convenience: a TYPE element with the default JUnit-5 framework and no explicit location. */
    private static Element type(String fqn, int coverage) {
        return td(fqn, ElementKind.CLASS, coverage, "", "");
    }

    @Test
    void identicalEmptyLocations_collapseIntoOneDefaultBlock() {
        String out = render(List.of(
            type("com.example.A", 80),
            type("com.example.B", 80),
            type("com.example.C", 80)));

        assertTrue(out.contains("<test_driven_default coverage_goal=\"80\" frameworks=\"JUNIT_5\">"),
            "the shared values render as a single default block");
        // No per-member stanza — the child-tag form appears only in individual <element> stanzas.
        assertFalse(out.contains("<coverage_goal>"), "coalesced members must not repeat child stanzas");
        assertFalse(out.contains("<element path="), "coalesced members must not emit individual stanzas");
        assertTrue(out.contains("<applies-to>"), "members are listed under applies-to");
        assertTrue(out.contains("com.example.A") && out.contains("com.example.B") && out.contains("com.example.C"),
            "every member is named in applies-to");
        // Empty locations mean the attribute is omitted (the AI infers the path).
        assertFalse(out.contains("test_location="), "empty locations omit the test_location attribute");
    }

    @Test
    void conventionLocations_renderAsPathTemplate() {
        String fqnA = "com.example.diag.AlphaDetector";
        String fqnB = "com.example.diag.BetaDetector";
        String out = render(List.of(
            td(fqnA, ElementKind.CLASS, 80, "", conventionPath(fqnA)),
            td(fqnB, ElementKind.CLASS, 80, "", conventionPath(fqnB))));

        assertTrue(out.contains("test_location=\"src/test/java/{path}Test.java\""),
            "mirror-convention locations collapse to the {path} template");
        assertTrue(out.contains(fqnA) && out.contains(fqnB), "both members listed");
    }

    @Test
    void mockPolicy_appearsAsAttributeWhenShared() {
        String out = render(List.of(
            td("com.example.A", ElementKind.CLASS, 90, "Mock Stripe", ""),
            td("com.example.B", ElementKind.CLASS, 90, "Mock Stripe", "")));

        assertTrue(out.contains("mock_policy=\"Mock Stripe\""), "shared mock policy renders as an attribute");
    }

    @Test
    void divergentElement_keepsIndividualStanza() {
        String out = render(List.of(
            type("com.example.A", 80),
            type("com.example.B", 80),
            type("com.example.Outlier", 95)));

        assertTrue(out.contains("<test_driven_default coverage_goal=\"80\" frameworks=\"JUNIT_5\">"),
            "the majority collapses into the default block");
        assertTrue(out.contains("<element path=\"com.example.Outlier\">"),
            "the divergent element keeps its own stanza");
        assertTrue(out.contains("<coverage_goal>95</coverage_goal>"),
            "the divergent element renders its own values");
    }

    @Test
    void bespokeLocation_isNotFoldedIntoTemplate() {
        String fqnA = "com.example.diag.AlphaDetector";
        String fqnB = "com.example.diag.BetaDetector";
        String out = render(List.of(
            td(fqnA, ElementKind.CLASS, 80, "", conventionPath(fqnA)),
            td(fqnB, ElementKind.CLASS, 80, "", conventionPath(fqnB)),
            td("com.example.Custom", ElementKind.CLASS, 80, "", "it/custom/CustomIT.java")));

        assertTrue(out.contains("test_location=\"src/test/java/{path}Test.java\""),
            "convention members still template");
        assertTrue(out.contains("<element path=\"com.example.Custom\">"),
            "the bespoke-location element stays individual");
        assertTrue(out.contains("<test_location>it/custom/CustomIT.java</test_location>"),
            "the bespoke location is rendered verbatim in its own stanza");
    }

    @Test
    void singleElement_belowThreshold_staysIndividual() {
        String out = render(List.of(type("com.example.Solo", 100)));

        assertFalse(out.contains("<test_driven_default"), "a lone element is not coalesced");
        assertTrue(out.contains("<element path=\"com.example.Solo\">"), "it renders as an individual stanza");
        assertTrue(out.contains("<coverage_goal>100</coverage_goal>"), "with its own values");
    }

    @Test
    void allDistinctValues_noCoalescing() {
        String out = render(List.of(
            type("com.example.A", 70),
            type("com.example.B", 80),
            type("com.example.C", 90)));

        assertFalse(out.contains("<test_driven_default"), "no group reaches the coalesce threshold");
        assertTrue(out.contains("<element path=\"com.example.A\">")
            && out.contains("<element path=\"com.example.B\">")
            && out.contains("<element path=\"com.example.C\">"),
            "each element renders individually");
    }

    @Test
    void tiedGroups_firstSeenBecomesDefault() {
        // Two groups of two; group with coverage 80 is inserted first and wins the default slot.
        String out = render(List.of(
            type("com.example.A", 80),
            type("com.example.B", 80),
            type("com.example.C", 95),
            type("com.example.D", 95)));

        assertTrue(out.contains("<test_driven_default coverage_goal=\"80\" frameworks=\"JUNIT_5\">"),
            "the first-seen group becomes the default block");
        assertTrue(out.contains("<element path=\"com.example.C\">")
            && out.contains("<element path=\"com.example.D\">"),
            "the runner-up group stays as individual stanzas");
    }

    @Test
    void appliesToOrder_followsInsertionOrder() {
        String out = render(List.of(
            type("com.example.First", 80),
            type("com.example.Second", 80),
            type("com.example.Third", 80)));

        int first = out.indexOf("com.example.First");
        int second = out.indexOf("com.example.Second");
        int third = out.indexOf("com.example.Third");
        assertTrue(first < second && second < third, "applies-to preserves insertion order");
    }

    @Test
    void render_isDeterministic() {
        List<Element> elements = List.of(
            type("com.example.A", 80),
            type("com.example.B", 80),
            type("com.example.Outlier", 95));

        assertEquals(render(elements), render(elements), "same input yields identical output");
    }
}
