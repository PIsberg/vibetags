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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The opt-in enforcing mode
 * (<a href="https://github.com/PIsberg/vibetags/issues/284">issue #284</a>).
 *
 * <p>Guardrails are advisory by design, and that default must stay untouched: every test here that
 * does not pass {@code -Avibetags.enforce} asserts silence. What the mode adds is a hard stop for
 * the families whose promise the processor can actually prove from the element model.
 */
@Tag("e2e")
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

    /**
     * Same name, same parameter types, different return type.
     *
     * <p>Structurally different from {@link #CONTRACT_V2_BROKEN}, and that difference is the whole
     * point of having both. A method's path embeds its parameter types, so changing one abandons
     * the approved entry and the violation is found by the "approved but absent" sweep. Changing
     * only the return type leaves the path intact, so the entry is still there and has to be caught
     * by comparing signatures — a separate code path that nothing exercised.
     */
    private static final String CONTRACT_V2_RETURN_TYPE = """
        package com.example;

        import se.deversity.vibetags.annotations.AIContract;

        public interface PaymentGateway {
            @AIContract(reason = "External gateway API — breaking changes violate the SLA")
            long charge(String customerId, double amount);
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

    /**
     * A constructor and a method whose name is the class name. javac prints both under the class's
     * simple name, so their element paths are byte-identical and a path-keyed baseline holds only
     * one of them (issue #552).
     */
    private static final String NAMESAKE_V1 = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        public class PaymentGateway {
            @AILocked(reason = "constructor invariants are frozen")
            public PaymentGateway(String customerId) {}

            @AILocked(reason = "the legacy factory entry point is frozen")
            public void PaymentGateway(String customerId) {}
        }
        """;

    /** The constructor gains a parameter; the method is untouched. */
    private static final String NAMESAKE_V2_CONSTRUCTOR_CHANGED = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        public class PaymentGateway {
            @AILocked(reason = "constructor invariants are frozen")
            public PaymentGateway(String customerId, double fee) {}

            @AILocked(reason = "the legacy factory entry point is frozen")
            public void PaymentGateway(String customerId) {}
        }
        """;

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
    void aConstructorAndAMethodNamedLikeItsClassAreSeparateEntries() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(NAMESAKE_V1, "-Avibetags.baseline.update=true");

        String content = Files.readString(root.resolve(".vibetags-baseline"));
        long entries = content.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).count();
        assertEquals(2, entries,
            "both guarded elements must be recorded; keyed on the path alone javac renders them "
                + "identically, so one silently replaces the other and is left unenforceable:\n"
                + content);
    }

    @Test
    void failsTheBuildWhenTheGuardedConstructorChanges() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(NAMESAKE_V1, "-Avibetags.baseline.update=true");

        List<Diagnostic<? extends JavaFileObject>> broken =
            compile(NAMESAKE_V2_CONSTRUCTOR_CHANGED, "-Avibetags.enforce=locked");

        assertTrue(errors(broken, "@AILocked violation"),
            "the constructor shares its rendered path with the namesake method, and the method won "
                + "the single entry, so changing the constructor was invisible to the gate");
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
    void failsTheBuildWhenOnlyTheReturnTypeChanges() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        List<Diagnostic<? extends JavaFileObject>> broken =
            compile(CONTRACT_V2_RETURN_TYPE, "-Avibetags.enforce=contract");

        assertTrue(errors(broken, "@AIContract violation"),
            "a changed return type breaks every caller and must be caught");
        assertTrue(errors(broken, "changed from what"),
            "this is the in-place shape change, so the message must be the 'changed' one rather "
                + "than the 'no such guarded element' one");
        assertTrue(errors(broken, "approved:") && errors(broken, "now:"),
            "the error has to show both shapes — a violation you cannot read is one you approve blind");
    }

    @Test
    void toleratesEmptyEntriesInTheFamilyList() throws IOException {
        // -Avibetags.enforce=contract,,locked, is what a shell variable or a generated build
        // argument produces. Rejecting it, or reading the empty entry as an unknown family, would
        // make the option fail on exactly the setups that pass it programmatically.
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");

        List<Diagnostic<? extends JavaFileObject>> messy =
            compile(CONTRACT_V1, "-Avibetags.enforce=contract,, locked ,");

        assertFalse(warns(messy, "unknown guardrail family"),
            "an empty entry is whitespace, not a family somebody misspelled");
        assertFalse(errors(messy, "violation"),
            "and the families that were named must still be enforced normally");
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

    /**
     * A baseline that is there but cannot be decoded is not "nothing recorded". Treating it that
     * way turned one Cp1252 byte from a Windows editor into a green enforcing build with a
     * warning nobody reads; the gate must fail loudly on a file it could not parse.
     */
    @Test
    void failsRatherThanPassesOnABaselineItCannotRead() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(CONTRACT_V1, "-Avibetags.baseline.update=true");
        Path baseline = root.resolve(".vibetags-baseline");
        byte[] bytes = Files.readAllBytes(baseline);
        bytes[bytes.length - 2] = (byte) 0xE5; // 'å' as Cp1252 writes it: not valid UTF-8
        Files.write(baseline, bytes);

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            compile(CONTRACT_V2_BROKEN, "-Avibetags.enforce=contract");

        assertTrue(errors(diagnostics, "could not read .vibetags-baseline"),
            "an unreadable baseline must stop the build, not be waved through");
        assertTrue(errors(diagnostics, "-Avibetags.baseline.update=true"), "and must name the way out");
        assertFalse(warns(diagnostics, "records nothing for module"),
            "an unreadable file is not an unrecorded one");
    }

    private static final String TWO_FAMILIES = """
        package com.example;
        import se.deversity.vibetags.annotations.AIContract;
        import se.deversity.vibetags.annotations.AILocked;
        public interface PaymentGateway {
            @AIContract(reason = "External gateway API")
            double charge(String customerId, double amount);
            @AILocked(reason = "settlement order is frozen")
            void settle();
        }
        """;

    private static final String TWO_FAMILIES_CONTRACT_BROKEN = """
        package com.example;
        import se.deversity.vibetags.annotations.AIContract;
        import se.deversity.vibetags.annotations.AILocked;
        public interface PaymentGateway {
            @AIContract(reason = "External gateway API")
            double charge(String customerId, long amount);
            @AILocked(reason = "settlement order is frozen")
            void settle();
        }
        """;

    /**
     * Re-approving one family must not throw the others away. {@code -Avibetags.enforce=locked}
     * with {@code -Avibetags.baseline.update=true} is how a developer re-records a locked element
     * they changed on purpose. The update replaced the module's whole block, so its
     * {@code @AIContract} entries vanished; the next {@code -Avibetags.enforce=all} build then had
     * nothing to compare the contracts against, read a broken one as "newly annotated", and stayed
     * green.
     */
    @Test
    void updatingOneFamilyKeepsTheOtherFamiliesApproved() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile(TWO_FAMILIES, "-Avibetags.enforce=all", "-Avibetags.baseline.update=true");
        String recorded = Files.readString(root.resolve(".vibetags-baseline"));
        assertTrue(recorded.contains("\tcontract\t") && recorded.contains("\tlocked\t"),
            "precondition: both families recorded:\n" + recorded);

        compile(TWO_FAMILIES, "-Avibetags.enforce=locked", "-Avibetags.baseline.update=true");
        String after = Files.readString(root.resolve(".vibetags-baseline"));
        assertTrue(after.contains("\tcontract\t"),
            "re-recording the locked family must leave the contract entries in place:\n" + after);

        List<Diagnostic<? extends JavaFileObject>> broken =
            compile(TWO_FAMILIES_CONTRACT_BROKEN, "-Avibetags.enforce=all");
        assertTrue(errors(broken, "@AIContract violation"),
            "the contract must still be enforced after an unrelated family was re-approved");
    }
}
