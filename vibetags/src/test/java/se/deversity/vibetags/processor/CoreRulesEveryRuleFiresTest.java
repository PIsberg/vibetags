package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.processor.internal.validation.CoreRules;
import se.deversity.vibetags.processor.internal.validation.ValidationContext;
import se.deversity.vibetags.processor.internal.validation.ValidationRule;

import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Each attribute rule must fire on the annotation it exists for, and stay quiet otherwise.
 *
 * <p>{@link CoreRules#all()} is seventeen rules whose whole purpose is one warning each: an
 * {@code @AIAudit} with no {@code checkFor} list, an {@code @AIKeepInSync} with no mirrors. Six of
 * those warnings had no test asserting them at all — searching the test tree for their message text
 * returned nothing for {@code @AIKeepInSync}, {@code @AIGenerated}, {@code @AIBannedApi},
 * {@code @AISunset}, {@code @AIObservability} and {@code @AIRegulation}. Deleting the
 * {@code ctx.warn} call from any of them left the suite green, which is the same drift that let
 * four renderers stop rendering the newest annotations: the rules kept being added, the assertions
 * did not.
 *
 * <p>So the check is derived from {@code CoreRules.all()} rather than written per rule. Rule
 * eighteen is covered the day it lands, in both directions: an annotation with nothing filled in
 * must be reported, and one with everything filled in must not be. The second direction is the one
 * that matters in a build — a rule that warns on correct code gets the whole processor muted.
 */
class CoreRulesEveryRuleFiresTest {

    /**
     * Rules that cannot fire on a blank annotation because they are about a <em>relationship</em>
     * rather than a missing attribute: a field that is not final, a number outside a range, a
     * second annotation that contradicts the first. A blank annotation on a bare element states
     * none of those, so silence there is correct. Each has its own positive case below, and a new
     * rule landing in this set has to be added deliberately — the test names it in the failure.
     */
    private static final Set<String> RELATIONSHIP_RULES =
        Set.of("AIImmutable", "AITestDriven", "AIThreadSafe");

    /** ISO date far enough out that {@code @AITemporary} is not overdue. */
    private static final String NOT_YET_EXPIRED = "2999-12-31";

    static Stream<Arguments> coreRules() {
        return CoreRules.all().stream()
            .map(rule -> Arguments.of(rule.scans().getSimpleName(), rule));
    }

    @ParameterizedTest(name = "@{0}")
    @MethodSource("coreRules")
    @DisplayName("reports an annotation with nothing filled in")
    void firesOnAnEmptyAnnotation(String annotation, ValidationRule rule) {
        Recorder recorder = check(rule, blank(), element());

        if (RELATIONSHIP_RULES.contains(annotation)) {
            assertTrue(recorder.messages.isEmpty(),
                annotation + " is listed as a relationship rule but reported something for a blank "
                    + "annotation on a bare element: " + recorder.messages);
            return;
        }
        assertFalse(recorder.messages.isEmpty(),
            "@" + annotation + " with every attribute left empty is indistinguishable from no "
                + "annotation at all once it reaches the agent, and nothing warned about it. Either "
                + "the rule stopped reporting, or it belongs in RELATIONSHIP_RULES");
    }

    @ParameterizedTest(name = "@{0}")
    @MethodSource("coreRules")
    @DisplayName("stays quiet on an annotation that is filled in")
    void silentOnACompleteAnnotation(String annotation, ValidationRule rule) {
        Recorder recorder = check(rule, complete(), element());

        assertEquals(List.of(), recorder.messages,
            "@" + annotation + " warned about a correctly filled-in annotation. A "
                + "rule that cries wolf is worse than a missing one: the first thing a team does "
                + "with a noisy processor is turn all of it off");
    }

    // ------------------------------------------------------ the three relationship rules

    @Test
    @DisplayName("@AIImmutable reports the field that is not final, not the type")
    void immutableReportsTheOffendingField() {
        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.getModifiers()).thenReturn(Set.of(Modifier.PRIVATE));
        Name name = mock(Name.class);
        when(name.toString()).thenReturn("mutable");
        when(field.getSimpleName()).thenReturn(name);

        Element type = element();
        doAnswer(invocation -> List.of(field)).when(type).getEnclosedElements();

        Recorder recorder = check(ruleFor(AIImmutable.class), blank(), type);

        assertEquals(1, recorder.messages.size(), "expected one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("'mutable' is not final"),
            "the warning must name the field the developer has to change: " + recorder.messages);
    }

    @Test
    @DisplayName("@AITestDriven reports a coverage goal outside 0..100")
    void testDrivenReportsAnImpossibleCoverageGoal() {
        Recorder recorder = check(ruleFor(AITestDriven.class),
            member -> "coverageGoal".equals(member.getName()) ? 101 : completeValue(member),
            element());

        assertEquals(1, recorder.messages.size(), "expected one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("101"),
            "the warning must quote the value that is wrong: " + recorder.messages);
    }

    @Test
    @DisplayName("@AIThreadSafe(IMMUTABLE) alongside @AIImmutable is reported as redundant")
    void threadSafeImmutableAlongsideImmutableIsRedundant() {
        Element target = element();
        doAnswer(invocation -> invocation.getArgument(0) == AIThreadSafe.class
            ? proxy(AIThreadSafe.class, m -> AIThreadSafe.Strategy.IMMUTABLE)
            : mock(AIImmutable.class)).when(target).getAnnotation(any());

        Recorder recorder = new Recorder();
        ruleFor(AIThreadSafe.class).check(recorder.ctx, target);

        assertEquals(1, recorder.messages.size(), "expected one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("@AIImmutable alone"),
            "the warning must say which annotation to keep: " + recorder.messages);
    }

    // ------------------------------------------------------------------ plumbing

    /** Collects what a rule reported, as {@code KIND|message}. */
    private static final class Recorder {
        private final List<String> messages = new ArrayList<>();
        private final ValidationContext ctx;

        Recorder() {
            Messager messager = mock(Messager.class);
            doAnswer(invocation -> {
                messages.add(invocation.getArgument(0) + "|" + invocation.getArgument(1));
                return null;
            }).when(messager).printMessage(any(Diagnostic.Kind.class), anyString(), any(Element.class));
            ctx = new ValidationContext(messager, mock(RoundEnvironment.class), null, null);
        }
    }

    /** Runs one rule against an element carrying {@code values} for the annotation it scans. */
    private static Recorder check(ValidationRule rule, MemberValues values, Element element) {
        doAnswer(invocation -> invocation.getArgument(0) == rule.scans()
            ? proxy(rule.scans(), values)
            : null).when(element).getAnnotation(any());

        Recorder recorder = new Recorder();
        rule.check(recorder.ctx, element);
        return recorder;
    }

    private static ValidationRule ruleFor(Class<? extends Annotation> type) {
        return CoreRules.all().stream()
            .filter(r -> r.scans() == type)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CoreRules rule scans " + type.getSimpleName()));
    }

    /** An element that reports as {@code com.example.Target} and encloses nothing. */
    private static Element element() {
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.Target");
        return element;
    }

    /** What one annotation member answers. */
    @FunctionalInterface
    private interface MemberValues {
        Object valueOf(Method member);
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A proxy(Class<A> type, MemberValues values) {
        return (A) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (self, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == (args == null ? null : args[0]);
                case "toString" -> "@" + type.getSimpleName() + "(test)";
                default -> values.valueOf(method);
            });
    }

    /** Nothing filled in: empty strings, empty arrays, false, zero. */
    private static MemberValues blank() {
        return member -> {
            Class<?> returns = member.getReturnType();
            if (returns == String.class) {
                return "";
            }
            if (returns.isArray()) {
                return Array.newInstance(returns.getComponentType(), 0);
            }
            if (returns == boolean.class) {
                return false;
            }
            if (returns == int.class) {
                return 0;
            }
            if (returns == long.class) {
                return 0L;
            }
            return fallback(member);
        };
    }

    /** Everything filled in, and filled in legally — no rule should have anything to say. */
    private static MemberValues complete() {
        return CoreRulesEveryRuleFiresTest::completeValue;
    }

    private static Object completeValue(Method member) {
        Class<?> returns = member.getReturnType();
        if (returns == String.class) {
            // The one member whose legal values are not "any non-blank string": an @AITemporary
            // whose date has passed is exactly what that rule reports, so a plausible-looking
            // string here would make the silent-when-complete case fail for the right reason.
            return "expiresOn".equals(member.getName()) ? NOT_YET_EXPIRED : "fixture-" + member.getName();
        }
        if (returns.isArray()) {
            Class<?> component = returns.getComponentType();
            if (component == String.class) {
                return new String[]{"fixture-" + member.getName()};
            }
            Object array = Array.newInstance(component, 1);
            Array.set(array, 0, component.isEnum() ? component.getEnumConstants()[0] : null);
            return array;
        }
        if (returns == boolean.class) {
            return true;
        }
        if (returns == int.class) {
            // Inside every range CoreRules checks; coverageGoal is 0..100.
            return 80;
        }
        if (returns == long.class) {
            return 80L;
        }
        return fallback(member);
    }

    /** Enums take their first constant; anything else takes the member's declared default. */
    private static Object fallback(Method member) {
        Class<?> returns = member.getReturnType();
        if (returns.isEnum()) {
            return returns.getEnumConstants()[0];
        }
        Object declared = member.getDefaultValue();
        if (declared != null) {
            return declared;
        }
        throw new AssertionError("no test value for " + member.getDeclaringClass().getSimpleName()
            + "." + member.getName() + "() of type " + returns);
    }
}
