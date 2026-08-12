package se.deversity.vibetags.processor.internal.content.annotations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @AIIgnore(reason = "...")} has to reach the file, like every other annotation's reason.
 *
 * <p>{@code AIIgnoreFormatter} never called {@code element.annotation(AIIgnore.class)} at all, so
 * the reason a developer wrote went nowhere on any of the 37 platforms. The annotation itself
 * rendered — the element was listed as excluded — which is what made this invisible: the file looked
 * right, and only the explanation was missing. An exclusion without its reason is the one an agent
 * cannot evaluate and a reviewer cannot audit.
 *
 * <p>Two rules, and the second is why this is not just "print the reason":
 *
 * <ul>
 *   <li>A reason the developer wrote must appear in the prose platforms.
 *   <li>The annotation's <em>default</em> reason must not. It says "Excluded from AI context",
 *       which is what the section heading above it already says; printing it on every entry would
 *       add a line of pure repetition to every file for everyone who never set one.
 * </ul>
 *
 * <p>The path-list platforms (the fifteen {@code *_IGNORE} files, {@code .aiexclude}, Mentat's
 * JSON) are deliberately left alone: they are machine-parsed globs and a schema, not prose.
 */
class AIIgnoreReasonTest {

    private static final String WRITTEN_REASON = "vendored upstream copy, edits are overwritten";

    /**
     * Platforms whose {@code @AIIgnore} output is prose a human reads. The path-list platforms are
     * excluded by shape, not by name: they emit a glob or a JSON entry with nowhere to put a
     * sentence.
     */
    static Stream<Platform> prosePlatforms() {
        return Stream.of(Platform.CURSOR, Platform.WINDSURF, Platform.CLAUDE, Platform.CODEX,
            Platform.COPILOT, Platform.QWEN, Platform.GEMINI, Platform.GEMINI_MD, Platform.LLMS,
            Platform.LLMS_FULL, Platform.AIDER_CONVENTIONS, Platform.ZED, Platform.INTERPRETER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prosePlatforms")
    @DisplayName("carries the reason the developer wrote")
    void rendersTheWrittenReason(Platform platform) {
        String rendered = render(platform, WRITTEN_REASON);

        assertTrue(rendered.contains(WRITTEN_REASON),
            platform + " rendered the exclusion without the reason behind it, which is the half a "
                + "reviewer needs: " + rendered);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prosePlatforms")
    @DisplayName("does not repeat the annotation's default reason")
    void omitsTheDefaultReason(Platform platform) {
        String rendered = render(platform, defaultReason());

        assertFalse(rendered.contains(defaultReason()),
            platform + " printed the default reason, which only restates the section heading and "
                + "would add a line of noise to every entry in every project: " + rendered);
    }

    @Test
    @DisplayName("the element is still listed when there is no reason to give")
    void stillListsTheElementWithoutAReason() {
        List<String> missing = new ArrayList<>();
        prosePlatforms().forEach(platform -> {
            if (!render(platform, defaultReason()).contains("com.example.Vendored")) {
                missing.add(platform.name());
            }
        });

        assertEquals(List.of(), missing,
            "dropping the default reason must not drop the exclusion itself — these platforms "
                + "stopped listing the element at all");
    }

    @Test
    @DisplayName("a null reason renders one line less, it does not throw")
    void survivesAnAnnotationInstanceThatAnswersNull() {
        // javac never returns null from an annotation member; a synthesized instance does, and
        // GuardrailInstructionBlock builds one for every annotation type. Reading the member
        // without this guard turned that into a NullPointerException out of a consumer's build —
        // caught by the full suite, not by this file, which is why it is pinned here now.
        List<String> threw = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            try {
                render(platform, null);
            } catch (RuntimeException e) {
                threw.add(platform + ": " + e);
            }
        }

        assertEquals(List.of(), threw,
            "VibeTags is advisory — a degraded annotation instance must cost a line of output, "
                + "never the compile");
    }

    @Test
    @DisplayName("the ignore files stay pure path lists")
    void ignoreFilesAreUnchangedByTheReason() {
        assertEquals(render(Platform.CURSOR_IGNORE, defaultReason()),
            render(Platform.CURSOR_IGNORE, WRITTEN_REASON),
            ".cursorignore is a glob list a tool parses; a reason has nowhere to go in it");
        assertEquals(render(Platform.MENTAT, defaultReason()), render(Platform.MENTAT, WRITTEN_REASON),
            "Mentat's output is a JSON schema, not prose");
    }

    /** The annotation's own default, read from the annotation rather than copied into this test. */
    private static String defaultReason() {
        try {
            return (String) AIIgnore.class.getDeclaredMethod("reason").getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("AIIgnore.reason() no longer exists", e);
        }
    }

    private static String render(Platform platform, String reason) {
        StringBuilder sb = new StringBuilder();
        FormatterRegistry.ignore().format(element(reason), sb, platform);
        return sb.toString();
    }

    private static TaggedElement element(String reason) {
        AIIgnore ignore = (AIIgnore) Proxy.newProxyInstance(
            AIIgnore.class.getClassLoader(),
            new Class<?>[]{AIIgnore.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> AIIgnore.class;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@AIIgnore(fixture)";
                default -> reason;
            });

        return TaggedElement.builder("com.example.Vendored")
            .names("com.example.Vendored", "Vendored", "com.example.Vendored", "com.example.Vendored")
            .kind(ElementTag.CLASS)
            .signature("com.example.Vendored")
            .annotation(AIIgnore.class, ignore)
            .build();
    }
}
