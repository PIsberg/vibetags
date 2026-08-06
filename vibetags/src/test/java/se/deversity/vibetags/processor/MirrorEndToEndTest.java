package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for cross-module rule mirroring (issue #312): a module that exercises another
 * module's annotated code — typically a reactor's centralised test module — declares that it wants
 * that module's scoped rules by dropping a {@code .vibetags-mirror} file in its own directory.
 *
 * <p>Laid out as a real Maven reactor (a {@code pom.xml} per module) so
 * {@link se.deversity.vibetags.processor.internal.ModuleRootResolver} resolves genuine module
 * directories, exactly like {@link PerModuleOutputEndToEndTest}.
 */
@Tag("e2e")
class MirrorEndToEndTest {

    private static final String FHE_SOURCE = """
        package com.example.fhe;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "Native bridge — ABI is pinned")
        public class NativeBridge {}
        """;

    private static final String FHE_SOURCE_UNANNOTATED = """
        package com.example.fhe;
        public class NativeBridge {}
        """;

    private static final String KEYS_SOURCE = """
        package com.example.fhe;
        import se.deversity.vibetags.annotations.AIPrivacy;
        public class Keys {
            @AIPrivacy(reason = "raw key material")
            private byte[] secret;
            @AIPrivacy(reason = "derived session key")
            private byte[] session;
        }
        """;

    private static final String RUNTIME_SOURCE = """
        package com.example.runtime;
        import se.deversity.vibetags.annotations.AIContext;
        @AIContext(focus = "key lifecycle", avoids = "logging key material")
        public class KeyContext {}
        """;

    private static final String TEST_MODULE_SOURCE = """
        package com.example.tests;
        import se.deversity.vibetags.annotations.AIParallelTests;
        @AIParallelTests
        public class SharedFixtures {}
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one module (real pom.xml + file-backed source) into the shared reactor root. */
    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    /** Makes {@code module} a mirror target with the given config body. */
    private void mirrorTarget(String module, String config) throws IOException {
        Files.createDirectories(reactorRoot.resolve(module).resolve(".claude/rules"));
        Files.writeString(reactorRoot.resolve(module).resolve(".vibetags-mirror"), config,
            StandardCharsets.UTF_8);
    }

    private Path mirrored(String targetModule, String sourceModule, String stem) {
        return reactorRoot.resolve(targetModule).resolve(".claude/rules")
            .resolve("mirrored-" + sourceModule + "-" + stem + ".md");
    }

    // ------------------------------------------------------------------

    @Test
    void emptyConfig_mirrorsEveryModuleIntoTheTarget() throws IOException {
        mirrorTarget("app-tests", "# mirror everything\n");

        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);
        compileModule("app-runtime", "com.example.runtime.KeyContext", RUNTIME_SOURCE);

        Path fromFhe = mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge");
        Path fromRuntime = mirrored("app-tests", "app-runtime", "com-example-runtime-KeyContext");
        assertTrue(Files.exists(fromFhe), "app-fhe's rules must be mirrored into the test module");
        assertTrue(Files.exists(fromRuntime), "app-runtime's rules must be mirrored into the test module");

        String body = Files.readString(fromFhe);
        assertTrue(body.contains("Native bridge — ABI is pinned"),
            "the mirrored file carries the source module's guardrail: " + body);
        assertTrue(body.contains("\"**/NativeBridge.java\""),
            "the rule keeps its own glob: " + body);
        assertTrue(body.contains("\"**/app-tests/**/*.java\""),
            "the target's sources are appended to paths: so the rule actually matches them: " + body);
    }

    @Test
    void explicitSourceList_mirrorsOnlyTheNamedModules() throws IOException {
        mirrorTarget("app-tests", "../app-fhe\n");

        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);
        compileModule("app-runtime", "com.example.runtime.KeyContext", RUNTIME_SOURCE);

        assertTrue(Files.exists(mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge")),
            "the named source module is mirrored");
        assertFalse(Files.exists(mirrored("app-tests", "app-runtime", "com-example-runtime-KeyContext")),
            "a module the config does not name must not be mirrored");
    }

    @Test
    void explicitGlobDirective_overridesTheDefaultTargetGlob() throws IOException {
        mirrorTarget("app-tests", "glob = **/app-tests/src/test/java/**/*.java\n");

        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);

        String body = Files.readString(mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge"));
        assertTrue(body.contains("\"**/app-tests/src/test/java/**/*.java\""),
            "the declared glob is used: " + body);
        assertFalse(body.contains("\"**/app-tests/**/*.java\""),
            "the default glob is replaced, not added to: " + body);
    }

    @Test
    void targetsOwnCompile_doesNotDeleteMirroredFiles() throws IOException {
        mirrorTarget("app-tests", "");
        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);

        Path fromFhe = mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge");
        assertTrue(Files.exists(fromFhe), "precondition: the mirror exists");

        // The target module now compiles its own annotated source; its granular cleanup runs over
        // the same directory and must leave the sibling's mirrored files alone.
        compileModule("app-tests", "com.example.tests.SharedFixtures", TEST_MODULE_SOURCE);

        assertTrue(Files.exists(fromFhe),
            "a target module's own cleanup must not delete rules mirrored in from a sibling");
        assertTrue(Files.exists(reactorRoot.resolve("app-tests/.claude/rules/com-example-tests-SharedFixtures.md")),
            "the target still writes its own per-class rules");
    }

    @Test
    void siblingMirrors_doNotCleanUpEachOther() throws IOException {
        mirrorTarget("app-tests", "");
        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);
        compileModule("app-runtime", "com.example.runtime.KeyContext", RUNTIME_SOURCE);

        assertTrue(Files.exists(mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge")),
            "app-runtime's compile must not clean up app-fhe's mirrored namespace");
        assertTrue(Files.exists(mirrored("app-tests", "app-runtime", "com-example-runtime-KeyContext")));
    }

    @Test
    void mirroredFileDisappears_whenTheSourceAnnotationDoes() throws IOException {
        mirrorTarget("app-tests", "");

        // Two annotated classes in one module, then a recompile with one of them un-annotated.
        // (A module with *no* annotation of any kind never invokes the processor at all — javac
        // only runs a "*" processor when the round has annotations — so the surviving class both
        // keeps the scenario realistic and keeps the processor running.)
        compileFhe(FHE_SOURCE, KEYS_SOURCE);
        Path bridgeRule = mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge");
        Path keysRule = mirrored("app-tests", "app-fhe", "com-example-fhe-Keys");
        assertTrue(Files.exists(bridgeRule), "precondition: the mirror exists");
        assertTrue(Files.exists(keysRule), "precondition: both mirrors exist");

        compileFhe(FHE_SOURCE_UNANNOTATED, KEYS_SOURCE);

        assertFalse(Files.exists(bridgeRule),
            "mirrored files are cleaned up like any other generated file when the annotations go away");
        assertTrue(Files.exists(keysRule),
            "the still-annotated class keeps its mirrored rule");
    }

    /** Compiles the app-fhe module with the two given sources. */
    private void compileFhe(String bridgeSource, String keysSource) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve("app-fhe"));
        Files.writeString(reactorRoot.resolve("app-fhe").resolve("pom.xml"),
            "<project><artifactId>app-fhe</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("app-fhe/src/main/java/com/example/fhe/NativeBridge.java", bridgeSource);
        harness.writeSourceFile("app-fhe/src/main/java/com/example/fhe/Keys.java", keysSource);
        harness.compile();
    }

    @Test
    void noMirrorConfig_writesNothingExtra() throws IOException {
        Files.createDirectories(reactorRoot.resolve("app-tests/.claude/rules"));
        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);

        try (var files = Files.list(reactorRoot.resolve("app-tests/.claude/rules"))) {
            assertTrue(files.findAny().isEmpty(),
                "without a .vibetags-mirror opt-in nothing is written into another module");
        }
    }

    @Test
    void editingTheConfig_regeneratesEvenThoughAnnotationsAreUnchanged() throws IOException {
        // The config lives in a sibling module, so it can never reach the compiling module's build
        // fingerprint. It must still bust the top-level short-circuit — hence the watched-input
        // cache entry. Starts naming a module that does not exist, so nothing is mirrored.
        mirrorTarget("app-tests", "../app-runtime\n");
        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);

        Path fromFhe = mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge");
        assertFalse(Files.exists(fromFhe), "precondition: app-fhe is not yet an accepted source");

        Files.writeString(reactorRoot.resolve("app-tests/.vibetags-mirror"), "../app-fhe\n",
            StandardCharsets.UTF_8);
        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);

        assertTrue(Files.exists(fromFhe),
            "editing .vibetags-mirror must invalidate the fingerprint short-circuit");
    }

    @Test
    void checkMode_reportsMissingMirroredFilesAsDrift_andWritesNothing() throws IOException {
        mirrorTarget("app-tests", "");

        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve("app-fhe"));
        Files.writeString(reactorRoot.resolve("app-fhe").resolve("pom.xml"),
            "<project><artifactId>app-fhe</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("app-fhe/src/main/java/com/example/fhe/NativeBridge.java", FHE_SOURCE);

        var diagnostics = harness.compileReturningDiagnostics("-Avibetags.check=true");

        String errors = diagnostics.stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(errors.contains("mirrored-app-fhe-com-example-fhe-NativeBridge.md"),
            "check mode must report an out-of-date mirrored file as drift; errors were:" + errors);
        assertFalse(Files.exists(mirrored("app-tests", "app-fhe", "com-example-fhe-NativeBridge")),
            "check mode must not write the file it reports");
    }

    @Test
    void targetWithoutGranularDirectory_isSkipped() throws IOException {
        // Config present, but the target opted into no scoped-rules directory — nothing to write to.
        Files.createDirectories(reactorRoot.resolve("app-tests"));
        Files.writeString(reactorRoot.resolve("app-tests/.vibetags-mirror"), "", StandardCharsets.UTF_8);

        compileModule("app-fhe", "com.example.fhe.NativeBridge", FHE_SOURCE);

        assertFalse(Files.exists(reactorRoot.resolve("app-tests/.claude")),
            "VibeTags never creates an opt-in directory that does not already exist");
    }
}
