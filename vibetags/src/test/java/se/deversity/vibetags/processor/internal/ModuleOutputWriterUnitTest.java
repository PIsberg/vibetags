package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ModuleOutputWriter} unit tests: source set suffix derivation, early exit guards,
 * module-scoped aggregate writes, sidecar body merges, and note emission.
 */
@DisplayName("Module output writer rules")
class ModuleOutputWriterUnitTest {

    @Test
    void sourceSetSuffix_handlesNullAndSeparators() {
        assertEquals("", ModuleOutputWriter.sourceSetSuffix(null, "mod__test"),
            "null regionId must yield empty suffix");
        assertEquals("", ModuleOutputWriter.sourceSetSuffix("mod", null),
            "null moduleId must yield empty suffix");
        assertEquals("", ModuleOutputWriter.sourceSetSuffix("mod", "other__test"),
            "non-matching prefix must yield empty suffix");
        assertEquals("", ModuleOutputWriter.sourceSetSuffix("mod", "mod"),
            "missing separator must yield empty suffix");
        assertEquals("", ModuleOutputWriter.sourceSetSuffix("mod", "mod_no_sep"),
            "different separator must yield empty suffix");

        assertEquals("__test", ModuleOutputWriter.sourceSetSuffix("mod", "mod__test"),
            "valid source-set suffix must be returned");
        assertEquals("__integration__test", ModuleOutputWriter.sourceSetSuffix("mod", "mod__integration__test"),
            "nested underscores must be preserved in suffix");
    }

    @Test
    void write_earlyExits_whenRootsOrActiveAreInvalid(@TempDir Path root) {
        GuardrailFileWriter writer = mock(GuardrailFileWriter.class);
        AnnotationCollector collector = new AnnotationCollector();

        // moduleRoot is null
        ModuleOutputWriter.write(null, root, Map.of(), Set.of("claude"), collector,
            "project", "header", null, writer, null);

        // moduleRoot equals vibetagsRoot (module is the root)
        ModuleOutputWriter.write(root, root, Map.of(), Set.of("claude"), collector,
            "project", "header", null, writer, null);

        // moduleActive is empty
        Path subModule = root.resolve("sub");
        ModuleOutputWriter.write(subModule, root, Map.of(), Set.of(), collector,
            "project", "header", null, writer, null);

        verify(writer, never()).writeFileIfChanged(anyString(), anyString(), anyBoolean());
    }

    @Test
    void write_moduleScopedAggregate_writesFileAndEmitsNote(@TempDir Path root) throws IOException {
        Path subModule = root.resolve("sub-module");
        Files.createDirectory(subModule);

        Path claudePath = subModule.resolve("CLAUDE.md");
        GuardrailFileWriter writer = mock(GuardrailFileWriter.class);
        AnnotationCollector collector = new AnnotationCollector();

        List<String> notes = new ArrayList<>();
        Messager messager = mock(Messager.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Diagnostic.Kind kind = invocation.getArgument(0);
            CharSequence msg = invocation.getArgument(1);
            if (kind == Diagnostic.Kind.NOTE) {
                notes.add(msg.toString());
            }
            return null;
        }).when(messager).printMessage(eq(Diagnostic.Kind.NOTE), anyString());

        GuardrailContentBuilder.Result prebuilt = new GuardrailContentBuilder.Result(
            Map.of("claude", "# Module Claude Rules", "unmapped", "# Unmapped Content"),
            Map.of());

        Map<String, Path> moduleFiles = Map.of("claude", claudePath);
        Set<String> moduleActive = Set.of("claude");

        ModuleOutputWriter.write(subModule, root, moduleFiles, moduleActive, collector, prebuilt,
            "demo", "# Header", null, writer, messager, List.of(), null, null);

        verify(writer).writeFileIfChanged(eq(claudePath.toString()), eq("# Module Claude Rules"), eq(false));

        assertEquals(1, notes.size(), "must emit one summary note");
        assertTrue(notes.get(0).contains("VibeTags: wrote 1 module-scoped file(s) under sub-module"),
            "note must report written count and sub-module path: " + notes.get(0));
    }

    @Test
    void write_mergesModuleBodiesAcrossSourceSets(@TempDir Path root) throws IOException {
        Path subModule = root.resolve("core");
        Files.createDirectory(subModule);

        Path claudePath = subModule.resolve("CLAUDE.md");
        GuardrailFileWriter writer = mock(GuardrailFileWriter.class);
        AnnotationCollector collector = new AnnotationCollector();

        ModuleSidecar mainSidecar = new ModuleSidecar("core", "core", "core");
        mainSidecar.putModuleBody("claude", "<!-- VIBETAGS-START -->\nmain rules\n<!-- VIBETAGS-END -->\n");

        ModuleSidecar testSidecar = new ModuleSidecar("core__test", "core", "core");
        testSidecar.putModuleBody("claude", "<!-- VIBETAGS-START -->\ntest rules\n<!-- VIBETAGS-END -->\n");

        GuardrailContentBuilder.Result prebuilt = new GuardrailContentBuilder.Result(
            Map.of("claude", "local rules"), Map.of());

        Map<String, Path> moduleFiles = Map.of("claude", claudePath);
        Set<String> moduleActive = Set.of("claude");

        ModuleOutputWriter.write(subModule, root, moduleFiles, moduleActive, collector, prebuilt,
            "demo", "# Header", null, writer, null,
            List.of(mainSidecar, testSidecar), "core", "core__test");

        String expectedMerged = ModuleSidecar.mergeModuleBodies("claude",
            List.of(mainSidecar, testSidecar), "core");

        verify(writer).writeFileIfChanged(eq(claudePath.toString()), eq(expectedMerged), eq(false));
    }

    @Test
    void write_ignoreService_alwaysCarriesHasNewRules(@TempDir Path root) throws IOException {
        Path subModule = root.resolve("api");
        Files.createDirectory(subModule);

        Path ignorePath = subModule.resolve(".cursorignore");
        GuardrailFileWriter writer = mock(GuardrailFileWriter.class);
        AnnotationCollector collector = new AnnotationCollector(); // no annotations

        GuardrailContentBuilder.Result prebuilt = new GuardrailContentBuilder.Result(
            Map.of("cursor_ignore", "**/target/**\n"), Map.of());

        Map<String, Path> moduleFiles = Map.of("cursor_ignore", ignorePath);
        Set<String> moduleActive = Set.of("cursor_ignore");

        ModuleOutputWriter.write(subModule, root, moduleFiles, moduleActive, collector, prebuilt,
            "demo", "# Header", null, writer, null, List.of(), null, null);

        verify(writer).writeFileIfChanged(eq(ignorePath.toString()), eq("**/target/**\n"), eq(true));
    }

    @Test
    void singleModuleOverload_delegatesSuccessfully(@TempDir Path root) throws IOException {
        Path subModule = root.resolve("lib");
        Files.createDirectory(subModule);

        Path claudePath = subModule.resolve("CLAUDE.md");
        GuardrailFileWriter writer = mock(GuardrailFileWriter.class);
        AnnotationCollector collector = new AnnotationCollector();

        Map<String, Path> moduleFiles = Map.of("claude", claudePath);
        Set<String> moduleActive = Set.of("claude");

        ModuleOutputWriter.write(subModule, root, moduleFiles, moduleActive, collector,
            "demo", "# Header", null, writer, null);

        verify(writer).writeFileIfChanged(eq(claudePath.toString()), anyString(), anyBoolean());
    }
}
