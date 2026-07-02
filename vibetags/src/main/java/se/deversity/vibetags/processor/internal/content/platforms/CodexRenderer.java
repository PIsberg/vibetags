package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;

import static se.deversity.vibetags.processor.internal.content.platforms.AnnotationSections.section;

/**
 * PlatformRenderer for generating Codex `AGENTS.md`, config, and rules.
 */
public final class CodexRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.CODEX, SectionCatalog.Key.AUDIT, AnnotationCollector::audit, FormatterRegistry.audit()),
        section(Platform.CODEX, SectionCatalog.Key.IGNORE, AnnotationCollector::ignore, FormatterRegistry.ignore()),
        section(Platform.CODEX, SectionCatalog.Key.DRAFT, AnnotationCollector::draft, FormatterRegistry.draft()),
        section(Platform.CODEX, SectionCatalog.Key.PRIVACY, AnnotationCollector::privacy, FormatterRegistry.privacy()),
        section(Platform.CODEX, SectionCatalog.Key.CORE, AnnotationCollector::core, FormatterRegistry.core()),
        section(Platform.CODEX, SectionCatalog.Key.PERFORMANCE, AnnotationCollector::performance, FormatterRegistry.performance()),
        section(Platform.CODEX, SectionCatalog.Key.CONTRACT, AnnotationCollector::contract, FormatterRegistry.contract()),
        section(Platform.CODEX, SectionCatalog.Key.TEST_DRIVEN, AnnotationCollector::testDriven, FormatterRegistry.testDriven()),
        section(Platform.CODEX, SectionCatalog.Key.THREAD_SAFE, AnnotationCollector::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.CODEX, SectionCatalog.Key.IMMUTABLE, AnnotationCollector::immutable, FormatterRegistry.immutable()),
        section(Platform.CODEX, SectionCatalog.Key.DEPRECATED, AnnotationCollector::deprecated, FormatterRegistry.deprecated()),
        section(Platform.CODEX, SectionCatalog.Key.OBSERVABILITY, AnnotationCollector::observability, FormatterRegistry.observability()),
        section(Platform.CODEX, SectionCatalog.Key.REGULATION, AnnotationCollector::regulation, FormatterRegistry.regulation()),
        section(Platform.CODEX, SectionCatalog.Key.PARALLEL_TESTS, AnnotationCollector::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.CODEX, SectionCatalog.Key.LEGACY_BRIDGE, AnnotationCollector::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.CODEX, SectionCatalog.Key.ARCHITECTURE, AnnotationCollector::architecture, FormatterRegistry.architecture()),
        section(Platform.CODEX, SectionCatalog.Key.PUBLIC_API, AnnotationCollector::publicApi, FormatterRegistry.publicApi()),
        section(Platform.CODEX, SectionCatalog.Key.STRICT_EXCEPTIONS, AnnotationCollector::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.CODEX, SectionCatalog.Key.STRICT_TYPES, AnnotationCollector::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.CODEX, SectionCatalog.Key.INTERNATIONALIZED, AnnotationCollector::internationalized, FormatterRegistry.internationalized()),
        section(Platform.CODEX, SectionCatalog.Key.STRICT_CLASSPATH, AnnotationCollector::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.CODEX, SectionCatalog.Key.SCHEMA_SAFE, AnnotationCollector::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.CODEX, SectionCatalog.Key.IDEMPOTENT, AnnotationCollector::idempotent, FormatterRegistry.idempotent()),
        section(Platform.CODEX, SectionCatalog.Key.FEATURE_FLAG, AnnotationCollector::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.CODEX, SectionCatalog.Key.SECURE, AnnotationCollector::secure, FormatterRegistry.secure())
    );

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        if (platform == Platform.CODEX_CONFIG) {
            return "# " + context.getGeneratedHeader().trim() + "\n[project]\nmodel = \"o3-mini\"\napproval_policy = \"on-request\"\n";
        }
        if (platform == Platform.CODEX_RULES) {
            return "# " + context.getGeneratedHeader().trim() + "\n# VibeTags: Starlark Command Permissions\n\n" +
                "prefix_rule(\"ls\", \"allow\")\n" +
                "prefix_rule(\"cat\", \"allow\")\n" +
                "prefix_rule(\"grep\", \"allow\")\n" +
                "prefix_rule(\"mvn\", \"prompt\")\n" +
                "prefix_rule(\"npm\", \"prompt\")\n" +
                "prefix_rule(\"git\", \"prompt\")\n" +
                "prefix_rule(\"rm\", \"prompt\")\n";
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("# AUTO-GENERATED AI RULES\n").append(context.getGeneratedHeader()).append("# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n");

        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.CODEX);
        }

        sb.append("\n## CONTEXTUAL RULES\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, Platform.CODEX);
        }

        AnnotationSections.render(sb, collector, Platform.CODEX, SECTIONS);

        return sb.toString();
    }
}
