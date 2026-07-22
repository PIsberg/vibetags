package se.deversity.vibetags.processor.internal.content.platforms;

import java.util.Set;
import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * Emits the "scoped-rules index" that replaces the bulky per-element buckets of an aggregate file
 * (CLAUDE.md, .cursorrules, .windsurfrules, .github/copilot-instructions.md) when that platform's
 * granular sibling is also opted in.
 *
 * <p>Rationale: when a user opts into both a platform's single always-loaded file <em>and</em> its
 * glob-scoped granular directory, rendering every element's full guardrails into both is pure
 * duplication that dilutes the high-value always-on rules. In that case the aggregate keeps only
 * the always-loaded safety guardrails inline (locked, core, privacy, ignore, audit, secure) and
 * points at the scoped files for everything else via this index. When the sibling is <em>not</em>
 * active the aggregate renders in full, exactly as before.
 *
 * <p>The index path for each owner element is derived from {@link ElementNaming#granularQName} — the
 * same transform {@code GranularRulesWriter} uses to name the files — so a pointer can never drift
 * from the file it references.
 */
final class GranularIndexSection {

    private GranularIndexSection() {}

    /**
     * Maps an aggregate platform to the service key of the granular directory that governs it, or
     * {@code null} when the platform has no granular sibling. {@code CLAUDE_LOCAL} deliberately maps
     * to {@code claude_granular}: {@code CLAUDE.local.md} is loaded by the same tool as
     * {@code CLAUDE.md} and mirrors its content, so it follows Claude's granular state. Platforms
     * that merely reuse a renderer's format but read no scoped directory (Cline, Firebase, Junie,
     * Void, the Claude skill) map to {@code null} and therefore never collapse to an index.
     */
    static String governingGranularKey(Platform platform) {
        switch (platform) {
            case CLAUDE:
            case CLAUDE_LOCAL:
                return "claude_granular";
            case CURSOR:
                return "cursor_granular";
            case WINDSURF:
                return "windsurf_granular";
            case COPILOT:
                return "copilot_granular";
            default:
                return null;
        }
    }

    /**
     * True when {@code platform}'s aggregate file should collapse to a scoped-rules index: it has a
     * governing granular sibling, that sibling is active, and there is at least one owner element to
     * point at.
     */
    static boolean indexActive(Platform platform, RenderingContext context) {
        String key = governingGranularKey(platform);
        return key != null
            && context.getActiveServices().contains(key)
            && !context.granularOwners().isEmpty();
    }

    /** Directory (no trailing slash) that holds the governing granular files for {@code platform}. */
    private static String scopedDir(Platform platform) {
        String key = governingGranularKey(platform);
        if (key == null) {
            return null;
        }
        switch (key) {
            case "claude_granular":   return ".claude/rules";
            case "cursor_granular":   return ".cursor/rules";
            case "windsurf_granular": return ".windsurf/rules";
            case "copilot_granular":  return ".github/instructions";
            default:                  return null;
        }
    }

    /** Filename suffix (including the leading dot) of the governing granular files for {@code platform}. */
    private static String scopedSuffix(Platform platform) {
        String key = governingGranularKey(platform);
        if (key == null) {
            return null;
        }
        // Cursor uses .mdc; Copilot uses the two-dot .instructions.md; the rest use .md.
        switch (key) {
            case "cursor_granular":  return ".mdc";
            case "copilot_granular": return ".instructions.md";
            default:                 return ".md";
        }
    }

    /** Relative path to the scoped rule file for {@code owner} under {@code platform}'s granular directory. */
    private static String scopedPath(Platform platform, Element owner, RenderingContext context) {
        // Name the file GranularRulesWriter actually wrote: the role-grouped stem when a
        // .vibetags-roles config routes this element, else the per-class qName. Resolving through
        // the same RoleConfig keeps the pointer from dangling to a file that was never written.
        String stem = context.roles() != null
            ? context.roles().granularStemFor(owner)
            : ElementNaming.granularQName(owner);
        return scopedDir(platform) + "/" + stem + scopedSuffix(platform);
    }

    /**
     * Appends the XML {@code <scoped_rules>} index (CLAUDE.md format). Attribute values are
     * XML-escaped for consistency with the rest of the Claude output. Emits nothing when there are
     * no owners.
     */
    static void appendXmlIndex(StringBuilder sb, Platform platform, RenderingContext context) {
        Set<Element> owners = context.granularOwners();
        if (owners.isEmpty()) {
            return;
        }
        sb.append("  <scoped_rules>\n");
        sb.append("    <note>Detailed per-element guardrails for the elements below live in scoped rule files that load automatically when the matching source file is opened. Consult the referenced file before modifying an element.</note>\n");
        for (Element owner : owners) {
            sb.append("    <element path=\"").append(Escape.xml(owner.toString()))
              .append("\" rules=\"").append(Escape.xml(scopedPath(platform, owner, context))).append("\"/>\n");
        }
        sb.append("  </scoped_rules>\n");
        sb.append("\n<rule>When you work on any element listed in <scoped_rules>, open its referenced rule file and apply the guardrails there. The rule files are the authoritative source for those elements.</rule>\n");
    }

    /**
     * Appends the markdown "## Scoped Rules Index" list (.cursorrules / .windsurfrules /
     * copilot-instructions.md format). Markdown outputs are free text, so values are not escaped —
     * matching the convention of the other markdown renderers. Emits nothing when there are no owners.
     */
    static void appendMarkdownIndex(StringBuilder sb, Platform platform, RenderingContext context) {
        Set<Element> owners = context.granularOwners();
        if (owners.isEmpty()) {
            return;
        }
        sb.append("\n## Scoped Rules Index\n");
        sb.append("Detailed per-element guardrails live in scoped rule files that load automatically when you open the matching source file. Consult the referenced file before modifying an element:\n\n");
        for (Element owner : owners) {
            sb.append("- `").append(owner.toString()).append("` → `").append(scopedPath(platform, owner, context)).append("`\n");
        }
    }
}
