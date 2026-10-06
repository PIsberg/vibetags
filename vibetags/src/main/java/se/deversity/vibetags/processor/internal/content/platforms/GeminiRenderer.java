package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.MarkdownSectionMerge;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;
import se.deversity.vibetags.processor.internal.content.SourceSetMerge;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.section;

/**
 * PlatformRenderer for generating `GEMINI.md`, the Gemini CLI file.
 */
public final class GeminiRenderer implements PlatformRenderer {
    @Override
    public SourceSetMerge sourceSetMerge() {
        return MarkdownSectionMerge::merge;
    }

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
        section(Platform.GEMINI_MD, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure())
    );

    /** The head above plus the shared tail, so a new annotation is listed once, in {@link AnnotationSections}. */
    private static final List<AnnotationSections.Section> ALL_SECTIONS = AnnotationSections.concat(
        SECTIONS, AnnotationSections.newestAnnotationSections(Platform.GEMINI_MD));

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        renderTitleAndLocked(sb, model, platform, context);
        if (GranularIndexSection.indexActive(platform, context)) {
            // Both the aggregate and .gemini/rules/ are opted in, so only the always-loaded
            // safety buckets stay inline and every other bucket moves to the scoped files (#320).
            // What stays inline keeps GEMINI.md's own title, locked heading and section wording,
            // as every other collapsing aggregate does (#721).
            AnnotationSections.renderInlineSafetySections(sb, model, platform);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
            return sb.toString();
        }

        if (!model.context().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (TaggedElement e : model.context()) FormatterRegistry.context().format(e, sec, platform);
            sb.append("\n## CONTEXTUAL RULES\nApply the following context when assisting with these files:\n\n").append(sec);
        }

        AnnotationSections.render(sb, model, platform, ALL_SECTIONS);

        return sb.toString();
    }

    /**
     * The opening both shapes of the file share: the Gemini title, then the locked files under
     * Gemini's heading, the heading omitted when nothing is locked. The collapsed file used to open
     * with the shared {@code # AUTO-GENERATED AI RULES} preamble instead, so adding
     * {@code .gemini/rules/} renamed the title and the locked heading along with the moved buckets
     * (#721).
     *
     * <p>No blank line follows the generated header: every section that can come next opens with
     * its own, and a second one here printed two blank lines above the first heading (#723).
     */
    private static void renderTitleAndLocked(StringBuilder sb, GuardrailModel model, Platform platform, RenderingContext context) {
        sb.append("# GEMINI AI INSTRUCTIONS\n").append(context.getGeneratedHeader());

        if (!model.locked().isEmpty()) {
            StringBuilder sec = new StringBuilder();
            for (TaggedElement e : model.locked()) FormatterRegistry.locked().format(e, sec, platform);
            sb.append("\n## LOCKED FILES (DO NOT MODIFY)\nDo not suggest modifications to the following files:\n\n").append(sec);
        }
    }
}
