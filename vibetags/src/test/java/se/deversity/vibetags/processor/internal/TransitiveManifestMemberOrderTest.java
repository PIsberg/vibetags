package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A published manifest must be the same bytes on every JDK.
 *
 * <p>This exists because it was not. {@link Class#getDeclaredMethods()} has no specified order and
 * the order genuinely differs between releases: on JDK 26 {@code @AIContext} reported
 * {@code focus} then {@code avoids}; on JDK 25 the reverse. The whole test suite was green on one
 * machine while CI's Maven legs failed on all three JDKs, because the reactor example regenerated
 * its committed guardrails with the two attributes swapped.
 *
 * <p>That is a defect worth a dedicated test rather than a line in an existing one. It cannot be
 * caught by running the suite — every JVM agrees with itself — so the check has to be on the
 * property (sorted) rather than on the symptom (differs between machines nobody has both of).
 */
class TransitiveManifestMemberOrderTest {

    /**
     * An annotation instance built as a dynamic proxy.
     *
     * <p>Reflection is not an option: every {@code @AI...} annotation is {@code SOURCE}-retention,
     * so it is absent from the class file and {@code Class.getAnnotation} returns null. A proxy is
     * the same shape {@code membersOf} actually meets — javac hands the processor a synthesized
     * implementation of the annotation interface, not a class-file record — and it lets a test
     * choose which attributes are set without compiling a fixture.
     *
     * @param values attribute name to value; anything not named falls back to the declared default
     */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A proxy(Class<A> type, Map<String, Object> values) {
        return (A) java.lang.reflect.Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type},
            (p, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "toString" -> "@" + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == (args == null ? null : args[0]);
                default -> values.containsKey(method.getName())
                    ? values.get(method.getName())
                    : method.getDefaultValue();
            });
    }

    private static Annotation instanceOf(Class<? extends Annotation> type) {
        return switch (type.getSimpleName()) {
            case "AIContext" -> proxy(AIContext.class, Map.of("focus", "f", "avoids", "a"));
            case "AICore" -> proxy(AICore.class, Map.of("sensitivity", "high", "note", "n"));
            case "AISecure" -> proxy(AISecure.class, Map.of("aspect", "s"));
            case "AIThreadSafe" -> proxy(AIThreadSafe.class,
                Map.of("strategy", AIThreadSafe.Strategy.IMMUTABLE, "note", "t"));
            default -> throw new IllegalArgumentException(type.getName());
        };
    }

    private static List<String> memberNames(Class<? extends Annotation> type) {
        return new ArrayList<>(TransitiveManifest.membersOf(instanceOf(type)).keySet());
    }

    @Test
    void attributesComeOutInNameOrder() {
        // @AIContext declares focus before avoids. Reflection may report either first, so the only
        // order that is the same everywhere is the sorted one.
        assertEquals(List.of("avoids", "focus"), memberNames(AIContext.class));
        assertEquals(List.of("note", "sensitivity"), memberNames(AICore.class));
        assertEquals(List.of("note", "strategy"), memberNames(AIThreadSafe.class));
    }

    @Test
    void everyAnnotationsAttributesAreSorted() {
        for (Class<? extends Annotation> type :
                List.of(AIContext.class, AICore.class, AISecure.class, AIThreadSafe.class)) {
            List<String> names = memberNames(type);
            List<String> sorted = new ArrayList<>(names);
            java.util.Collections.sort(sorted);
            assertEquals(sorted, names, type.getSimpleName() + " reported its attributes unsorted");
        }
    }

    @Test
    void repeatedReadsAgree() {
        assertEquals(TransitiveManifest.membersOf(instanceOf(AIContext.class)),
            TransitiveManifest.membersOf(instanceOf(AIContext.class)));
    }

    @Test
    void theRenderedSummaryFollowsTheSameOrder() {
        // memberSummary() is what reaches both the generated files and the build fingerprint, so
        // ordering that stopped at the map would still churn every consumer's committed output.
        Map<String, String> members = TransitiveManifest.membersOf(instanceOf(AIContext.class));
        String summary = new se.deversity.vibetags.processor.model.TransitiveRule(
            "o", "p", "@AIContext", TransitiveManifest.tierOf("@AIContext"), members).memberSummary();
        assertEquals("avoids=a; focus=f", summary);
    }

    @Test
    void theSerialisedManifestFollowsTheSameOrder() {
        // The JAR entry itself, not just the rendered file. A manifest whose bytes depend on the
        // publishing JDK is not reproducible for anyone downstream.
        String json = TransitiveManifest.toJson("com.acme.api", "o",
            List.of(new se.deversity.vibetags.processor.model.TransitiveRule("o", "com.acme.api",
                "@AIContext", TransitiveManifest.tierOf("@AIContext"),
                TransitiveManifest.membersOf(instanceOf(AIContext.class)))), "1.0");
        assertTrue(json.indexOf("\"avoids\"") < json.indexOf("\"focus\""), json);
    }

    @Test
    void attributesLeftAtTheirDefaultAreOmitted() {
        // Not ordering, but the same method: a library that set only `aspect` must not publish the
        // other attributes as empty strings for an agent to read past.
        Map<String, String> members = TransitiveManifest.membersOf(instanceOf(AISecure.class));
        assertEquals(Map.of("aspect", "s"), members);
        assertFalse(members.containsValue(""), "an empty attribute is noise, not a rule");
    }
}
