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
 * PlatformRenderer for generating `.github/copilot-instructions.md`.
 */
public final class CopilotRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.COPILOT, SectionCatalog.Key.AUDIT, AnnotationCollector::audit, FormatterRegistry.audit()),
        section(Platform.COPILOT, SectionCatalog.Key.IGNORE, AnnotationCollector::ignore, FormatterRegistry.ignore()),
        section(Platform.COPILOT, SectionCatalog.Key.DRAFT, AnnotationCollector::draft, FormatterRegistry.draft()),
        section(Platform.COPILOT, SectionCatalog.Key.PRIVACY, AnnotationCollector::privacy, FormatterRegistry.privacy()),
        section(Platform.COPILOT, SectionCatalog.Key.CORE, AnnotationCollector::core, FormatterRegistry.core()),
        section(Platform.COPILOT, SectionCatalog.Key.PERFORMANCE, AnnotationCollector::performance, FormatterRegistry.performance()),
        section(Platform.COPILOT, SectionCatalog.Key.CONTRACT, AnnotationCollector::contract, FormatterRegistry.contract()),
        section(Platform.COPILOT, SectionCatalog.Key.TEST_DRIVEN, AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        section(Platform.COPILOT, SectionCatalog.Key.THREAD_SAFE, AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.COPILOT, SectionCatalog.Key.IMMUTABLE, AnnotationCollector::immutable, FormatterRegistry.immutable()),
        section(Platform.COPILOT, SectionCatalog.Key.DEPRECATED, AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        section(Platform.COPILOT, SectionCatalog.Key.OBSERVABILITY, AnnotationCollector::observability, FormatterRegistry.observability()),
        section(Platform.COPILOT, SectionCatalog.Key.REGULATION, AnnotationCollector::regulation, FormatterRegistry.regulation()),
        section(Platform.COPILOT, SectionCatalog.Key.PARALLEL_TESTS, AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.COPILOT, SectionCatalog.Key.LEGACY_BRIDGE, AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.COPILOT, SectionCatalog.Key.ARCHITECTURE, AnnotationCollector::architecture, FormatterRegistry.architecture()),
        section(Platform.COPILOT, SectionCatalog.Key.PUBLIC_API, AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        section(Platform.COPILOT, SectionCatalog.Key.STRICT_EXCEPTIONS, AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.COPILOT, SectionCatalog.Key.STRICT_TYPES, AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.COPILOT, SectionCatalog.Key.INTERNATIONALIZED, AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        section(Platform.COPILOT, SectionCatalog.Key.STRICT_CLASSPATH, AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.COPILOT, SectionCatalog.Key.SCHEMA_SAFE, AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.COPILOT, SectionCatalog.Key.IDEMPOTENT, AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        section(Platform.COPILOT, SectionCatalog.Key.FEATURE_FLAG, AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.COPILOT, SectionCatalog.Key.SECURE, AnnotationCollector::secure, FormatterRegistry.secure()),
        section(Platform.COPILOT, SectionCatalog.Key.CALLERS_ONLY, AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.COPILOT, SectionCatalog.Key.SANDBOX_ONLY, AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.COPILOT, SectionCatalog.Key.MEMORY_BUDGET, AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.COPILOT, SectionCatalog.Key.PURE, AnnotationCollector::pure, FormatterRegistry.pure()),
        section(Platform.COPILOT, SectionCatalog.Key.DOMAIN_MODEL, AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        section(Platform.COPILOT, SectionCatalog.Key.EXTENSIBLE, AnnotationCollector::extensible, FormatterRegistry.extensible()),
        section(Platform.COPILOT, SectionCatalog.Key.INPUT_SANITIZED, AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.COPILOT, SectionCatalog.Key.SECURE_LOGGING, AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.COPILOT, SectionCatalog.Key.EXPLAIN, AnnotationCollector::explain, FormatterRegistry.explain()),
        section(Platform.COPILOT, SectionCatalog.Key.PROTOTYPE, AnnotationCollector::prototype, FormatterRegistry.prototype()),
        section(Platform.COPILOT, SectionCatalog.Key.SUNSET, AnnotationCollector::sunset, FormatterRegistry.sunset()),
        section(Platform.COPILOT, SectionCatalog.Key.TEMPORARY, AnnotationCollector::temporary, FormatterRegistry.temporary())
    );

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# GitHub Copilot Instructions\n")
          .append(context.getGeneratedHeader())
          .append("# AUTO-GENERATED BY VIBETAGS. Do not edit manually.\n\n## Locked Files — DO NOT MODIFY\nDo not suggest changes to the following files:\n\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.COPILOT);
        }

        if (GranularIndexSection.indexActive(platform, context)) {
            // Granular sibling opted in: keep the always-loaded safety buckets inline and index the
            // rest to the scoped .github/instructions files (context detail moves there too).
            AnnotationSections.renderInlineSafetySections(sb, collector, Platform.COPILOT);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
        } else {
            sb.append("\n## Contextual Guidelines\n");
            for (Element e : collector.context()) {
                FormatterRegistry.context().format(e, sb, Platform.COPILOT);
            }

            AnnotationSections.render(sb, collector, Platform.COPILOT, SECTIONS);
        }

        return sb.toString();
    }
}
