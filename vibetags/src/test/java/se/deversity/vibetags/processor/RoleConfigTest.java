package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.content.RoleConfig;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;
import javax.lang.model.element.ElementKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    /**
     * A role named on two lines is one role, and its rule file must declare every glob both lines
     * gave it.
     *
     * <p>Routing already treats the two lines as one role: {@code roleFor} returns the same name
     * for a class matched by either, so both classes land in one file. {@code globsFor} answered
     * with the first matching line alone, so the file that contains both got frontmatter naming
     * only the first line's glob. Nothing fails and the guardrails are in the file; the file simply
     * never loads when the agent opens one of the second line's classes, which is the one thing a
     * granular rule file exists to do.
     *
     * <p>Appending a line rather than extending one is how a config grows, so this is reachable by
     * ordinary editing rather than by a typo.
     */
    @Test
    @DisplayName("a role named on two lines keeps the globs from both")
    void repeatedRoleName_keepsEveryGlobItWasGiven(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "api-endpoints = **/*Controller.java",
            "api-endpoints = **/*Resource.java");

        assertEquals("api-endpoints", roles.roleFor(type("com.example.web.OrderController")).orElse(null));
        assertEquals("api-endpoints", roles.roleFor(type("com.example.web.OrderResource")).orElse(null),
            "both lines route into the one role, so both classes share a rule file");

        assertEquals(List.of("**/*Controller.java", "**/*Resource.java"), roles.globsFor("api-endpoints"),
            "the file holds both lines' classes, so its frontmatter must name both lines' globs; "
                + "with only the first, the file never loads for the classes of the second");
    }

    @Test
    @DisplayName("a package and a class of the same name are not one cached answer")
    void kindIsPartOfTheRoleLookup(@TempDir Path dir) throws IOException {
        // roleFor is memoised, because the same element is asked several times per build: twice or
        // three times by GranularRulesWriter.plan and once per indexed aggregate. A memo keyed only
        // by qualified name would answer this second call with the first call's role, which is the
        // one way memoising here can be wrong, and it would be wrong silently.
        RoleConfig roles = write(dir,
            "hooks = com/example/webhooks/**",
            "klass = com/example/webhooks.java");

        assertEquals("hooks", roles.roleFor(pkg("com.example.webhooks")).orElse(null),
            "the package path ends in a separator and belongs to the package role");
        assertEquals("klass", roles.roleFor(type("com.example.webhooks")).orElse(null),
            "the class of the same qualified name resolves to a .java path and a different role");
    }

    @Test
    @DisplayName("asking twice gives the same answer")
    void repeatedLookupsAgree(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "api-endpoints = **/*Controller.java");
        TaggedElement controller = type("com.example.web.OrderController");
        TaggedElement service = type("com.example.web.OrderService");

        for (int call = 0; call < 3; call++) {
            assertEquals("api-endpoints", roles.roleFor(controller).orElse(null), "call " + call);
            assertTrue(roles.roleFor(service).isEmpty(), "call " + call + ": unmatched stays unmatched");
        }
    }

    /** The rule-file stem the index points at must be the stem the writer writes; one function. */
    @Test
    @DisplayName("the granular stem is the sanitized role name, or the per-class name with no role")
    void granularStem_isTheSanitizedRoleOrTheClassName(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "api endpoints! = **/*Controller.java");

        assertEquals("api-endpoints-", roles.granularStemFor(type("com.example.web.OrderController")));
        assertEquals("com-example-web-OrderService", roles.granularStemFor(type("com.example.web.OrderService")));
    }

    @Test
    @DisplayName("sanitize keeps [A-Za-z0-9._-] and turns everything else into one dash each")
    void sanitize_keepsFilenameSafeCharactersOnly() {
        assertEquals("a-b-c-d.e_f-g", RoleConfig.sanitize("a b/c\\d.e_f-g"));
        assertEquals("caf-", RoleConfig.sanitize("caf\u00e9"));
        assertEquals("--", RoleConfig.sanitize("  "));
    }

    /**
     * The hash is of the file's exact text, line ending included, so it must equal the
     * fingerprint hash of those bytes. The oracle is computed here from {@code String.hashCode},
     * not read from the code under test.
     */
    @Test
    @DisplayName("the content hash is the 8-hex fingerprint hash of the raw file text")
    void contentHash_isTheHashOfTheRawText(@TempDir Path dir) throws IOException {
        String text = "api = **/*Controller.java\n";
        RoleConfig roles = write(dir, "api = **/*Controller.java");
        assertEquals(String.format("%08x", text.hashCode()), roles.contentHash());
    }

    @Test
    @DisplayName("CRLF and bare CR end a line as well as LF does")
    void everyLineEndingSeparatesRoles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(RoleConfig.FILE_NAME),
            "a = **/*Alpha.java\r\nb = **/*Beta.java\rc = **/*Gamma.java", StandardCharsets.UTF_8);
        RoleConfig roles = RoleConfig.load(dir);

        assertEquals("a", roles.roleFor(type("x.MyAlpha")).orElse(null));
        assertEquals("b", roles.roleFor(type("x.MyBeta")).orElse(null));
        assertEquals("c", roles.roleFor(type("x.MyGamma")).orElse(null));
    }

    @Test
    @DisplayName("lines with no name, no '=' or no matchers yield no role, and an all-such file is empty")
    void unusableLinesYieldNoRole(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "# only unusable lines below",
            "= **/*Controller.java",
            "   = **/*Service.java",
            "lonely",
            "nothing =",
            "commas = , ,");

        assertFalse(roles == null, "a present file loads, even when nothing in it is usable");
        assertTrue(roles.isEmpty());
        assertTrue(roles.roleFor(type("com.example.OrderController")).isEmpty());
        assertTrue(roles.globsFor("nothing").isEmpty());
    }

    @Test
    @DisplayName("a directory named .vibetags-roles is not a config")
    void aDirectoryIsNotAConfig(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve(RoleConfig.FILE_NAME));
        assertNull(RoleConfig.load(dir));
    }

    /**
     * Within a role an exact name is tried before its globs, but across roles config order
     * decides first: an earlier role's glob beats a later role's exact name.
     */
    @Test
    @DisplayName("config order beats matcher type")
    void anEarlierGlobBeatsALaterExactName(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "endpoints = **/*Endpoint.java",
            "legacy = com.example.legacy.WeirdEndpoint");
        assertEquals("endpoints", roles.roleFor(type("com.example.legacy.WeirdEndpoint")).orElse(null));
    }

    @Test
    @DisplayName("a role of exact names only routes them and declares no glob")
    void aRoleOfExactNamesOnly(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "legacy = com.example.A, com.example.B");

        assertEquals("legacy", roles.roleFor(type("com.example.B")).orElse(null));
        assertTrue(roles.roleFor(type("com.example.C")).isEmpty());
        assertEquals(List.of(), roles.globsFor("legacy"));
    }

    @Test
    @DisplayName("a line whose only matcher does not compile is no role, and the name's later line still is")
    void aLineOfOnlyAMalformedGlobIsNoRole(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir,
            "api = **/*{Controller.java",
            "api = **/*Endpoint.java");

        assertEquals(List.of("**/*Endpoint.java"), roles.globsFor("api"));
        assertEquals("api", roles.roleFor(type("com.example.OrderEndpoint")).orElse(null));
    }

    @Test
    @DisplayName("whitespace around the name and each matcher is not part of them")
    void whitespaceAroundNameAndMatchersIsTrimmed(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "\t api  =   **/*Controller.java  ,\t com.example.Legacy  ");

        assertEquals(List.of("**/*Controller.java"), roles.globsFor("api"));
        assertEquals("api", roles.roleFor(type("com.example.Legacy")).orElse(null));
        assertEquals("api", roles.granularStemFor(type("com.example.web.OrderController")));
    }

    @Test
    @DisplayName("a comma inside a brace group belongs to the glob, one outside it separates matchers")
    void aBraceGroupKeepsItsComma(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "api = **/*{Controller,Service}.java, com.example.Legacy");

        assertEquals(List.of("**/*{Controller,Service}.java"), roles.globsFor("api"));
        assertEquals("api", roles.roleFor(type("com.example.OrderService")).orElse(null));
        assertEquals("api", roles.roleFor(type("com.example.Legacy")).orElse(null));
    }

    /** Some editors save U+FEFF first; it is encoding, not the first role's name (#1197). */
    @Test
    @DisplayName("a byte order mark is not part of the first role's name")
    void aByteOrderMarkIsNotPartOfTheFirstRoleName(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(RoleConfig.FILE_NAME),
            "\uFEFFapi = **/*Controller.java\napi = com.example.Legacy\n", StandardCharsets.UTF_8);
        RoleConfig roles = RoleConfig.load(dir);

        assertEquals("api", roles.roleFor(type("com.example.web.OrderController")).orElse(null));
        assertEquals("api", roles.granularStemFor(type("com.example.web.OrderController")),
            "one rule file for the role, not a second one named -api");
        assertEquals(List.of("**/*Controller.java"), roles.globsFor("api"));
    }

    /**
     * A type's glob is matched against the file its code is in (#1178): the package directory and
     * {@link TaggedElement#sourceFile()}. Rebuilding the path from the qualified name gave a Kotlin
     * type a {@code .java} name and a nested type a directory per enclosing type, so neither ever
     * matched a glob written for its file.
     */
    @Test
    @DisplayName("a Kotlin type is matched as the .kt file it is in")
    void aKotlinTypeMatchesItsKtFile(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "kotlin = **/*.kt", "billing = com/x/InvoiceUtils.kt");

        assertEquals("kotlin", roles.roleFor(sourceType("com.x.Invoice", "Invoice", "Invoice.kt")).orElse(null));
        assertEquals("billing",
            write(dir, "billing = com/x/InvoiceUtils.kt").roleFor(
                sourceType("com.x.Invoice", "Invoice", "InvoiceUtils.kt")).orElse(null),
            "a Kotlin file may declare a type whose name is not the file's");
    }

    @Test
    @DisplayName("a nested type is matched as its outer type's file")
    void aNestedTypeMatchesItsOuterTypesFile(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "api = **/*Controller.java");

        assertEquals("api", roles.roleFor(
            sourceType("com.x.FooController.Request", "FooController", "FooController.java")).orElse(null));
        assertEquals("api", roles.roleFor(
            sourceType("com.x.FooController.Request.Body", "FooController", "FooController.java")).orElse(null),
            "two levels deep");
        assertTrue(write(dir, "api = **/FooController/**").roleFor(
                sourceType("com.x.FooController.Request", "FooController", "FooController.java")).isEmpty(),
            "no FooController directory exists, so a glob for one does not route the nested type");
    }

    @Test
    @DisplayName("a type in the default package or in a package named like it keeps its directory")
    void thePackageDirectoryComesFromTheModel(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "root = *.java", "odd = com/Foo/Foo.java");

        assertEquals("root", roles.roleFor(sourceType("Top", "Top", "Top.java")).orElse(null),
            "'*' does not cross a '/', so this matches only a file with no package directory");
        assertEquals("odd", roles.roleFor(sourceType("com.Foo.Foo.Inner", "Foo", "Foo.java")).orElse(null),
            "a package segment equal to the outer type's name is still the package");
    }

    @Test
    @DisplayName("a type nested in a default-package type, and a name without its outer type, still get a package")
    void thePackageCutsEdges(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "root = *.java", "fallback = com/x/Other.java");

        assertEquals("root", roles.roleFor(sourceType("Outer.Inner", "Outer", "Outer.java")).orElse(null),
            "nested in a type of the default package: no directory");
        assertEquals("fallback", roles.roleFor(sourceType("com.x.Thing", "Other", "Other.java")).orElse(null),
            "an outermost name the qualified name does not hold: the package is what precedes the simple name");
    }

    /** A type as the collector builds it, with the outermost type's name and the file it is in. */
    private static TaggedElement sourceType(String fqn, String fileStem, String sourceFile) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return TaggedElement.builder(fqn)
            .names(fqn, simple, simple, fqn.replaceAll("[^A-Za-z0-9-]", "-"))
            .fileStem(fileStem)
            .sourceFile(sourceFile)
            .kind(ElementTag.CLASS)
            .build();
    }

    @Test
    @DisplayName("a commented-out role line is a comment, '=' and all")
    void aCommentedOutRoleIsNoRole(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "# api = **/*Controller.java", "  #models = **/*Entity.java");

        assertTrue(roles.isEmpty());
        assertTrue(roles.roleFor(type("com.example.OrderController")).isEmpty());
    }

    @Test
    @DisplayName("everything after the first '=' is matchers")
    void onlyTheFirstEqualsSeparatesTheName(@TempDir Path dir) throws IOException {
        RoleConfig roles = write(dir, "api = **/*Controller.java = **/*Service.java");

        assertEquals(List.of("**/*Controller.java = **/*Service.java"), roles.globsFor("api"));
        assertFalse(roles.isEmpty());
    }
}
