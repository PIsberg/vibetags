package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lock-does-not-follow-overrides gap: an override of an {@code @AILocked} method carries none
 * of the lock's protection — SOURCE retention and the absence of {@code @Inherited} mean no
 * generated guardrail file ever mentions the override, so an AI agent is free to rewrite the
 * replacement logic while the locked original stays byte-identical. The processor cannot make the
 * lock follow; what it can do is say so at the one moment the author is looking: compile time.
 */
class LockedOverrideValidationTest {

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void overrideOfLockedConcreteMethod_warns(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.addSource("com.example.Base",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Base {\n"
                + "    @AILocked(reason = \"settlement rounding is regulator-approved\")\n"
                + "    public long round(long cents) { return cents; }\n"
                + "}\n");
        h.addSource("com.example.Sub",
            "package com.example;\n"
                + "public class Sub extends Base {\n"
                + "    @Override\n"
                + "    public long round(long cents) { return cents + 1; }\n"
                + "}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .anyMatch(d -> d.getMessage(null).contains("does not follow overrides")
                    && d.getMessage(null).contains("Sub")),
            "overriding a locked concrete method must warn: " + diagnostics);
    }

    @Test
    void implementingALockedAbstractMethod_staysQuiet(@TempDir Path tmp) throws IOException {
        // A locked abstract method has no body to lock; implementing it is the intended use, and
        // a warning that fires on ordinary work is a warning people learn to skip.
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.addSource("com.example.Gateway",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public interface Gateway {\n"
                + "    @AILocked(reason = \"wire format\")\n"
                + "    long charge(long cents);\n"
                + "}\n");
        h.addSource("com.example.GatewayImpl",
            "package com.example;\n"
                + "public class GatewayImpl implements Gateway {\n"
                + "    @Override\n"
                + "    public long charge(long cents) { return cents; }\n"
                + "}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .noneMatch(d -> d.getMessage(null).contains("does not follow overrides")),
            "implementing a locked abstract method is the intended use, not a bypass: " + diagnostics);
    }

    @Test
    void lockedOverrideOfLockedMethod_staysQuiet(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp, false);
        h.addSource("com.example.Base2",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Base2 {\n"
                + "    @AILocked(reason = \"base\")\n"
                + "    public long round(long cents) { return cents; }\n"
                + "}\n");
        h.addSource("com.example.Sub2",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Sub2 extends Base2 {\n"
                + "    @Override\n"
                + "    @AILocked(reason = \"override locked too\")\n"
                + "    public long round(long cents) { return cents; }\n"
                + "}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .noneMatch(d -> d.getMessage(null).contains("does not follow overrides")),
            "a locked override means the guardrail followed — nothing to report: " + diagnostics);
    }
}
