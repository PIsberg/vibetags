package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.GranularSections;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.model.TaggedElement;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the granular section collapser (issue #313): the constant rule sentence that
 * {@code @AIPrivacy}, {@code @AISecure} and friends emit must appear once per section, not once per
 * annotated element — while a lone element keeps the historical byte-for-byte output.
 */
class GranularSectionsTest {

    private static final String PRIVACY_RULE = "- **Rule**: Never log or expose runtime values of this element.";

    private static Element type(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name name = mock(Name.class);
        when(name.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(name);
        return e;
    }

    private static TaggedElement field(Element owner, String simple) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(simple);
        when(e.getKind()).thenReturn(ElementKind.FIELD);
        when(e.getEnclosingElement()).thenReturn(owner);
        Name name = mock(Name.class);
        when(name.toString()).thenReturn(simple);
        when(e.getSimpleName()).thenReturn(name);
        return TaggedElements.tagged(e);
    }

    private static GranularBody.Entry privacy(Element owner, TaggedElement element, String reason) {
        return new GranularBody.Entry(TaggedElements.tagged(owner), element, "PII / Privacy Guardrails",
            List.of(PRIVACY_RULE, "- **Reason**: " + reason));
    }

    // ------------------------------------------------------------------
    // Threshold: a lone element keeps the historical output exactly
    // ------------------------------------------------------------------

    @Test
    void singleMemberStanza_rendersHistoricalFormatByteForByte() {
        Element owner = type("com.example.KeyBundle");
        TaggedElement f = field(owner, "privateKey");

        String out = GranularSections.render(List.of(privacy(owner, f, "key material")), false);

        assertEquals("### Rules for field privateKey\n"
            + PRIVACY_RULE + "\n"
            + "- **Reason**: key material\n", out);
    }

    @Test
    void twoStanzasWithNothingInCommon_keepHistoricalFormat() {
        Element owner = type("com.example.Foo");
        GranularBody.Entry a = new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "a"), "Locked Status",
            List.of("- **Reason**: one"));
        GranularBody.Entry b = new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "b"), "Locked Status",
            List.of("- **Reason**: two"));

        String out = GranularSections.render(List.of(a, b), false);

        assertEquals("### Rules for field a\n- **Reason**: one\n"
            + "\n### Rules for field b\n- **Reason**: two\n", out);
    }

    // ------------------------------------------------------------------
    // Hoisting: the constant rule line is stated once, pluralized
    // ------------------------------------------------------------------

    @Test
    void twoPrivacyStanzas_hoistSharedRuleOnceAndPluralizeIt() {
        Element owner = type("com.example.KeyBundle");
        String out = GranularSections.render(List.of(
            privacy(owner, field(owner, "serialVersionUID"), "key material"),
            privacy(owner, field(owner, "privateKey"), "private exponent")), false);

        assertEquals(1, count(out, "Never log or expose runtime values"),
            "the constant rule sentence must appear exactly once per section");
        assertTrue(out.startsWith("## PII / Privacy Guardrails\n"
            + "- **Rule**: Never log or expose runtime values of these elements.\n"),
            "hoisted rule leads the section, pluralized; was:\n" + out);
        assertTrue(out.contains("### Rules for field serialVersionUID\n- **Reason**: key material\n"));
        assertTrue(out.contains("### Rules for field privateKey\n- **Reason**: private exponent\n"));
    }

    @Test
    void stanzasThatAreEntirelyShared_collapseToAnAppliesToList() {
        Element owner = type("com.example.Calc");
        String pureRule = "- **Rule**: Must remain a pure function. Forbid state modifications and side effects.";
        GranularBody.Entry a = new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "add"), "Mathematical Purity", List.of(pureRule));
        GranularBody.Entry b = new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "mul"), "Mathematical Purity", List.of(pureRule));

        String out = GranularSections.render(List.of(a, b), false);

        assertEquals("## Mathematical Purity\n" + pureRule + "\n"
            + "- **Applies to**: `Calc.add`, `Calc.mul`\n", out);
    }

    @Test
    void sharedTrailingLine_isHoistedToo() {
        // @AIFeatureFlag puts its varying line first and the constant rule second.
        Element owner = type("com.example.Gate");
        String rule = "- **Rule**: This code is gated behind a feature flag. Preserve the flag check.";
        GranularBody.Entry a = new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "x"), "Feature Flag Gate",
            List.of("- **Flag**: 'alpha' (default: false)", rule));
        GranularBody.Entry b = new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "y"), "Feature Flag Gate",
            List.of("- **Flag**: 'beta' (default: true)", rule));

        String out = GranularSections.render(List.of(a, b), false);

        assertEquals(1, count(out, "gated behind a feature flag"),
            "a shared trailing line hoists just like a shared leading one");
        assertTrue(out.contains("- **Flag**: 'alpha' (default: false)"));
        assertTrue(out.contains("- **Flag**: 'beta' (default: true)"));
    }

    @Test
    void sectionsAreIndependent_oneCollapsesTheOtherDoesNot() {
        Element owner = type("com.example.Mixed");
        String out = GranularSections.render(List.of(
            privacy(owner, field(owner, "a"), "one"),
            privacy(owner, field(owner, "b"), "two"),
            new GranularBody.Entry(TaggedElements.tagged(owner), field(owner, "c"), "Locked Status", List.of("- **Reason**: solo"))), false);

        assertEquals(1, count(out, "Never log or expose runtime values"));
        assertTrue(out.contains("### Rules for field c\n- **Reason**: solo\n"),
            "a single-stanza section keeps its historical form: " + out);
        assertFalse(out.contains("## Locked Status"),
            "a single-stanza section gets no hoisted section heading");
    }

    // ------------------------------------------------------------------
    // Qualified (role/topic file) mode
    // ------------------------------------------------------------------

    @Test
    void qualifiedMode_usesFullyQualifiedHeadingsAcrossOwners() {
        Element bundle = type("com.example.KeyBundle");
        Element pair = type("com.example.PaillierKeyPair");
        String out = GranularSections.render(List.of(
            privacy(bundle, field(bundle, "serialVersionUID"), "key material"),
            privacy(pair, field(pair, "privateKey"), "private exponent")), true);

        assertEquals(1, count(out, "Never log or expose runtime values"),
            "cross-owner duplication in a role file collapses too — the case measured in #313");
        assertTrue(out.contains("### com.example.KeyBundle.serialVersionUID"));
        assertTrue(out.contains("### com.example.PaillierKeyPair.privateKey"));
    }

    @Test
    void qualifiedMode_singleStanzaStillGetsSectionHeading() {
        Element owner = type("com.example.KeyBundle");
        String out = GranularSections.render(List.of(privacy(owner, field(owner, "k"), "why")), true);

        assertTrue(out.startsWith("## PII / Privacy Guardrails\n"));
        assertTrue(out.contains("### com.example.KeyBundle.k\n"));
    }

    // ------------------------------------------------------------------
    // Pluralization
    // ------------------------------------------------------------------

    @Test
    void pluralizeRewritesOnlyTheSingularSelfReferences() {
        assertEquals("- **Rule**: These types are immutable. Never introduce non-final fields.",
            GranularSections.pluralize("- **Rule**: This type is immutable. Never introduce non-final fields."));
        assertEquals("These operations are idempotent. Calling them multiple times must produce the same"
                + " result as calling them once.",
            GranularSections.pluralize("This operation is idempotent. Calling it multiple times must produce"
                + " the same result as calling it once."));
        assertEquals("- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.",
            GranularSections.pluralize("- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths."),
            "a sentence with no singular self-reference must pass through untouched");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
