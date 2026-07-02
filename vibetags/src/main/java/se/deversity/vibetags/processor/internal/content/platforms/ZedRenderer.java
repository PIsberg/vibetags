package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.section;

/**
 * PlatformRenderer for generating `.rules` for Zed.
 */
public final class ZedRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.ZED, SectionCatalog.Key.THREAD_SAFE, AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.ZED, SectionCatalog.Key.IMMUTABLE, AnnotationCollector::immutable, FormatterRegistry.immutable()),
        section(Platform.ZED, SectionCatalog.Key.DEPRECATED, AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        section(Platform.ZED, SectionCatalog.Key.OBSERVABILITY, AnnotationCollector::observability, FormatterRegistry.observability()),
        section(Platform.ZED, SectionCatalog.Key.REGULATION, AnnotationCollector::regulation, FormatterRegistry.regulation()),
        section(Platform.ZED, SectionCatalog.Key.AUDIT, AnnotationCollector::audit, FormatterRegistry.audit()),
        section(Platform.ZED, SectionCatalog.Key.IGNORE, AnnotationCollector::ignore, FormatterRegistry.ignore()),
        section(Platform.ZED, SectionCatalog.Key.DRAFT, AnnotationCollector::draft, FormatterRegistry.draft()),
        section(Platform.ZED, SectionCatalog.Key.PRIVACY, AnnotationCollector::privacy, FormatterRegistry.privacy()),
        section(Platform.ZED, SectionCatalog.Key.CORE, AnnotationCollector::core, FormatterRegistry.core()),
        section(Platform.ZED, SectionCatalog.Key.PERFORMANCE, AnnotationCollector::performance, FormatterRegistry.performance()),
        section(Platform.ZED, SectionCatalog.Key.CONTRACT, AnnotationCollector::contract, FormatterRegistry.contract()),
        section(Platform.ZED, SectionCatalog.Key.TEST_DRIVEN, AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        section(Platform.ZED, SectionCatalog.Key.PARALLEL_TESTS, AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.ZED, SectionCatalog.Key.LEGACY_BRIDGE, AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.ZED, SectionCatalog.Key.ARCHITECTURE, AnnotationCollector::architecture, FormatterRegistry.architecture()),
        section(Platform.ZED, SectionCatalog.Key.PUBLIC_API, AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        section(Platform.ZED, SectionCatalog.Key.STRICT_EXCEPTIONS, AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.ZED, SectionCatalog.Key.STRICT_TYPES, AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.ZED, SectionCatalog.Key.INTERNATIONALIZED, AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        section(Platform.ZED, SectionCatalog.Key.STRICT_CLASSPATH, AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.ZED, SectionCatalog.Key.SCHEMA_SAFE, AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.ZED, SectionCatalog.Key.IDEMPOTENT, AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        section(Platform.ZED, SectionCatalog.Key.FEATURE_FLAG, AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.ZED, SectionCatalog.Key.SECURE, AnnotationCollector::secure, FormatterRegistry.secure()),
        section(Platform.ZED, SectionCatalog.Key.CALLERS_ONLY, AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.ZED, SectionCatalog.Key.SANDBOX_ONLY, AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.ZED, SectionCatalog.Key.MEMORY_BUDGET, AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.ZED, SectionCatalog.Key.PURE, AnnotationCollector::pure, FormatterRegistry.pure()),
        section(Platform.ZED, SectionCatalog.Key.DOMAIN_MODEL, AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        section(Platform.ZED, SectionCatalog.Key.EXTENSIBLE, AnnotationCollector::extensible, FormatterRegistry.extensible()),
        section(Platform.ZED, SectionCatalog.Key.INPUT_SANITIZED, AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.ZED, SectionCatalog.Key.SECURE_LOGGING, AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.ZED, SectionCatalog.Key.EXPLAIN, AnnotationCollector::explain, FormatterRegistry.explain()),
        section(Platform.ZED, SectionCatalog.Key.PROTOTYPE, AnnotationCollector::prototype, FormatterRegistry.prototype()),
        section(Platform.ZED, SectionCatalog.Key.SUNSET, AnnotationCollector::sunset, FormatterRegistry.sunset()),
        section(Platform.ZED, SectionCatalog.Key.TEMPORARY, AnnotationCollector::temporary, FormatterRegistry.temporary())
    );

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(context.getGeneratedHeader())
          .append("# Do not edit manually.\n\n## Locked Files (Do Not Modify)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.ZED);
        }

        sb.append("\n## Context Guidelines\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.ZED);
        }

        AnnotationSections.render(sb, collector, Platform.ZED, SECTIONS);

        return sb.toString();
    }
}
