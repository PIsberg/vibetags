import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import se.deversity.vibetags.processor.AIGuardrailProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jazzer entry point. Targets the file-content merger inside
 * {@link AIGuardrailProcessor#writeFileIfChanged(String, String, boolean)} —
 * the most complex string-parsing boundary in the processor (YAML front-matter
 * detection, marker block parsing, content merge, legacy-block stripping).
 *
 * <p>The fuzzer is in the default package on purpose: simpler classpath for
 * Jazzer's {@code --target_class}.
 */
public class VibeTagsFuzzer {
    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        try {
            AIGuardrailProcessor processor = new AIGuardrailProcessor();

            // Fuzzer controls the existing file context (up to 500 bytes).
            String fakeExistingContent = data.consumeString(500);

            // Third writeFileIfChanged arg controls the multi-module preservation guard.
            // Both true and false hit different branches, so let the fuzzer pick.
            boolean hasNewRules = data.consumeBoolean();

            // Fuzzer controls what the newly generated block payload will be.
            String newContentPayload = data.consumeRemainingAsString();

            // Randomly choose file extension to fan across the three marker regimes:
            //   .md / .mdc       → HTML markers (`<!-- VIBETAGS-START -->`)
            //   .json            → no markers (full overwrite, plus the 0.7.1
            //                      streaming byte-compare fast path on size match)
            //   "" (e.g. rules)  → hash markers (`# VIBETAGS-START`)
            String ext;
            int extChoice = data.consumeInt(0, 3);
            if (extChoice == 0) ext = ".md";
            else if (extChoice == 1) ext = ".mdc";
            else if (extChoice == 2) ext = ".json";
            else ext = ""; // rules file (hash markers)

            Path tempFile = Files.createTempFile("fuzz_", ext);

            try {
                Files.writeString(tempFile, fakeExistingContent);
                processor.writeFileIfChanged(tempFile.toString(), newContentPayload, hasNewRules);
            } finally {
                Files.deleteIfExists(tempFile);
                // Atomic-write sidecar used by GuardrailFileWriter#writeContentWithBackup
                // (renamed from `.bak` to `.vibetags-tmp` in 0.5.6).
                Files.deleteIfExists(Path.of(tempFile.toString() + ".vibetags-tmp"));
            }
        } catch (IOException e) {
            // Expected on invalid temp files or fuzzed filesystem paths.
        } catch (IllegalArgumentException e) {
            // Expected on certain invalid Java arg invariants.
        }
    }
}
