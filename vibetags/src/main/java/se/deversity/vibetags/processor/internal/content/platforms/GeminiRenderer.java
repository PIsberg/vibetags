package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.GuardrailModel;
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
        section(Platform.GEMINI, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.GEMINI, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.GEMINI, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.GEMINI, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.GEMINI, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.GEMINI, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.GEMINI, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.GEMINI, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.GEMINI, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.GEMINI, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.GEMINI, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.GEMINI, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.GEMINI, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.GEMINI, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.GEMINI, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.GEMINI, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.GEMINI, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.GEMINI, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.GEMINI, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.GEMINI, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.GEMINI, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.GEMINI, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.GEMINI, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.GEMINI, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.GEMINI, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure()),
        section(Platform.GEMINI, SectionCatalog.Key.CALLERS_ONLY, GuardrailModel::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.GEMINI, SectionCatalog.Key.SANDBOX_ONLY, GuardrailModel::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.GEMINI, SectionCatalog.Key.MEMORY_BUDGET, GuardrailModel::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.GEMINI, SectionCatalog.Key.PURE, GuardrailModel::pure, FormatterRegistry.pure()),
        section(Platform.GEMINI, SectionCatalog.Key.DOMAIN_MODEL, GuardrailModel::domainModel, FormatterRegistry.domainModel()),
        section(Platform.GEMINI, SectionCatalog.Key.EXTENSIBLE, GuardrailModel::extensible, FormatterRegistry.extensible()),
        section(Platform.GEMINI, SectionCatalog.Key.INPUT_SANITIZED, GuardrailModel::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.GEMINI, SectionCatalog.Key.SECURE_LOGGING, GuardrailModel::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.GEMINI, SectionCatalog.Key.EXPLAIN, GuardrailModel::explain, FormatterRegistry.explain()),
        section(Platform.GEMINI, SectionCatalog.Key.PROTOTYPE, GuardrailModel::prototype, FormatterRegistry.prototype()),
        section(Platform.GEMINI, SectionCatalog.Key.SUNSET, GuardrailModel::sunset, FormatterRegistry.sunset()),
        section(Platform.GEMINI, SectionCatalog.Key.TEMPORARY, GuardrailModel::temporary, FormatterRegistry.temporary()),
        section(Platform.GEMINI, SectionCatalog.Key.GENERATED, GuardrailModel::generated, FormatterRegistry.generated()),
        section(Platform.GEMINI, SectionCatalog.Key.LOAD_BEARING, GuardrailModel::loadBearing, FormatterRegistry.loadBearing()),
        section(Platform.GEMINI, SectionCatalog.Key.BANNED_API, GuardrailModel::bannedApi, FormatterRegistry.bannedApi()),
        section(Platform.GEMINI, SectionCatalog.Key.THREAD_AFFINITY, GuardrailModel::threadAffinity, FormatterRegistry.threadAffinity()),
        section(Platform.GEMINI, SectionCatalog.Key.KEEP_IN_SYNC, GuardrailModel::keepInSync, FormatterRegistry.keepInSync())
    );

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        if (GranularIndexSection.indexActive(platform, context)) {
            // Both the aggregate and .gemini/rules/ are opted in, so only the always-loaded
            // safety buckets stay inline and every other bucket moves to the scoped files (#320).
            AnnotationSections.renderLockedPreamble(sb, model, platform, context.getGeneratedHeader());
            AnnotationSections.renderInlineSafetySections(sb, model, platform);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
            return sb.toString();
        }
        sb.append("# GEMINI AI INSTRUCTIONS\n").append(context.getGeneratedHeader()).append("\n");

        if (!model.locked().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (TaggedElement e : model.locked()) FormatterRegistry.locked().format(e, sec, platform);
            sb.append("\n## LOCKED FILES (DO NOT MODIFY)\nDo not suggest modifications to the following files:\n\n").append(sec);
        }

        if (!model.context().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (TaggedElement e : model.context()) FormatterRegistry.context().format(e, sec, platform);
            sb.append("\n## CONTEXTUAL RULES\nApply the following context when assisting with these files:\n\n").append(sec);
        }

        AnnotationSections.render(sb, model, platform, SECTIONS);

        return sb.toString();
    }
}
