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
 * PlatformRenderer for generating Codex `AGENTS.md`, config, and rules.
 */
public final class CodexRenderer implements PlatformRenderer {

    private static final List<AnnotationSections.Section> SECTIONS = List.of(
        section(Platform.CODEX, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
        section(Platform.CODEX, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
        section(Platform.CODEX, SectionCatalog.Key.DRAFT, GuardrailModel::draft, FormatterRegistry.draft()),
        section(Platform.CODEX, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
        section(Platform.CODEX, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
        section(Platform.CODEX, SectionCatalog.Key.PERFORMANCE, GuardrailModel::performance, FormatterRegistry.performance()),
        section(Platform.CODEX, SectionCatalog.Key.CONTRACT, GuardrailModel::contract, FormatterRegistry.contract()),
        section(Platform.CODEX, SectionCatalog.Key.TEST_DRIVEN, GuardrailModel::testDriven, FormatterRegistry.testDriven()),
        section(Platform.CODEX, SectionCatalog.Key.THREAD_SAFE, GuardrailModel::threadSafe, FormatterRegistry.threadSafe()),
        section(Platform.CODEX, SectionCatalog.Key.IMMUTABLE, GuardrailModel::immutable, FormatterRegistry.immutable()),
        section(Platform.CODEX, SectionCatalog.Key.DEPRECATED, GuardrailModel::deprecated, FormatterRegistry.deprecated()),
        section(Platform.CODEX, SectionCatalog.Key.OBSERVABILITY, GuardrailModel::observability, FormatterRegistry.observability()),
        section(Platform.CODEX, SectionCatalog.Key.REGULATION, GuardrailModel::regulation, FormatterRegistry.regulation()),
        section(Platform.CODEX, SectionCatalog.Key.PARALLEL_TESTS, GuardrailModel::parallelTests, FormatterRegistry.parallelTests()),
        section(Platform.CODEX, SectionCatalog.Key.LEGACY_BRIDGE, GuardrailModel::legacyBridge, FormatterRegistry.legacyBridge()),
        section(Platform.CODEX, SectionCatalog.Key.ARCHITECTURE, GuardrailModel::architecture, FormatterRegistry.architecture()),
        section(Platform.CODEX, SectionCatalog.Key.PUBLIC_API, GuardrailModel::publicApi, FormatterRegistry.publicApi()),
        section(Platform.CODEX, SectionCatalog.Key.STRICT_EXCEPTIONS, GuardrailModel::strictExceptions, FormatterRegistry.strictExceptions()),
        section(Platform.CODEX, SectionCatalog.Key.STRICT_TYPES, GuardrailModel::strictTypes, FormatterRegistry.strictTypes()),
        section(Platform.CODEX, SectionCatalog.Key.INTERNATIONALIZED, GuardrailModel::internationalized, FormatterRegistry.internationalized()),
        section(Platform.CODEX, SectionCatalog.Key.STRICT_CLASSPATH, GuardrailModel::strictClasspath, FormatterRegistry.strictClasspath()),
        section(Platform.CODEX, SectionCatalog.Key.SCHEMA_SAFE, GuardrailModel::schemaSafe, FormatterRegistry.schemaSafe()),
        section(Platform.CODEX, SectionCatalog.Key.IDEMPOTENT, GuardrailModel::idempotent, FormatterRegistry.idempotent()),
        section(Platform.CODEX, SectionCatalog.Key.FEATURE_FLAG, GuardrailModel::featureFlag, FormatterRegistry.featureFlag()),
        section(Platform.CODEX, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure())
    );

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
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

        for (TaggedElement e : model.locked()) {
            FormatterRegistry.locked().format(e, sb, Platform.CODEX);
        }

        sb.append("\n## CONTEXTUAL RULES\n");
        for (TaggedElement e : model.context()) {
            FormatterRegistry.context().format(e, sb, Platform.CODEX);
        }

        AnnotationSections.render(sb, model, Platform.CODEX, SECTIONS);

        return sb.toString();
    }
}
