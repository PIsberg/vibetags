package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.internal.validation.RuleFileLengthRule;
import se.deversity.vibetags.processor.internal.validation.ValidationContext;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs {@link RuleFileLengthRule} over the rule files VibeTags writes into the Devin Desktop,
 * Windsurf and Antigravity rule directories (issues #695, #701).
 *
 * <p>The processor calls this after {@code generateFiles()} or {@code checkFiles()} returns, so it
 * reads each file as the build leaves it: the content this build just wrote, the unchanged file a
 * fingerprint short-circuit left in place, or in check mode the committed file. Running before
 * generation, where the YAML key check runs, would measure the previous build and miss the build
 * that made a file too long. Both methods release the diagnostic log on their way out, so the
 * caller hands in a logger it has reopened.
 *
 * <p>A file is measured only when it carries the VibeTags marker pair: a rule a user wrote alone is
 * not something this build produced or can shorten.
 */
public final class RuleFileLengthWarner {

    private RuleFileLengthWarner() {}

    /**
     * Warns once for each opted-in rule file over the documented cap.
     *
     * @param messager     where the warning goes
     * @param log          the diagnostic log, or {@code null}
     * @param displayRoot  what file paths are shown relative to
     * @param serviceFiles service key to output path, from {@link ServiceRegistry#buildServiceFileMap}
     */
    public static void warn(Messager messager, @Nullable Logger log, Path displayRoot,
                            Map<String, Path> serviceFiles) {
        for (String serviceKey : RuleFileLengthRule.CAPPED_DIRECTORIES) {
            Path dir = serviceFiles.get(serviceKey);
            if (dir == null || !Files.isDirectory(dir)) {
                continue; // not opted in
            }
            List<Path> files;
            try (Stream<Path> entries = Files.list(dir)) {
                files = entries.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p)).sorted().toList();
            } catch (IOException | RuntimeException e) {
                if (log != null && log.isDebugEnabled()) {
                    log.debug("validation.skip check=rule-file-length dir={} reason=unlistable detail={}",
                        HandAuthoredYamlKeyWarner.displayPath(displayRoot, dir), e.getMessage());
                }
                continue;
            }
            for (Path file : files) {
                measure(messager, log, displayRoot, serviceKey, file);
            }
        }
    }

    private static void measure(Messager messager, @Nullable Logger log, Path displayRoot, String serviceKey,
                                Path file) {
        String shown = HandAuthoredYamlKeyWarner.displayPath(displayRoot, file);
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            if (log != null && log.isDebugEnabled()) {
                log.debug("validation.skip check=rule-file-length file={} reason=unreadable detail={}",
                    shown, e.getMessage());
            }
            return;
        }
        if (!content.contains(GuardrailFileWriter.MARKER_START_MD)) {
            if (log != null && log.isDebugEnabled()) {
                log.debug("validation.skip check=rule-file-length file={} reason=no-markers", shown);
            }
            return;
        }
        if (!RuleFileLengthRule.exceedsLimit(content)) {
            return;
        }
        int length = RuleFileLengthRule.length(content);
        Path fileName = file.getFileName();
        boolean safetyFile = fileName != null && ServiceRegistry.SAFETY_TIER_FILE.equals(fileName.toString());
        messager.printMessage(Diagnostic.Kind.WARNING,
            ValidationContext.PREFIX + RuleFileLengthRule.message(serviceKey, shown, length, safetyFile));
        if (log != null) {
            log.warn("validation.rule-file-over-limit file={} chars={} limit={}",
                shown, length, RuleFileLengthRule.WORKSPACE_RULE_FILE_LIMIT);
        }
    }
}
