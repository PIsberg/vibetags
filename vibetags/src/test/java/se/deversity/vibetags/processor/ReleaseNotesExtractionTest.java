package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Release notes are extracted by one script, and that script refuses to emit a truncated file.
 *
 * <p>The extraction used to be an inline awk range, in two places. An awk range tests its end
 * pattern against the record that opened it, and the end pattern here matches the section heading
 * itself, so the range opened and closed on one line and the command emitted the heading alone.
 *
 * <p>What makes it worth a test rather than a comment is the direction it fails in. The output is
 * not empty and not an error: it is a file containing exactly the right release title with the
 * entire body missing. {@code gh release create --notes-file} accepts it, and by the time anyone
 * reads the release page the tag exists and the Maven Central deploy has fired. A tag that
 * publishes cannot be re-cut.
 *
 * <p>It has now happened twice. {@code docs/RELEASING.md} shipped the range form, produced a
 * one-line release note for 1.2.3, and was corrected with a warning against it. The release skill
 * kept its own copy of the broken command and produced the same one-line extract again at
 * the next release cut from it (#619), with that warning sitting unread in the other file.
 * Two copies of a subtle command was the defect, so the invariant pinned here is that there
 * is exactly one implementation and no document inlines its own.
 */
@DisplayName("Release notes come from one script that refuses to truncate")
class ReleaseNotesExtractionTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String SCRIPT = "tools/release-notes.sh";

    @Test
    @DisplayName("no document inlines its own extraction; both call the shared script")
    void theExtractionIsNotInlinedInAnyRunnableBlock() throws IOException {
        assertTrue(Files.isRegularFile(REPO_ROOT.resolve(SCRIPT)),
            SCRIPT + " is missing, so both documents point at a script that does not exist.");

        for (String doc : List.of(".claude/skills/release/SKILL.md", "docs/RELEASING.md")) {
            Path path = REPO_ROOT.resolve(doc);
            assertTrue(Files.isRegularFile(path), doc + " is missing");
            String text = Files.readString(path, StandardCharsets.UTF_8);

            assertTrue(text.contains(SCRIPT),
                doc + " no longer calls " + SCRIPT + ". If the extraction moved, point this test "
                    + "and both documents at wherever it went, rather than letting each grow its "
                    + "own copy again.");

            // Only runnable blocks count. Both documents quote the broken awk range on purpose, in
            // prose, to explain why it must not be used, and that prose has to stay quotable.
            for (String block : shellBlocks(text)) {
                boolean inlinesIt = block.contains("awk") && block.contains("## [");
                assertTrue(!inlinesIt,
                    doc + " has a runnable block that extracts the changelog section itself:"
                        + System.lineSeparator() + block + System.lineSeparator()
                        + "That is how the same one-line-release-note bug shipped twice. Call "
                        + SCRIPT + " instead.");
            }
        }
    }

    @Test
    @DisplayName("the script takes the whole section and stops at the next release")
    void theWholeSectionIsExtractedAndTheNextOneIsNot() throws Exception {
        Result run = runScript(syntheticChangelog(), "9.9.9");
        assertEquals(0, run.exit(), run.err());

        assertTrue(run.out().contains("## [9.9.9]"),
            "the heading opens the notes, as every release body since 1.2.x does:"
                + System.lineSeparator() + run.out());
        assertTrue(run.out().contains("first body line"),
            "the section body is missing:" + System.lineSeparator() + run.out());
        assertTrue(run.out().contains("last body line"),
            "the section body is cut short:" + System.lineSeparator() + run.out());
        assertTrue(!run.out().contains("9.9.8"),
            "the next release leaked into these notes:" + System.lineSeparator() + run.out());

        // The bug itself: a heading, and nothing underneath it.
        assertTrue(run.out().lines().count() > 4,
            "the extract is one line again, which is the #619 shape exactly:"
                + System.lineSeparator() + run.out());
    }

    @Test
    @DisplayName("a missing or empty section is refused rather than published")
    void aTruncatedExtractIsRefused() throws Exception {
        Result absent = runScript(syntheticChangelog(), "7.7.7");

        assertNotEquals(0, absent.exit(),
            "a version with no section in the changelog produced notes and exit 0. What reads "
                + "that file next is gh release create, which tags main and fires the Central "
                + "deploy, so the refusal has to happen here:"
                + System.lineSeparator() + absent.out());
        assertTrue(absent.err().contains("Refusing"),
            "the refusal has to say why, since its caller is one step from publishing: "
                + absent.err());
    }

    /** A changelog with one real section, a following section, and nothing else. */
    private static Path syntheticChangelog() throws IOException {
        Path file = Files.createTempDirectory("release-notes").resolve("CHANGELOG.md");
        Files.writeString(file, String.join(System.lineSeparator(),
            "# Changelog",
            "",
            "## [9.9.9] - 2026-01-02",
            "",
            "### Fixed",
            "",
            "- first body line",
            "- middle body line",
            "- last body line",
            "",
            "## [9.9.8] - 2026-01-01",
            "",
            "### Fixed",
            "",
            "- belongs to the previous release",
            ""), StandardCharsets.UTF_8);
        return file;
    }

    private static Result runScript(Path changelog, String version) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", SCRIPT, version);
        pb.directory(REPO_ROOT.toFile());
        pb.environment().put("CHANGELOG", changelog.toAbsolutePath().toString());
        Process p;
        try {
            p = pb.start();
        } catch (IOException noShell) {
            assumeTrue(false, "no sh on PATH; the Linux CI job runs this");
            return new Result(0, "", "");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out, err);
    }

    private record Result(int exit, String out, String err) { }

    /** The contents of every fenced shell block, which are the lines a reader actually runs. */
    private static List<String> shellBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            if (current == null) {
                if (trimmed.equals("```bash") || trimmed.equals("```sh")) {
                    current = new StringBuilder();
                }
            } else if (trimmed.equals("```")) {
                blocks.add(current.toString());
                current = null;
            } else {
                current.append(line).append(System.lineSeparator());
            }
        }
        return blocks;
    }
}
