package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSR 269 lifecycle the adapter plays under KSP, each case against real KSP2: what reaches the
 * processor, when it writes, and when it must not.
 */
class KspLifecycleTest {

    private static final String IMPORTS = "import se.deversity.vibetags.annotations.*\n\n";

    @TempDir
    Path root;

    private Path sources() throws IOException {
        return Files.createDirectories(root.resolve("src/main/kotlin"));
    }

    private Path write(String relative, String content) throws IOException {
        Path file = sources().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private void optIn(String... files) throws IOException {
        Files.writeString(root.resolve("build.gradle.kts"), "");
        for (String file : files) {
            Files.writeString(root.resolve(file), "");
        }
    }

    @Test
    void generatesGuardrailsForKotlinSources() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS
            + "@AILocked(reason = \"audited\") class Vault\n");

        KspHarness.Result result = new KspHarness(sources(), root).run();

        assertEquals("OK", result.exitCode(), () -> String.valueOf(result.errors()));
        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.a.Vault") && claude.contains("audited"), claude);
    }

    @Test
    void writesOnlyTheFilesThatAlreadyExist() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked class Vault\n");

        new KspHarness(sources(), root).run();

        // File presence is the only opt-in (invariant 1): nothing else may appear.
        assertFalse(Files.exists(root.resolve(".cursorrules")));
        assertFalse(Files.exists(root.resolve("GEMINI.md")));
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
    }

    @Test
    void warnsWhenTheRootOptionIsMissing() throws IOException {
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked class Vault\n");
        List<String> processed = new ArrayList<>();
        // A recording delegate: with no root the real processor would write at the JVM's working
        // directory, which is this module's checkout.
        SymbolProcessorProvider provider = env -> new KspGuardrailProcessor(env, new Recorder(processed));

        KspHarness.Result result = new KspHarness(sources(), root).withoutRootOption().run(provider);

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("vibetags.root is not set")),
            () -> "warnings: " + result.warnings());
        assertTrue(processed.contains("round"), "the processor still ran: " + processed);
    }

    @Test
    void driveOrderIsInitThenRoundsThenOneFinalRound() throws IOException {
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked class Vault\n");
        List<String> processed = new ArrayList<>();
        SymbolProcessorProvider provider = env -> new KspGuardrailProcessor(env, new Recorder(processed));

        new KspHarness(sources(), root).run(provider);

        assertEquals("init", processed.get(0));
        assertEquals("over", processed.get(processed.size() - 1));
        assertEquals(1, processed.stream().filter("over"::equals).count(), "exactly one final round: " + processed);
        assertTrue(processed.contains("root:com.a.Vault"), "the round carried the Kotlin class: " + processed);
    }

    @Test
    void anErrorElsewhereInTheBuildLeavesEveryFileUntouched() throws IOException {
        optIn();
        Files.writeString(root.resolve("CLAUDE.md"), "hand-written, not yet generated\n");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked(reason = \"r\") class Vault\n");
        // Another processor in the same KSP run reports an error: KSP calls onError, not finish.
        SymbolProcessorProvider failing = env -> new SymbolProcessor() {
            @Override
            public List<KSAnnotated> process(Resolver resolver) {
                env.getLogger().error("a sibling processor failed", null);
                return List.of();
            }
        };

        KspHarness.Result result = new KspHarness(sources(), root)
            .run(new se.deversity.vibetags.ksp.VibeTagsSymbolProcessorProvider(), failing);

        assertFalse(result.errors().isEmpty(), "the sibling's error must have been raised");
        assertEquals("hand-written, not yet generated\n", Files.readString(root.resolve("CLAUDE.md")),
            "an error round writes nothing, exactly as after a failed javac compile");
    }

    /**
     * Invariant 17 under KSP. The control proves the premise: in KSP's incremental mode a processor
     * that declares nothing is shown only the changed file. VibeTags must still regenerate the
     * whole module; without its aggregating association it rewrote CLAUDE.md from the one file and
     * dropped the untouched file's guardrail (this test's first run, before the fix).
     */
    @Test
    void anIncrementalRunStillWritesTheWholeModule(@TempDir Path controlRoot) throws IOException {
        assertEquals(1, filesAnIncrementalRunShows(controlRoot),
            "control: KSP's incremental mode must hand an ordinary processor only the dirty file");

        optIn("CLAUDE.md");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked(reason = \"vault\") class Vault\n");
        Path ledger = write("com/b/Ledger.kt", "package com.b\n" + IMPORTS
            + "@AILocked(reason = \"ledger\") class Ledger\n");
        new KspHarness(sources(), root).incremental(List.of()).run();
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("com.a.Vault"), "first build");

        Files.writeString(ledger, "package com.b\n" + IMPORTS
            + "@AILocked(reason = \"ledger\") class Ledger {\n    @AILocked(reason = \"new\") fun post() {}\n}\n");
        new KspHarness(sources(), root).incremental(List.of(ledger)).run();

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.b.Ledger.post()"), "the change reached the output:\n" + claude);
        assertTrue(claude.contains("com.a.Vault"),
            "the untouched file's guardrail survived an incremental run over Ledger.kt:\n" + claude);
    }

    /** How many files the second of two incremental KSP runs shows a processor that claims nothing. */
    private static int filesAnIncrementalRunShows(Path project) throws IOException {
        Path src = Files.createDirectories(project.resolve("src"));
        Files.writeString(src.resolve("A.kt"), "package p\nclass A\n");
        Path b = src.resolve("B.kt");
        Files.writeString(b, "package p\nclass B\n");
        AtomicInteger shown = new AtomicInteger(-1);
        SymbolProcessorProvider probe = env -> new SymbolProcessor() {
            @Override
            public List<KSAnnotated> process(Resolver resolver) {
                if (shown.get() < 0) {
                    List<Object> files = new ArrayList<>();
                    resolver.getAllFiles().iterator().forEachRemaining(files::add);
                    shown.set(files.size());
                }
                return List.of();
            }
        };
        new KspHarness(src, project).incremental(List.of()).run(probe);
        Files.writeString(b, "package p\nclass B { fun x() {} }\n");
        shown.set(-1);
        new KspHarness(src, project).incremental(List.of(b)).run(probe);
        return shown.get();
    }

    @Test
    void checkModeFailsTheBuildWhenAFileIsOutOfDate() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked(reason = \"r\") class Vault\n");

        KspHarness.Result result = new KspHarness(sources(), root).option("vibetags.check", "true").run();

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("VibeTags: check failed")),
            () -> "errors: " + result.errors());
        assertEquals("", Files.readString(root.resolve("CLAUDE.md")), "check mode writes nothing");
    }

    @Test
    void checkModePassesOnceTheFilesAreCurrent() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS + "@AILocked(reason = \"r\") class Vault\n");
        new KspHarness(sources(), root).run();

        KspHarness.Result result = new KspHarness(sources(), root).option("vibetags.check", "true").run();

        assertTrue(result.errors().isEmpty(), () -> "errors: " + result.errors());
        assertTrue(result.infos().stream().anyMatch(i -> i.contains("check passed")), () -> "infos: " + result.infos());
    }

    @Test
    void validationWarningsReachTheKspLog() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Vault.kt", "package com.a\n" + IMPORTS
            + "@AILocked(reason = \"r\") @AIDraft(instructions = \"write it\") class Vault\n");

        KspHarness.Result result = new KspHarness(sources(), root).run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("VibeTags") && w.contains("AIDraft")),
            () -> "warnings: " + result.warnings());
    }

    @Test
    void classAndEnumValuedMembersAreReadAsJavacWouldHandThemOver() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Api.kt", "package com.a\n" + IMPORTS
            + "class NewApi\n"
            + "class Api {\n"
            + "    @AISunset(jira = \"API-7\", replacement = NewApi::class) fun old() {}\n"
            + "    @AITestDriven(framework = [AITestDriven.Framework.MOCKITO]) fun tested() {}\n"
            + "}\n");

        KspHarness.Result result = new KspHarness(sources(), root).run();

        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("failed and was skipped")),
            () -> "warnings: " + result.warnings());
        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.a.NewApi"), "the Class-valued member reached the output:\n" + claude);
        assertTrue(claude.contains("API-7"), claude);
        assertTrue(claude.toUpperCase(java.util.Locale.ROOT).contains("MOCKITO"),
            "the enum-array member reached the output:\n" + claude);
    }

    /**
     * kapt writes no stub for a function whose JVM name a value class mangles, so its guardrail
     * reaches no file (docs/JVM-LANGUAGES.md, #681). KSP keeps that element set, so the paths match
     * across front ends, but unlike kapt it can see the loss and says so at build time.
     */
    @Test
    void aGuardrailOnAFunctionWithNoStubIsReportedNotSilentlyLost() throws IOException {
        optIn("CLAUDE.md");
        write("com/a/Ledger.kt", "package com.a\n" + IMPORTS
            + "@JvmInline value class AccountId(val raw: String)\n"
            + "class Ledger {\n"
            + "    @AILocked(reason = \"lost\") fun balanceFor(id: AccountId): Long = 0\n"
            + "    @AILocked(reason = \"kept\") fun total(): Long = 0\n"
            + "}\n");

        KspHarness.Result result = new KspHarness(sources(), root).run();

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.a.Ledger.total()"), claude);
        assertFalse(claude.contains("balanceFor"), "kapt's element set: no stub, no path\n" + claude);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("@AILocked on fun balanceFor in com.a.Ledger")
                && w.contains("reaches no guardrail file")),
            () -> "warnings: " + result.warnings());
    }

    @Test
    void noCompatibilityModeHasNoDefaultImpls() throws IOException {
        optIn(".vibetags-locks");
        write("com/a/Shape.kt", "package com.a\n" + IMPORTS
            + "interface Shape { @AILocked(reason = \"r\") fun describe(): String = \"\" }\n");

        new KspHarness(sources(), root).run();
        String compatible = Files.readString(root.resolve(".vibetags-locks"));
        Files.writeString(root.resolve(".vibetags-locks"), "");
        Files.deleteIfExists(root.resolve(".vibetags-cache"));
        new KspHarness(sources(), root).jvmDefault("no-compatibility").run();
        String withoutImpls = Files.readString(root.resolve(".vibetags-locks"));

        assertTrue(compatible.contains("com.a.Shape.DefaultImpls.describe(com.a.Shape)"), compatible);
        assertTrue(withoutImpls.contains("com.a.Shape.describe()"), withoutImpls);
        assertFalse(withoutImpls.contains("DefaultImpls"), withoutImpls);
    }

    /** Stands in for the processor, recording what the adapter hands it. */
    private static final class Recorder extends AbstractProcessor {
        private final List<String> events;

        Recorder(List<String> events) {
            this.events = events;
        }

        @Override
        public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
            super.init(env);
            events.add("init");
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (round.processingOver()) {
                events.add("over");
            } else {
                events.add("round");
                round.getRootElements().forEach(e -> events.add("root:" + e));
            }
            return false;
        }
    }
}
