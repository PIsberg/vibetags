package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module identity must survive a wrapped {@code ProcessingEnvironment}
 * (<a href="https://github.com/PIsberg/vibetags/issues/331">issue #331</a>).
 *
 * <p>VibeTags declares itself an {@code aggregating} incremental processor, so Gradle hands it its
 * own {@code ProcessingEnvironment} decorator rather than javac's. {@code Trees.instance} rejects
 * anything that is not literally {@code JavacProcessingEnvironment}, so under Gradle the Tree API
 * was never available, module resolution returned nothing for every module, and they all collapsed
 * onto the working directory — which under Gradle is neither the module nor the reactor root. The
 * result was one content-hash region appended beside Maven's named ones, restored on every later
 * build from a gitignored sidecar.
 *
 * <p>{@link DelegatingProcessingEnvironment} below reproduces exactly that: a plain delegate whose
 * only relevant property is not being javac's own class.
 */
class WrappedProcessingEnvironmentTest {

    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Core IR node")
        public class IrNode {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void moduleIsIdentifiedByNameEvenWhenTheTreeApiIsUnavailable() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createDirectories(reactorRoot.resolve("module-core"));
        Files.writeString(reactorRoot.resolve("module-core").resolve("pom.xml"),
            "<project><artifactId>module-core</artifactId></project>", StandardCharsets.UTF_8);

        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        harness.writeSourceFile("module-core/src/main/java/com/example/core/IrNode.java", CORE_SOURCE);
        harness.compileWith(new WrappedEnvProcessor());

        assertTrue(Files.exists(reactorRoot.resolve(".vibetags-mod-module-core")),
            "the sidecar must be filed under the module's directory name, not a content hash: "
                + sidecarNames());
    }

    /**
     * The whole point of the bug: with the Tree API gone, the fallback identity was shared by every
     * module, so the second module to compile overwrote the first instead of joining it.
     */
    @Test
    void twoModulesUnderAWrappedEnvironmentStayDistinct() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        for (String module : new String[]{"module-core", "module-cli"}) {
            Files.createDirectories(reactorRoot.resolve(module));
            Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
                "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
            ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
            harness.writeSourceFile(module + "/src/main/java/com/example/" + module.replace('-', '_')
                + "/Node.java", """
                package com.example.%s;

                import se.deversity.vibetags.annotations.AILocked;

                @AILocked(reason = "owned by %s")
                public class Node {
                }
                """.formatted(module.replace('-', '_'), module));
            harness.compileWith(new WrappedEnvProcessor());
        }

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-cli -->"), claude);
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-core -->"), claude);
        assertTrue(claude.contains("owned by module-core"), "the first module's guardrails survive");
        assertTrue(claude.contains("owned by module-cli"), "and the second's are added");
    }

    private String sidecarNames() throws IOException {
        try (Stream<Path> files = Files.list(reactorRoot)) {
            return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith(".vibetags-mod-"))
                        .sorted()
                        .toList()
                        .toString();
        }
    }

    /**
     * The processor as a build tool with its own incremental-processing decorator would run it.
     *
     * <p>The two {@code @Supported*} annotations are repeated because JSR 269 reads them off the
     * concrete class and Java annotations are not inherited — without them this subclass would
     * claim no annotation types and never run at all.
     */
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @SupportedOptions({"vibetags.root", "vibetags.project", "vibetags.log.path", "vibetags.log.level",
                       "vibetags.cache", "vibetags.check", "vibetags.module"})
    private static final class WrappedEnvProcessor extends AIGuardrailProcessor {
        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(new DelegatingProcessingEnvironment(processingEnv));
        }
    }

    /** Pure pass-through; the only thing that matters is that it is not javac's own class. */
    private record DelegatingProcessingEnvironment(ProcessingEnvironment delegate)
            implements ProcessingEnvironment {

        @Override public Map<String, String> getOptions() { return delegate.getOptions(); }
        @Override public Messager getMessager() { return delegate.getMessager(); }
        @Override public Filer getFiler() { return delegate.getFiler(); }
        @Override public Elements getElementUtils() { return delegate.getElementUtils(); }
        @Override public Types getTypeUtils() { return delegate.getTypeUtils(); }
        @Override public SourceVersion getSourceVersion() { return delegate.getSourceVersion(); }
        @Override public Locale getLocale() { return delegate.getLocale(); }
    }
}
