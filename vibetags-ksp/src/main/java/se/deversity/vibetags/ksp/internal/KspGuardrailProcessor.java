package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.processing.JvmPlatformInfo;
import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.processing.PlatformInfo;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSFile;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.AIGuardrailProcessor;

import javax.annotation.processing.Processor;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs {@link AIGuardrailProcessor} under KSP by playing javac's part in the JSR 269 lifecycle:
 * {@code init} once, {@code process} once per KSP round with that round's stub model, and a final
 * {@code processingOver} round from {@link #finish} (or from {@link #onError}, flagged as an error
 * round, so the processor leaves every file untouched as it does after a failed javac compile).
 *
 * <p>The first round is built from {@link Resolver#getAllFiles()}, not from the files KSP reports
 * as changed. An incremental KSP run hands a processor only its dirty files, and a round shown part
 * of a module would read everything else as deleted and rewrite the module's guardrails without it.
 * Later rounds see only the files earlier processors generated in the round before.
 */
public final class KspGuardrailProcessor implements SymbolProcessor {

    private static final String ROOT_OPTION = "vibetags.root";
    private static final String ASSOCIATION_PACKAGE = "se.deversity.vibetags.ksp";
    private static final String ASSOCIATION_NAME = "all-sources";

    private final SymbolProcessorEnvironment environment;
    private final Processor delegate;
    private final AnnotationReader annotations = new AnnotationReader(KspGuardrailProcessor.class.getClassLoader());
    private final List<KSFile> sources = new ArrayList<>();
    private @Nullable KspProcessingEnvironment processingEnvironment;
    private boolean over;

    public KspGuardrailProcessor(SymbolProcessorEnvironment environment) {
        this(environment, new AIGuardrailProcessor());
    }

    /** Test seam: drives {@code delegate} in place of a fresh {@link AIGuardrailProcessor}. */
    KspGuardrailProcessor(SymbolProcessorEnvironment environment, Processor delegate) {
        this.environment = environment;
        this.delegate = delegate;
    }

    @Override
    public List<KSAnnotated> process(Resolver resolver) {
        KSPLogger logger = environment.getLogger();
        try {
            boolean first = processingEnvironment == null;
            List<KSFile> files = list(first ? resolver.getAllFiles().iterator() : resolver.getNewFiles().iterator());
            sources.addAll(files);
            if (first) {
                claimEveryInput(files);
            }
            StubModel model = new StubBuilder(resolver, annotations, defaultImpls()).build(files);
            for (String lost : model.dropped()) {
                logger.warn("VibeTags: " + lost, null);
            }
            KspElements elements = new KspElements(model);
            KspProcessingEnvironment env = processingEnvironment;
            if (env == null) {
                if (!environment.getOptions().containsKey(ROOT_OPTION)) {
                    logger.warn("VibeTags: " + ROOT_OPTION + " is not set. KSP runs inside the Gradle daemon, "
                        + "whose working directory is not the project; pass ksp { arg(\"" + ROOT_OPTION
                        + "\", projectDir.absolutePath) }.", null);
                }
                env = new KspProcessingEnvironment(environment.getOptions(), new KspMessager(logger),
                    new KspFiler(environment.getCodeGenerator(), sources), elements);
                processingEnvironment = env;
                delegate.init(env);
            } else {
                env.setElements(elements);
            }
            KspRoundEnvironment round = KspRoundEnvironment.of(model);
            delegate.process(presentTypes(round), round);
        } catch (RuntimeException | LinkageError failure) {
            // The processor downgrades its own failures to warnings; this catches the adapter's,
            // for the same reason: a guardrail generator must never fail the consumer's build.
            logger.warn("VibeTags: guardrail generation under KSP failed and was skipped (build not affected): "
                + failure, null);
        }
        return List.of();
    }

    @Override
    public void finish() {
        end(false);
    }

    @Override
    public void onError() {
        end(true);
    }

    private void end(boolean errorRaised) {
        if (processingEnvironment == null || over) {
            return;
        }
        over = true;
        try {
            delegate.process(Set.of(), KspRoundEnvironment.over(errorRaised));
        } catch (RuntimeException | LinkageError failure) {
            environment.getLogger().warn("VibeTags: guardrail generation under KSP failed and was skipped "
                + "(build not affected): " + failure, null);
        }
    }

    /**
     * Makes every source an input of one aggregating output, so that on an incremental run a change
     * to any file puts every file back in front of this processor.
     *
     * <p>Without it KSP hands an incremental run only the dirty files, from {@code getAllFiles()}
     * as much as from {@code getNewFiles()}, and the processor would regenerate the module's
     * guardrails from that fraction, dropping every untouched file's (the loss invariant 17 exists
     * to stop). {@code associate} records the dependency without writing a file, so nothing lands
     * in the consumer's jar.
     */
    private void claimEveryInput(List<KSFile> files) {
        try {
            environment.getCodeGenerator().associate(files, ASSOCIATION_PACKAGE, ASSOCIATION_NAME, "txt");
        } catch (RuntimeException unsupported) {
            environment.getLogger().warn("VibeTags: could not register with KSP's incremental processing ("
                + unsupported + "); an incremental build may regenerate guardrails from changed files only. "
                + "Run a clean build if a guardrail goes missing.", null);
        }
    }

    /**
     * Whether interfaces get {@code DefaultImpls}: every {@code -jvm-default} mode but
     * {@code no-compatibility} (and its pre-2.2 name, {@code all}) keeps them.
     */
    private boolean defaultImpls() {
        for (PlatformInfo platform : environment.getPlatforms()) {
            if (platform instanceof JvmPlatformInfo jvm) {
                String mode = jvm.getJvmDefaultMode();
                return !"no-compatibility".equals(mode) && !"all".equals(mode);
            }
        }
        return true;
    }

    /** The annotation types present this round, as javac hands them to {@code process}. */
    private static Set<TypeElement> presentTypes(KspRoundEnvironment round) {
        Set<TypeElement> present = new LinkedHashSet<>();
        for (String type : round.presentAnnotationTypes()) {
            present.add(KTypeElement.reference(type));
        }
        return present;
    }

    private static <T> List<T> list(Iterator<T> iterator) {
        List<T> items = new ArrayList<>();
        iterator.forEachRemaining(items::add);
        return items;
    }
}
