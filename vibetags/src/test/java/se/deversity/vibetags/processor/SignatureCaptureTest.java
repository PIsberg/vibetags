package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural signatures are computed only when something is going to read them.
 *
 * <p>{@code ElementSignature.of} on a type walks every enclosed member, renders each one and sorts
 * the result — the most expensive thing the collector does per element. Its only reader is the
 * opt-in enforcing mode, so on an ordinary build every one of those strings used to be built and
 * then dropped. These tests pin both directions of the switch against real javac elements, because
 * a mocked {@code Element} produces an empty signature either way and would pass whatever the
 * collector did.
 *
 * <p>The other half of the contract — that enforcement still catches a changed shape once it is
 * switched on — is {@code EnforcingModeEndToEndTest}.
 */
@Tag("e2e")
class SignatureCaptureTest {

    private static final String SOURCE =
        "package com.example.sig;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"payment maths\")\n"
            + "public class Charger {\n"
            + "    public long charge(String account, double amount) { return 0L; }\n"
            + "    public void refund(String account) {}\n"
            + "}\n";

    @Test
    void signatureIsNotComputed_whenCaptureIsOff(@TempDir Path tmp) throws IOException {
        assertEquals("", collectSignature(tmp, false),
            "With enforcement off nothing reads the signature, so it must not be built");
    }

    @Test
    void signatureIsComputed_whenCaptureIsOn(@TempDir Path tmp) throws IOException {
        String signature = collectSignature(tmp, true);

        assertTrue(signature.contains("charge(java.lang.String,double):long"),
            "The captured signature must describe the guarded method's shape. Was: " + signature);
        assertTrue(signature.contains("refund"),
            "Every visible member belongs in the signature. Was: " + signature);
    }

    /**
     * Runs the collector inside a real compilation and returns the signature it recorded for the
     * one {@code @AILocked} element.
     */
    private static String collectSignature(Path tmp, boolean capture) throws IOException {
        Path classOut = tmp.resolve("classes");
        Files.createDirectories(classOut);

        CapturingProcessor processor = new CapturingProcessor(capture);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = ProcessorTestHarness.sharedFileManager();
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
        JavaCompiler.CompilationTask task = compiler.getTask(
            null, fm, diagnostics,
            List.of("-classpath", System.getProperty("java.class.path"), "-proc:only"),
            null,
            List.of(new StringSource("com/example/sig/Charger.java", SOURCE)));
        task.setProcessors(List.of(processor));
        task.call();
        return processor.signature;
    }

    /** Drives {@link AnnotationCollector} directly so the assertion is about the collector alone. */
    private static final class CapturingProcessor extends AbstractProcessor {

        private final AnnotationCollector collector = new AnnotationCollector();
        private final boolean capture;
        private String signature = "<never collected>";

        CapturingProcessor(boolean capture) {
            this.capture = capture;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            collector.captureSignatures(capture);
            if (!roundEnv.processingOver()) {
                collector.collect(roundEnv);
                return false;
            }
            for (TaggedElement element : collector.model().of(AILocked.class)) {
                signature = element.signature();
            }
            return false;
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String name, String code) {
            super(URI.create("string:///" + name), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
