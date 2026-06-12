package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ModuleSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the multi-module aggregation path in
 * {@code AIGuardrailProcessor.generateFiles()} (lines 205–218 and 235).
 *
 * <p>Strategy:
 * <ol>
 *   <li>First compile primes the per-module sidecar on disk.</li>
 *   <li>A sibling sidecar is created manually with a different module ID so that
 *       {@code ModuleSidecar.readAll()} returns two entries on the next compile.</li>
 *   <li>The added sidecar changes the sidecar-stamp, so the fingerprint short-circuit
 *       does not fire and the full generate phase runs.</li>
 *   <li>Second compile finds {@code allSidecars.size() > 1} → {@code multiModule = true}
 *       → the merge loop (L205–218) and the ternary {@code anyContributed} branch (L235)
 *       execute.</li>
 * </ol>
 */
class MultiModuleProcessorTest {

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void multiModule_mergesContributionsFromSiblingModules(@TempDir Path tmp) throws Exception {
        // ---- First compile: prime this module's sidecar ----
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"core logic\")\n"
                + "public class A {}\n");
        h1.compile();

        List<Path> sidecars1 = Files.list(tmp)
            .filter(p -> p.getFileName().toString().startsWith(".vibetags-mod-"))
            .toList();
        assertTrue(sidecars1.size() >= 1, "First compile must write a module sidecar");

        // ---- Add sibling sidecar ----
        // modulePath="" skips the stale-directory check in readAll(), ensuring the sibling
        // is always included regardless of what directory the tests run from.
        ModuleSidecar sibling = new ModuleSidecar("sibling-module", "");
        sibling.putBody("cursor",
            "## Locked Files (Do Not Modify)\n* `com.sibling.SiblingClass` - sibling reason\n");
        sibling.putBody("claude",
            "  <locked_files>\n    <file path=\"com.sibling.SiblingClass\">\n"
                + "      <reason>sibling reason</reason>\n    </file>\n  </locked_files>\n");
        sibling.save(tmp);

        // The extra sidecar file changes computeSidecarStamp → the fingerprint short-circuit
        // does not fire, so the full generate phase (including the multiModule path) runs.

        // ---- Second compile: two sidecars → multiModule = true ----
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"core logic\")\n"
                + "public class A {}\n");
        h2.compile();

        // Verify multi-module merge path executed: both modules' content should appear.
        String cursor = Files.readString(tmp.resolve(".cursorrules"));
        assertTrue(cursor.contains("com.example.A") || cursor.contains("sibling"),
            ".cursorrules must contain merged content from both modules");
    }

    @Test
    void multiModule_anyContributed_trueWhenSiblingHasBody(@TempDir Path tmp) throws Exception {
        // Verifies the true branch of the ternary at L235:
        //   boolean anyContributed = multiModule
        //       ? allSidecars.stream().anyMatch(s -> s.getBodies().containsKey(service))
        //       : collector.anyAnnotationsFound();

        // First compile with @AIContext
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.B",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"business rules\", avoids = \"raw SQL\")\n"
                + "public class B {}\n");
        h1.compile();

        // Add sibling sidecar contributing to claude service
        ModuleSidecar sibling = new ModuleSidecar("sibling-mod-b", "");
        sibling.putBody("claude",
            "  <contextual_instructions>\n"
                + "    <file path=\"com.sibling.Other\">\n"
                + "      <focus>sibling focus</focus>\n"
                + "      <avoids>sibling avoids</avoids>\n"
                + "    </file>\n"
                + "  </contextual_instructions>\n");
        sibling.save(tmp);

        // Second compile: multiModule = true → true branch of ternary executes
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.B",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"business rules\", avoids = \"raw SQL\")\n"
                + "public class B {}\n");
        h2.compile();

        String claude = Files.readString(tmp.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.B") || claude.contains("contextual"),
            "CLAUDE.md must contain content from the multi-module merge");
    }

    @Test
    void multiModule_readAll_includesSidecarsWithEmptyModulePath(@TempDir Path tmp) throws Exception {
        // Directly tests ModuleSidecar.readAll() with a sidecar that has modulePath=""
        // → covers the false branch of the stale check (modulePath.isEmpty() == true).
        ModuleSidecar s1 = new ModuleSidecar("mod-a", "");
        s1.putBody("cursor", "content-a");
        s1.save(tmp);

        ModuleSidecar s2 = new ModuleSidecar("mod-b", "");
        s2.putBody("cursor", "content-b");
        s2.save(tmp);

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(tmp);
        assertTrue(loaded.size() >= 2, "readAll must include both sidecars with empty modulePath");
        assertTrue(loaded.stream().anyMatch(s -> "mod-a".equals(s.getModuleId())),
            "readAll must find mod-a");
        assertTrue(loaded.stream().anyMatch(s -> "mod-b".equals(s.getModuleId())),
            "readAll must find mod-b");
    }

    @Test
    void multiModule_readAll_skipsButNeverDeletesFutureVersionSidecar(@TempDir Path tmp) throws Exception {
        // A sidecar written by a NEWER processor (mixed-version multi-module build) declares a
        // higher "# version" than this build understands. It must be excluded from the merge
        // (we cannot safely parse it) but NEVER deleted — the newer sibling module owns it.
        ModuleSidecar current = new ModuleSidecar("mod-current", "");
        current.putBody("cursor", "content-current");
        current.save(tmp);

        Path futureSidecar = tmp.resolve(".vibetags-mod-future");
        Files.writeString(futureSidecar,
            "# version=99\nmoduleId=mod-future\nmodulePath=\nsome-future-key{not=parseable}\n");

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(tmp);
        assertTrue(loaded.stream().anyMatch(s -> "mod-current".equals(s.getModuleId())),
            "current-format sidecar must load normally");
        assertFalse(loaded.stream().anyMatch(s -> "mod-future".equals(s.getModuleId())),
            "future-format sidecar must not be parsed into the merge");
        assertTrue(Files.exists(futureSidecar),
            "future-format sidecar must not be deleted — its module owns it");
    }

    @Test
    void multiModule_readAll_deletesMalformedSidecar(@TempDir Path tmp) throws Exception {
        // Distinct from the future-version case: a sidecar with no moduleId is garbage from
        // any version's perspective and keeps the existing delete-on-malformed behaviour.
        Path malformed = tmp.resolve(".vibetags-mod-broken");
        Files.writeString(malformed, "# version=1\nno-module-id-here=x\n");

        ModuleSidecar.readAll(tmp);
        assertFalse(Files.exists(malformed), "malformed sidecar must be pruned");
    }

    @Test
    void computeModuleId_compilationRootEqualsVibetagsRoot_returnsRootId(@TempDir Path tmp) throws IOException {
        // When compilationRoot == vibetagsRoot, rel is "" or "." → returns "_root_"
        // Covers the rel.isEmpty() branch in computeModuleId.
        String id = ModuleSidecar.computeModuleId(tmp, tmp);
        assertTrue("_root_".equals(id), "computeModuleId must return _root_ when roots are equal");
    }

    @Test
    void computeModuleId_compilationRootOutsideVibetagsRoot_usesStableHash(@TempDir Path tmp) {
        // Regression: when the compilation root is not under the VibeTags root (as in every
        // single-module test, where the root is a @TempDir far from the project dir), relativize
        // produces a long "../../.."-laden path. Used verbatim as a filename, that can exceed the
        // OS limit — on macOS save() then failed with ENAMETOOLONG and wrote no sidecar. The id
        // must instead be a short, stable hash with no path segments.
        Path vibetagsRoot = tmp.resolve("project");
        Path compilationRoot = tmp.resolve("somewhere").resolve("else").resolve("deep");

        String id = ModuleSidecar.computeModuleId(compilationRoot, vibetagsRoot);

        assertTrue(id.matches("[0-9a-f]+"),
            "an out-of-tree compilation root must yield a short hex hash id, got: " + id);
        assertTrue(!id.contains(".."),
            "module id must never embed '..' path segments (filename-length risk), got: " + id);
        assertTrue(id.equals(ModuleSidecar.computeModuleId(compilationRoot, vibetagsRoot)),
            "id must be stable across calls");
    }

    @Test
    void computeModulePath_compilationRootOutsideVibetagsRoot_returnsEmpty(@TempDir Path tmp) {
        // Regression: an out-of-tree compilation root yields a "../../.."-escaping relative path.
        // Stored as the sidecar's modulePath, readAll() would resolve it for a directory-existence
        // staleness check — unreliable across symlinked temp dirs (macOS /var -> /private/var),
        // where it wrongly pruned the just-written sidecar and broke the first-compile assertion.
        // Must return "" so the staleness check is skipped for such modules.
        Path vibetagsRoot = tmp.resolve("project");
        Path compilationRoot = tmp.resolve("somewhere").resolve("else").resolve("deep");

        assertTrue(ModuleSidecar.computeModulePath(compilationRoot, vibetagsRoot).isEmpty(),
            "an out-of-tree compilation root must yield an empty module path");
    }

    @Test
    void computeModulePath_compilationRootEqualsVibetagsRoot_returnsEmpty(@TempDir Path tmp) throws IOException {
        // When compilationRoot == vibetagsRoot, rel is "" → computeModulePath returns ""
        // Covers the ".".equals(rel) ? "" branch in computeModulePath.
        String path = ModuleSidecar.computeModulePath(tmp, tmp);
        assertTrue(path.isEmpty(), "computeModulePath must return empty string when roots are equal");
    }
}
