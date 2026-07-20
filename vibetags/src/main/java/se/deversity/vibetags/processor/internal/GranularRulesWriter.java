package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIContext;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
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
    public Set<String> writeAll(Map<Element, StringBuilder> elementRules,
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
    public Set<String> writeAll(Map<Element, StringBuilder> elementRules,
                                Map<String, Path> serviceFiles,
                                Set<String> activeServices,
                                RoleConfig roles) {
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
        Map<String, List<Element>> roleMembers = new LinkedHashMap<>();
        Map<Element, StringBuilder> unmatched = new LinkedHashMap<>();
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
            String qName = ElementNaming.granularQName(owner);
            writtenQNames.add(qName);
            String simpleName = owner.getSimpleName().toString();
            String description = "AI rules for " + owner;
            List<String> globs = List.of(defaultGlob(owner));
            String content = body.toString().trim();
            for (GranularFormat f : formats) {
                fileWriter.writeFileIfChanged(
                    serviceFiles.get(f.serviceKey).resolve(qName + f.extension).toString(),
                    f.render(description, simpleName, globs, content), true);
            }
        });

        // Role members → one grouped, human-named file per role.
        roleMembers.forEach((roleName, members) -> {
            String stem = sanitize(roleName);
            writtenQNames.add(stem);
            List<String> globs = roles.globsFor(roleName);
            if (globs.isEmpty()) {
                // Role defined only by FQNs — derive globs from the members' own class/package globs.
                Set<String> derived = new LinkedHashSet<>();
                for (Element m : members) {
                    derived.add(defaultGlob(m));
                }
                globs = new ArrayList<>(derived);
            }
            StringBuilder body = new StringBuilder();
            for (Element m : members) {
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append("## ").append(m).append("\n\n").append(elementRules.get(m).toString().trim());
            }
            String description = "AI rules for role " + roleName;
            String content = body.toString();
            for (GranularFormat f : formats) {
                fileWriter.writeFileIfChanged(
                    serviceFiles.get(f.serviceKey).resolve(stem + f.extension).toString(),
                    f.render(description, roleName, globs, content), true);
            }
        });

        return writtenQNames;
    }

    private static String defaultGlob(Element owner) {
        String simpleName = owner.getSimpleName().toString();
        return owner.getKind() == ElementKind.PACKAGE
            ? "**/" + simpleName + "/**/*.java"
            : "**/" + simpleName + ".java";
    }

    /** Role names are user-authored; keep filenames to filesystem-safe characters. */
    private static String sanitize(String roleName) {
        return roleName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static String arr(List<String> globs) {
        return "[\"" + String.join("\", \"", globs) + "\"]";
    }

    /**
     * Removes orphaned granular files for the active platforms, skipping {@code excludeQNames}
     * (the per-class qNames and role stems just written this round).
     */
    public void cleanupAll(Map<String, Path> serviceFiles, Set<String> activeServices, Set<String> excludeQNames) {
        for (GranularFormat f : FORMATS) {
            if (activeServices.contains(f.serviceKey)) {
                fileWriter.cleanupGranularDirectory(serviceFiles.get(f.serviceKey), f.extension, excludeQNames);
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
        new GranularFormat("claude_granular", ".md",
            (desc, globs) -> "---\npaths: " + arr(globs) + "\n---\n\n",
            n -> "# Rules for " + n + "\n\n"),
        new GranularFormat("copilot_granular", ".instructions.md",
            (desc, globs) -> "---\napplyTo: \"" + String.join(",", globs) + "\"\n---\n\n",
            n -> "# Copilot Instructions for " + n + "\n\n")
    );
}
