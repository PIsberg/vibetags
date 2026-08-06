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
 * PlatformRenderer for generating `.windsurfrules`.
 */
public final class WindsurfRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.WINDSURF, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.WINDSURF, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.WINDSURF, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.WINDSURF, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.WINDSURF, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.WINDSURF, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.WINDSURF, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.WINDSURF, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.WINDSURF, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.WINDSURF, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.WINDSURF, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.WINDSURF, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.WINDSURF, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.WINDSURF, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.WINDSURF, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.WINDSURF, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.WINDSURF, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.WINDSURF, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.WINDSURF, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.WINDSURF, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.WINDSURF, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.WINDSURF, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.WINDSURF, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.WINDSURF, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.WINDSURF, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure())
    );

    private static final List<AnnotationSections.Section> ALL_SECTIONS =
        AnnotationSections.concat(SECTIONS, AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS);

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        if (GranularIndexSection.indexActive(platform, context)) {
            AnnotationSections.renderIndexedPreamble(sb, model, Platform.WINDSURF, context.getGeneratedHeader());
            AnnotationSections.renderInlineSafetySections(sb, model, Platform.WINDSURF);
            GranularIndexSection.appendMarkdownIndex(sb, platform, context);
        } else {
            AnnotationSections.renderLockedAndContextPreamble(sb, model, Platform.WINDSURF, context.getGeneratedHeader());
            AnnotationSections.render(sb, model, Platform.WINDSURF, ALL_SECTIONS);
        }

        return sb.toString();
    }
}
