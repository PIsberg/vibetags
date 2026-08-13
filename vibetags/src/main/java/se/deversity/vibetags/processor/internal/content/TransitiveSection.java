package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the guardrails a project inherited from its dependencies, as an appendix to the
 * aggregate instruction files.
 *
 * <h2>Why an appendix, and why after the project's own rules</h2>
 *
 * <p>Order is the precedence statement. A library cannot displace what the application says about
 * itself, so everything the project declared comes first and the inherited block follows. Nothing
 * enforces that beyond position, and nothing could: the output is prose an agent reads, not a
 * ruleset a compiler applies. Saying "STRICT" louder would not change that, which is why there is
 * no override mechanism here to get wrong.
 *
 * <p>Every rule is rendered with the artifact it came from. A dependency is contributing text to
 * instructions an agent will act on, and a reader who cannot see whose text it is cannot judge it.
 * {@code TransitiveSectionTest} pins the attribution.
 *
 * <h2>Which files carry it</h2>
 *
 * <p>{@link #PLATFORMS} is the explicit list, not a computed one. The appendix is markdown, so it
 * belongs only in files that are markdown or free text; appending it to a YAML, JSON or TOML config
 * would produce a document that a strict parser rejects. Ignore-files are excluded for the same
 * reason: they are path lists, and a heading in one is a path.
 * {@code TransitiveAppendixCoverageTest} fails if a platform joins or leaves this set without the
 * list being updated deliberately.
 */
public final class TransitiveSection {

    /**
     * The aggregate instruction files that carry the inherited-guardrail appendix.
     *
     * <p>All markdown or free text. Deliberately excludes the structured-config platforms
     * ({@code coderabbit}, {@code sweep}, {@code mentat}, {@code cody}, {@code interpreter},
     * {@code codex_config}, {@code qwen_settings}, and the rest), the ignore-file family, and
     * {@code llms} — whose whole shape is a terse link index that a prose block would spoil.
     */
    public static final Set<Platform> PLATFORMS = EnumSet.of(
        Platform.CLAUDE,
        Platform.CLAUDE_LOCAL,
        Platform.CURSOR,
        Platform.CODEX,
        Platform.GEMINI,
        Platform.GEMINI_MD,
        Platform.COPILOT,
        Platform.QWEN,
        Platform.LLMS_FULL,
        Platform.AIDER_CONVENTIONS,
        Platform.WINDSURF,
        Platform.ZED,
        Platform.CLINE,
        Platform.JUNIE,
        Platform.FIREBASE,
        Platform.VOID);

    /** Heading of the safety-tier block. */
    static final String SAFETY_HEADING = "## Inherited Guardrails (dependencies)";

    /** Heading of the advisory-tier block. */
    static final String ADVISORY_HEADING = "## Inherited Context (dependencies)";

    private TransitiveSection() {}

    /** True when {@code platform}'s output carries the appendix. */
    public static boolean carries(Platform platform) {
        return PLATFORMS.contains(platform);
    }

    /**
     * The appendix for {@code model}, or {@code ""} when the platform does not carry one or no
     * dependency contributed a rule.
     *
     * <p>Renders every rule the model holds. The volume cap
     * ({@code -Avibetags.manifest.max}) is applied once, upstream, where rules enter the model:
     * capping here as well would put the same limit in two places and leave the build fingerprint
     * tracking rules that were never written.
     */
    public static String render(GuardrailModel model, Platform platform) {
        if (!carries(platform) || !model.anyTransitiveRules()) {
            return "";
        }
        List<TransitiveRule> safety = new ArrayList<>();
        List<TransitiveRule> advisory = new ArrayList<>();
        for (TransitiveRule rule : model.transitiveRules()) {
            (rule.tier() == TransitiveRule.Tier.SAFETY ? safety : advisory).add(rule);
        }

        StringBuilder sb = new StringBuilder(256 + model.transitiveRules().size() * 96);
        sb.append('\n');
        if (!safety.isEmpty()) {
            sb.append(SAFETY_HEADING).append('\n');
            sb.append("\nThese come from packages this project depends on. They constrain how the"
                + " dependency may be used; they are not editable from here.\n\n");
            appendRules(sb, safety);
        }
        if (!advisory.isEmpty()) {
            if (!safety.isEmpty()) {
                sb.append('\n');
            }
            sb.append(ADVISORY_HEADING).append('\n');
            sb.append("\nConventions the dependency's authors documented. Follow them unless this"
                + " project's own rules above say otherwise.\n\n");
            appendRules(sb, advisory);
        }
        return sb.toString();
    }

    private static void appendRules(StringBuilder sb, List<TransitiveRule> rules) {
        String currentPackage = null;
        for (TransitiveRule rule : rules) {
            if (!rule.packageName().equals(currentPackage)) {
                currentPackage = rule.packageName();
                sb.append("- `").append(currentPackage).append('`');
                if (!rule.origin().isEmpty()) {
                    sb.append(" (from ").append(rule.origin()).append(')');
                }
                sb.append('\n');
            }
            sb.append("  - ").append(rule.annotation());
            String members = rule.memberSummary();
            if (!members.isEmpty()) {
                sb.append(": ").append(oneLine(members));
            }
            sb.append('\n');
        }
    }

    /**
     * Collapses every run of whitespace to a single space.
     *
     * <p>An annotation attribute is free text a library author wrote, and a text block with
     * newlines in it is entirely legal there. Emitted raw into a markdown bullet, the second line
     * would leave the list and become body prose belonging to nobody — losing the rule's
     * attribution in a file whose whole point is that inherited rules are attributable.
     */
    static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
