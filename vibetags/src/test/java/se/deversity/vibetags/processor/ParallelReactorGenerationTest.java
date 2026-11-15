package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import se.deversity.vibetags.processor.internal.GenerationLock;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two modules of a parallel reactor generating into one root at once (#908).
 *
 * <p>Each module round saves its sidecar, reads every sidecar, merges, and writes the root files.
 * Nothing serialised that span across modules, so a module that read the sidecars before its
 * sibling saved one could write last, from a merge without the sibling, and the sibling's region
 * was gone from {@code CLAUDE.md} until the next build.
 *
 * <p>The window is milliseconds wide, so this test does not wait for it. The seam
 * {@code AIGuardrailProcessor.afterSidecarRead} (set through {@code setAfterSidecarRead}) holds module-core right after its sidecar read;
 * module-cli then compiles; module-core is released once module-cli has finished, or after a few
 * seconds if module-cli cannot finish because module-core holds the generation lock. Without the
 * lock module-cli runs to completion in the gap and module-core overwrites it, every time. Both run
 * in this JVM, as {@code mvn -T} runs them.
 */
@Tag("e2e")
// The seam is one static field: the race test owns it for its whole run, so this class's tests run
// one at a time (the suite otherwise runs methods concurrently, and a sibling clearing the seam left
// module-core unheld). InterruptedGenerationTest sets it too, so both hold SEAM_LOCK; each acts only
// on a thread of its own name.
@Execution(ExecutionMode.SAME_THREAD)
@org.junit.jupiter.api.parallel.ResourceLock(ParallelReactorGenerationTest.SEAM_LOCK)
class ParallelReactorGenerationTest {

    /** Held by every test class that sets {@code AIGuardrailProcessor.afterSidecarRead}. */
    static final String SEAM_LOCK = "AIGuardrailProcessor.afterSidecarRead";

    private static final String CORE_THREAD = "module-core";

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aModuleThatGeneratesWhileItsSiblingIsMidMergeKeepsItsRegion() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        ProcessorTestHarness core = module("module-core", "com.example.core.IrNode", """
            package com.example.core;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "core IR node")
            public class IrNode {}
            """);
        ProcessorTestHarness cli = module("module-cli", "com.example.cli.KartaCli", """
            package com.example.cli;
            import se.deversity.vibetags.annotations.AIAudit;
            @AIAudit(checkFor = {"Path Traversal"})
            public class KartaCli {}
            """);

        CountDownLatch coreHeld = new CountDownLatch(1);
        CountDownLatch releaseCore = new CountDownLatch(1);
        AIGuardrailProcessor.setAfterSidecarRead(() -> {
            if (CORE_THREAD.equals(Thread.currentThread().getName()) && coreHeld.getCount() > 0) {
                coreHeld.countDown();
                await(releaseCore, 60);
            }
        });
        try {
            raceTheModules(core, cli, coreHeld, releaseCore);
        } finally {
            AIGuardrailProcessor.setAfterSidecarRead(null);
        }
    }

    private void raceTheModules(ProcessorTestHarness core, ProcessorTestHarness cli,
                                CountDownLatch coreHeld, CountDownLatch releaseCore) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        Thread coreBuild = new Thread(() -> run(core, failures), CORE_THREAD);
        coreBuild.start();
        assertTrue(coreHeld.await(60, TimeUnit.SECONDS), () -> "module-core never reached its sidecar read: " + failures);

        Thread cliBuild = new Thread(() -> run(cli, failures), "module-cli");
        cliBuild.start();
        // Without the lock module-cli finishes here, inside module-core's read-to-write gap. With
        // it module-cli waits for module-core, so this times out and module-core is let go.
        cliBuild.join(TimeUnit.SECONDS.toMillis(5));
        releaseCore.countDown();
        coreBuild.join();
        cliBuild.join();
        assertTrue(failures.isEmpty(), () -> "a module build threw: " + failures);

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("com.example.core.IrNode"), () -> "module-core's region is missing:\n" + claude);
        assertTrue(claude.contains("com.example.cli.KartaCli"),
            () -> "module-cli's region is missing: module-core wrote CLAUDE.md last, from a merge read "
                + "before module-cli saved its sidecar. The file was:\n" + claude);
    }

    @Test
    void aModuleRoundOfAnOptedInRootGeneratesUnderTheLock() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        module("module-core", "com.example.core.IrNode", LOCKED_SOURCE).compile();

        assertTrue(Files.exists(root.resolve(".vibetags-generate.lock")),
            "a reactor module writing into an opted-in root must take the generation lock");
    }

    @Test
    void aModuleRoundOfARootWithNoOptInCreatesNoLockFile() throws IOException {
        // Invariant 1 and the third-party corpus: a project that never opted in has nothing written.
        module("module-core", "com.example.core.IrNode", LOCKED_SOURCE).compile();

        assertFalse(Files.exists(root.resolve(".vibetags-generate.lock")),
            "a root with no opt-in has no root files to race over and must not get a lock file");
    }

    @Test
    void aModuleThatOptedInOnItsOwnGeneratesUnderTheLock() throws Exception {
        // #1074. The root has no opt-in, but module-core does. Its round still saves a sidecar and
        // flushes .vibetags-cache at the root, the same shared files a sibling flushes, so the
        // flush must wait for a sibling's generation like any other module round's.
        Files.createDirectories(root.resolve("module-core"));
        Files.createFile(root.resolve("module-core").resolve("CLAUDE.md"));
        ProcessorTestHarness core = module("module-core", "com.example.core.IrNode", LOCKED_SOURCE);

        assertWaitsForASiblingsGeneration(core, "module-core");

        assertTrue(Files.exists(root.resolve(".vibetags-cache")),
            "precondition: the round writes shared state at the root, which is what needs the lock");
        assertTrue(Files.readString(root.resolve("module-core").resolve("CLAUDE.md"), StandardCharsets.UTF_8)
            .contains("com.example.core.IrNode"), "module-core's own CLAUDE.md must still be generated");
    }

    @Test
    void theRootsOwnRoundWaitsForASiblingModule() throws Exception {
        // #1074. Gradle compiles a root project that has sources of its own alongside its
        // subprojects. The root round reads every sidecar and writes the root files, so once a
        // sibling module exists it races that sibling exactly as two modules would.
        Files.createFile(root.resolve("CLAUDE.md"));
        module("module-cli", "com.example.cli.KartaCli", AUDITED_SOURCE).compile();
        ProcessorTestHarness rootModule = rootModule();

        assertWaitsForASiblingsGeneration(rootModule, "root");

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("com.example.app.App") && claude.contains("com.example.cli.KartaCli"),
            () -> "both regions must be merged once the root round runs:\n" + claude);
    }

    @Test
    void aSingleModuleBuildCreatesNoLockFile() throws IOException {
        // The root round of a project with no sibling module has nothing to race: no lock file
        // appears in the most common layout there is.
        Files.createFile(root.resolve("CLAUDE.md"));
        rootModule().compile();

        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("com.example.app.App"),
            "precondition: the root round generated");
        assertFalse(Files.exists(root.resolve(".vibetags-generate.lock")),
            "a single-module build has no sibling to race and must not get a lock file");
    }

    @Test
    void theRootRoundOfAGradleRootWithSubprojectsLocksOnItsFirstBuild() throws IOException {
        // #1081. No sibling has locked yet, so there is no lock file to go by, but settings.gradle
        // declares a subproject that compiles alongside the root. Generating unlocked here is the
        // first-build race #1074 left open.
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("module-cli"));
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name = 'app'\ninclude 'module-cli'\n", StandardCharsets.UTF_8);
        rootModule("build.gradle", "plugins { id 'java' }").compile();

        assertTrue(Files.exists(root.resolve(".vibetags-generate.lock")),
            "the root round of a root that declares subprojects must generate under the lock");
    }

    @Test
    void theRootRoundOfAMavenRootWithModulesLocksOnItsFirstBuild() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("module-cli"));
        rootModule("pom.xml", "<project><artifactId>app</artifactId>"
            + "<modules><module>module-cli</module></modules></project>").compile();

        assertTrue(Files.exists(root.resolve(".vibetags-generate.lock")),
            "the root round of a pom that declares modules must generate under the lock");
    }

    @Test
    void theRootRoundLocksWhenOnlyADeclaredSubprojectOptedIn() throws IOException {
        // The module-only opt-in of #1074, seen from the root round: the root has no AI file, the
        // subproject does, and both rounds write the shared sidecars and cache at the root.
        Files.createDirectories(root.resolve("module-cli"));
        Files.createFile(root.resolve("module-cli").resolve("CLAUDE.md"));
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"module-cli\")\n", StandardCharsets.UTF_8);
        rootModule("build.gradle.kts", "plugins { java }").compile();

        assertTrue(Files.exists(root.resolve(".vibetags-generate.lock")),
            "a subproject's opt-in is enough for the root round to share root state with it");
    }

    @Test
    void aSingleProjectGradleBuildCreatesNoLockFile() throws IOException {
        // A settings.gradle that only names the root project declares no sibling to race.
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'app'\n", StandardCharsets.UTF_8);
        rootModule("build.gradle", "plugins { id 'java' }").compile();

        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("com.example.app.App"),
            "precondition: the root round generated");
        assertFalse(Files.exists(root.resolve(".vibetags-generate.lock")),
            "a Gradle build with no subproject must not get a lock file");
    }

    @Test
    void aRootWithSubprojectsButNoOptInCreatesNoLockFile() throws IOException {
        // Invariant 1: declaring subprojects is not an opt-in.
        Files.createDirectories(root.resolve("module-cli"));
        Files.writeString(root.resolve("settings.gradle"), "include 'module-cli'\n", StandardCharsets.UTF_8);
        rootModule("build.gradle", "plugins { id 'java' }").compile();

        assertFalse(Files.exists(root.resolve(".vibetags-generate.lock")),
            "a project that never opted in must have nothing written, the lock file included");
    }

    /** Holds the generation lock as a sibling would, and asserts {@code module} waits for it. */
    private void assertWaitsForASiblingsGeneration(ProcessorTestHarness module, String name) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread build;
        try (GenerationLock sibling = GenerationLock.acquire(root, "module-sibling", null)) {
            assertTrue(sibling.held(), "precondition: the sibling holds the generation lock");
            build = new Thread(() -> {
                run(module, failures);
                done.countDown();
            }, name);
            build.start();
            assertFalse(done.await(2, TimeUnit.SECONDS),
                name + " generated while a sibling held the generation lock: " + failures);
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), name + " must finish once the lock is free");
        build.join();
        assertTrue(failures.isEmpty(), () -> name + " threw: " + failures);
    }

    /** The root project itself, with a build file and sources of its own. */
    private ProcessorTestHarness rootModule() throws IOException {
        return rootModule("pom.xml", "<project><artifactId>app</artifactId></project>");
    }

    /** The root project, with the named build file and sources of its own. */
    private ProcessorTestHarness rootModule(String buildFile, String buildContent) throws IOException {
        Files.writeString(root.resolve(buildFile), buildContent, StandardCharsets.UTF_8);
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/app/App.java", """
            package com.example.app;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "entry point")
            public class App {}
            """);
        return harness;
    }

    @Test
    void anEarlyExitedRoundClearsItsDigestUnderTheLock() throws Exception {
        // #916. A round skipped as unchanged that then sees another processor's generated sources
        // clears its recorded digest and flushes the cache. That flush re-reads and rewrites the
        // shared .vibetags-cache, so outside the lock it could race a sibling's generation flush,
        // and the clear itself could be lost to it, leaving a digest that vouches for a skip.
        Files.createFile(root.resolve("CLAUDE.md"));
        ProcessorTestHarness core = module("module-core", "com.example.core.IrNode", LOCKED_SOURCE);
        core.compile();

        List<Throwable> failures = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread rebuild;
        try (GenerationLock sibling = GenerationLock.acquire(root, "module-sibling", null)) {
            assertTrue(sibling.held(), "precondition: the sibling holds the generation lock");
            rebuild = new Thread(() -> {
                try {
                    for (Diagnostic<? extends JavaFileObject> d
                            : core.compileWithReturningDiagnostics(new GeneratesASourceAfterTheFirstRound())) {
                        synchronized (notes) {
                            notes.add(d.getMessage(Locale.ROOT));
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
                done.countDown();
            }, CORE_THREAD);
            rebuild.start();
            assertFalse(done.await(2, TimeUnit.SECONDS),
                "the early-exited round flushed the shared cache while a sibling held the generation "
                    + "lock: " + notes);
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "the rebuild must finish once the lock is free");
        rebuild.join();
        assertTrue(failures.isEmpty(), () -> "the rebuild threw: " + failures);
        assertTrue(notes.stream().anyMatch(n -> n.contains("another processor generated sources")),
            () -> "precondition: the rebuild must take the sources-appeared branch, not generate: " + notes);
    }

    /**
     * Runs VibeTags and, after its first round, generates one source, as an annotation processor
     * sharing the compilation can. Only a round skipped as unchanged then sees sources it never
     * recorded, which is the branch {@link #anEarlyExitedRoundClearsItsDigestUnderTheLock} needs.
     */
    private static final class GeneratesASourceAfterTheFirstRound extends AbstractProcessor {
        private final AIGuardrailProcessor delegate = new AIGuardrailProcessor();
        private boolean generated;

        @Override
        public synchronized void init(ProcessingEnvironment env) {
            super.init(env);
            delegate.init(env);
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            delegate.process(annotations, roundEnv);
            if (!generated && !roundEnv.processingOver()) {
                generated = true;
                try (Writer out = processingEnv.getFiler()
                        .createSourceFile("com.example.core.GeneratedLater").openWriter()) {
                    out.write("package com.example.core;\npublic class GeneratedLater {}\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }
    }

    private static final String LOCKED_SOURCE = """
        package com.example.core;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "core IR node")
        public class IrNode {}
        """;

    private static final String AUDITED_SOURCE = """
        package com.example.cli;
        import se.deversity.vibetags.annotations.AIAudit;
        @AIAudit(checkFor = {"Path Traversal"})
        public class KartaCli {}
        """;

    private ProcessorTestHarness module(String name, String fqn, String source) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name).resolve("pom.xml"),
            "<project><artifactId>" + name + "</artifactId></project>", StandardCharsets.UTF_8);
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile(name + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        return harness;
    }

    private static void run(ProcessorTestHarness module, List<Throwable> failures) {
        try {
            module.compile();
        } catch (Throwable t) {
            synchronized (failures) {
                failures.add(t);
            }
        }
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
