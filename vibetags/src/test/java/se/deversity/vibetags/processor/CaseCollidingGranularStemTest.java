package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two annotated elements whose rule filenames differ only in capitalisation must not fail silently.
 *
 * <p>A granular stem is the element's fully-qualified name with its dots turned into dashes, so the
 * package {@code com.example.payment} and the class {@code com.example.Payment} plan
 * {@code com-example-payment} and {@code com-example-Payment}. A case-sensitive filesystem holds
 * two files and both guardrails survive. A case-insensitive one, which is what Windows and macOS
 * give you by default, holds one: the second write lands on the first, one element's guardrails are
 * gone, and the scoped-rules index goes on naming both. Measured on Windows before this warning
 * existed, the rules directory held a single {@code com-example-payment.md} and the class's
 * {@code @AILocked} appeared nowhere in the output tree.
 *
 * <p>The assertion is on the diagnostic rather than on the files, deliberately: which of the two
 * survives is a property of the filesystem the test happens to run on, and a test that asserts
 * "two files" passes on the Linux CI runner precisely where the bug does not bite. The warning is
 * raised from the plan, so it is the same on every platform.
 */
@Tag("e2e")
class CaseCollidingGranularStemTest {

    @AfterEach
    void releaseLog() {
        VibeTagsLogger.shutdown();
    }

    @Test
    @DisplayName("rule files differing only in case are reported, not silently collapsed")
    void stemsDifferingOnlyInCaseAreWarnedAbout(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");

        h.addSource("com.example.payment.package-info",
            "@se.deversity.vibetags.annotations.AIContext(focus = \"settlement timing\")\n"
                + "package com.example.payment;\n");
        h.addSource("com.example.Payment",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"wire format\")\n"
                + "public class Payment {}\n");

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(warned(diagnostics, "com-example-payment", "com-example-Payment"),
            "a collision that costs one element its guardrails on half the platforms in use must "
                + "be said out loud. Diagnostics: " + messages(diagnostics));
    }

    @Test
    @DisplayName("ordinary distinct rule files raise no such warning")
    void distinctStemsAreNotWarnedAbout(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");

        h.addSource("com.example.Payment",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"wire format\")\n"
                + "public class Payment {}\n");
        h.addSource("com.example.Refund",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"ledger contract\")\n"
                + "public class Refund {}\n");

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(diagnostics.stream()
                .map(d -> d.getMessage(Locale.ROOT))
                .noneMatch(m -> m.contains("differ only in capitalisation")),
            "a warning on every ordinary build is one nobody reads. Diagnostics: "
                + messages(diagnostics));
    }

    private static boolean warned(List<Diagnostic<? extends JavaFileObject>> diagnostics,
                                  String first, String second) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(Locale.ROOT))
            .anyMatch(m -> m.contains("differ only in capitalisation")
                && m.contains(first) && m.contains(second));
    }

    private static String messages(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream().map(d -> d.getMessage(Locale.ROOT)).toList().toString();
    }
}
