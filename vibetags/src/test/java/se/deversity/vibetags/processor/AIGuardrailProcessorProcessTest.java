package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPrivacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the process() method and remaining uncovered branches in AIGuardrailProcessor.
 */
@SuppressWarnings("unchecked")
class AIGuardrailProcessorProcessTest {

    // -----------------------------------------------------------------------
    // process() — early-return paths
    // -----------------------------------------------------------------------

    @Test
    void process_processingOver_returnsEarlyWithFalse() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        ProcessingEnvironment env = mockEnv(noopMessager());
        processor.init(env);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(true);

        boolean result = processor.process(Set.of(), roundEnv);

        assertFalse(result);
        // Nothing should have been read from the round environment
        verify(roundEnv, never()).getElementsAnnotatedWith(any(java.lang.Class.class));
    }

    @Test
    void process_idempotent_secondCallIsNoOp() {
        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        // First call with processingOver=true triggers generateFiles()
        RoundEnvironment roundEnv1 = mock(RoundEnvironment.class);
        when(roundEnv1.processingOver()).thenReturn(true);
        doReturn(java.util.Set.of()).when(roundEnv1).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv1).getElementsAnnotatedWith(AIContext.class);
        doReturn(java.util.Set.of()).when(roundEnv1).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(java.util.Set.of()).when(roundEnv1).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv1).getElementsAnnotatedWith(AIDraft.class);
        doReturn(java.util.Set.of()).when(roundEnv1).getElementsAnnotatedWith(AIPrivacy.class);

        processor.process(Set.of(), roundEnv1);
        int notesAfterFirst = notes.size();
        assertTrue(notesAfterFirst > 0, "First call should produce at least one NOTE");

        // Second call must be a no-op — no additional messages
        RoundEnvironment roundEnv2 = mock(RoundEnvironment.class);
        when(roundEnv2.processingOver()).thenReturn(true);
        processor.process(Set.of(), roundEnv2);
        assertEquals(notesAfterFirst, notes.size(),
            "Second call must not produce any additional messages (idempotency guard)");
    }

    @Test
    void process_alwaysReturnsFalse() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(noopMessager()));

        boolean result = processor.process(Set.of(), emptyRoundEnv());

        assertFalse(result, "process() must return false to not claim annotations");
    }

    @Test
    void process_noSignalFiles_emitsNote() {
        // vibetags/ has no signal files so resolveActiveServices will find nothing
        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(true);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.process(Set.of(), roundEnv);

        assertTrue(notes.stream().anyMatch(n -> n.contains("No AI config files found")),
            "Should emit a NOTE when no signal files are present");
    }

    @Test
    void process_withContradictoryAnnotations_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        // Element that has BOTH @AILocked and @AIDraft (a contradiction)
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.BadClass");
        when(element.getSimpleName()).thenReturn(nameOf("BadClass"));
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);
        doReturn(java.util.Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.process(Set.of(), roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("both @AIDraft and @AILocked")),
            "Should warn about AIDraft+AILocked contradiction");
    }

    @Test
    void process_withEmptyAuditAnnotation_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.UnconfiguredAudit");
        when(element.getSimpleName()).thenReturn(nameOf("UnconfiguredAudit"));
        AIAudit emptyAudit = mock(AIAudit.class);
        when(emptyAudit.checkFor()).thenReturn(new String[0]);
        when(element.getAnnotation(AIAudit.class)).thenReturn(emptyAudit);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(java.util.Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.process(Set.of(), roundEnv);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("has no 'checkFor' items list")),
            "Should warn about @AIAudit with empty checkFor");
    }

    // -----------------------------------------------------------------------
    // checkOrphanedAnnotations() — uncovered branches
    // -----------------------------------------------------------------------

    @Test
    void checkOrphanedAnnotations_copilotActiveNoIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Set<String> active = Set.of("copilot");
        processor.checkOrphanedAnnotations(messager, active, false, true, false);

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".copilotignore")),
            "Should warn about missing .copilotignore when copilot is active");
    }

    @Test
    void checkOrphanedAnnotations_codexActiveNoAiExclude_withIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Set<String> active = Set.of("codex");
        processor.checkOrphanedAnnotations(messager, active, false, true, false);

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".aiexclude")),
            "Should warn about missing .aiexclude when codex is active and @AIIgnore is used");
    }

    @Test
    void checkOrphanedAnnotations_hasIgnoreFalse_noWarningsEmitted() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        // hasIgnore=false means no @AIIgnore annotations present — no warnings should fire
        Set<String> active = Set.of("cursor", "claude", "copilot", "qwen", "gemini", "codex");
        processor.checkOrphanedAnnotations(messager, active, false, false, false);

        assertTrue(warnings.isEmpty(),
            "No warnings should be emitted when hasIgnore=false");
    }

    @Test
    void checkOrphanedAnnotations_hasLockedTrue_codexActiveNoAiExclude_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Set<String> active = Set.of("codex");
        processor.checkOrphanedAnnotations(messager, active, true, false, false);

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".aiexclude")),
            "Should warn about .aiexclude when @AILocked is used and codex is active");
    }

    @Test
    void checkOrphanedAnnotations_hasLockedTrue_geminiActiveNoAiExclude_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Set<String> active = Set.of("gemini");
        processor.checkOrphanedAnnotations(messager, active, true, false, false);

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".aiexclude")),
            "Should warn about .aiexclude when @AILocked is used and gemini is active");
    }

    @Test
    void checkOrphanedAnnotations_aiexcludePresent_noAiExcludeWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Set<String> active = Set.of("gemini", "codex", "aiexclude");
        processor.checkOrphanedAnnotations(messager, active, true, true, false);

        assertFalse(warnings.stream().anyMatch(w -> w.contains(".aiexclude")),
            "No .aiexclude warning when .aiexclude is already in active set");
    }

    @Test
    void checkOrphanedAnnotations_allIgnoreFilesPresent_noWarnings() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Set<String> active = Set.of(
            "cursor", "cursor_ignore",
            "claude", "claude_ignore",
            "copilot", "copilot_ignore",
            "qwen", "qwen_ignore",
            "gemini", "codex", "aiexclude"
        );
        processor.checkOrphanedAnnotations(messager, active, true, true, false);

        assertTrue(warnings.isEmpty(),
            "No warnings when all ignore and aiexclude files are present");
    }

    @Test
    void checkOrphanedAnnotations_emptyActiveSet_noWarnings() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        processor.checkOrphanedAnnotations(messager, Set.of(), true, true, false);

        assertTrue(warnings.isEmpty(),
            "No warnings when no services are active");
    }

    // -----------------------------------------------------------------------
    // buildServiceFileMap() — completeness check
    // -----------------------------------------------------------------------

    @Test
    void buildServiceFileMap_containsAllExpectedKeys() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        Map<String, Path> map = AIGuardrailProcessor.buildServiceFileMap(root);

        Set<String> expectedKeys = Set.of(
            "cursor", "claude", "aiexclude", "codex", "gemini", "copilot", "qwen",
            "cursor_ignore", "claude_ignore", "copilot_ignore", "qwen_ignore",
            "codex_config", "codex_rules", "qwen_settings", "qwen_refactor",
            "llms", "llms_full", "aider_conventions", "aider_ignore",
            "cursor_granular", "roo_granular", "trae_granular"
        );
        assertEquals(expectedKeys, map.keySet(),
            "buildServiceFileMap must return exactly the expected set of keys");
    }

    @Test
    void buildServiceFileMap_pathsAreUnderRoot() {
        Path root = Path.of("/some/project/root");
        Map<String, Path> map = AIGuardrailProcessor.buildServiceFileMap(root);

        for (Map.Entry<String, Path> entry : map.entrySet()) {
            assertTrue(entry.getValue().startsWith(root),
                "Path for '" + entry.getKey() + "' must be under the root");
        }
    }

    @Test
    void buildServiceFileMap_specificPaths() {
        Path root = Path.of("/proj");
        Map<String, Path> map = AIGuardrailProcessor.buildServiceFileMap(root);

        assertEquals(root.resolve(".cursorrules"),                      map.get("cursor"));
        assertEquals(root.resolve("CLAUDE.md"),                         map.get("claude"));
        assertEquals(root.resolve(".aiexclude"),                        map.get("aiexclude"));
        assertEquals(root.resolve("AGENTS.md"),                         map.get("codex"));
        assertEquals(root.resolve("gemini_instructions.md"),            map.get("gemini"));
        assertEquals(root.resolve(".github/copilot-instructions.md"),   map.get("copilot"));
        assertEquals(root.resolve("QWEN.md"),                           map.get("qwen"));
        assertEquals(root.resolve(".cursorignore"),                     map.get("cursor_ignore"));
        assertEquals(root.resolve(".claudeignore"),                     map.get("claude_ignore"));
        assertEquals(root.resolve(".copilotignore"),                    map.get("copilot_ignore"));
        assertEquals(root.resolve(".qwenignore"),                       map.get("qwen_ignore"));
        assertEquals(root.resolve(".codex/config.toml"),                map.get("codex_config"));
        assertEquals(root.resolve(".codex/rules/vibetags.rules"),       map.get("codex_rules"));
        assertEquals(root.resolve(".qwen/settings.json"),               map.get("qwen_settings"));
        assertEquals(root.resolve(".qwen/commands/refactor.md"),        map.get("qwen_refactor"));
        assertEquals(root.resolve("llms.txt"),                          map.get("llms"));
        assertEquals(root.resolve("llms-full.txt"),                     map.get("llms_full"));
    }

    // -----------------------------------------------------------------------
    // writeFileIfChanged() — edge cases
    // -----------------------------------------------------------------------

    @Test
    void writeFileIfChanged_fileDoesNotExist_createsFileAndReturnsTrue(@TempDir Path tempDir) throws IOException {
        Path newFile = tempDir.resolve("new-file.md");
        assertFalse(Files.exists(newFile), "Precondition: file must not exist");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(newFile.toString(), "hello world", true);

        assertTrue(changed, "Should return true when creating a new file");
        assertTrue(Files.exists(newFile), "File should now exist");
        assertTrue(Files.readString(newFile).contains("hello world"));
    }

    @Test
    void writeFileIfChanged_nestedDirDoesNotExist_createsParentAndFile(@TempDir Path tempDir) throws IOException {
        Path nested = tempDir.resolve("sub/dir/output.md");
        assertFalse(Files.exists(nested.getParent()), "Precondition: parent dir must not exist");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(nested.toString(), "nested content", true);

        assertTrue(changed);
        assertTrue(Files.exists(nested), "Nested file should be created");
        assertTrue(Files.readString(nested).contains("nested content"));
    }

    @Test
    void writeFileIfChanged_unwritablePath_returnsFalseWithWarning(@TempDir Path tempDir) throws IOException {
        // Create a file and then try to write a child path under it — parent is a file, not a dir
        Path parentAsFile = tempDir.resolve("i-am-a-file");
        Files.createFile(parentAsFile);
        Path impossiblePath = parentAsFile.resolve("child.md");

        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        boolean result = processor.writeFileIfChanged(impossiblePath.toString(), "content", true);

        assertFalse(result, "Should return false when write fails");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Failed to write AI rules file")),
            "Should emit a WARNING when write fails");
    }

    @Test
    void writeFileIfChanged_unicodeContent_preservedCorrectly(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("unicode.md");
        String content = "# 🛡️ Security\nFocus: performance\nAvoid: java.util.regex\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(file.toString(), content, true);

        String written = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(written.contains("🛡️"), "Unicode content must be preserved");
        assertTrue(written.contains("java.util.regex"), "ASCII content must be preserved");
    }

    @Test
    void writeFileIfChanged_multipleRoundTrips_onlyWritesWhenChanged(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("roundtrip.md");
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        // First write
        assertTrue(processor.writeFileIfChanged(file.toString(), "v1", true));
        // Same content — no rewrite
        assertFalse(processor.writeFileIfChanged(file.toString(), "v1", true));
        // Different content — rewrite
        assertTrue(processor.writeFileIfChanged(file.toString(), "v2", true));
        // Same again — no rewrite
        assertFalse(processor.writeFileIfChanged(file.toString(), "v2", true));
    }

    // -----------------------------------------------------------------------
    // validateAnnotations() — edge cases
    // -----------------------------------------------------------------------

    @Test
    void validateAnnotations_noAnnotations_noWarnings() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(), "No warnings when there are no annotations");
    }

    @Test
    void validateAnnotations_lockedWithoutDraft_noContradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SomeClass");
        AILocked locked = mock(AILocked.class);
        when(element.getAnnotation(AILocked.class)).thenReturn(locked);
        when(element.getAnnotation(AIDraft.class)).thenReturn(null); // no @AIDraft

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(java.util.Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No contradiction warning when @AILocked is used without @AIDraft");
    }

    @Test
    void validateAnnotations_auditWithItems_noEmptyWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.SomeClass");
        AIAudit audit = mock(AIAudit.class);
        when(audit.checkFor()).thenReturn(new String[]{"SQL Injection"});
        when(element.getAnnotation(AIAudit.class)).thenReturn(audit);

        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of(element)).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        processor.validateAnnotations(messager, roundEnv);

        assertTrue(warnings.isEmpty(),
            "No warning when @AIAudit has non-empty checkFor list");
    }

    // -----------------------------------------------------------------------
    // llms.txt / llms-full.txt — opt-in and content tests
    // -----------------------------------------------------------------------

    @Test
    void buildServiceFileMap_llmsPathsAreCorrect() {
        Path root = Path.of("/proj");
        Map<String, Path> map = AIGuardrailProcessor.buildServiceFileMap(root);

        assertEquals(root.resolve("llms.txt"),      map.get("llms"),      "llms should resolve to llms.txt");
        assertEquals(root.resolve("llms-full.txt"), map.get("llms_full"), "llms_full should resolve to llms-full.txt");
    }

    @Test
    void resolveActiveServices_llmsTxtPresent_activatesLlms(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("llms.txt"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertTrue(active.contains("llms"), "llms should be active when llms.txt exists");
        assertFalse(active.contains("llms_full"), "llms_full should not be active without llms-full.txt");
    }

    @Test
    void resolveActiveServices_llmsFullTxtPresent_activatesLlmsFull(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("llms-full.txt"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertFalse(active.contains("llms"), "llms should not be active without llms.txt");
        assertTrue(active.contains("llms_full"), "llms_full should be active when llms-full.txt exists");
    }

    @Test
    void resolveActiveServices_bothLlmsFilesPresent_activatesBoth(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("llms.txt"));
        Files.createFile(tempDir.resolve("llms-full.txt"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertTrue(active.contains("llms"),      "llms should be active when llms.txt exists");
        assertTrue(active.contains("llms_full"), "llms_full should be active when llms-full.txt exists");
    }

    @Test
    void process_llmsTxtOptIn_writesLlmsTxtWithCorrectFormat(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("llms.txt"));

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        Map<String, String> options = Map.of("vibetags.root", tempDir.toString());
        when(env.getOptions()).thenReturn(options);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(env);
        try {
            processor.process(Set.of(), emptyRoundEnv());
            triggerGeneration(processor);
            String content = Files.readString(tempDir.resolve("llms.txt"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(content.contains("## Locked Files"),      "llms.txt should have Locked Files section");
            assertTrue(content.contains("## Contextual Rules"),  "llms.txt should have Contextual Rules section");
            assertTrue(content.contains("> AI guardrail rules"), "llms.txt should have summary blockquote");
        } finally {
            VibeTagsLogger.shutdown(); // release file handle so @TempDir can be deleted
        }
    }

    @Test
    void process_llmsFullTxtOptIn_writesLlmsFullTxtWithCorrectFormat(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("llms-full.txt"));

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        Map<String, String> options = Map.of("vibetags.root", tempDir.toString());
        when(env.getOptions()).thenReturn(options);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(env);
        try {
            processor.process(Set.of(), emptyRoundEnv());
            triggerGeneration(processor);
            String content = Files.readString(tempDir.resolve("llms-full.txt"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(content.contains("## Locked Files (Do Not Edit)"), "llms-full.txt should have expanded Locked Files header");
            assertTrue(content.contains("## Contextual Rules"),            "llms-full.txt should have Contextual Rules section");
            assertTrue(content.contains("> Complete AI guardrail"),        "llms-full.txt should have full summary blockquote");
        } finally {
            VibeTagsLogger.shutdown(); // release file handle so @TempDir can be deleted
        }
    }

    // -----------------------------------------------------------------------
    // resolveActiveServices — Aider opt-in
    // -----------------------------------------------------------------------

    @Test
    void resolveActiveServices_conventionsMdPresent_activatesAider(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CONVENTIONS.md"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertTrue(active.contains("aider_conventions"),
            "aider_conventions should be active when CONVENTIONS.md exists");
    }

    @Test
    void resolveActiveServices_aiderIgnorePresent_activatesAiderIgnore(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve(".aiderignore"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertTrue(active.contains("aider_ignore"),
            "aider_ignore should be active when .aiderignore exists");
    }

    // -----------------------------------------------------------------------
    // writeFileIfChanged — marker-based update paths
    // -----------------------------------------------------------------------

    @Test
    void writeFileIfChanged_fileWithExistingMarkers_updatesSection(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("CLAUDE.md");
        String initialContent = "# Human content\n\n<!-- VIBETAGS-START -->\nold rule\n<!-- VIBETAGS-END -->\n";
        Files.writeString(mdFile, initialContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(mdFile.toString(), "new rule content", true);

        assertTrue(changed, "Should return true when marker section changes");
        String written = Files.readString(mdFile);
        assertTrue(written.contains("new rule content"), "New content should be inside markers");
        assertFalse(written.contains("old rule"), "Old rule should be replaced");
        assertTrue(written.contains("<!-- VIBETAGS-START -->"), "Markers should be preserved");
        assertTrue(written.contains("# Human content"), "Human content before markers should be preserved");
    }

    @Test
    void writeFileIfChanged_fileWithExistingMarkers_sameContent_returnsFalse(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("test.md");
        String body = "the rule";
        String markedContent = "<!-- VIBETAGS-START -->\n" + body + "\n<!-- VIBETAGS-END -->\n";
        Files.writeString(mdFile, markedContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(mdFile.toString(), body, true);

        assertFalse(changed, "Should return false when content has not changed");
    }

    @Test
    void writeFileIfChanged_hashMarkerFile_updatesSection(@TempDir Path tempDir) throws IOException {
        Path rulesFile = tempDir.resolve(".cursorrules");
        String initial = "# Human header\n# VIBETAGS-START\nold content\n# VIBETAGS-END\n";
        Files.writeString(rulesFile, initial);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(rulesFile.toString(), "new content", true);

        assertTrue(changed, "Should update hash-commented marker files");
        String written = Files.readString(rulesFile);
        assertTrue(written.contains("new content"));
        assertFalse(written.contains("old content"));
    }

    @Test
    void writeFileIfChanged_hasNewRulesFalse_existingNonEmptyFile_skips(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("CLAUDE.md");
        String existingMarked = "<!-- VIBETAGS-START -->\nexisting content\n<!-- VIBETAGS-END -->\n";
        Files.writeString(mdFile, existingMarked);

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        boolean changed = processor.writeFileIfChanged(mdFile.toString(), "different content", false);

        assertFalse(changed, "Should skip update when hasNewRules=false and file already has content");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Skipping update")),
            "Should emit a skip NOTE for multi-module preservation");
    }

    @Test
    void writeFileIfChanged_jsonFile_completeOverwrite(@TempDir Path tempDir) throws IOException {
        Path jsonFile = tempDir.resolve(".qwen/settings.json");
        Files.createDirectories(jsonFile.getParent());
        Files.writeString(jsonFile, "{\"old\": true}");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(jsonFile.toString(), "{\"new\": true}", true);

        assertTrue(changed, "JSON files should be completely overwritten");
        assertEquals("{\"new\": true}", Files.readString(jsonFile).strip());
    }

    @Test
    void writeFileIfChanged_tomlFile_completeOverwrite(@TempDir Path tempDir) throws IOException {
        Path tomlFile = tempDir.resolve(".codex/config.toml");
        Files.createDirectories(tomlFile.getParent());
        Files.writeString(tomlFile, "model = \"old\"");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(tomlFile.toString(), "model = \"new\"", true);

        assertTrue(changed, "TOML files should be completely overwritten");
        assertEquals("model = \"new\"", Files.readString(tomlFile).strip());
    }

    // -----------------------------------------------------------------------
    // stripLegacyVibeTagsBlock — unit coverage
    // -----------------------------------------------------------------------

    @Test
    void stripLegacyVibeTagsBlock_nullInput_returnsNull() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        assertNull(processor.stripLegacyVibeTagsBlock(null));
    }

    @Test
    void stripLegacyVibeTagsBlock_emptyInput_returnsEmpty() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        assertEquals("", processor.stripLegacyVibeTagsBlock(""));
    }

    @Test
    void stripLegacyVibeTagsBlock_noVibatagsHeader_returnsUnchanged() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        String input = "# Some human content\nWith no VibeTags header";
        assertEquals(input, processor.stripLegacyVibeTagsBlock(input));
    }

    @Test
    void stripLegacyVibeTagsBlock_withBareHeader_stripsBlock() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // The bare (hash-comment) form of the header as written in non-.md files
        String header = "# Generated by VibeTags | https://github.com/PIsberg/vibetags";
        String input = header + "\n<project_guardrails>\n</rule>\n</project_guardrails>\n<rule>rule1</rule>";
        String result = processor.stripLegacyVibeTagsBlock(input);
        assertFalse(result.contains("<project_guardrails>"),
            "Legacy VibeTags XML block should be stripped when bare header is present");
    }

    @Test
    void stripLegacyVibeTagsBlock_withHtmlCommentHeader_stripsBlock() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // The HTML-comment form as written into .md files:
        // "<!-- # Generated by VibeTags | <url> -->"
        String header = "<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->";
        String input = header + "\n<project_guardrails>\n</rule>\n</project_guardrails>\n<rule>rule1</rule>";
        String result = processor.stripLegacyVibeTagsBlock(input);
        assertFalse(result.contains("<project_guardrails>"),
            "Legacy VibeTags XML block should be stripped when HTML-comment header is present");
    }

    @Test
    void stripLegacyVibeTagsBlock_humanContentBefore_preserved() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        String humanContent = "## My Custom Section\nThis is important human content.";
        String vibetagsBlock = "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n# AUTO-GENERATED";
        String input = humanContent + "\n\n" + vibetagsBlock;

        // The human content has no XML closer, so the whole rest is treated as legacy
        // but human content before the header should be preserved since it has non-boilerplate content
        String result = processor.stripLegacyVibeTagsBlock(input);
        assertTrue(result.contains("My Custom Section"),
            "Human content before VibeTags header must survive");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static RoundEnvironment emptyRoundEnv() {
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        when(roundEnv.processingOver()).thenReturn(false);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(java.util.Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        return roundEnv;
    }

    /** Calls process() with processingOver=true to trigger file generation. */
    private static void triggerGeneration(AIGuardrailProcessor processor) {
        RoundEnvironment genEnv = mock(RoundEnvironment.class);
        when(genEnv.processingOver()).thenReturn(true);
        doReturn(java.util.Set.of()).when(genEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(java.util.Set.of()).when(genEnv).getElementsAnnotatedWith(AIContext.class);
        doReturn(java.util.Set.of()).when(genEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(java.util.Set.of()).when(genEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(java.util.Set.of()).when(genEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(java.util.Set.of()).when(genEnv).getElementsAnnotatedWith(AIPrivacy.class);
        processor.process(Set.of(), genEnv);
    }

    private static ProcessingEnvironment mockEnv(Messager messager) {
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        when(env.getOptions()).thenReturn(Map.of());
        return env;
    }

    /** Real (non-mock) Name implementation — avoids nested-stubbing issues. */
    private static Name nameOf(String value) {
        return new Name() {
            public boolean contentEquals(CharSequence cs) { return value.contentEquals(cs); }
            public int length() { return value.length(); }
            public char charAt(int index) { return value.charAt(index); }
            public CharSequence subSequence(int start, int end) { return value.subSequence(start, end); }
            public String toString() { return value; }
        };
    }

    private static Messager noopMessager() {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a, javax.lang.model.element.AnnotationValue v) {}
        };
    }

    private static Messager capturingMessager(Diagnostic.Kind capture, List<String> sink) {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == capture) sink.add(msg.toString());
            }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) { printMessage(kind, msg); }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a) { printMessage(kind, msg); }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a, javax.lang.model.element.AnnotationValue v) { printMessage(kind, msg); }
        };
    }

    // -----------------------------------------------------------------------
    // cleanupGranularDirectory — hash-marker paths (uncovered in coverage)
    // -----------------------------------------------------------------------

    @Test
    void cleanupGranularDirectory_hashMarkerWithHumanContent_survivesStripped(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("SomeRule.txt");
        // File with hash-comment markers and human content BEFORE them
        Files.writeString(file, "# Human header\n\n# VIBETAGS-START\ngenerated content\n# VIBETAGS-END\n");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.cleanupGranularDirectory(rulesDir, ".txt", Set.of());

        assertTrue(Files.exists(file), "File with human content before markers should survive");
        String content = Files.readString(file);
        assertFalse(content.contains("# VIBETAGS-START"), "Hash start marker should be stripped");
        assertFalse(content.contains("generated content"), "Generated content should be removed");
        assertTrue(content.contains("# Human header"), "Human content before markers must be preserved");
    }

    @Test
    void cleanupGranularDirectory_hashMarkerOnlyVibeTags_deletesFile(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("OnlyVibeTags.txt");
        // Only VibeTags content — should be deleted
        Files.writeString(file, "# VIBETAGS-START\ngenerated\n# VIBETAGS-END\n");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.cleanupGranularDirectory(rulesDir, ".txt", Set.of());

        assertFalse(Files.exists(file), "File with only VibeTags content should be deleted");
    }

    @Test
    void cleanupGranularDirectory_hashMarkerWithFrontMatterOnly_deletesFile(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("FrontMatterOnly.txt");
        // YAML front-matter + hash-comment VibeTags block; after stripping only front-matter remains
        Files.writeString(file,
            "---\ndescription: \"AI rules for Foo\"\nglobs: [\"**/Foo.java\"]\n---\n" +
            "# VIBETAGS-START\nold rule\n# VIBETAGS-END\n");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.cleanupGranularDirectory(rulesDir, ".txt", Set.of());

        assertFalse(Files.exists(file),
            "File with only YAML front-matter after stripping hash markers should be deleted");
    }

    @Test
    void cleanupGranularDirectory_hashMarkerNoEndMarker_fileNotModified(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("NoEnd.txt");
        String initial = "# VIBETAGS-START\nno end marker here\n";
        Files.writeString(file, initial);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.cleanupGranularDirectory(rulesDir, ".txt", Set.of());

        assertTrue(Files.exists(file), "File without end marker should not be deleted");
        assertEquals(initial, Files.readString(file), "File without end marker should be unmodified");
    }

    @Test
    void cleanupGranularDirectory_fileWithNoMarkers_notModified(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("ManualRule.txt");
        String content = "# Purely human-written rule\nNo VibeTags markers here.\n";
        Files.writeString(file, content);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.cleanupGranularDirectory(rulesDir, ".txt", Set.of());

        assertTrue(Files.exists(file), "File with no markers should survive");
        assertEquals(content, Files.readString(file), "File content should be unchanged");
    }

    // -----------------------------------------------------------------------
    // writeFileIfChanged — malformed markers and legacy-file paths
    // -----------------------------------------------------------------------

    @Test
    void writeFileIfChanged_malformedMarkersNoEnd_emitsWarningAndWrites(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("broken.md");
        // Start marker present but no end marker — malformed
        Files.writeString(mdFile, "<!-- VIBETAGS-START -->\norphaned content with no end marker");

        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        boolean changed = processor.writeFileIfChanged(mdFile.toString(), "new content", true);

        assertTrue(changed, "Should write when markers are malformed (start but no end)");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("malformed markers")),
            "Should emit a WARNING about malformed markers");
        assertTrue(Files.readString(mdFile).contains("new content"), "New content should be written");
    }

    @Test
    void writeFileIfChanged_legacyFileWithGeneratedHeader_upgradesToMarkers(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("legacy.md");
        // Legacy file: has the VibeTags generated-header line but no VIBETAGS-START/END markers
        String legacyContent =
            "<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->\n" +
            "<project_guardrails>\n  <locked_files/>\n</project_guardrails>\n";
        Files.writeString(mdFile, legacyContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(mdFile.toString(), "new content", true);

        assertTrue(changed, "Should upgrade legacy file to marker format");
        String written = Files.readString(mdFile);
        assertTrue(written.contains("<!-- VIBETAGS-START -->"), "Should add start marker");
        assertTrue(written.contains("<!-- VIBETAGS-END -->"), "Should add end marker");
        assertTrue(written.contains("new content"), "Should contain new content");
    }

    @Test
    void writeFileIfChanged_legacyFileWithHeader_hasNewRulesFalse_skips(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("legacy.md");
        // Legacy file — no markers but has VibeTags header
        Files.writeString(mdFile,
            "<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->\n" +
            "<project_guardrails/>\n");

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        boolean changed = processor.writeFileIfChanged(mdFile.toString(), "different content", false);

        assertFalse(changed, "Should skip when hasNewRules=false and file already has content");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Skipping update")),
            "Should emit a skip NOTE for multi-module preservation");
    }

    @Test
    void writeFileIfChanged_yamlFrontMatterInContent_markersPlacedAfterFrontMatter(@TempDir Path tempDir) throws IOException {
        Path mdcFile = tempDir.resolve("rule.mdc");
        // Content starts with YAML front-matter — markers must be placed AFTER the closing "---"
        String contentWithFrontMatter =
            "---\ndescription: \"AI rules for Foo\"\nglobs: [\"**/Foo.java\"]\nalwaysApply: false\n---\n" +
            "# Rule Content\nDo not modify this class.";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(mdcFile.toString(), contentWithFrontMatter, true);

        assertTrue(changed, "Should create file with YAML front-matter content");
        String written = Files.readString(mdcFile);
        int frontMatterEnd = written.lastIndexOf("---", written.indexOf("<!-- VIBETAGS-START -->"));
        int markerStart = written.indexOf("<!-- VIBETAGS-START -->");
        assertTrue(frontMatterEnd > 0 && markerStart > frontMatterEnd,
            "Markers must appear after the YAML front-matter block");
        assertTrue(written.contains("Do not modify this class."), "Rule content should be preserved");
    }

    @Test
    void writeFileIfChanged_sectionMissingHasNewRulesFalse_skips(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("notes.md");
        // Existing file has human content but NO VibeTags markers and NO generated header
        Files.writeString(mdFile, "# My notes\nSome human content here.\n");

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        boolean changed = processor.writeFileIfChanged(mdFile.toString(), "new guardrail content", false);

        assertFalse(changed, "Should skip when hasNewRules=false and existing non-marker file has content");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Skipping update")),
            "Should emit skip NOTE for multi-module preservation");
    }

    @Test
    void writeFileIfChanged_nonMarkdownHasNewRulesFalse_skips(@TempDir Path tempDir) throws IOException {
        Path jsonFile = tempDir.resolve(".qwen/settings.json");
        Files.createDirectories(jsonFile.getParent());
        Files.writeString(jsonFile, "{\"existing\": true}");

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(mockEnv(messager));

        boolean changed = processor.writeFileIfChanged(jsonFile.toString(), "{\"new\": true}", false);

        assertFalse(changed, "Should skip JSON overwrite when hasNewRules=false");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Skipping update")),
            "Should emit skip NOTE for JSON file multi-module preservation");
    }

    // -----------------------------------------------------------------------
    // stripLegacyVibeTagsBlock — both prefix and humanContent non-empty
    // -----------------------------------------------------------------------

    @Test
    void stripLegacyVibeTagsBlock_humanPrefixAndHumanSuffix_returnsBoth() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // Human content before the header, then VibeTags XML block, then more human content
        String humanPrefix = "Human notes written by team."; // non-boilerplate (no #/-/*/<)
        String vibetagsBlock =
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n" +
            "<project_guardrails>\n" +
            "  <locked_files/>\n" +
            "</project_guardrails>\n" +
            "<rule>keep locked</rule>\n";
        String humanSuffix = "# Extra section after guardrails";
        String input = humanPrefix + "\n\n" + vibetagsBlock + humanSuffix;

        String result = processor.stripLegacyVibeTagsBlock(input);

        assertTrue(result.contains("Human notes"), "Human prefix must be preserved");
        assertTrue(result.contains("# Extra section"), "Human suffix must be preserved");
        assertFalse(result.contains("<project_guardrails>"), "VibeTags XML block must be removed");
    }

    // -----------------------------------------------------------------------
    // warn() — log != null branch (previously uncovered)
    // -----------------------------------------------------------------------

    @Test
    void warn_withNonNullLog_executesLogWarn(@TempDir Path tempDir) throws IOException {
        // Signal file: claude is active, but .claudeignore is absent → warning fires
        Files.createFile(tempDir.resolve("CLAUDE.md"));

        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        // log.level=OFF → log = NOPLogger (non-null, no-op) — covers the `if (log != null)` branch
        Map<String, String> options = Map.of(
            "vibetags.root", tempDir.toString(),
            "vibetags.log.level", "OFF"
        );
        when(env.getOptions()).thenReturn(options);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(env);

        // Round 1: accumulate one @AIIgnore element so hasIgnore=true in generateFiles()
        Element ignoreElement = mock(Element.class);
        when(ignoreElement.toString()).thenReturn("com.example.IgnoredClass");
        when(ignoreElement.getSimpleName()).thenReturn(nameOf("IgnoredClass"));
        when(ignoreElement.getKind()).thenReturn(javax.lang.model.element.ElementKind.CLASS);
        AIIgnore ignoreAnnotation = mock(AIIgnore.class);
        when(ignoreAnnotation.reason()).thenReturn("test reason");
        when(ignoreElement.getAnnotation(AIIgnore.class)).thenReturn(ignoreAnnotation);

        RoundEnvironment round1 = mock(RoundEnvironment.class);
        when(round1.processingOver()).thenReturn(false);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of(ignoreElement)).when(round1).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIPrivacy.class);
        processor.process(Set.of(), round1);

        // Round 2: trigger generateFiles() → checkOrphanedAnnotations() → warn() with log != null
        try {
            triggerGeneration(processor);
        } finally {
            VibeTagsLogger.shutdown();
        }

        assertTrue(warnings.stream().anyMatch(w -> w.contains(".claudeignore")),
            "Should emit a WARNING about missing .claudeignore when @AIIgnore is used and claude is active");
    }

    // -----------------------------------------------------------------------
    // Per-platform gating in generateFiles — covers the `if (xActive)` false
    // branches added by the activeServices optimisation. Existing E2E tests
    // pre-create every opt-in file, so they only exercise the true branches.
    // -----------------------------------------------------------------------

    /**
     * Annotations are present but NO opt-in files exist, so every
     * {@code if (xActive)} guard in generateFiles evaluates to {@code false}.
     * This single test covers the false-branch of every per-element platform
     * append in one pass.
     */
    @Test
    void generateFiles_annotationsPresentButNoOptInFiles_skipsAllPlatformAppends(@TempDir Path tempDir) throws IOException {
        // No opt-in files created — every activeServices.contains(...) is false.

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        Map<String, String> options = Map.of(
            "vibetags.root", tempDir.toString(),
            "vibetags.log.level", "OFF"
        );
        when(env.getOptions()).thenReturn(options);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(env);

        // Build one element annotated with each annotation type so every
        // per-element loop body executes at least once.
        Element locked  = mockClassElement("com.example.Locked",  "Locked");
        AILocked lockedAnno = mock(AILocked.class);
        when(lockedAnno.reason()).thenReturn("locked-reason");
        when(locked.getAnnotation(AILocked.class)).thenReturn(lockedAnno);

        Element ctx = mockClassElement("com.example.Ctx", "Ctx");
        AIContext ctxAnno = mock(AIContext.class);
        when(ctxAnno.focus()).thenReturn("focus");
        when(ctxAnno.avoids()).thenReturn("avoid");
        when(ctx.getAnnotation(AIContext.class)).thenReturn(ctxAnno);

        Element ign = mockClassElement("com.example.Ign", "Ign");

        Element aud = mockClassElement("com.example.Aud", "Aud");
        AIAudit audAnno = mock(AIAudit.class);
        when(audAnno.checkFor()).thenReturn(new String[]{"SQL Injection"});
        when(aud.getAnnotation(AIAudit.class)).thenReturn(audAnno);

        Element drf = mockClassElement("com.example.Drf", "Drf");
        AIDraft drfAnno = mock(AIDraft.class);
        when(drfAnno.instructions()).thenReturn("implement me");
        when(drf.getAnnotation(AIDraft.class)).thenReturn(drfAnno);

        Element prv = mockClassElement("com.example.Prv", "Prv");
        AIPrivacy prvAnno = mock(AIPrivacy.class);
        when(prvAnno.reason()).thenReturn("PII");
        when(prv.getAnnotation(AIPrivacy.class)).thenReturn(prvAnno);

        // Round 1: accumulate all element types
        RoundEnvironment round1 = mock(RoundEnvironment.class);
        when(round1.processingOver()).thenReturn(false);
        doReturn(Set.of(locked)).when(round1).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of(ctx)).when(round1).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of(ign)).when(round1).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of(aud)).when(round1).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of(drf)).when(round1).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of(prv)).when(round1).getElementsAnnotatedWith(AIPrivacy.class);
        processor.process(Set.of(), round1);

        // Round 2: trigger generateFiles with no platforms active
        try {
            triggerGeneration(processor);
        } finally {
            VibeTagsLogger.shutdown();
        }

        // No platform files should have been written
        assertFalse(Files.exists(tempDir.resolve(".cursorrules")));
        assertFalse(Files.exists(tempDir.resolve("CLAUDE.md")));
        assertFalse(Files.exists(tempDir.resolve("AGENTS.md")));
        assertFalse(Files.exists(tempDir.resolve("QWEN.md")));
        assertFalse(Files.exists(tempDir.resolve("gemini_instructions.md")));
        assertFalse(Files.exists(tempDir.resolve("llms.txt")));
        assertFalse(Files.exists(tempDir.resolve("CONVENTIONS.md")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("nothing will be generated")),
            "Should emit the no-active-services NOTE");
    }

    /**
     * Only ONE platform's opt-in file exists. Tests that the active
     * platform's true-branch fires while every other platform's false-branch
     * fires in the same generation pass — locks in the per-platform gating
     * even when most platforms are inactive.
     */
    @Test
    void generateFiles_onlyCursorOptedIn_writesCursorOnlyAndSkipsOthers(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve(".cursorrules"));

        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
        when(env.getOptions()).thenReturn(Map.of(
            "vibetags.root", tempDir.toString(),
            "vibetags.log.level", "OFF"
        ));

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.init(env);

        Element locked = mockClassElement("com.example.Locked", "Locked");
        AILocked lockedAnno = mock(AILocked.class);
        when(lockedAnno.reason()).thenReturn("locked-reason");
        when(locked.getAnnotation(AILocked.class)).thenReturn(lockedAnno);

        RoundEnvironment round1 = mock(RoundEnvironment.class);
        when(round1.processingOver()).thenReturn(false);
        doReturn(Set.of(locked)).when(round1).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(round1).getElementsAnnotatedWith(AIPrivacy.class);
        processor.process(Set.of(), round1);

        try {
            triggerGeneration(processor);
        } finally {
            VibeTagsLogger.shutdown();
        }

        String cursorContent = Files.readString(tempDir.resolve(".cursorrules"));
        assertTrue(cursorContent.contains("com.example.Locked"),
            "Cursor (active) should contain the locked element");
        // Every other platform's opt-in file is absent → not created
        assertFalse(Files.exists(tempDir.resolve("CLAUDE.md")));
        assertFalse(Files.exists(tempDir.resolve("AGENTS.md")));
        assertFalse(Files.exists(tempDir.resolve("QWEN.md")));
        assertFalse(Files.exists(tempDir.resolve("gemini_instructions.md")));
        assertFalse(Files.exists(tempDir.resolve("llms.txt")));
        assertFalse(Files.exists(tempDir.resolve("CONVENTIONS.md")));
    }

    // -----------------------------------------------------------------------
    // writeFileIfChanged — non-marker size-mismatch fast path (opt 2)
    // -----------------------------------------------------------------------

    /**
     * Non-marker file (JSON/TOML) whose existing size differs from the new
     * content by more than the 64-byte trim-tolerance. The size-mismatch
     * fast-path skips the full file read and writes directly.
     */
    @Test
    void writeFileIfChanged_nonMarkerSizeMismatch_writesWithoutFullRead(@TempDir Path tempDir) throws IOException {
        Path settings = tempDir.resolve("settings.json");
        // 1 KB of existing content; new content is short — sizes differ by far more than 64 bytes.
        Files.writeString(settings, "{\"x\":\"" + "x".repeat(1024) + "\"}");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(settings.toString(), "{\"new\": true}", true);

        assertTrue(changed, "Should overwrite when sizes mismatch by more than the trim tolerance");
        assertEquals("{\"new\": true}", Files.readString(settings).strip());
    }

    /**
     * Non-marker file whose existing content equals the new content modulo
     * trailing whitespace (within the 64-byte tolerance). The fast path does
     * NOT trigger, the read happens, and strip-equality returns true → no-op.
     */
    @Test
    void writeFileIfChanged_nonMarkerSizeWithinTolerance_takesSlowPath(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("config.toml");
        // Existing content differs from new content by trailing whitespace only.
        Files.writeString(config, "model = \"o3-mini\"\n\n\n");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(config.toString(), "model = \"o3-mini\"", true);

        assertFalse(changed, "Trim-only diff (within 64-byte tolerance) should be a no-op");
    }

    /** Mocks a TYPE-kind Element with a given FQN and simple name. */
    private static Element mockClassElement(String fqn, String simpleName) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getSimpleName()).thenReturn(nameOf(simpleName));
        when(e.getKind()).thenReturn(javax.lang.model.element.ElementKind.CLASS);
        return e;
    }
}
