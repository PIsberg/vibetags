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

        RoundEnvironment roundEnv = emptyRoundEnv();

        // First call should process and emit at least one NOTE
        processor.process(Set.of(), roundEnv);
        int notesAfterFirst = notes.size();
        assertTrue(notesAfterFirst > 0, "First call should produce at least one NOTE");

        // Second call must be a no-op — no additional messages
        processor.process(Set.of(), roundEnv);
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

        processor.process(Set.of(), emptyRoundEnv());

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
            "llms", "llms_full"
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
        boolean changed = processor.writeFileIfChanged(newFile.toString(), "hello world");

        assertTrue(changed, "Should return true when creating a new file");
        assertTrue(Files.exists(newFile), "File should now exist");
        assertTrue(Files.readString(newFile).contains("hello world"));
    }

    @Test
    void writeFileIfChanged_nestedDirDoesNotExist_createsParentAndFile(@TempDir Path tempDir) throws IOException {
        Path nested = tempDir.resolve("sub/dir/output.md");
        assertFalse(Files.exists(nested.getParent()), "Precondition: parent dir must not exist");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(nested.toString(), "nested content");

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

        boolean result = processor.writeFileIfChanged(impossiblePath.toString(), "content");

        assertFalse(result, "Should return false when write fails");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Failed to write AI rules file")),
            "Should emit a WARNING when write fails");
    }

    @Test
    void writeFileIfChanged_unicodeContent_preservedCorrectly(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("unicode.md");
        String content = "# 🛡️ Security\nFocus: performance\nAvoid: java.util.regex\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(file.toString(), content);

        String written = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(written.contains("🛡️"), "Unicode content must be preserved");
        assertTrue(written.contains("java.util.regex"), "ASCII content must be preserved");
    }

    @Test
    void writeFileIfChanged_multipleRoundTrips_onlyWritesWhenChanged(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("roundtrip.md");
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        // First write
        assertTrue(processor.writeFileIfChanged(file.toString(), "v1"));
        // Same content — no rewrite
        assertFalse(processor.writeFileIfChanged(file.toString(), "v1"));
        // Different content — rewrite
        assertTrue(processor.writeFileIfChanged(file.toString(), "v2"));
        // Same again — no rewrite
        assertFalse(processor.writeFileIfChanged(file.toString(), "v2"));
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
            String content = Files.readString(tempDir.resolve("llms-full.txt"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(content.contains("## Locked Files (Do Not Edit)"), "llms-full.txt should have expanded Locked Files header");
            assertTrue(content.contains("## Contextual Rules"),            "llms-full.txt should have Contextual Rules section");
            assertTrue(content.contains("> Complete AI guardrail"),        "llms-full.txt should have full summary blockquote");
        } finally {
            VibeTagsLogger.shutdown(); // release file handle so @TempDir can be deleted
        }
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

    private static ProcessingEnvironment mockEnv(Messager messager) {
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getMessager()).thenReturn(messager);
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
}
