package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.BuildFingerprint;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fingerprint of a fixed, fully populated build is a literal.
 *
 * <p>Every other fingerprint test compares two computed values: this attribute changes the hash,
 * that order does not. None of them can see a change that moves both sides together, and that is
 * the shape of the expensive mistake here. Reordering the descriptor table, renaming a tag or
 * rewording an extractor leaves every such comparison green while changing the hashed string for
 * every consumer, so each of them misses its {@code .vibetags-cache} short-circuit once and
 * nothing anywhere says why.
 *
 * <p>The values below were computed from the forty-four hand-written {@code appendAnnotationSet}
 * calls, before they became the descriptor table
 * (<a href="https://github.com/PIsberg/vibetags/issues/765">issue #765</a>), and have to survive
 * it. The first model carries one element per entry of {@link GuardrailAnnotations#ALL} with every
 * member answered, so each extractor's populated side is in the hash, and the second leaves every
 * optional member at its declared default so the other side is too.
 *
 * <p>A red result is not always a defect. Adding annotation 45 appends a section to the hashed
 * string, which is a real one-off invalidation of every consumer's cache and changes these
 * literals. Update them then, knowingly. Any other cause is a fingerprint change nobody decided on.
 */
@DisplayName("The fingerprint of a fixed model is pinned to a literal")
class BuildFingerprintPinnedValueTest {

    /** Fixed, so a release does not move the literals; the version's own effect is tested elsewhere. */
    private static final String VERSION = "pinned";

    private static final Set<String> SERVICES = Set.of("claude", "cursor", "llms");

    @Test
    @DisplayName("every annotation, every member populated")
    void populatedModelHashesToThePinnedValue() {
        assertEquals("3f87a281",
            BuildFingerprint.compute(collectorOf(GuardrailModels::element), SERVICES, VERSION),
            "the hashed string changed for a build whose annotations did not. Every consumer's "
                + "cached fingerprint is invalidated by whatever did this; see the class comment "
                + "for the one expected cause");
    }

    @Test
    @DisplayName("every annotation, optional members left at their defaults")
    void bareModelHashesToThePinnedValue() {
        assertEquals("d5e1d517",
            BuildFingerprint.compute(collectorOf(GuardrailModels::elementWithMembersUnset), SERVICES, VERSION),
            "the hashed string changed for a build of bare annotations. See the class comment");
    }

    /**
     * Presence, per annotation and derived from the registry. The literals above would also move
     * if a descriptor went missing, but they cannot say which; this names it.
     */
    @Test
    @DisplayName("each registered annotation, alone, moves the fingerprint")
    void everyAnnotationAloneMovesTheFingerprint() {
        String empty = BuildFingerprint.compute(new AnnotationCollector(), SERVICES, VERSION);
        List<String> unhashed = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            RoundEnvironment round = mock(RoundEnvironment.class);
            doReturn(Set.of(elementCarrying(type, GuardrailModels::element)))
                .when(round).getElementsAnnotatedWith(type);
            AnnotationCollector collector = new AnnotationCollector();
            collector.collect(round);
            if (empty.equals(BuildFingerprint.compute(collector, SERVICES, VERSION))) {
                unhashed.add(type.getSimpleName());
            }
        }
        assertEquals(List.of(), unhashed,
            "these annotations are collected and rendered but never reach the fingerprint "
                + "(invariant 12), so editing one leaves the hash unchanged and the short-circuit "
                + "skips the regeneration it needed. Each needs an entry in AnnotationDescriptors.ALL");
    }

    /**
     * A collector holding one mocked element per registered annotation, carrying the annotation
     * instance the {@link GuardrailModels} fixture built for that type.
     */
    private static AnnotationCollector collectorOf(Function<Class<? extends Annotation>, TaggedElement> fixture) {
        RoundEnvironment round = mock(RoundEnvironment.class);
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            doReturn(Set.of(elementCarrying(type, fixture))).when(round).getElementsAnnotatedWith(type);
        }
        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(round);
        return collector;
    }

    /** Binds the registry's wildcard so {@code getAnnotation} is stubbed with its own type. */
    private static <A extends Annotation> Element elementCarrying(
            Class<A> type, Function<Class<? extends Annotation>, TaggedElement> fixture) {
        String qualified = "com.example.pinned." + type.getSimpleName() + "Target";
        Element element = mock(Element.class);
        when(element.toString()).thenReturn(qualified);
        when(element.getKind()).thenReturn(ElementKind.CLASS);
        Name simple = mock(Name.class);
        when(simple.toString()).thenReturn(qualified.substring(qualified.lastIndexOf('.') + 1));
        when(element.getSimpleName()).thenReturn(simple);
        when(element.getAnnotation(type)).thenReturn(fixture.apply(type).annotation(type));
        return element;
    }
}
