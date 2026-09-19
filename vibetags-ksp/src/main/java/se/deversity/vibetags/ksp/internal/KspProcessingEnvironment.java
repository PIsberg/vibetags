package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.SourcePositionResolver;
import se.deversity.vibetags.processor.model.SourceLocation;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Locale;
import java.util.Map;

/**
 * The {@link ProcessingEnvironment} the processor is initialised with under KSP. It is not javac's,
 * so {@code Trees.instance} rejects it; the processor already runs without the Tree API (ECJ and
 * Gradle's incremental wrapper take the same path), losing only source positions in
 * {@code .vibetags-locks} and the local-declaration warning.
 *
 * <p>It gives the positions back as a {@link SourcePositionResolver.Source}: each element carries
 * the line range of the Kotlin declaration it stands for, so {@code .vibetags-locks} under KSP
 * points at the {@code .kt} file (under kapt it pointed into the generated stub).
 */
final class KspProcessingEnvironment implements ProcessingEnvironment, SourcePositionResolver.Source {

    private final Map<String, String> options;
    private final Messager messager;
    private final Filer filer;
    private final Types types = new KspTypes();
    private Elements elements;

    KspProcessingEnvironment(Map<String, String> options, Messager messager, Filer filer, Elements elements) {
        this.options = Map.copyOf(options);
        this.messager = messager;
        this.filer = filer;
        this.elements = elements;
    }

    /** Points element lookups at a later round's model. */
    void setElements(Elements elements) {
        this.elements = elements;
    }

    @Override
    public Map<String, String> getOptions() {
        return options;
    }

    @Override
    public Messager getMessager() {
        return messager;
    }

    @Override
    public Filer getFiler() {
        return filer;
    }

    @Override
    public Elements getElementUtils() {
        return elements;
    }

    @Override
    public Types getTypeUtils() {
        return types;
    }

    @Override
    public SourceVersion getSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    @Override
    public @Nullable SourceLocation locate(Element element) {
        return element instanceof KElement known ? known.location() : null;
    }
}
