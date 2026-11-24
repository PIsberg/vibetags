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
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * Positions in the {@code .vibetags-locks} report come from the javac Tree API, and
     * {@code Trees.instance} rejects a wrapped environment just as it does for module identity
     * above. Module identity has a standard-API fallback ({@code Elements.getFileObjectOf});
     * positions have none, so the resolver must reflectively unwrap the decorator or every entry
     * in the report silently loses its line range under Gradle.
     */
    @Test
    void lockReportPositions_surviveAWrappedEnvironment() throws IOException {
        Files.createFile(reactorRoot.resolve(".vibetags-locks"));
        Files.createDirectories(reactorRoot.resolve("module-core"));
        Files.writeString(reactorRoot.resolve("module-core").resolve("pom.xml"),
            "<project><artifactId>module-core</artifactId></project>", StandardCharsets.UTF_8);

        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        harness.writeSourceFile("module-core/src/main/java/com/example/core/IrNode.java", CORE_SOURCE);
        harness.compileWith(new WrappedEnvProcessor());

        String report = Files.readString(reactorRoot.resolve(".vibetags-locks"), StandardCharsets.UTF_8);
        assertTrue(report.contains("IrNode"),
            "the locked element must appear in the report:\n" + report);
        assertTrue(report.contains("\"startLine\""),
            "positions must survive the ProcessingEnvironment wrapper (reflective unwrap):\n" + report);
    }

    /**
     * {@code @AIArchitecture(cannotReference)} is the one guardrail that fails the build, and it
     * reads imports through the Tree API. Calling {@code Trees.instance} directly on the wrapped
     * environment threw, the rule downgraded itself to a NOTE, and every Gradle build compiled an
     * illegal import cleanly (issue #1118). The rule must unwrap the decorator like every other
     * Tree-API consumer does.
     */
    @Test
    void architectureImportCheck_survivesAWrappedEnvironment() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        harness.writeSourceFile("src/main/java/com/example/infra/Db.java", """
            package com.example.infra;

            public class Db {
            }
            """);
        harness.writeSourceFile("src/main/java/com/example/domain/Order.java", """
            package com.example.domain;

            import com.example.infra.Db;
            import se.deversity.vibetags.annotations.AIArchitecture;

            @AIArchitecture(belongsTo = "domain", cannotReference = {"com.example.infra"})
            public class Order {
                Db db;
            }
            """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness.compileWithReturningDiagnostics(new WrappedEnvProcessor());

        List<String> errors = diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(Locale.ROOT))
            .toList();
        assertTrue(errors.stream().anyMatch(e -> e.contains("illegal import of 'com.example.infra.Db'")),
            "the illegal import must still be an ERROR behind a wrapped environment: " + diagnostics);
    }

    /**
     * A non-public wrapper that exposes the inner environment only through {@code delegate()} or
     * {@code getDelegate()} (and carries a self-referential {@code ProcessingEnvironment} field)
     * must still unwrap cleanly (#1203).
     */
    @Test
    void lockReportPositions_surviveANonPublicAccessorOnlyWrapper() throws IOException {
        Files.createFile(reactorRoot.resolve(".vibetags-locks"));
        Files.createDirectories(reactorRoot.resolve("module-core"));
        Files.writeString(reactorRoot.resolve("module-core").resolve("pom.xml"),
            "<project><artifactId>module-core</artifactId></project>", StandardCharsets.UTF_8);

        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        harness.writeSourceFile("module-core/src/main/java/com/example/core/IrNode.java", CORE_SOURCE);
        harness.compileWith(new AccessorWrappedEnvProcessor());

        String report = Files.readString(reactorRoot.resolve(".vibetags-locks"), StandardCharsets.UTF_8);
        assertTrue(report.contains("IrNode"),
            "the locked element must appear in the report:\n" + report);
        assertTrue(report.contains("\"startLine\""),
            "positions must survive a non-public accessor-only ProcessingEnvironment wrapper:\n" + report);
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

    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @SupportedOptions({"vibetags.root", "vibetags.project", "vibetags.log.path", "vibetags.log.level",
                       "vibetags.cache", "vibetags.check", "vibetags.module"})
    private static final class AccessorWrappedEnvProcessor extends AIGuardrailProcessor {
        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(new GetDelegateOnlyEnvironment(new DelegateMethodOnlyEnvironment(processingEnv)));
        }
    }

    /**
     * Holds the wrapped environment in an {@code Object[]} so no {@code ProcessingEnvironment}
     * field exists to match, plus a self-referential {@code ProcessingEnvironment} field that must
     * be skipped, exposing the delegate only via {@code delegate()} (#1203).
     */
    private static final class DelegateMethodOnlyEnvironment implements ProcessingEnvironment {
        @SuppressWarnings("unused")
        private final ProcessingEnvironment self = this;
        private final Object[] box;

        DelegateMethodOnlyEnvironment(ProcessingEnvironment inner) {
            this.box = new Object[]{inner};
        }

        public ProcessingEnvironment delegate() {
            return (ProcessingEnvironment) box[0];
        }

        @Override public Map<String, String> getOptions() { return delegate().getOptions(); }
        @Override public Messager getMessager() { return delegate().getMessager(); }
        @Override public Filer getFiler() { return delegate().getFiler(); }
        @Override public Elements getElementUtils() { return delegate().getElementUtils(); }
        @Override public Types getTypeUtils() { return delegate().getTypeUtils(); }
        @Override public SourceVersion getSourceVersion() { return delegate().getSourceVersion(); }
        @Override public Locale getLocale() { return delegate().getLocale(); }
    }

    /** Exposes the delegate only via {@code getDelegate()}, with no {@code ProcessingEnvironment} field. */
    private static final class GetDelegateOnlyEnvironment implements ProcessingEnvironment {
        private final Object[] box;

        GetDelegateOnlyEnvironment(ProcessingEnvironment inner) {
            this.box = new Object[]{inner};
        }

        public ProcessingEnvironment getDelegate() {
            return (ProcessingEnvironment) box[0];
        }

        @Override public Map<String, String> getOptions() { return getDelegate().getOptions(); }
        @Override public Messager getMessager() { return getDelegate().getMessager(); }
        @Override public Filer getFiler() { return getDelegate().getFiler(); }
        @Override public Elements getElementUtils() { return getDelegate().getElementUtils(); }
        @Override public Types getTypeUtils() { return getDelegate().getTypeUtils(); }
        @Override public SourceVersion getSourceVersion() { return getDelegate().getSourceVersion(); }
        @Override public Locale getLocale() { return getDelegate().getLocale(); }
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
