package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.List;
import org.jspecify.annotations.Nullable;
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
 * PlatformRenderer for generating `.windsurfrules`, and the always-on safety files Devin Desktop
 * loads from {@code .windsurf/rules/} and {@code .devin/rules/} (issue #684), which carry the same
 * safety sections `.windsurfrules` keeps inline when its scoped rules are opted in.
 */
public final class WindsurfRenderer implements PlatformRenderer {
    @Override
    public SourceSetMerge sourceSetMerge() {
        return MarkdownSectionMerge::merge;
    }

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
        if (platform == Platform.WINDSURF_SAFETY || platform == Platform.DEVIN_SAFETY) {
            return renderSafetyTier(model, platform, context);
        }
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

    /**
     * The safety file inside a Devin Desktop rules directory (issue #684).
     *
     * <p>Every other file VibeTags writes there is a {@code trigger: glob} rule, which docs.devin.ai
     * says is "applied when Cascade reads or edits a file matching the globs pattern". The six safety
     * buckets must not wait for that (invariant 6), so this file is {@code trigger: always_on}: "Full
     * rule content is included in the system prompt on every message."
     *
     * <p>When another always-loaded file already carries the tier, this one names it instead of
     * repeating it, because both would be in the system prompt: {@code .windsurfrules}, which Devin
     * Desktop still reads beside both directories, and for {@code .windsurf/rules/} also the
     * preferred {@code .devin/rules/}, since Devin CLI loads the rule files of both. It is still
     * written in that case, and when the tier is empty, so a guardrail it carried before is retired
     * rather than left loaded with nothing to update it.
     */
    private static String renderSafetyTier(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        sb.append("---\ntrigger: always_on\n---\n\n");
        String carrier = safetyTierCarrier(platform, context);
        if (carrier == null) {
            AnnotationSections.renderIndexedPreamble(sb, model, Platform.WINDSURF, context.getGeneratedHeader());
            AnnotationSections.renderInlineSafetySections(sb, model, Platform.WINDSURF);
        } else {
            sb.append("# AUTO-GENERATED AI RULES\n")
              .append(context.getGeneratedHeader())
              .append("# Do not edit manually.\n\n## SAFETY GUARDRAILS\n")
              .append("The always-loaded guardrails (locked, audit, ignore, privacy, core and security) are in ")
              .append(carrier)
              .append(", which is also loaded on every message, so they are not repeated here.\n");
        }
        String dir = platform == Platform.DEVIN_SAFETY ? ".devin/rules/" : ".windsurf/rules/";
        sb.append("\n## SCOPED RULES\n")
          .append("Every other guardrail lives in the other rule files in ").append(dir)
          .append(", each loaded when a file its globs: pattern matches is read or edited.\n");
        return sb.toString();
    }

    /** The other always-loaded file that carries the safety tier, or {@code null} when none does. */
    private static @Nullable String safetyTierCarrier(Platform platform, RenderingContext context) {
        if (context.getActiveServices().contains(Platform.WINDSURF.getServiceKey())) {
            return ".windsurfrules";
        }
        if (platform == Platform.WINDSURF_SAFETY
                && context.getActiveServices().contains(Platform.DEVIN_GRANULAR.getServiceKey())) {
            return ".devin/rules/+vibetags-safety.md";
        }
        return null;
    }
}
