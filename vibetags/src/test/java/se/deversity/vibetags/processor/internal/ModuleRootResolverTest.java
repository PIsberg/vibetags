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
    void nearestBuildFileAncestor_buildFileIsDirectory_notTreatedAsMarker(@TempDir Path tmp) throws IOException {
        // A DIRECTORY named pom.xml must not mark a module root (isRegularFile check).
        Path odd = tmp.resolve("odd");
        Files.createDirectories(odd.resolve("pom.xml"));
        Files.createDirectories(odd.resolve("src"));

        assertNull(ModuleRootResolver.nearestBuildFileAncestor(odd.resolve("src")));
    }
}
