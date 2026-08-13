package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inherited-guardrail appendix: what it says, where it goes, and where it must not go. */
class TransitiveSectionTest {

    private static TransitiveRule rule(String pkg, String label, String origin, String note) {
        return new TransitiveRule(origin, pkg, label,
            label.equals("@AISecure") || label.equals("@AICore")
                ? TransitiveRule.Tier.SAFETY : TransitiveRule.Tier.ADVISORY,
            note.isEmpty() ? Map.of() : Map.of("note", note));
    }

    private static GuardrailModel modelWith(TransitiveRule... rules) {
        GuardrailModel.Builder b = GuardrailModel.builder();
        for (TransitiveRule r : rules) {
            b.transitiveRule(r);
        }
        return b.build();
    }

    @Test
    void namesTheArtifactEveryRuleCameFrom() {
        // A dependency is contributing text to instructions an agent will act on. A reader who
        // cannot see whose text it is has no way to judge it.
        String out = TransitiveSection.render(
            modelWith(rule("com.acme.api", "@AISecure", "com.acme:crypto-core:2.4.0", "use the factory")),
            Platform.CLAUDE);
        assertTrue(out.contains("com.acme:crypto-core:2.4.0"), out);
        assertTrue(out.contains("com.acme.api"), out);
        assertTrue(out.contains("@AISecure"), out);
        assertTrue(out.contains("use the factory"), out);
    }

    @Test
    void anUnattributedRuleStillRendersWithoutAnEmptyParenthesis() {
        String out = TransitiveSection.render(
            modelWith(rule("com.acme.api", "@AISecure", "", "x")), Platform.CLAUDE);
        assertFalse(out.contains("(from )"), "an unset origin must not render as an empty attribution:\n" + out);
        assertTrue(out.contains("com.acme.api"), out);
    }

    @Test
    void safetyRulesComeBeforeAdvisoryOnes() {
        String out = TransitiveSection.render(modelWith(
            rule("com.acme.api", "@AIPerformance", "o", "advice"),
            rule("com.acme.api", "@AISecure", "o", "constraint")), Platform.CLAUDE);
        int safety = out.indexOf(TransitiveSection.SAFETY_HEADING);
        int advisory = out.indexOf(TransitiveSection.ADVISORY_HEADING);
        assertTrue(safety >= 0 && advisory >= 0, out);
        assertTrue(safety < advisory, "the tier that binds must be read first:\n" + out);
    }

    @Test
    void omitsAHeadingWhoseTierHasNoRules() {
        String safetyOnly = TransitiveSection.render(
            modelWith(rule("com.acme.api", "@AISecure", "o", "x")), Platform.CLAUDE);
        assertFalse(safetyOnly.contains(TransitiveSection.ADVISORY_HEADING), safetyOnly);

        String advisoryOnly = TransitiveSection.render(
            modelWith(rule("com.acme.api", "@AIPerformance", "o", "x")), Platform.CLAUDE);
        assertFalse(advisoryOnly.contains(TransitiveSection.SAFETY_HEADING), advisoryOnly);
    }

    @Test
    void groupsRulesUnderTheirPackageWithoutRepeatingTheHeader() {
        String out = TransitiveSection.render(modelWith(
            rule("com.acme.api", "@AISecure", "o", "one"),
            rule("com.acme.api", "@AICore", "o", "two")), Platform.CLAUDE);
        long headers = out.lines().filter(l -> l.startsWith("- `com.acme.api`")).count();
        assertEquals(1, headers, "one package should print one header:\n" + out);
    }

    @Test
    void twoArtifactsPublishingTheSamePackageAreBothAttributed() {
        // A split package: reachable when a build combines -Avibetags.manifest.dir with classpath
        // discovery, since both feed the reader additively and TransitiveRule.equals includes the
        // origin, so neither rule deduplicates the other away. They sort adjacent because the
        // comparator orders by package first, which is exactly when grouping on the package alone
        // would print the second rule under the first one's artifact.
        String out = TransitiveSection.render(modelWith(
            rule("com.acme.api", "@AISecure", "com.acme:core:1.0", "from acme"),
            rule("com.acme.api", "@AISecure", "org.other:lib:2.0", "from other")), Platform.CLAUDE);

        assertTrue(out.contains("com.acme:core:1.0"), out);
        assertTrue(out.contains("org.other:lib:2.0"),
            "the second artifact must not vanish into the first one's header:\n" + out);

        // Each rule must sit under its own artifact, not merely have it mentioned somewhere.
        int otherHeader = out.indexOf("(from org.other:lib:2.0)");
        int otherRule = out.indexOf("from other");
        int acmeRule = out.indexOf("from acme");
        assertTrue(otherHeader >= 0 && otherHeader < otherRule,
            "org.other's rule must follow its own header:\n" + out);
        assertTrue(acmeRule < otherHeader,
            "acme's rule must stay above org.other's header:\n" + out);
    }

    @Test
    void oneArtifactPerPackageStillPrintsOneHeader() {
        // The other half: repeating the header for every rule would be noise. Grouping is on
        // (package, origin), so an ordinary single-origin package is unchanged.
        String out = TransitiveSection.render(modelWith(
            rule("com.acme.api", "@AISecure", "com.acme:core:1.0", "one"),
            rule("com.acme.api", "@AICore", "com.acme:core:1.0", "two")), Platform.CLAUDE);
        assertEquals(1, out.lines().filter(l -> l.startsWith("- `com.acme.api`")).count(), out);
    }

    @Test
    void collapsesMultiLineAttributesOntoOneBullet() {
        // A text-block attribute is legal on the publishing side. Emitted raw, its second line
        // would leave the markdown list and become prose attributed to nobody.
        String out = TransitiveSection.render(
            modelWith(rule("com.acme.api", "@AISecure", "o", "first line\n  second line\n\nthird")),
            Platform.CLAUDE);
        assertTrue(out.contains("first line second line third"), out);
        long bullets = out.lines().filter(l -> l.startsWith("  - @AISecure")).count();
        assertEquals(1, bullets, "the rule must stay one bullet:\n" + out);
    }

    @Test
    void oneLineCollapsesEveryWhitespaceRun() {
        assertEquals("a b c", TransitiveSection.oneLine("  a\n\n\tb   c  "));
    }

    @Test
    void rendersNothingWithoutTransitiveRules() {
        assertEquals("", TransitiveSection.render(GuardrailModel.EMPTY, Platform.CLAUDE),
            "a project that inherited nothing must render byte-identically to before this feature");
    }

    @Test
    void rendersNothingForAPlatformThatDoesNotCarryTheAppendix() {
        GuardrailModel model = modelWith(rule("com.acme.api", "@AISecure", "o", "x"));
        assertEquals("", TransitiveSection.render(model, Platform.CODERABBIT),
            "a YAML config must not receive a markdown heading");
        assertEquals("", TransitiveSection.render(model, Platform.CURSOR_IGNORE),
            "an ignore file is a path list; a heading in one is a path");
    }

    // ------------------------------------------------------------------ the platform list itself

    @Test
    void theCarryingPlatformsAreExactlyTheDeclaredList() {
        // Pinned deliberately. Adding a platform to the set is a decision about which file a
        // dependency's words appear in, not a refactor, so it should fail here first.
        assertEquals(Set.of(
                Platform.CLAUDE, Platform.CLAUDE_LOCAL, Platform.CURSOR, Platform.CODEX,
                Platform.GEMINI, Platform.GEMINI_MD, Platform.COPILOT, Platform.QWEN,
                Platform.LLMS_FULL, Platform.AIDER_CONVENTIONS, Platform.WINDSURF, Platform.ZED,
                Platform.CLINE, Platform.JUNIE, Platform.FIREBASE, Platform.VOID),
            Set.copyOf(TransitiveSection.PLATFORMS));
    }

    @Test
    void everyCarryingPlatformWritesAMarkdownOrFreeTextFile() {
        // Derived from the service registry rather than restated: appending markdown to a JSON,
        // YAML or TOML document produces a file a strict parser rejects, and this is the check
        // that notices if the list ever grows to include one.
        Map<String, Path> files = ServiceRegistry.buildServiceFileMap(Paths.get("."));
        // Extensions whose content a parser reads structurally. Zed's `.rules` and Codex's
        // `vibetags.rules` are plain text despite the name, so the extension is not on this list.
        List<String> structured = List.of(".json", ".yaml", ".yml", ".toml");
        List<String> problems = new java.util.ArrayList<>();
        for (Platform platform : TransitiveSection.PLATFORMS) {
            Path file = files.get(platform.getServiceKey());
            assertTrue(file != null, platform + " has no registered output file");
            String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            for (String ext : structured) {
                if (name.endsWith(ext)) {
                    problems.add(platform + " writes " + name + ", which a markdown appendix would corrupt");
                }
            }
            if (name.contains("ignore")) {
                problems.add(platform + " writes the ignore file " + name + ", which is a path list");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n  ", problems));
    }

    @Test
    void everyCarryingPlatformActuallyEmitsTheBlock() {
        // carries() and render() agreeing is the whole contract; a platform in the list that
        // renders nothing would be a silently missing file, not a visible failure.
        GuardrailModel model = modelWith(rule("com.acme.api", "@AISecure", "com.acme:x:1", "note"));
        for (Platform platform : TransitiveSection.PLATFORMS) {
            String out = TransitiveSection.render(model, platform);
            assertTrue(out.contains(TransitiveSection.SAFETY_HEADING),
                platform + " is listed as carrying the appendix but rendered: '" + out + "'");
        }
    }

    @Test
    void noPlatformOutsideTheListEmitsTheBlock() {
        GuardrailModel model = modelWith(rule("com.acme.api", "@AISecure", "com.acme:x:1", "note"));
        for (Platform platform : Platform.values()) {
            if (TransitiveSection.PLATFORMS.contains(platform)) {
                continue;
            }
            assertEquals("", TransitiveSection.render(model, platform),
                platform + " is not in the carrying list but rendered content");
        }
    }
}
