package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * "Renders as another platform" is one property, resolved in one place
 * (<a href="https://github.com/PIsberg/vibetags/issues/764">issue #764</a>).
 *
 * <p>It used to be a pair of {@code case} labels in every formatter and a second registration in
 * {@code SectionCatalog}, and the one time half of a pair was missing, {@code GEMINI.md} printed the
 * default headings with nothing failing (#721). These cases pin the mechanism rather than the
 * wording: a formatter is only ever handed a family, so it cannot tell an alias from the platform
 * it renders as, however its switch is spelled. The wording itself is
 * {@code GeminiAliasHeadingParityTest}'s business.
 */
class PlatformAliasTest {

    /** The aliases that exist today. A new one is added here on purpose, with its target. */
    private static final Set<Platform> ALIASES = EnumSet.of(Platform.GEMINI_MD);

    @Test
    void geminiMdRendersAsGemini() {
        assertSame(Platform.GEMINI, Platform.GEMINI_MD.rendersAs());
    }

    @Test
    void everyOtherPlatformRendersAsItself_andNoAliasChains() {
        for (Platform p : Platform.values()) {
            if (!ALIASES.contains(p)) {
                assertSame(p, p.rendersAs(), p + " is not a declared alias");
            }
            assertSame(p.rendersAs(), p.rendersAs().rendersAs(),
                p + " renders as an alias of something else; one hop only");
        }
    }

    @Test
    void aFormatterIsHandedTheFamily_neverTheAlias() {
        List<Platform> seen = new ArrayList<>();
        AnnotationFormatter recording = (element, sb, family) -> seen.add(family);

        for (Platform p : Platform.values()) {
            recording.format(null, new StringBuilder(), p);
        }

        List<Platform> expected = new ArrayList<>();
        for (Platform p : Platform.values()) {
            expected.add(p.rendersAs());
        }
        assertEquals(expected, seen);
    }
}
