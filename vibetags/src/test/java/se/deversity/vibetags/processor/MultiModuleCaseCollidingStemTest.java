package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-module twin of {@code CaseCollidingGranularStemTest}
 * (<a href="https://github.com/PIsberg/vibetags/issues/525">issue #525</a>): the package
 * {@code com.example.payment} and the class {@code com.example.Payment} live in <em>different</em>
 * reactor modules, so their case-colliding stems meet only in the sidecar merge — the within-plan
 * fold from #510 never sees them together. Before the merge-side fold, each module's compile wrote
 * its own element alone, and on a case-insensitive filesystem whichever module compiled last
 * replaced the shared physical file: one element's guardrails gone, the scoped-rules index still
 * naming both.
 *
 * <p>The assertions mirror the single-module test's runner-agnostic shape, with one addition for
 * the cold first pass: a module that compiles before its colliding sibling's sidecar exists cannot
 * know about it, so its own file converges on that module's <em>next</em> compile — the same
 * one-pass-behind convergence the #365 role merge has. After the second pass, every surviving
 * colliding file must carry every element.
 */
@Tag("e2e")
class MultiModuleCaseCollidingStemTest {

    private static final String PACKAGE_SOURCE = """
        @se.deversity.vibetags.annotations.AIContext(focus = "settlement timing")
        package com.example.payment;
        """;

    private static final String CLASS_SOURCE = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "wire format")
        public class Payment {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one module's sources into the shared reactor root, mimicking one reactor pass. */
    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(
            module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    private List<Path> collidingFiles() throws IOException {
        try (Stream<Path> entries = Files.list(reactorRoot.resolve(".gemini/rules"))) {
            return entries
                .filter(p -> String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT)
                    .equals("com-example-payment.md"))
                .toList();
        }
    }

    @Test
    @DisplayName("case-colliding stems from different modules merge instead of replacing each other")
    void collidingStemsAcrossModulesMerge() throws IOException {
        Files.createDirectories(reactorRoot.resolve("module-payments"));
        Files.createDirectories(reactorRoot.resolve("module-model"));
        Files.createDirectories(reactorRoot.resolve(".gemini/rules"));

        compileModule("module-payments", "com.example.payment.package-info", PACKAGE_SOURCE);
        compileModule("module-model", "com.example.Payment", CLASS_SOURCE);

        // First pass: the later module has both sidecars in view, so on every filesystem at least
        // one surviving colliding file must already carry both elements. On a case-insensitive
        // filesystem it is the only file, which is exactly the case that used to lose a guardrail.
        boolean anyMerged = false;
        for (Path file : collidingFiles()) {
            String content = Files.readString(file);
            anyMerged |= content.contains("settlement timing") && content.contains("wire format");
        }
        assertTrue(anyMerged,
            "after the second module's compile, no colliding rule file carries both elements — "
                + "the later write replaced the earlier module's guardrails instead of merging");

        // Second pass for the first module: with its sibling's sidecar now on disk, its own file
        // converges. From here every surviving file must carry every colliding element, whichever
        // names this filesystem kept.
        compileModule("module-payments", "com.example.payment.package-info", PACKAGE_SOURCE);

        List<Path> files = collidingFiles();
        assertFalse(files.isEmpty(), "no colliding rule file survived at all");
        for (Path file : files) {
            String content = Files.readString(file);
            assertTrue(content.contains("settlement timing") && content.contains("wire format"),
                file.getFileName() + " must carry both modules' guardrails after both modules "
                    + "have compiled with each other's sidecars in view. Content: " + content);
        }
    }
}
