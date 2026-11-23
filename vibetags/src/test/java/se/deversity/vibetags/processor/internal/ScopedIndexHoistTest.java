package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scoped-rules index of a reactor root's Markdown aggregate, written once after the module
 * blocks instead of once per module (#930), compared whole through {@link ModuleSidecar#mergeFor}.
 *
 * <p>The hoist applies only to sections of the shape {@code GranularIndexSection} renders: the
 * heading on a line of its own, one preamble line, then {@code - } lines up to the next {@code ## }
 * heading. Anything else, and fewer than two such sections, leaves every module block as it was.
 * Until this test only whole multi-module builds reached the hoist, which see the hoisted case and
 * not the ones it must decline. Written before the hoist moved out of {@code ModuleSidecar}.
 */
class ScopedIndexHoistTest {

    private static final String PRE = "Detailed guardrails live in scoped files:";

    private static ModuleSidecar module(String id, String body) {
        ModuleSidecar sidecar = new ModuleSidecar(id, id);
        sidecar.putBody("claude", body);
        return sidecar;
    }

    private static String merge(String a, String b) {
        return ModuleSidecar.mergeFor("claude", List.of(module("a", a), module("b", b)), true);
    }

    private static String stacked(String a, String b) {
        return "<!-- VIBETAGS-MODULE: a -->\n" + a + "\n<!-- VIBETAGS-MODULE-END: a -->\n"
            + "<!-- VIBETAGS-MODULE: b -->\n" + b + "\n<!-- VIBETAGS-MODULE-END: b -->";
    }

    @Test
    void twoSectionsBecomeOneAfterTheModuleBlocks() {
        String a = "# Rules A\n\n- r1\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`\n- `shared`";
        String b = "# Rules B\n\n## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`\n- `shared`\n\n## After\ntext";

        assertEquals(stacked("# Rules A\n\n- r1", "# Rules B\n\n## After\ntext")
            + "\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`\n- `shared`\n- `b.Y`", merge(a, b));
    }

    @Test
    void differentPreamblesAreLeftInPlace() {
        String a = "# A\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`";
        String b = "# B\n\n## Scoped Rules Index\nAnother preamble.\n\n- `b.Y`";

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void oneSectionAloneIsLeftInPlace() {
        String a = "# A\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`";
        String b = "# B\n\n- r2";

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void aLineThatIsNeitherPreambleNorItemLeavesEverySectionInPlace() {
        String a = "# A\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`\nA stray sentence.";
        String b = "# B\n\n## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`";

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void aSectionWithNoPreambleLeavesEverySectionInPlace() {
        String a = "# A\n\n## Scoped Rules Index\n- `a.X`";
        String b = "# B\n\n## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`";

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void sectionsWithNoItemsAreLeftInPlace() {
        String a = "# A\n\n## Scoped Rules Index\n" + PRE;
        String b = "# B\n\n## Scoped Rules Index\n" + PRE;

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void theHeadingCountsOnlyOnALineOfItsOwn() {
        String a = "# A\n\nSee ## Scoped Rules Index below.\n\n## Scoped Rules Index (old)\n" + PRE + "\n\n- `a.X`";
        String b = "# B\n\n## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`";

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void theHeadingMustAlsoStartItsLine() {
        String a = "# A\n\nSee ## Scoped Rules Index\n" + PRE + "\n\n- `a.X`";
        String b = "# B\n\n## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`";

        assertEquals(stacked(a, b), merge(a, b));
    }

    @Test
    void aSubHeadingInsideTheSectionDoesNotEndIt() {
        String a = "# A\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`\n### Not a section\n- `a.Z`";
        String b = "# B\n\n## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`";

        assertEquals(stacked(a, b), merge(a, b),
            "a ### line is not the next ## heading, so it is a stray line inside the section");
    }

    @Test
    void aSectionInTheMiddleOfABlockClosesTheGapItLeaves() {
        String a = "# A\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`\n\n\n## Tail\nend";
        String b = "## Scoped Rules Index\n" + PRE + "\n\n- `b.Y`";

        assertEquals(stacked("# A\n\n## Tail\nend", "")
            + "\n\n## Scoped Rules Index\n" + PRE + "\n\n- `a.X`\n- `b.Y`", merge(a, b));
    }
}
