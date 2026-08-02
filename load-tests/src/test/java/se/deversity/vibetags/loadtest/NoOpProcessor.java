package se.deversity.vibetags.loadtest;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * An annotation processor that does nothing at all, used as a control.
 *
 * <p>{@code MemoryVolumeStressTest} compares a VibeTags compile against one run with
 * {@code -proc:none}, and calls the difference "bytes attributable to the processor itself". It is
 * not. {@code -proc:none} switches off javac's whole annotation-processing subsystem: the extra
 * rounds, the {@code JavacProcessingEnvironment}, the retained element model, the round-environment
 * bookkeeping. All of that is charged to VibeTags in that subtraction, and none of it is code
 * VibeTags could make cheaper.
 *
 * <p>This processor claims every annotation, returns {@code false}, and allocates nothing of its
 * own, so a compile with it on measures exactly the price of running <em>an</em> annotation
 * processor. {@code ProcessorTaxStressTest} uses it to split the overhead into the part any
 * processor pays and the part VibeTags adds — the second being the only one worth optimizing.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class NoOpProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Deliberately empty. Returning false matches VibeTags, so other processors — and this
        // comparison — see the same annotations rather than a claimed-and-consumed set.
        return false;
    }
}
