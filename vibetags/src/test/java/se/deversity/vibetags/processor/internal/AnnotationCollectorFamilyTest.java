package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Pins the named bucket accessors on {@link AnnotationCollector} as a complete, one-to-one index of
 * {@link GuardrailAnnotations#ALL}.
 *
 * <p>The family is a hand-written fan-out: one accessor per registered annotation, added by hand by
 * whoever adds the annotation. Five of the 44 have no caller in main or test, which invites deleting
 * them; deleting any of them turns a list that is complete into a list that is merely long, and the
 * add-annotation skill would then need a rule for when an accessor is wanted and when it is not.
 * Keeping the family uniform is the cheaper answer, but only if something enforces it, so this test
 * is what makes "uniform" a fact rather than an intention, and it is what gives the five otherwise
 * uncalled accessors a caller.
 *
 * <p>The one-to-one half matters independently: these 44 lines are the most copy-pasteable in the
 * repository, and an accessor pointing at its neighbour's annotation class renders the wrong bucket
 * into every generated file with nothing else failing.
 */
class AnnotationCollectorFamilyTest {

    /** Every public no-arg {@code Set<Element>} accessor, which is exactly the named-bucket family. */
    private static List<Method> bucketAccessors() {
        List<Method> accessors = new ArrayList<>();
        for (Method m : AnnotationCollector.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.getParameterCount() != 0 || m.isSynthetic()) {
                continue;
            }
            if (m.getReturnType() != Set.class) {
                continue;
            }
            Type generic = m.getGenericReturnType();
            if (generic instanceof ParameterizedType p
                    && p.getActualTypeArguments().length == 1
                    && p.getActualTypeArguments()[0] == Element.class) {
                accessors.add(m);
            }
        }
        return accessors;
    }

    /**
     * A collector holding one distinct element per registered annotation, so that the element an
     * accessor returns identifies the annotation class it reads.
     */
    private static Map<Element, Class<? extends Annotation>> populate(AnnotationCollector collector) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        Map<Element, Class<? extends Annotation>> owner = new LinkedHashMap<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            Element element = mock(Element.class);
            owner.put(element, type);
            doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(type);
        }
        assertTrue(collector.collect(roundEnv), "the mocked round should have produced elements");
        return owner;
    }

    @Test
    @DisplayName("every registered annotation has exactly one named bucket accessor")
    void everyRegisteredAnnotationHasOneAccessor() {
        AnnotationCollector collector = new AnnotationCollector();
        Map<Element, Class<? extends Annotation>> owner = populate(collector);

        Map<Class<? extends Annotation>, List<String>> reachedBy = new LinkedHashMap<>();
        for (Method accessor : bucketAccessors()) {
            Set<Element> returned = invoke(accessor, collector);
            assertEquals(1, returned.size(),
                accessor.getName() + "() returned " + returned.size() + " elements; each accessor "
                    + "must read exactly one annotation bucket");
            Class<? extends Annotation> type = owner.get(returned.iterator().next());
            reachedBy.computeIfAbsent(type, unused -> new ArrayList<>()).add(accessor.getName());
        }

        Set<String> registered = new TreeSet<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            registered.add(type.getSimpleName());
        }
        Set<String> covered = new TreeSet<>();
        for (Class<? extends Annotation> type : reachedBy.keySet()) {
            covered.add(type.getSimpleName());
        }
        assertEquals(registered, covered,
            "AnnotationCollector's named bucket accessors no longer index GuardrailAnnotations.ALL "
                + "one for one; add the missing accessor rather than leaving the family partial");

        Set<String> duplicated = new LinkedHashSet<>();
        for (Map.Entry<Class<? extends Annotation>, List<String>> e : reachedBy.entrySet()) {
            if (e.getValue().size() > 1) {
                duplicated.add(e.getKey().getSimpleName() + " read by " + e.getValue());
            }
        }
        assertEquals(Set.of(), duplicated,
            "two accessors read the same annotation bucket, so some other annotation has none");
    }

    @Test
    @DisplayName("each accessor reads the annotation its own name promises")
    void eachAccessorReadsTheAnnotationItsNamePromises() {
        AnnotationCollector collector = new AnnotationCollector();
        Map<Element, Class<? extends Annotation>> owner = populate(collector);

        Set<String> mismatched = new LinkedHashSet<>();
        for (Method accessor : bucketAccessors()) {
            Set<Element> returned = invoke(accessor, collector);
            if (returned.size() != 1) {
                continue; // reported by everyRegisteredAnnotationHasOneAccessor
            }
            Class<? extends Annotation> type = owner.get(returned.iterator().next());
            String promised = type.getSimpleName().substring(2); // drop the "AI" prefix
            if (!accessor.getName().equalsIgnoreCase(promised)) {
                mismatched.add(accessor.getName() + "() reads " + type.getSimpleName());
            }
        }
        // Bijection alone does not catch two accessors whose bodies were swapped: the family stays
        // one-to-one while both render the wrong bucket. Naming is what detects that.
        assertEquals(Set.of(), mismatched,
            "an accessor reads an annotation other than the one its name names");
    }

    @Test
    @DisplayName("the family has one accessor per registered annotation and no spares")
    void theFamilyIsExactlyAsLargeAsTheRegistry() {
        assertEquals(GuardrailAnnotations.ALL.size(), bucketAccessors().size(),
            "the named bucket accessors and GuardrailAnnotations.ALL must stay the same length");
    }

    @SuppressWarnings("unchecked")
    private static Set<Element> invoke(Method accessor, AnnotationCollector collector) {
        try {
            return (Set<Element>) accessor.invoke(collector);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + accessor.getName() + "()", e);
        }
    }
}
