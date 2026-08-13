package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.TransitiveRule;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishing half of transitive guardrails: turns a library's package-level annotations into
 * manifests inside its own JAR.
 *
 * <h2>Opt-in</h2>
 *
 * <p>A {@code .vibetags-manifest} file at the library root activates emission, following the same
 * file-presence rule as every other VibeTags output. Without it nothing is written, so upgrading
 * the processor never starts publishing a library's internals to its consumers. The file's first
 * non-blank, non-comment line, if any, is the artifact coordinate stamped into every manifest as
 * {@code origin}.
 *
 * <p>Output goes to {@link StandardLocation#CLASS_OUTPUT}, which both Maven and Gradle package into
 * the JAR with no build configuration, and which is cleaned with the rest of the build output.
 * Writing through the {@link Filer} rather than to a path is what makes that true; it is also the
 * only way the file lands somewhere the compiler will account for.
 *
 * <h2>What propagates</h2>
 *
 * <p>Only annotations on {@code package-info.java}. Type-level and method-level guardrails stay
 * local on purpose: propagating them would scale a manifest with the library's whole API surface,
 * and a consumer cannot act on a rule about a class it never sees. A package is the smallest unit
 * a library author can point at that still means something on the other side of a JAR.
 */
public final class TransitiveManifestWriter {

    /** Marker file at the library root that opts a build into publishing manifests. */
    public static final String MARKER_FILE = ".vibetags-manifest";

    private TransitiveManifestWriter() {}

    /** True when {@code root} carries the opt-in marker. */
    public static boolean optedIn(Path root) {
        return Files.isRegularFile(root.resolve(MARKER_FILE));
    }

    /**
     * The artifact coordinate to stamp into manifests: the marker file's first non-blank,
     * non-comment line, or {@code ""} when it has none.
     *
     * <p>Empty is a supported outcome, not a failure. A library that has not told VibeTags its
     * coordinate still gets working manifests; its rules simply render without attribution, and the
     * reader is told so rather than shown a guess.
     */
    public static String originFrom(Path root) {
        Path marker = root.resolve(MARKER_FILE);
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return trimmed;
                }
            }
        } catch (IOException e) {
            // Unreadable marker: emission still proceeds unattributed. Failing here would turn a
            // permissions quirk into a broken build for a feature that is advisory by design.
            return "";
        }
        return "";
    }

    /**
     * Groups a model's package-level annotations into one rule list per package.
     *
     * <p>Compiler-free: it reads only the snapshot, which is what lets it run after the last round
     * has closed and lets its tests exercise it without a javac in the loop.
     */
    public static Map<String, List<TransitiveRule>> rulesByPackage(GuardrailModel model, String origin) {
        Map<String, List<TransitiveRule>> byPackage = new LinkedHashMap<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            String label = GuardrailAnnotations.label(type);
            for (TaggedElement element : model.of(type)) {
                if (element.kind() != ElementTag.PACKAGE) {
                    continue;
                }
                Annotation instance = element.annotation(type);
                if (instance == null) {
                    continue;
                }
                String packageName = element.path();
                if (packageName.isEmpty()) {
                    // The unnamed package. It cannot be imported by name, so no consumer could ever
                    // look the manifest up; publishing one would be dead weight in every JAR.
                    continue;
                }
                byPackage.computeIfAbsent(packageName, k -> new ArrayList<>())
                    .add(new TransitiveRule(origin, packageName, label,
                        TransitiveManifest.tierOf(label), TransitiveManifest.membersOf(instance)));
            }
        }
        return byPackage;
    }

    /**
     * Writes one manifest per annotated package.
     *
     * @return the package names written, in sorted order; empty when the model has no package-level
     *         annotations
     * @throws IOException if the Filer refuses a write
     */
    public static List<String> emit(Filer filer, GuardrailModel model, String origin,
                                    String processorVersion, @Nullable Logger log) throws IOException {
        Map<String, List<TransitiveRule>> byPackage = rulesByPackage(model, origin);
        List<String> written = new ArrayList<>(byPackage.keySet());
        java.util.Collections.sort(written);
        for (String packageName : written) {
            List<TransitiveRule> rules = byPackage.getOrDefault(packageName, List.of());
            String json = TransitiveManifest.toJson(packageName, origin, rules, processorVersion);
            FileObject out = filer.createResource(StandardLocation.CLASS_OUTPUT,
                TransitiveManifest.RESOURCE_PACKAGE, TransitiveManifest.resourceNameFor(packageName));
            try (Writer w = out.openWriter()) {
                w.write(json);
            }
            if (log != null && log.isDebugEnabled()) {
                log.debug("manifest.write package={} rules={} origin={} uri={}",
                    packageName, rules.size(),
                    origin.isEmpty() ? "<unset>" : origin, out.toUri());
            }
        }
        return written;
    }
}
