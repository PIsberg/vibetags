package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Devin Desktop, formerly Windsurf: the {@code .devin/rules/} directory and {@code .devinignore}
 * (#671).
 *
 * <p>docs.devin.ai lists {@code .devin/rules/*.md} as "preferred" and {@code .windsurf/rules/*.md}
 * as the "fallback", and shows the front matter of a glob rule as {@code trigger: glob} followed by
 * a {@code globs:} pattern. The rule files here copy that shape. {@code .devinignore} takes "the
 * same syntax as {@code .gitignore}", so it gets the ignore-file glob.
 *
 * <p>The Windsurf outputs must not move: a project that adds {@code .devin/rules/} beside
 * {@code .windsurfrules} and {@code .windsurf/rules/} gets both of those byte for byte as before.
 */
@Tag("e2e")
class DevinDesktopEndToEndTest {

    private static final String LOCKED_SOURCE_V1 =
        "package com.example.payment;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"first reason\")\n"
            + "public class PaymentProcessor {}\n";

    private static final String LOCKED_SOURCE_V2 = LOCKED_SOURCE_V1.replace("first reason", "second reason");

    private static final String IGNORED_SOURCE =
        "package com.example.gen;\n"
            + "import se.deversity.vibetags.annotations.AIIgnore;\n"
            + "@AIIgnore\n"
            + "public class GeneratedMetadata {}\n";

    private static final String DEVIN_RULE = ".devin/rules/com-example-payment-PaymentProcessor.md";
    private static final String WINDSURF_RULE = ".windsurf/rules/com-example-payment-PaymentProcessor.md";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aDevinRuleCarriesTheDocumentedGlobTriggerFrontMatter(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
        h.compile();

        String rule = h.readFile(DEVIN_RULE);
        assertTrue(rule.startsWith("---\ntrigger: glob\nglobs: **/PaymentProcessor.java\n---\n"),
            "the front matter docs.devin.ai documents for a glob rule, and nothing else:\n" + rule);
        assertTrue(rule.contains("<!-- VIBETAGS-START -->") && rule.contains("first reason"),
            "the element guardrails between markers:\n" + rule);
    }

    /**
     * The glob value is unquoted, as in the vendor example, and a leading {@code *} is not valid
     * YAML to a strict parser. The front-matter reader in GuardrailFileWriter must still see it as
     * a header on the next write, or a rebuild would stack a second header above the first.
     */
    @Test
    void aRebuildRefreshesTheRuleWithoutStackingAHeader(@TempDir Path root) throws Exception {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
        h.compile();
        VibeTagsLogger.shutdown();

        ProcessorTestHarness.awaitFilesystemTick(root);
        h.clearSources();
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V2);
        h.compile();

        String rule = h.readFile(DEVIN_RULE);
        assertTrue(rule.startsWith("---\ntrigger: glob\n"), rule);
        assertEquals(1, rule.split("trigger: glob", -1).length - 1, "exactly one header:\n" + rule);
        assertTrue(rule.contains("second reason") && !rule.contains("first reason"), rule);
    }

    /** A role file with several globs: the docs show no list form, so they are joined with commas. */
    @Test
    void aRoleFileJoinsItsGlobsWithCommas(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".devin/rules"));
        Files.writeString(root.resolve(".vibetags-roles"), "web = **/*Controller.java, **/*Endpoint.java\n");
        h.addSource("com.example.web.OrderController",
            "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderController {}\n");
        h.addSource("com.example.web.OrderEndpoint",
            "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderEndpoint {}\n");
        h.compile();

        String role = h.readFile(".devin/rules/web.md");
        assertTrue(role.startsWith("---\ntrigger: glob\nglobs: **/*Controller.java,**/*Endpoint.java\n---\n"), role);
    }

    /** The assertion the ignore formatter default arm would fail: a glob, not just a header. */
    @Test
    void devinIgnoreCarriesTheGlobForAnIgnoredElement(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn(".devinignore");
        h.addSource("com.example.gen.GeneratedMetadata", IGNORED_SOURCE);
        h.compile();

        String ignore = h.readFile(".devinignore");
        assertTrue(ignore.contains("# Devin Desktop-specific exclusion list."), ignore);
        assertTrue(ignore.lines().anyMatch("**/GeneratedMetadata.java"::equals),
            "a .gitignore-syntax glob on a line of its own:\n" + ignore);
    }

    @Test
    void addingDevinRulesLeavesEveryWindsurfOutputByteForByte(@TempDir Path base) throws IOException {
        Path windsurfOnly = base.resolve("windsurf-only");
        Path withDevin = base.resolve("with-devin");
        for (Path root : List.of(windsurfOnly, withDevin)) {
            ProcessorTestHarness h = new ProcessorTestHarness(root, false);
            h.touchOptIn(".windsurfrules");
            Files.createDirectories(root.resolve(".windsurf/rules"));
            if (root.equals(withDevin)) {
                Files.createDirectories(root.resolve(".devin/rules"));
            }
            h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
            h.addSource("com.example.gen.GeneratedMetadata", IGNORED_SOURCE);
            h.compile();
            VibeTagsLogger.shutdown();
        }

        assertTrue(Files.exists(withDevin.resolve(DEVIN_RULE)), "the Devin rule is written beside the Windsurf ones");
        assertEquals(read(windsurfOnly, ".windsurfrules"), read(withDevin, ".windsurfrules"),
            ".windsurfrules must not change when .devin/rules/ is opted in");
        List<String> names = listing(windsurfOnly.resolve(".windsurf/rules"));
        assertEquals(names, listing(withDevin.resolve(".windsurf/rules")));
        assertTrue(names.contains("com-example-payment-PaymentProcessor.md"), names.toString());
        for (String name : names) {
            assertEquals(read(windsurfOnly, ".windsurf/rules/" + name), read(withDevin, ".windsurf/rules/" + name),
                name + " must not change when .devin/rules/ is opted in");
        }
        assertFalse(Files.exists(windsurfOnly.resolve(".devin")), "no .devin/ without the opt-in (invariant 1)");
    }

    /**
     * Both directories opted in duplicate the guardrails: the Devin CLI docs say "Rule files in
     * .devin/rules/ and .windsurf/rules/ are both loaded" and that the .devin/rules/ files "use the
     * same frontmatter as .windsurf/rules/*.md". Pinned so the PLATFORMS.md advice to pick one
     * stays true: the two files are the same file, front matter included (#683).
     */
    @Test
    void bothRuleDirectoriesCarryTheSameFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.createDirectories(root.resolve(".devin/rules"));
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
        h.compile();

        assertEquals(h.readFile(DEVIN_RULE), h.readFile(WINDSURF_RULE));
    }

    /**
     * Windsurf's rule schema is {@code trigger:} (#683). docs.devin.ai lists "Trigger values:
     * always_on, manual, model_decision, agent, glob" under the Windsurf accordion, and its glob
     * example is {@code trigger: glob} with a {@code globs:} pattern; the
     * {@code description}/{@code globs}/{@code alwaysApply} table on the same page is Cursor's.
     * A rule with no trigger has no documented activation mode at all.
     */
    @Test
    void aWindsurfRuleCarriesTheDocumentedGlobTriggerFrontMatter(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
        h.compile();

        String rule = h.readFile(WINDSURF_RULE);
        assertTrue(rule.startsWith("---\ntrigger: glob\nglobs: **/PaymentProcessor.java\n---\n\n"),
            "the front matter docs.devin.ai documents for a glob rule, and nothing else:\n" + rule);
        assertFalse(rule.contains("alwaysApply"), "Cursor's key has no meaning to Windsurf:\n" + rule);
        assertTrue(rule.contains("<!-- VIBETAGS-START -->") && rule.contains("first reason"), rule);
    }

    @Test
    void aWindsurfRoleFileJoinsItsGlobsWithCommas(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.writeString(root.resolve(".vibetags-roles"), "web = **/*Controller.java, **/*Endpoint.java\n");
        h.addSource("com.example.web.OrderController",
            "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderController {}\n");
        h.addSource("com.example.web.OrderEndpoint",
            "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderEndpoint {}\n");
        h.compile();

        String role = h.readFile(".windsurf/rules/web.md");
        assertTrue(role.startsWith("---\ntrigger: glob\nglobs: **/*Controller.java,**/*Endpoint.java\n---\n\n"), role);
    }

    /**
     * A project upgrading from an earlier release has Cursor-shaped headers committed. The first
     * build must replace that header rather than stack the new one above it, and must keep what a
     * person wrote outside the markers.
     */
    @Test
    void anUpgradeReplacesTheCursorShapedWindsurfHeader(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.writeString(root.resolve(WINDSURF_RULE),
            "---\ndescription: \"AI rules for com.example.payment.PaymentProcessor\"\n"
                + "globs: [\"**/PaymentProcessor.java\"]\nalwaysApply: false\n---\n\n"
                + "Hand-written note the team keeps here.\n\n"
                + "<!-- VIBETAGS-START -->\n# Rules for PaymentProcessor\n\nstale\n<!-- VIBETAGS-END -->\n");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
        h.compile();

        String rule = h.readFile(WINDSURF_RULE);
        assertTrue(rule.startsWith("---\ntrigger: glob\nglobs: **/PaymentProcessor.java\n---\n\n"), rule);
        assertEquals(1, rule.split("globs:", -1).length - 1, "exactly one header:\n" + rule);
        assertFalse(rule.contains("alwaysApply") || rule.contains("description:"), rule);
        assertTrue(rule.contains("Hand-written note the team keeps here."), rule);
        assertTrue(rule.contains("first reason") && !rule.contains("stale"), rule);
    }

    /**
     * Cursor's rules keep the header they had: the Windsurf fix must not reach the format the two
     * used to share. Pinned whole, for a per-element file and a role file.
     */
    @Test
    void cursorRuleFrontMatterIsUnchanged(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".cursor/rules"));
        Files.createDirectories(root.resolve(".windsurf/rules"));
        Files.writeString(root.resolve(".vibetags-roles"), "web = **/*Controller.java, **/*Endpoint.java\n");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE_V1);
        h.addSource("com.example.web.OrderController",
            "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderController {}\n");
        h.compile();

        String element = h.readFile(".cursor/rules/com-example-payment-PaymentProcessor.mdc");
        assertTrue(element.startsWith(
            "---\ndescription: \"AI rules for com.example.payment.PaymentProcessor\"\n"
                + "globs: [\"**/PaymentProcessor.java\"]\nalwaysApply: false\n---\n\n"
                + "<!-- VIBETAGS-START -->\n# Rules for PaymentProcessor\n"), element);
        String role = h.readFile(".cursor/rules/web.mdc");
        assertTrue(role.startsWith(
            "---\ndescription: \"AI rules for role web\"\n"
                + "globs: [\"**/*Controller.java\", \"**/*Endpoint.java\"]\nalwaysApply: false\n---\n\n"
                + "<!-- VIBETAGS-START -->\n# Rules for web\n"), role);
    }

    private static String read(Path root, String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
