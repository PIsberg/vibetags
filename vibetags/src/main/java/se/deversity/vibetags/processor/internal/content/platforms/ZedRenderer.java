package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.List;
import se.deversity.vibetags.processor.model.GuardrailModel;
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
        section(Platform.ZED, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.ZED, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.ZED, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.ZED, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.ZED, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.ZED, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.ZED, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.ZED, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.ZED, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.ZED, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.ZED, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.ZED, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.ZED, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.ZED, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.ZED, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.ZED, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.ZED, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.ZED, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.ZED, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.ZED, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.ZED, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.ZED, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.ZED, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.ZED, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.ZED, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure()),
        section(Platform.ZED, SectionCatalog.Key.CALLERS_ONLY, GuardrailModel::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.ZED, SectionCatalog.Key.SANDBOX_ONLY, GuardrailModel::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.ZED, SectionCatalog.Key.MEMORY_BUDGET, GuardrailModel::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.ZED, SectionCatalog.Key.PURE, GuardrailModel::pure, FormatterRegistry.pure()),
        section(Platform.ZED, SectionCatalog.Key.DOMAIN_MODEL, GuardrailModel::domainModel, FormatterRegistry.domainModel()),
        section(Platform.ZED, SectionCatalog.Key.EXTENSIBLE, GuardrailModel::extensible, FormatterRegistry.extensible()),
        section(Platform.ZED, SectionCatalog.Key.INPUT_SANITIZED, GuardrailModel::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.ZED, SectionCatalog.Key.SECURE_LOGGING, GuardrailModel::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.ZED, SectionCatalog.Key.EXPLAIN, GuardrailModel::explain, FormatterRegistry.explain()),
        section(Platform.ZED, SectionCatalog.Key.PROTOTYPE, GuardrailModel::prototype, FormatterRegistry.prototype()),
        section(Platform.ZED, SectionCatalog.Key.SUNSET, GuardrailModel::sunset, FormatterRegistry.sunset()),
        section(Platform.ZED, SectionCatalog.Key.TEMPORARY, GuardrailModel::temporary, FormatterRegistry.temporary()),
        section(Platform.ZED, SectionCatalog.Key.GENERATED, GuardrailModel::generated, FormatterRegistry.generated()),
        section(Platform.ZED, SectionCatalog.Key.LOAD_BEARING, GuardrailModel::loadBearing, FormatterRegistry.loadBearing()),
        section(Platform.ZED, SectionCatalog.Key.BANNED_API, GuardrailModel::bannedApi, FormatterRegistry.bannedApi()),
        section(Platform.ZED, SectionCatalog.Key.THREAD_AFFINITY, GuardrailModel::threadAffinity, FormatterRegistry.threadAffinity()),
        section(Platform.ZED, SectionCatalog.Key.KEEP_IN_SYNC, GuardrailModel::keepInSync, FormatterRegistry.keepInSync())
    );

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(context.getGeneratedHeader())
          .append("# Do not edit manually.\n\n## Locked Files (Do Not Modify)\n");

        for (TaggedElement e : model.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.ZED);
        }

        sb.append("\n## Context Guidelines\n");
        for (TaggedElement e : model.context()) {
            FormatterRegistry.context().format(e, sb, Platform.ZED);
        }

        AnnotationSections.render(sb, model, Platform.ZED, SECTIONS);

        return sb.toString();
    }
}
