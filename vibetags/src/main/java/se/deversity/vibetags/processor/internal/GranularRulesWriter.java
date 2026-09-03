package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.GranularContribution;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.RoleConfig;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.internal.content.GranularSections;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
                                @Nullable RoleConfig roles) {
        return writeAll(elementRules, serviceFiles, activeServices, roles, Map.of());
    }

    /**
     * As above, but publishing every module's contribution to a file rather than only this
     * compilation's (<a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>).
     *
     * <p>A role declared in a reactor-root {@code .vibetags-roles} can match classes in several
     * modules, and all of them resolve the same output path — so each module's compile replaced the
     * file with its own classes alone and the siblings' guardrails vanished, silently and
     * nondeterministically (whichever module compiled last won). Two source sets of one module
     * collide the same way. {@code mergedByStem} carries what every sidecar recorded for that stem,
     * merged; a stem it does not mention keeps this compilation's own rendering, which is what makes
     * the single-module output byte-for-byte unchanged.
     *
     * @param mergedByStem stem → merged contribution, from {@code ModuleSidecar.mergeGranular}
     */
    public Set<String> writeAll(Map<TaggedElement, GranularBody> elementRules,
                                Map<String, Path> serviceFiles,
                                Set<String> activeServices,
                                @Nullable RoleConfig roles,
                                Map<String, GranularContribution> mergedByStem) {
        return write(elementRules, serviceFiles, activeServices, roles, "", List.of(), mergedByStem);
    }

    /**
     * Writes {@code elementRules} into <em>another</em> module's granular directories (issue #312).
     * Identical to {@link #writeAll} except that every filename carries {@code filePrefix} — which
     * namespaces the source module so two producers mirroring into the same target never collide or
     * clean up each other's files — and {@code extraGlobs} are appended to each file's frontmatter
     * so the mirrored rules actually match the target module's own sources.
     *
     * <p>No cross-module merge applies: the prefix already makes every mirrored stem unique to its
     * source module, so there is no shared file for two compilations to overwrite.
     *
     * @param filePrefix reserved filename prefix, e.g. {@code mirrored-payments-core-}
     * @param extraGlobs globs appended after the rule's own glob(s)
     * @return the prefixed stems written, for {@link #cleanupMirrored}
     */
    public Set<String> writeMirrored(Map<TaggedElement, GranularBody> elementRules,
                                     Map<String, Path> serviceFiles,
                                     Set<String> activeServices,
                                     @Nullable RoleConfig roles,
                                     String filePrefix,
                                     List<String> extraGlobs) {
        return write(elementRules, serviceFiles, activeServices, roles, filePrefix, extraGlobs, Map.of());
    }

    /**
     * What this compilation contributes to each granular rule file, keyed by stem — the globs its
     * frontmatter needs and the rendered body — without touching the filesystem.
     *
     * <p>Recorded in the module sidecar, where it does two jobs. Its <em>keys</em> are the filenames
     * this round will write, which sibling compilations read as cleanup exclusions — without them a
     * round deletes every rule file it did not itself write, which is every main-source rule file
     * when the round is {@code test-compile}
     * (<a href="https://github.com/PIsberg/vibetags/issues/330">issue #330</a>). Its <em>values</em>
     * are what those files should contain, so a file several modules write is merged rather than
     * replaced (issue #365).
     *
     * <p>Both come from {@link #plan}, the same method {@link #write} writes from, so a recorded
     * contribution can never name or describe a file that would have been written differently.
     */
    public static Map<String, GranularContribution> contributionsFor(
            Map<TaggedElement, GranularBody> elementRules, @Nullable RoleConfig roles) {
        Map<String, GranularContribution> contributions = new LinkedHashMap<>();
        plan(elementRules, roles, "", List.of())
            .forEach((stem, unit) -> contributions.put(stem, unit.content()));
        return contributions;
    }

    private Set<String> write(Map<TaggedElement, GranularBody> elementRules,
                              Map<String, Path> serviceFiles,
                              Set<String> activeServices,
                              @Nullable RoleConfig roles,
                              String filePrefix,
                              List<String> extraGlobs,
                              Map<String, GranularContribution> mergedByStem) {
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

        Map<String, Unit> units = plan(elementRules, roles, filePrefix, extraGlobs);
        noteStemsDifferingOnlyInCase(units.keySet());

        for (Map.Entry<String, Unit> planned : units.entrySet()) {
            String stem = planned.getKey();
            Unit unit = planned.getValue();
            writtenQNames.add(stem);
            // The merged view wins when there is one, because it already contains this module's own
            // contribution — the sidecar was written before it was read. Its globs are taken whole
            // rather than unioned with the local ones so that every module writing this file writes
            // the same bytes; a per-module union would order them per module and churn the diff.
            GranularContribution merged = mergedByStem.get(stem);
            List<String> globs = unit.content().globs();
            String body = unit.content().body();
            if (merged != null && !merged.isEmpty() && !merged.globs().isEmpty()) {
                globs = merged.globs();
                body = merged.body();
            }
            for (GranularFormat f : formats) {
                Path serviceDir = serviceFiles.get(f.serviceKey);
                if (serviceDir == null) {
                    continue;  // service not configured for this run: nothing to write
                }
                fileWriter.writeFileIfChanged(
                    serviceDir.resolve(stem + f.extension).toString(),
                    f.render(unit.description(), unit.displayName(), globs, body), true);
            }
        }

        return writtenQNames;
    }

    /**
     * Notes, informationally, that planned rule files differ only in capitalisation.
     *
     * <p>{@link #foldCaseCollisions} already made the collision lossless: every colliding stem
     * carries the same merged content, so whichever names a filesystem collapses, the surviving
     * file holds every element's guardrails. What remains true — and worth a NOTE rather than
     * silence — is that a case-insensitive filesystem holds one file where a case-sensitive one
     * holds several, so the same sources leave a different file <em>count</em> on different
     * machines, and the committed rules directory depends on who compiled it. A NOTE and not a
     * warning, because the build has handled it; a warning on handled behaviour is how a team
     * ends up muting the processor.
     */
    private void noteStemsDifferingOnlyInCase(Set<String> stems) {
        Map<String, List<String>> byFoldedCase = new LinkedHashMap<>();
        for (String stem : stems) {
            byFoldedCase.computeIfAbsent(stem.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(stem);
        }
        byFoldedCase.forEach((folded, colliding) -> {
            if (colliding.size() > 1) {
                fileWriter.note("VibeTags: granular rule files " + colliding + " differ only in "
                    + "capitalisation, so they are planned as one merged rule file written under "
                    + "each name - no guardrail is lost either way. A case-insensitive filesystem "
                    + "(the default on Windows and macOS) still holds one file where a "
                    + "case-sensitive one holds " + colliding.size() + ", so the committed rules "
                    + "directory depends on who compiled it. Rename one of the annotated elements, "
                    + "or route them into one role file, to make the layout identical everywhere.");
            }
        });
    }

    /**
     * Everything {@link #write} would write, as stem → file, with nothing rendered to disk. Split
     * out of the write so the same plan can be recorded in the sidecar (see
     * {@link #contributionsFor}) and so the merge has one definition of what a stem's content is.
     */
    private static Map<String, Unit> plan(Map<TaggedElement, GranularBody> elementRules,
                                          @Nullable RoleConfig roles,
                                          String filePrefix,
                                          List<String> extraGlobs) {
        // One name for "roles are on", non-null when they are: a separate boolean flag says the
        // same thing to a reader but nothing to the compiler, and every use below is in a lambda.
        final RoleConfig activeRoles = (roles != null && !roles.isEmpty()) ? roles : null;

        // Partition owners: role members (first-match, config order) vs. unmatched. Insertion order
        // is preserved so output stays deterministic (elementRules is a LinkedHashMap).
        Map<String, List<TaggedElement>> roleMembers = new LinkedHashMap<>();
        Map<TaggedElement, GranularBody> unmatched = new LinkedHashMap<>();
        elementRules.forEach((owner, body) -> {
            String role = activeRoles != null ? activeRoles.roleFor(owner).orElse(null) : null;
            if (role != null) {
                roleMembers.computeIfAbsent(role, k -> new ArrayList<>()).add(owner);
            } else {
                unmatched.put(owner, body);
            }
        });

        // A list, not a map keyed by stem: two elements can plan the same stem outright (the
        // nested com.example.Foo.Bar and the top-level com.example.Foo_Bar both become
        // com-example-Foo-Bar, since every non-alphanumeric character is a dash). Keyed by stem,
        // the second put replaced the first before foldCaseCollisions ever saw it, and one
        // element had no guardrail in any output while the index still pointed at the file.
        List<Map.Entry<String, Planned>> planned = new ArrayList<>();

        // Unmatched elements → one file per class/package (unchanged output).
        unmatched.forEach((owner, body) -> {
            String qName = filePrefix + owner.granularQName();
            List<String> globs = withExtra(List.of(defaultGlob(owner)), extraGlobs);
            planned.add(Map.entry(qName, new Planned(owner.simpleName(), String.valueOf(owner), globs,
                body.entries(),
                new Unit(owner.simpleName(), "AI rules for " + owner,
                    new GranularContribution(globs, body.toString().trim())))));
        });

        // Role members → one grouped, human-named file per role. Grouped by the stem first,
        // because the stem is what a file is: RoleConfig.sanitize maps everything a filename
        // cannot carry to a dash, so "api endpoints" and "api-endpoints" are two roles with one
        // filename. Planning them one at a time let the second overwrite the first and the first
        // role's classes lost their rule file with nothing said, while the scoped-rules index went
        // on pointing them at the survivor. A file several producers write is merged, never
        // replaced — the same answer the multi-module case gives (issue #365).
        Map<String, Map<String, List<TaggedElement>>> byStem = new LinkedHashMap<>();
        roleMembers.forEach((roleName, members) ->
            byStem.computeIfAbsent(filePrefix + RoleConfig.sanitize(roleName), k -> new LinkedHashMap<>())
                  .put(roleName, members));

        byStem.forEach((stem, rolesInFile) -> {
            List<TaggedElement> members = new ArrayList<>();
            Set<String> globs = new LinkedHashSet<>();
            rolesInFile.forEach((roleName, ofRole) -> {
                members.addAll(ofRole);
                List<String> declared = activeRoles == null ? List.of() : activeRoles.globsFor(roleName);
                if (declared.isEmpty()) {
                    // Role defined only by FQNs — derive globs from its own members' class/package
                    // globs. Per role rather than per stem: a role with no globs sharing a filename
                    // with one that has them still needs its members reachable.
                    for (TaggedElement m : ofRole) {
                        globs.add(defaultGlob(m));
                    }
                } else {
                    globs.addAll(declared);
                }
            });
            List<String> fileGlobs = withExtra(new ArrayList<>(globs), extraGlobs);
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
            // One name is the name; several are all named, so the heading says which config lines
            // feed the file rather than picking one and looking like the others were dropped.
            String displayName = String.join(", ", rolesInFile.keySet());
            planned.add(Map.entry(stem, new Planned(displayName, "role " + displayName, fileGlobs, stanzas,
                new Unit(displayName, "AI rules for role " + displayName,
                    new GranularContribution(fileGlobs, GranularSections.render(stanzas, true).trim())))));
        });

        return foldCaseCollisions(planned);
    }

    /**
     * Plans whose stems differ only in capitalisation become one merged rule file written under
     * <em>each</em> of the colliding names (<a
     * href="https://github.com/PIsberg/vibetags/issues/510">issue #510</a>).
     *
     * <p>On a case-insensitive filesystem — the default on Windows and macOS — the colliding names
     * are one physical file, and before this fold the second write landed on the first: one
     * element's guardrails were gone while the scoped-rules index still pointed at them. Writing
     * the merged content under every colliding name is the one layout that loses nothing anywhere
     * and changes no stem, so {@code RoleConfig.granularStemFor} stays the single source of truth
     * and the index cannot drift. The colliding files are byte-identical on purpose: on a
     * case-insensitive filesystem the later writes then match the first byte for byte and skip,
     * and the file every index entry reaches carries every element that resolves to it. The same
     * answer the role-name collision above and the multi-module merge (issue #365) already give:
     * a file several producers write is merged, never replaced.
     *
     * <p>A plan with no case collisions — every ordinary build — takes the singleton branch for
     * every stem and produces byte-for-byte what it produced before the fold existed.
     */
    private static Map<String, Unit> foldCaseCollisions(List<Map.Entry<String, Planned>> planned) {
        // Equal stems fold here too, the same way: they are the limiting case of a case
        // collision, one physical file on every filesystem, and the merged unit written under
        // the one name carries every element that resolves to it.
        Map<String, List<Map.Entry<String, Planned>>> byFoldedCase = new LinkedHashMap<>();
        for (Map.Entry<String, Planned> entry : planned) {
            byFoldedCase.computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(entry);
        }

        Map<String, Unit> units = new LinkedHashMap<>();
        for (List<Map.Entry<String, Planned>> colliding : byFoldedCase.values()) {
            if (colliding.size() == 1) {
                units.put(colliding.get(0).getKey(), colliding.get(0).getValue().rendered());
                continue;
            }
            List<String> displayNames = new ArrayList<>();
            List<String> subjects = new ArrayList<>();
            Set<String> globs = new LinkedHashSet<>();
            List<GranularBody.Entry> stanzas = new ArrayList<>();
            for (Map.Entry<String, Planned> entry : colliding) {
                Planned p = entry.getValue();
                displayNames.add(p.displayName());
                subjects.add(p.subject());
                globs.addAll(p.globs());
                stanzas.addAll(p.stanzas());
            }
            // Qualified rendering, like a role file: the file now covers several elements, so each
            // stanza's heading must say which fully-qualified element it binds.
            Unit merged = new Unit(String.join(", ", displayNames),
                "AI rules for " + String.join(", ", subjects),
                new GranularContribution(new ArrayList<>(globs),
                    GranularSections.render(stanzas, true).trim()));
            for (Map.Entry<String, Planned> entry : colliding) {
                units.put(entry.getKey(), merged);
            }
        }
        return units;
    }

    /** One planned granular file: its frontmatter description, its heading name, and its content. */
    private record Unit(String displayName, String description, GranularContribution content) {}

    /**
     * A planned file before case-collision folding: the {@link Unit} exactly as the pre-fold code
     * rendered it (what singletons emit, byte for byte), plus the raw ingredients — stanzas, globs,
     * naming — a merged unit is rebuilt from when stems collide case-insensitively.
     */
    private record Planned(String displayName, String subject, List<String> globs,
                           List<GranularBody.Entry> stanzas, Unit rendered) {}

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
     * Removes the rule files for {@code stems} from every active granular directory, whatever the
     * round's jurisdiction over that directory otherwise is.
     *
     * <p>Named stems only, and that is the whole difference from {@link #cleanupAll}. The sweep
     * cannot run from a reactor module round because it argues from absence — a file nothing has
     * claimed might belong to a sibling whose sidecar this round has not been shown, which is how a
     * cold clone lost 256 tracked rule files (issue #383). These stems are the opposite kind of
     * evidence: they were read out of a sidecar that named them, whose module directory is now
     * gone. That is a positive statement that the module which wrote them has left the build, and
     * it does not get stronger by waiting for the root to compile.
     *
     * <p>The caller is responsible for excluding stems a surviving module still claims, so a role
     * file shared by several modules is never deleted here — it is rewritten without the departed
     * module's share by the ordinary merge.
     *
     * @return the stems whose files were actually removed, sorted, for the destructive-sweep report
     */
    public Set<String> removeStems(Map<String, Path> serviceFiles, Set<String> activeServices,
                                   Set<String> stems) {
        Set<String> removed = new LinkedHashSet<>();
        if (stems.isEmpty()) {
            return removed;
        }
        for (GranularFormat f : FORMATS) {
            Path dir = serviceFiles.get(f.serviceKey);
            if (dir == null || !activeServices.contains(f.serviceKey)) {
                continue;
            }
            for (String stem : stems) {
                // Through the writer, never Files.deleteIfExists: the writer invalidates the
                // cache entry (a recorded file that is missing pins the short-circuit off) and,
                // in dry-run, reports the removal instead of performing it.
                if (fileWriter.deleteIfExists(dir.resolve(stem + f.extension))) {
                    removed.add(stem);
                }
            }
        }
        return removed;
    }

    /**
     * Removes orphaned granular files for the active platforms, skipping {@code excludeQNames}
     * (the per-class qNames and role stems just written this round).
     */
    public Set<String> cleanupAll(Map<String, Path> serviceFiles, Set<String> activeServices, Set<String> excludeQNames) {
        Set<String> removed = new LinkedHashSet<>();
        for (GranularFormat f : FORMATS) {
            Path dir = serviceFiles.get(f.serviceKey);
            if (dir != null && activeServices.contains(f.serviceKey)) {
                removed.addAll(fileWriter.cleanupGranularDirectory(dir, f.extension, excludeQNames));
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
            Path dir = serviceFiles.get(f.serviceKey);
            if (dir != null && activeServices.contains(f.serviceKey)) {
                fileWriter.cleanupGranularDirectory(dir, f.extension, excludeQNames, filePrefix);
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

    // YAML-frontmatter builders, keyed by the shape each platform uses. Methods rather than lambdas
    // in constants: the method reference reads the same at the use site and the body gets a name.
    private static String fmDescGlobsApply(String desc, List<String> globs) {
        return "---\ndescription: \"" + desc + "\"\nglobs: " + arr(globs) + "\nalwaysApply: false\n---\n\n";
    }

    /** No front matter at all — the platform reads the file by path, not by a globs declaration. */
    private static String fmNone(String desc, List<String> globs) {
        return "";
    }

    // Order = historical per-class write order.
    private static final List<GranularFormat> FORMATS = List.of(
        new GranularFormat("cursor_granular", ".mdc", GranularRulesWriter::fmDescGlobsApply, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("trae_granular", ".md",
            (desc, globs) -> "---\nalwaysApply: false\nglobs: " + arr(globs) + "\ndescription: \"" + desc + "\"\n---\n\n",
            n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("roo_granular", ".md", GranularRulesWriter::fmNone, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("windsurf_granular", ".md", GranularRulesWriter::fmDescGlobsApply, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("continue_granular", ".md", GranularRulesWriter::fmDescGlobsApply, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("tabnine_granular", ".md", GranularRulesWriter::fmNone, n -> "# AI Guidelines for " + n + "\n\n"),
        new GranularFormat("amazonq_granular", ".md", GranularRulesWriter::fmNone, n -> "# Amazon Q Rules for " + n + "\n\n"),
        new GranularFormat("ai_rules_granular", ".md", GranularRulesWriter::fmNone, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("pearai_granular", ".md", GranularRulesWriter::fmDescGlobsApply, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("kiro_granular", ".md", GranularRulesWriter::fmNone, n -> "# Amazon Kiro Steering: " + n + "\n\n"),
        new GranularFormat("gemini_granular", ".md", GranularRulesWriter::fmNone, n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("claude_granular", ".md",
            (desc, globs) -> "---\npaths: " + arr(globs) + "\n---\n\n",
            n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("copilot_granular", ".instructions.md",
            (desc, globs) -> "---\napplyTo: \"" + String.join(",", globs) + "\"\n---\n\n",
            n -> "# Copilot Instructions for " + n + "\n\n")
    );
}
