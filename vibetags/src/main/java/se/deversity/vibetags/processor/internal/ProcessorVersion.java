package se.deversity.vibetags.processor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the running processor's version once, at class load.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code Implementation-Version} from the jar manifest (set by maven-jar-plugin /
 *       the Gradle jar manifest block);</li>
 *   <li>Maven's {@code pom.properties} embedded in the jar;</li>
 *   <li>{@code "dev"} — e.g. when running from {@code target/classes} in tests or a
 *       repackaged classpath without manifest metadata.</li>
 * </ol>
 *
 * <p>The value is folded into {@link BuildFingerprint} so that upgrading the processor
 * invalidates the previous run's top-level fingerprint: a new version may render different
 * content from identical annotation inputs, and the short-circuit must not skip that
 * regeneration. The {@code "dev"} fallback keeps fingerprints stable across local test runs.
 */
public final class ProcessorVersion {

    private static final String VERSION = resolve();

    private ProcessorVersion() {}

    /** The processor version, never null or blank. */
    public static String get() {
        return VERSION;
    }

    /**
     * Where the running processor was loaded from — the jar path, or the classes directory — or
     * {@code "unknown"} when the JVM will not say.
     *
     * <p>Logged next to the version because the pair is what settles the one question a version
     * banner cannot answer on its own. A build once reported a banner naming a release two
     * versions older than the only one {@code dependencies --configuration annotationProcessor}
     * listed, and the natural reading was a hardcoded fallback somewhere in the processor. There
     * is none: {@link #get()} is one constant read from the jar manifest and every banner prints
     * it, so an old banner means an old jar was on the processor path — a second configuration, a
     * stale build cache, or a daemon holding an old classloader. The jar's own path says which,
     * in one line, instead of an afternoon spent looking for a constant that does not exist.
     */
    public static String origin() {
        try {
            java.security.CodeSource source =
                ProcessorVersion.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return source.getLocation().toString();
            }
        } catch (RuntimeException withheld) {
            // A security manager or an exotic classloader may refuse; the version still prints.
        }
        return "unknown";
    }

    private static String resolve() {
        Package pkg = ProcessorVersion.class.getPackage();
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && !v.isBlank()) return v;
        }
        try (InputStream in = ProcessorVersion.class.getResourceAsStream(
                "/META-INF/maven/se.deversity.vibetags/vibetags-processor/pom.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (IOException ignored) {
            // Fall through to the dev fallback — version detection is best-effort.
        }
        return "dev";
    }
}
