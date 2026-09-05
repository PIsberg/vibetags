package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two modules whose granular stems differ only in capitalisation share one rule file on a
 * case-insensitive filesystem, and the file must not depend on which module compiled last
 * (issue #579).
 *
 * <p>{@code ModuleSidecar.mergeGranular} already folds the two stems into one body, but the
 * heading and the front-matter description were rendered from the compiling module's own plan:
 * {@code # Rules for Payment} after module {@code a}, {@code # Rules for payment} after module
 * {@code b}. On Windows and macOS the two names are one file, so it flipped with every compile
 * and check mode's verdict depended on build order. The single-module fold already produces a
 * joined heading; the cross-module merge has to produce the same one.
 */
@Tag("e2e")
class MultiModuleCaseCollisionTest {

    private static final String CLASS_SOURCE = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "wire format")
        public class Payment {}
        """;

    private static final String PACKAGE_SOURCE = """
        @se.deversity.vibetags.annotations.AIContext(focus = "settlement timing")
        package com.example.payment;
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    /** Every rule file whose name folds to the colliding stem, by name, with its content. */
    private Map<String, String> collidingFiles() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var entries = Files.list(reactorRoot.resolve(".cursor/rules"))) {
            for (Path p : entries.toList()) {
                String name = String.valueOf(p.getFileName());
                // Keyed case-folded: on a case-insensitive filesystem the one file's entry takes
                // the case of whichever name last wrote it, and that is not what is under test.
                String folded = name.toLowerCase(Locale.ROOT);
                if (folded.equals("com-example-payment.mdc")) {
                    files.put(folded, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }

    @Test
    void sharedFileDoesNotDependOnWhichModuleCompiledLast() throws IOException {
        Files.createFile(reactorRoot.resolve(".cursorrules"));
        Files.createDirectories(reactorRoot.resolve(".cursor/rules"));
        Files.createFile(reactorRoot.resolve(".cursor/rules/.vibetags"));

        compileModule("a", "com.example.Payment", CLASS_SOURCE);
        compileModule("b", "com.example.payment.package-info", PACKAGE_SOURCE);
        Map<String, String> afterB = collidingFiles();
        assertFalse(afterB.isEmpty(), "no rule file written for the colliding stems at all");

        compileModule("a", "com.example.Payment", CLASS_SOURCE);
        Map<String, String> afterA = collidingFiles();

        assertEquals(afterB, afterA,
            "recompiling the other module must leave every colliding file byte-identical");
        for (Map.Entry<String, String> file : afterA.entrySet()) {
            String content = file.getValue();
            assertTrue(content.contains("# Rules for Payment, payment"),
                file.getKey() + " covers both elements, so its heading must name both: " + content);
            assertTrue(content.contains("description: \"AI rules for com.example.Payment, com.example.payment\""),
                file.getKey() + "'s description must name both: " + content);
            assertTrue(content.contains("wire format") && content.contains("settlement timing"),
                file.getKey() + " must carry both guardrails: " + content);
        }
    }
}
