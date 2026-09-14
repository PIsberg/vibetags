package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof of the fingerprint short-circuit at the top of
 * {@code AIGuardrailProcessor.generateFiles()}: a compile whose inputs have not moved since the
 * previous one skips the content build and every write, and a compile whose inputs have moved does
 * not.
 *
 * <p>Every test observes the skip itself, through the NOTE the processor prints when it takes it
 * ("inputs unchanged since last run"). Unchanged output mtimes are not evidence on their own: the
 * per-file write cache ({@code write.skip reason=cache-unchanged}) leaves them unchanged as well, so
 * a test that asserts only mtimes stays green with the short-circuit disabled (issue #700).
 *
 * <p>No state is engineered. A no-op recompile short-circuits as it is: the sidecar is left
 * untouched when its bytes are unchanged, so the recorded sidecar stamp still matches. An earlier
 * version of this class deleted the sidecars and patched the stored stamp to {@code 0} through a
 * {@code WriteCache} never bound to the module. Since the cache keeps one header section per module
 * (#556) that patch moved only the root-wide stamp, which then disagreed with the module's own, so
 * no compile after it could short-circuit: the positive case passed on the per-file cache and every
 * negative case passed whether or not its change was detected.
 *
 * <p>The negative cases therefore run a control first: recompile with nothing changed and assert
 * the skip fires, then make the one change under test and assert it does not. Their source blocks
 * keep their original form, so this rewrite removes no line the locked-files guard would read as a
 * stripped lock.
 */
@Tag("e2e")
class FingerprintShortCircuitTest {

    private static final String SKIP_NOTE = "inputs unchanged since last run";

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void shortCircuit_skipsGenerationWhenAllConditionsMatch(@TempDir Path tmp) throws Exception {
        assertFalse(shortCircuited(compileExampleSources(tmp)),
            "precondition: a first compile has no cache to match and must generate");

        Path cursorRules = tmp.resolve(".cursorrules");
        Path claudeMd    = tmp.resolve("CLAUDE.md");
        assertTrue(Files.exists(cursorRules), ".cursorrules must exist after first compile");
        long cursorMtime = Files.getLastModifiedTime(cursorRules).toMillis();
        long claudeMtime = Files.getLastModifiedTime(claudeMd).toMillis();

        // Let the filesystem clock tick so a re-write, if one happened, would be visible.
        ProcessorTestHarness.awaitFilesystemTick(tmp);

        assertTrue(shortCircuited(compileExampleSources(tmp)),
            "a recompile with unchanged annotations, services, sidecars and outputs must take the "
                + "fingerprint short-circuit and print '" + SKIP_NOTE + "'");

        assertEquals(cursorMtime, Files.getLastModifiedTime(cursorRules).toMillis(),
            ".cursorrules must not be rewritten when the fingerprint short-circuit fires");
        assertEquals(claudeMtime, Files.getLastModifiedTime(claudeMd).toMillis(),
            "CLAUDE.md must not be rewritten when the fingerprint short-circuit fires");
    }

    @Test
    void shortCircuit_doesNotFire_whenAnnotationChanges(@TempDir Path tmp) throws Exception {
        // Compile with one @AILocked reason.
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"original reason\")\n" +
            "public class A {}\n");
        h1.compile();
        assertControlShortCircuits(compileA(tmp, "original reason", "class"));

        Path cursorRules = tmp.resolve(".cursorrules");
        long mtime1 = Files.getLastModifiedTime(cursorRules).toMillis();

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        // Recompile with a DIFFERENT reason → fingerprint changes → short-circuit must NOT fire.
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"completely different reason\")\n" +
            "public class A {}\n");
        assertFalse(shortCircuited(h2.compileReturningDiagnostics()),
            "an edited annotation attribute must not short-circuit");

        long mtime2 = Files.getLastModifiedTime(cursorRules).toMillis();

        assertTrue(mtime2 > mtime1,
            ".cursorrules must be rewritten when annotation attributes change (fingerprint differs)");
    }

    @Test
    void shortCircuit_doesNotFire_whenOutputFileDeleted(@TempDir Path tmp) throws Exception {
        compileExampleSources(tmp);
        assertControlShortCircuits(compileExampleSources(tmp));

        // Delete a generated output file: allCachedFilesStable() must return false.
        Path cursorRules = tmp.resolve(".cursorrules");
        Files.delete(cursorRules);

        assertFalse(shortCircuited(compileExampleSources(tmp)),
            "a deleted output file must not short-circuit");

        assertTrue(Files.exists(cursorRules),
            ".cursorrules must be recreated when deleted (short-circuit must not fire)");
    }

    @Test
    void shortCircuit_doesNotFire_whenProjectNameChanges(@TempDir Path tmp) throws Exception {
        // Compile with one project name; llms.txt renders it as the H1.
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"stable reason\")\n" +
            "public class A {}\n");
        h1.compile("-Avibetags.project=AlphaCorp");
        assertTrue(Files.readString(tmp.resolve("llms.txt")).contains("AlphaCorp"),
            "precondition: the first compile must render the project name into llms.txt");
        assertControlShortCircuits(compileA(tmp, "stable reason", "class", "-Avibetags.project=AlphaCorp"));

        // Recompile with a DIFFERENT project name and identical annotations. The annotation
        // fingerprint alone cannot see the rename, so without a run-context stamp on the cache
        // the short-circuit fires and llms.txt keeps the old name until some annotation changes.
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"stable reason\")\n" +
            "public class A {}\n");
        assertFalse(shortCircuited(h2.compileReturningDiagnostics("-Avibetags.project=BetaCorp")),
            "a changed -Avibetags.project must not short-circuit");

        assertTrue(Files.readString(tmp.resolve("llms.txt")).contains("BetaCorp"),
            "llms.txt must be regenerated when -Avibetags.project changes");
    }

    @Test
    void shortCircuit_doesNotFire_whenModuleOverrideChanges(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"stable reason\")\n" +
            "public class A {}\n");
        h1.compile("-Avibetags.module=alpha");
        assertControlShortCircuits(compileA(tmp, "stable reason", "class", "-Avibetags.module=alpha"));

        // Same annotations, new module name: this compilation owns a sidecar under the new id,
        // which can only be written if the short-circuit does not fire.
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"stable reason\")\n" +
            "public class A {}\n");
        assertFalse(shortCircuited(h2.compileReturningDiagnostics("-Avibetags.module=beta")),
            "a changed -Avibetags.module must not short-circuit");

        assertTrue(Files.exists(tmp.resolve(".vibetags-mod-beta")),
            "the module sidecar must be re-saved under the new -Avibetags.module id");
    }

    /**
     * The locks report records each element's kind, so a class that becomes an interface with
     * nothing else moving — same name, same lines, same reason — changes what the file should say.
     * The fingerprint hashed the path and the annotation attributes and nothing about the element
     * itself, so this build short-circuited and left the report describing a class that no longer
     * exists, while check mode on the same tree reported drift (issue #440's shape again).
     */
    @Test
    void shortCircuit_doesNotFire_whenAnElementChangesKind(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"frozen\")\n" +
            "public class A {}\n");
        h1.compile();
        Path locks = tmp.resolve(".vibetags-locks");
        assertTrue(Files.readString(locks).contains("\"kind\":\"CLASS\""),
            "the first build reports a class");
        assertControlShortCircuits(compileA(tmp, "frozen", "class"));

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"frozen\")\n" +
            "public interface A {}\n");
        assertFalse(shortCircuited(h2.compileReturningDiagnostics()),
            "a class that became an interface must not short-circuit");
        String report = Files.readString(locks);
        assertTrue(report.contains("\"kind\":\"INTERFACE\""),
            "the report must follow the element's kind:\n" + report);
        assertFalse(report.contains("\"kind\":\"CLASS\""), report);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<Diagnostic<? extends JavaFileObject>> compileExampleSources(Path root)
            throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root);
        ProcessorTestHarness.addExampleSources(h);
        return h.compileReturningDiagnostics();
    }

    /**
     * The control recompile: {@code com.example.A} exactly as a negative case's first compile
     * declared it, with the given lock reason and declaration keyword.
     */
    private static List<Diagnostic<? extends JavaFileObject>> compileA(Path root, String reason,
            String keyword, String... options) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root);
        h.addSource("com.example.A",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"" + reason + "\")\n"
                + "public " + keyword + " A {}\n");
        return h.compileReturningDiagnostics(options);
    }

    private static boolean shortCircuited(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
            .anyMatch(d -> d.getMessage(Locale.ROOT).contains(SKIP_NOTE));
    }

    /**
     * A negative case proves nothing unless the same setup, with nothing changed, would have
     * skipped: otherwise a short-circuit that can never fire passes it trivially.
     */
    private static void assertControlShortCircuits(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        assertTrue(shortCircuited(diagnostics),
            "control: a recompile with nothing changed must short-circuit, or the negative "
                + "assertion that follows passes whether or not the change is detected");
    }
}
