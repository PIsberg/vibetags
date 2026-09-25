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
        // A UTF-8 file of at most the cap in bytes cannot hold more than the cap in chars: every
        // code point costs at least as many bytes as the UTF-16 units it occupies (1 to 1, 2 to 1,
        // 3 to 1, and 4 to 2 for a surrogate pair). Such a file can never warn, so reading and
        // decoding it is pure cost, and this runs over every rule file after every build, including
        // the ones a fingerprint short-circuit left untouched.
        //
        // Gated on DEBUG rather than applied always, deliberately. The two skip events below are
        // contracts (invariant 15, docs/LOGGING.md) and fire today for small files; a reader who
        // asked for DEBUG still gets them, and pays the read to do so. With DEBUG off, which is
        // every ordinary build, nothing observable is lost.
        if ((log == null || !log.isDebugEnabled()) && withinCapByBytes(file, RuleFileLengthRule.limit(serviceKey))) {
            return;
        }
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
        if (!RuleFileLengthRule.exceedsLimit(serviceKey, content)) {
            return;
        }
        int length = RuleFileLengthRule.length(serviceKey, content);
        Path fileName = file.getFileName();
        boolean safetyFile = fileName != null && ServiceRegistry.SAFETY_TIER_FILE.equals(fileName.toString());
        messager.printMessage(Diagnostic.Kind.WARNING,
            ValidationContext.PREFIX + RuleFileLengthRule.message(serviceKey, shown, length, safetyFile));
        if (log != null && RuleFileLengthRule.countsBytes(serviceKey)) {
            log.warn("validation.rule-file-over-limit file={} bytes={} limit={}",
                shown, length, RuleFileLengthRule.limit(serviceKey));
        } else if (log != null) {
            log.warn("validation.rule-file-over-limit file={} chars={} limit={}",
                shown, length, RuleFileLengthRule.limit(serviceKey));
        }
    }

    /**
     * True when the file is small enough in bytes that it cannot exceed {@code limit}: exactly so
     * for a cap in bytes, and for a cap in characters because a UTF-8 file cannot hold more UTF-16
     * units than it has bytes.
     *
     * <p>A file whose size cannot be read answers {@code false}, so the caller falls through to the
     * ordinary read and reaches the same verdict it always did, including its skip event.
     */
    private static boolean withinCapByBytes(Path file, int limit) {
        try {
            return Files.size(file) <= limit;
        } catch (IOException | RuntimeException unsizable) {
            return false;
        }
    }
}
