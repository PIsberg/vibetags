package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The docs' aggregate↔granular pair claim is derived from the code, not counted by hand.
 *
 * <p>PLATFORMS.md and LOAD-BEARING.md both state how many platforms have an aggregate file with a
 * granular sibling directory, and list the pairs. That count sat at "Four" while
 * {@code GranularIndexSection.governingGranularKey} already gated a fifth pair
 * ({@code GEMINI.md} ↔ {@code .gemini/rules/}, #320) — the drift went unnoticed until the RC10
 * GA dogfooding sweep collapsed a consumer's GEMINI.md and the docs disagreed with what had just
 * visibly happened. This test derives the number word and the directory list from the same switch
 * the renderer uses, so adding a sixth pair without updating both docs fails the build.
 */
@DisplayName("Docs state the code-derived aggregate-granular pairs")
class DocsGranularPairsClaimTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Map<Integer, String> NUMBER_WORDS = Map.of(
        2, "Two", 3, "Three", 4, "Four", 5, "Five", 6, "Six", 7, "Seven", 8, "Eight", 9, "Nine");

    @Test
    void docsMatchDerivedPairs() throws IOException {
        Set<String> keys = new TreeSet<>();
        Set<String> dirs = new TreeSet<>();
        for (Platform platform : Platform.values()) {
            String key = GranularIndexSection.governingGranularKey(platform);
            if (key != null && keys.add(key)) {
                String dir = GranularIndexSection.scopedDir(platform);
                assertNotNull(dir, "Platform " + platform + " has granular key " + key
                    + " but no scoped directory — the two switches drifted");
                dirs.add(dir);
            }
        }
        String word = NUMBER_WORDS.get(keys.size());
        assertNotNull(word, "Extend NUMBER_WORDS: the code now defines " + keys.size() + " pairs");

        for (String rel : List.of("docs/PLATFORMS.md", "docs/LOAD-BEARING.md")) {
            String claimLine = Files.readAllLines(REPO_ROOT.resolve(rel), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.contains("platforms have both"))
                .findFirst()
                .orElseGet(() -> fail(rel + " no longer carries the pairs claim"
                    + " (\"platforms have both\" not found)"));

            assertTrue(claimLine.contains(word + " platforms have both"),
                rel + " must say \"" + word + " platforms have both\" (code defines "
                    + keys.size() + " pairs: " + keys + "). Line: " + claimLine);
            for (String dir : dirs) {
                assertTrue(claimLine.contains("`" + dir + "/`"),
                    rel + " pairs claim must name `" + dir + "/`. Line: " + claimLine);
            }
        }
    }
}
