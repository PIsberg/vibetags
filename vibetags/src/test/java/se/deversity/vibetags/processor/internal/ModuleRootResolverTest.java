package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRootResolverTest {

    @Test
    void nearestBuildFileAncestor_findsMavenModuleRoot(@TempDir Path tmp) throws IOException {
        Path module = tmp.resolve("reactor").resolve("module-core");
        Path sourceDir = module.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        Files.createFile(module.resolve("pom.xml"));

        assertEquals(module, ModuleRootResolver.nearestBuildFileAncestor(sourceDir));
    }

    @Test
    void nearestBuildFileAncestor_findsGradleModuleRoot(@TempDir Path tmp) throws IOException {
        Path module = tmp.resolve("sub");
        Path sourceDir = module.resolve("src/main/java");
        Files.createDirectories(sourceDir);
        Files.createFile(module.resolve("build.gradle.kts"));

        assertEquals(module, ModuleRootResolver.nearestBuildFileAncestor(sourceDir));
    }

    @Test
    void nearestBuildFileAncestor_nestedModules_innermostWins(@TempDir Path tmp) throws IOException {
        // reactor/pom.xml AND reactor/a/pom.xml — sources in a/ belong to a/, not the reactor.
        Path reactor = tmp.resolve("reactor");
        Path inner = reactor.resolve("a");
        Path sourceDir = inner.resolve("src/main/java");
        Files.createDirectories(sourceDir);
        Files.createFile(reactor.resolve("pom.xml"));
        Files.createFile(inner.resolve("pom.xml"));

        assertEquals(inner, ModuleRootResolver.nearestBuildFileAncestor(sourceDir));
    }

    @Test
    void nearestBuildFileAncestor_noBuildFile_returnsNull(@TempDir Path tmp) throws IOException {
        Path sourceDir = tmp.resolve("plain/src");
        Files.createDirectories(sourceDir);

        // @TempDir ancestry contains no pom.xml/build.gradle — the walk must come up empty
        // (rather than, say, treating the temp root or filesystem root as a module).
        assertNull(ModuleRootResolver.nearestBuildFileAncestor(sourceDir));
    }

    @Test
    void nearestBuildFileAncestor_nullDir_returnsNull() {
        assertNull(ModuleRootResolver.nearestBuildFileAncestor(null));
    }

    @Test
    void hasBuildFile_identifiesDirectoriesWithBuildFiles(@TempDir Path tmp) throws IOException {
        assertFalse(ModuleRootResolver.hasBuildFile(tmp));
        assertFalse(ModuleRootResolver.hasBuildFile(null));

        Path pomDir = Files.createDirectory(tmp.resolve("pom"));
        Files.createFile(pomDir.resolve("pom.xml"));
        assertTrue(ModuleRootResolver.hasBuildFile(pomDir));

        Path gradleDir = Files.createDirectory(tmp.resolve("gradle"));
        Files.createFile(gradleDir.resolve("build.gradle"));
        assertTrue(ModuleRootResolver.hasBuildFile(gradleDir));

        Path ktsDir = Files.createDirectory(tmp.resolve("kts"));
        Files.createFile(ktsDir.resolve("build.gradle.kts"));
        assertTrue(ModuleRootResolver.hasBuildFile(ktsDir));
    }

    @Test
    void sourceSetOf_readsTheSegmentAfterSrc(@TempDir Path tmp) {
        Path module = tmp.resolve("module-core");
        assertEquals("main", ModuleRootResolver.sourceSetOf(module, module.resolve("src/main/java/com/example")));
        assertEquals("test", ModuleRootResolver.sourceSetOf(module, module.resolve("src/test/java/com/example")));
        // Gradle's extra source sets are first-class, not special-cased against a fixed list.
        assertEquals("integrationTest",
            ModuleRootResolver.sourceSetOf(module, module.resolve("src/integrationTest/java")));
    }

    @Test
    void sourceSetOf_offConventionLayout_returnsNull(@TempDir Path tmp) {
        Path module = tmp.resolve("module-core");
        // Generated sources live outside src/ — the caller then falls back to "main", preserving
        // the behaviour of every project that does not follow the convention.
        assertNull(ModuleRootResolver.sourceSetOf(module,
            module.resolve("target/generated-sources/annotations/com/example")));
        assertNull(ModuleRootResolver.sourceSetOf(module, module.resolve("src")));
    }

    @Test
    void nearestBuildFileAncestor_buildFileIsDirectory_notTreatedAsMarker(@TempDir Path tmp) throws IOException {
        // A DIRECTORY named pom.xml must not mark a module root (isRegularFile check).
        Path odd = tmp.resolve("odd");
        Files.createDirectories(odd.resolve("pom.xml"));
        Files.createDirectories(odd.resolve("src"));

        assertNull(ModuleRootResolver.nearestBuildFileAncestor(odd.resolve("src")));
    }

    @Test
    void fromRound_resolvesMainAndMixedSourceSets(@TempDir Path tmp) throws IOException {
        Path module = tmp.resolve("module-core");
        Path mainFile = module.resolve("src/main/java/com/example/App.java");
        Path testFile = module.resolve("src/test/java/com/example/AppTest.java");
        Files.createDirectories(mainFile.getParent());
        Files.createDirectories(testFile.getParent());
        Files.createFile(module.resolve("pom.xml"));
        Files.createFile(mainFile);
        Files.createFile(testFile);

        javax.lang.model.element.Element mainEl = org.mockito.Mockito.mock(javax.lang.model.element.Element.class);
        javax.lang.model.element.Element testEl = org.mockito.Mockito.mock(javax.lang.model.element.Element.class);
        RoundSources mainOnly = roundSourcesFor(java.util.Map.of(mainEl, mainFile));

        ModuleIdentity single = ModuleRootResolver.fromRound(mainOnly);
        assertNotNull(single);
        assertEquals(module, single.root());
        assertEquals("main", single.sourceSet());
        assertFalse(single.mixedSourceSets());

        java.util.Map<javax.lang.model.element.Element, Path> bothMap = new java.util.LinkedHashMap<>();
        bothMap.put(mainEl, mainFile);
        bothMap.put(testEl, testFile);
        RoundSources mixed = roundSourcesFor(bothMap);

        ModuleIdentity both = ModuleRootResolver.fromRound(mixed);
        assertNotNull(both);
        assertEquals(module, both.root());
        assertEquals("main", both.sourceSet());
        assertTrue(both.mixedSourceSets());
    }

    @Test
    void withRecordedOwner_reassignsAnExternalSourceRootToItsRecordingModule(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        Path sharedFile = tmp.resolve("shared/src/main/java/com/example/Shared.java");
        Files.createDirectories(app.resolve("src/main/java"));
        Files.createDirectories(sharedFile.getParent());
        Files.createFile(tmp.resolve("pom.xml"));
        Files.createFile(app.resolve("pom.xml"));
        Files.createFile(sharedFile);

        ModuleSidecar appSidecar = new ModuleSidecar("app", "app");
        appSidecar.setSourceRoots(java.util.Set.of("app/src/main/java", "shared/src/main/java"));
        appSidecar.save(tmp);

        javax.lang.model.element.Element sharedEl = org.mockito.Mockito.mock(javax.lang.model.element.Element.class);
        RoundSources sources = roundSourcesFor(java.util.Map.of(sharedEl, sharedFile));

        ModuleIdentity resolvedToRoot = new ModuleIdentity(tmp, "main");
        ModuleIdentity reassigned = ModuleRootResolver.withRecordedOwner(resolvedToRoot, sources, tmp);
        assertNotNull(reassigned);
        assertEquals(app.toAbsolutePath().normalize(), reassigned.root());
        assertEquals("main", reassigned.sourceSet());
    }

    @Test
    void withRecordedOwner_keepsResolvedWhenAmbiguousOrAlreadyConventional(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        Path cli = tmp.resolve("cli");
        Path sharedFile = tmp.resolve("shared/src/main/java/com/example/Shared.java");
        Path conventionalFile = app.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(conventionalFile.getParent());
        Files.createDirectories(cli.resolve("src/main/java"));
        Files.createDirectories(sharedFile.getParent());
        Files.createFile(tmp.resolve("pom.xml"));
        Files.createFile(app.resolve("pom.xml"));
        Files.createFile(cli.resolve("pom.xml"));
        Files.createFile(sharedFile);
        Files.createFile(conventionalFile);

        for (String mod : java.util.List.of("app", "cli")) {
            ModuleSidecar sidecar = new ModuleSidecar(mod, mod);
            sidecar.setSourceRoots(java.util.Set.of(mod + "/src/main/java", "shared/src/main/java"));
            sidecar.save(tmp);
        }

        javax.lang.model.element.Element sharedEl = org.mockito.Mockito.mock(javax.lang.model.element.Element.class);
        RoundSources ambiguousSources = roundSourcesFor(java.util.Map.of(sharedEl, sharedFile));
        ModuleIdentity resolvedToRoot = new ModuleIdentity(tmp, "main");
        assertSame(resolvedToRoot, ModuleRootResolver.withRecordedOwner(resolvedToRoot, ambiguousSources, tmp),
            "when two modules claim the same external root, neither wins");

        javax.lang.model.element.Element convEl = org.mockito.Mockito.mock(javax.lang.model.element.Element.class);
        RoundSources conventionalSources = roundSourcesFor(java.util.Map.of(convEl, conventionalFile));
        ModuleIdentity resolvedToApp = new ModuleIdentity(app, "main");
        assertSame(resolvedToApp, ModuleRootResolver.withRecordedOwner(resolvedToApp, conventionalSources, tmp),
            "files already under <resolved>/src return resolved immediately");
    }

    private static RoundSources roundSourcesFor(java.util.Map<javax.lang.model.element.Element, Path> elementFiles) {
        javax.annotation.processing.ProcessingEnvironment env =
            org.mockito.Mockito.mock(javax.annotation.processing.ProcessingEnvironment.class);
        javax.lang.model.util.Elements elements =
            org.mockito.Mockito.mock(javax.lang.model.util.Elements.class);
        org.mockito.Mockito.when(env.getElementUtils()).thenReturn(elements);
        for (var entry : elementFiles.entrySet()) {
            javax.tools.JavaFileObject jfo = org.mockito.Mockito.mock(javax.tools.JavaFileObject.class);
            org.mockito.Mockito.when(jfo.toUri()).thenReturn(entry.getValue().toUri());
            org.mockito.Mockito.when(elements.getFileObjectOf(entry.getKey())).thenReturn(jfo);
        }
        javax.annotation.processing.RoundEnvironment roundEnv =
            org.mockito.Mockito.mock(javax.annotation.processing.RoundEnvironment.class);
        org.mockito.Mockito.doReturn(new java.util.LinkedHashSet<>(elementFiles.keySet()))
            .when(roundEnv).getRootElements();
        return RoundSources.of(env, roundEnv);
    }
}
