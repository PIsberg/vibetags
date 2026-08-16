package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.model.RoleConfig;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.model.TaggedElement;
import javax.lang.model.element.ElementKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoleConfig}: parsing {@code .vibetags-roles}, glob→regex matching,
 * first-match routing, FQN overrides, and package handling.
 */
class RoleConfigTest {

    private static RoleConfig write(Path dir, String... lines) throws IOException {
        Files.writeString(dir.resolve(RoleConfig.FILE_NAME), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return RoleConfig.load(dir);
    }

    private static TaggedElement type(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        return TaggedElements.tagged(e);
    }

    private static TaggedElement pkg(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.PACKAGE);
        return TaggedElements.tagged(e);
    }

    @Test
    void absentFile_loadsNull(@TempDir Path dir) {
        assertNull(RoleConfig.load(dir), "no .vibetags-roles → null (roles off)");
    }

    @Test
    void globRole_matchesByReconstructedPath(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "api-endpoints = **/*Controller.java");
        assertFalse(roles.isEmpty());
        assertEquals("api-endpoints", roles.roleFor(type("com.example.web.OrderController")).orElse(null));
        assertTrue(roles.roleFor(type("com.example.web.OrderService")).isEmpty(),
            "non-matching class belongs to no role");
    }

    @Test
    void firstMatchWins_inConfigOrder(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "webhooks    = **/webhooks/**",
            "controllers = **/*Controller.java");
        // WebhookController matches BOTH globs; the first role (webhooks) wins.
        assertEquals("webhooks", roles.roleFor(type("com.example.webhooks.WebhookController")).orElse(null));
    }

    @Test
    void fqnMatcher_isExact(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "special = com.example.legacy.WeirdEndpoint");
        assertEquals("special", roles.roleFor(type("com.example.legacy.WeirdEndpoint")).orElse(null));
        assertTrue(roles.roleFor(type("com.example.legacy.WeirdEndpointX")).isEmpty(),
            "FQN match must be exact, not a prefix");
    }

    @Test
    void mixedGlobsAndFqns_onOneLine(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "models = **/*Entity.java, com.example.odd.NotAnEntity");
        assertEquals("models", roles.roleFor(type("com.example.UserEntity")).orElse(null));
        assertEquals("models", roles.roleFor(type("com.example.odd.NotAnEntity")).orElse(null));
        assertEquals(java.util.List.of("**/*Entity.java"), roles.globsFor("models"),
            "globsFor returns only the glob matchers, not the FQN override");
    }

    @Test
    void packageElement_matchesDirectoryGlob(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "hooks = **/webhooks/**");
        assertEquals("hooks", roles.roleFor(pkg("com.example.webhooks")).orElse(null));
        assertEquals("hooks", roles.roleFor(type("com.example.webhooks.Handler")).orElse(null));
    }

    /**
     * A glob that does not compile — an unclosed brace group is the easy one to type — used to
     * throw a {@code PatternSyntaxException} out of {@code load}, which the processor calls at
     * the top of its generate phase: one typo in {@code .vibetags-roles} silently stopped every
     * output of that build (files, sidecar, mirrors), downgraded to a WARNING nobody reads. A
     * matcher that cannot compile matches nothing. The unclosed brace swallows the rest of its
     * own line (the tokenizer cannot know where the group was meant to end), which is the cost
     * of the typo; the next line is untouched by it and still routes.
     */
    @Test
    void malformedGlob_isSkipped_notThrown(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "api = **/*{Controller,Service.java, **/*Endpoint.java",
            "models = **/*Entity.java");
        assertEquals("models", roles.roleFor(type("com.example.data.ProductEntity")).orElse(null),
            "the line after the typo still routes");
        assertTrue(roles.roleFor(type("com.example.web.OrderController")).isEmpty(),
            "the malformed matcher matches nothing rather than something surprising");
        assertTrue(roles.globsFor("api").isEmpty(),
            "and it is not written into any rule file's frontmatter either");
    }

    @Test
    void commentsAndBlankLines_ignored(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "# roles config",
            "",
            "api = **/*Controller.java",
            "   # trailing comment");
        assertEquals("api", roles.roleFor(type("a.b.FooController")).orElse(null));
    }

    @Test
    void contentHash_changesWithContent(@TempDir Path a, @TempDir Path b) throws IOException {
        RoleConfig one = write(a, "api = **/*Controller.java");
        RoleConfig two = write(b, "api = **/*Service.java");
        assertFalse(one.contentHash().equals(two.contentHash()), "different config → different hash");
    }

    @Test
    void braceAlternation_inGlob(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "x = **/{Foo,Bar}.java");
        assertEquals("x", roles.roleFor(type("a.b.Foo")).orElse(null));
        assertEquals("x", roles.roleFor(type("a.b.Bar")).orElse(null));
        assertTrue(roles.roleFor(type("a.b.Baz")).isEmpty(), "brace alternation matches only the listed names");
    }

    @Test
    void singleStar_staysWithinSegment(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "top = *.java");
        assertEquals("top", roles.roleFor(type("Foo")).orElse(null), "default-package class matches *.java");
        assertTrue(roles.roleFor(type("a.Foo")).isEmpty(), "single * does not cross a package boundary");
    }
}
