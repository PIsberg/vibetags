package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The opt-in enforcing mode
 * (<a href="https://github.com/PIsberg/vibetags/issues/284">issue #284</a>).
 *
 * <p>Guardrails are advisory by design, and that default must stay untouched: every test here that
 * does not pass {@code -Avibetags.enforce} asserts silence. What the mode adds is a hard stop for
 * the families whose promise the processor can actually prove from the element model.
 */
class EnforcingModeEndToEndTest {

    private static final String CONTRACT_V1 = """
        package com.example;

        import se.deversity.vibetags.annotations.AIContract;

        public interface PaymentGateway {
            @AIContract(reason = "External gateway API — breaking changes violate the SLA")
            double charge(String customerId, double amount);
        }
        """;

    /** Same method, different parameter type: exactly what @AIContract promises will not happen. */
    private static final String CONTRACT_V2_BROKEN = """
        package com.example;

        import se.deversity.vibetags.annotations.AIContract;

        public interface PaymentGateway {
            @AIContract(reason = "External gateway API — breaking changes violate the SLA")
            double charge(String customerId, long amount);
        }
        """;

    /** Reworded reason, identical signature: not a violation. */
    private static final String CONTRACT_V2_HARMLESS = """
        package com.example;

        import se.deversity.vibetags.annotations.AIContract;

        public interface PaymentGateway {
            @AIContract(reason = "External gateway API — reworded, same signature")
            double charge(String customerId, double amount);
        }
        """;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private List<Diagnostic<? extends JavaFileObject>> compile(String source, String... options)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        harness.writeSourceFile("src/main/java/com/example/PaymentGateway.java", source);
        return harness.compileReturningDiagnostics(options);
    }

    private static boolean errors(List<Diagnostic<? extends JavaFileObject>> diagnostics, String needle) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .anyMatch(d -> d.getMessage(null).contains(needle));
    }

    private static boolean warns(List<Diagnostic<? extends JavaFileObject>> diagnostics, String needle) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .anyMatch(d -> d.getMessage(null).contains(needle));
    }

    @Test
    void recordsABaselineOnDemandAndThenPasses() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        Path baseline = root.resolve(".vibetags-baseline");
        assertTrue(Files.exists(baseline), "the update run must write the baseline");
        String content = Files.readString(baseline);
        assertTrue(content.contains("contract"), content);
        assertTrue(content.contains("charge(java.lang.String,double):double"),
            "the baseline records the signature in full, so a PR diff shows the change:\n" + content);

        List<Diagnostic<? extends JavaFileObject>> second = compile(CONTRACT_V1, "-Avibetags.enforce=contract");
        assertFalse(errors(second, "violation"), "an unchanged signature must pass");
    }

    @Test
    void failsTheBuildWhenAGuardedSignatureChanges() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        List<Diagnostic<? extends JavaFileObject>> broken =
            compile(CONTRACT_V2_BROKEN, "-Avibetags.enforce=contract");

        assertTrue(errors(broken, "@AIContract violation"),
            "a changed parameter type is what this mode exists to stop");
        assertTrue(errors(broken, "-Avibetags.baseline.update=true"),
            "the error must say how to approve an intended change");
    }

    @Test
    void ignoresChangesThatAreNotStructural() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        List<Diagnostic<? extends JavaFileObject>> reworded =
            compile(CONTRACT_V2_HARMLESS, "-Avibetags.enforce=contract");

        assertFalse(errors(reworded, "violation"),
            "rewording a reason changes no caller — enforcing that would just get switched off");
    }

    /** The default posture is advisory; enforcement must be invisible until asked for. */
    @Test
    void doesNothingUnlessAskedTo() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        List<Diagnostic<? extends JavaFileObject>> plain = compile(CONTRACT_V2_BROKEN);

        assertFalse(errors(plain, "violation"), "no -Avibetags.enforce, no enforcement");
        assertFalse(warns(plain, "enforcement"), "…and no chatter about it either");
    }

    /**
     * Switching the option on before recording a baseline must not fail every build — that teaches
     * people to switch it off again. It says what to run instead.
     */
    @Test
    void warnsRatherThanFailsWhenNoBaselineHasBeenRecorded() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        List<Diagnostic<? extends JavaFileObject>> first = compile(CONTRACT_V1, "-Avibetags.enforce=contract");

        assertFalse(errors(first, "violation"), "an unrecorded baseline is not a violation");
        assertTrue(warns(first, "records nothing for module"), "but it must not pass silently either");
        assertTrue(warns(first, "-Avibetags.baseline.update=true"), "and must name the fix");
    }

    /** Families that cannot be proved statically are refused by name, not silently ignored. */
    @Test
    void rejectsAFamilyItCannotProve() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            compile(CONTRACT_V1, "-Avibetags.enforce=callersonly");

        assertTrue(warns(diagnostics, "unknown guardrail family 'callersonly'"),
            "an unenforceable family must be reported, not quietly dropped");
        assertTrue(warns(diagnostics, "stay advisory"),
            "…and the message must say why, so the boundary is documented where it is hit");
    }

    /** A newly annotated element has nothing to compare against, and is not a violation. */
    @Test
    void treatsANewlyGuardedElementAsUnapprovedRatherThanBroken() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/PaymentGateway.java", CONTRACT_V1);
        harness.writeSourceFile("src/main/java/com/example/RefundGateway.java", """
            package com.example;

            import se.deversity.vibetags.annotations.AIContract;

            public interface RefundGateway {
                @AIContract(reason = "new, not yet in the baseline")
                void refund(String transactionId);
            }
            """);
        List<Diagnostic<? extends JavaFileObject>> added =
            harness.compileReturningDiagnostics("-Avibetags.enforce=contract");

        assertFalse(errors(added, "violation"), "adding a guarded element is not breaking one");
    }
}
