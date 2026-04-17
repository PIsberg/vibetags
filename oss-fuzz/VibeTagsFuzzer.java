import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import se.deversity.vibetags.processor.AIGuardrailProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class VibeTagsFuzzer {
    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        try {
            // We fuzz the most complex string parsing boundary: the file content merger
            // which deals with YAML front-matter, markers, and index finding.
            
            AIGuardrailProcessor processor = new AIGuardrailProcessor();
            
            // Fuzzer controls the existing file context
            String fakeExistingContent = data.consumeString(500);
            
            // Fuzzer controls what the newly generated block payload will be
            String newContentPayload = data.consumeRemainingAsString();
            
            // Randomly choose file extension to test different marker logic
            String ext = "";
            int extChoice = data.consumeInt(0, 3);
            if (extChoice == 0) ext = ".md";
            else if (extChoice == 1) ext = ".mdc";
            else if (extChoice == 2) ext = ".json";
            else ext = ""; // rules file (hash markers)
            
            Path tempFile = Files.createTempFile("fuzz_", ext);
            
            try {
                // Write existing garbage (or structured) content controlled by fuzzer
                Files.writeString(tempFile, fakeExistingContent);
                
                // Attack the writeFileIfChanged logic which parses markers and merges blocks
                processor.writeFileIfChanged(tempFile.toString(), newContentPayload);
                
            } finally {
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(Path.of(tempFile.toString() + ".bak"));
            }
        } catch (IOException e) {
            // IOExceptions are expected on invalid temp files or fuzzed filesystem paths
        } catch (IllegalArgumentException e) {
            // Expected on certain invalid Java arg invariants
        }
    }
}
