package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
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
        section(Platform.WINDSURF, SectionCatalog.Key.THREAD_SAFE, AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.WINDSURF, SectionCatalog.Key.IMMUTABLE, AnnotationCollector::immutable, FormatterRegistry.immutable()),
        section(Platform.WINDSURF, SectionCatalog.Key.DEPRECATED, AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        section(Platform.WINDSURF, SectionCatalog.Key.OBSERVABILITY, AnnotationCollector::observability, FormatterRegistry.observability()),
        section(Platform.WINDSURF, SectionCatalog.Key.REGULATION, AnnotationCollector::regulation, FormatterRegistry.regulation()),
        section(Platform.WINDSURF, SectionCatalog.Key.AUDIT, AnnotationCollector::audit, FormatterRegistry.audit()),
        section(Platform.WINDSURF, SectionCatalog.Key.IGNORE, AnnotationCollector::ignore, FormatterRegistry.ignore()),
        section(Platform.WINDSURF, SectionCatalog.Key.DRAFT, AnnotationCollector::draft, FormatterRegistry.draft()),
        section(Platform.WINDSURF, SectionCatalog.Key.PRIVACY, AnnotationCollector::privacy, FormatterRegistry.privacy()),
        section(Platform.WINDSURF, SectionCatalog.Key.CORE, AnnotationCollector::core, FormatterRegistry.core()),
        section(Platform.WINDSURF, SectionCatalog.Key.PERFORMANCE, AnnotationCollector::performance, FormatterRegistry.performance()),
        section(Platform.WINDSURF, SectionCatalog.Key.CONTRACT, AnnotationCollector::contract, FormatterRegistry.contract()),
        section(Platform.WINDSURF, SectionCatalog.Key.TEST_DRIVEN, AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        section(Platform.WINDSURF, SectionCatalog.Key.PARALLEL_TESTS, AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.WINDSURF, SectionCatalog.Key.LEGACY_BRIDGE, AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.WINDSURF, SectionCatalog.Key.ARCHITECTURE, AnnotationCollector::architecture, FormatterRegistry.architecture()),
        section(Platform.WINDSURF, SectionCatalog.Key.PUBLIC_API, AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        section(Platform.WINDSURF, SectionCatalog.Key.STRICT_EXCEPTIONS, AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.WINDSURF, SectionCatalog.Key.STRICT_TYPES, AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.WINDSURF, SectionCatalog.Key.INTERNATIONALIZED, AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        section(Platform.WINDSURF, SectionCatalog.Key.STRICT_CLASSPATH, AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.WINDSURF, SectionCatalog.Key.SCHEMA_SAFE, AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.WINDSURF, SectionCatalog.Key.IDEMPOTENT, AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        section(Platform.WINDSURF, SectionCatalog.Key.FEATURE_FLAG, AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.WINDSURF, SectionCatalog.Key.SECURE, AnnotationCollector::secure, FormatterRegistry.secure())
    );

    private static final List<AnnotationSections.Section> ALL_SECTIONS =
        AnnotationSections.concat(SECTIONS, AnnotationSections.EMOJI_STYLE_NEWEST_ANNOTATIONS);

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        AnnotationSections.renderLockedAndContextPreamble(sb, collector, Platform.WINDSURF, context.getGeneratedHeader());
        AnnotationSections.render(sb, collector, Platform.WINDSURF, ALL_SECTIONS);

        return sb.toString();
    }
}
