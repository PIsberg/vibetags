package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A brace glob in the front matter of a granular rule file, for every platform that writes more than
 * one glob into it (issue #696, extending #685 beyond Devin Desktop and Windsurf).
 *
 * <p>A {@code .vibetags-roles} glob may use brace alternation, and a {@code .vibetags-mirror} glob
 * line is taken whole, so either can put a comma inside one glob. Where the vendor documents its glob
 * list as a comma-separated value, a reader that splits on commas cuts that glob in two:
 * <ul>
 *   <li>GitHub Copilot, docs.github.com: "You can specify multiple patterns by separating them with
 *       commas", with {@code applyTo: "**}{@code /*.ts,**}{@code /*.tsx"}.</li>
 *   <li>Cursor, cursor.com/docs/rules: {@code docs/**}{@code /*.md, docs/**}{@code /*.mdx} matches
 *       ".md and .mdx files under docs/ (comma-separated)".</li>
 *   <li>Trae, docs.trae.cn/ide/rules: several patterns may be configured, separated by {@code ,},
 *       and synced to the {@code globs} field.</li>
 * </ul>
 * Those three expand each brace group into one glob per alternative, as #685 does, and write any
 * other comma as {@code ?}. Claude Code, Cline and Continue document a YAML list of patterns, each
 * entry matched whole, and Claude Code and Cline document brace globs inside an entry, so their
 * headers keep the glob as written.
 */
@Tag("e2e")
class GranularBraceGlobEndToEndTest {

    private static final String ROLES = "web = **/{api,web}/*.{java,kt}, **/*Endpoint.java\n";
    private static final String EXPANDED = "**/api/*.java,**/api/*.kt,**/web/*.java,**/web/*.kt,**/*Endpoint.java";
    private static final String EXPANDED_LIST =
        "[\"**/api/*.java\", \"**/api/*.kt\", \"**/web/*.java\", \"**/web/*.kt\", \"**/*Endpoint.java\"]";
    private static final String AS_WRITTEN_LIST = "[\"**/{api,web}/*.{java,kt}\", \"**/*Endpoint.java\"]";

    private static final String LOCKED_SOURCE =
        "package com.example.payment;\n"
            + "@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\n"
            + "public class PaymentProcessor {}\n";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness roleBuild(Path root, String dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(dir));
        Files.writeString(root.resolve(".vibetags-roles"), ROLES);
        h.addSource("com.example.web.OrderController",
            "package com.example.web;\n@se.deversity.vibetags.annotations.AILocked(reason = \"r\")\npublic class OrderController {}\n");
        h.compile();
        return h;
    }

    @Test
    void copilotApplyToExpandsABraceGlobSoEveryCommaSeparatesWholeGlobs(@TempDir Path root) throws IOException {
        String file = roleBuild(root, ".github/instructions").readFile(".github/instructions/web.instructions.md");
        assertTrue(file.startsWith("---\napplyTo: \"" + EXPANDED + "\"\n---\n\n"),
            "each brace alternative becomes a pattern of its own, in order:\n" + file);
    }

    @Test
    void cursorGlobsExpandABraceGlob(@TempDir Path root) throws IOException {
        String file = roleBuild(root, ".cursor/rules").readFile(".cursor/rules/web.mdc");
        assertTrue(file.startsWith("---\ndescription: \"AI rules for role web\"\nglobs: " + EXPANDED_LIST
                + "\nalwaysApply: false\n---\n\n"),
            "no list entry keeps a comma a comma-splitting reader would cut:\n" + file);
    }

    @Test
    void traeGlobsExpandABraceGlob(@TempDir Path root) throws IOException {
        String file = roleBuild(root, ".trae/rules").readFile(".trae/rules/web.md");
        assertTrue(file.startsWith("---\nalwaysApply: false\nglobs: " + EXPANDED_LIST
                + "\ndescription: \"AI rules for role web\"\n---\n\n"),
            "no list entry keeps a comma a comma-splitting reader would cut:\n" + file);
    }

    /** A YAML list whose entries are matched whole already keeps a brace glob intact. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        ".claude/rules; .claude/rules/web.md; '---|paths: '",
        ".clinerules; .clinerules/web.md; '---|paths: '",
        ".continue/rules; .continue/rules/web.md; '---|description: \"AI rules for role web\"|globs: '",
    })
    void aListPlatformKeepsTheBraceGlobAsWritten(String dir, String rule, String headerStart, @TempDir Path root)
            throws IOException {
        String file = roleBuild(root, dir).readFile(rule);
        String expectedStart = headerStart.replace('|', '\n') + AS_WRITTEN_LIST + "\n";
        assertTrue(file.startsWith(expectedStart), "the list entry is the glob as written:\n" + file);
    }

    /** A mirror glob line with a comma no brace group explains stays one pattern in applyTo. */
    @Test
    void aLiteralCommaInAMirrorGlobCannotSplitCopilotApplyTo(@TempDir Path reactorRoot) throws IOException {
        Files.createDirectories(reactorRoot.resolve("app-tests/.github/instructions"));
        Files.writeString(reactorRoot.resolve("app-tests/.vibetags-mirror"), "glob = **/app,tests/**/*.java\n",
            StandardCharsets.UTF_8);
        Files.createDirectories(reactorRoot.resolve("app-core"));
        Files.writeString(reactorRoot.resolve("app-core/pom.xml"),
            "<project><artifactId>app-core</artifactId></project>", StandardCharsets.UTF_8);
        ProcessorTestHarness h = new ProcessorTestHarness(reactorRoot, false);
        h.writeSourceFile("app-core/src/main/java/com/example/payment/PaymentProcessor.java", LOCKED_SOURCE);
        h.compile();

        String mirrored = Files.readString(reactorRoot.resolve(
            "app-tests/.github/instructions/mirrored-app-core-com-example-payment-PaymentProcessor.instructions.md"));
        assertTrue(mirrored.startsWith("---\napplyTo: \"**/PaymentProcessor.java,**/app?tests/**/*.java\"\n---\n"),
            "the only comma left separates the rule's own glob from the mirror glob:\n" + mirrored);
    }
}
