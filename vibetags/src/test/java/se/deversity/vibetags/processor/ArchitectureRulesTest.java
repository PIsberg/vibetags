package se.deversity.vibetags.processor;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Architecture fitness functions for the VibeTags annotation processor.
 *
 * <p>These tests encode structural invariants that the project relies on but that
 * were previously upheld only by convention:
 *
 * <ul>
 *   <li>Every formatter in {@code content.annotations} implements {@link AnnotationFormatter}
 *       and is {@code final} — the {@link se.deversity.vibetags.processor.internal.content.FormatterRegistry}
 *       returns them as stateless singletons.</li>
 *   <li>Every renderer in {@code content.platforms} implements {@link PlatformRenderer}
 *       and is {@code final} — same singleton contract via
 *       {@link se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry}.</li>
 *   <li>Neither formatters nor renderers carry instance-level state — mutable fields would
 *       cause data races under the {@code ForkJoinPool} parallel writes added in v0.9.7.</li>
 *   <li>The processor package hierarchy is cycle-free, guarding the layered design as the
 *       codebase grows.</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "se.deversity.vibetags.processor",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureRulesTest {

    // -----------------------------------------------------------------------
    // Shared conditions — declared first so field-initialisation order is safe
    // -----------------------------------------------------------------------

    /**
     * Fails any class that has at least one non-static (instance) field.
     * Formatter and renderer classes are stateless singletons; instance state
     * would break thread-safety under parallel file writes.
     */
    private static final ArchCondition<JavaClass> HAVE_NO_INSTANCE_FIELDS =
            new ArchCondition<JavaClass>("have no non-static (instance) fields") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaField field : javaClass.getFields()) {
                        if (!field.getModifiers().contains(JavaModifier.STATIC)) {
                            events.add(SimpleConditionEvent.violated(javaClass,
                                    String.format("%s has non-static field '%s' — formatters/renderers must be stateless",
                                            javaClass.getSimpleName(), field.getName())));
                        }
                    }
                }
            };

    // -----------------------------------------------------------------------
    // Formatter rules (content.annotations package)
    // -----------------------------------------------------------------------

    /**
     * Every public class in {@code content.annotations} must implement {@link AnnotationFormatter}.
     * This prevents accidentally placing a non-formatter helper class in the formatters package,
     * which would silently be ignored by {@code FormatterRegistry}.
     */
    @ArchTest
    static final ArchRule FORMATTERS_IMPLEMENT_ANNOTATION_FORMATTER =
            classes()
                    .that().resideInAPackage("..content.annotations..")
                    .and().arePublic()
                    .should().implement(AnnotationFormatter.class)
                    .as("Every public class in content.annotations must implement AnnotationFormatter");

    /**
     * Formatter classes are stateless singletons obtained from {@code FormatterRegistry}.
     * Subclassing could allow callers to inject state and break the singleton contract.
     */
    @ArchTest
    static final ArchRule FORMATTERS_ARE_FINAL =
            classes()
                    .that().resideInAPackage("..content.annotations..")
                    .and().arePublic()
                    .should().haveModifier(JavaModifier.FINAL)
                    .as("All formatter classes must be final — they are stateless singletons");

    /**
     * Instance fields on formatters would cause data races under the parallel
     * {@code ForkJoinPool} file writes introduced in v0.9.7.
     */
    @ArchTest
    static final ArchRule FORMATTERS_HAVE_NO_INSTANCE_FIELDS =
            classes()
                    .that().resideInAPackage("..content.annotations..")
                    .and().arePublic()
                    .should(HAVE_NO_INSTANCE_FIELDS)
                    .as("Formatter classes must have no non-static (instance) fields");

    // -----------------------------------------------------------------------
    // Renderer rules (content.platforms package)
    // -----------------------------------------------------------------------

    /**
     * Every public class in {@code content.platforms} must implement {@link PlatformRenderer}.
     * This prevents non-renderer utilities from accumulating in the renderers package
     * and being accidentally registered with {@code PlatformRendererRegistry}.
     */
    @ArchTest
    static final ArchRule RENDERERS_IMPLEMENT_PLATFORM_RENDERER =
            classes()
                    .that().resideInAPackage("..content.platforms..")
                    .and().arePublic()
                    .should().implement(PlatformRenderer.class)
                    .as("Every public class in content.platforms must implement PlatformRenderer");

    /**
     * Renderer classes are stateless; marking them {@code final} prevents
     * subclasses from overriding behaviour registered in {@code PlatformRendererRegistry}.
     */
    @ArchTest
    static final ArchRule RENDERERS_ARE_FINAL =
            classes()
                    .that().resideInAPackage("..content.platforms..")
                    .and().arePublic()
                    .should().haveModifier(JavaModifier.FINAL)
                    .as("All renderer classes must be final — they are stateless singletons");

    /**
     * Same thread-safety rationale as {@link #FORMATTERS_HAVE_NO_INSTANCE_FIELDS}.
     */
    @ArchTest
    static final ArchRule RENDERERS_HAVE_NO_INSTANCE_FIELDS =
            classes()
                    .that().resideInAPackage("..content.platforms..")
                    .and().arePublic()
                    .should(HAVE_NO_INSTANCE_FIELDS)
                    .as("Renderer classes must have no non-static (instance) fields");

    // -----------------------------------------------------------------------
    // Package-level rules
    // -----------------------------------------------------------------------

    /**
     * The {@code content.annotations} and {@code content.platforms} sub-packages must not
     * form circular dependencies with each other. Formatters are pure output helpers and
     * renderers orchestrate them — the dependency must be one-way.
     *
     * <p>Note: the broader {@code internal} ↔ {@code internal.content} dependency is
     * intentional by design: {@code PlatformRenderer.render()} takes {@link
     * se.deversity.vibetags.processor.internal.AnnotationCollector} as a parameter, and
     * {@code GuardrailContentBuilder} (in {@code internal}) drives the content layer.
     * That known cross-cutting dependency is excluded here; only the leaf sub-packages
     * are checked for cycles.
     */
    @ArchTest
    static final ArchRule NO_CYCLES_IN_CONTENT_SUBPACKAGES =
            slices()
                    .matching("se.deversity.vibetags.processor.internal.content.(**)")
                    .should().beFreeOfCycles()
                    .as("content.annotations and content.platforms must be free of circular package dependencies");
}
