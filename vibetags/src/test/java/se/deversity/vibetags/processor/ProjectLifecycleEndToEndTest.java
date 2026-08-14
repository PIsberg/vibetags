package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.WriteCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's timeline, not one compile: the transitions a consumer's repository goes through
 * over months, in the order they happen. Day zero with nothing opted in, the steady state of
 * repeated no-change builds, a platform opted into long after the code was annotated, and a module
 * leaving the reactor.
 *
 * <p>{@code GuardrailLifecycleEndToEndTest} already covers the second compile — an annotation
 * edited, an annotation removed, a platform opted out. What it does not cover is the shape of the
 * project changing around the annotations, which is where the remaining silent failures live: the
 * build stays green, the files look plausible, and something that should have gone is still there.
 *
 * <p>Three of these tests pin a <em>limitation</em> rather than a feature. They are written as
 * passing tests of measured behaviour, in the same spirit as
 * {@code GuardrailLifecycleEndToEndTest#emptyingAModuleEntirely_…}: changing the behaviour should
 * be a deliberate act with a failing test to update, and the cost should be discoverable from the
 * suite rather than only from a bug report. Each one names what it costs the consumer.
 */
@Tag("e2e")
class ProjectLifecycleEndToEndTest {

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    // -----------------------------------------------------------------------
    // Day zero: the processor is on the build, nothing is opted in
    // -----------------------------------------------------------------------

    /**
     * The first build after adding VibeTags to a pom. File presence is the opt-in, so a project
     * that has created no signal file must come out of the build with no guardrail file — and the
     * footprint it does leave has to be exactly the three internal ones, because everything else
     * would be an unexplained untracked file in somebody's repository.
     */
    @Test
    void dayZero_nothingOptedIn_writesNoGuardrailFileAndOnlyItsOwnState(@TempDir Path root)
            throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.Ledger", locked("com.example", "Ledger", "Reconciliation is load-bearing"));
        h.compile();
        VibeTagsLogger.shutdown();

        for (Map.Entry<String, Path> service : ServiceRegistry.buildServiceFileMap(root).entrySet()) {
            assertFalse(Files.exists(service.getValue()),
                "the processor created " + service.getKey() + " (" + root.relativize(service.getValue())
                    + ") in a project that opted into nothing — file presence is the opt-in, and "
                    + "creating one activates a platform the developer never asked for");
        }

        List<String> footprint = filesUnder(root, false);
        footprint.removeIf(p -> p.startsWith("src/"));
        footprint.replaceAll(p -> p.startsWith(".vibetags-mod-") ? ".vibetags-mod-*" : p);
        assertEquals(List.of(".vibetags-cache", ".vibetags-mod-*", "vibetags.log"), footprint,
            "a non-participating project must see only VibeTags' own state files. Anything else "
                + "here is a new untracked file appearing in every consumer's repository");
    }

    // -----------------------------------------------------------------------
    // Steady state: building again, and again, with nothing changed
    // -----------------------------------------------------------------------

    /**
     * The commonest event in the whole lifecycle by a wide margin: a build where nothing about the
     * annotations changed. Every generated file must come out byte-identical <em>and untouched</em>
     * — a rewrite with identical content still moves the mtime, which is enough to make incremental
     * build tools redo downstream work and enough to make a file watcher fire on every compile.
     *
     * <p>Three builds rather than two: the write cache reaches its steady state on the second, so a
     * two-build test cannot tell "stable" from "stabilising".
     */
    @Test
    void steadyState_repeatedBuildsLeaveEveryGeneratedFileByteIdenticalAndUntouched(@TempDir Path root)
            throws Exception {
        ProcessorTestHarness first = new ProcessorTestHarness(root);
        first.addSource("com.example.Ledger", locked("com.example", "Ledger", "Reconciliation is load-bearing"));
        first.compile();
        VibeTagsLogger.shutdown();

        // A floor, not a pin: the point is that the comparison below covers the real output set and
        // not an empty map, which would make every assertEquals that follows vacuously true.
        Map<String, String> contentAfterFirst = generatedContent(root);
        assertTrue(contentAfterFirst.size() >= 20,
            "expected the default opt-in set to generate the whole platform spread, got "
                + contentAfterFirst.size() + ": " + contentAfterFirst.keySet());

        rebuildUnchanged(root);
        Map<String, Long> mtimeAfterSecond = generatedMtimes(root);
        assertEquals(contentAfterFirst, generatedContent(root),
            "a no-change build rewrote a generated file's content");

        rebuildUnchanged(root);
        assertEquals(contentAfterFirst, generatedContent(root),
            "the third no-change build rewrote a generated file's content");
        assertEquals(mtimeAfterSecond, generatedMtimes(root),
            "a no-change build touched a generated file whose content it did not change — "
                + "identical bytes at a new mtime still invalidate every downstream incremental task");
    }

    /**
     * The fingerprint short-circuit has to fire on an ordinary unchanged rebuild, and keep firing.
     *
     * <p>It could not, until the stamp it compares was fixed. The sidecar stamp hashes the
     * last-modified times of every {@code .vibetags-mod-*} file under the root; it was read at the
     * top of {@code generateFiles()}, <em>before</em> the round wrote its own sidecar, and that
     * pre-write value was what got stored. The round then rewrote its sidecar, so the stamp the
     * next round computed always included an mtime that moved after the stored value was taken.
     * The three conditions could never all hold and the generate phase ran in full every time.
     *
     * <p>{@code FingerprintShortCircuitTest} covers the branch itself, but cannot show a real build
     * reaching it: it engineers the one state in which the old stamp could match, by deleting every
     * sidecar and patching the stored stamp to {@code "0"}. This test is the other half of that
     * pair — no fixture surgery, just two ordinary rebuilds.
     *
     * <p>Two of them, deliberately. A skip that works once and not twice is the same defect one
     * build further out, and that is exactly what a stamp stored from the wrong moment produces.
     */
    @Test
    void steadyState_theFingerprintShortCircuitFiresOnAnUnchangedRebuild(
            @TempDir Path root) throws Exception {
        ProcessorTestHarness first = new ProcessorTestHarness(root);
        first.addSource("com.example.Ledger", locked("com.example", "Ledger", "Reconciliation is load-bearing"));
        first.compile();
        VibeTagsLogger.shutdown();

        String storedStamp = new WriteCache(root.resolve(".vibetags-cache")).getSidecarStamp();
        String stampOnDiskNow = Long.toHexString(ModuleSidecar.computeSidecarStamp(root));
        // Both have to be real values, or "they match" would be an artefact of one being absent.
        assertFalse(storedStamp == null || storedStamp.isBlank(),
            "the first build must have stored a sidecar stamp to compare against");
        assertNotEquals("0", stampOnDiskNow,
            "a sidecar must exist on disk after the first build, or the stamp is trivially zero");
        assertEquals(stampOnDiskNow, storedStamp,
            "the stored sidecar stamp must describe the sidecars as this round left them, or it "
                + "can never match what the next round reads and the short-circuit is dead code");

        Path sidecar = onlySidecar(root);
        long sidecarMtime = Files.getLastModifiedTime(sidecar).toMillis();

        rebuildUnchanged(root);

        assertEquals(sidecarMtime, Files.getLastModifiedTime(sidecar).toMillis(),
            "a build whose inputs are unchanged must short-circuit before the sidecar write. A "
                + "rewritten sidecar moves the stamp out from under the next build too, so the "
                + "skip could never happen twice running");

        rebuildUnchanged(root);
        assertEquals(sidecarMtime, Files.getLastModifiedTime(sidecar).toMillis(),
            "and it must keep short-circuiting: a skip that only works once is the same bug "
                + "one build further out");
    }

    // -----------------------------------------------------------------------
    // A platform opted into long after the code was annotated
    // -----------------------------------------------------------------------

    /**
     * LIMITATION, measured. Creating a new opt-in file at a reactor root fills it from the sidecars
     * of the modules that have compiled <em>since</em>, and a sidecar only carries bodies for the
     * services that were active when its module last ran. A module with no source change is not
     * recompiled, so its guardrails are absent from the new file — and nothing says so.
     *
     * <p>What it costs: the consumer opts a platform in, sees a plausible, well-formed file, and
     * ships a guardrail set missing every module that happened not to rebuild. A full reactor pass
     * heals it, which is why this survives — but the incremental build that produced the partial
     * file reported no warning, and check mode would have compared against it happily.
     */
    @Test
    void optingAPlatformInLater_carriesOnlyTheModulesThatHaveRecompiledSince(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));

        // Months later: the developer adopts a new AI reviewer and creates its opt-in file.
        Files.createFile(root.resolve(".pr_agent.toml"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // An incremental build: only the module whose sources changed compiles.
        List<String> warnings = compileModuleCapturingWarnings(root, "module-cli",
            "com.example.cli.Cli", locked("com.example.cli", "Cli", "CLI entry point, revised"));

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".pr_agent.toml")
                && w.contains("module-core") && w.contains("Run a full build")),
            "the build that produces the partial file has to say so, naming the file and the "
                + "module missing from it. Silence here is the whole defect: the output is "
                + "well-formed and looks complete. Warnings were:\n  " + String.join("\n  ", warnings));

        String partial = Files.readString(root.resolve(".pr_agent.toml"), StandardCharsets.UTF_8);
        assertTrue(partial.contains("com.example.cli.Cli"),
            "the module that recompiled must reach the newly opted-in file");
        assertFalse(partial.contains("com.example.core.IrNode"),
            "measured limitation: a module that did not recompile has no body for the new service "
                + "in its sidecar, so it is missing from the file. If this now fails, the partial-"
                + "file window has been closed — assert the module IS present instead");

        // The full reactor pass every consumer eventually runs is the documented remedy.
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));

        String healed = Files.readString(root.resolve(".pr_agent.toml"), StandardCharsets.UTF_8);
        assertTrue(healed.contains("com.example.core.IrNode") && healed.contains("com.example.cli.Cli"),
            "a full reactor pass must leave the newly opted-in file carrying every module");
    }

    // -----------------------------------------------------------------------
    // A module leaves the reactor
    // -----------------------------------------------------------------------

    /**
     * A module is deleted from the build. Its sidecar records the module path, so the next round
     * prunes it and the merged files forget the module — including the whole-file JSON that is
     * assembled from sidecars rather than from a marker region, which is the half that a
     * marker-based test would not notice.
     */
    @Test
    void moduleRemovedFromTheReactor_leavesEveryMergedFile(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve(".mentatconfig.json"));
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.cli.Cli"),
            "both modules must be present before one is removed");

        deleteRecursively(root.resolve("module-cli"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node, revised"));

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertFalse(claude.contains("com.example.cli.Cli"),
            "a module deleted from the reactor must leave the merged aggregate — its guardrails "
                + "describe code that is no longer in the repository");
        assertTrue(claude.contains("com.example.core.IrNode"), "the surviving module must stay");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-module-cli")),
            "the deleted module's sidecar must be pruned, or it keeps re-supplying the guardrails");

        String mentat = Files.readString(root.resolve(".mentatconfig.json"));
        assertFalse(mentat.contains("com.example.cli.Cli"),
            "the whole-file JSON is assembled from sidecars, so the removal has to reach it too");
        assertTrue(mentat.contains("com.example.core.IrNode"), "and must keep the surviving module");
    }

    /**
     * The same removal with nothing else changed, which is the case the fingerprint short-circuit
     * can skip past.
     *
     * <p>{@code moduleRemovedFromTheReactor_leavesEveryMergedFile} rebuilds the surviving module
     * with an edited reason, so its fingerprint differs and the round runs in full whatever the
     * short-circuit does. Deleting a module changes no annotation and moves no sidecar mtime, so a
     * rebuild that edits nothing is invisible to every other term of the skip condition — and a
     * skipped round never reaches the merge that prunes the stale sidecar. Without the staleness
     * term in that condition the deleted module's guardrails would survive until some unrelated
     * edit happened to change the fingerprint.
     */
    @Test
    void moduleRemovedFromTheReactor_isNoticedEvenWhenNothingElseChanges(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.cli.Cli"),
            "both modules must be present before one is removed");

        // module-core compiles again BEFORE the deletion, so the cache's fingerprint and stamp are
        // the ones its next round will present. Without this the last round belonged to a different
        // module, the fingerprint differed on module identity alone, and the round would run in
        // full for that reason rather than because anything noticed the deletion — which is how an
        // earlier version of this test passed with the staleness term deleted.
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));

        deleteRecursively(root.resolve("module-cli"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // Byte-identical to the compile just above: no annotation edited, nothing touched.
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertFalse(claude.contains("com.example.cli.Cli"),
            "a deleted module must leave the merged output on the next build even when that build "
                + "has nothing of its own to report. Skipping the round on an unchanged fingerprint "
                + "leaves the repository describing a module that is gone");
        assertTrue(claude.contains("com.example.core.IrNode"),
            "and the surviving module must still be there");
    }

    /**
     * The aggregates forget a deleted module the moment any sibling recompiles (above); its
     * granular rule files wait for a root compile. Both halves are asserted here, because the gap
     * between them is a real window in which a rule file contradicts every aggregate in the repo.
     *
     * <p>The delay is deliberate and is the jurisdiction rule from
     * <a href="https://github.com/PIsberg/vibetags/issues/383">issue #383</a>: a reactor module
     * round may not sweep the shared root, because it cannot tell an orphan from a sibling it has
     * not been shown — {@code .vibetags-mod-*} is gitignored, so on a cold clone the sidecars
     * appear one module at a time. Sweeping on that evidence deleted 256 tracked rule files on a
     * cold {@code mvn -pl core clean compile}, exit 0. A module owns its own directory and its own
     * mirrors, never the shared root.
     *
     * <p>None of that stops a departure being handled, though, and it used to: the sweep is not the
     * only way to name a file for removal. A sidecar whose module directory is gone <em>names</em>
     * the stems that module wrote, which is evidence rather than absence, so those files can be
     * removed from any round. The catch was ordering — {@code readAll} deletes the stale sidecar,
     * so the record was thrown away before anything could act on it. Reading the stems first is
     * the whole fix.
     *
     * <p>Two earlier versions of this test were wrong in opposite directions, which is why the
     * history is here. The first asserted the leftover file was permanent until deleted by hand; it
     * never tried a root compile, and the sweep guard's own comment says "surviving until the root
     * compiles". The second asserted it waits for that root compile, which was true but treated a
     * consequence of discarding the evidence as if it were the design.
     */
    @Test
    void moduleRemovedFromTheReactor_takesItsGranularRuleFilesWithIt(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));

        Path cliRule = root.resolve(".claude/rules/com-example-cli-Cli.md");
        assertTrue(Files.exists(cliRule), "the module's rule file must be generated first");

        deleteRecursively(root.resolve("module-cli"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node, revised"));

        assertFalse(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.cli.Cli"),
            "the aggregate must forget the deleted module");
        assertFalse(Files.exists(cliRule),
            "the departed module's rule file must go with its guardrails, on the same build. A "
                + "rule file loads by glob, so leaving it means the agent keeps reading a "
                + "guardrail about a class every aggregate in the repository agrees is gone");
        assertTrue(Files.exists(root.resolve(".claude/rules/com-example-core-IrNode.md")),
            "and the surviving module's rule file must not go with it");
    }

    /**
     * The bound on the rule above: only stems no surviving module claims are removed. A role file
     * written by several modules is shared, so a departure has to rewrite it rather than delete it,
     * or removing one module from a reactor takes its co-authors' guardrails with it.
     */
    @Test
    void aDepartedModuleDoesNotTakeASharedRoleFileWithIt(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.writeString(root.resolve(".vibetags-roles"),
            "shared = **/core/**, **/cli/**\n", StandardCharsets.UTF_8);

        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));

        Path roleFile = root.resolve(".claude/rules/shared.md");
        assertTrue(Files.exists(roleFile), "both modules must route into one shared role file");
        assertTrue(Files.readString(roleFile).contains("com.example.cli.Cli")
                && Files.readString(roleFile).contains("com.example.core.IrNode"),
            "and both must be in it before one module leaves:\n" + Files.readString(roleFile));

        deleteRecursively(root.resolve("module-cli"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node, revised"));

        assertTrue(Files.exists(roleFile),
            "a role file the surviving module still writes must never be deleted as an orphan");
        String role = Files.readString(roleFile);
        assertTrue(role.contains("com.example.core.IrNode"),
            "the surviving module's guardrail must still be in it:\n" + role);
        assertFalse(role.contains("com.example.cli.Cli"),
            "and the departed module's share must be gone from it:\n" + role);
    }

    /** A module directory renamed: merged once, under the new identity, with no ghost of the old. */
    @Test
    void moduleRenamed_isMergedOnceUnderItsNewIdentity(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));

        deleteRecursively(root.resolve("module-cli"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-frontend", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertEquals(1, occurrences(claude, "com.example.cli.Cli"),
            "a renamed module must be merged exactly once — the old sidecar is pruned by module "
                + "path, and a rename that kept both would state the same guardrail twice:\n" + claude);
        assertTrue(claude.contains("com.example.core.IrNode"), "the untouched module must survive");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-module-cli")),
            "the old module identity's sidecar must be pruned once its directory is gone");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Recompiles the same single-module sources, as an unchanged incremental build would. */
    private static void rebuildUnchanged(Path root) throws Exception {
        ProcessorTestHarness.awaitFilesystemTick(root);
        ProcessorTestHarness again = new ProcessorTestHarness(root, false);
        again.addSource("com.example.Ledger", locked("com.example", "Ledger", "Reconciliation is load-bearing"));
        again.compile();
        VibeTagsLogger.shutdown();
    }

    /** Every generated file's content, keyed by path relative to the root. */
    private static Map<String, String> generatedContent(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String rel : filesUnder(root, true)) {
            out.put(rel, Files.readString(root.resolve(rel), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Every generated file's last-modified time, keyed by path relative to the root. */
    private static Map<String, Long> generatedMtimes(Path root) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        for (String rel : filesUnder(root, true)) {
            out.put(rel, Files.getLastModifiedTime(root.resolve(rel)).toMillis());
        }
        return out;
    }

    /**
     * Sorted paths of every regular file under {@code root}, relative and slash-separated.
     * {@code generatedOnly} drops the sources and VibeTags' own state files, leaving the guardrail
     * output.
     */
    private static List<String> filesUnder(Path root, boolean generatedOnly) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (generatedOnly && (rel.startsWith("src/")
                        || rel.equals(".vibetags-cache")
                        || rel.startsWith(".vibetags-mod-")
                        || rel.equals("vibetags.log"))) {
                    continue;
                }
                out.add(rel);
            }
        }
        return out;
    }

    private static Path onlySidecar(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            List<Path> sidecars = files
                .filter(p -> String.valueOf(p.getFileName()).startsWith(".vibetags-mod-"))
                .toList();
            assertEquals(1, sidecars.size(), "expected exactly one sidecar in a single-module build");
            return sidecars.get(0);
        }
    }

    private static int occurrences(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String locked(String pkg, String type, String reason) {
        return "package " + pkg + ";\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + reason + "\")\n"
            + "public class " + type + " {}\n";
    }

    /** As {@link #compileModule}, returning the WARNING diagnostics the round emitted. */
    private static List<String> compileModuleCapturingWarnings(Path root, String module, String fqn,
                                                               String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.WARNING
                || d.getKind() == javax.tools.Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

    /** One module's compile into the shared reactor root, as a reactor pass would do it. */
    private static void compileModule(Path root, String module, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
        VibeTagsLogger.shutdown();
    }
}
