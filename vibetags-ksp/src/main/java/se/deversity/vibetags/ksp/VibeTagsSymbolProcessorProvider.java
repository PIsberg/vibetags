package se.deversity.vibetags.ksp;

import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import se.deversity.vibetags.ksp.internal.KspGuardrailProcessor;

/**
 * KSP's entry point for VibeTags, registered in
 * {@code META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider}.
 *
 * <p>Every option is the one the javac processor takes, passed with {@code ksp { arg(...) }} in
 * place of {@code -A}: {@code vibetags.root} (required under KSP, whose working directory is the
 * Gradle daemon's), {@code vibetags.check}, {@code vibetags.enforce} and the rest.
 */
public final class VibeTagsSymbolProcessorProvider implements SymbolProcessorProvider {

    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new KspGuardrailProcessor(environment);
    }
}
