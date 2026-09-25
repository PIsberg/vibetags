package se.deversity.vibetags.loadtest;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * A processor that does only what an early "the sources did not change" exit would have to do,
 * used as a control for issue #834.
 *
 * <p>The spike on that issue named the measurement that decides it. A rebuild can only skip the
 * collection walk if it can prove, before the walk, that no annotated source changed. An mtime
 * proxy is cheap and wrong in the direction {@code WriteCache}'s guardrail forbids (a false
 * "unchanged" leaves stale guardrail files), so the proxy would have to read content. This
 * processor reads it: on the first round it resolves every root element to its compilation unit,
 * streams each distinct source through SHA-256, and returns {@code false} as VibeTags does.
 *
 * <p>What it leaves out makes it a lower bound on the proxy, which is the direction that keeps the
 * comparison honest. A real exit would also compare the digests against a stored table, persist
 * them, and prove the classpath's transitive manifests unchanged. If even this much is not clearly
 * cheaper than what the short-circuit leaves standing, the real one has no headroom.
 *
 * <p>It counts what it hashed so the harness can assert the arm did its work. A control that
 * silently hashed nothing would report the cheapest proxy ever measured.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class SourceHashProcessor extends AbstractProcessor {

    /** Distinct sources hashed by the most recent compile. Read by the harness after the compile. */
    private static volatile int lastHashedCount;

    private Trees trees;
    private boolean hashed;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        trees = Trees.instance(processingEnv);
        lastHashedCount = 0;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (hashed || roundEnv.processingOver()) {
            return false;
        }
        hashed = true;
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        Set<URI> seen = new HashSet<>();
        for (Element root : roundEnv.getRootElements()) {
            TreePath path = trees.getPath(root);
            if (path == null) {
                continue;
            }
            JavaFileObject source = path.getCompilationUnit().getSourceFile();
            if (!seen.add(source.toUri())) {
                continue;
            }
            try (InputStream in = source.openInputStream()) {
                for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + source.toUri(), e);
            }
            // One digest per file, as a stored per-source table would need, not one for the round.
            digest.digest();
        }
        lastHashedCount = seen.size();
        return false;
    }

    /** How many distinct sources the most recent compile hashed. */
    static int lastHashedCount() {
        return lastHashedCount;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JDK", e);
        }
    }
}
