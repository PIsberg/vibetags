package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GEMINI} and {@code GEMINI_MD} say the same thing, because they are one wording for two
 * files.
 *
 * <p>This is the shape of <a href="https://github.com/PIsberg/vibetags/issues/721">issue #721</a>.
 * {@code SectionCatalog} held Gemini's heading overrides under {@code Platform.GEMINI} only.
 * {@code GEMINI.md}'s full render looked them up under that platform and was fine; its collapsed
 * render asks for the platform it is actually writing, {@code GEMINI_MD}, found nothing registered,
 * and printed the shared default headings instead. The fix was to register the same map twice, and
 * {@code SectionCatalog} does:
 *
 * <pre>
 *     OVERRIDES.put(Platform.GEMINI, geminiOverrides);
 *     OVERRIDES.put(Platform.GEMINI_MD, geminiOverrides);
 * </pre>
 *
 * <p>Nothing held that pair together. <a
 * href="https://github.com/PIsberg/vibetags/issues/764">Issue #764</a> is about the same duplication
 * across the formatters and says "the next alias repeats #721". This closes the door on that for
 * the pair that has already been through it once.
 *
 * <p>Deliberately behavioural rather than a scan of {@code case} labels. A test that greps for
 * {@code case GEMINI_MD:} passes the moment somebody writes the label and says nothing about what it
 * renders; this one fails whenever the two platforms would print different headings, however the
 * dispatch is spelled. It therefore survives #764's refactor, which is the point: the guard should
 * outlive the code shape it was written against.
 */
@DisplayName("The two Gemini platforms render identical section headings")
class GeminiAliasHeadingParityTest {

    @Test
    @DisplayName("every section heading is the same for GEMINI and GEMINI_MD")
    void everySectionHeadingMatchesBetweenTheTwoGeminiPlatforms() {
        List<String> divergent = new ArrayList<>();

        for (SectionCatalog.Key key : SectionCatalog.Key.values()) {
            String gemini = SectionCatalog.header(Platform.GEMINI, key);
            String geminiMd = SectionCatalog.header(Platform.GEMINI_MD, key);
            if (!java.util.Objects.equals(gemini, geminiMd)) {
                divergent.add(key + ":\n    GEMINI    = " + gemini + "\n    GEMINI_MD = " + geminiMd);
            }
        }

        assertEquals(List.of(), divergent,
            "GEMINI and GEMINI_MD are one wording for two files, and these sections disagree:\n"
                + String.join("\n", divergent)
                + "\nThat is issue #721 exactly: GEMINI.md's collapsed render asks for GEMINI_MD, "
                + "and anything registered under only one of the two prints the shared defaults "
                + "instead of Gemini's wording. Register it under both.");
    }

    /** The headerless decision has to match too, or one file loses a section the other keeps. */
    @Test
    @DisplayName("and the headerless sections are the same set")
    void headerlessSectionsMatchBetweenTheTwoGeminiPlatforms() {
        List<String> divergent = new ArrayList<>();

        for (SectionCatalog.Key key : SectionCatalog.Key.values()) {
            boolean gemini = SectionCatalog.isHeaderless(Platform.GEMINI, key);
            boolean geminiMd = SectionCatalog.isHeaderless(Platform.GEMINI_MD, key);
            if (gemini != geminiMd) {
                divergent.add(key + " (GEMINI=" + gemini + ", GEMINI_MD=" + geminiMd + ")");
            }
        }

        assertEquals(List.of(), divergent,
            "one Gemini file would drop a section heading the other keeps: " + divergent);
    }

    /**
     * The catalogue has to be non-trivial for the comparison above to mean anything. Two platforms
     * that both resolve to {@code null} everywhere agree perfectly and prove nothing.
     */
    @Test
    @DisplayName("the comparison is not vacuous")
    void geminiActuallyOverridesSomeHeadings() {
        long overridden = java.util.Arrays.stream(SectionCatalog.Key.values())
            .filter(key -> SectionCatalog.header(Platform.GEMINI, key) != null)
            .count();

        assertTrue(overridden > 0,
            "SectionCatalog returns no heading at all for GEMINI, so the parity assertions above "
                + "compare nothing against nothing and would pass however badly the two platforms "
                + "diverged.");
    }
}
