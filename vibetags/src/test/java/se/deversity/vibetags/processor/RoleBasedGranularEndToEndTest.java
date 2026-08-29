package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for role/topic-based granular rules ({@code .vibetags-roles}): annotated
 * elements are grouped into human-named rule files by glob/FQN, with first-match routing and a
 * per-class fallback for elements matching no role. Uses Cursor granular ({@code .cursor/rules})
 * as the representative platform.
 */
@Tag("e2e")
class RoleBasedGranularEndToEndTest {

    @AfterEach
    void releaseLog() {
        VibeTagsLogger.shutdown();
    }

    private static final String ORDER_CONTROLLER =
        "package com.example.web;\n"
            + "import se.deversity.vibetags.annotations.AIContext;\n"
            + "@AIContext(focus = \"routing\", avoids = \"reflection\")\n"
            + "public class OrderController {}\n";
    private static final String USER_CONTROLLER =
        "package com.example.web;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"auth surface\")\n"
            + "public class UserController {}\n";
    private static final String PRODUCT_ENTITY =
        "package com.example.data;\n"
            + "import se.deversity.vibetags.annotations.AICore;\n"
            + "@AICore(sensitivity = \"high\", note = \"persistence model\")\n"
            + "public class ProductEntity {}\n";
    private static final String PLAIN_SERVICE =
        "package com.example.util;\n"
            + "import se.deversity.vibetags.annotations.AIAudit;\n"
            + "@AIAudit(checkFor = {\"SQL Injection\"})\n"
            + "public class PlainService {}\n";

    private static ProcessorTestHarness harness(Path dir, String rolesContent) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn(".cursor/rules/.vibetags");
        Files.writeString(dir.resolve(".vibetags-roles"), rolesContent, StandardCharsets.UTF_8);
        return h;
    }

    @Test
    void rolesGroupMatchingClasses_intoNamedFiles(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = harness(dir,
            "api-endpoints = **/*Controller.java\n"
            + "models        = **/*Entity.java\n");
        h.addSource("com.example.web.OrderController", ORDER_CONTROLLER);
        h.addSource("com.example.web.UserController", USER_CONTROLLER);
        h.addSource("com.example.data.ProductEntity", PRODUCT_ENTITY);
        h.compile();

        String api = h.readFile(".cursor/rules/api-endpoints.mdc");
        assertTrue(api.contains("globs: [\"**/*Controller.java\"]"), "role file carries the role's glob");
        assertTrue(api.contains("com.example.web.OrderController"), "both controllers grouped into the role file");
        assertTrue(api.contains("com.example.web.UserController"));
        assertTrue(api.contains("# Rules for api-endpoints"), "role name is the file heading");

        assertTrue(h.readFile(".cursor/rules/models.mdc").contains("com.example.data.ProductEntity"));

        // Matched classes do NOT also get a per-class file.
        assertFalse(h.fileExists(".cursor/rules/com-example-web-OrderController.mdc"),
            "a class routed to a role must not also produce a per-class file");
    }

    @Test
    void unmatchedClass_keepsPerClassFile(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = harness(dir, "api-endpoints = **/*Controller.java\n");
        h.addSource("com.example.web.OrderController", ORDER_CONTROLLER);
        h.addSource("com.example.util.PlainService", PLAIN_SERVICE);
        h.compile();

        assertTrue(h.fileExists(".cursor/rules/api-endpoints.mdc"));
        assertTrue(h.fileExists(".cursor/rules/com-example-util-PlainService.mdc"),
            "a class matching no role keeps its per-class file (non-lossy)");
    }

    @Test
    void fqnOverride_routesOddClassIntoRole(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = harness(dir,
            "models = **/*Entity.java, com.example.util.PlainService\n");
        h.addSource("com.example.util.PlainService", PLAIN_SERVICE);
        h.compile();

        assertTrue(h.readFile(".cursor/rules/models.mdc").contains("com.example.util.PlainService"),
            "an FQN listed in a role overrides the glob and routes the odd class in");
        assertFalse(h.fileExists(".cursor/rules/com-example-util-PlainService.mdc"),
            "the overridden class no longer gets a per-class file");
    }

    @Test
    void roleDefinedOnlyByFqns_derivesItsGlobsFromItsMembers(@TempDir Path dir) throws IOException {
        // A role can be written as a bare list of classes, with no glob at all. The rule file still
        // needs a `globs:` front-matter line, because that line is how the editor decides when to
        // load it — a role file with no globs is a file the agent never opens. So the globs are
        // derived from the members' own paths.
        ProcessorTestHarness h = harness(dir,
            "legacy = com.example.util.PlainService, com.example.data.ProductEntity\n");
        h.addSource("com.example.util.PlainService", PLAIN_SERVICE);
        h.addSource("com.example.data.ProductEntity", PRODUCT_ENTITY);
        h.compile();

        String legacy = h.readFile(".cursor/rules/legacy.mdc");
        assertTrue(legacy.contains("globs:"),
            "a role file with no globs line is one the editor never loads:\n" + legacy);
        assertTrue(legacy.contains("PlainService.java") && legacy.contains("ProductEntity.java"),
            "the derived globs must name both members, or one of them silently loses its rules:\n" + legacy);
        assertTrue(legacy.contains("com.example.util.PlainService")
                && legacy.contains("com.example.data.ProductEntity"),
            "both members' guardrails belong in the grouped file:\n" + legacy);
    }

    @Test
    void firstMatchWins_whenGlobsOverlap(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = harness(dir,
            "webhooks    = **/webhooks/**\n"
            + "controllers = **/*Controller.java\n");
        h.addSource("com.example.webhooks.HookController",
            "package com.example.webhooks;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"delivery guarantees\")\n"
                + "public class HookController {}\n");
        h.compile();

        assertTrue(h.readFile(".cursor/rules/webhooks.mdc").contains("com.example.webhooks.HookController"),
            "an element matching two roles goes to the first (config order)");
        assertFalse(h.fileExists(".cursor/rules/controllers.mdc"),
            "the second matching role does not also claim it");
    }

    @Test
    void multiGlobRole_rendersAllGlobsInFrontmatter(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = harness(dir, "web = **/*Controller.java, **/*Endpoint.java\n");
        h.addSource("com.example.web.OrderController", ORDER_CONTROLLER);
        h.compile();

        assertTrue(h.readFile(".cursor/rules/web.mdc").contains("globs: [\"**/*Controller.java\", \"**/*Endpoint.java\"]"),
            "all of a role's globs appear in the frontmatter list");
    }

    @Test
    void withoutRolesConfig_outputIsPerClass(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn(".cursor/rules/.vibetags"); // granular on, but NO .vibetags-roles
        h.addSource("com.example.web.OrderController", ORDER_CONTROLLER);
        h.compile();

        assertTrue(h.fileExists(".cursor/rules/com-example-web-OrderController.mdc"),
            "without a roles config, granular output stays per-class");
        assertFalse(h.fileExists(".cursor/rules/api-endpoints.mdc"));
    }

    @Test
    void perModuleRoles_produceRoleFilesUnderTheModule(@TempDir Path reactorRoot) throws IOException {
        // A submodule opts into its own .cursor/rules + .vibetags-roles (file-backed sources so the
        // module root resolves). Its role file lands under the module directory.
        Path module = reactorRoot.resolve("module-web");
        Files.createDirectories(module.resolve(".cursor/rules"));
        Files.writeString(module.resolve("pom.xml"),
            "<project><artifactId>module-web</artifactId></project>", StandardCharsets.UTF_8);
        Files.writeString(module.resolve(".vibetags-roles"),
            "api-endpoints = **/*Controller.java\n", StandardCharsets.UTF_8);

        ProcessorTestHarness h = new ProcessorTestHarness(reactorRoot, false);
        h.writeSourceFile("module-web/src/main/java/com/example/web/OrderController.java", ORDER_CONTROLLER);
        h.compile();

        assertTrue(Files.exists(module.resolve(".cursor/rules/api-endpoints.mdc")),
            "per-module roles group the module's own classes under the module directory");
        assertTrue(Files.readString(module.resolve(".cursor/rules/api-endpoints.mdc"))
                .contains("com.example.web.OrderController"));
    }

    /**
     * The globs a role file declares are what make the editor load it, and they are rendered from
     * {@code .vibetags-roles}. Editing that config is therefore a change to the rule file, not just
     * to the config: the fingerprint already notices it (the roles hash is folded in), and the
     * writer must then let the new front matter through rather than keep the file's old one.
     */
    @Test
    void editingARolesGlobs_reachesTheExistingRuleFilesFrontmatter(@TempDir Path dir) throws Exception {
        ProcessorTestHarness h = harness(dir, "web = **/*Controller.java\n");
        h.addSource("com.example.web.OrderController", ORDER_CONTROLLER);
        h.compile();
        assertTrue(h.readFile(".cursor/rules/web.mdc").contains("globs: [\"**/*Controller.java\"]"),
            "precondition: the role file starts out with the role's first glob");

        ProcessorTestHarness.awaitFilesystemTick(dir);
        Files.writeString(dir.resolve(".vibetags-roles"),
            "web = **/*Controller.java, **/*Endpoint.java\n", StandardCharsets.UTF_8);
        h.compile();

        String web = h.readFile(".cursor/rules/web.mdc");
        assertTrue(web.contains("globs: [\"**/*Controller.java\", \"**/*Endpoint.java\"]"),
            "a glob added to the role must reach the existing file's front matter, or the rule "
                + "silently keeps applying to the old set of files:\n" + web);
        assertTrue(web.contains("com.example.web.OrderController"), "the body must still be there:\n" + web);
    }

    /**
     * Two role names that differ only in characters a filename cannot carry resolve to one file,
     * and that file must hold both roles.
     *
     * <p>{@code RoleConfig.sanitize} maps everything outside {@code [a-zA-Z0-9._-]} to a dash, so
     * {@code api endpoints} and {@code api-endpoints} are two roles with one filename. The writer
     * planned them into a map keyed by that filename and the second put replaced the first, so one
     * role's classes lost their rule file entirely. Nothing warned, and the scoped-rules index
     * still pointed those classes at the surviving file, which does not mention them: an index
     * entry leading to a file that says nothing about the element it names.
     *
     * <p>Reachable by renaming a role and leaving the old line, or by two people adding a role from
     * opposite ends of a config. Merging is the same answer the multi-module case already gives: a
     * file several producers write is merged, never replaced (issue #365).
     */
    @Test
    void rolesCollidingOnOneFilename_areMerged_notReplaced(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = harness(dir,
            "api endpoints = **/*Controller.java\n"
            + "api-endpoints = **/*Entity.java\n");
        h.addSource("com.example.web.OrderController", ORDER_CONTROLLER);
        h.addSource("com.example.data.ProductEntity", PRODUCT_ENTITY);
        h.compile();

        String merged = h.readFile(".cursor/rules/api-endpoints.mdc");
        assertTrue(merged.contains("com.example.data.ProductEntity"),
            "the second role reaches its file:" + System.lineSeparator() + merged);
        assertTrue(merged.contains("com.example.web.OrderController"),
            "and so does the first, which shares the filename. Replacing it drops the guardrail "
                + "with no diagnostic:" + System.lineSeparator() + merged);
        assertTrue(merged.contains("**/*Controller.java") && merged.contains("**/*Entity.java"),
            "a file holding both roles' classes must declare both roles' globs, or it never loads "
                + "for half of them:" + System.lineSeparator() + merged);
    }
}
