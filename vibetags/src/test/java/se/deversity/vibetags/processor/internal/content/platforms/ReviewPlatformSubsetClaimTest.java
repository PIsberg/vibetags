package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The review platforms carry a declared subset of the annotations, and the declaration is checked
 * against the formatters rather than counted by hand
 * (<a href="https://github.com/PIsberg/vibetags/issues/547">issue #547</a>).
 *
 * <p>Sweep, Mentat and Plandex are code-review tools, not editors, and their formatters carry an
 * arm for only some annotations. Nothing said which: the set grew by accretion as the
 * {@code add-annotation} skill wired each new annotation through while older ones were never
 * revisited, so {@code @AIKeepInSync} reached Sweep and {@code @AIThreadSafe} did not, with no
 * document or test to say whether that was a decision. {@code RendererDropsNoSupportedAnnotationTest}
 * guards the other half (a renderer never drops an annotation its formatters support); this test
 * derives each platform's supported set from the formatters and holds PLATFORMS.md to it, so an
 * arm added or removed without the doc line changing fails the build, in either direction.
 */
@DisplayName("PLATFORMS.md states the formatter-derived subset each review platform carries")
class ReviewPlatformSubsetClaimTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** {@code - **Sweep** (`sweep.yaml`) carries: `@AIAudit`, `@AILocked`, ...}. */
    private static final Pattern CLAIM = Pattern.compile("^- \\*\\*(Sweep|Mentat|Plandex)\\*\\* .*carries: (.*)$");
    private static final Pattern NAME = Pattern.compile("`@(AI[A-Za-z]+)`");

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Platform.class, names = {"SWEEP", "MENTAT", "PLANDEX"})
    void docsNameExactlyTheAnnotationsTheFormattersRenderThere(Platform platform) throws IOException {
        Set<String> derived = new TreeSet<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (!formatterOutput(type, platform).isBlank()) {
                derived.add(type.getSimpleName());
            }
        }

        String label = platform.name().charAt(0) + platform.name().substring(1).toLowerCase(java.util.Locale.ROOT);
        Set<String> documented = new TreeSet<>();
        String claim = Files.readAllLines(REPO_ROOT.resolve("docs/PLATFORMS.md"), StandardCharsets.UTF_8)
            .stream()
            .filter(line -> {
                Matcher m = CLAIM.matcher(line);
                return m.matches() && m.group(1).equals(label);
            })
            .findFirst()
            .orElseGet(() -> fail("docs/PLATFORMS.md no longer carries the line \"- **" + label
                + "** (...) carries: ...\"; the formatters render these there: " + derived));
        Matcher names = NAME.matcher(CLAIM.matcher(claim).replaceAll("$2"));
        while (names.find()) {
            documented.add(names.group(1));
        }

        assertEquals(derived, documented,
            "docs/PLATFORMS.md must name exactly the annotations whose formatter has a " + platform
                + " arm. Formatters render: " + derived + "; the doc says: " + documented
                + ". Change the doc line and the formatter in the same commit.");
    }

    /** What {@code type}'s formatter writes for {@code platform}; blank means "not carried there". */
    private static String formatterOutput(Class<? extends Annotation> type, Platform platform) {
        String name = "se.deversity.vibetags.processor.internal.content.annotations."
            + type.getSimpleName() + "Formatter";
        try {
            AnnotationFormatter formatter = (AnnotationFormatter)
                Class.forName(name).getDeclaredConstructor().newInstance();
            TaggedElement element = GuardrailModels.element(type);
            StringBuilder sb = new StringBuilder();
            formatter.format(element, sb, platform);
            return sb.toString();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no formatter resolved for " + type.getSimpleName(), e);
        }
    }
}
