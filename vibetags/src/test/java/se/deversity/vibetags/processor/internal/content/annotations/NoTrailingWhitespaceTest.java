package se.deversity.vibetags.processor.internal.content.annotations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A generated line must not end in whitespace.
 *
 * <p>Both of these formatters build a summary by appending {@code ". "} after each clause that has
 * a value, then appending an optional trailing field. When that last field is unset the summary
 * ends in a space, and it reaches the file that way. The one that bit: a
 * {@code @AIObservability(metrics = ...)} with no {@code note} put {@code "Metrics: m. "} into
 * {@code llms.txt} and {@code TESTING.md}.
 *
 * <p>It matters more than tidiness. Trailing whitespace is what the standard pre-commit
 * {@code trailing-whitespace} hook removes, and VibeTags rewrites on the next build, so a consumer
 * running both gets a file the two tools fight over forever and a repository that is never clean.
 * This project runs that hook itself, which is how it surfaced.
 */
class NoTrailingWhitespaceTest {

    /** Platforms whose output for these annotations is prose with a summary sentence in it. */
    static Stream<Platform> prosePlatforms() {
        return Stream.of(Platform.CURSOR, Platform.WINDSURF, Platform.CODEX, Platform.COPILOT,
            Platform.QWEN, Platform.GEMINI, Platform.GEMINI_MD, Platform.LLMS, Platform.LLMS_FULL,
            Platform.ZED, Platform.INTERPRETER, Platform.CLAUDE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prosePlatforms")
    @DisplayName("@AIObservability with no note leaves no line ending in a space")
    void observabilityWithoutANoteEndsNoLineInWhitespace(Platform platform) {
        StringBuilder sb = new StringBuilder();
        FormatterRegistry.observability().format(observabilityElement(), sb, platform);
        assertEquals(List.of(), offendingLines(sb.toString()),
            platform + " ended a line in whitespace; pre-commit strips it and the next build "
                + "writes it back. Rendered:\n" + sb);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prosePlatforms")
    @DisplayName("@AICore with no note leaves no line ending in a space")
    void coreWithoutANoteEndsNoLineInWhitespace(Platform platform) {
        StringBuilder sb = new StringBuilder();
        FormatterRegistry.core().format(coreElement(), sb, platform);
        assertEquals(List.of(), offendingLines(sb.toString()),
            platform + " ended a line in whitespace; pre-commit strips it and the next build "
                + "writes it back. Rendered:\n" + sb);
    }

    /** Line numbers whose text ends in whitespace, ignoring lines that are only whitespace. */
    private static List<String> offendingLines(String rendered) {
        List<String> bad = new ArrayList<>();
        String[] lines = rendered.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isBlank() && !line.equals(stripTrailing(line))) {
                bad.add("line " + (i + 1) + ": [" + line + "]");
            }
        }
        return bad;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static TaggedElement observabilityElement() {
        AIObservability obs = (AIObservability) Proxy.newProxyInstance(
            AIObservability.class.getClassLoader(),
            new Class<?>[]{AIObservability.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> AIObservability.class;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@AIObservability(fixture)";
                case "metrics" -> new String[]{"orders.placed"};
                case "note" -> "";
                default -> new String[0];
            });
        return element().annotation(AIObservability.class, obs).build();
    }

    private static TaggedElement coreElement() {
        AICore core = (AICore) Proxy.newProxyInstance(
            AICore.class.getClassLoader(),
            new Class<?>[]{AICore.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> AICore.class;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@AICore(fixture)";
                case "note" -> "";
                default -> sensitivity(method.getReturnType());
            });
        return element().annotation(AICore.class, core).build();
    }

    /** The declared default for an enum-typed member, so this test does not name the enum. */
    private static Object sensitivity(Class<?> returnType) {
        return returnType.isEnum() ? returnType.getEnumConstants()[0] : "";
    }

    private static TaggedElement.Builder element() {
        return TaggedElement.builder("com.example.Orders")
            .names("com.example.Orders", "Orders", "com.example.Orders", "com.example.Orders")
            .kind(ElementTag.CLASS)
            .signature("com.example.Orders");
    }
}
