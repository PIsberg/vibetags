package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.ModuleSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies multi-module aggregation: when two modules share the same VibeTags root (via
 * {@code vibetags.root}), both modules' annotations survive into the shared output files.
 * Previously only the last module to compile was represented (last-writer-wins bug).
 */
class MultiModuleAggregationTest {

    // -----------------------------------------------------------------------
    // ModuleSidecar unit tests
    // -----------------------------------------------------------------------

    @Test
    void computeModuleId_rootModule_returnsRootSentinel(@TempDir Path root) {
        String id = ModuleSidecar.computeModuleId(root, root);
        assertEquals("_root_", id);
    }

    @Test
    void computeModuleId_childModule_returnsRelativePath(@TempDir Path root) {
        Path child = root.resolve("module-graph");
        String id = ModuleSidecar.computeModuleId(child, root);
        assertEquals("module-graph", id);
    }

    @Test
    void computeModuleId_nestedModule_replacesPathSeparator(@TempDir Path root) {
        Path child = root.resolve("a").resolve("b");
        String id = ModuleSidecar.computeModuleId(child, root);
        assertEquals("a_b", id);
    }

    @Test
    void sidecar_saveAndLoad_roundTrips(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("module-graph")); // satisfy staleness check
        ModuleSidecar orig = new ModuleSidecar("module-graph", "module-graph");
        orig.putBody("cursor", "## LOCKED FILES\n* `com.example.Node`");
        orig.putBody("claude", "<locked_files><file path=\"com.example.Node\"/></locked_files>");
        orig.save(root);

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertEquals(1, loaded.size());
        ModuleSidecar s = loaded.get(0);
        assertEquals("module-graph", s.getModuleId());
        assertEquals("## LOCKED FILES\n* `com.example.Node`", s.getBodies().get("cursor"));
        assertEquals("<locked_files><file path=\"com.example.Node\"/></locked_files>",
                s.getBodies().get("claude"));
    }

    @Test
    void sidecar_emptyBody_notStored(@TempDir Path root) throws IOException {
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        s.putBody("cursor", "   ");   // blank — should be ignored
        s.putBody("claude", "content");
        s.save(root);

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).getBodies().get("cursor"));
        assertNotNull(loaded.get(0).getBodies().get("claude"));
    }

    @Test
    void sidecar_stalePruned_whenModuleDirMissing(@TempDir Path root) throws IOException {
        // Write a sidecar claiming to be from "ghost-module" (directory doesn't exist)
        ModuleSidecar stale = new ModuleSidecar("ghost_module", "ghost-module");
        stale.putBody("cursor", "stale content");
        stale.save(root);

        // readAll() should prune it because root/ghost-module doesn't exist
        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertTrue(loaded.isEmpty(), "Stale sidecar from non-existent module directory should be pruned");
    }

    @Test
    void sidecar_rootModule_notPruned(@TempDir Path root) throws IOException {
        // Root module (modulePath="") is never pruned even though there's no sub-directory
        ModuleSidecar rootMod = new ModuleSidecar("_root_", "");
        rootMod.putBody("cursor", "root content");
        rootMod.save(root);

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertEquals(1, loaded.size());
    }

    // -----------------------------------------------------------------------
    // mergeFor() tests
    // -----------------------------------------------------------------------

    @Test
    void mergeFor_singleModule_returnsBodyAsIs() throws IOException {
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        s.putBody("cursor", "## LOCKED FILES\n* `com.example.Foo`");

        String merged = ModuleSidecar.mergeFor("cursor", List.of(s), false);
        assertEquals("## LOCKED FILES\n* `com.example.Foo`", merged);
    }

    @Test
    void mergeFor_twoModules_wrapsInSubMarkers_hashStyle() throws IOException {
        ModuleSidecar graph = new ModuleSidecar("module-graph", "module-graph");
        graph.putBody("cursor", "## LOCKED FILES\n* `com.example.Node`");

        ModuleSidecar cli = new ModuleSidecar("module-cli", "module-cli");
        cli.putBody("cursor", "## AUDIT\n* `com.example.KartaCli`");

        String merged = ModuleSidecar.mergeFor("cursor", List.of(graph, cli), false);

        assertTrue(merged.contains("# VIBETAGS-MODULE: module-graph"), "Should start graph sub-marker");
        assertTrue(merged.contains("# VIBETAGS-MODULE-END: module-graph"), "Should end graph sub-marker");
        assertTrue(merged.contains("# VIBETAGS-MODULE: module-cli"), "Should start cli sub-marker");
        assertTrue(merged.contains("# VIBETAGS-MODULE-END: module-cli"), "Should end cli sub-marker");
        assertTrue(merged.contains("com.example.Node"), "Graph annotation should be present");
        assertTrue(merged.contains("com.example.KartaCli"), "CLI annotation should be present");
    }

    @Test
    void mergeFor_twoModules_wrapsInSubMarkers_htmlStyle() {
        ModuleSidecar graph = new ModuleSidecar("module-graph", "module-graph");
        graph.putBody("claude", "<locked_files><file path=\"com.example.Node\"/></locked_files>");

        ModuleSidecar cli = new ModuleSidecar("module-cli", "module-cli");
        cli.putBody("claude", "<audit_requirements><file path=\"com.example.KartaCli\"/></audit_requirements>");

        String merged = ModuleSidecar.mergeFor("claude", List.of(graph, cli), true);

        assertTrue(merged.contains("<!-- VIBETAGS-MODULE: module-graph -->"));
        assertTrue(merged.contains("<!-- VIBETAGS-MODULE-END: module-graph -->"));
        assertTrue(merged.contains("<!-- VIBETAGS-MODULE: module-cli -->"));
        assertTrue(merged.contains("com.example.Node"));
        assertTrue(merged.contains("com.example.KartaCli"));
    }

    @Test
    void mergeFor_skipsModulesWithNoBodyForService() {
        ModuleSidecar graph = new ModuleSidecar("module-graph", "module-graph");
        graph.putBody("cursor", "## LOCKED\n* `Node`");
        // graph has no "claude" body

        ModuleSidecar cli = new ModuleSidecar("module-cli", "module-cli");
        cli.putBody("claude", "<audit>KartaCli</audit>");
        // cli has no "cursor" body

        // Merging "claude": only cli contributes → single body, no sub-markers
        String claudeMerged = ModuleSidecar.mergeFor("claude", List.of(graph, cli), true);
        assertEquals("<audit>KartaCli</audit>", claudeMerged);
        assertFalse(claudeMerged.contains("VIBETAGS-MODULE"), "Single contributor → no sub-markers");

        // Merging "cursor": only graph contributes → single body, no sub-markers
        String cursorMerged = ModuleSidecar.mergeFor("cursor", List.of(graph, cli), false);
        assertEquals("## LOCKED\n* `Node`", cursorMerged);
    }

    @Test
    void mergeFor_noContributions_returnsEmpty() {
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        // No "llms" body registered
        String merged = ModuleSidecar.mergeFor("llms", List.of(s), true);
        assertTrue(merged.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Integration: two-module compile into the same root
    // -----------------------------------------------------------------------

    @Test
    void twoModules_bothAnnotationsAppearInSharedOutput(@TempDir Path sharedRoot) throws IOException {
        // Create module directories under the shared root
        Path graphDir = sharedRoot.resolve("module-graph");
        Path cliDir = sharedRoot.resolve("module-cli");
        Files.createDirectories(graphDir);
        Files.createDirectories(cliDir);

        // Opt-in files at the shared root
        touch(sharedRoot, "CLAUDE.md");
        touch(sharedRoot, ".cursorrules");

        // --- Simulate module-graph compiling (has @AILocked on Node) ---
        // Build sidecar as if this module ran the processor
        ModuleSidecar graphSidecar = new ModuleSidecar("module-graph", "module-graph");
        graphSidecar.putBody("cursor", "## LOCKED FILES (DO NOT EDIT)\n* `com.example.graph.Node` - Reason: Core graph node");
        graphSidecar.putBody("claude", "<locked_files>\n  <file path=\"com.example.graph.Node\">\n    <reason>Core graph node</reason>\n  </file>\n</locked_files>");
        graphSidecar.save(sharedRoot);

        // --- Simulate module-cli compiling (has @AIAudit on KartaCli) ---
        ModuleSidecar cliSidecar = new ModuleSidecar("module-cli", "module-cli");
        cliSidecar.putBody("cursor", "## MANDATORY SECURITY AUDITS\n* `com.example.cli.KartaCli`\n  - Required Checks: Path Traversal");
        cliSidecar.putBody("claude", "<audit_requirements>\n  <file path=\"com.example.cli.KartaCli\">\n    <vulnerability_check>Path Traversal</vulnerability_check>\n  </file>\n</audit_requirements>");
        cliSidecar.save(sharedRoot);

        // Now simulate module-cli being the last to write .cursorrules
        List<ModuleSidecar> all = ModuleSidecar.readAll(sharedRoot);
        assertEquals(2, all.size(), "Both sidecars should be loaded");

        String mergedCursor = ModuleSidecar.mergeFor("cursor", all, false);
        String mergedClaude = ModuleSidecar.mergeFor("claude", all, true);

        // Both modules' annotations should survive
        assertTrue(mergedCursor.contains("com.example.graph.Node"),
                "graph module's @AILocked should be in merged .cursorrules");
        assertTrue(mergedCursor.contains("com.example.cli.KartaCli"),
                "cli module's @AIAudit should be in merged .cursorrules");
        assertTrue(mergedClaude.contains("com.example.graph.Node"),
                "graph module's @AILocked should be in merged CLAUDE.md");
        assertTrue(mergedClaude.contains("com.example.cli.KartaCli"),
                "cli module's @AIAudit should be in merged CLAUDE.md");

        // Write to .cursorrules using the file writer (simulating the final write step)
        AIGuardrailProcessor proc = new AIGuardrailProcessor();
        Path cursorrules = sharedRoot.resolve(".cursorrules");
        proc.writeFileIfChanged(cursorrules.toString(), mergedCursor, true);

        String onDisk = Files.readString(cursorrules);
        assertTrue(onDisk.contains("com.example.graph.Node"),
                "graph annotations must survive in the shared .cursorrules");
        assertTrue(onDisk.contains("com.example.cli.KartaCli"),
                "cli annotations must survive in the shared .cursorrules");
    }

    @Test
    void computeSidecarStamp_changesWhenSidecarUpdated(@TempDir Path root) throws IOException {
        // No sidecars initially
        long stamp0 = ModuleSidecar.computeSidecarStamp(root);

        // Write a sidecar
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        s.putBody("cursor", "content");
        s.save(root);

        long stamp1 = ModuleSidecar.computeSidecarStamp(root);
        assertNotEquals(stamp0, stamp1, "Stamp should change after a sidecar is written");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void touch(Path root, String relative) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) Files.createFile(p);
    }

    // -----------------------------------------------------------------------
    // Edge-case tests (from review)
    // -----------------------------------------------------------------------

    @Test
    void sidecar_corruptBase64_prunedOnLoad(@TempDir Path root) throws IOException {
        // Write a syntactically valid sidecar file but with corrupt Base64 in a body line.
        Path sidecarFile = root.resolve(".vibetags-mod-" + "corrupt");
        Files.writeString(sidecarFile, "# version=2\nmoduleId=corrupt\nmodulePath=\ncursor=!!!NOT_BASE64!!!\n");

        // readAll should silently discard the malformed sidecar and delete the file.
        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertTrue(loaded.isEmpty(), "Corrupt sidecar should be pruned");
        assertFalse(Files.exists(sidecarFile), "Corrupt sidecar file should be deleted");
    }

    @Test
    void mergeFor_threeModules_deterministicOrdering() {
        ModuleSidecar alpha = new ModuleSidecar("alpha", "alpha");
        alpha.putBody("cursor", "## Alpha rules");

        ModuleSidecar beta = new ModuleSidecar("beta", "beta");
        beta.putBody("cursor", "## Beta rules");

        ModuleSidecar gamma = new ModuleSidecar("gamma", "gamma");
        gamma.putBody("cursor", "## Gamma rules");

        String merged = ModuleSidecar.mergeFor("cursor", List.of(alpha, beta, gamma), false);

        // All three modules present
        assertTrue(merged.contains("# VIBETAGS-MODULE: alpha"));
        assertTrue(merged.contains("# VIBETAGS-MODULE: beta"));
        assertTrue(merged.contains("# VIBETAGS-MODULE: gamma"));
        assertTrue(merged.contains("Alpha rules"));
        assertTrue(merged.contains("Beta rules"));
        assertTrue(merged.contains("Gamma rules"));

        // Ordering: alpha before beta before gamma (list insertion order)
        int alphaIdx = merged.indexOf("# VIBETAGS-MODULE: alpha");
        int betaIdx = merged.indexOf("# VIBETAGS-MODULE: beta");
        int gammaIdx = merged.indexOf("# VIBETAGS-MODULE: gamma");
        assertTrue(alphaIdx < betaIdx, "alpha should appear before beta");
        assertTrue(betaIdx < gammaIdx, "beta should appear before gamma");
    }

    // -----------------------------------------------------------------------
    // hasContent() — not exercised by any other test
    // -----------------------------------------------------------------------

    @Test
    void sidecar_hasContent_falseWhenNoBodiesAdded() {
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        assertFalse(s.hasContent(), "Newly created sidecar with no bodies must report hasContent=false");
    }

    @Test
    void sidecar_hasContent_trueAfterNonBlankBodyAdded() {
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        s.putBody("cursor", "## LOCKED\n* `com.example.Foo`");
        assertTrue(s.hasContent(), "Sidecar with a non-blank body must report hasContent=true");
    }

    @Test
    void sidecar_hasContent_falseAfterOnlyBlankBodyAdded() {
        // putBody discards blank bodies, so hasContent must still be false.
        ModuleSidecar s = new ModuleSidecar("_root_", "");
        s.putBody("cursor", "   ");
        assertFalse(s.hasContent(), "Blank body must not be stored — hasContent must remain false");
    }

    // -----------------------------------------------------------------------
    // load() edge cases: line without '=' and missing moduleId
    // -----------------------------------------------------------------------

    @Test
    void sidecar_load_lineWithoutEquals_isSkipped(@TempDir Path root) throws IOException {
        // A sidecar file with a valid moduleId but also a line with no '='.
        // load() must skip the invalid line and still parse the valid body.
        Path sidecarFile = root.resolve(".vibetags-mod-skiptest");
        String encoded = java.util.Base64.getEncoder()
            .encodeToString("## rules".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(sidecarFile,
            "# version=2\n"
            + "moduleId=skiptest\n"
            + "modulePath=\n"
            + "THIS_LINE_HAS_NO_EQUALS_SIGN\n"
            + "cursor=" + encoded + "\n");

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertEquals(1, loaded.size(), "Sidecar with an invalid line must still load");
        assertEquals("skiptest", loaded.get(0).getModuleId());
        assertEquals("## rules", loaded.get(0).getBodies().get("cursor"),
            "Valid body entries after the invalid line must still be parsed");
    }

    @Test
    void sidecar_load_missingModuleId_pruned(@TempDir Path root) throws IOException {
        // A sidecar file with NO moduleId= line; load() returns null → readAll prunes it.
        Path sidecarFile = root.resolve(".vibetags-mod-noid");
        String encoded = java.util.Base64.getEncoder()
            .encodeToString("content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(sidecarFile,
            "# version=2\n"
            + "modulePath=\n"
            + "cursor=" + encoded + "\n");

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(root);
        assertTrue(loaded.isEmpty(), "Sidecar without moduleId must be pruned by readAll()");
        assertFalse(Files.exists(sidecarFile),
            "Malformed sidecar file must be deleted by readAll()");
    }

    // -----------------------------------------------------------------------
    // Branch-coverage additions: ModuleSidecar edge cases
    // -----------------------------------------------------------------------

    /** L68 false branch: putBody with null body must not store anything. */
    @Test
    void putBody_nullBody_isIgnored() {
        ModuleSidecar s = new ModuleSidecar("test", "");
        s.putBody("cursor", null);
        assertFalse(s.hasContent(), "null body must not be stored");
    }

    /** L146 true branch: readAll on a plain file (not a directory) returns empty. */
    @Test
    void readAll_nonDirectoryRoot_returnsEmptyList(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("notadir.txt");
        Files.writeString(file, "content");
        List<ModuleSidecar> result = ModuleSidecar.readAll(file);
        assertTrue(result.isEmpty(), "readAll on a file (not dir) must return empty list");
    }

    /** L150 true branch: readAll filters out .tmp sidecar files. */
    @Test
    void readAll_skipsTmpSidecarFiles(@TempDir Path tmp) throws IOException {
        Path tmpSidecar = tmp.resolve(".vibetags-mod-pending.tmp");
        Files.writeString(tmpSidecar, "moduleId=pending\nmodulePath=\n");
        List<ModuleSidecar> result = ModuleSidecar.readAll(tmp);
        assertTrue(result.isEmpty(), ".tmp sidecar files must be filtered out by readAll");
    }

    /**
     * L159 false branch: sidecar with modulePath="_root_" is included without stale check
     * (the !"_root_".equals(s.modulePath) condition evaluates false -> no directory check).
     */
    @Test
    void readAll_sidecarWithRootModulePath_isIncludedWithoutStaleCheck(@TempDir Path tmp) throws IOException {
        ModuleSidecar s = new ModuleSidecar("root-module", "_root_");
        s.putBody("cursor", "## Rules\n* rule one");
        s.save(tmp);
        List<ModuleSidecar> loaded = ModuleSidecar.readAll(tmp);
        assertTrue(loaded.stream().anyMatch(m -> "root-module".equals(m.getModuleId())),
            "sidecar with modulePath='_root_' must be included without stale directory check");
    }

    /** L177 true branch: listPaths on a plain file (not a directory) returns empty. */
    @Test
    void listPaths_nonDirectoryRoot_returnsEmptyList(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("notadir.txt");
        Files.writeString(file, "x");
        List<Path> result = ModuleSidecar.listPaths(file);
        assertTrue(result.isEmpty(), "listPaths on a file must return empty list");
    }

    /** L182 true branch: listPaths excludes .tmp sidecar files. */
    @Test
    void listPaths_skipsTmpSidecarFiles(@TempDir Path tmp) throws IOException {
        Path tmpSidecar = tmp.resolve(".vibetags-mod-x.tmp");
        Files.writeString(tmpSidecar, "irrelevant");
        List<Path> result = ModuleSidecar.listPaths(tmp);
        assertTrue(result.isEmpty(), ".tmp files must be excluded from listPaths");
    }

    // -----------------------------------------------------------------------
    // Lean indexed-root aggregate (issue #298): .vibetags-root-index opt-in links
    // each module's own scoped rules instead of embedding a full second copy.
    // -----------------------------------------------------------------------

    /** Saves a two-module reactor (graph + cli) with claude bodies and returns the shared root. */
    private static void seedTwoModuleReactor(Path root) throws IOException {
        Files.createDirectories(root.resolve("module-graph"));
        Files.createDirectories(root.resolve("module-cli"));
        ModuleSidecar graph = new ModuleSidecar("module-graph", "module-graph");
        graph.putBody("claude", "<locked_files><file path=\"com.example.graph.Node\"/></locked_files>");
        graph.putBody("gemini_md", "GRAPH GEMINI BODY: com.example.graph.Node");
        graph.save(root);
        ModuleSidecar cli = new ModuleSidecar("module-cli", "module-cli");
        cli.putBody("claude", "<audit_requirements><file path=\"com.example.cli.KartaCli\"/></audit_requirements>");
        cli.putBody("gemini_md", "CLI GEMINI BODY: com.example.cli.KartaCli");
        cli.save(root);
    }

    @Test
    void indexMode_off_byDefault_embedsFullBodies(@TempDir Path root) throws IOException {
        seedTwoModuleReactor(root);
        // Even with per-module .claude/rules present, WITHOUT the opt-in the merge embeds full bodies.
        Files.createDirectories(root.resolve("module-graph/.claude/rules"));
        Files.createDirectories(root.resolve("module-cli/.claude/rules"));

        String merged = ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true);

        assertTrue(merged.contains("com.example.graph.Node"), "graph body must be embedded");
        assertTrue(merged.contains("com.example.cli.KartaCli"), "cli body must be embedded");
        assertFalse(merged.contains("maintained in that module's own files"),
            "no pointer text without the .vibetags-root-index opt-in");
    }

    @Test
    void indexMode_replacesGranularModuleBodyWithPointer(@TempDir Path root) throws IOException {
        seedTwoModuleReactor(root);
        // Each module opts into its own granular dir → its guardrails live there.
        Files.createDirectories(root.resolve("module-graph/.claude/rules"));
        Files.createDirectories(root.resolve("module-cli/.claude/rules"));
        touch(root, ".vibetags-root-index"); // opt in

        String merged = ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true);

        // Full bodies are gone; pointers to each module's scoped dir are present.
        assertFalse(merged.contains("com.example.graph.Node"), "graph body must NOT be embedded");
        assertFalse(merged.contains("com.example.cli.KartaCli"), "cli body must NOT be embedded");
        assertTrue(merged.contains("module-graph/.claude/rules/"), "must link graph's scoped rules");
        assertTrue(merged.contains("module-cli/.claude/rules/"), "must link cli's scoped rules");
        // Module sub-markers still frame each pointer for traceability.
        assertTrue(merged.contains("<!-- VIBETAGS-MODULE: module-graph -->"));
        assertTrue(merged.contains("<!-- VIBETAGS-MODULE: module-cli -->"));
    }

    @Test
    void indexMode_moduleWithAggregateFile_pointerNamesFile(@TempDir Path root) throws IOException {
        seedTwoModuleReactor(root);
        // module-graph opts into its own CLAUDE.md (aggregate, no granular dir).
        touch(root, "module-graph/CLAUDE.md");
        touch(root, ".vibetags-root-index");

        String merged = ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true);

        assertTrue(merged.contains("module-graph/CLAUDE.md"), "pointer must name graph's own CLAUDE.md");
        assertFalse(merged.contains("com.example.graph.Node"), "graph body must NOT be embedded");
        // module-cli has NO own output → its body stays embedded (nothing lost).
        assertTrue(merged.contains("com.example.cli.KartaCli"),
            "a module with no per-module output keeps its embedded body");
    }

    @Test
    void indexMode_moduleWithoutOwnOutput_staysEmbedded(@TempDir Path root) throws IOException {
        seedTwoModuleReactor(root);
        // Opt in, but NO module generates its own output → everything stays embedded (lossless).
        touch(root, ".vibetags-root-index");

        String merged = ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true);

        assertTrue(merged.contains("com.example.graph.Node"), "graph body stays embedded (no own output)");
        assertTrue(merged.contains("com.example.cli.KartaCli"), "cli body stays embedded (no own output)");
        assertFalse(merged.contains("maintained in that module's own files"), "no pointers when nothing is linkable");
    }

    @Test
    void indexMode_nonAggregateService_fullMergeUnchanged(@TempDir Path root) throws IOException {
        seedTwoModuleReactor(root);
        Files.createDirectories(root.resolve("module-graph/.claude/rules"));
        Files.createDirectories(root.resolve("module-cli/.claude/rules"));
        touch(root, ".vibetags-root-index");

        // GEMINI.md has no granular sibling → it must still embed the full merge.
        String merged = ModuleSidecar.mergeFor("gemini_md", ModuleSidecar.readAll(root), true);

        assertTrue(merged.contains("GRAPH GEMINI BODY"), "gemini_md keeps graph body");
        assertTrue(merged.contains("CLI GEMINI BODY"), "gemini_md keeps cli body");
        assertFalse(merged.contains("maintained in that module's own files"), "gemini_md is never linked");
    }

    @Test
    void indexMode_rootOwnBodyStaysInline(@TempDir Path root) throws IOException {
        // A reactor where the root module itself contributes claude content plus one child module.
        Files.createDirectories(root.resolve("module-graph/.claude/rules"));
        ModuleSidecar rootMod = new ModuleSidecar("_root_", "");
        rootMod.putBody("claude", "<core_elements>ROOT LEVEL RULE</core_elements>");
        rootMod.save(root);
        ModuleSidecar graph = new ModuleSidecar("module-graph", "module-graph");
        graph.putBody("claude", "<locked_files><file path=\"com.example.graph.Node\"/></locked_files>");
        graph.save(root);
        touch(root, ".vibetags-root-index");

        String merged = ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true);

        assertTrue(merged.contains("ROOT LEVEL RULE"), "the root module's own body must stay inline");
        assertFalse(merged.contains("com.example.graph.Node"), "the child module is linked, not embedded");
        assertTrue(merged.contains("module-graph/.claude/rules/"), "child module linked to its scoped rules");
    }
}
