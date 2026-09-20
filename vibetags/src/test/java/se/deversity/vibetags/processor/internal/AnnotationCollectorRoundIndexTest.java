package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The round index describes the round that just ran, and nothing before it.
 *
 * <p>Three callers ask javac the same per-type question every round — the collector, then every
 * validation rule through {@code ValidationContext.elementsWith}, then the locks-report path for
 * {@code @AILocked} — and each query walks every root element. Measured on examples/multimodule:
 * 103 collect scans, 78 validation scans and 4 locks scans per build, so 82 of the 185 were
 * repeats. The index is what lets the second and third caller reuse the first's answer.
 *
 * <p>What makes it dangerous is the difference this test exists to pin. The collector's buckets
 * <em>accumulate</em> across rounds, because the generated files describe the whole compilation;
 * validation <em>reports</em> per round. Hand the validator the accumulated buckets and every
 * warning from round one is emitted again in round two, and again in round three — duplicated
 * diagnostics in a consumer's build, from a change that was supposed to be invisible.
 */
class AnnotationCollectorRoundIndexTest {

    private static Element named(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        Name simple = mock(Name.class);
        when(simple.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(simple);
        return e;
    }

    /** One round offering exactly {@code elements} for {@code @AILocked}. */
    private static RoundEnvironment roundWith(Element... elements) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        AILocked locked = mock(AILocked.class);
        when(locked.reason()).thenReturn("pinned");
        for (Element e : elements) {
            when(e.getAnnotation(AILocked.class)).thenReturn(locked);
        }
        doReturn(Set.of(elements)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        return roundEnv;
    }

    @Test
    @DisplayName("a type gone from the next round leaves no stale entry behind")
    void aTypeAbsentInTheNextRoundIsNotStillReported() {
        // The failure mode, precisely. A type queried in round one and *not* queried in round two
        // is the only way a stale entry survives: where both rounds query it, the second simply
        // overwrites the first and the bug hides. Round two here reports a different annotation as
        // present, so @AILocked is skipped, and an index that is not reset still hands the
        // validator round one's element — every @AILocked warning repeated, once per later round.
        AnnotationCollector collector = new AnnotationCollector();
        Element first = named("com.example.First");

        collector.collect(roundWith(first), Set.of(AILocked.class.getName()));
        assertEquals(Set.of(first), collector.elementsThisRound(AILocked.class),
            "round one asked about @AILocked and found it");

        RoundEnvironment later = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(later).getElementsAnnotatedWith(AIContext.class);
        collector.collect(later, Set.of(AIContext.class.getName()));

        assertNull(collector.elementsThisRound(AILocked.class),
            "round two never asked about @AILocked, so the index must not answer for it");
        assertEquals(Set.of(first), collector.elementsOf(AILocked.class),
            "the buckets still accumulate — the generated files describe the whole compilation");
    }

    @Test
    @DisplayName("the index holds this round's elements, while the buckets accumulate")
    void theIndexIsThisRoundOnly() {
        AnnotationCollector collector = new AnnotationCollector();
        Element first = named("com.example.First");
        Element second = named("com.example.Second");

        collector.collect(roundWith(first));
        assertEquals(Set.of(first), collector.elementsThisRound(AILocked.class),
            "round one's index is round one's elements");

        collector.collect(roundWith(second));

        assertEquals(Set.of(second), collector.elementsThisRound(AILocked.class),
            "round two's index must not carry round one's element, or every rule reports it twice");
        assertEquals(Set.of(first, second), collector.elementsOf(AILocked.class),
            "the buckets still accumulate — the generated files describe the whole compilation");
    }

    @Test
    @DisplayName("a type this round was never asked about answers null, not empty")
    void unaskedTypesAreNullNotEmpty() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);

        // Only AILocked is reported present, so no other type is queried this round.
        collector.collect(roundEnv, Set.of(AILocked.class.getName()));

        assertEquals(Set.of(), collector.elementsThisRound(AILocked.class),
            "asked and found nothing is an answer a caller may reuse");
        assertNull(collector.elementsThisRound(AIContext.class),
            "never asked is not the same as found nothing, and must not be reused as one");
    }

    @Test
    @DisplayName("reset drops the index with the buckets")
    void resetClearsTheIndex() {
        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(roundWith(named("com.example.First")));
        assertTrue(collector.elementsThisRound(AILocked.class) != null);

        collector.reset();

        assertNull(collector.elementsThisRound(AILocked.class),
            "the round that filled it is over; a later caller must not read it as its own");
    }

    @Test
    @DisplayName("the index handed to the validator cannot be written by it")
    void theIndexIsUnmodifiable() {
        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(roundWith(named("com.example.First")));
        Map<Class<? extends Annotation>, Set<? extends Element>> index = collector.roundIndex();

        assertThrows(UnsupportedOperationException.class, index::clear);
    }
}
