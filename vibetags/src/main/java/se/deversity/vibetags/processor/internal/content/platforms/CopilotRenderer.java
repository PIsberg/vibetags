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
 * PlatformRenderer for generating `.github/copilot-instructions.md`.
 */
public final class CopilotRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.COPILOT, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.COPILOT, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.COPILOT, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.COPILOT, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.COPILOT, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.COPILOT, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.COPILOT, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.COPILOT, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.COPILOT, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.COPILOT, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.COPILOT, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.COPILOT, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.COPILOT, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.COPILOT, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.COPILOT, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.COPILOT, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.COPILOT, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.COPILOT, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.COPILOT, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.COPILOT, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.COPILOT, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.COPILOT, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.COPILOT, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.COPILOT, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.COPILOT, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure()),
        section(Platform.COPILOT, SectionCatalog.Key.CALLERS_ONLY, GuardrailModel::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.COPILOT, SectionCatalog.Key.SANDBOX_ONLY, GuardrailModel::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.COPILOT, SectionCatalog.Key.MEMORY_BUDGET, GuardrailModel::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.COPILOT, SectionCatalog.Key.PURE, GuardrailModel::pure, FormatterRegistry.pure()),
        section(Platform.COPILOT, SectionCatalog.Key.DOMAIN_MODEL, GuardrailModel::domainModel, FormatterRegistry.domainModel()),
        section(Platform.COPILOT, SectionCatalog.Key.EXTENSIBLE, GuardrailModel::extensible, FormatterRegistry.extensible()),
        section(Platform.COPILOT, SectionCatalog.Key.INPUT_SANITIZED, GuardrailModel::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.COPILOT, SectionCatalog.Key.SECURE_LOGGING, GuardrailModel::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.COPILOT, SectionCatalog.Key.EXPLAIN, GuardrailModel::explain, FormatterRegistry.explain()),
        section(Platform.COPILOT, SectionCatalog.Key.PROTOTYPE, GuardrailModel::prototype, FormatterRegistry.prototype()),
        section(Platform.COPILOT, SectionCatalog.Key.SUNSET, GuardrailModel::sunset, FormatterRegistry.sunset()),
        section(Platform.COPILOT, SectionCatalog.Key.TEMPORARY, GuardrailModel::temporary, FormatterRegistry.temporary()),
        section(Platform.COPILOT, SectionCatalog.Key.GENERATED, GuardrailModel::generated, FormatterRegistry.generated()),
        section(Platform.COPILOT, SectionCatalog.Key.LOAD_BEARING, GuardrailModel::loadBearing, FormatterRegistry.loadBearing()),
        section(Platform.COPILOT, SectionCatalog.Key.BANNED_API, GuardrailModel::bannedApi, FormatterRegistry.bannedApi()),
        section(Platform.COPILOT, SectionCatalog.Key.THREAD_AFFINITY, GuardrailModel::threadAffinity, FormatterRegistry.threadAffinity()),
        section(Platform.COPILOT, SectionCatalog.Key.KEEP_IN_SYNC, GuardrailModel::keepInSync, FormatterRegistry.keepInSync())
    );

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        boolean indexed = GranularIndexSection.indexActive(platform, context);
        sb.append("# GitHub Copilot Instructions\n")
          .append(context.getGeneratedHeader())
          .append("# AUTO-GENERATED BY VIBETAGS. Do not edit manually.\n");

        // In indexed mode the locked heading is dropped when nothing is locked: the reactor merge
        // repeats this preamble once per module, and empty headings are what made a supposedly lean
        // copilot-instructions.md bulkier than the full merge it replaced (issue #319).
        if (!indexed || !model.locked().isEmpty()) {
            sb.append("\n## Locked Files — DO NOT MODIFY\nDo not suggest changes to the following files:\n\n");
            for (TaggedElement e : model.locked()) {
                FormatterRegistry.locked().format(e, sb, Platform.COPILOT);
            }
        }

        if (indexed) {
            // Granular sibling opted in: keep the always-loaded safety buckets inline and index the
            // rest to the scoped .github/instructions files (context detail moves there too).
            AnnotationSections.renderInlineSafetySections(sb, model, Platform.COPILOT);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
        } else {
            sb.append("\n## Contextual Guidelines\n");
            for (TaggedElement e : model.context()) {
                FormatterRegistry.context().format(e, sb, Platform.COPILOT);
            }

            AnnotationSections.render(sb, model, Platform.COPILOT, SECTIONS);
        }

        return sb.toString();
    }
}
