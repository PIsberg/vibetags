package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.RoleConfig;
import org.jspecify.annotations.Nullable;
import java.util.Set;
import se.deversity.vibetags.processor.model.TaggedElement;
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
 * <p>The index path for each owner element is derived from {@code TaggedElement.granularQName()} — the
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
    static @Nullable String governingGranularKey(Platform platform) {
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
            case GEMINI_MD:
                return "gemini_granular";
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

    /**
     * Directory (no trailing slash) that holds the governing granular files for {@code platform}.
     * Package-private so {@code DocsGranularPairsClaimTest} can derive the documented pair list.
     */
    static @Nullable String scopedDir(Platform platform) {
        String key = governingGranularKey(platform);
        if (key == null) {
            return null;
        }
        switch (key) {
            case "claude_granular":   return ".claude/rules";
            case "cursor_granular":   return ".cursor/rules";
            case "windsurf_granular": return ".windsurf/rules";
            case "copilot_granular":  return ".github/instructions";
            case "gemini_granular":   return ".gemini/rules";
            default:                  return null;
        }
    }

    /** Filename suffix (including the leading dot) of the governing granular files for {@code platform}. */
    private static @Nullable String scopedSuffix(Platform platform) {
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
    private static String scopedPath(Platform platform, TaggedElement owner, RenderingContext context) {
        // Name the file GranularRulesWriter actually wrote: the role-grouped stem when a
        // .vibetags-roles config routes this element, else the per-class qName. Resolving through
        // the same RoleConfig keeps the pointer from dangling to a file that was never written.
        RoleConfig roles = context.roles();
        String stem = roles != null ? roles.granularStemFor(owner) : owner.granularQName();
        String dir = scopedDir(platform);
        String suffix = scopedSuffix(platform);
        if (dir == null || suffix == null) {
            // Only reachable if a caller skipped indexActive(), which already required a governing
            // granular key; a bare stem is still a usable pointer, an NPE in a renderer is not.
            return stem;
        }
        return dir + "/" + stem + suffix;
    }

    /**
     * The path {@code owner}'s scoped file would have under {@code platform}'s naming convention:
     * the granular directory, the element's qualified name with every non-alphanumeric character
     * replaced by {@code -}, and the platform's suffix.
     *
     * <p>When {@link #scopedPath} agrees with this, the index omits the pointer and states the
     * convention once instead: printing a value derived from the FQN next to the FQN it was
     * derived from spends always-loaded context on nothing (issue #626). A {@code .vibetags-roles}
     * config routes several elements onto one shared role file, so the two disagree there and the
     * explicit pointer is kept.
     */
    private static @Nullable String conventionalPath(Platform platform, TaggedElement owner) {
        String dir = scopedDir(platform);
        String suffix = scopedSuffix(platform);
        return dir == null || suffix == null ? null : dir + "/" + owner.granularQName() + suffix;
    }

    /**
     * One sentence naming the convention, so an entry that carries no explicit pointer is still
     * resolvable. Empty when the platform has no granular directory, which {@link #indexActive}
     * already rules out for every caller.
     */
    private static String conventionNote(Platform platform) {
        String dir = scopedDir(platform);
        String suffix = scopedSuffix(platform);
        if (dir == null || suffix == null) {
            return "";
        }
        return " Unless an entry carries an explicit path, its file is "
            + dir + "/{path, every non-alphanumeric character replaced by '-'}" + suffix + ".";
    }

    /**
     * Appends the XML {@code <scoped_rules>} index (CLAUDE.md format). Attribute values are
     * XML-escaped for consistency with the rest of the Claude output. Emits nothing when there are
     * no owners.
     */
    static void appendXmlIndex(StringBuilder sb, Platform platform, RenderingContext context) {
        Set<TaggedElement> owners = context.granularOwners();
        if (owners.isEmpty() || context.safetyDigest()) {
            return;
        }
        sb.append("  <scoped_rules>\n")
            .append("    <note>Detailed per-element guardrails for the elements below live in scoped rule files that load automatically when the matching source file is opened.")
            .append(Escape.xml(conventionNote(platform)))
            .append(" Consult the file before modifying an element.</note>\n");
        for (TaggedElement owner : owners) {
            String path = scopedPath(platform, owner, context);
            sb.append("    <element path=\"").append(Escape.xml(owner.toString()));
            if (!path.equals(conventionalPath(platform, owner))) {
                sb.append("\" rules=\"").append(Escape.xml(path));
            }
            sb.append("\"/>\n");
        }
        sb.append("  </scoped_rules>\n")
            .append("\n<rule>When you work on any element listed in <scoped_rules>, open its referenced rule file and apply the guardrails there. The rule files are the authoritative source for those elements.</rule>\n");
    }

    /**
     * Appends the markdown "## Scoped Rules Index" list (.cursorrules / .windsurfrules /
     * copilot-instructions.md format). Markdown outputs are free text, so values are not escaped —
     * matching the convention of the other markdown renderers. Emits nothing when there are no owners.
     */
    static void appendMarkdownIndex(StringBuilder sb, Platform platform, RenderingContext context) {
        Set<TaggedElement> owners = context.granularOwners();
        if (owners.isEmpty() || context.safetyDigest()) {
            return;
        }
        sb.append("\n## Scoped Rules Index\n")
            .append("Detailed per-element guardrails live in scoped rule files that load automatically when you open the matching source file.")
            .append(conventionNote(platform))
            .append(" Consult the file before modifying an element:\n\n");
        for (TaggedElement owner : owners) {
            String path = scopedPath(platform, owner, context);
            sb.append("- `").append(owner.toString()).append('`');
            if (!path.equals(conventionalPath(platform, owner))) {
                sb.append(" → `").append(path).append('`');
            }
            sb.append('\n');
        }
    }
}
