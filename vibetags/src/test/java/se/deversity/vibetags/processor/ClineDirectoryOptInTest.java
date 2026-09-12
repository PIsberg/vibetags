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

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cline's current documentation describes {@code .clinerules/} as a <em>directory</em> of rule
 * files and does not mention the single file VibeTags writes. A user following those docs creates
 * a directory at exactly the path VibeTags maps the {@code cline} service to.
 *
 * <p>{@code resolveActiveServices} opts a service in on {@link Files#exists}, which is true for a
 * directory, so the aggregate service activates and the writer is then asked to write a regular
 * file over a directory.
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
}
