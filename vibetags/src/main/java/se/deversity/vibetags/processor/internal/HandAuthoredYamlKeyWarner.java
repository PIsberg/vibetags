package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.validation.DuplicateYamlKeyRule;
import se.deversity.vibetags.processor.internal.validation.ValidationContext;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Runs {@link DuplicateYamlKeyRule} over every opted-in YAML output (issue #635).
 *
 * <p>The processor calls this beside enforcement, outside {@code generateFiles()}, for the reason
 * enforcement is there too: that method's fingerprint short-circuit skips a build whose inputs are
 * unchanged, and a duplicated key is a defect that persists across exactly those builds. A warning
 * that fired once and then went quiet would be one nobody saw.
 *
 * <p>The YAML platforms are the ones whose renderer declares a
 * {@link se.deversity.vibetags.processor.internal.content.YamlMergeShape}, which invariant 10 already
 * requires of every renderer that emits YAML, so a new YAML platform is covered without being named.
 */
public final class HandAuthoredYamlKeyWarner {

    private HandAuthoredYamlKeyWarner() {}

    /**
     * Warns once per duplicated key in each opted-in YAML output, on every build.
     *
     * @param messager     where the warning goes
     * @param log          the diagnostic log, or {@code null}
     * @param displayRoot  what file paths are shown relative to, so a reactor's root file and a
     *                     module's own file of the same name can be told apart in the build log
     * @param serviceFiles service key to output path, from {@link ServiceRegistry#buildServiceFileMap}
     */
    public static void warn(Messager messager, @Nullable Logger log, Path displayRoot,
                            Map<String, Path> serviceFiles) {
        for (Map.Entry<String, Path> service : serviceFiles.entrySet()) {
            String serviceKey = service.getKey();
            Path file = service.getValue();
            if (PlatformRendererRegistry.mergeShapeFor(serviceKey) == null || !Files.isRegularFile(file)) {
                continue; // not a YAML platform, or not opted in
            }
            Path fileNamePath = file.getFileName();
            String fileName = fileNamePath != null ? fileNamePath.toString() : file.toString();
            String[] markers = GuardrailFileWriter.getMarkersFor(fileName);
            if (markers == null) {
                continue;
            }
            String shown = displayPath(displayRoot, file);
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                // The writer reports an unreadable output file on its own path; this check is advisory.
                if (log != null && log.isDebugEnabled()) {
                    log.debug("validation.skip check=duplicate-yaml-key file={} reason=unreadable detail={}",
                        shown, e.getMessage());
                }
                continue;
            }
            Set<String> owned = DuplicateYamlKeyRule.ownedKeys(serviceKey);
            for (DuplicateYamlKeyRule.Finding finding : DuplicateYamlKeyRule.find(content, owned, markers[0], markers[1])) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    ValidationContext.PREFIX + DuplicateYamlKeyRule.message(shown, finding));
                if (log != null) {
                    log.warn("validation.duplicate-yaml-key file={} key={} handLine={} generatedLine={} readLine={}",
                        shown, finding.key(), finding.handLine(), finding.generatedLine(), finding.readLine());
                }
            }
        }
    }

    /** {@code file} relative to {@code root} with forward slashes, or as given when it lies elsewhere. */
    private static String displayPath(Path root, Path file) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        Path shown = absoluteFile.startsWith(absoluteRoot) ? absoluteRoot.relativize(absoluteFile) : absoluteFile;
        return shown.toString().replace('\\', '/');
    }
}
