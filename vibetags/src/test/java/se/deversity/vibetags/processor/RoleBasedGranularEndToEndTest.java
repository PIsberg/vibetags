package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
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
}
