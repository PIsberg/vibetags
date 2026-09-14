package se.deversity.vibetags.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
