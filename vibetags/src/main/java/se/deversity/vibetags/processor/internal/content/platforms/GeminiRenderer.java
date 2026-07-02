package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.section;

/**
 * PlatformRenderer for generating `GEMINI.md` and `gemini_instructions.md`.
 */
public final class GeminiRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.GEMINI, SectionCatalog.Key.AUDIT, AnnotationCollector::audit, FormatterRegistry.audit()),
        section(Platform.GEMINI, SectionCatalog.Key.IGNORE, AnnotationCollector::ignore, FormatterRegistry.ignore()),
        section(Platform.GEMINI, SectionCatalog.Key.DRAFT, AnnotationCollector::draft, FormatterRegistry.draft()),
        section(Platform.GEMINI, SectionCatalog.Key.PRIVACY, AnnotationCollector::privacy, FormatterRegistry.privacy()),
        section(Platform.GEMINI, SectionCatalog.Key.CORE, AnnotationCollector::core, FormatterRegistry.core()),
        section(Platform.GEMINI, SectionCatalog.Key.PERFORMANCE, AnnotationCollector::performance, FormatterRegistry.performance()),
        section(Platform.GEMINI, SectionCatalog.Key.CONTRACT, AnnotationCollector::contract, FormatterRegistry.contract()),
        section(Platform.GEMINI, SectionCatalog.Key.TEST_DRIVEN, AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        section(Platform.GEMINI, SectionCatalog.Key.THREAD_SAFE, AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.GEMINI, SectionCatalog.Key.IMMUTABLE, AnnotationCollector::immutable, FormatterRegistry.immutable()),
        section(Platform.GEMINI, SectionCatalog.Key.DEPRECATED, AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        section(Platform.GEMINI, SectionCatalog.Key.OBSERVABILITY, AnnotationCollector::observability, FormatterRegistry.observability()),
        section(Platform.GEMINI, SectionCatalog.Key.REGULATION, AnnotationCollector::regulation, FormatterRegistry.regulation()),
        section(Platform.GEMINI, SectionCatalog.Key.PARALLEL_TESTS, AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.GEMINI, SectionCatalog.Key.LEGACY_BRIDGE, AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.GEMINI, SectionCatalog.Key.ARCHITECTURE, AnnotationCollector::architecture, FormatterRegistry.architecture()),
        section(Platform.GEMINI, SectionCatalog.Key.PUBLIC_API, AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        section(Platform.GEMINI, SectionCatalog.Key.STRICT_EXCEPTIONS, AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.GEMINI, SectionCatalog.Key.STRICT_TYPES, AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.GEMINI, SectionCatalog.Key.INTERNATIONALIZED, AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        section(Platform.GEMINI, SectionCatalog.Key.STRICT_CLASSPATH, AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.GEMINI, SectionCatalog.Key.SCHEMA_SAFE, AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.GEMINI, SectionCatalog.Key.IDEMPOTENT, AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        section(Platform.GEMINI, SectionCatalog.Key.FEATURE_FLAG, AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.GEMINI, SectionCatalog.Key.SECURE, AnnotationCollector::secure, FormatterRegistry.secure()),
        section(Platform.GEMINI, SectionCatalog.Key.CALLERS_ONLY, AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.GEMINI, SectionCatalog.Key.SANDBOX_ONLY, AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.GEMINI, SectionCatalog.Key.MEMORY_BUDGET, AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.GEMINI, SectionCatalog.Key.PURE, AnnotationCollector::pure, FormatterRegistry.pure()),
        section(Platform.GEMINI, SectionCatalog.Key.DOMAIN_MODEL, AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        section(Platform.GEMINI, SectionCatalog.Key.EXTENSIBLE, AnnotationCollector::extensible, FormatterRegistry.extensible()),
        section(Platform.GEMINI, SectionCatalog.Key.INPUT_SANITIZED, AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.GEMINI, SectionCatalog.Key.SECURE_LOGGING, AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.GEMINI, SectionCatalog.Key.EXPLAIN, AnnotationCollector::explain, FormatterRegistry.explain()),
        section(Platform.GEMINI, SectionCatalog.Key.PROTOTYPE, AnnotationCollector::prototype, FormatterRegistry.prototype()),
        section(Platform.GEMINI, SectionCatalog.Key.SUNSET, AnnotationCollector::sunset, FormatterRegistry.sunset()),
        section(Platform.GEMINI, SectionCatalog.Key.TEMPORARY, AnnotationCollector::temporary, FormatterRegistry.temporary())
    );

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# GEMINI AI INSTRUCTIONS\n").append(context.getGeneratedHeader()).append("\n");

        if (!collector.locked().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.locked()) FormatterRegistry.locked().format(e, sec, platform);
            sb.append("\n## LOCKED FILES (DO NOT MODIFY)\nDo not suggest modifications to the following files:\n\n").append(sec);
        }

        if (!collector.context().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (Element e : collector.context()) FormatterRegistry.context().format(e, sec, platform);
            sb.append("\n## CONTEXTUAL RULES\nApply the following context when assisting with these files:\n\n").append(sec);
        }

        AnnotationSections.render(sb, collector, platform, SECTIONS);

        return sb.toString();
    }
}
