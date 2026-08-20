package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.TransitiveManifestReader;
import se.deversity.vibetags.processor.internal.TransitiveManifestWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The multi-module example is the working demonstration of transitive guardrails, and the only
 * place the feature is exercised through Maven rather than through a programmatic {@code javac}.
 *
 * <p>What it demonstrates is easy to break silently. Deleting an opt-in marker, or the
 * {@code package-info.java} the whole thing hangs off, leaves a reactor that still builds, still
 * regenerates byte-for-byte, and still passes CI — while quietly demonstrating nothing. These
 * assertions are what makes that a failure instead of a slow rot.
 *
 * <p>Reads the committed files rather than compiling, so it stays in the fast tier. The end-to-end
 * behaviour is {@code TransitiveGuardrailLifecycleE2ETest}'s job.
 */
class TransitiveExampleCoverageTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();
    private static final Path REACTOR = REPO_ROOT.resolve("examples/multimodule");

    /** The package whose {@code package-info.java} publishes the demo's inherited rules. */
    private static final String PUBLISHED_PACKAGE = "com.example.multimodule.core";

    /** Modules that import {@link #PUBLISHED_PACKAGE} and therefore inherit its guardrails. */
    private static final List<String> CONSUMING_MODULES = List.of("engine", "cli");

    @Test
    void theReactorOptsIntoBothHalvesOfTheFeature() {
        assumeTrue(Files.isDirectory(REACTOR), "examples/multimodule not reachable; skipping");
        assertTrue(TransitiveManifestWriter.optedIn(REACTOR),
            "the demo publishes its package guardrails, so " + TransitiveManifestWriter.MARKER_FILE
                + " must be present at " + REACTOR);
        assertTrue(TransitiveManifestReader.optedIn(REACTOR),
            "the demo also consumes them, so " + TransitiveManifestReader.MARKER_FILE + " must be present");
    }

    @Test
    void theMarkerNamesAnArtifactCoordinate() throws IOException {
        assumeTrue(Files.isRegularFile(REACTOR.resolve(TransitiveManifestWriter.MARKER_FILE)),
            "marker not reachable; skipping");
        String origin = TransitiveManifestWriter.originFrom(REACTOR);
        assertFalse(origin.isBlank(),
            "an unattributed demo teaches the wrong thing: the point of the origin field is that a "
                + "reader can see which dependency contributed a rule");
        assertEquals(2, origin.chars().filter(c -> c == ':').count(),
            "origin should read group:artifact:version, got '" + origin + "'");
    }

    @Test
    void thePublishingPackageStillDeclaresPackageLevelGuardrails() throws IOException {
        Path packageInfo = REACTOR.resolve("core/src/main/java")
            .resolve(PUBLISHED_PACKAGE.replace('.', '/')).resolve("package-info.java");
        assumeTrue(Files.isRegularFile(packageInfo), "package-info not reachable; skipping");

        String source = Files.readString(packageInfo, StandardCharsets.UTF_8);
        assertTrue(source.contains("@AISecure"),
            "the demo needs one safety-tier rule, or the 'Inherited Guardrails' heading never renders");
        assertTrue(source.contains("@AIContext") || source.contains("@AIThreadSafe"),
            "the demo needs an advisory-tier rule too, or the two tiers cannot be told apart");
        assertTrue(source.contains("package " + PUBLISHED_PACKAGE + ";"), packageInfo.toString());
    }

    @Test
    void everyConsumingModuleShowsTheInheritedRulesInItsRegion() throws IOException {
        Path claude = REACTOR.resolve("CLAUDE.md");
        assumeTrue(Files.isRegularFile(claude), "CLAUDE.md not reachable; skipping");
        String content = Files.readString(claude, StandardCharsets.UTF_8);
        assumeTrue(content.contains("VIBETAGS-MODULE:"), "reactor output not generated; skipping");

        for (String module : CONSUMING_MODULES) {
            String region = regionOf(content, module);
            assertTrue(region.contains("Inherited Guardrails (dependencies)"),
                module + " imports " + PUBLISHED_PACKAGE + " but its region carries no inherited "
                    + "safety rules — either the demo stopped importing it, or discovery broke:\n" + region);
            assertTrue(region.contains(PUBLISHED_PACKAGE), module + "'s region:\n" + region);
            assertTrue(region.contains(TransitiveManifestWriter.originFrom(REACTOR)),
                "every inherited rule must name the artifact it came from, in " + module);
        }
    }

    @Test
    void thePublishingModuleDoesNotInheritFromItself() throws IOException {
        // The lookup key is derived from imports, and core imports nothing of its own. A block here
        // would mean discovery had started matching on something other than what the sources use.
        Path claude = REACTOR.resolve("CLAUDE.md");
        assumeTrue(Files.isRegularFile(claude), "CLAUDE.md not reachable; skipping");
        String content = Files.readString(claude, StandardCharsets.UTF_8);
        assumeTrue(content.contains("VIBETAGS-MODULE: core"), "reactor output not generated; skipping");

        assertFalse(regionOf(content, "core").contains("Inherited Guardrails"),
            "core declares these rules; it must not also inherit them");
    }

    @Test
    void aModuleWithNoAnnotationsOfItsOwnStillAppearsWhenItInheritsSome() throws IOException {
        // A behaviour change worth pinning. Before transitive guardrails, a module contributed a
        // region to the reactor root only when its own sources carried annotations, and the demo's
        // tests/ module — which has none, and opts into .vibetags-mirror instead — was deliberately
        // absent. It imports com.example.multimodule.core, so it now inherits that package's rules
        // and genuinely has something to say about its own code.
        //
        // What must NOT have changed is why: mirroring still creates no region. If a mirrored rule
        // ever reached the aggregate, it would show here as a populated element section.
        Path claude = REACTOR.resolve("CLAUDE.md");
        assumeTrue(Files.isRegularFile(claude), "CLAUDE.md not reachable; skipping");
        String content = Files.readString(claude, StandardCharsets.UTF_8);
        assumeTrue(content.contains("VIBETAGS-MODULE: tests"), "tests region not generated; skipping");

        String region = regionOf(content, "tests");
        assertTrue(region.contains("Inherited Guardrails (dependencies)"),
            "the only thing that may put tests/ in the root merge is an inherited rule:\n" + region);
        assertFalse(region.contains("<file path="),
            "a mirrored rule must not reach the reactor root's aggregate:\n" + region);
    }

    /** The merged region a module contributed, between its sub-markers. */
    private static String regionOf(String content, String module) {
        String start = "<!-- VIBETAGS-MODULE: " + module + " -->";
        String end = "<!-- VIBETAGS-MODULE-END: " + module + " -->";
        int from = content.indexOf(start);
        int to = content.indexOf(end, from + 1);
        assertTrue(from >= 0 && to > from, "no region for module '" + module + "' in the merged output");
        return content.substring(from, to);
    }
}
