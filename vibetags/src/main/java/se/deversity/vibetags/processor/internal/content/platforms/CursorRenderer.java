package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.section;

/**
 * PlatformRenderer for generating `.cursorrules`.
 */
public final class CursorRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.CURSOR, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.CURSOR, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.CURSOR, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.CURSOR, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.CURSOR, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.CURSOR, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.CURSOR, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.CURSOR, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.CURSOR, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.CURSOR, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.CURSOR, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.CURSOR, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.CURSOR, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.CURSOR, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.CURSOR, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.CURSOR, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.CURSOR, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.CURSOR, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.CURSOR, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.CURSOR, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.CURSOR, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.CURSOR, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.CURSOR, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.CURSOR, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.CURSOR, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure())
    );

    private static final List<AnnotationSections.Section> ALL_SECTIONS =
        AnnotationSections.concat(SECTIONS, AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS);

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        if (GranularIndexSection.indexActive(platform, context)) {
            // Granular sibling opted in: keep only the always-loaded safety buckets inline and point
            // at the scoped rule files for everything else (see GranularIndexSection).
            AnnotationSections.renderIndexedPreamble(sb, model, Platform.CURSOR, context.getGeneratedHeader());
            AnnotationSections.renderInlineSafetySections(sb, model, Platform.CURSOR);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
        } else {
            AnnotationSections.renderLockedAndContextPreamble(sb, model, Platform.CURSOR, context.getGeneratedHeader());
            AnnotationSections.render(sb, model, Platform.CURSOR, ALL_SECTIONS);
        }

        return sb.toString();
    }
}
