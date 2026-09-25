package se.deversity.vibetags.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vibetags doctor} — exit code 0 must mean "the processor would behave" and 1 must
 * mean "something needs action". Each test builds the smallest project directory that
 * produces one verdict.
 */
class DoctorCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    private int doctor() {
        PrintStream stream = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        return Main.run(new String[]{"doctor"}, stream, stream, dir);
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private void mavenProjectWiredForVibeTags() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <dependencies>
                <dependency><artifactId>vibetags-annotations</artifactId></dependency>
              </dependencies>
              <annotationProcessorPaths>
                <path><artifactId>vibetags-processor</artifactId></path>
              </annotationProcessorPaths>
            </project>
            """);
    }

    /**
     * {@code doctor --context} (issue #840) weighs what a session loads. The numbers are asserted
     * against the bytes the test wrote, and the stacked block is the case it exists to expose:
     * one section appearing twice in one file, the shape a per-source-set render left behind (#839).
     */
    @Test
    void contextReport_weighsTheFilesAndNamesASectionThatAppearsTwice() throws Exception {
        mavenProjectWiredForVibeTags();
        String block = """
            <project_guardrails>
              <scoped_rules>
                <note>rules load by glob</note>
                <element path="a.One"/>
                <element path="a.Two"/>
              </scoped_rules>
            </project_guardrails>
            """;
        String claude = "# Hand-written\n\n<!-- VIBETAGS-START -->\n" + block
            + block.replace("a.One\"/>\n    <element path=\"a.Two", "a.Three") + "<!-- VIBETAGS-END -->\n";
        Files.writeString(dir.resolve("CLAUDE.md"), claude);
        Files.createDirectories(dir.resolve(".claude/rules"));
        Files.writeString(dir.resolve(".claude/rules/a-One.md"), "12345");
        Files.writeString(dir.resolve(".claude/rules/a-Two.md"), "123");

        PrintStream stream = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        int code = Main.run(new String[]{"doctor", "--context"}, stream, stream, dir);

        assertEquals(0, code, "a heavy file is information, not a finding:\n" + out());
        int total = claude.getBytes(StandardCharsets.UTF_8).length;
        String generated = claude.substring(claude.indexOf("-->") + 3, claude.lastIndexOf("<!-- VIBETAGS-END"));
        int generatedBytes = generated.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(out().contains(String.format(java.util.Locale.ROOT, "%,9d B", total)), out());
        assertTrue(out().contains(String.format(java.util.Locale.ROOT, "generated %,d B (%d%%)",
            generatedBytes, generatedBytes * 100 / total)), out());
        assertTrue(out().lines().anyMatch(l -> l.contains("<scoped_rules>") && l.contains("3 entries")
            && l.contains("appears 2 times")), "three elements over two copies of the section:\n" + out());
        assertTrue(out().lines().anyMatch(l -> l.contains(".claude/rules/") && l.contains("8 B")
            && l.contains("2 files")), out());
    }

    @Test
    void withoutContext_doctorDoesNotWeighAnything() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "<!-- VIBETAGS-START -->\n<!-- VIBETAGS-END -->\n");
        doctor();
        assertFalse(out().contains("context weight"), out());
    }
    @Test
    void wiredProjectWithIntactMarkers_isHealthy() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), """
            hand-authored intro
            <!-- VIBETAGS-START -->
            generated
            <!-- VIBETAGS-END -->
            """);

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("result: healthy"), out());
        assertTrue(out().contains("claude -> CLAUDE.md"), out());
    }

    /** A Kotlin build on KSP wires vibetags-ksp, which runs the processor; that is wired (#496). */
    @Test
    void kspWiredGradleProject_isHealthy() throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), """
            plugins { id("com.google.devtools.ksp") version "2.3.12" }
            dependencies {
                compileOnly("se.deversity.vibetags:vibetags-annotations")
                ksp("se.deversity.vibetags:vibetags-ksp")
            }
            """);
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("processor wired: yes (vibetags-ksp)"), out());
    }

    @Test
    void missingProcessorWiring_needsAction() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("vibetags-processor"), out());
    }

    @Test
    void noOptInFiles_needsAction() throws Exception {
        mavenProjectWiredForVibeTags();

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("no opt-in files present"), out());
        assertTrue(out().contains("vibetags init"), out());
    }

    @Test
    void unbalancedMarkers_needAction() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"),
            "<!-- VIBETAGS-START -->\nan END marker someone deleted\n");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("unbalanced VIBETAGS markers in CLAUDE.md"), out());
    }

    @Test
    void unreadableMarkerFile_isAFindingNotAHealthyPass() throws Exception {
        // 0xFF can never appear in UTF-8, so Files.readString fails on this file. A file
        // doctor cannot read is exactly the state it must not report as "all intact":
        // the writer needs to read it to preserve hand-authored content.
        mavenProjectWiredForVibeTags();
        Files.write(dir.resolve("CLAUDE.md"), new byte[]{(byte) 0xFF, (byte) 0xFE});

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("could not read CLAUDE.md"), out());
        assertFalse(out().contains("result: healthy"), out());
    }

    @Test
    void unreadableBuildFile_reportsUnknownWiringInsteadOfGuessing() throws Exception {
        Files.write(dir.resolve("pom.xml"), new byte[]{(byte) 0xFF});
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("could not read pom.xml"), out());
        assertFalse(out().contains("not found in pom.xml"),
            "doctor must not claim the wiring is missing when it could not read the file");
    }

    @Test
    void agentsMdNextToOtherConfigs_isExplainedAsAPointer() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        Files.writeString(dir.resolve("AGENTS.md"), "see CLAUDE.md");

        int code = doctor();

        // The pointer case is a note, not a finding: this is the processor's documented rule.
        assertEquals(0, code, out());
        assertTrue(out().contains("hand-authored pointer"), out());
    }

    @Test
    void noBuildFile_needsAction() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("no build file found"), out());
    }

    // ------------------------------------------------------------------ Groovy field guardrails

    private Path sourceFile(String relPath, String source) throws Exception {
        Path file = dir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    @Test
    void groovyFieldGuardrail_isReportedAsDropped() throws Exception {
        // groovyc's Java stubs carry no fields at all, so a field-targeted guardrail generates
        // nothing and the build says nothing (#494). Doctor is the tool that can still see the
        // .groovy source and say so, with the file, the line and the annotation.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/groovy/com/example/Customer.groovy", """
            package com.example

            import se.deversity.vibetags.annotations.AIPrivacy

            class Customer {
                @AIPrivacy(dataType = "email")
                String billingEmail

                String plainField
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Customer.groovy"), out());
        assertTrue(out().contains("@AIPrivacy"), out());
        assertTrue(out().contains("billingEmail"), out());
        assertTrue(out().toLowerCase().contains("dropped"), out());
    }

    @Test
    void groovyMethodAndClassGuardrails_areNotFlagged() throws Exception {
        // Types, constructors, methods and parameters all survive into groovyc's stubs; only
        // fields are missing. A doctor that cries wolf on the levels that work gets ignored.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/groovy/com/example/Billing.groovy", """
            package com.example

            import se.deversity.vibetags.annotations.AILocked
            import se.deversity.vibetags.annotations.AICore

            @AICore(sensitivity = "high", note = "settlement core")
            class Billing {
                @AILocked(reason = "wire format")
                def charge(BigDecimal amount) {
                    amount
                }
            }
            """);

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("groovy sources:"), out());
        assertTrue(out().contains("no field-level guardrails"), out());
    }

    @Test
    void projectWithoutGroovy_printsNoGroovyLine() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(0, doctor(), out());
        assertFalse(out().contains("groovy sources:"),
            "a Java-only project has nothing Groovy to report on: " + out());
    }

    @Test
    void groovyFieldGuardrail_onOneLineAndFullyQualified_isStillFound() throws Exception {
        // Groovy idiom puts annotation and declaration on one line, and script-style files use
        // the fully-qualified form without an import. Both shapes must reach the same finding.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/groovy/com/example/Card.groovy", """
            package com.example

            class Card {
                @se.deversity.vibetags.annotations.AIPrivacy(dataType = "pan") String cardNumber
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("@AIPrivacy") && out().contains("cardNumber"), out());
    }

    @Test
    void groovyFieldGuardrail_behindCommentsAndInitializer_isStillFound() throws Exception {
        // Comments and blank lines between the annotation and its field must be skipped, and a
        // field whose initializer calls a method is still a field - the parentheses that
        // disqualify a declaration are the ones before the '='.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/groovy/com/example/Token.groovy", """
            package com.example

            import se.deversity.vibetags.annotations.AISecureLogging

            class Token {
                @AISecureLogging(maskWith = "***")
                /* rotated hourly
                 * by the scheduler */
                // never log it
                def authToken = TokenSource.next()
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("@AISecureLogging") && out().contains("authToken"), out());
    }

    @Test
    void groovyAnnotationWithNoDeclarationAfterIt_isNotFlagged() throws Exception {
        // An annotation as the last meaningful token binds nothing; there is no field to lose.
        // Same for a field-only annotation someone left above a class declaration - the class
        // level survives the stubs, so there is nothing truthful for doctor to report.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/groovy/com/example/Trailing.groovy", """
            package com.example

            import se.deversity.vibetags.annotations.AIPrivacy

            @AIPrivacy(dataType = "misplaced")
            class Trailing {
            }
            // dangling annotation below, nothing after it
            // @AIPrivacy would go here
            """);

        assertEquals(0, doctor(), out());
        assertFalse(out().toLowerCase().contains("dropped"), out());
    }

    @Test
    void unreadableGroovyFile_isAFindingNotASilentPass() throws Exception {
        // Same policy as the marker checks: "could not check" reported as "checked, fine" is
        // the one lie a health tool must not tell.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.write(dir.resolve("src/main/groovy/Broken.groovy"), new byte[]{(byte) 0xFF, (byte) 0xFE});

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("could not read"), out());
        assertTrue(out().contains("Broken.groovy"), out());
    }

    @Test
    void groovyUnderBuildDirectories_isNotScanned() throws Exception {
        // build/ and target/ hold generated or copied sources; flagging those reports the
        // build's plumbing, not the developer's code.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("build/tmp/Generated.groovy", """
            class Generated {
                @se.deversity.vibetags.annotations.AIPrivacy(dataType = "x")
                String copied
            }
            """);

        assertEquals(0, doctor(), out());
        assertFalse(out().contains("Generated.groovy"), out());
    }

    @Test
    void groovyInsideANestedCheckout_isNotScanned() throws Exception {
        // Claude Code keeps agent worktrees under .claude/worktrees/, each a full checkout of the
        // repository. On this repository 36 of doctor's 54 findings were one Groovy example
        // repeated in 36 worktrees, burying the 18 real ones (#842). A directory holding a .git
        // file (a worktree) or a .git directory (a nested clone) is another checkout, whatever it
        // is called, so neither is scanned. The project's own source still is.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        String fieldGuardrail = """
            class %s {
                @se.deversity.vibetags.annotations.AIPrivacy(dataType = "x")
                String copied
            }
            """;
        sourceFile(".claude/worktrees/agent-1/.git", "gitdir: ../../../.git/worktrees/agent-1\n");
        sourceFile(".claude/worktrees/agent-1/src/main/groovy/InWorktree.groovy",
            fieldGuardrail.formatted("InWorktree"));
        Files.createDirectories(dir.resolve("vendor/other-repo/.git"));
        sourceFile("vendor/other-repo/src/InClone.groovy", fieldGuardrail.formatted("InClone"));
        sourceFile("src/main/groovy/Own.groovy", fieldGuardrail.formatted("Own"));

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Own.groovy"), "the project's own source is still scanned: " + out());
        assertFalse(out().contains("InWorktree.groovy"), out());
        assertFalse(out().contains("InClone.groovy"), out());
    }

    // ------------------------------------------------------------------ Kotlin value-class functions
    //
    // Each case states one kapt behaviour measured on Kotlin 2.4.10 in #681 / #689: a function whose
    // JVM name is mangled by a value class is absent from kapt's Java stub, so every guardrail on it
    // reaches no processor. The negatives are the look-alikes that were measured to survive.

    private static final String ACCOUNT_ID = """
        package com.example.ledger

        @JvmInline
        value class AccountId(val raw: String)
        """;

    private void kotlinProject() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/example/ledger/AccountId.kt", ACCOUNT_ID);
    }

    @Test
    void kotlinFunctionTakingValueClass_isReportedAsLost() throws Exception {
        // The shape examples/kotlin pins: balanceFor(AccountId) is in no stub and no generated file.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/AccountLedger.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            class AccountLedger {
                @AILocked(reason = "reconciled nightly")
                fun balanceFor(account: AccountId): Long = account.raw.length.toLong()

                fun unguarded(account: AccountId): Long = 0L
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("AccountLedger.kt:7"), out());
        assertTrue(out().contains("@AILocked"), out());
        assertTrue(out().contains("fun balanceFor"), out());
        assertTrue(out().contains("value class com.example.ledger.AccountId"), out());
        assertTrue(out().contains("@JvmName(\"balanceFor\")"), out());
        assertTrue(out().contains("heuristic"), "the output must say what kind of check this is: " + out());
        assertFalse(out().contains("unguarded"),
            "an unannotated value-class function loses nothing: " + out());
    }

    @Test
    void kotlinFunctionReturningStdlibValueClass_isReportedAsLost() throws Exception {
        // Measured in #689: a function returning kotlin.time.Duration was absent from the stub.
        // A parameter guardrail on a mangled function goes with it (#496 spike, UserId? and ULong).
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Timeouts.kt", """
            package com.example.ledger

            import kotlin.time.Duration
            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AIPerformance

            class Timeouts {
                @AIPerformance(constraint = "called per request")
                fun budgetFor(
                    route: String
                ): Duration = Duration.ZERO

                fun count(@AIInputSanitized(AIInputSanitized.SanitizerType.XSS) n: UInt?): Int = 0
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Timeouts.kt:9 @AIPerformance on fun budgetFor"), out());
        assertTrue(out().contains("kotlin.time.Duration"), out());
        assertTrue(out().contains("Timeouts.kt:13 @AIInputSanitized (parameter n) on fun count"), out());
        assertTrue(out().contains("kotlin.UInt"), out());
    }

    @Test
    void kotlinResultParameter_isNotFlagged() throws Exception {
        // kotlin.Result is a value class the compiler does not mangle as a parameter:
        // settle(Result<Long>) renders as settle(java.lang.Object) in examples/kotlin.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Settlement.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            class Settlement {
                @AILocked(reason = "payout contract")
                fun settle(outcome: Result<Long>): String = "settled"
            }
            """);

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("kotlin sources:"), out());
    }

    @Test
    void kotlinValueClassAsTypeArgument_isNotFlagged() throws Exception {
        // many(List<AccountId>) was kept in #689's measurement: a type argument does not mangle.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Batch.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            class Batch {
                @AILocked(reason = "batch format")
                fun many(ids: List<AccountId>): Map<AccountId, Long> = emptyMap()
            }
            """);

        assertEquals(0, doctor(), out());
    }

    @Test
    void kotlinValueClassFunctionWithJvmName_isNotFlagged() throws Exception {
        // An explicit @JvmName switches mangling off: #689 measured closeAccount(java.lang.String) in
        // the stub. It is also the workaround the finding recommends, so it must clear the finding.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Closer.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            class Closer {
                @JvmName("closeAccount")
                @AILocked(reason = "closure is audited")
                fun close(account: AccountId) {
                }

                @AILocked(reason = "same, one line") @kotlin.jvm.JvmName("closeAll") fun closeAll(id: AccountId) {}
            }
            """);

        assertEquals(0, doctor(), out());
    }

    @Test
    void kotlinGuardrailOnNonFunctions_isNotFlagged() throws Exception {
        // Types reach the stub, and a bare guardrail on a property lands on its backing field, which
        // keeps its name (#692). Text inside strings and comments declares nothing.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Holder.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked
            import se.deversity.vibetags.annotations.AIPrivacy

            @AILocked(reason = "fun lookup(id: AccountId) is described here, not declared")
            class Holder(val id: AccountId) {
                @AIPrivacy
                val owner: AccountId = id

                // @AILocked fun commented(id: AccountId) {}
                /* @AILocked
                   fun blockCommented(id: AccountId) {} */
                fun plain(id: AccountId) {}
            }
            """);

        assertEquals(0, doctor(), out());
    }

    @Test
    void kotlinValueClassFromAnotherFileAndPackage_isResolvedThroughTheImport() throws Exception {
        // AccountId is declared in AccountId.kt, package com.example.ledger. A function in another
        // file and package that imports it is lost; a function that imports a same-named ordinary
        // class, or java.time.Duration, is not.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/api/Accounts.kt", """
            package com.example.api

            import com.example.ledger.AccountId
            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "public lookup")
            fun lookup(id: AccountId): String = id.raw
            """);
        sourceFile("src/main/kotlin/com/example/legacy/Legacy.kt", """
            package com.example.legacy

            import com.example.legacy.model.AccountId
            import java.time.Duration
            import se.deversity.vibetags.annotations.AILocked

            class Legacy {
                @AILocked(reason = "a different AccountId, an ordinary class")
                fun find(id: AccountId): String = ""

                @AILocked(reason = "java.time.Duration is an ordinary class")
                fun timeout(): Duration = Duration.ZERO
            }
            """);
        sourceFile("src/main/kotlin/com/example/legacy/model/AccountId.kt", """
            package com.example.legacy.model

            class AccountId(val raw: String)
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Accounts.kt:7 @AILocked on fun lookup"), out());
        assertTrue(out().contains("com.example.ledger.AccountId"), out());
        assertFalse(out().contains("fun find"), out());
        assertFalse(out().contains("fun timeout"), out());
    }

    @Test
    void kotlinFunctionTakingAnExposedValueClass_isStillReportedAsLost() throws Exception {
        // @JvmExposeBoxed on a value class exposes that class's own members, not the functions
        // elsewhere that take it: in #692's kapt build takesBoxedClass(BoxedId) was absent from
        // CLAUDE.md. #688 stayed silent on any file mentioning the annotation.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Exposed.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @OptIn(ExperimentalStdlibApi::class)
            @JvmExposeBoxed
            @JvmInline
            value class Cents(val raw: Long)

            class Exposed {
                @AILocked(reason = "boxed variant exists")
                fun charge(amount: Cents) {}
            }
            """);
        sourceFile("src/main/kotlin/com/example/ledger/UsesExposed.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "the class itself is exposed boxed")
            fun refund(amount: Cents) {}
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Exposed.kt:12 @AILocked on fun charge"), out());
        assertTrue(out().contains("UsesExposed.kt:6 @AILocked on fun refund"), out());
    }

    @Test
    void kotlinModuleCompiledWithExposeBoxed_keepsATopLevelFunctionAndSaysSo() throws Exception {
        // Under -Xjvm-expose-boxed, #692 measured controlValueParam(AccountId) kept, as
        // controlValueParam(com.example.shapes.AccountId). #688 skipped the whole check instead.
        kotlinProject();
        Files.writeString(dir.resolve("build.gradle.kts"), """
            kotlin { compilerOptions { freeCompilerArgs.add("-Xjvm-expose-boxed") } }
            """);
        sourceFile("src/main/kotlin/com/example/ledger/AccountLedger.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "reconciled nightly")
            fun balanceFor(account: AccountId): Long = 0L
            """);

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("-Xjvm-expose-boxed"), out());
        assertFalse(out().contains("fun balanceFor"), out());
    }

    // Every case below states shapes measured in #692 on Kotlin 2.4.10: one kapt build with an
    // @AILocked or @AIInputSanitized on each shape, read back from the generated CLAUDE.md. The line
    // numbers in the assertions count from the text block's first line.

    @Test
    void kotlinExtensionReceiverOfValueClass_isReportedAsLost() throws Exception {
        // describe-VBQJbmA, describeOrEmpty-6_y_IbE and memberDescribe-VBQJbmA: a receiver is a
        // parameter on the JVM, so it mangles the name and kapt leaves the function out. A value
        // class as the receiver's type argument (List<AccountId>) kept firstRaw.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Extensions.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "display format")
            fun AccountId.describe(): String = raw

            @AILocked(reason = "display format")
            fun AccountId?.describeOrEmpty(): String = this?.raw ?: ""

            @AILocked(reason = "a type argument does not mangle")
            fun List<AccountId>.firstRaw(): String = first().raw

            class Formatter {
                @AILocked(reason = "member extension")
                fun AccountId.memberDescribe(): String = raw
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Extensions.kt:6 @AILocked on fun describe"), out());
        assertTrue(out().contains("Extensions.kt:9 @AILocked on fun describeOrEmpty"), out());
        assertTrue(out().contains("Extensions.kt:16 @AILocked on fun memberDescribe"), out());
        assertFalse(out().contains("fun firstRaw"), out());
    }

    @Test
    void kotlinTopLevelFunctionReturningValueClass_isNotFlagged() throws Exception {
        // A return type mangles only a member's name. makeId(), timeoutTop() returning Duration,
        // makeU() returning UInt, a private one, an extension with a plain receiver and a top-level
        // suspend function returning AccountId all reached CLAUDE.md under their plain names.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Factories.kt", """
            package com.example.ledger

            import kotlin.time.Duration
            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "id format")
            fun makeId(): AccountId = AccountId("m")

            @AILocked(reason = "timeouts")
            private fun timeoutTop(): Duration? = Duration.ZERO

            @AILocked(reason = "counter")
            fun String.toCount(): UInt = 1u

            @AILocked(reason = "suspend")
            suspend fun fetchId(): AccountId = AccountId("x")
            """);

        assertEquals(0, doctor(), out());
    }

    @Test
    void kotlinMemberReturningValueClass_isReportedAsLost_inEveryKindOfBody() throws Exception {
        // Lost: a member of an object, of a companion, of an interface, and a suspend member
        // (fetchMember-9bTijhI). A top-level suspend function taking a value class is lost too
        // (fetchFor-eWJBmvc): the parameter mangles it, suspend or not.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Members.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            object Registry {
                @AILocked(reason = "o")
                fun current(): AccountId = AccountId("o")
            }

            class WithCompanion {
                companion object {
                    @AILocked(reason = "c")
                    fun make(): AccountId = AccountId("c")
                }
            }

            interface Lookup {
                @AILocked(reason = "i")
                fun find(): AccountId
            }

            class Fetcher {
                @AILocked(reason = "s")
                suspend fun fetch(): AccountId = AccountId("s")
            }

            @AILocked(reason = "t")
            suspend fun fetchFor(id: AccountId): String = id.raw
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Members.kt:7 @AILocked on fun current"), out());
        assertTrue(out().contains("Members.kt:13 @AILocked on fun make"), out());
        assertTrue(out().contains("Members.kt:19 @AILocked on fun find"), out());
        assertTrue(out().contains("Members.kt:24 @AILocked on fun fetch"), out());
        assertTrue(out().contains("Members.kt:28 @AILocked on fun fetchFor"), out());
    }

    @Test
    void kotlinPropertyAccessorGuardrails_areReportedWhereTheAccessorIsMangled() throws Exception {
        // A bare or @field: guardrail lands on the backing field, which keeps its name (Props.a,
        // Props.d). A top-level getter keeps its name too (getTopIdGet()). Every setter is mangled
        // (setTopIdSet-VBQJbmA, setC-VBQJbmA, and @setparam: with it), and so is a member getter
        // (getB--QnrX9o), including a constructor property's. @get:JvmName kept idValue().
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Props.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AILocked

            @get:AILocked(reason = "top-level getter keeps its name")
            val topId: AccountId = AccountId("t")

            @set:AILocked(reason = "top-level setter is mangled")
            var topAssigned: AccountId = AccountId("t")

            class Account {
                @AILocked(reason = "lands on the backing field")
                val bare: AccountId = AccountId("a")

                @field:AILocked(reason = "field")
                val stored: AccountId = AccountId("f")

                @get:AILocked(reason = "member getter is mangled")
                val owner: AccountId get() = AccountId("o")

                @set:AILocked(reason = "member setter is mangled")
                var assigned: AccountId = AccountId("s")

                @setparam:AIInputSanitized(AIInputSanitized.SanitizerType.XSS)
                var incoming: AccountId = AccountId("i")

                @get:JvmName("namedId")
                @get:AILocked(reason = "explicit getter name")
                val named: AccountId = AccountId("n")

                @get:AILocked(reason = "plain type")
                val label: String = "l"
            }

            class Holder(@get:AILocked(reason = "constructor property getter") val id: AccountId)
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Props.kt:10 @set:AILocked on property topAssigned"), out());
        assertTrue(out().contains("Props.kt:20 @get:AILocked on property owner"), out());
        assertTrue(out().contains("Props.kt:23 @set:AILocked on property assigned"), out());
        assertTrue(out().contains("Props.kt:26 @setparam:AIInputSanitized on property incoming"), out());
        assertTrue(out().contains("Props.kt:36 @get:AILocked on property id"), out());
        for (String kept : new String[]{"property topId", "property bare", "property stored",
            "property named", "property label"}) {
            assertFalse(out().contains(kept), kept + " keeps its guardrail: " + out());
        }
    }

    @Test
    void kotlinConstructorTakingValueClass_isReportedAsLost() throws Exception {
        // The primary and a secondary constructor taking AccountId compile to private constructors
        // plus synthetic public ones, and neither guardrail reached CLAUDE.md; nor did one on a plain
        // constructor parameter. A secondary constructor without a value class kept
        // Holder(java.lang.String,boolean), and a bare guardrail on a constructor property landed on
        // its field (Holder.idS21).
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Ctors.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AILocked

            class Holder @AILocked(reason = "primary") constructor(val id: AccountId) {
                @AILocked(reason = "secondary with a value class")
                constructor(id: AccountId, n: Int) : this(AccountId(id.raw + n))

                @AILocked(reason = "secondary without one")
                constructor(raw: String, flag: Boolean) : this(AccountId(raw + flag))
            }

            class Parser(@AIInputSanitized(AIInputSanitized.SanitizerType.PATH_TRAVERSAL) input: AccountId) {
                val raw: String = input.raw
            }

            class Plain @AILocked(reason = "plain") constructor(val raw: String)

            class Stored(@AIInputSanitized(AIInputSanitized.SanitizerType.LDAP) val id: AccountId)
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Ctors.kt:6 @AILocked on constructor Holder"), out());
        assertTrue(out().contains("Ctors.kt:8 @AILocked on constructor Holder"), out());
        assertTrue(out().contains("Ctors.kt:14 @AIInputSanitized (parameter input) on constructor Parser"), out());
        assertFalse(out().contains("Ctors.kt:11"), out());
        assertFalse(out().contains("constructor Plain"), out());
        assertFalse(out().contains("constructor Stored"), out());
    }

    @Test
    void kotlinMembersDeclaredInsideValueClass_areReportedAsLost() throws Exception {
        // A value class's members compile to static describe-impl, getSize-impl and
        // constructor-impl, whatever their own signature, and kapt left all three out. The value
        // class itself, its underlying property's getter (TicketId.getRaw()) and its companion's
        // plain function (Voucher.Companion.parse) kept their guardrails.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/OrderId.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "the value class itself reaches the stub")
            @JvmInline
            value class OrderId(@get:AILocked(reason = "underlying getter") val raw: String) {
                @AILocked(reason = "secondary")
                constructor(n: Int) : this(n.toString())

                @AILocked(reason = "member")
                fun describe(): String = raw

                @get:AILocked(reason = "member getter")
                val size: Int get() = raw.length

                companion object {
                    @AILocked(reason = "a companion is an ordinary class")
                    fun parse(raw: String): String = raw
                }
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("OrderId.kt:9 @AILocked on constructor OrderId"), out());
        assertTrue(out().contains("OrderId.kt:12 @AILocked on fun describe"), out());
        assertTrue(out().contains("OrderId.kt:15 @get:AILocked on property size"), out());
        assertFalse(out().contains("OrderId.kt:7"), out());
        assertFalse(out().contains("fun parse"), out());
    }

    @Test
    void kotlinJvmExposeBoxed_keepsWhatItExposesAndNothingElse() throws Exception {
        // Kept: @JvmExposeBoxed on a function (exposedFun(AccountId)), a member of a value class
        // carrying it (BoxedId.describe()), and a direct member of a class carrying it
        // (ExposedHolder.take(AccountId)). Lost: a function elsewhere taking the exposed value class
        // (takesBoxedClass), a suspend member of the exposed class, and a member of a class nested
        // inside it (NestedInExposed.take).
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Exposed.kt", """
            @file:OptIn(ExperimentalStdlibApi::class)

            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @JvmInline
            @JvmExposeBoxed
            value class Cents(val raw: Long) {
                @AILocked(reason = "member of an exposed value class")
                fun describe(): String = raw.toString()
            }

            @JvmExposeBoxed
            @AILocked(reason = "boxed variant on the function")
            fun charge(amount: Cents): Long = amount.raw

            @AILocked(reason = "exposing the class does not expose functions elsewhere")
            fun refund(amount: Cents): Long = amount.raw

            @JvmExposeBoxed
            class Ledger {
                @AILocked(reason = "direct member of an exposed class")
                fun post(id: AccountId): String = id.raw

                @AILocked(reason = "suspend is not exposed")
                suspend fun postLater(id: AccountId): String = id.raw

                class Nested {
                    @AILocked(reason = "exposure does not reach nested classes")
                    fun post(id: AccountId): String = id.raw
                }
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Exposed.kt:19 @AILocked on fun refund"), out());
        assertTrue(out().contains("com.example.ledger.Cents"), out());
        assertTrue(out().contains("Exposed.kt:27 @AILocked on fun postLater"), out());
        assertTrue(out().contains("Exposed.kt:31 @AILocked on fun post"), out());
        for (String kept : new String[]{"Exposed.kt:11", "Exposed.kt:16", "Exposed.kt:24"}) {
            assertFalse(out().contains(kept), kept + " is exposed boxed: " + out());
        }
    }

    @Test
    void kotlinModuleCompiledWithExposeBoxed_reportsOnlyWhatTheOptionDoesNotExpose() throws Exception {
        // A second kapt build of the same shapes with -Xjvm-expose-boxed kept everything except
        // suspend functions taking or returning a value class, open, abstract and interface members,
        // and a value class's secondary constructors.
        kotlinProject();
        Files.writeString(dir.resolve("build.gradle.kts"), """
            kotlin { compilerOptions { freeCompilerArgs.add("-Xjvm-expose-boxed") } }
            """);
        sourceFile("src/main/kotlin/com/example/ledger/AccountLedger.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "boxed variant")
            fun balanceFor(account: AccountId): Long = 0L

            class Ledger {
                @AILocked(reason = "boxed variant")
                fun owner(): AccountId = AccountId("o")

                @AILocked(reason = "suspend is not exposed")
                suspend fun later(account: AccountId): Long = 0L
            }

            abstract class Base {
                @AILocked(reason = "open is not exposed")
                open fun reopen(account: AccountId): Long = 0L
            }

            interface Lookup {
                @AILocked(reason = "interface members are not exposed")
                fun find(): AccountId
            }

            @JvmInline
            value class OrderId(val raw: String) {
                @AILocked(reason = "member, exposed")
                fun describe(): String = raw

                @AILocked(reason = "secondary constructor, not exposed")
                constructor(n: Int) : this(n.toString())
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("AccountLedger.kt:13 @AILocked on fun later"), out());
        assertTrue(out().contains("AccountLedger.kt:18 @AILocked on fun reopen"), out());
        assertTrue(out().contains("AccountLedger.kt:23 @AILocked on fun find"), out());
        assertTrue(out().contains("AccountLedger.kt:32 @AILocked on constructor OrderId"), out());
        for (String kept : new String[]{"fun balanceFor", "fun owner", "fun describe", "skipped"}) {
            assertFalse(out().contains(kept), kept + ": " + out());
        }
    }

    @Test
    void kotlinExposeBoxedOption_appliesOnlyUnderTheBuildFileThatPassesIt() throws Exception {
        kotlinProject();
        Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("app/build.gradle.kts"), """
            kotlin { compilerOptions { freeCompilerArgs.add("-Xjvm-expose-boxed") } }
            """);
        String lookup = """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "boxed only where the option is passed")
            fun lookup(id: AccountId): String = id.raw
            """;
        sourceFile("app/src/main/kotlin/com/example/ledger/AppLookup.kt", lookup);
        sourceFile("core/src/main/kotlin/com/example/ledger/CoreLookup.kt", lookup);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("CoreLookup.kt:6 @AILocked on fun lookup"), out());
        assertFalse(out().contains("AppLookup.kt"), out());
    }

    // ------------------------------------------------ shapes #692 did not build (#713)
    //
    // One more kapt build of the #692 fixture (Kotlin 2.4.10, JDK 21), with and without
    // -Xjvm-expose-boxed, read back from CLAUDE.md; the table is in docs/JVM-LANGUAGES.md. Each test
    // runs doctor both ways and asserts the number of findings, so a kept shape that starts being
    // reported fails as surely as a lost one that goes silent.

    private static final String EXPOSE_BOXED_BUILD = """
        kotlin { compilerOptions { freeCompilerArgs.add("-Xjvm-expose-boxed") } }
        """;

    /** "File.kt:N" for the first line of {@code source} containing {@code text}. */
    private static String at(String file, String source, String text) {
        int index = source.indexOf(text);
        assertTrue(index >= 0, "the source must contain " + text);
        return file + ":" + (source.substring(0, index).chars().filter(c -> c == 10).count() + 1);
    }

    private void assertFindingCount(int count) {
        assertTrue(out().contains(count == 0
                ? "no guardrails found"
                : "; " + count + " declaration(s) whose guardrails kapt will drop"),
            "expected " + count + " finding(s): " + out());
    }

    /** Runs doctor again with the project compiled under -Xjvm-expose-boxed. */
    private int doctorWithExposeBoxed() throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), EXPOSE_BOXED_BUILD);
        stdout.reset();
        return doctor();
    }

    @Test
    void kotlinExtensionProperties_followTheAccessorRules() throws Exception {
        // A receiver is the accessor's parameter: getLabel-VBQJbmA and every setter were left out,
        // top-level and member. A top-level getter with a plain receiver returning a value class kept
        // getAsId(java.lang.String), as a top-level getter does; the member one did not. A type
        // argument receiver (getFirstRaw(java.util.List)) and plain types kept theirs. Under
        // -Xjvm-expose-boxed all twelve were kept.
        kotlinProject();
        String file = "ExtProps.kt";
        String source = """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AILocked

            @get:AILocked(reason = "receiver mangles the getter")
            val AccountId.label: String get() = raw

            @get:AILocked(reason = "nullable receiver")
            val AccountId?.labelOrEmpty: String get() = this?.raw ?: ""

            @set:AILocked(reason = "receiver mangles the setter")
            var AccountId.alias: String
                get() = raw
                set(value) { check(value.isNotEmpty()) }

            @setparam:AIInputSanitized(AIInputSanitized.SanitizerType.XSS)
            var AccountId.note: String
                get() = raw
                set(value) { check(value.isNotEmpty()) }

            @set:AILocked(reason = "value type mangles the setter")
            var String.assignedId: AccountId
                get() = AccountId(this)
                set(value) { check(value.raw.isNotEmpty()) }

            @get:AILocked(reason = "a top-level getter's return type does not mangle")
            val String.asId: AccountId get() = AccountId(this)

            @get:AILocked(reason = "a type argument does not mangle")
            val List<AccountId>.firstRaw: String get() = first().raw

            @get:AILocked(reason = "plain")
            val String.size: Int get() = length

            class Formatter {
                @get:AILocked(reason = "member extension getter")
                val AccountId.memberLabel: String get() = raw

                @get:AILocked(reason = "member getter returning a value class")
                val String.memberId: AccountId get() = AccountId(this)

                @set:AILocked(reason = "member extension setter")
                var AccountId.memberAlias: String
                    get() = raw
                    set(value) { check(value.isNotEmpty()) }

                @get:AILocked(reason = "plain")
                val String.memberSize: Int get() = length
            }
            """;
        sourceFile("src/main/kotlin/com/example/ledger/" + file, source);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains(at(file, source, ".label:") + " @get:AILocked on property label:"), out());
        assertTrue(out().contains(at(file, source, ".labelOrEmpty") + " @get:AILocked on property labelOrEmpty"), out());
        assertTrue(out().contains(at(file, source, ".alias") + " @set:AILocked on property alias"), out());
        assertTrue(out().contains(at(file, source, ".note") + " @setparam:AIInputSanitized on property note"), out());
        assertTrue(out().contains(at(file, source, ".assignedId") + " @set:AILocked on property assignedId"), out());
        assertTrue(out().contains(at(file, source, ".memberLabel") + " @get:AILocked on property memberLabel"), out());
        assertTrue(out().contains(at(file, source, ".memberId") + " @get:AILocked on property memberId"), out());
        assertTrue(out().contains(at(file, source, ".memberAlias") + " @set:AILocked on property memberAlias"), out());
        assertTrue(out().contains("its getter uses value class com.example.ledger.AccountId"), out());
        for (String kept : new String[]{"property asId", "property firstRaw", "property size", "property memberSize"}) {
            assertFalse(out().contains(kept), kept + " keeps its guardrail: " + out());
        }
        assertFindingCount(8);

        assertEquals(0, doctorWithExposeBoxed(), out());
        assertFindingCount(0);
    }

    @Test
    void kotlinAnonymousObjectsEnumEntryBodiesAndLocalClasses_loseEveryGuardrail() throws Exception {
        // None of these reaches kapt's stubs, whatever the signature: every guardrail in an object
        // expression (at top level, in a member property), an enum entry's body and a local class,
        // and on the local class itself and its constructors, was absent from CLAUDE.md with and
        // without -Xjvm-expose-boxed. A named class's own plain member kept its guardrail.
        kotlinProject();
        String file = "Local.kt";
        String source = """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            interface Finder {
                fun find(id: AccountId): String
                fun label(): String
                val current: String
            }

            val topFinder: Finder = object : Finder {
                @AILocked(reason = "anonymous override taking a value class")
                override fun find(id: AccountId): String = id.raw

                @AILocked(reason = "anonymous override, plain")
                override fun label(): String = "a"

                @get:AILocked(reason = "anonymous getter")
                override val current: String get() = "c"

                @AILocked(reason = "anonymous extra member")
                fun extra(): String = "e"

                @AILocked(reason = "anonymous stored property")
                val stored: String = "s"

                @set:AILocked(reason = "anonymous setter")
                var mutable: String = "m"
            }

            class Service {
                private val finder = object : Finder {
                    @AILocked(reason = "anonymous object in a member property")
                    override fun label(): String = "b"

                    override fun find(id: AccountId): String = id.raw
                    override val current: String get() = "c"
                }

                @AILocked(reason = "a named class's own member is kept")
                fun plain(): String = finder.label()

                fun build(): String {
                    @AILocked(reason = "local class")
                    class Local @AILocked(reason = "local primary constructor") constructor(val raw: String) {
                        @AILocked(reason = "local member")
                        fun take(): String = raw

                        @AILocked(reason = "local stored property")
                        val kept: String = raw

                        @AILocked(reason = "local secondary constructor")
                        constructor(n: Int) : this(n.toString())
                    }
                    return Local(1).take()
                }
            }

            enum class Kind {
                FIRST {
                    @AILocked(reason = "enum entry body")
                    override fun describe(): String = "first"
                };

                abstract fun describe(): String
            }
            """;
        sourceFile("src/main/kotlin/com/example/ledger/" + file, source);

        for (boolean exposeBoxed : new boolean[]{false, true}) {
            assertEquals(1, exposeBoxed ? doctorWithExposeBoxed() : doctor(), out());
            assertTrue(out().contains(at(file, source, "fun find(id: AccountId): String = id.raw") + " @AILocked on fun find"), out());
            assertTrue(out().contains(at(file, source, "String = \"a\"") + " @AILocked on fun label"), out());
            assertTrue(out().contains(at(file, source, "val current: String get()") + " @get:AILocked on property current"), out());
            assertTrue(out().contains(at(file, source, "fun extra") + " @AILocked on fun extra"), out());
            assertTrue(out().contains(at(file, source, "val stored") + " @AILocked on property stored"), out());
            assertTrue(out().contains(at(file, source, "var mutable") + " @set:AILocked on property mutable"), out());
            assertTrue(out().contains(at(file, source, "String = \"b\"") + " @AILocked on fun label"), out());
            assertTrue(out().contains(at(file, source, "class Local") + " @AILocked on class Local"), out());
            assertTrue(out().contains(at(file, source, "class Local") + " @AILocked on constructor Local"), out());
            assertTrue(out().contains(at(file, source, "fun take") + " @AILocked on fun take"), out());
            assertTrue(out().contains(at(file, source, "val kept") + " @AILocked on property kept"), out());
            assertTrue(out().contains(at(file, source, "constructor(n: Int)") + " @AILocked on constructor Local"), out());
            assertTrue(out().contains(at(file, source, "String = \"first\"") + " @AILocked on fun describe"), out());
            assertTrue(out().contains("anonymous object"), out());
            assertFalse(out().contains("fun plain"), out());
            assertFindingCount(13);
        }
    }

    @Test
    void kotlinOverrideAndSuspendMembersInsideValueClass_areReportedOnlyWhenMeasuredLost() throws Exception {
        // A plain override inside a value class keeps an instance bridge, and label(), getCurrent()
        // and toString() kept their guardrails. An override whose signature uses a value class was
        // left out (find-..., make-..., getCurrentId-...), and so was every suspend member, even
        // with no value class in its signature; under -Xjvm-expose-boxed the overrides were kept
        // and the suspend members, including one in a value class carrying @JvmExposeBoxed, were not.
        kotlinProject();
        String file = "FinderId.kt";
        String source = """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            interface Finder {
                fun find(id: AccountId): String
                fun label(): String
                val current: String
                fun make(): AccountId
                val currentId: AccountId
            }

            @JvmInline
            value class FinderId(val raw: String) : Finder {
                @AILocked(reason = "override taking a value class")
                override fun find(id: AccountId): String = id.raw

                @AILocked(reason = "plain override keeps its bridge")
                override fun label(): String = raw

                @get:AILocked(reason = "plain override getter keeps its bridge")
                override val current: String get() = raw

                @AILocked(reason = "toString keeps its bridge")
                override fun toString(): String = raw

                @AILocked(reason = "override returning a value class")
                override fun make(): AccountId = AccountId(raw)

                @get:AILocked(reason = "override getter returning a value class")
                override val currentId: AccountId get() = AccountId(raw)

                @AILocked(reason = "suspend, plain")
                suspend fun fetch(): String = raw

                @AILocked(reason = "suspend taking a value class")
                suspend fun fetchFor(id: AccountId): String = id.raw
            }

            @JvmExposeBoxed
            @JvmInline
            value class ExposedId(val raw: String) {
                @AILocked(reason = "suspend is not exposed")
                suspend fun later(): String = raw
            }
            """;
        sourceFile("src/main/kotlin/com/example/ledger/" + file, source);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains(at(file, source, "override fun find") + " @AILocked on fun find"), out());
        assertTrue(out().contains(at(file, source, "override fun make") + " @AILocked on fun make"), out());
        assertTrue(out().contains(at(file, source, "override val currentId") + " @get:AILocked on property currentId"), out());
        assertTrue(out().contains(at(file, source, "suspend fun fetch()") + " @AILocked on fun fetch"), out());
        assertTrue(out().contains(at(file, source, "suspend fun fetchFor") + " @AILocked on fun fetchFor"), out());
        assertTrue(out().contains(at(file, source, "suspend fun later") + " @AILocked on fun later"), out());
        for (String kept : new String[]{"fun label", "property current:", "fun toString"}) {
            assertFalse(out().contains(kept), kept + " keeps its guardrail: " + out());
        }
        assertFindingCount(6);

        assertEquals(1, doctorWithExposeBoxed(), out());
        assertTrue(out().contains(at(file, source, "suspend fun fetch()") + " @AILocked on fun fetch"), out());
        assertTrue(out().contains(at(file, source, "suspend fun fetchFor") + " @AILocked on fun fetchFor"), out());
        assertTrue(out().contains(at(file, source, "suspend fun later") + " @AILocked on fun later"), out());
        assertFindingCount(3);
    }

    @Test
    void kotlinExposeBoxedClass_keepsItsSettersAndSecondaryConstructors() throws Exception {
        // setId(AccountId), its @setparam:, ExposedSetters(AccountId) and a guardrail on that
        // constructor's parameter all reached CLAUDE.md under their boxed names.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/ExposedSetters.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AILocked

            @JvmExposeBoxed
            class ExposedSetters {
                @set:AILocked(reason = "exposed setter")
                var id: AccountId = AccountId("s")

                @setparam:AIInputSanitized(AIInputSanitized.SanitizerType.XSS)
                var incoming: AccountId = AccountId("s")

                constructor()

                @AILocked(reason = "exposed secondary constructor")
                constructor(id: AccountId) {
                    this.id = id
                }

                constructor(@AIInputSanitized(AIInputSanitized.SanitizerType.XSS) other: AccountId, flag: Boolean) {
                    this.id = if (flag) other else id
                }
            }
            """);

        assertEquals(0, doctor(), out());
        assertFindingCount(0);
    }

    @Test
    void kotlinParameterGuardrailOnConstructorProperty_isReportedWhenTheConstructorIsMangled() throws Exception {
        // @param: puts the guardrail on the constructor parameter only, and a constructor taking a
        // value class is left out, so ParamA's was lost (kept under -Xjvm-expose-boxed). A bare
        // AIInputSanitized, which targets PARAMETER and FIELD, rendered on the field (ParamC.id,
        // ParamD.id), and @field: and a plain-typed @param: kept theirs.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Params.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AIInputSanitized.SanitizerType

            class ParamA(@param:AIInputSanitized(SanitizerType.XSS) val id: AccountId)

            class ParamB(@param:AIInputSanitized(SanitizerType.XSS) val raw: String)

            class ParamC(@AIInputSanitized(SanitizerType.XSS) val id: AccountId)

            class ParamD(@AIInputSanitized(SanitizerType.XSS) var id: AccountId)

            class ParamE(@field:AIInputSanitized(SanitizerType.XSS) val id: AccountId)
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Params.kt:6 @param:AIInputSanitized (parameter id) on constructor ParamA"), out());
        assertFindingCount(1);

        assertEquals(0, doctorWithExposeBoxed(), out());
        assertFindingCount(0);
    }

    @Test
    void kotlinValueClassPrimaryConstructorGuardrails_areReportedAsLost() throws Exception {
        // A value class's primary constructor compiles to a private constructor plus a static
        // constructor-impl: the guardrail on GuardedId's constructor and on ParamId's @param: were
        // lost, a bare one rendered on the field (FieldId.raw), and @JvmExposeBoxed on the class or
        // -Xjvm-expose-boxed kept the constructor's.
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Ids.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AIInputSanitized
            import se.deversity.vibetags.annotations.AIInputSanitized.SanitizerType
            import se.deversity.vibetags.annotations.AILocked

            @JvmInline
            value class GuardedId @AILocked(reason = "primary constructor") constructor(val raw: String)

            @JvmInline
            value class ParamId(@param:AIInputSanitized(SanitizerType.XSS) val raw: String)

            @JvmInline
            value class FieldId(@AIInputSanitized(SanitizerType.XSS) val raw: String)

            @JvmExposeBoxed
            @JvmInline
            value class ExposedGuardedId @AILocked(reason = "exposed primary constructor") constructor(val raw: String)
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Ids.kt:8 @AILocked on constructor GuardedId"), out());
        assertTrue(out().contains("Ids.kt:11 @param:AIInputSanitized (parameter raw) on constructor ParamId"), out());
        assertTrue(out().contains("constructor-impl"), out());
        assertFindingCount(2);

        assertEquals(0, doctorWithExposeBoxed(), out());
        assertFindingCount(0);
    }
    // ------------------------------------------------ value classes from other modules and dependencies
    //
    // #691. A value class declared outside the scanned sources mangles a function the same way: in the
    // #692 fixture, Consumer.forCustomer(CustomerId), with CustomerId in a separate Gradle module, lost
    // its guardrail. The jar cases use Java stand-ins compiled here: kotlinc writes @kotlin.jvm.JvmInline
    // into a value class's RuntimeVisibleAnnotations (javap on the fixture's model.jar), and a Java
    // class carrying an annotation of that name looks the same to a class-file reader.

    private static final String CONSUMER = """
        package com.acme.app

        import com.acme.model.CustomerId
        import com.acme.model.PlainCustomer
        import se.deversity.vibetags.annotations.AILocked

        @AILocked(reason = "customer lookup")
        fun forCustomer(id: CustomerId): String = id.raw

        @AILocked(reason = "an ordinary class from the same jar")
        fun forPlain(customer: PlainCustomer): String = ""

        @AILocked(reason = "kotlin.Result carries @JvmInline but is not mangled")
        fun settle(outcome: Result<Long>): String = ""
        """;

    private int doctor(String... options) {
        PrintStream stream = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        String[] args = new String[options.length + 1];
        args[0] = "doctor";
        System.arraycopy(options, 0, args, 1, options.length);
        return Main.run(args, stream, stream, dir);
    }

    /** Compiles the dependency stand-ins into a class directory outside every scanned source root. */
    private Path compiledDependency() throws Exception {
        Path src = dir.resolve("deps/src");
        List<Path> files = List.of(
            javaFile(src, "kotlin/jvm/JvmInline.java", """
                package kotlin.jvm;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface JvmInline {
                }
                """),
            javaFile(src, "kotlin/Result.java", """
                package kotlin;

                @kotlin.jvm.JvmInline
                public final class Result<T> {
                }
                """),
            javaFile(src, "com/acme/model/CustomerId.java", """
                package com.acme.model;

                @kotlin.jvm.JvmInline
                public final class CustomerId {
                    public String getRaw() {
                        return "";
                    }
                }
                """),
            javaFile(src, "com/acme/model/PlainCustomer.java", """
                package com.acme.model;

                public final class PlainCustomer {
                }
                """));
        Path classes = Files.createDirectories(dir.resolve("deps/classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        files.forEach(f -> args.add(f.toString()));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "stand-ins must compile");
        return classes;
    }

    private static Path javaFile(Path root, String relPath, String source) throws Exception {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    /** Packs a class directory, plus any extra entries, into a jar under deps/. */
    private Path jar(Path classes, String name, Map<String, byte[]> extra) throws Exception {
        Path jar = dir.resolve("deps").resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : extra.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void kotlinValueClassFromSiblingModule_isResolvedWhenDoctorRunsFromTheReactorRoot() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("model/src/main/kotlin/com/acme/model/CustomerId.kt", """
            package com.acme.model

            @JvmInline
            value class CustomerId(val raw: String)
            """);
        sourceFile("app/src/main/kotlin/com/acme/app/Consumer.kt", CONSUMER);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forCustomer"), out());
        assertTrue(out().contains("value class com.acme.model.CustomerId"), out());
        assertFalse(out().contains("fun settle"), out());
    }

    @Test
    void kotlinValueClassFromDependencyJar_isReportedWhenTheJarIsOnTheClasspath() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/acme/app/Consumer.kt", CONSUMER);
        Path jar = jar(compiledDependency(), "model.jar", Map.of());

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("--classpath"),
            "a run without a classpath must say how to see dependency value classes: " + out());

        stdout.reset();
        assertEquals(1, doctor("--classpath", jar.toString()), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forCustomer"), out());
        assertTrue(out().contains("value class com.acme.model.CustomerId"), out());
        assertTrue(out().contains("1 value class(es) found"), "kotlin.Result is not counted: " + out());
        assertFalse(out().contains("fun forPlain"), out());
        assertFalse(out().contains("fun settle"), out());
    }

    @Test
    void classDirectoryOnTheClasspath_isReadLikeAJar_andASourceDeclarationWins() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/acme/app/Consumer.kt", CONSUMER);
        Path classes = compiledDependency();

        assertEquals(1, doctor("--classpath", classes.toString()), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forCustomer"), out());

        // The sources say CustomerId is an ordinary class now; a stale class directory must not
        // turn that into a finding.
        sourceFile("src/main/kotlin/com/acme/model/CustomerId.kt", """
            package com.acme.model

            class CustomerId(val raw: String)
            """);
        stdout.reset();
        assertEquals(0, doctor("--classpath", classes.toString()), out());
    }

    @Test
    void unreadableClasspathEntries_areFindingsNotASilentPass() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/acme/app/Consumer.kt", CONSUMER);
        byte[] broken = "\u00ca\u00fe\u00ba\u00be kotlin/jvm/JvmInline".getBytes(StandardCharsets.ISO_8859_1);
        Path jar = jar(compiledDependency(), "broken.jar", Map.of("com/acme/model/Broken.class", broken));
        Path missing = dir.resolve("deps/missing.jar");

        assertEquals(1, doctor("--classpath", jar + File.pathSeparator + missing), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forCustomer"),
            "the readable classes in a jar still count: " + out());
        assertTrue(out().contains("com/acme/model/Broken.class"), out());
        assertTrue(out().contains("missing.jar") && out().contains("does not exist"), out());
    }

    @Test
    void deeplyNestedAnnotationInAClassFile_isAFindingNotACrash() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/acme/app/Consumer.kt", CONSUMER);
        Path jar = jar(compiledDependency(), "nested.jar",
            Map.of("com/acme/model/Deep.class", classFileWithNestedAnnotation(100_000)));

        assertEquals(1, doctor("--classpath", jar.toString()), out());
        assertTrue(out().contains("com/acme/model/Deep.class"), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forCustomer"), out());
    }

    /** A class file whose @JvmInline annotation holds an annotation nested {@code depth} levels deep. */
    private static byte[] classFileWithNestedAnnotation(int depth) throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream b = new java.io.DataOutputStream(body);
        b.writeShort(1);            // one annotation
        for (int i = 0; i < depth; i++) {
            b.writeShort(2);        // type: Lkotlin/jvm/JvmInline;
            b.writeShort(1);        // one element
            b.writeShort(2);        // element name
            b.writeByte('@');       // whose value is another annotation
        }
        b.writeShort(2);
        b.writeShort(0);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream c = new java.io.DataOutputStream(bytes);
        c.writeInt(0xCAFEBABE);
        c.writeShort(0);
        c.writeShort(65);
        c.writeShort(5);            // constant pool count
        c.writeByte(1);
        c.writeUTF("RuntimeVisibleAnnotations");
        c.writeByte(1);
        c.writeUTF("Lkotlin/jvm/JvmInline;");
        c.writeByte(1);
        c.writeUTF("com/acme/model/Deep");
        c.writeByte(7);
        c.writeShort(3);
        c.writeShort(0x31);         // access flags
        c.writeShort(4);            // this class
        c.writeShort(0);            // super class
        c.writeShort(0);            // interfaces
        c.writeShort(0);            // fields
        c.writeShort(0);            // methods
        c.writeShort(1);            // attributes
        c.writeShort(1);
        c.writeInt(body.size());
        body.writeTo(c);
        c.flush();
        return bytes.toByteArray();
    }

    // ------------------------------------------------ nested and deprecated inline value classes (#714)
    //
    // kotlinc 2.4.10 writes @kotlin.jvm.JvmInline into a deprecated `inline class` too, and a nested
    // value class compiles to Outer$Id, named Outer.Id through its InnerClasses attribute (javap -v on
    // the fixture's model classes). Through kapt, a function taking either, from source or from another
    // module, lost its guardrail and kept it under -Xjvm-expose-boxed.

    @Test
    void kotlinNestedValueClassesInSources_areResolvedByTheirQualifiedName() throws Exception {
        kotlinProject();
        sourceFile("src/main/kotlin/com/example/ledger/Outer.kt", """
            package com.example.ledger

            class Outer {
                @JvmInline
                value class Id(val raw: String)

                class Mid {
                    @JvmInline
                    value class Deep(val raw: String)
                }

                class Plain(val raw: String)
            }

            inline class LegacyId(val raw: String)
            """);
        sourceFile("src/main/kotlin/com/example/ledger/Ledger.kt", """
            package com.example.ledger

            import se.deversity.vibetags.annotations.AILocked

            class Ledger {
                @AILocked(reason = "same package, no import")
                fun samePackage(id: Outer.Id): String = id.raw
            }
            """);
        sourceFile("src/main/kotlin/com/example/app/Use.kt", """
            package com.example.app

            import com.example.ledger.LegacyId
            import com.example.ledger.Outer
            import com.example.ledger.Outer.Mid.Deep
            import se.deversity.vibetags.annotations.AILocked

            class Use {
                @AILocked(reason = "through the imported outer class")
                fun qualified(id: Outer.Id): String = id.raw

                @AILocked(reason = "imported nested value class")
                fun imported(id: Deep): String = id.raw

                @AILocked(reason = "fully qualified")
                fun full(id: com.example.ledger.Outer.Mid.Deep): String = id.raw

                @AILocked(reason = "deprecated inline class")
                fun legacy(id: LegacyId): String = id.raw

                @AILocked(reason = "an ordinary nested class")
                fun plain(p: Outer.Plain): String = p.raw
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Ledger.kt:7 @AILocked on fun samePackage"), out());
        assertTrue(out().contains("Use.kt:10 @AILocked on fun qualified"), out());
        assertTrue(out().contains("value class com.example.ledger.Outer.Id"), out());
        assertTrue(out().contains("Use.kt:13 @AILocked on fun imported"), out());
        assertTrue(out().contains("Use.kt:16 @AILocked on fun full"), out());
        assertTrue(out().contains("value class com.example.ledger.Outer.Mid.Deep"), out());
        assertTrue(out().contains("Use.kt:19 @AILocked on fun legacy"), out());
        assertFalse(out().contains("fun plain"), out());
        assertFindingCount(5);
    }

    @Test
    void nestedValueClassesInADependency_areNamedAsSourceWritesThem() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/acme/app/Consumer.kt", """
            package com.acme.app

            import com.acme.model.Outer
            import com.acme.model.Outer.Mid.Deep
            import se.deversity.vibetags.annotations.AILocked

            @AILocked(reason = "nested value class from a jar")
            fun forId(id: Outer.Id): String = ""

            @AILocked(reason = "doubly nested, imported")
            fun forDeep(id: Deep): String = ""

            @AILocked(reason = "an ordinary nested class")
            fun forPlain(p: Outer.Plain): String = ""
            """);
        Path src = dir.resolve("deps/src");
        List<Path> files = List.of(
            javaFile(src, "kotlin/jvm/JvmInline.java", """
                package kotlin.jvm;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface JvmInline {
                }
                """),
            javaFile(src, "com/acme/model/Outer.java", """
                package com.acme.model;

                public final class Outer {
                    @kotlin.jvm.JvmInline
                    public static final class Id {
                    }

                    public static final class Mid {
                        @kotlin.jvm.JvmInline
                        public static final class Deep {
                        }
                    }

                    public static final class Plain {
                    }

                    public Object local() {
                        @kotlin.jvm.JvmInline
                        final class Local {
                        }
                        return new Local();
                    }
                }
                """));
        Path classes = Files.createDirectories(dir.resolve("deps/classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        files.forEach(f -> args.add(f.toString()));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
            "stand-ins must compile");
        Path jar = jar(classes, "model.jar", Map.of());

        assertEquals(1, doctor("--classpath", jar.toString()), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forId"), out());
        assertTrue(out().contains("value class com.acme.model.Outer.Id"), out());
        assertTrue(out().contains("Consumer.kt:11 @AILocked on fun forDeep"), out());
        assertTrue(out().contains("2 value class(es) found"),
            "Id and Deep count; a local class carrying @JvmInline has no source name: " + out());
        assertFalse(out().contains("fun forPlain"), out());
    }

    @Test
    void innerClassesNamingTheClassAsItsOwnOuter_isAFindingNotACrash() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        sourceFile("src/main/kotlin/com/acme/app/Consumer.kt", CONSUMER);
        Path jar = jar(compiledDependency(), "loop.jar",
            Map.of("com/acme/model/Loop$Id.class", classFileNestedInItself()));

        assertEquals(1, doctor("--classpath", jar.toString()), out());
        assertTrue(out().contains("com/acme/model/Loop$Id.class"), out());
        assertTrue(out().contains("Consumer.kt:8 @AILocked on fun forCustomer"), out());
    }

    /** A @JvmInline class file whose InnerClasses entry names the class as its own outer class. */
    private static byte[] classFileNestedInItself() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream c = new java.io.DataOutputStream(bytes);
        c.writeInt(0xCAFEBABE);
        c.writeShort(0);
        c.writeShort(65);
        c.writeShort(7);            // constant pool count
        c.writeByte(1);
        c.writeUTF("RuntimeVisibleAnnotations");
        c.writeByte(1);
        c.writeUTF("Lkotlin/jvm/JvmInline;");
        c.writeByte(1);
        c.writeUTF("com/acme/model/Loop$Id");
        c.writeByte(7);
        c.writeShort(3);
        c.writeByte(1);
        c.writeUTF("InnerClasses");
        c.writeByte(1);
        c.writeUTF("Id");
        c.writeShort(0x31);         // access flags
        c.writeShort(4);            // this class
        c.writeShort(0);            // super class
        c.writeShort(0);            // interfaces
        c.writeShort(0);            // fields
        c.writeShort(0);            // methods
        c.writeShort(2);            // attributes
        c.writeShort(5);            // InnerClasses: one entry, Loop$Id nested in Loop$Id
        c.writeInt(10);
        c.writeShort(1);
        c.writeShort(4);
        c.writeShort(4);
        c.writeShort(6);
        c.writeShort(0x19);
        c.writeShort(1);            // RuntimeVisibleAnnotations: @JvmInline
        c.writeInt(6);
        c.writeShort(1);
        c.writeShort(2);
        c.writeShort(0);
        c.flush();
        return bytes.toByteArray();
    }
    @Test
    void projectWithoutKotlin_printsNoKotlinLine() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(0, doctor(), out());
        assertFalse(out().contains("kotlin sources:"),
            "a Java-only project has nothing Kotlin to report on: " + out());
    }

    @Test
    void unreadableKotlinFile_isAFindingNotASilentPass() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.write(dir.resolve("src/main/kotlin/Broken.kt"), new byte[]{(byte) 0xFF, (byte) 0xFE});

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("could not read") && out().contains("Broken.kt"), out());
    }
}
