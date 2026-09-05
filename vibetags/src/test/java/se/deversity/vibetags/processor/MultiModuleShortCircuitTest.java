package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A no-op rebuild of a reactor must short-circuit in <em>every</em> module, not only the one that
 * happened to flush the shared cache last (issue #556).
 *
 * <p>Two things stood in the way. {@code .vibetags-cache} carried one {@code # fingerprint} and one
 * {@code # sidecar-stamp} header for the whole root, so each module's flush overwrote its
 * sibling's and the next build of the sibling never matched. And a module's sidecar was rewritten
 * on every full round whether or not its content had changed, which moved its mtime, which moved
 * the stamp every other module had recorded, so the reactor chased its own tail: each module's
 * full round guaranteed the next module's.
 */
@Tag("e2e")
class MultiModuleShortCircuitTest {

    private static final String A_SOURCE = """
        package com.example.a;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "wire format")
        public class Payment {}
        """;

    private static final String B_SOURCE = """
        package com.example.b;
        import se.deversity.vibetags.annotations.AIContext;
        @AIContext(focus = "settlement timing", avoids = "float arithmetic")
        public class Ledger {}
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private List<Diagnostic<? extends JavaFileObject>> compileModule(String module, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        return harness.compileReturningDiagnostics();
    }

    private static boolean shortCircuited(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
            .anyMatch(d -> d.getMessage(Locale.ROOT).contains("inputs unchanged since last run"));
    }

    @Test
    void everyModuleShortCircuitsOnANoOpReactorRebuild() throws Exception {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createFile(reactorRoot.resolve(".cursorrules"));

        // First build, then a second pass in which each module catches up with the merged
        // state the other left behind (the shared root files and the sidecar set both changed
        // under it during the first pass).
        compileModule("a", "com.example.a.Payment", A_SOURCE);
        compileModule("b", "com.example.b.Ledger", B_SOURCE);
        ProcessorTestHarness.awaitFilesystemTick(reactorRoot);
        compileModule("a", "com.example.a.Payment", A_SOURCE);
        compileModule("b", "com.example.b.Ledger", B_SOURCE);
        ProcessorTestHarness.awaitFilesystemTick(reactorRoot);

        // Nothing changed: the third pass must be a no-op in both modules.
        assertTrue(shortCircuited(compileModule("a", "com.example.a.Payment", A_SOURCE)),
            "module a must short-circuit on a no-op rebuild");
        assertTrue(shortCircuited(compileModule("b", "com.example.b.Ledger", B_SOURCE)),
            "module b must short-circuit on a no-op rebuild");
    }
}
