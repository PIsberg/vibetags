package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An annotation written the short way must not put a bold label with nothing after it into the
 * per-element rule file (#507).
 *
 * <p>{@code UnsetMemberRenderingTest} asks this of the renderer. This asks it of the file, because
 * the section collapser sits in between: it hoists the lines every stanza in a section shares, and
 * a label that renders empty for each of them is exactly the shape it hoists, which would move the
 * defect rather than remove it.
 *
 * <p>It also pins the half of the fix that is not a deletion of text. A class whose only annotation
 * is a bare {@code @AIContext} or {@code @AIArchitecture} has nothing to put under a heading, so it
 * gets no rule file, and with the granular directory opted in the aggregate has collapsed to an
 * index of those files, so it is not listed there either. That is the contract the processor
 * already prints at compile time: {@code CoreRules} warns that a blank {@code @AIContext} will be
 * ignored. The warning is asserted here so the two cannot drift apart. Without granular opt-in the
 * aggregate still names the element, which
 * {@code UnsetMemberRenderingTest.RENDERS_NOTHING_WHEN_BARE} pins; only the per-element files move.
 */
@Tag("e2e")
class GranularEmptyMemberEndToEndTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;
    private static List<Diagnostic<? extends JavaFileObject>> diagnostics;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir, false);
        harness.touchOptIn("CLAUDE.md");
        harness.touchOptIn(".claude/rules/.vibetags");

        // Written bare, which is what these compile to when the optional members are left out.
        add("bare", "Watched", "@AIObservability");
        add("bare", "Layered", "@AIArchitecture");
        add("bare", "Focused", "@AIContext");

        // The populated forms, so the fix cannot pass by rendering nothing for anybody.
        add("filled", "Metered", "@AIObservability(metrics = {\"orders.placed\"})");
        add("filled", "Domain", "@AIArchitecture(belongsTo = \"domain\")");
        add("filled", "Narrowed", "@AIContext(focus = \"settlement timing\")");

        diagnostics = harness.compileReturningDiagnostics();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static void add(String pkg, String className, String annotation) {
        harness.addSource("com.example." + pkg + "." + className,
            "package com.example." + pkg + ";\n"
                + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "import se.deversity.vibetags.annotations.AIObservability;\n"
                + annotation + "\n"
                + "public final class " + className + " {}\n");
    }

    @Test
    @DisplayName("a bare @AIObservability keeps its rule and drops the empty Details label")
    void bareObservabilityDropsTheEmptyLabel() throws IOException {
        String rules = harness.readFile(".claude/rules/com-example-bare-Watched.md");
        assertTrue(rules.contains("Do not remove or rename instrumentation"),
            "the constant rule must survive; only the empty member goes");
        assertFalse(rules.contains("**Details**"),
            "a Details label with nothing after it costs context and carries no guardrail:"
                + System.lineSeparator() + rules);
    }

    @Test
    @DisplayName("a populated @AIObservability still renders its Details")
    void populatedObservabilityStillRendersDetails() throws IOException {
        String rules = harness.readFile(".claude/rules/com-example-filled-Metered.md");
        assertTrue(rules.contains("**Details**: Metrics: orders.placed."),
            "the fix must not silence a member somebody set:" + System.lineSeparator() + rules);
    }

    @Test
    @DisplayName("a class annotated only with a bare @AIContext gets no rule file and no index entry")
    void bareContextProducesNoRuleFile() throws IOException {
        assertFalse(harness.fileExists(".claude/rules/com-example-bare-Focused.md"),
            "a heading with nothing under it reads as an annotation that says nothing, so no "
                + "stanza is recorded and there is nothing to put in a file");
        assertFalse(harness.readFile("CLAUDE.md").contains("com.example.bare.Focused"),
            "with the granular directory opted in the aggregate is an index of rule files, so an "
                + "element with no rule file has no entry: an index line pointing at a file that "
                + "says nothing is the same empty label one level up. CLAUDE.md:"
                + System.lineSeparator() + harness.readFile("CLAUDE.md"));
    }

    @Test
    @DisplayName("a class annotated only with a bare @AIArchitecture gets no rule file and no index entry")
    void bareArchitectureProducesNoRuleFile() throws IOException {
        assertFalse(harness.fileExists(".claude/rules/com-example-bare-Layered.md"),
            "a layer nobody named and no prohibited references leave nothing to say");
        assertFalse(harness.readFile("CLAUDE.md").contains("com.example.bare.Layered"),
            "no rule file means no index entry, for the reason the @AIContext case states");
    }

    @Test
    @DisplayName("the populated forms still get their own rule files, and their index entries")
    void populatedFormsStillGetRuleFiles() throws IOException {
        assertTrue(harness.readFile(".claude/rules/com-example-filled-Domain.md")
                .contains("**Layer**: domain"),
            "a named layer must still reach the rule file");
        assertTrue(harness.readFile(".claude/rules/com-example-filled-Narrowed.md")
                .contains("**Focus**: settlement timing"),
            "a stated focus must still reach the rule file");

        String index = harness.readFile("CLAUDE.md");
        assertTrue(index.contains("com.example.filled.Domain")
                && index.contains("com.example.filled.Narrowed"),
            "dropping the empty ones must not drop the populated ones with them:"
                + System.lineSeparator() + index);
    }

    /**
     * The author is not left to discover the missing file for themselves. Both annotations warn at
     * compile time, and those warnings are why the silence downstream is a contract rather than a
     * loss: the processor says the annotation will be ignored before it ignores it.
     */
    @Test
    @DisplayName("the compiler warns about both bare annotations")
    void theAuthorIsWarnedAboutBothBareAnnotations() {
        assertTrue(warned("@AIContext", "Focused"),
            "CoreRules warns that a blank @AIContext will be ignored, and that warning is what "
                + "makes the absent rule file a stated outcome. Diagnostics: " + messages());
        assertTrue(warned("@AIArchitecture", "Layered"),
            "ArchitectureRule warns about a blank belongsTo. Diagnostics: " + messages());
    }

    private static boolean warned(String annotation, String className) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(Locale.ROOT))
            .anyMatch(m -> m.contains(annotation) && m.contains(className));
    }

    private static String messages() {
        return diagnostics.stream().map(d -> d.getMessage(Locale.ROOT)).toList().toString();
    }
}
