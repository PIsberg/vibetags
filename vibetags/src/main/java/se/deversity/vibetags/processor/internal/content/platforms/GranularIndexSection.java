package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.RoleConfig;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.GranularPairing;
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
     * The pairing that governs {@code platform}'s aggregate, or {@code null} when it has none.
     * {@code CLAUDE_LOCAL} is the one alias: {@code CLAUDE.local.md} is loaded by the same tool as
     * {@code CLAUDE.md}, so it follows Claude's pairing. Every other platform resolves by its own
     * service key, which is what keeps this from being a second list of the pairs (#763).
     */
    private static @Nullable GranularPairing pairingFor(Platform platform) {
        if (platform == Platform.CLAUDE_LOCAL) {
            return GranularPairing.CLAUDE;
        }
        return GranularPairing.forAggregate(platform.getServiceKey());
    }

    /**
     * Maps an aggregate platform to the service key of the granular directory that governs it, or
     * {@code null} when the platform has no granular sibling. {@code CLAUDE_LOCAL} deliberately maps
     * to {@code claude_granular}: {@code CLAUDE.local.md} is loaded by the same tool as
     * {@code CLAUDE.md} and mirrors its content, so it follows Claude's granular state. Platforms
     * that merely reuse a renderer's format but read no scoped directory (Cline, Firebase, Junie,
     * Void, the Claude skill) map to {@code null} and therefore never collapse to an index.
     */
    static @Nullable String governingGranularKey(Platform platform) {
        GranularPairing pairing = pairingFor(platform);
        return pairing == null ? null : pairing.granularKey();
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
        GranularPairing pairing = pairingFor(platform);
        return pairing == null ? null : pairing.scopedDir();
    }

    /** Filename suffix (including the leading dot) of the governing granular files for {@code platform}. */
    private static @Nullable String scopedSuffix(Platform platform) {
        GranularPairing pairing = pairingFor(platform);
        return pairing == null ? null : pairing.extension();
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
     * Whether the tool that reads {@code platform}'s aggregate loads a scoped rule file by itself once
     * a matching source file is in play, which is what the index note promises. Claude Code, Cursor,
     * Windsurf and Copilot do. Gemini CLI does not (#669): it loads {@code GEMINI.md} files, the
     * hierarchy plus just-in-time ones in a directory a tool touches, and its source never names
     * {@code .gemini/rules/}. An agent told a rule is already loaded does not go and read it, so the
     * Gemini note sends the agent to the file instead. {@code @file} imports in {@code GEMINI.md}
     * would not help: they are expanded when the file loads, which would undo the collapse.
     */
    private static boolean loadsScopedFilesOnOpen(Platform platform) {
        GranularPairing pairing = pairingFor(platform);
        return pairing == null || pairing.loadsScopedFilesOnOpen();
    }

    /**
     * Appends the XML {@code <scoped_rules>} index (CLAUDE.md format). Attribute values are
     * XML-escaped for consistency with the rest of the Claude output. Emits nothing when there are
     * no owners.
     */
    static void appendXmlIndex(StringBuilder sb, Platform platform, RenderingContext context) {
        // indexOwners, not granularOwners: an owner whose file holds only the safety tier is
        // already inline above, and a line pointing at it spends context on nothing (#839).
        Set<TaggedElement> owners = context.indexOwners();
        if (owners.isEmpty() || context.safetyDigest()) {
            return;
        }
        sb.append("  <scoped_rules>\n")
            .append("    <note>Detailed per-element guardrails for the elements below live in scoped rule files that load automatically when the matching source file is opened.")
            .append(" An elements entry lists names under a shared prefix: in=\"a.b\" listing C, D means a.b.C and a.b.D.")
            .append(Escape.xml(conventionNote(platform)))
            .append(" Consult the file before modifying an element.</note>\n");
        for (IndexLine line : indexLines(platform, owners, context)) {
            if (line.prefix != null) {
                sb.append("    <elements in=\"").append(Escape.xml(line.prefix)).append("\">");
                for (int i = 0; i < line.names.size(); i++) {
                    sb.append(i == 0 ? "" : ", ").append(Escape.xml(line.names.get(i)));
                }
                sb.append("</elements>\n");
                continue;
            }
            sb.append("    <element path=\"").append(Escape.xml(line.names.get(0)));
            if (line.rules != null) {
                sb.append("\" rules=\"").append(Escape.xml(line.rules));
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
        // indexOwners, not granularOwners: an owner whose file holds only the safety tier is
        // already inline above, and a line pointing at it spends context on nothing (#839).
        Set<TaggedElement> owners = context.indexOwners();
        if (owners.isEmpty() || context.safetyDigest()) {
            return;
        }
        boolean loadsItself = loadsScopedFilesOnOpen(platform);
        sb.append("\n## Scoped Rules Index\n")
            .append(loadsItself
                ? "Detailed per-element guardrails live in scoped rule files that load automatically when you open the matching source file."
                : "Detailed per-element guardrails live in scoped rule files that Gemini CLI does not load on its own.")
            .append(" A line `a.b`: `C`, `D` names a.b.C and a.b.D.")
            .append(conventionNote(platform))
            .append(loadsItself
                ? " Consult the file before modifying an element:\n\n"
                : " Before modifying an element listed below, open its file with read_file and apply the guardrails there:\n\n");
        for (IndexLine line : indexLines(platform, owners, context)) {
            if (line.prefix != null) {
                sb.append("- `").append(line.prefix).append("`: ");
                for (int i = 0; i < line.names.size(); i++) {
                    sb.append(i == 0 ? "`" : ", `").append(line.names.get(i)).append('`');
                }
                sb.append('\n');
                continue;
            }
            sb.append("- `").append(line.names.get(0)).append('`');
            if (line.rules != null) {
                sb.append(" → `").append(line.rules).append('`');
            }
            sb.append('\n');
        }
    }

    /**
     * The index as lines, grouping every conventionally named owner under the text before its simple
     * name, so a shared package is written once rather than once per element (issue #839). A group
     * sits where its first owner would have, and {@code prefix + "." + name} is that owner again, so
     * nothing is lost. An owner whose file is not at the conventional path keeps a line of its own
     * with the explicit pointer, as does one whose name has no prefix to share.
     *
     * <p>Grouping per line rather than hoisting one base over the whole index is what keeps the
     * source-set merges working: a line is self-contained, so the main and test rounds' lines can be
     * kept side by side even when both name one package, where a hoisted base differs per round.
     */
    private static List<IndexLine> indexLines(Platform platform, Set<TaggedElement> owners, RenderingContext context) {
        List<IndexLine> lines = new ArrayList<>();
        Map<String, IndexLine> groups = new LinkedHashMap<>();
        for (TaggedElement owner : owners) {
            String path = scopedPath(platform, owner, context);
            boolean conventional = path.equals(conventionalPath(platform, owner));
            String prefix = conventional ? prefixOf(owner) : null;
            if (prefix == null) {
                IndexLine single = new IndexLine(null, conventional ? null : path);
                single.names.add(owner.toString());
                lines.add(single);
                continue;
            }
            IndexLine group = groups.get(prefix);
            if (group == null) {
                group = new IndexLine(prefix, null);
                groups.put(prefix, group);
                lines.add(group);
            }
            group.names.add(owner.simpleName());
        }
        return lines;
    }

    /**
     * What precedes {@code owner}'s simple name, or {@code null} when nothing does. Derived from the
     * simple name rather than the last dot, so it is only ever a prefix that reproduces the
     * qualified name exactly when joined back with a dot.
     */
    private static @Nullable String prefixOf(TaggedElement owner) {
        String name = owner.qualifiedName();
        String simple = owner.simpleName();
        int cut = name.length() - simple.length() - 1;
        if (simple.isEmpty() || cut <= 0 || !name.endsWith("." + simple)) {
            return null;
        }
        return name.substring(0, cut);
    }

    /** One index line: owners sharing {@code prefix}, or with a {@code null} prefix one owner in full. */
    private static final class IndexLine {
        private final @Nullable String prefix;
        private final List<String> names = new ArrayList<>();
        private final @Nullable String rules;

        IndexLine(@Nullable String prefix, @Nullable String rules) {
            this.prefix = prefix;
            this.rules = rules;
        }
    }
}
