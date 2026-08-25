package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The on-disk form of a library's package-level guardrails: one JSON document per annotated
 * package, packaged inside the library's JAR and read back by a consuming compilation.
 *
 * <h2>Where it lives, and why not {@code META-INF/}</h2>
 *
 * <p>{@link #RESOURCE_PACKAGE} is a valid Java package name, and it has to be. javac's
 * {@code CLASS_PATH} location skips archive directories that are not valid package identifiers, so
 * a resource under {@code META-INF/} is invisible to {@code Filer.getResource} and lists as zero
 * entries even through javac's own file manager. {@code TransitiveManifestPathTest} pins the
 * working path; {@code TransitiveDiscoveryE2ETest} proves the {@code META-INF/} path stays broken,
 * so nobody moves it back on the strength of it being the conventional location.
 *
 * <h2>Determinism</h2>
 *
 * <p>{@link #toJson} emits keys in a fixed order and rules in sorted order. The manifest is a build
 * output that ends up in a JAR, and a JAR that differs byte-for-byte between two builds of the same
 * source breaks reproducible builds for everyone downstream. It is also read back into the
 * consumer's {@code BuildFingerprint}, where instability would churn generated files on every
 * compile.
 */
public final class TransitiveManifest {

    /**
     * The package (and therefore the directory) manifests live in, inside the JAR. Must stay a
     * valid Java package name, one segment per level: {@code vibetags/manifests/}.
     */
    @AILocked(reason = "Must stay a valid Java package name. javac's CLASS_PATH location skips archive "
        + "directories that are not package identifiers, so moving these manifests under META-INF/ leaves "
        + "Filer.getResource listing zero entries and transitive discovery fails silently while the "
        + "conventional location looks correct. TransitiveManifestPathTest pins the working path.")
    public static final String RESOURCE_PACKAGE = "vibetags.manifests";

    /** Filename extension of a manifest, appended to the governed package name. */
    public static final String RESOURCE_SUFFIX = ".json";

    /**
     * Format version written into every manifest.
     *
     * <p>Version history:
     * <ul>
     *   <li>1 — package name, origin coordinate, and a sorted rule list carrying the annotation
     *       label, derived tier, and the annotation's attributes.</li>
     * </ul>
     *
     * <p>A reader that meets a higher version skips the file with a warning rather than guessing,
     * because a rule it half-understands is a rule an agent may act on wrongly. A reader that meets
     * a lower version still reads it: every field of v1 is required, so there is nothing to
     * degrade.
     */
    public static final int FORMAT_VERSION = 1;

    /**
     * The six always-on safety buckets. A rule from one of these is {@link TransitiveRule.Tier#SAFETY};
     * everything else is advisory.
     *
     * <p>This is deliberately the same list the scoped-rules index keeps inline when an aggregate
     * collapses. Introducing a separate severity vocabulary for transitive rules would create a
     * second copy of it that no test keeps in agreement with the first.
     */
    static final Set<Class<? extends Annotation>> SAFETY_ANNOTATIONS = Set.of(
        AILocked.class, AICore.class, AIPrivacy.class, AIIgnore.class, AIAudit.class, AISecure.class);

    /** Labels of the safety annotations, as they appear in a manifest's {@code annotation} field. */
    static final Set<String> SAFETY_LABELS = SAFETY_ANNOTATIONS.stream()
        .map(GuardrailAnnotations::label)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private TransitiveManifest() {}

    /** The resource name a manifest for {@code packageName} occupies, relative to {@link #RESOURCE_PACKAGE}. */
    public static String resourceNameFor(String packageName) {
        return packageName + RESOURCE_SUFFIX;
    }

    /** The tier a rule carrying {@code label} belongs to. */
    public static TransitiveRule.Tier tierOf(String label) {
        return SAFETY_LABELS.contains(label) ? TransitiveRule.Tier.SAFETY : TransitiveRule.Tier.ADVISORY;
    }

    /**
     * Reads an annotation instance's attributes into an ordered map, skipping any left at their
     * declared default.
     *
     * <p>Defaults are skipped so a library that annotates a package with {@code @AICore} and sets
     * only {@code note} does not publish six empty strings for an agent to read past.
     *
     * <p><strong>Attributes are ordered by name, and that is load-bearing.</strong>
     * {@link Class#getDeclaredMethods()} has no specified order, and it genuinely differs between
     * JDK versions: on JDK 26 {@code @AIContext} reported {@code focus} then {@code avoids}, on JDK
     * 25 the reverse. Left as-reported, the same sources produced different manifests and different
     * generated files depending on which JDK compiled them — committed guardrails churning on every
     * colleague's machine, and a published JAR that is not byte-reproducible. Declaration order
     * would read marginally better and is not available through reflection at all. Same class of
     * defect, and the same fix, as {@code GuardrailModel} sorting its buckets rather than keeping
     * {@code getElementsAnnotatedWith}'s unspecified order.
     *
     * <p>Array attributes are joined with {@code ", "}. A {@code Class}-valued attribute is
     * rendered by its name; that path cannot throw {@code MirroredTypeException} here because this
     * runs against an annotation proxy on a snapshotted element, not against a live
     * {@code javax.lang.model} mirror.
     */
    public static Map<String, String> membersOf(Annotation annotation) {
        Map<String, String> members = new LinkedHashMap<>();
        Method[] declared = annotation.annotationType().getDeclaredMethods().clone();
        Arrays.sort(declared, java.util.Comparator.comparing(Method::getName));
        for (Method m : declared) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            Object value;
            try {
                value = m.invoke(annotation);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // A Class-valued member on a live mirror throws MirroredTypeException. Elements
                // reaching here are snapshots, so this is defensive: skip the attribute rather
                // than lose the whole rule, and never let it escape into the consumer's build.
                continue;
            }
            if (value == null || equalsDefault(m, value)) {
                continue;
            }
            String rendered = render(value);
            if (!rendered.isEmpty()) {
                members.put(m.getName(), rendered);
            }
        }
        return members;
    }

    private static boolean equalsDefault(Method m, Object value) {
        Object def = m.getDefaultValue();
        if (def == null) {
            return false;
        }
        if (def.getClass().isArray() && value.getClass().isArray()) {
            return Arrays.deepEquals(new Object[]{def}, new Object[]{value});
        }
        return def.equals(value);
    }

    private static String render(Object value) {
        if (value instanceof Object[] array) {
            List<String> parts = new ArrayList<>(array.length);
            for (Object o : array) {
                String s = String.valueOf(o).strip();
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof Class<?> c) {
            return c.getName();
        }
        return String.valueOf(value).strip();
    }

    /**
     * Serialises one package's rules. {@code rules} is sorted here, so callers need not.
     *
     * @param origin the publishing artifact's {@code group:artifact:version}, or {@code ""}
     */
    public static String toJson(String packageName, String origin, List<TransitiveRule> rules,
                                String producedByVersion) {
        List<TransitiveRule> sorted = new ArrayList<>(rules);
        java.util.Collections.sort(sorted);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n")
            .append("  \"manifestVersion\": ").append(FORMAT_VERSION).append(",\n")
            .append("  \"origin\": ").append(Json.quote(origin)).append(",\n")
            .append("  \"package\": ").append(Json.quote(packageName)).append(",\n")
            .append("  \"producedBy\": ").append(Json.quote("vibetags/" + producedByVersion)).append(",\n")
            .append("  \"rules\": [");
        for (int i = 0; i < sorted.size(); i++) {
            TransitiveRule rule = sorted.get(i);
            sb.append(i == 0 ? "\n" : ",\n")
                .append("    {\n")
                .append("      \"annotation\": ").append(Json.quote(rule.annotation())).append(",\n")
                .append("      \"tier\": ").append(Json.quote(rule.tier().name())).append(",\n")
                .append("      \"members\": {");
            int j = 0;
            for (Map.Entry<String, String> e : rule.members().entrySet()) {
                sb.append(j++ == 0 ? "\n" : ",\n")
                    .append("        ").append(Json.quote(e.getKey())).append(": ").append(Json.quote(e.getValue()));
            }
            sb.append(j == 0 ? "}\n" : "\n      }\n")
                .append("    }");
        }
        sb.append(sorted.isEmpty() ? "]\n" : "\n  ]\n")
            .append("}\n");
        return sb.toString();
    }

    /**
     * Parses one manifest document into rules.
     *
     * @param fallbackPackage the package the reader asked for, used when the document omits its own
     *                        {@code package} field
     * @return the rules, or an empty list when the document is a version this build does not
     *         understand
     * @throws Json.JsonException if the document is not well-formed
     * @throws IllegalArgumentException if the document is well-formed but not a manifest
     */
    public static List<TransitiveRule> parse(String json, String fallbackPackage) {
        Map<String, Object> root = Json.parseObject(json);
        Object versionValue = root.get("manifestVersion");
        int version;
        try {
            version = Integer.parseInt(String.valueOf(versionValue).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("manifestVersion is missing or not a number", e);
        }
        if (version > FORMAT_VERSION) {
            // Newer than this processor understands. Skipping beats guessing: a half-read rule is
            // still rendered into instructions somebody's agent will act on.
            return List.of();
        }
        String origin = Json.string(root, "origin", "");
        String packageName = Json.string(root, "package", fallbackPackage);
        Object rulesValue = root.get("rules");
        if (!(rulesValue instanceof List<?> rawRules)) {
            throw new IllegalArgumentException("rules is missing or not an array");
        }
        List<TransitiveRule> out = new ArrayList<>(rawRules.size());
        for (Object raw : rawRules) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("a rules entry is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ruleObject = (Map<String, Object>) rawMap;
            String label = Json.string(ruleObject, "annotation", "");
            if (label.isEmpty()) {
                throw new IllegalArgumentException("a rules entry has no annotation");
            }
            // The tier is re-derived rather than trusted. It is a function of the annotation, and a
            // JAR that claimed SAFETY for an advisory rule would buy itself a place in the section
            // the volume cap never drops.
            TransitiveRule.Tier tier = tierOf(label);
            Map<String, String> members = new LinkedHashMap<>();
            if (ruleObject.get("members") instanceof Map<?, ?> rawMembers) {
                for (Map.Entry<?, ?> e : rawMembers.entrySet()) {
                    members.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            out.add(new TransitiveRule(origin, packageName, label, tier, members));
        }
        return out;
    }
}
