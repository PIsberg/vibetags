package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.internal.GranularRulesWriter;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Targets missed branch coverage in GranularRulesWriter.writeAll():
 *
 *   L51  AND chain (all true)   - no granular services active → early return empty set
 *   L51  AND chain (mid-short)  - individual non-cursor service active → AND short-circuits
 *   L65  false branch           - cursor_granular NOT in activeServices → if(cursorGranular) skipped
 */
class GranularRulesWriterUnitTest {

    private static Element namedClassElement(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name name = mock(Name.class);
        when(name.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(name);
        return e;
    }

    // ------------------------------------------------------------------
    // L51 AND chain: all conditions true → early return
    // ------------------------------------------------------------------

    /**
     * When no granular service is active every !serviceGranular flag is true,
     * so the AND chain at L51 evaluates to true and writeAll() returns immediately
     * with an empty set — no file I/O occurs.
     */
    @Test
    void writeAll_noGranularServices_returnsEmptySet(@TempDir Path tmp) {
        GuardrailFileWriter fileWriter = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        GranularRulesWriter writer = new GranularRulesWriter(fileWriter);

        Map<Element, StringBuilder> rules = new LinkedHashMap<>();
        rules.put(namedClassElement("com.example.Foo"),
                  new StringBuilder("## Locked\n- reason: test\n"));

        // Empty activeServices → all !serviceGranular are true → AND chain true → early return
        Set<String> written = writer.writeAll(rules, Map.of(), Set.of());
        assertTrue(written.isEmpty(), "no granular services must return empty set immediately");
    }

    // ------------------------------------------------------------------
    // L51 AND chain: individual non-cursor services → short-circuit; L65 false branch
    // ------------------------------------------------------------------

    /**
     * For each non-cursor granular service:
     *  - cursor_granular is NOT active   → L65 if(cursorGranular) false branch
     *  - the specific service IS active  → AND chain short-circuits at that position
     *  - a granular file must be written for that service
     *
     * Services and their expected output extensions:
     *   trae_granular        → .md  in trae_granular dir
     *   roo_granular         → .md  in roo_granular dir
     *   windsurf_granular    → .md  in windsurf_granular dir
     *   continue_granular    → .md  in continue_granular dir
     *   tabnine_granular     → .md  in tabnine_granular dir
     *   amazonq_granular     → .md  in amazonq_granular dir
     *   ai_rules_granular    → .md  in ai_rules_granular dir
     *   pearai_granular      → .md  in pearai_granular dir
     */
    /**
     * Verifies each non-cursor granular service individually using a fresh temp directory
     * created programmatically (avoids @TempDir/@ParameterizedTest resolver conflict).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "trae_granular",
        "roo_granular",
        "windsurf_granular",
        "continue_granular",
        "tabnine_granular",
        "amazonq_granular",
        "ai_rules_granular",
        "pearai_granular"
    })
    void writeAll_nonCursorGranularService_writesFile(String service) throws IOException {
        Path tmp = Files.createTempDirectory("vibetags-test-");
        try {
            GuardrailFileWriter fileWriter =
                new GuardrailFileWriter("# VibeTags\n", null, null, null);
            GranularRulesWriter writer = new GranularRulesWriter(fileWriter);

            Element elem = namedClassElement("com.example.Bar");
            Map<Element, StringBuilder> rules = new LinkedHashMap<>();
            rules.put(elem, new StringBuilder("## Locked\n- reason: test\n"));

            // Create the service directory so the writer can resolve the path
            Path serviceDir = tmp.resolve(service);
            Files.createDirectories(serviceDir);
            Map<String, Path> serviceFiles = Map.of(service, serviceDir);

            // cursor_granular is NOT in activeServices → L65 if(cursorGranular) is false
            Set<String> written = writer.writeAll(rules, serviceFiles, Set.of(service));

            assertFalse(written.isEmpty(),
                service + " active → writeAll must write at least one granular file");

            try (Stream<Path> files = Files.list(serviceDir)) {
                assertTrue(files.findFirst().isPresent(),
                    "At least one granular file must exist in " + service + " directory");
            }
        } finally {
            // Clean up temp dir
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            }
        }
    }
}
