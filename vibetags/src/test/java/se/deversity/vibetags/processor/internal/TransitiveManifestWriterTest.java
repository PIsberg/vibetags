package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.TransitiveRule;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TransitiveManifestWriter} without a compiler: opt-in marker detection, coordinate extraction,
 * model grouping by package, and manifest file emission.
 */
@DisplayName("Transitive manifest emission and package grouping")
class TransitiveManifestWriterTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(TransitiveManifestWriterTest.class.getName() + "." + testInfo.getDisplayName());
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

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A annotation(Class<A> type, Map<String, Object> values) {
        return (A) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@" + type.getName();
                default -> values.containsKey(method.getName())
                    ? values.get(method.getName())
                    : defaultOf(method);
            });
    }

    private static Object defaultOf(Method method) {
        Object declared = method.getDefaultValue();
        if (declared != null) {
            return declared;
        }
        throw new IllegalStateException(method.getName() + " has no default; give it a value");
    }

    private static <A extends Annotation> TaggedElement packageElement(String pkgName, Class<A> type, A ann) {
        return TaggedElement.builder(pkgName)
            .kind(ElementTag.PACKAGE)
            .names(pkgName, pkgName, pkgName, pkgName.replace('.', '-'))
            .annotation(type, ann)
            .build();
    }

    @Test
    void optedIn_detectsMarkerFile(@TempDir Path root) throws IOException {
        assertFalse(TransitiveManifestWriter.optedIn(root),
            "optedIn must be false when marker file is absent");

        Path marker = root.resolve(TransitiveManifestWriter.MARKER_FILE);
        Files.createFile(marker);
        assertTrue(TransitiveManifestWriter.optedIn(root),
            "optedIn must be true when marker file is a regular file");

        Files.delete(marker);
        Files.createDirectory(marker);
        assertFalse(TransitiveManifestWriter.optedIn(root),
            "optedIn must be false when marker is a directory rather than a regular file");
    }

    @Test
    void originFrom_extractsFirstCoordinateLine(@TempDir Path root) throws IOException {
        assertEquals("", TransitiveManifestWriter.originFrom(root),
            "originFrom must return empty string when marker file is absent");

        Path marker = root.resolve(TransitiveManifestWriter.MARKER_FILE);
        Files.writeString(marker, "# only comments\n   \n# and blanks\n", StandardCharsets.UTF_8);
        assertEquals("", TransitiveManifestWriter.originFrom(root),
            "originFrom must return empty string when marker carries no non-comment lines");

        Files.writeString(marker, """
            # Header comment
            
               com.example:library-core:1.2.3   
            # Second line is ignored
            com.example:library-extra:4.5.6
            """, StandardCharsets.UTF_8);
        assertEquals("com.example:library-core:1.2.3", TransitiveManifestWriter.originFrom(root),
            "originFrom must return first stripped non-comment line");
    }

    @Test
    void rulesByPackage_groupsPackageAnnotationsAndSkipsNonPackageElements() {
        AILocked lockedAnn = annotation(AILocked.class, Map.of("reason", "critical lock"));
        AISecure secureAnn = annotation(AISecure.class, Map.of("aspect", "sanitization"));
        AIContext contextAnn = annotation(AIContext.class, Map.of("focus", "user api"));

        TaggedElement pkg1 = TaggedElement.builder("com.example.api")
            .kind(ElementTag.PACKAGE)
            .names("com.example.api", "api", "api", "com-example-api")
            .annotation(AILocked.class, lockedAnn)
            .annotation(AISecure.class, secureAnn)
            .build();

        TaggedElement pkg2 = packageElement("com.example.model", AIContext.class, contextAnn);

        // A class element (must be skipped by rulesByPackage)
        TaggedElement classEl = TaggedElement.builder("com.example.api.User")
            .kind(ElementTag.CLASS)
            .names("com.example.api.User", "User", "User", "com-example-api-User")
            .annotation(AILocked.class, lockedAnn)
            .build();

        // An unnamed package element (must be skipped by rulesByPackage)
        TaggedElement unnamedPkg = TaggedElement.builder("")
            .kind(ElementTag.PACKAGE)
            .names("", "", "", "")
            .annotation(AILocked.class, lockedAnn)
            .build();

        GuardrailModel model = GuardrailModel.builder()
            .add(AILocked.class, pkg1)
            .add(AISecure.class, pkg1)
            .add(AIContext.class, pkg2)
            .add(AILocked.class, classEl)
            .add(AILocked.class, unnamedPkg)
            .build();

        Map<String, List<TransitiveRule>> grouped = TransitiveManifestWriter.rulesByPackage(
            model, "com.example:library:1.0");

        assertEquals(2, grouped.size(), "only named package elements must produce rule lists");
        assertTrue(grouped.containsKey("com.example.api"), "api package must be present");
        assertTrue(grouped.containsKey("com.example.model"), "model package must be present");

        List<TransitiveRule> apiRules = grouped.get("com.example.api");
        assertEquals(2, apiRules.size(), "api package must carry both annotations");
        assertTrue(apiRules.stream().anyMatch(r -> r.annotation().equals("@AILocked")),
            "api package must carry @AILocked rule");
        assertTrue(apiRules.stream().anyMatch(r -> r.annotation().equals("@AISecure")),
            "api package must carry @AISecure rule");

        List<TransitiveRule> modelRules = grouped.get("com.example.model");
        assertEquals(1, modelRules.size(), "model package must carry one rule");
        assertEquals("@AIContext", modelRules.get(0).annotation());
    }

    @Test
    void rulesByPackage_elementWithoutAnnotationInstance_isSkipped() {
        TaggedElement missingAnnPkg = TaggedElement.builder("com.example.broken")
            .kind(ElementTag.PACKAGE)
            .names("com.example.broken", "broken", "broken", "com-example-broken")
            .build(); // no annotation attached

        GuardrailModel model = GuardrailModel.builder()
            .add(AILocked.class, missingAnnPkg)
            .build();

        Map<String, List<TransitiveRule>> grouped = TransitiveManifestWriter.rulesByPackage(
            model, "com.example:lib:1.0");
        assertTrue(grouped.isEmpty(), "element returning null for annotation must be skipped");
    }

    @Test
    void emit_writesManifestsAndLogsDebug() throws IOException {
        AILocked lockedAnn = annotation(AILocked.class, Map.of("reason", "safety boundary"));
        TaggedElement pkgB = packageElement("com.example.b", AILocked.class, lockedAnn);
        TaggedElement pkgA = packageElement("com.example.a", AILocked.class, lockedAnn);

        GuardrailModel model = GuardrailModel.builder()
            .add(AILocked.class, pkgB)
            .add(AILocked.class, pkgA)
            .build();

        Filer filer = mock(Filer.class);
        FileObject foA = mock(FileObject.class);
        FileObject foB = mock(FileObject.class);
        StringWriter swA = new StringWriter();
        StringWriter swB = new StringWriter();

        when(foA.openWriter()).thenReturn(swA);
        when(foB.openWriter()).thenReturn(swB);
        when(foA.toUri()).thenReturn(URI.create("file:///out/a.json"));
        when(foB.toUri()).thenReturn(URI.create("file:///out/b.json"));

        when(filer.createResource(eq(StandardLocation.CLASS_OUTPUT),
            eq(TransitiveManifest.RESOURCE_PACKAGE),
            eq(TransitiveManifest.resourceNameFor("com.example.a")))).thenReturn(foA);
        when(filer.createResource(eq(StandardLocation.CLASS_OUTPUT),
            eq(TransitiveManifest.RESOURCE_PACKAGE),
            eq(TransitiveManifest.resourceNameFor("com.example.b")))).thenReturn(foB);

        List<String> written = TransitiveManifestWriter.emit(filer, model, "com.example:lib:1.0", "1.3.7", logger);

        assertEquals(List.of("com.example.a", "com.example.b"), written,
            "written packages must be returned in sorted order");

        assertTrue(swA.toString().contains("com.example.a"), "manifest for package a must contain package name");
        assertTrue(swB.toString().contains("com.example.b"), "manifest for package b must contain package name");

        List<String> debugLogs = appender.list.stream()
            .filter(e -> e.getLevel() == Level.DEBUG)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertEquals(2, debugLogs.size(), "each written manifest must emit a debug log");
        assertTrue(debugLogs.get(0).contains("manifest.write package=com.example.a"),
            "debug log must name package a: " + debugLogs.get(0));
        assertTrue(debugLogs.get(0).contains("origin=com.example:lib:1.0"),
            "debug log must name origin: " + debugLogs.get(0));
    }

    @Test
    void emit_emptyOrigin_logsUnsetInDebug() throws IOException {
        AILocked lockedAnn = annotation(AILocked.class, Map.of("reason", "safety boundary"));
        TaggedElement pkg = packageElement("com.example.pkg", AILocked.class, lockedAnn);
        GuardrailModel model = GuardrailModel.builder().add(AILocked.class, pkg).build();

        Filer filer = mock(Filer.class);
        FileObject fo = mock(FileObject.class);
        when(fo.openWriter()).thenReturn(new StringWriter());
        when(fo.toUri()).thenReturn(URI.create("file:///out/pkg.json"));
        when(filer.createResource(any(), any(), any())).thenReturn(fo);

        TransitiveManifestWriter.emit(filer, model, "", "1.3.7", logger);

        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("origin=<unset>")),
            "empty origin must be logged as <unset>");
    }

    @Test
    void emit_emptyModel_writesNothing() throws IOException {
        GuardrailModel model = GuardrailModel.builder().build();
        Filer filer = mock(Filer.class);

        List<String> written = TransitiveManifestWriter.emit(filer, model, "com.example:lib:1.0", "1.3.7", null);
        assertTrue(written.isEmpty(), "model with no package guardrails must write nothing");
        verify(filer, never()).createResource(any(), any(), any());
    }

    @Test
    void emit_withNullLogger_succeedsQuietly() throws IOException {
        AILocked lockedAnn = annotation(AILocked.class, Map.of("reason", "safety boundary"));
        TaggedElement pkg = packageElement("com.example.pkg", AILocked.class, lockedAnn);
        GuardrailModel model = GuardrailModel.builder().add(AILocked.class, pkg).build();

        Filer filer = mock(Filer.class);
        FileObject fo = mock(FileObject.class);
        when(fo.openWriter()).thenReturn(new StringWriter());
        when(fo.toUri()).thenReturn(URI.create("file:///out/pkg.json"));
        when(filer.createResource(any(), any(), any())).thenReturn(fo);

        List<String> written = TransitiveManifestWriter.emit(filer, model, "origin", "1.3.7", null);
        assertEquals(List.of("com.example.pkg"), written, "null logger must not prevent emission");
    }

    @Test
    void emit_filerThrowsIOException_propagates() throws IOException {
        AILocked lockedAnn = annotation(AILocked.class, Map.of("reason", "safety boundary"));
        TaggedElement pkg = packageElement("com.example.pkg", AILocked.class, lockedAnn);
        GuardrailModel model = GuardrailModel.builder().add(AILocked.class, pkg).build();

        Filer filer = mock(Filer.class);
        when(filer.createResource(any(), any(), any())).thenThrow(new IOException("disk full"));

        assertThrows(IOException.class, () ->
            TransitiveManifestWriter.emit(filer, model, "origin", "1.3.7", null),
            "Filer IOException must propagate to caller");
    }
}
