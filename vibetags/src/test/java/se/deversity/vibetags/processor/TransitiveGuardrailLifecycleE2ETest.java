package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole transitive-guardrail lifecycle, driven through a real {@code javac} in both halves:
 * a library declares package guardrails and is compiled and jarred, then a separate compilation
 * of a consuming project resolves that JAR off its compile classpath and renders the rules into
 * its own {@code CLAUDE.md}.
 *
 * <p>Nothing here is stubbed. The library is compiled, packaged and read back exactly as Maven or
 * Gradle would do it, because the feature's one hard question — what an annotation processor can
 * actually see on the compile classpath — has an answer that only a real compiler gives. Unit
 * tests over the reader would have happily passed against the {@code META-INF/} layout that does
 * not work at all.
 *
 * @see #aManifestUnderMetaInfIsInvisibleToTheCompiler() for the measurement that shaped the design
 */
@Tag("e2e")
class TransitiveGuardrailLifecycleE2ETest {

    private static final String LIB_PACKAGE_INFO = """
        @se.deversity.vibetags.annotations.AISecure(
            aspect = "Never construct a raw Cipher; go through CryptoManagerFactory.")
        @se.deversity.vibetags.annotations.AIContext(
            focus = "Key exchange is non-blocking; do not add synchronous IO.")
        @se.deversity.vibetags.annotations.AIThreadSafe(
            note = "Every factory product is safe to share between threads.")
        package com.acme.crypto.api;
        """;

    private static final String LIB_CLASS = """
        package com.acme.crypto.api;
        public final class CryptoManagerFactory {
            public static Object create() { return new Object(); }
        }
        """;

    private static final String CONSUMER_CLASS = """
        package app;
        import com.acme.crypto.api.CryptoManagerFactory;
        public class App {
            Object gateway = CryptoManagerFactory.create();
        }
        """;

    /**
     * Fixture root for one test.
     *
     * <p>Managed here rather than with {@code @TempDir} so cleanup can tolerate a locked file;
     * see {@link #deleteRecursively(Path)}.
     */
    private Path tmp;

    @BeforeEach
    void createFixtureRoot() throws IOException {
        tmp = Files.createTempDirectory("vibetags-transitive");
    }

    @AfterEach
    void removeFixtureRoot() throws IOException {
        deleteRecursively(tmp);
    }

    // ---------------------------------------------------------------------------- the happy path

    @Test
    void aLibrarysPackageGuardrailsReachAConsumersGeneratedFiles() throws Exception {
        Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
        Path app = consumerRoot(tmp, true);

        compile(app, List.of("-classpath", classpathWith(jar)), sourceFile(app, "app/App.java", CONSUMER_CLASS));

        String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("Inherited Guardrails (dependencies)"),
            "the safety-tier block should be present:\n" + report(app, "CLAUDE.md"));
        assertTrue(claude.contains("com.acme.crypto.api"), claude);
        assertTrue(claude.contains("com.acme:crypto-core:2.4.0"),
            "the rule must name the artifact it came from:\n" + claude);
        assertTrue(claude.contains("Never construct a raw Cipher"), claude);
        assertTrue(claude.contains("Inherited Context (dependencies)"),
            "the advisory-tier block should be present too:\n" + claude);
        assertTrue(claude.contains("Key exchange is non-blocking"), claude);
    }

    @Test
    void theInheritedBlockComesAfterTheProjectsOwnRules() throws Exception {
        // Order is the entire precedence mechanism. If a dependency's words could appear above the
        // application's own, a library would be able to talk over the project about itself.
        {
            Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
            Path app = consumerRoot(tmp, true);
            compile(app, List.of("-classpath", classpathWith(jar)),
                sourceFile(app, "app/App.java", """
                    package app;
                    import com.acme.crypto.api.CryptoManagerFactory;
                    @se.deversity.vibetags.annotations.AILocked(reason = "app owns this")
                    public class App {
                        Object gateway = CryptoManagerFactory.create();
                    }
                    """));

            String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
            int own = claude.indexOf("app owns this");
            int inherited = claude.indexOf("Inherited Guardrails (dependencies)");
            assertTrue(own >= 0, "the project's own rule should be present:\n" + claude);
            assertTrue(inherited > own,
                "inherited rules must follow the project's own:\n" + claude);
        }
    }

    // --------------------------------------------------------------------------- the opt-in gates

    @Test
    void nothingIsInheritedWithoutTheConsumerOptIn() throws Exception {
        Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
        Path app = consumerRoot(tmp, false);

        compile(app, List.of("-classpath", classpathWith(jar)), sourceFile(app, "app/App.java", CONSUMER_CLASS));

        String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertFalse(claude.contains("Inherited Guardrails"),
            "a project that did not ask for dependency rules must not receive them:\n" + claude);
    }

    @Test
    void aLibraryPublishesNothingWithoutTheEmitOptIn() throws Exception {
        Path lib = tmp.resolve("lib-silent");
        Path classes = compileLibrary(lib, null);
        assertFalse(Files.isDirectory(classes.resolve("vibetags").resolve("manifests")),
            "upgrading the processor must never start publishing a library's internals");
    }

    @Test
    void anUnimportedPackageIsNeverLookedUp() throws Exception {
        // This is what bounds the volume of inherited text, and it is structural: the lookup key
        // is derived from the import, so a package nobody imports has no key to be found by.
        Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
        Path app = consumerRoot(tmp, true);

        compile(app, List.of("-classpath", classpathWith(jar)), sourceFile(app, "app/App.java", """
            package app;
            public class App { int unrelated = 1; }
            """));

        String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertFalse(claude.contains("Inherited Guardrails"),
            "a dependency the sources never import must contribute nothing:\n" + claude);
    }

    // ------------------------------------------------------------- the measurement behind the path

    @Test
    void aManifestUnderMetaInfIsInvisibleToTheCompiler() throws Exception {
        // Pins the finding the whole design rests on. javac's CLASS_PATH location skips archive
        // directories that are not valid package names, so the conventional META-INF/ location is
        // unreadable from an annotation processor. Without this test, moving the manifest back to
        // the "obvious" place would look like a tidy-up and would silently disable the feature.
        Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
        Path repacked = tmp.resolve("meta-inf-only.jar");
        repackUnderMetaInf(jar, repacked);
        Path app = consumerRoot(tmp, true);

        compile(app, List.of("-classpath", classpathWith(repacked)),
            sourceFile(app, "app/App.java", CONSUMER_CLASS));

        String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertFalse(claude.contains("Inherited Guardrails"),
            "if this ever passes, javac gained the ability to read META-INF from CLASS_PATH and "
                + "the manifest location can be reconsidered — until then it cannot:\n" + claude);
    }

    // ----------------------------------------------------------------- the silent-failure regression

    @Test
    void upgradingADependencyRewritesTheGeneratedFile() throws Exception {
        // Whether the inherited rules are part of the build fingerprint is asserted directly by
        // TransitiveFingerprintTest, not here: this path also rewrites the module sidecar on every
        // run, and the sidecar's mtime feeds the other half of the same short-circuit, so an
        // upgrade reaches the file here whether or not the fingerprint noticed. What this test is
        // good for is the whole chain — new JAR, new lookup, new render, file actually rewritten
        // over a populated write cache.
        Path app = consumerRoot(tmp, true);
        Path source = sourceFile(app, "app/App.java", CONSUMER_CLASS);

        Path v1 = buildLibraryJar(tmp.resolve("v1"), "com.acme:crypto-core:2.4.0");
        compile(app, List.of("-classpath", classpathWith(v1)), source);
        String first = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(first.contains("com.acme:crypto-core:2.4.0"), first);
        assertTrue(Files.exists(app.resolve(".vibetags-cache")),
            "the write cache must be live, or this test proves nothing about the short-circuit");

        Path v2 = buildLibraryJar(tmp.resolve("v2"), "com.acme:crypto-core:2.5.0");
        compile(app, List.of("-classpath", classpathWith(v2)), source);
        String second = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);

        assertTrue(second.contains("com.acme:crypto-core:2.5.0"),
            "the upgraded dependency must reach the file:\n" + report(app, "CLAUDE.md"));
        assertFalse(second.contains("com.acme:crypto-core:2.4.0"),
            "the superseded version must be gone:\n" + report(app, "CLAUDE.md"));
    }

    @Test
    void recompilingWithAnUnchangedDependencyIsStable() throws Exception {
        // The other half of the same contract: inherited rules must not make every build look
        // changed, or the cache never hits and committed files churn on every colleague's machine.
        Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
        Path app = consumerRoot(tmp, true);
        Path source = sourceFile(app, "app/App.java", CONSUMER_CLASS);

        compile(app, List.of("-classpath", classpathWith(jar)), source);
        String first = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        compile(app, List.of("-classpath", classpathWith(jar)), source);
        String second = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);

        assertEquals(first, second, "identical inputs must produce identical bytes");
    }

    // ----------------------------------------------------------------------- the directory fallback

    @Test
    void aPreExtractedManifestDirectoryWorksWithoutTheClasspath() throws Exception {
        // The escape hatch for kapt, ECJ and JPMS builds, where classpath discovery cannot work.
        // Exercised through a real compile so the option plumbing is covered, not just the reader.
        // Note the source imports nothing and the JAR is not on the classpath: this path is
        // deliberately independent of both.
        Path extracted = extractManifests(tmp, "com.acme:crypto-core:2.4.0");
        Path app = consumerRoot(tmp, true);
        compile(app, manifestDirOptions(extracted),
            sourceFile(app, "app/App.java", "package app;\npublic class App {}\n"));

        String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("com.acme.crypto.api"),
            "the directory fallback must work with no dependency on the compile classpath:\n" + claude);
    }

    @Test
    void anExplicitPackageListResolvesOffTheClasspathWithoutReadingImports() throws Exception {
        // -Avibetags.manifest.packages is the fallback for a build whose compiler exposes no Tree
        // API. It still goes through Filer.getResource, and it runs in the final round rather than
        // a live one — worth an end-to-end test rather than trusting that a Filer read is legal
        // there, which is the kind of assumption javac punishes quietly.
        Path jar = buildLibraryJar(tmp, "com.acme:crypto-core:2.4.0");
        Path app = consumerRoot(tmp, true);

        compile(app,
            List.of("-classpath", classpathWith(jar),
                    "-Avibetags.manifest.packages=com.acme.crypto.api,com.nothing.here"),
            // Imports nothing, so import-driven discovery finds nothing and only the option can.
            sourceFile(app, "app/App.java", "package app;\npublic class App {}\n"));

        String claude = Files.readString(app.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("Never construct a raw Cipher"),
            "the explicit key list should have resolved the manifest:\n" + report(app, "CLAUDE.md"));
        assertTrue(claude.contains("com.acme:crypto-core:2.4.0"), claude);
    }

    @Test
    void theAdvisoryCapDropsAdvisoryRulesButNeverSafetyOnes() throws Exception {
        // The fixture package carries one safety rule (@AISecure) and two advisory ones
        // (@AIContext, @AIThreadSafe), so a cap of 1 has something to drop and something to
        // keep. Volume control that could drop a safety rule would be a security feature that
        // quietly stops working on large projects.
        Path extracted = extractManifests(tmp, "com.acme:crypto-core:2.4.0");
        String noSource = "package app;\npublic class App {}\n";

        Path uncappedRoot = consumerRoot(tmp.resolve("uncapped"), true);
        compile(uncappedRoot, manifestDirOptions(extracted),
            sourceFile(uncappedRoot, "app/App.java", noSource));
        String uncapped = Files.readString(uncappedRoot.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(uncapped.contains("Key exchange is non-blocking"), uncapped);
        assertTrue(uncapped.contains("safe to share between threads"), uncapped);

        Path cappedRoot = consumerRoot(tmp.resolve("capped"), true);
        List<String> options = new ArrayList<>(manifestDirOptions(extracted));
        options.add("-Avibetags.manifest.max=1");
        compile(cappedRoot, options, sourceFile(cappedRoot, "app/App.java", noSource));
        String capped = Files.readString(cappedRoot.resolve("CLAUDE.md"), StandardCharsets.UTF_8);

        assertTrue(capped.contains("Never construct a raw Cipher"),
            "a safety rule must survive any cap:\n" + capped);
        long advisoryBullets = capped.lines()
            .filter(l -> l.contains("Key exchange is non-blocking")
                || l.contains("safe to share between threads")).count();
        assertEquals(1, advisoryBullets,
            "a cap of 1 should leave exactly one advisory rule:\n" + capped);
    }

    /** Compiles the fixture library and copies its manifests into a standalone directory. */
    private static Path extractManifests(Path tmp, String origin) throws IOException {
        Path classes = compileLibrary(tmp.resolve("lib"), origin);
        Path extracted = tmp.resolve("extracted");
        Files.createDirectories(extracted);
        try (Stream<Path> manifests = Files.list(classes.resolve("vibetags").resolve("manifests"))) {
            for (Path manifest : manifests.toList()) {
                Files.copy(manifest, extracted.resolve(manifest.getFileName().toString()));
            }
        }
        return extracted;
    }

    private static List<String> manifestDirOptions(Path extracted) {
        return List.of("-classpath", System.getProperty("java.class.path"),
            "-Avibetags.manifest.dir=" + extracted.toAbsolutePath());
    }

    // ------------------------------------------------------------------------------------ helpers

    /** Compiles the fixture library and returns its class-output directory. */
    private static Path compileLibrary(Path libRoot, String origin) throws IOException {
        Files.createDirectories(libRoot);
        if (origin != null) {
            Files.writeString(libRoot.resolve(".vibetags-manifest"),
                "# artifact coordinate stamped into every manifest\n" + origin + "\n");
        }
        Path classes = libRoot.resolve("classes");
        Files.createDirectories(classes);

        List<Path> sources = List.of(
            sourceFile(libRoot, "com/acme/crypto/api/package-info.java", LIB_PACKAGE_INFO),
            sourceFile(libRoot, "com/acme/crypto/api/CryptoManagerFactory.java", LIB_CLASS));

        run(classes, List.of("-classpath", System.getProperty("java.class.path"),
            "-Avibetags.root=" + libRoot.toAbsolutePath()), sources);
        return classes;
    }

    /** Compiles the fixture library and packages its class output into a JAR. */
    private static Path buildLibraryJar(Path base, String origin) throws IOException {
        Files.createDirectories(base);
        Path classes = compileLibrary(base.resolve("lib"), origin);
        assertTrue(Files.isRegularFile(
                classes.resolve("vibetags").resolve("manifests").resolve("com.acme.crypto.api.json")),
            "the library build should have published a manifest at the package-named path");
        Path jar = base.resolve("lib.jar");
        jar(classes, jar);
        return jar;
    }

    /** A consumer project root with CLAUDE.md opted in, and optionally transitive discovery too. */
    private static Path consumerRoot(Path base, boolean transitive) throws IOException {
        Path app = base.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("CLAUDE.md"), "");
        if (transitive) {
            Files.createFile(app.resolve(".vibetags-transitive"));
        }
        return app;
    }

    private static void compile(Path appRoot, List<String> options, Path... sources) throws IOException {
        List<String> all = new ArrayList<>(options);
        all.add("-Avibetags.root=" + appRoot.toAbsolutePath());
        Path classes = appRoot.resolve("classes");
        Files.createDirectories(classes);
        run(classes, all, List.of(sources));
    }

    /**
     * Runs one compilation with a private file manager.
     *
     * <p>Deliberately not the harness's shared per-thread manager. javac keeps every JAR it opened
     * in that manager's cache, and the cache is never closed — on Windows the fixture JAR then
     * stays locked and {@code @TempDir} cleanup fails after the assertions have already passed,
     * turning a green test into an error. A manager this method owns and closes releases the
     * handle before the test ends. Handing javac its own {@code getStandardFileManager} class
     * back (rather than a forwarding wrapper) keeps the CodeQL constraint the harness documents.
     */
    private static void run(Path classOutput, List<String> options, List<Path> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "run the tests on a JDK, not a JRE");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOutput.toFile()));
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            JavaCompiler.CompilationTask task =
                compiler.getTask(null, fm, diagnostics, options, null, units);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            ok = task.call();
        }

        List<String> errors = diagnostics.getDiagnostics().stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
            .map(Object::toString).toList();
        assertTrue(ok && errors.isEmpty(), "compilation failed:\n  " + String.join("\n  ", errors));
        LAST_NOTES.set(diagnostics.getDiagnostics().stream()
            .map(d -> d.getKind() + ": " + d.getMessage(null)).toList());
    }

    /**
     * The diagnostics of the most recent compilation on this thread. Assertion failures in this
     * class are about a file that came out wrong, and the processor's own NOTEs say which branch
     * it took to get there; without them a failure here is a blank file and no explanation.
     */
    private static final ThreadLocal<List<String>> LAST_NOTES = ThreadLocal.withInitial(List::of);

    /** The generated file plus the processor's diagnostics, for an assertion message. */
    private static String report(Path appRoot, String relative) throws IOException {
        Path file = appRoot.resolve(relative);
        String content = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "<absent>";
        return content + "\n--- processor diagnostics ---\n  " + String.join("\n  ", LAST_NOTES.get());
    }

    private static Path sourceFile(Path root, String relative, String content) throws IOException {
        Path file = root.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String classpathWith(Path jar) {
        return jar.toAbsolutePath() + java.io.File.pathSeparator + System.getProperty("java.class.path");
    }

    /** Packs {@code dir} into {@code jar}, entry names relative to {@code dir}. */
    private static void jar(Path dir, Path jarFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(out);
             Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = dir.relativize(path).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(name));
                jos.write(Files.readAllBytes(path));
                jos.closeEntry();
            }
        }
    }

    /**
     * Rewrites {@code source} with every {@code vibetags/manifests/} entry moved to
     * {@code META-INF/} — the layout the original design proposed, and the one javac cannot read.
     */
    private static void repackUnderMetaInf(Path source, Path target) throws IOException {
        try (java.util.jar.JarFile in = new java.util.jar.JarFile(source.toFile());
             OutputStream out = Files.newOutputStream(target);
             JarOutputStream jos = new JarOutputStream(out)) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("vibetags/manifests/")) {
                    name = "META-INF/" + name.substring("vibetags/manifests/".length());
                }
                jos.putNextEntry(new JarEntry(name));
                try (var is = in.getInputStream(entry)) {
                    jos.write(is.readAllBytes());
                }
                jos.closeEntry();
            }
        }
    }

    /**
     * Best-effort recursive delete.
     *
     * <p>Tolerant of files it cannot remove, which on Windows is not hypothetical: the processor's
     * SLF4J appender holds {@code vibetags.log} open for the lifetime of the compilation's
     * classloader, so a fixture root can still be locked when the test ends. {@code @TempDir}
     * turns that into a failed test <em>after</em> every assertion has passed, which reports a
     * cleanup problem as a broken feature. Deleting what we can and leaving the rest to the OS
     * keeps the test's verdict about the code under test.
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Locked by a still-open handle; the OS reclaims it with the temp directory.
                }
            }
        }
    }
}
