package se.deversity.vibetags.processor.internal.content.annotations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .aiexclude} is a list of file globs, so only an element that <em>is</em> a file may
 * contribute one.
 *
 * <p>{@code AILockedFormatter} emitted {@code "**}{@code /" + simpleName + ".java"} for every locked
 * element regardless of kind. On a type that is right. On a member it is not, and the two failure
 * shapes differ in severity:
 *
 * <ul>
 *   <li>Inert noise, when the member name matches no file: this repository's own {@code .aiexclude}
 *       carried {@code **}{@code /generateFiles.java} for a locked method.
 *   <li>Silent over-exclusion, when it does. A field named {@code ALL} or {@code Config} emits
 *       {@code **}{@code /ALL.java} or {@code **}{@code /Config.java}, and Gemini Code Assist and
 *       Android Studio then drop an unrelated source file from AI context. Nothing reports it: the
 *       file simply stops being visible to the assistant, and the developer who locked one field
 *       never asked for that.
 * </ul>
 *
 * <p>A member-level lock still reaches every platform that can name a member — the
 * {@code locked_files} block, {@code .vibetags-locks}, Codex, Copilot. Only the glob format drops
 * it, because a glob cannot express "this field".
 */
class AILockedExcludeGlobTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ElementTag.class,
        names = {"METHOD", "CONSTRUCTOR", "FIELD", "ENUM_CONSTANT", "PARAMETER", "PACKAGE"})
    @DisplayName("a locked member contributes no glob to .aiexclude")
    void memberContributesNoGlob(ElementTag kind) {
        assertEquals("", render(kind, "ALL"),
            kind + " is not a file, so it must not produce a **/<name>.java exclusion");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ElementTag.class, names = {"CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE"})
    @DisplayName("a locked type still contributes its file glob")
    void typeStillContributesItsGlob(ElementTag kind) {
        assertEquals("**/Payments.java\n", render(kind, "Payments"),
            kind + " is a file and must keep its exclusion");
    }

    @Test
    @DisplayName("the prose platforms still name the locked member")
    void memberStillReachesTheProsePlatforms() {
        StringBuilder sb = new StringBuilder();
        FormatterRegistry.locked().format(element(ElementTag.FIELD, "ALL"), sb, Platform.CLAUDE);
        assertTrue(sb.toString().contains("com.example.Registry.ALL"),
            "dropping the glob must not drop the lock: " + sb);
    }

    private static String render(ElementTag kind, String simpleName) {
        StringBuilder sb = new StringBuilder();
        FormatterRegistry.locked().format(element(kind, simpleName), sb, Platform.AI_EXCLUDE);
        return sb.toString();
    }

    private static TaggedElement element(ElementTag kind, String simpleName) {
        AILocked locked = (AILocked) Proxy.newProxyInstance(
            AILocked.class.getClassLoader(),
            new Class<?>[]{AILocked.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "reason" -> "append only";
                case "annotationType" -> AILocked.class;
                case "toString" -> "@AILocked";
                case "hashCode" -> 0;
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
        String qName = "com.example.Registry." + simpleName;
        return TaggedElement.builder(qName)
            .names(qName, simpleName, qName, qName)
            .kind(kind)
            .signature(qName)
            .annotation(AILocked.class, locked)
            .build();
    }
}
