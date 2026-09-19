package se.deversity.vibetags.ksp.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a constructor {@code val}'s annotation with no use-site target lands, by
 * {@code -Xannotation-default-target} (#756). Under {@code param-property}, Kotlin 2.2's default, an
 * annotation that may target both goes on the parameter and the field; under {@code first-only}, the
 * default before 2.2, on the parameter alone. The field is an element path of its own, so the mode
 * changes what a guardrail is keyed by.
 *
 * <p>{@code annotation-default-target/<mode>/} holds kapt's generated {@code CLAUDE.md} and
 * {@code llms-full.txt} for {@code annotation-default-target/src}, built with Kotlin 2.4.10 under each
 * setting of the flag. Re-record them from kapt; never edit them to match.
 */
class AnnotationDefaultTargetTest {

    @TempDir
    Path root;

    @Test
    void theTwoRecordingsDiffer() throws IOException {
        // Otherwise the tests below could not tell the modes apart.
        assertNotEquals(recorded("first-only", "CLAUDE.md"), recorded("param-property", "CLAUDE.md"));
    }

    @Test
    void kotlin22DefaultsToParamPropertyAsKaptDoes() throws IOException {
        assertMatchesKapt("param-property", run(harness -> harness));
    }

    @Test
    void theOptionSelectsFirstOnlyAsTheCompilerFlagDoes() throws IOException {
        assertMatchesKapt("first-only",
            run(harness -> harness.option("vibetags.ksp.annotationDefaultTarget", "first-only")));
    }

    @Test
    void aLanguageVersionBefore22DefaultsToFirstOnly() throws IOException {
        assertMatchesKapt("first-only", run(harness -> harness.languageVersion("2.1")));
    }

    @Test
    void theOptionIsTheAdaptersAndNeverReachesTheProcessor() throws IOException {
        KspHarness.Result result = run(harness -> harness.option("vibetags.ksp.annotationDefaultTarget",
            "first-only"));

        // The processor warns about any vibetags.* option it does not know; this one is not its.
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("unrecognized option")),
            () -> "warnings: " + result.warnings());
    }

    @Test
    void anUnknownValueWarnsAndFallsBackToTheLanguageDefault() throws IOException {
        KspHarness.Result result = run(harness -> harness.option("vibetags.ksp.annotationDefaultTarget",
            "param-only"));

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("param-only")
            && w.contains("is not a value of -Xannotation-default-target")), () -> "warnings: " + result.warnings());
        assertMatchesKapt("param-property", result);
    }

    private void assertMatchesKapt(String mode, KspHarness.Result result) throws IOException {
        assertEquals("OK", result.exitCode(), () -> "errors: " + result.errors());
        for (String file : new String[] {"CLAUDE.md", "llms-full.txt"}) {
            assertEquals(recorded(mode, file), Files.readString(root.resolve(file)), mode + " " + file);
        }
    }

    private KspHarness.Result run(UnaryOperator<KspHarness> configure) throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), "");
        Files.writeString(root.resolve("llms-full.txt"), "");
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Path sources = KspHarness.copySources("annotation-default-target/src", root.resolve("src"));
        return configure.apply(new KspHarness(sources, root)).run();
    }

    private static String recorded(String mode, String file) throws IOException {
        try (InputStream in = AnnotationDefaultTargetTest.class.getResourceAsStream(
                "/annotation-default-target/" + mode + "/" + file)) {
            assertTrue(in != null, () -> "missing recording " + mode + "/" + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
