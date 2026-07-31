package se.deversity.vibetags.processor;

import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;
import java.util.Set;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Turns a mocked {@link Element} into the {@link TaggedElement} the formatters and renderers now
 * take.
 *
 * <p>Deliberately routed through a real {@link AnnotationCollector} rather than assembling a
 * {@code TaggedElement} by hand: the snapshot rules (which name forms are derived, how the kind
 * maps, how {@code AISunset.replacement} is resolved) then live in exactly one place. A hand-built
 * fixture would be a second implementation that drifts, and the tests would keep passing while the
 * real one broke.
 */
public final class TaggedElements {

    private TaggedElements() {}

    /**
     * Snapshots {@code element}, carrying whichever {@code @AI...} annotations it actually answers
     * {@code getAnnotation} for. Every bucket is stubbed to contain the element, so the resulting
     * snapshot is the same one a real compilation round would produce — including for an element
     * carrying no annotation at all, which is the case the formatter null-guards exist for.
     */
    public static TaggedElement tagged(Element element) {
        RoundEnvironment round = mock(RoundEnvironment.class);
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            doReturn(Set.of(element)).when(round).getElementsAnnotatedWith(type);
        }
        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(round);
        GuardrailModel model = collector.model();
        return model.locked().iterator().next();
    }
}
