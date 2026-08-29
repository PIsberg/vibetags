package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An annotation that declares {@code reason()} must render the reason it was given into the
 * per-element rule file (issue #506).
 *
 * <p>The failure this pins is silent by construction. {@code reason} is optional on all but one of
 * these annotations, so nothing fails at compile time; the rule file is written, it is the right
 * size, and the sentence under the heading is the annotation's constant boilerplate, identical for
 * every use of that annotation in every project. The one project-specific sentence the author wrote
 * is the part that never arrives, and no diagnostic anywhere says so. In {@code async-test-lib}
 * that was 24 {@code @AIPublicAPI} uses rendering the same paragraph.
 *
 * <p>Derived from {@link GuardrailAnnotations#ALL} and from the {@code reason()} member itself, so
 * annotation 45 is covered on the day it lands and a renderer that forgets its reason names itself
 * in the failure.
 */
class GranularReasonRenderedTest {

    @Test
    @DisplayName("every annotation that declares reason() renders it in the granular stanza")
    void everyDeclaredReasonReachesTheRuleFile() {
        Map<TaggedElement, GranularBody> rules =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotation());

        List<String> dropped = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            String reason = fixtureReason(type);
            if (reason == null) {
                continue;
            }
            if (!String.join("\n", linesFor(rules, type)).contains(reason)) {
                dropped.add(type.getSimpleName());
            }
        }

        assertEquals(List.of(), dropped,
            "these annotations accept a reason and the granular renderer discards it, so the rule "
                + "file an agent reads carries only the boilerplate that is identical for every "
                + "use of that annotation. Render it as a `- **Reason**: ...` line, the way "
                + "@AILocked and @AIContract already do");
    }

    @Test
    @DisplayName("an omitted reason emits no Reason label with nothing after it")
    void unsetReasonEmitsNoEmptyLabel() {
        Map<TaggedElement, GranularBody> rules =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotationWithMembersUnset());

        List<String> empty = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (!omittable(type)) {
                continue;
            }
            for (String line : linesFor(rules, type)) {
                if (line.strip().equals("- **Reason**:")) {
                    empty.add(type.getSimpleName());
                }
            }
        }

        assertEquals(List.of(), empty,
            "a reason nobody wrote must read as though the member does not exist, not as though "
                + "its value went missing. Guard the line the way CommonFormatterHelper.bullet does");
    }

    @Test
    @DisplayName("a reason left at its declared default is not echoed back")
    void defaultedReasonIsNotEchoed() {
        Map<TaggedElement, GranularBody> rules =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotationWithMembersUnset());

        String body = String.join(System.lineSeparator(), linesFor(rules, AIIgnore.class));

        assertFalse(body.contains(declaredReason(AIIgnore.class)),
            "@AIIgnore.reason() defaults to a sentence that restates the rule line already above "
                + "it, so echoing it spends an agent's context on nothing. Only a reason somebody "
                + "actually wrote belongs in the file - AIIgnoreFormatter already draws that line");
    }

    /**
     * Guards the fixture: if {@code reason()} ever stopped being answered with a distinctive value,
     * the sweep above would pass by matching the empty string against every stanza.
     */
    @Test
    void theFixtureAnswersReasonWithSomethingDistinctive() {
        String reason = fixtureReason(se.deversity.vibetags.annotations.AILocked.class);
        assertFalse(reason == null || reason.isBlank(),
            "GuardrailModels must answer reason() with a value a stanza can be searched for");
    }

    /**
     * The value {@code GuardrailModels} hands the renderer for {@code type.reason()}, or
     * {@code null} when the annotation declares no such member.
     */
    private static String fixtureReason(Class<? extends Annotation> type) {
        Method reason;
        try {
            reason = type.getDeclaredMethod("reason");
        } catch (NoSuchMethodException e) {
            return null;
        }
        if (reason.getReturnType() != String.class) {
            return null;
        }
        try {
            Object value = reason.invoke(GuardrailModels.element(type).annotation(type));
            return value instanceof String s ? s : null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not answer " + type.getSimpleName() + ".reason()", e);
        }
    }

    /** Every line of every stanza recorded for {@code type}'s fixture element. */
    private static List<String> linesFor(Map<TaggedElement, GranularBody> rules,
                                         Class<? extends Annotation> type) {
        String marker = GuardrailModels.marker(type);
        List<String> lines = new ArrayList<>();
        for (GranularBody granular : rules.values()) {
            for (GranularBody.Entry stanza : granular.entries()) {
                if (marker.equals(stanza.element().qualifiedName())) {
                    lines.addAll(stanza.lines());
                }
            }
        }
        return lines;
    }

    /**
     * True when {@code type.reason()} has a declared default, which is the only way an author can
     * leave it out. A member with no default does not compile when omitted, so the empty value the
     * fixture supplies for it is a state no user can reach and not this test's subject.
     */
    private static boolean omittable(Class<? extends Annotation> type) {
        try {
            return type.getDeclaredMethod("reason").getDefaultValue() != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** {@code type.reason()}'s declared default. */
    private static String declaredReason(Class<? extends Annotation> type) {
        try {
            Object declared = type.getDeclaredMethod("reason").getDefaultValue();
            if (declared instanceof String s && !s.isBlank()) {
                return s;
            }
            throw new AssertionError(type.getSimpleName() + ".reason() has no non-blank default");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + " declares no reason()", e);
        }
    }
}
