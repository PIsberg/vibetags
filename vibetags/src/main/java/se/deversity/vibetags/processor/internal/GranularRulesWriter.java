package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.RoleConfig;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.internal.content.GranularSections;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Writes granular rule files for Cursor (.mdc), Claude, Windsurf, Copilot, Trae, Roo, and similar
 * platforms. By default each annotated class/package becomes one file in the platform's rules
 * directory. When a {@link RoleConfig} is supplied (a {@code .vibetags-roles} config is present),
 * elements matching a role are instead grouped into one human-named file per role (e.g.
 * {@code api-endpoints.md}); elements matching no role keep their per-class file.
 *
 * <p>Returns the set of qNames/role-stems written so the caller can pass them to
 * {@link GuardrailFileWriter#cleanupGranularDirectory(Path, String, Set)} as the exclude list,
 * preventing a delete-then-recreate cycle on each compile.
 */
@AIContext(
    focus = "Writes granular rule files (per-class, or role-grouped when .vibetags-roles is present) for Cursor, Windsurf, Trae, Roo, and similar platforms; cleanup runs AFTER write to avoid delete-then-recreate cycles",
    avoids = "Running cleanup before write — would delete files that are about to be recreated, causing spurious filesystem events and empty windows for incremental build tools"
)
public final class GranularRulesWriter {

    private final GuardrailFileWriter fileWriter;

    public GranularRulesWriter(GuardrailFileWriter fileWriter) {
        this.fileWriter = fileWriter;
    }

    /** Per-class granular writing (no role routing). */
    public Set<String> writeAll(Map<TaggedElement, GranularBody> elementRules,
                                Map<String, Path> serviceFiles,
                                Set<String> activeServices) {
        return writeAll(elementRules, serviceFiles, activeServices, null);
    }

    /**
     * Writes granular rule files for all active platforms.
     *
     * @param elementRules    map of owning class/package element → accumulated rules markdown
     * @param serviceFiles    service-key → directory path map
     * @param activeServices  currently-active services (controls which platforms get files)
     * @param roles           role routing config, or {@code null}/empty for per-class behavior
     * @return qNames (per-class) and role stems (filename minus extension) of files just written
     */
    public Set<String> writeAll(Map<TaggedElement, GranularBody> elementRules,
                                Map<String, Path> serviceFiles,
                                Set<String> activeServices,
                                RoleConfig roles) {
        return write(elementRules, serviceFiles, activeServices, roles, "", List.of());
    }

    /**
     * Writes {@code elementRules} into <em>another</em> module's granular directories (issue #312).
     * Identical to {@link #writeAll} except that every filename carries {@code filePrefix} — which
     * namespaces the source module so two producers mirroring into the same target never collide or
     * clean up each other's files — and {@code extraGlobs} are appended to each file's frontmatter
     * so the mirrored rules actually match the target module's own sources.
     *
     * @param filePrefix reserved filename prefix, e.g. {@code mirrored-payments-core-}
     * @param extraGlobs globs appended after the rule's own glob(s)
     * @return the prefixed stems written, for {@link #cleanupMirrored}
     */
    public Set<String> writeMirrored(Map<TaggedElement, GranularBody> elementRules,
                                     Map<String, Path> serviceFiles,
                                     Set<String> activeServices,
                                     RoleConfig roles,
                                     String filePrefix,
                                     List<String> extraGlobs) {
        return write(elementRules, serviceFiles, activeServices, roles, filePrefix, extraGlobs);
    }

    private Set<String> write(Map<TaggedElement, GranularBody> elementRules,
                              Map<String, Path> serviceFiles,
                              Set<String> activeServices,
                              RoleConfig roles,
                              String filePrefix,
                              List<String> extraGlobs) {
        Set<String> writtenQNames = new LinkedHashSet<>();
        List<GranularFormat> formats = new ArrayList<>();
        for (GranularFormat f : FORMATS) {
            if (activeServices.contains(f.serviceKey)) {
                formats.add(f);
            }
        }
        if (formats.isEmpty()) {
            return writtenQNames;
        }

        boolean rolesActive = roles != null && !roles.isEmpty();

        // Partition owners: role members (first-match, config order) vs. unmatched. Insertion order
        // is preserved so output stays deterministic (elementRules is a LinkedHashMap).
        Map<String, List<TaggedElement>> roleMembers = new LinkedHashMap<>();
        Map<TaggedElement, GranularBody> unmatched = new LinkedHashMap<>();
        elementRules.forEach((owner, body) -> {
            String role = rolesActive ? roles.roleFor(owner).orElse(null) : null;
            if (role != null) {
                roleMembers.computeIfAbsent(role, k -> new ArrayList<>()).add(owner);
            } else {
                unmatched.put(owner, body);
            }
        });

        // Unmatched elements → one file per class/package (unchanged output).
        unmatched.forEach((owner, body) -> {
            String qName = filePrefix + owner.granularQName();
            writtenQNames.add(qName);
            String simpleName = owner.simpleName();
            String description = "AI rules for " + owner;
            List<String> globs = withExtra(List.of(defaultGlob(owner)), extraGlobs);
            String content = body.toString().trim();
            for (GranularFormat f : formats) {
                Path serviceDir = serviceFiles.get(f.serviceKey);
                if (serviceDir == null) {
                    continue;  // service not configured for this run: nothing to write
                }
                fileWriter.writeFileIfChanged(
                    serviceDir.resolve(qName + f.extension).toString(),
                    f.render(description, simpleName, globs, content), true);
            }
        });

        // Role members → one grouped, human-named file per role.
        roleMembers.forEach((roleName, members) -> {
            String stem = filePrefix + RoleConfig.sanitize(roleName);
            writtenQNames.add(stem);
            List<String> globs = roles.globsFor(roleName);
            if (globs.isEmpty()) {
                // Role defined only by FQNs — derive globs from the members' own class/package globs.
                Set<String> derived = new LinkedHashSet<>();
                for (TaggedElement m : members) {
                    derived.add(defaultGlob(m));
                }
                globs = new ArrayList<>(derived);
            }
            globs = withExtra(globs, extraGlobs);
            // A role file spans several owners, so its stanzas are re-rendered together in
            // qualified mode: organised by topic (section) with fully-qualified element headings,
            // and with each section's shared rule sentence hoisted once instead of repeated per
            // element (issue #313).
            List<GranularBody.Entry> stanzas = new ArrayList<>();
            for (TaggedElement m : members) {
                GranularBody memberBody = elementRules.get(m);
                if (memberBody != null) {
                    stanzas.addAll(memberBody.entries());
                }
            }
            String description = "AI rules for role " + roleName;
            String content = GranularSections.render(stanzas, true).trim();
            for (GranularFormat f : formats) {
                Path serviceDir = serviceFiles.get(f.serviceKey);
                if (serviceDir == null) {
                    continue;  // service not configured for this run: nothing to write
                }
                fileWriter.writeFileIfChanged(
                    serviceDir.resolve(stem + f.extension).toString(),
                    f.render(description, roleName, globs, content), true);
            }
        });

        return writtenQNames;
    }

    /**
     * The stems {@link #writeAll} would write for {@code elementRules} under {@code roles}, without
     * touching the filesystem.
     *
     * <p>Exists so a compilation can record its own granular filenames <em>before</em> it writes
     * them: they go into the module sidecar, where sibling compilations read them as cleanup
     * exclusions. Without that, a round deletes every rule file it did not itself write — which is
     * every main-source rule file, when the round is {@code test-compile}
     * (<a href="https://github.com/PIsberg/vibetags/issues/330">issue #330</a>).
     *
     * <p>Deliberately a pure function of the same inputs {@code write} uses, so the recorded stems
     * can never name a file that was not written.
     */
    public static Set<String> stemsFor(Map<TaggedElement, GranularBody> elementRules, RoleConfig roles) {
        Set<String> stems = new LinkedHashSet<>();
        boolean rolesActive = roles != null && !roles.isEmpty();
        elementRules.keySet().forEach(owner -> {
            String role = rolesActive ? roles.roleFor(owner).orElse(null) : null;
            stems.add(role != null ? RoleConfig.sanitize(role) : owner.granularQName());
        });
        return stems;
    }

    /**
     * The rule's own globs followed by any mirror globs, de-duplicated and order-preserving. Returns
     * {@code globs} untouched when there is nothing to add, so the ordinary single-glob per-class
     * frontmatter stays byte-for-byte identical.
     */
    private static List<String> withExtra(List<String> globs, List<String> extraGlobs) {
        if (extraGlobs.isEmpty()) {
            return globs;
        }
        Set<String> merged = new LinkedHashSet<>(globs);
        merged.addAll(extraGlobs);
        return new ArrayList<>(merged);
    }

    private static String defaultGlob(TaggedElement owner) {
        String simpleName = owner.simpleName();
        return owner.kind() == ElementTag.PACKAGE
            ? "**/" + simpleName + "/**/*.java"
            : "**/" + simpleName + ".java";
    }

    private static String arr(List<String> globs) {
        return "[\"" + String.join("\", \"", globs) + "\"]";
    }

    /**
     * Removes orphaned granular files for the active platforms, skipping {@code excludeQNames}
     * (the per-class qNames and role stems just written this round).
     */
    public Set<String> cleanupAll(Map<String, Path> serviceFiles, Set<String> activeServices, Set<String> excludeQNames) {
        Set<String> removed = new LinkedHashSet<>();
        for (GranularFormat f : FORMATS) {
            if (activeServices.contains(f.serviceKey)) {
                removed.addAll(
                    fileWriter.cleanupGranularDirectory(serviceFiles.get(f.serviceKey), f.extension, excludeQNames));
            }
        }
        return removed;
    }

    /**
     * Removes orphaned <em>mirrored</em> files for one source module, leaving the target module's
     * own rules and every other source module's mirrors alone. Scoped by {@code filePrefix}, which
     * is what lets modules of a reactor compile independently without deleting each other's output.
     */
    public void cleanupMirrored(Map<String, Path> serviceFiles, Set<String> activeServices,
                                String filePrefix, Set<String> excludeQNames) {
        for (GranularFormat f : FORMATS) {
            if (activeServices.contains(f.serviceKey)) {
                fileWriter.cleanupGranularDirectory(
                    serviceFiles.get(f.serviceKey), f.extension, excludeQNames, filePrefix);
            }
        }
    }

    /**
     * Per-platform granular file format: extension, frontmatter builder (from description + glob
     * list), and heading builder. A file is {@code frontmatter + heading + body}; the single-glob
     * case reproduces the historical per-class output byte-for-byte.
     */
    private static final class GranularFormat {
        final String serviceKey;
        final String extension;
        private final BiFunction<String, List<String>, String> frontmatter;
        private final Function<String, String> heading;

        GranularFormat(String serviceKey, String extension,
                       BiFunction<String, List<String>, String> frontmatter,
                       Function<String, String> heading) {
            this.serviceKey = serviceKey;
            this.extension = extension;
            this.frontmatter = frontmatter;
            this.heading = heading;
        }

        String render(String description, String displayName, List<String> globs, String body) {
            return frontmatter.apply(description, globs) + heading.apply(displayName) + body;
        }
    }

    // YAML-frontmatter builders, keyed by the shape each platform uses.
    private static final BiFunction<String, List<String>, String> FM_DESC_GLOBS_APPLY =
        (desc, globs) -> "---\ndescription: \"" + desc + "\"\nglobs: " + arr(globs) + "\nalwaysApply: false\n---\n\n";
    private static final BiFunction<String, List<String>, String> FM_NONE = (desc, globs) -> "";

    // Order = historical per-class write order.
    private static final List<GranularFormat> FORMATS = List.of(
        new GranularFormat("cursor_granular", ".mdc", FM_DESC_GLOBS_APPLY, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("trae_granular", ".md",
            (desc, globs) -> "---\nalwaysApply: false\nglobs: " + arr(globs) + "\ndescription: \"" + desc + "\"\n---\n\n",
            n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("roo_granular", ".md", FM_NONE, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("windsurf_granular", ".md", FM_DESC_GLOBS_APPLY, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("continue_granular", ".md", FM_DESC_GLOBS_APPLY, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("tabnine_granular", ".md", FM_NONE, n -> "# AI Guidelines for " + n + "\n\n"),
        new GranularFormat("amazonq_granular", ".md", FM_NONE, n -> "# Amazon Q Rules for " + n + "\n\n"),
        new GranularFormat("ai_rules_granular", ".md", FM_NONE, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("pearai_granular", ".md", FM_DESC_GLOBS_APPLY, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("kiro_granular", ".md", FM_NONE, n -> "# Amazon Kiro Steering: " + n + "\n\n"),
        new GranularFormat("gemini_granular", ".md", FM_NONE, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("claude_granular", ".md",
            (desc, globs) -> "---\npaths: " + arr(globs) + "\n---\n\n",
            n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("copilot_granular", ".instructions.md",
            (desc, globs) -> "---\napplyTo: \"" + String.join(",", globs) + "\"\n---\n\n",
            n -> "# Copilot Instructions for " + n + "\n\n")
    );
}
