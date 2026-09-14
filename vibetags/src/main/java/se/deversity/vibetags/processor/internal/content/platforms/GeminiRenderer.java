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
 * PlatformRenderer for generating `GEMINI.md`. It also rendered `gemini_instructions.md` until that
 * output was removed in 2.0.0 (#645).
 */
public final class GeminiRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.GEMINI_MD, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.CALLERS_ONLY, GuardrailModel::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.SANDBOX_ONLY, GuardrailModel::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.MEMORY_BUDGET, GuardrailModel::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.PURE, GuardrailModel::pure, FormatterRegistry.pure()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.DOMAIN_MODEL, GuardrailModel::domainModel, FormatterRegistry.domainModel()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.EXTENSIBLE, GuardrailModel::extensible, FormatterRegistry.extensible()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.INPUT_SANITIZED, GuardrailModel::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.SECURE_LOGGING, GuardrailModel::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.EXPLAIN, GuardrailModel::explain, FormatterRegistry.explain()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.PROTOTYPE, GuardrailModel::prototype, FormatterRegistry.prototype()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.SUNSET, GuardrailModel::sunset, FormatterRegistry.sunset()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.TEMPORARY, GuardrailModel::temporary, FormatterRegistry.temporary()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.GENERATED, GuardrailModel::generated, FormatterRegistry.generated()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.LOAD_BEARING, GuardrailModel::loadBearing, FormatterRegistry.loadBearing()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.BANNED_API, GuardrailModel::bannedApi, FormatterRegistry.bannedApi()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.THREAD_AFFINITY, GuardrailModel::threadAffinity, FormatterRegistry.threadAffinity()),
        section(Platform.GEMINI_MD, SectionCatalog.Key.KEEP_IN_SYNC, GuardrailModel::keepInSync, FormatterRegistry.keepInSync())
    );

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        if (GranularIndexSection.indexActive(platform, context)) {
            // Both the aggregate and .gemini/rules/ are opted in, so only the always-loaded
            // safety buckets stay inline and every other bucket moves to the scoped files (#320).
            AnnotationSections.renderIndexedPreamble(sb, model, platform, context.getGeneratedHeader());
            AnnotationSections.renderInlineSafetySectionsInDefaultWording(sb, model, platform);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
            return sb.toString();
        }
        sb.append("# GEMINI AI INSTRUCTIONS\n").append(context.getGeneratedHeader()).append('\n');

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
