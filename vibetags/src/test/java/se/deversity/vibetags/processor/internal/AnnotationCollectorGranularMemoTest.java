package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-element granular bodies are rendered once per collected state, not once per content build.
 *
 * <p>{@code renderGranular} depends only on the model and is the heaviest per-element render there
 * is: 44 bucket loops plus a split and a regex match per line. Every
 * {@code GuardrailContentBuilder.build()} recomputed it, and a build happens far more than once per
 * compilation — the root build, each module's own build, every accepting mirror target and each
 * safety digest. Measured on the three-module example before this memo: 12 calls for one
 * {@code mvn verify}, and 3 for this repository's own two-round self-annotate. After: 6 and 2, one
 * per collected state rather than one per build.
 *
 * <p>What this test pins is the memo's contract, which is what a regression would break: the same
 * map for the same state, a fresh one once the state changes, and no way for one of the sharers to
 * change what the others see.
 */
class AnnotationCollectorGranularMemoTest {

    private static Element namedElement(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        Name simpleName = mock(Name.class);
        when(simpleName.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(simpleName);
        return e;
    }

    /** Collects one {@code @AILocked} element, the cheapest annotation that renders a granular body. */
    private static void collectLocked(AnnotationCollector collector, String fqn, String reason) {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        Element element = namedElement(fqn);
        AILocked locked = mock(AILocked.class);
        when(locked.reason()).thenReturn(reason);
        when(element.getAnnotation(AILocked.class)).thenReturn(locked);
        doReturn(Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        assertTrue(collector.collect(roundEnv), "the mocked round should have produced an element");
    }

    @Test
    @DisplayName("the same collected state renders its granular bodies once")
    void sameStateIsRenderedOnce() {
        AnnotationCollector collector = new AnnotationCollector();
        collectLocked(collector, "com.example.Ledger", "settlement contract");

        Map<TaggedElement, GranularBody> first = collector.granularRules();
        assertSame(first, collector.granularRules(),
            "a second content build must reuse the first build's render, not repeat it");
        assertSame(first, collector.granularRules(), "and a third");
    }

    @Test
    @DisplayName("collecting more elements renders again")
    void collectingInvalidatesTheMemo() {
        AnnotationCollector collector = new AnnotationCollector();
        collectLocked(collector, "com.example.Ledger", "settlement contract");
        Map<TaggedElement, GranularBody> before = collector.granularRules();

        collectLocked(collector, "com.example.Journal", "append only");
        Map<TaggedElement, GranularBody> after = collector.granularRules();

        assertNotSame(before, after, "a round that added an element must not reuse the old render");
        assertEquals(2, after.size(), "both elements must be in the new render");
    }

    @Test
    @DisplayName("reset clears it, so the next round starts from nothing")
    void resetInvalidatesTheMemo() {
        AnnotationCollector collector = new AnnotationCollector();
        collectLocked(collector, "com.example.Ledger", "settlement contract");
        assertEquals(1, collector.granularRules().size());

        collector.reset();

        assertTrue(collector.granularRules().isEmpty(),
            "after reset the collector holds nothing, so it can render nothing");
    }

    @Test
    @DisplayName("what it hands out matches a direct render of the same model")
    void memoAgreesWithADirectRender() {
        AnnotationCollector collector = new AnnotationCollector();
        collectLocked(collector, "com.example.Ledger", "settlement contract");

        assertEquals(
            PlatformRendererRegistry.granularRenderer().renderGranular(collector.model()).toString(),
            collector.granularRules().toString(),
            "memoising must not change what is rendered, only how often");
    }

    @Test
    @DisplayName("a sharer cannot change what the other sharers see")
    void theSharedMapIsUnmodifiable() {
        AnnotationCollector collector = new AnnotationCollector();
        collectLocked(collector, "com.example.Ledger", "settlement contract");
        Map<TaggedElement, GranularBody> rules = collector.granularRules();

        // One map now reaches the root build, every module build and each mirror target. Sharing is
        // only safe while none of them can write to it.
        assertThrows(UnsupportedOperationException.class, rules::clear);
    }
}
