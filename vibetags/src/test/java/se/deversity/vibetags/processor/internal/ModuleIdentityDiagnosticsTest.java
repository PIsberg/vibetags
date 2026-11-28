package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleIdentityDiagnostics}: the warnings about a compilation whose module identity will
 * cost it guardrails, each with its exact text, its log event and every early return.
 *
 * <p>{@code DetachedModuleWarningTest}, {@code SourceDigestEarlyExitEndToEndTest} and
 * {@code DiagnosticPathSeparatorTest} prove each one is raised by a real build, by fragment. These
 * pin the whole sentence, the singular and plural forms and the four log events, which nothing
 * asserted before.
 */
@DisplayName("Module identity diagnostics")
class ModuleIdentityDiagnosticsTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(ModuleIdentityDiagnosticsTest.class.getName() + "." + testInfo.getDisplayName());
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private final Messager messager = new Messager() {
        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
            if (kind == Diagnostic.Kind.WARNING) {
                warnings.add(msg.toString());
            }
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a,
                                 AnnotationValue v) {
            printMessage(kind, msg);
        }
    };

    private List<String> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ModuleIdentityDiagnostics diagnostics(Path root, @Nullable String moduleIdOverride) {
        return new ModuleIdentityDiagnostics(root, messager, moduleIdOverride, logger);
    }

    // ---------------------------------------------------------------- detached from its reactor

    @Test
    @DisplayName("a module its reactor declares, generating as its own root, is told to pass -Avibetags.root")
    void aDetachedModuleIsTold(@TempDir Path tmp) throws IOException {
        Path reactor = reactorDeclaring(tmp, "module-core");
        Path module = reactor.resolve("module-core");

        diagnostics(module, null).warnIfDetachedFromReactor(module, true);

        assertEquals(List.of("VibeTags: module 'module-core' generated its guardrails as its own root ("
            + module + "), but " + reactor + " declares it as a module. Its guardrails are"
            + " NOT part of that reactor's merged files. Pass -Avibetags.root=" + reactor
            + " for this module \u2014 most often it overrides the compiler plugin's compilerArgs or"
            + " annotationProcessorPaths and so does not inherit the reactor's configuration."), warnings);
        assertEquals(List.of("module.detached module=module-core moduleRoot=" + module + " reactorRoot=" + reactor),
            events());
    }

    @Test
    @DisplayName("a detached module with nothing annotated loses nothing, so nothing is said")
    void aDetachedModuleThatContributesNothingIsSilent(@TempDir Path tmp) throws IOException {
        Path module = reactorDeclaring(tmp, "module-core").resolve("module-core");

        diagnostics(module, null).warnIfDetachedFromReactor(module, false);

        assertEquals(List.of(), warnings);
        assertEquals(List.of(), events());
    }

    @Test
    @DisplayName("a module already sharing the reactor's root is not detached")
    void aModuleUnderTheSharedRootIsSilent(@TempDir Path tmp) throws IOException {
        Path reactor = reactorDeclaring(tmp, "module-core");

        diagnostics(reactor, null).warnIfDetachedFromReactor(reactor.resolve("module-core"), true);

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a standalone project that merely lives inside another directory is not detached")
    void aStandaloneProjectIsSilent(@TempDir Path tmp) throws IOException {
        Path outer = reactorDeclaring(tmp, "something-else");
        Path standalone = outer.resolve("standalone");
        Files.createDirectories(standalone);
        Files.writeString(standalone.resolve("pom.xml"), "<project><artifactId>standalone</artifactId></project>",
            StandardCharsets.UTF_8);

        diagnostics(standalone, null).warnIfDetachedFromReactor(standalone, true);

        assertEquals(List.of(), warnings);
    }

    // ---------------------------------------------------------------- Gradle subprojects with no build file

    @Test
    @DisplayName("one Gradle subproject with no build file of its own is named in the singular")
    void oneCollapsingSubproject(@TempDir Path root) throws IOException {
        settings(root, "settings.gradle", "include 'alpha'\n");
        subproject(root, "alpha", false);

        diagnostics(root, null).warnIfModuleUnidentifiable(root, "_root_");

        assertEquals(List.of("VibeTags: alpha is declared as a Gradle subproject but has"
            + " no build file of its own, so it resolves to this root and shares one module"
            + " identity with every other such subproject. They overwrite one another's"
            + " guardrails and only the last to compile survives. Pass"
            + " -Avibetags.module=${project.name} in the shared build file to give each one"
            + " its own identity."), warnings);
        assertEquals(List.of("module.collapsed subprojects=[alpha] root=" + root + " reason=no-build-file-of-their-own"),
            events());
    }

    @Test
    @DisplayName("several are named once each, in settings order, across settings.gradle and settings.gradle.kts")
    void severalCollapsingSubprojects(@TempDir Path root) throws IOException {
        settings(root, "settings.gradle", "include 'alpha', 'beta'\n");
        settings(root, "settings.gradle.kts", "include(\"beta\")\ninclude(\"gamma\")\n");
        subproject(root, "alpha", false);
        subproject(root, "beta", false);
        subproject(root, "gamma", false);

        diagnostics(root, null).warnIfModuleUnidentifiable(root, "_root_");

        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertEquals("VibeTags: alpha, beta, gamma are declared as Gradle subprojects but have"
            + " no build file of its own, so it resolves to this root and shares one module"
            + " identity with every other such subproject. They overwrite one another's"
            + " guardrails and only the last to compile survives. Pass"
            + " -Avibetags.module=${project.name} in the shared build file to give each one"
            + " its own identity.", warnings.get(0));
    }

    @Test
    @DisplayName("a subproject with its own build file, or with no sources, does not collapse")
    void subprojectsThatDoNotCollapse(@TempDir Path root) throws IOException {
        settings(root, "settings.gradle", "include 'built', 'empty', 'absent'\n");
        subproject(root, "built", true);
        Files.createDirectories(root.resolve("empty"));

        diagnostics(root, null).warnIfModuleUnidentifiable(root, "_root_");

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("-Avibetags.module already gives each subproject its own identity, so nothing is said")
    void anOverrideSilencesTheCollapse(@TempDir Path root) throws IOException {
        settings(root, "settings.gradle", "include 'alpha'\n");
        subproject(root, "alpha", false);

        diagnostics(root, "alpha").warnIfModuleUnidentifiable(root, "alpha");

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a module that resolved below the root is not collapsing into it")
    void aModuleBelowTheRootDoesNotCollapse(@TempDir Path root) throws IOException {
        settings(root, "settings.gradle", "include 'alpha'\n");
        subproject(root, "alpha", false);
        Path module = subproject(root, "beta", true);

        diagnostics(root, null).warnIfModuleUnidentifiable(module, "beta");

        assertEquals(List.of(), warnings);
    }

    // ---------------------------------------------------------------- unidentifiable

    @Test
    @DisplayName("a module outside the root, beside named modules, is told it was filed under a hash")
    void anUnidentifiableModuleBesideNamedOnes(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        sidecar(root, "core");
        sidecar(root, "api");
        sidecar(root, "a1b2c3d4");
        List<String> named = namedBesides(root, "a1b2c3d4");
        assertEquals(Set.of("core", "api"), Set.copyOf(named), "precondition: both neighbours are read");

        diagnostics(root, null).warnIfModuleUnidentifiable(elsewhere, "a1b2c3d4");

        assertEquals(List.of("VibeTags: could not identify the compiling module (its sources are not under "
            + root + "), so it is filed under the content hash 'a1b2c3d4' alongside the existing module(s) "
            + String.join(", ", named)
            + ". If that hash is really one of them under another name, the shared guardrail"
            + " files will gain a duplicate set of regions. Pass -Avibetags.module=<name> to"
            + " name it, or -Avibetags.root so the module resolves under the root."), warnings);
        assertEquals(List.of("module.unidentified id=a1b2c3d4 root=" + root + " compilationRoot=" + elsewhere
            + " existing=" + named), events());
    }

    @Test
    @DisplayName("an unidentifiable module with no named neighbours, its own sidecar aside, is not warned about")
    void anUnidentifiableModuleAloneIsSilent(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        sidecar(root, "a1b2c3d4");

        diagnostics(root, null).warnIfModuleUnidentifiable(elsewhere, "a1b2c3d4");

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("an unidentifiable module named with -Avibetags.module took the remedy already")
    void anUnidentifiableModuleWithAnOverrideIsSilent(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        sidecar(root, "core");

        diagnostics(root, "mine").warnIfModuleUnidentifiable(elsewhere, "mine");

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a module under the root is identified by its path, so nothing is said")
    void aModuleUnderTheRootIsIdentified(@TempDir Path root) throws IOException {
        sidecar(root, "core");
        Path module = Files.createDirectories(root.resolve("api"));

        diagnostics(root, null).warnIfModuleUnidentifiable(module, "api");

        assertEquals(List.of(), warnings);
    }

    // ---------------------------------------------------------------- two modules, one id

    @Test
    @DisplayName("another live module filed under this module's id is named, with the remedy (#869)")
    void anIdCollisionIsNamed(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a_b"));
        new ModuleSidecar("a_b", "a_b", "a_b").save(root);

        diagnostics(root, null).warnAboutIdCollision("a_b", "a/b");

        assertEquals(List.of("VibeTags: modules 'a/b' and 'a_b' both resolve to the module id 'a_b',"
            + " so they share one sidecar and one region, and each build of one replaces the other's"
            + " guardrails in the shared files. Give one of them an id of its own with -Avibetags.module=<name>."),
            warnings);
        assertEquals(List.of("sidecar.collision moduleId=a_b path=a/b other=a_b"), events());
    }

    @Test
    @DisplayName("the root's own module is shown as (root), and a backslash path with forward slashes")
    void theRootAndBackslashesAreShownReadably(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("x"));
        new ModuleSidecar("_root_", "x", "_root_").save(root);

        diagnostics(root, null).warnAboutIdCollision("_root_", "");

        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).startsWith("VibeTags: modules '(root)' and 'x' both resolve "), warnings.get(0));

        warnings.clear();
        Files.createDirectories(root.resolve("c_d"));
        new ModuleSidecar("c_d", "c_d", "c_d").save(root);
        diagnostics(root, null).warnAboutIdCollision("c_d", "c\\d");

        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).startsWith("VibeTags: modules 'c/d' and 'c_d' both resolve "), warnings.get(0));
    }

    @Test
    @DisplayName("a sidecar under this id from a module that is gone, or from this module, is no collision")
    void noCollisionWithoutAnotherLiveModule(@TempDir Path root) throws IOException {
        new ModuleSidecar("gone", "gone", "gone").save(root); // its directory was never there
        Files.createDirectories(root.resolve("same"));
        new ModuleSidecar("same", "same", "same").save(root);

        diagnostics(root, null).warnAboutIdCollision("gone", "elsewhere");
        diagnostics(root, null).warnAboutIdCollision("same", "same");
        diagnostics(root, null).warnAboutIdCollision("none", "none");

        assertEquals(List.of(), warnings);
        assertEquals(List.of(), events());
    }

    // ---------------------------------------------------------------- fixtures

    private static Path reactorDeclaring(Path tmp, String module) throws IOException {
        Path reactor = Files.createDirectories(tmp.resolve("reactor"));
        Files.writeString(reactor.resolve("pom.xml"),
            "<project><modules><module>" + module + "</module></modules></project>", StandardCharsets.UTF_8);
        Path dir = Files.createDirectories(reactor.resolve(module));
        Files.writeString(dir.resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        return reactor;
    }

    private static void settings(Path root, String name, String text) throws IOException {
        Files.writeString(root.resolve(name), text, StandardCharsets.UTF_8);
    }

    /** A subproject directory with sources, and a build file of its own when asked. */
    private static Path subproject(Path root, String name, boolean withBuildFile) throws IOException {
        Path project = root.resolve(name);
        Files.createDirectories(project.resolve("src/main/java"));
        if (withBuildFile) {
            Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n", StandardCharsets.UTF_8);
        }
        return project;
    }

    /** A live module's sidecar: its directory exists, or a read prunes it as a departed module. */
    private static void sidecar(Path root, String id) throws IOException {
        Files.createDirectories(root.resolve(id));
        ModuleSidecar sidecar = new ModuleSidecar(id, id, id);
        sidecar.putBody("claude", id + " body");
        sidecar.setElementIds(Set.of("element-of-" + id));
        sidecar.save(root);
    }

    /** The ids the warning lists, in the order it reads them from disk. */
    private static List<String> namedBesides(Path root, String moduleId) {
        return ModuleSidecar.peekAll(root).stream()
            .map(ModuleSidecar::getModuleId)
            .filter(id -> !id.equals(moduleId))
            .toList();
    }
}
