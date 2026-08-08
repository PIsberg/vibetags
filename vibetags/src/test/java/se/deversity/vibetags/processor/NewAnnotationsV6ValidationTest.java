package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compile-time validation warnings for the v1.0.0 evidence-based wave.
 *
 * <p>Each source below trips exactly one intended condition; the clean sources at the end assert
 * the warnings stay silent when the condition is not met, which is what stops a validator from
 * degrading into noise everybody filters out.
 */
class NewAnnotationsV6ValidationTest {

    @TempDir
    static Path tempDir;

    private static List<String> warnings;

    @BeforeAll
    static void compileAndCollect() throws IOException {
        warnings = new ArrayList<>();

        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);

        List<JavaFileObject> sources = List.of(
            // @AIGenerated + @AIIgnore — contradictory
            new StringSource("com/example/v6/GeneratedIgnored.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIGenerated;\n"
                    + "import se.deversity.vibetags.annotations.AIIgnore;\n"
                    + "@AIGenerated(from = \"schema.yaml\", regenerateWith = \"mvn generate-sources\")\n"
                    + "@AIIgnore\n"
                    + "public class GeneratedIgnored {}\n"),

            // @AIGenerated + @AIDraft — contradictory
            new StringSource("com/example/v6/GeneratedDraft.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIGenerated;\n"
                    + "import se.deversity.vibetags.annotations.AIDraft;\n"
                    + "@AIGenerated(from = \"schema.yaml\", editInstead = \"schema.yaml\")\n"
                    + "@AIDraft(instructions = \"Implement me\")\n"
                    + "public class GeneratedDraft {}\n"),

            // @AIGenerated with no route back to the source
            new StringSource("com/example/v6/GeneratedNoRoute.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIGenerated;\n"
                    + "@AIGenerated(from = \"schema.yaml\")\n"
                    + "public class GeneratedNoRoute {}\n"),

            // @AILoadBearing with no failure mode
            new StringSource("com/example/v6/LoadBearingNoBreaksIf.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AILoadBearing;\n"
                    + "@AILoadBearing(invariant = \"The retry loop must stay unrolled\")\n"
                    + "public class LoadBearingNoBreaksIf {}\n"),

            // @AILoadBearing(suppressAudit) + @AIAudit — contradictory
            new StringSource("com/example/v6/LoadBearingAudited.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AILoadBearing;\n"
                    + "import se.deversity.vibetags.annotations.AIAudit;\n"
                    + "@AILoadBearing(invariant = \"Keep it\", breaksIf = \"Crash\", suppressAudit = true)\n"
                    + "@AIAudit(checkFor = {\"SQL Injection\"})\n"
                    + "public class LoadBearingAudited {}\n"),

            // @AIBannedApi with an empty ban list
            new StringSource("com/example/v6/BannedApiEmpty.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIBannedApi;\n"
                    + "@AIBannedApi(forbidden = {})\n"
                    + "public class BannedApiEmpty {}\n"),

            // @AIBannedApi with no sanctioned replacement
            new StringSource("com/example/v6/BannedApiNoRoute.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIBannedApi;\n"
                    + "@AIBannedApi(forbidden = {\"java.util.Date\"})\n"
                    + "public class BannedApiNoRoute {}\n"),

            // @AIThreadAffinity + @AIThreadSafe — the headline contradiction
            new StringSource("com/example/v6/AffinityAndSafe.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadAffinity;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                    + "@AIThreadAffinity(value = AIThreadAffinity.Affinity.MAIN_ONLY, marshalVia = \"invokeLater\")\n"
                    + "@AIThreadSafe\n"
                    + "public class AffinityAndSafe {}\n"),

            // @AIThreadAffinity(NAMED) with no thread name
            new StringSource("com/example/v6/AffinityUnnamed.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadAffinity;\n"
                    + "@AIThreadAffinity(value = AIThreadAffinity.Affinity.NAMED, marshalVia = \"post()\")\n"
                    + "public class AffinityUnnamed {}\n"),

            // @AIKeepInSync with an empty mirror list
            new StringSource("com/example/v6/KeepInSyncEmpty.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIKeepInSync;\n"
                    + "@AIKeepInSync(mirrors = {})\n"
                    + "public class KeepInSyncEmpty {}\n"),

            // Fully-specified sources: none of the above conditions should fire for these.
            new StringSource("com/example/v6/CleanGenerated.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIGenerated;\n"
                    + "@AIGenerated(from = \"orders.yaml\", regenerateWith = \"mvn generate-sources\",\n"
                    + "             editInstead = \"orders.yaml\")\n"
                    + "public class CleanGenerated {}\n"),

            new StringSource("com/example/v6/CleanAffinity.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadAffinity;\n"
                    + "@AIThreadAffinity(value = AIThreadAffinity.Affinity.NAMED, thread = \"Swing EDT\",\n"
                    + "                  marshalVia = \"SwingUtilities.invokeLater\",\n"
                    + "                  symptomIfViolated = \"Silent repaint corruption\")\n"
                    + "public class CleanAffinity {}\n"),

            new StringSource("com/example/v6/CleanKeepInSync.java",
                "package com.example.v6;\n"
                    + "import se.deversity.vibetags.annotations.AIKeepInSync;\n"
                    + "@AIKeepInSync(mirrors = {\"pom.xml\", \"README.md\"}, reason = \"Version drifts\",\n"
                    + "              enforcedBy = \"ProjectFactsConsistencyTest\")\n"
                    + "public class CleanKeepInSync {}\n")
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = ProcessorTestHarness.sharedFileManager()) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + tempDir.toAbsolutePath()
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        }

        for (var d : diagnostics.getDiagnostics()) {
            warnings.add(d.getMessage(Locale.ROOT));
        }
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static void assertWarned(String about, String... fragments) {
        assertTrue(warnings.stream().anyMatch(w -> {
            for (String fragment : fragments) {
                if (!w.contains(fragment)) return false;
            }
            return true;
        }), "Expected a warning about " + about + ". Warnings: " + warnings);
    }

    @Test
    void warns_generatedAndIgnore_combination() {
        assertWarned("@AIGenerated + @AIIgnore", "@AIGenerated", "@AIIgnore", "contradictory");
    }

    @Test
    void warns_generatedAndDraft_combination() {
        assertWarned("@AIGenerated + @AIDraft", "@AIGenerated", "@AIDraft", "contradictory");
    }

    @Test
    void warns_generated_withNoRouteBackToTheSource() {
        assertWarned("@AIGenerated without a redirect", "@AIGenerated", "GeneratedNoRoute", "no route back");
    }

    @Test
    void warns_loadBearing_withBlankBreaksIf() {
        assertWarned("@AILoadBearing without a failure mode",
            "@AILoadBearing", "LoadBearingNoBreaksIf", "breaksIf");
    }

    @Test
    void warns_loadBearingSuppressAudit_withAudit() {
        assertWarned("suppressAudit + @AIAudit", "@AILoadBearing", "@AIAudit", "contradictory");
    }

    @Test
    void warns_bannedApi_withEmptyForbiddenList() {
        assertWarned("@AIBannedApi banning nothing", "@AIBannedApi", "no forbidden APIs");
    }

    @Test
    void warns_bannedApi_withNoSanctionedRoute() {
        assertWarned("@AIBannedApi without useInstead",
            "@AIBannedApi", "BannedApiNoRoute", "useInstead");
    }

    @Test
    void warns_threadAffinityAndThreadSafe_combination() {
        assertWarned("@AIThreadAffinity + @AIThreadSafe",
            "@AIThreadAffinity", "@AIThreadSafe", "opposite");
    }

    @Test
    void warns_namedAffinity_withBlankThread() {
        assertWarned("Affinity.NAMED without a thread name",
            "@AIThreadAffinity", "AffinityUnnamed", "NAMED");
    }

    @Test
    void warns_keepInSync_withEmptyMirrorList() {
        assertWarned("@AIKeepInSync with no mirrors", "@AIKeepInSync", "no mirrors");
    }

    @Test
    void staysSilent_forFullySpecifiedAnnotations() {
        assertFalse(warnings.stream().anyMatch(w -> w.contains("CleanGenerated")),
            "A fully-specified @AIGenerated must not warn. Warnings: " + warnings);
        assertFalse(warnings.stream().anyMatch(w -> w.contains("CleanAffinity")),
            "A fully-specified @AIThreadAffinity must not warn. Warnings: " + warnings);
        assertFalse(warnings.stream().anyMatch(w -> w.contains("CleanKeepInSync")),
            "A fully-specified @AIKeepInSync must not warn. Warnings: " + warnings);
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String name, String code) {
            super(URI.create("string:///" + name), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
