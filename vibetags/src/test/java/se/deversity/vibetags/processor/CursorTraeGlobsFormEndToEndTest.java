package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code globs:} value of a Cursor {@code .mdc} rule and a Trae {@code .trae/rules} rule is a bare
 * comma-separated value, not a bracketed quoted list (issue #699).
 *
 * <p>Neither reader parses YAML. Cursor's ({@code extensions/cursor-agent-exec} in the shipped app)
 * takes the text after {@code globs:}, removes one pair of surrounding quotes, and splits on the commas
 * outside a brace group. Trae's ({@code MultiRuleService.parseMetadata} in the workbench) splits the
 * raw text on every comma. Either way a bracketed list reached the matcher as patterns carrying
 * literal brackets and quotes, {@code ["**}{@code /PaymentProcessor.java"]}, which match no source
 * file, so no VibeTags rule auto-attached. {@link #cursorReaderGlobs} and {@link #traeReaderGlobs}
 * restate the two readers, so each test first asserts what the tool would scope the rule to, and
 * only then the bytes. The evidence is in docs/PLATFORMS.md.
 */
@Tag("e2e")
class CursorTraeGlobsFormEndToEndTest {

    private static final String LOCKED_SOURCE =
        "package com.example.payment;\n"
            + "@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\n"
            + "public class PaymentProcessor {}\n";
    private static final String CONTROLLER_SOURCE =
        "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderController {}\n";
    private static final String PAYMENT_FILE = "src/main/java/com/example/payment/PaymentProcessor.java";
    private static final String CONTROLLER_FILE = "src/main/java/com/example/web/OrderController.java";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness build(Path root, String dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(dir));
        Files.writeString(root.resolve(".vibetags-roles"), "web = **/*Controller.java, **/*Endpoint.java\n");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.addSource("com.example.web.OrderController", CONTROLLER_SOURCE);
        h.compile();
        return h;
    }

    @Test
    void cursorPerElementRuleScopesToTheClass(@TempDir Path root) throws IOException {
        String rule = build(root, ".cursor/rules").readFile(".cursor/rules/com-example-payment-PaymentProcessor.mdc");
        assertMatches(cursorReaderGlobs(rule), List.of("**/PaymentProcessor.java"), PAYMENT_FILE, rule);
        assertTrue(rule.startsWith("---\ndescription: \"AI rules for com.example.payment.PaymentProcessor\"\n"
                + "globs: **/PaymentProcessor.java\nalwaysApply: false\n---\n\n<!-- VIBETAGS-START -->\n"),
            "the documented bare form:\n" + rule);
    }

    @Test
    void cursorRoleRuleScopesToEveryRoleGlob(@TempDir Path root) throws IOException {
        String rule = build(root, ".cursor/rules").readFile(".cursor/rules/web.mdc");
        assertMatches(cursorReaderGlobs(rule), List.of("**/*Controller.java", "**/*Endpoint.java"), CONTROLLER_FILE, rule);
        assertTrue(rule.startsWith("---\ndescription: \"AI rules for role web\"\n"
                + "globs: **/*Controller.java,**/*Endpoint.java\nalwaysApply: false\n---\n\n"),
            "several globs as one comma-separated value:\n" + rule);
    }

    @Test
    void traePerElementRuleScopesToTheClass(@TempDir Path root) throws IOException {
        String rule = build(root, ".trae/rules").readFile(".trae/rules/com-example-payment-PaymentProcessor.md");
        assertMatches(traeReaderGlobs(rule), List.of("**/PaymentProcessor.java"), PAYMENT_FILE, rule);
        assertTrue(rule.startsWith("---\nalwaysApply: false\nglobs: **/PaymentProcessor.java\n"
                + "description: \"AI rules for com.example.payment.PaymentProcessor\"\n---\n\n"),
            "the documented comma-separated form:\n" + rule);
    }

    @Test
    void traeRoleRuleScopesToEveryRoleGlob(@TempDir Path root) throws IOException {
        String rule = build(root, ".trae/rules").readFile(".trae/rules/web.md");
        assertMatches(traeReaderGlobs(rule), List.of("**/*Controller.java", "**/*Endpoint.java"), CONTROLLER_FILE, rule);
        assertTrue(rule.startsWith("---\nalwaysApply: false\nglobs: **/*Controller.java,**/*Endpoint.java\n"
                + "description: \"AI rules for role web\"\n---\n\n"),
            "several globs as one comma-separated value:\n" + rule);
    }

    /**
     * A project upgrading from an earlier release has bracketed headers committed. The first build
     * replaces that header in place and keeps what a person wrote outside the markers.
     */
    @Test
    void anUpgradeReplacesACommittedBracketedCursorHeader(@TempDir Path root) throws IOException {
        String path = ".cursor/rules/com-example-payment-PaymentProcessor.mdc";
        Files.createDirectories(root.resolve(".cursor/rules"));
        Files.writeString(root.resolve(path),
            "---\ndescription: \"AI rules for com.example.payment.PaymentProcessor\"\n"
                + "globs: [\"**/PaymentProcessor.java\"]\nalwaysApply: false\n---\n\n"
                + "Hand-written note the team keeps here.\n\n"
                + "<!-- VIBETAGS-START -->\n# Rules for PaymentProcessor\n\nstale\n<!-- VIBETAGS-END -->\n");
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        String rule = h.readFile(path);
        assertMatches(cursorReaderGlobs(rule), List.of("**/PaymentProcessor.java"), PAYMENT_FILE, rule);
        assertEquals(1, rule.split("globs:", -1).length - 1, "exactly one header:\n" + rule);
        assertFalse(rule.contains("[\""), "no bracketed list left behind:\n" + rule);
        assertTrue(rule.contains("Hand-written note the team keeps here."), rule);
        assertFalse(rule.contains("stale"), rule);
    }

    /** Asserts that one of the globs a reader took from {@code rule} matches {@code file}, then the globs themselves. */
    private static void assertMatches(List<String> globs, List<String> expected, String file, String rule) {
        assertTrue(globs.stream().anyMatch(g -> matches(g, file)),
            "no glob the reader takes matches " + file + ", so the rule never attaches: " + globs + "\n" + rule);
        assertEquals(expected, globs, "the globs the reader takes from the header:\n" + rule);
    }

    /**
     * Whether {@code glob} matches {@code file}. A pattern the JDK cannot compile, such as one with a
     * {@code /} inside a bracket class the way a bracketed list's first entry has, matches nothing.
     */
    private static boolean matches(String glob, String file) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(Path.of(file));
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * The {@code globs} value as Cursor's {@code .mdc} reader takes it: trimmed, one pair of surrounding
     * double or single quotes removed, split on the commas outside a brace group, each piece trimmed,
     * empty pieces dropped.
     */
    private static List<String> cursorReaderGlobs(String rule) {
        String value = globsValue(rule);
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> globs = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                addTrimmed(globs, value.substring(start, i));
                start = i + 1;
            }
        }
        addTrimmed(globs, value.substring(start));
        return globs;
    }

    /** The {@code globs} value as Trae's reader takes it: split on every comma, each piece trimmed, empty pieces dropped. */
    private static List<String> traeReaderGlobs(String rule) {
        List<String> globs = new ArrayList<>();
        for (String piece : globsValue(rule).split(",", -1)) {
            addTrimmed(globs, piece);
        }
        return globs;
    }

    /** The trimmed text after {@code globs:} in the header of {@code rule}. */
    private static String globsValue(String rule) {
        String header = rule.substring(4, rule.indexOf("\n---", 4));
        String value = null;
        for (String line : header.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "globs".equals(line.substring(0, colon).trim())) {
                value = line.substring(colon + 1).trim();
            }
        }
        assertTrue(value != null, "no globs line in:\n" + rule);
        return value;
    }

    private static void addTrimmed(List<String> globs, String piece) {
        String trimmed = piece.trim();
        if (!trimmed.isEmpty()) {
            globs.add(trimmed);
        }
    }
}
