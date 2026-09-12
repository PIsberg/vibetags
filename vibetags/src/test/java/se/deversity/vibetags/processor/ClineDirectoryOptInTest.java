package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cline reads {@code .clinerules} in two shapes at one path: the directory of rule files its
 * current documentation describes, and the single file VibeTags has written since v0.9.7, which
 * Cline's loader still reads. VibeTags maps both, as {@code cline_granular} and {@code cline}.
 *
 * <p>A path is a file or a directory, never both, so the entry's type decides which of the two
 * services the user opted into. Before that, {@code resolveActiveServices} opted a service in on
 * {@link Files#exists}, which is true for a directory, so a {@code .clinerules/} directory
 * activated the single-file service and the writer was asked to write a regular file over it.
 */
@Tag("e2e")
class ClineDirectoryOptInTest {

    @Test
    void aClinerulesDirectoryDoesNotActivateTheSingleFileService(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".clinerules"));

        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        Set<String> active = ServiceRegistry.resolveActiveServices(serviceFiles);

        assertFalse(active.contains("cline"),
            "a .clinerules/ directory is Cline's current documented shape, not an opt-in to the "
                + "single .clinerules file. Activating the file service points the writer at a "
                + "directory.");
    }

    @Test
    void aClinerulesDirectoryActivatesExactlyTheDirectoryService(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".clinerules"));

        Set<String> active = ServiceRegistry.resolveActiveServices(ServiceRegistry.buildServiceFileMap(root));

        assertEquals(Set.of("cline_granular"), active,
            "a user following Cline's current docs creates .clinerules/ and must get Cline's "
                + "directory form, not nothing");
    }

    @Test
    void aClinerulesFileActivatesExactlyTheFileService(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve(".clinerules"));

        Set<String> active = ServiceRegistry.resolveActiveServices(ServiceRegistry.buildServiceFileMap(root));

        assertEquals(Set.of("cline"), active,
            "an existing .clinerules file must keep regenerating as a file; the directory service "
                + "mapped to the same path must not also claim it");
    }
}
