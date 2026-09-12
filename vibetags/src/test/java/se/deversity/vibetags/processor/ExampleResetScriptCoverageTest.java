package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code examples/basic/reset-ai-files.sh} must clear every output the example actually opts into.
 *
 * <p>The script empties files and deletes granular rule files so a following clean compile proves
 * the processor regenerates everything from scratch. It does that from two hand-maintained lists:
 * an {@code AI_FILES} array and a {@code for dir in ...} loop. Both are copies of a subset of
 * {@link ServiceRegistry}, and a copy drifts.
 *
 * <p>It had already drifted. {@code .kiro/steering} was opted in under {@code examples/basic} from
 * v0.9.7 and never added to the directory loop, so a reset left its 32 generated files in place.
 * That failure is invisible in the worst way: a rule file for a class whose annotation was since
 * removed survives the reset, the following compile does not rewrite it, and the stale file reads
 * as current output. The reset then proves less than it appears to, which is the entire point of
 * running it.
 *
 * <p>So the lists are derived here rather than trusted. Scope is what the example has opted into
 * on disk, not everything {@code ServiceRegistry} can name: file presence is the opt-in, so a path
 * the example does not create is a path the script has no business clearing.
 */
@DisplayName("The example reset script clears every output the example opts into")
class ExampleResetScriptCoverageTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /**
     * Paths the script deliberately does not clear, each with the reason it cannot be an omission.
     * An entry here is a decision; anything else missing is drift.
     */
    private static final Map<String, String> DELIBERATE_OMISSIONS = Map.of(
        "AGENTS.md",
        "the sole-file fallback rule means VibeTags never regenerates AGENTS.md while this example "
            + "ships other AI config files, so clearing it would blank a pointer permanently. The "
            + "script says so at the AI_FILES entry it is missing from.",
        "greptile.json",
        "the file is the user's review configuration and VibeTags owns only a span inside two of "
            + "its values. Emptying it would erase the hand-set fields the example exists to show "
            + "surviving, and nothing stale can outlive a reset, because every compile replaces the "
            + "span whole.");

    @Test
    void everyOptedInOutputIsClearedByTheResetScript() throws IOException {
        Path example = REPO_ROOT.resolve("examples/basic");
        Path script = example.resolve("reset-ai-files.sh");
        assumeTrue(Files.isRegularFile(script), "repo layout not reachable; skipping");

        String text = Files.readString(script, StandardCharsets.UTF_8);
        List<String> missingFiles = new ArrayList<>();
        List<String> missingDirectories = new ArrayList<>();

        for (Map.Entry<String, Path> service : ServiceRegistry.buildServiceFileMap(example).entrySet()) {
            Path target = service.getValue();
            if (!Files.exists(target)) {
                continue; // not opted into by this example; nothing for the script to reset
            }
            String relative = example.relativize(target).toString().replace('\\', '/');
            if (DELIBERATE_OMISSIONS.containsKey(relative)) {
                continue;
            }
            // The script quotes every entry, in both the array and the loop, so requiring the
            // quotes keeps a substring of a longer path from passing as a match: ".claude/rules"
            // must not be satisfied by ".claude/rules/nested" appearing somewhere else.
            if (!text.contains("\"" + relative + "\"")) {
                (Files.isDirectory(target) ? missingDirectories : missingFiles)
                    .add(relative + "  (service key: " + service.getKey() + ")");
            }
        }

        assertTrue(missingFiles.isEmpty() && missingDirectories.isEmpty(),
            "examples/basic/reset-ai-files.sh does not clear outputs the example opts into, so a "
                + "reset leaves stale generated content behind and the clean-compile check that "
                + "follows it proves less than it claims.\n"
                + "  Add to the AI_FILES array: " + missingFiles + "\n"
                + "  Add to the granular 'for dir in' loop: " + missingDirectories + "\n"
                + "If an omission is deliberate, record it in DELIBERATE_OMISSIONS with the reason "
                + "rather than deleting this assertion.");
    }

    /**
     * The reverse direction. An entry naming a path no service writes any more is not harmless: it
     * is a line that reads as coverage, survives the platform being renamed or dropped, and quietly
     * clears nothing.
     */
    @Test
    void everyPathTheScriptNamesIsStillWrittenBySomeService() throws IOException {
        Path example = REPO_ROOT.resolve("examples/basic");
        Path script = example.resolve("reset-ai-files.sh");
        assumeTrue(Files.isRegularFile(script), "repo layout not reachable; skipping");

        Set<String> known = new java.util.LinkedHashSet<>();
        for (Path target : ServiceRegistry.buildServiceFileMap(example).values()) {
            known.add(example.relativize(target).toString().replace('\\', '/'));
        }
        // The cache is not a service output but is legitimately removed by the script.
        known.add(".vibetags-cache");

        List<String> unknown = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"([.A-Za-z0-9][^\"\\n]*/[^\"\\n]+|\\.[A-Za-z0-9][^\"\\n/]*|[A-Z][A-Za-z]*\\.md)\"")
            .matcher(stripComments(Files.readString(script, StandardCharsets.UTF_8)));
        while (m.find()) {
            String candidate = m.group(1);
            if (candidate.startsWith("$") || candidate.contains("*") || candidate.contains("{")) {
                continue; // shell expansion, not a literal path
            }
            if (!known.contains(candidate)) {
                unknown.add(candidate);
            }
        }

        assertTrue(unknown.isEmpty(),
            "examples/basic/reset-ai-files.sh names paths no service in ServiceRegistry writes. "
                + "Each is a line that looks like coverage and clears nothing: " + unknown);
    }

    /** Comments carry example paths and prose; only executable lines are checked. */
    private static String stripComments(String script) {
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("#")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
