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
