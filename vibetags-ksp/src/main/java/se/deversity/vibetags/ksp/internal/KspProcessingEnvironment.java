package se.deversity.vibetags.ksp.internal;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Locale;
import java.util.Map;

/**
 * The {@link ProcessingEnvironment} the processor is initialised with under KSP. It is not javac's,
 * so {@code Trees.instance} rejects it; the processor already runs without the Tree API (ECJ and
 * Gradle's incremental wrapper take the same path), losing only source positions in
 * {@code .vibetags-locks} and the local-declaration warning.
 */
final class KspProcessingEnvironment implements ProcessingEnvironment {

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
}
