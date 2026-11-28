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
import se.deversity.vibetags.processor.internal.content.Platform;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PartialMergeDiagnostics}: the warnings about a merged file that is missing, or points at,
 * a module's guardrails, each with its exact text and its log event.
 *
 * <p>The end-to-end cases in {@code ShortCircuitStateWarningTest} prove each warning is raised on
 * both the regenerating build and the rebuild, by fragment. These pin what a fragment cannot: the
 * whole sentence, the singular and plural forms, the cap on named duplicates, every early return,
 * and the {@code reason=} each event carries.
 */
@DisplayName("Partial-merge diagnostics")
class PartialMergeDiagnosticsTest {

    private static final long OLD = 1_000_000L;
    private static final long NEW = 2_000_000_000_000L;

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(PartialMergeDiagnosticsTest.class.getName() + "." + testInfo.getDisplayName());
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

    private void report(Path root, List<ModuleSidecar> sidecars, Set<String> stemsThisBuildWrites) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        Set<String> active = ServiceRegistry.resolveActiveServices(messager, serviceFiles);
        warnings.clear();
        new PartialMergeDiagnostics(root, messager, logger)
            .report(active, serviceFiles, sidecars, () -> stemsThisBuildWrites);
    }

    private void report(Path root, List<ModuleSidecar> sidecars) {
        report(root, sidecars, Set.of());
    }

    // ---------------------------------------------------------------- single module

    @Test
    @DisplayName("a healthy single module warns about nothing")
    void aHealthySingleModuleIsSilent(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar only = saved(root, "svc", Set.of("com-example-A"), OLD);

        report(root, List.of(only));

        assertEquals(List.of(), warnings);
        assertEquals(List.of(), events());
    }

    @Test
    @DisplayName("an unreadable sidecar is named, even beside a single module")
    void anUnreadableSidecarIsNamed(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar ghost = saved(root, "ghost", Set.of("com-example-ghost-Ghost"), OLD);
        Path file = root.resolve(ModuleSidecar.SIDECAR_PREFIX + "ghost");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        // Without its trailer the file reads as one a parallel build is still writing.
        Files.write(file, lines.stream().filter(l -> !"# end".equals(l.strip())).toList(), StandardCharsets.UTF_8);

        report(root, List.of(ghost));

        assertEquals(List.of("VibeTags: 1 module sidecar(s) could not be read this round, so"
            + " the modules they describe are missing from the generated files: .vibetags-mod-ghost"
            + ". A sidecar written by a newer VibeTags than this one (a mixed-version reactor)"
            + " or held open by a parallel build reads this way. Nothing was deleted; build"
            + " again with one version throughout and the regions come back."), warnings);
        assertEquals(List.of("sidecar.unreadable count=1 names=[.vibetags-mod-ghost]"), events());
    }

    @Test
    @DisplayName("an unusable mirror config is named with its reason, even beside a single module")
    void anUnusableMirrorConfigIsNamed(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("app-tests"));
        Files.writeString(root.resolve("app-tests/.vibetags-mirror"), "bad" + (char) 0 + "line\n",
            StandardCharsets.UTF_8);
        List<MirrorConfig.Unusable> unusable = MirrorConfig.unusable(root);
        assertEquals(1, unusable.size(), "precondition: the config is unusable");
        String reason = unusable.get(0).reason();

        report(root, List.of(saved(root, "svc", Set.of("com-example-A"), OLD)));

        assertEquals(List.of("VibeTags: app-tests/.vibetags-mirror is ignored: " + reason
            + ". Its module receives no mirrored rules until it is fixed."), warnings);
        assertEquals(List.of("mirror.skip file=app-tests/.vibetags-mirror reason=unusable-config detail=" + reason),
            events());
    }

    @Test
    @DisplayName("the reactor-only checks stay silent for one module, whatever it holds")
    void theReactorChecksNeedTwoSidecars(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar only = new ModuleSidecar("svc", "svc", "svc");
        only.putBody("claude", "body");
        only.setElementIds(Set.of("com-example-A"));
        only.setGranularStems(Set.of("com-example-A")); // would read as an opt-out window with a sibling
        only.save(root);
        Files.setLastModifiedTime(root.resolve(ModuleSidecar.SIDECAR_PREFIX + "svc"), FileTime.fromMillis(OLD));
        Files.createFile(root.resolve("llms.txt")); // opted into after the module compiled

        report(root, List.of(only));

        assertEquals(List.of(), warnings);
    }

    // ---------------------------------------------------------------- opted in after a module compiled

    @Test
    @DisplayName("a platform opted into after one module compiled names it in the singular")
    void anOptInAfterOneModuleCompiled(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("llms.txt"));
        Files.setLastModifiedTime(root.resolve("llms.txt"), FileTime.fromMillis(NEW));
        ModuleSidecar a = saved(root, "module-a", Set.of("com-example-A"), OLD);
        ModuleSidecar b = saved(root, "module-b", Set.of("com-example-B"), OLD);
        b.putBody("llms", "b's llms body");
        b.save(root);
        Files.setLastModifiedTime(root.resolve(ModuleSidecar.SIDECAR_PREFIX + "module-b"), FileTime.fromMillis(NEW));

        report(root, List.of(a, b));

        assertEquals(List.of("VibeTags: llms.txt was opted into after module-a last compiled, so its"
            + " guardrails are missing from it. Run a full build to complete the file."), warnings);
        assertEquals(List.of("merge.partial service=llms modulesBehind=[module-a] reason=opted-in-after-last-compile"),
            events());
    }

    @Test
    @DisplayName("a platform opted into after several modules compiled names them in the plural")
    void anOptInAfterSeveralModulesCompiled(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("llms.txt"));
        Files.setLastModifiedTime(root.resolve("llms.txt"), FileTime.fromMillis(NEW));
        ModuleSidecar b = saved(root, "module-b", Set.of("com-example-B"), OLD);
        ModuleSidecar a = saved(root, "module-a", Set.of("com-example-A"), OLD);

        report(root, List.of(b, a));

        assertEquals(List.of("VibeTags: llms.txt was opted into after module-a, module-b last compiled, so"
            + " their guardrails are missing from it. Run a full build to complete the file."), warnings);
    }

    @Test
    @DisplayName("TESTING.md never reads as opted into late: a main round renders nothing for it")
    void testingMdIsNeverLate(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Path testing = root.resolve("TESTING.md");
        Files.createFile(testing);
        Files.setLastModifiedTime(testing, FileTime.fromMillis(NEW));
        ModuleSidecar a = saved(root, "module-a", Set.of("com-example-A"), OLD);
        ModuleSidecar b = saved(root, "module-b", Set.of("com-example-B"), OLD);
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        assertEquals(testing, serviceFiles.get(Platform.TESTING.getServiceKey()),
            "precondition: TESTING.md is the testing service's file");

        report(root, List.of(a, b));

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("modules that compiled after the opt-in are current, so nothing is said")
    void modulesNewerThanTheOptInAreCurrent(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("llms.txt"));
        Files.setLastModifiedTime(root.resolve("llms.txt"), FileTime.fromMillis(OLD));
        ModuleSidecar a = saved(root, "module-a", Set.of("com-example-A"), NEW);
        ModuleSidecar b = saved(root, "module-b", Set.of("com-example-B"), NEW);

        report(root, List.of(a, b));

        assertEquals(List.of(), warnings);
    }

    // ---------------------------------------------------------------- granular directory opted out

    @Test
    @DisplayName("a granular opt-out one module has not seen names it in the singular")
    void aGranularOptOutOneModuleHasNotSeen(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar a = saved(root, "module-a", Set.of("com-example-A"), OLD);
        ModuleSidecar b = withStems(root, "module-b", Set.of("com-example-B"));

        report(root, List.of(a, b));

        assertEquals(List.of("VibeTags: the granular rules directory was opted out after module-b last"
            + " compiled, so its region still points at rule files that no longer exist. Run a full"
            + " build to complete the file."), warnings);
        assertEquals(List.of("merge.partial modulesBehind=[module-b] reason=granular-opted-out-after-last-compile"),
            events());
    }

    @Test
    @DisplayName("a granular opt-out several modules have not seen names them in the plural, sorted")
    void aGranularOptOutSeveralModulesHaveNotSeen(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar b = withStems(root, "module-b", Set.of("com-example-B"));
        ModuleSidecar a = withStems(root, "module-a", Set.of("com-example-A"));

        report(root, List.of(b, a));

        assertEquals(List.of("VibeTags: the granular rules directory was opted out after module-a, module-b"
            + " last compiled, so their regions still point at rule files that no longer exist. Run a"
            + " full build to complete the file."), warnings);
    }

    // ---------------------------------------------------------------- a sibling's rule file is missing

    @Test
    @DisplayName("a sibling's rule file that is not on disk is named")
    void aSiblingsMissingRuleFileIsNamed(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ModuleSidecar a = withStems(root, "module-a", Set.of("com-example-a-Alpha"));
        ModuleSidecar b = withStems(root, "module-b", Set.of("com-example-b-Beta"));
        Files.createFile(root.resolve(".claude/rules/com-example-a-Alpha.md"));

        report(root, List.of(a, b));

        assertEquals(List.of("VibeTags: the generated files name module-b's com-example-b-Beta"
            + " as the authoritative rule file(s) for those elements, but they are not on disk"
            + " and this build does not write another module's files. Their guardrails are"
            + " stated nowhere until that module recompiles. Run a full build to restore them."), warnings);
        assertEquals(List.of("granular.missing entries=[module-b's com-example-b-Beta] reason=sibling-file-absent"),
            events());
    }

    @Test
    @DisplayName("several missing rule files are named once each, sorted")
    void severalMissingRuleFilesAreNamedOnceEachSorted(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createDirectories(root.resolve(".cursor/rules"));
        ModuleSidecar b = withStems(root, "module-b", new LinkedHashSet<>(List.of("com-example-b-Zeta", "com-example-b-Beta")));
        ModuleSidecar a = withStems(root, "module-a", Set.of("com-example-a-Alpha"));

        report(root, List.of(b, a));

        assertEquals(1, warnings.size(), "one warning however many directories: " + warnings);
        assertTrue(warnings.get(0).startsWith("VibeTags: the generated files name module-a's com-example-a-Alpha,"
                + " module-b's com-example-b-Beta, module-b's com-example-b-Zeta as the authoritative"),
            "each missing stem once, in sorted order, though two directories lack it: " + warnings);
    }

    @Test
    @DisplayName("a rule file on disk in another case is not missing (case-insensitive filesystems)")
    void aRuleFileInAnotherCaseIsPresent(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ModuleSidecar a = withStems(root, "module-a", Set.of("com-example-a-Alpha"));
        ModuleSidecar b = withStems(root, "module-b", Set.of("com-example-b-Beta"));
        Files.createFile(root.resolve(".claude/rules/com-example-a-Alpha.md"));
        Files.createFile(root.resolve(".claude/rules/COM-example-b-beta.md"));

        report(root, List.of(a, b));

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a stem this build is about to write is not missing, in any case")
    void aStemThisBuildWritesIsNotMissing(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ModuleSidecar a = withStems(root, "module-a", Set.of("com-example-a-Alpha"));
        ModuleSidecar b = withStems(root, "module-b", Set.of("com-example-b-Beta"));

        report(root, List.of(a, b), Set.of("com-example-a-Alpha", "com-example-B-BETA"));

        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a stem a module writes into its own directory is not looked for at the root (#443)")
    void aModuleScopedStemIsNotLookedForAtTheRoot(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ModuleSidecar a = withStems(root, "module-a", Set.of());
        ModuleSidecar b = new ModuleSidecar("module-b", "module-b", "module-b");
        b.putBody("claude", "b body");
        b.setElementIds(Set.of("com-example-b-Beta"));
        b.putModuleGranularContribution("com-example-b-Beta",
            new se.deversity.vibetags.processor.internal.content.GranularContribution(List.of("**/Beta.java"), "beta rule"));
        b.setGranularStems(Set.of("com-example-b-Beta"));
        b.save(root);

        report(root, List.of(a, b));

        assertEquals(List.of(), warnings);
    }

    // ---------------------------------------------------------------- one element, two regions

    @Test
    @DisplayName("one element claimed by two regions is named in the singular, with the sidecars to delete")
    void oneElementClaimedTwice(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        ModuleSidecar stale = saved(root, "_root_", Set.of("com-example-svc-AuthUtil", "com-example-svc-Departed"), OLD);
        ModuleSidecar live = saved(root, "svc", Set.of("com-example-svc-AuthUtil"), NEW);

        report(root, List.of(stale, live));

        assertEquals(List.of("VibeTags: com-example-svc-AuthUtil (_root_ and svc) claim the same annotated"
            + " element under more than one module, so every generated file states that guardrail"
            + " twice. An element belongs to exactly one module, so one of these regions is a"
            + " leftover from an earlier layout. Delete the sidecar that no longer describes a"
            + " module you build: .vibetags-mod-_root_, .vibetags-mod-svc."), warnings);
        assertEquals(List.of("region.duplicate elements=1 regions=[_root_, svc] reason=element-claimed-twice"),
            events());
    }

    @Test
    @DisplayName("more than five duplicated elements are summarised after the first five")
    void manyDuplicatesAreCapped(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Set<String> seven = new LinkedHashSet<>(List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7"));
        ModuleSidecar a = saved(root, "module-a", seven, OLD);
        ModuleSidecar b = saved(root, "module-b", seven, NEW);

        report(root, List.of(a, b));

        assertEquals(1, warnings.size(), String.valueOf(warnings));
        String warning = warnings.get(0);
        assertTrue(warning.contains(" (module-a and module-b) and 2 more claim the same annotated elements"
                + " under more than one module, so every generated file states those guardrails twice."),
            "five named, the rest counted, plural wording: " + warning);
        assertEquals(5, warning.split("\\(module-a and module-b\\)", -1).length - 1,
            "exactly five elements named: " + warning);
        assertEquals(List.of("region.duplicate elements=7 regions=[module-a, module-b] reason=element-claimed-twice"),
            events());
    }

    // ---------------------------------------------------------------- fixtures

    /** A module sidecar with a CLAUDE.md body and the given elements, saved with mtime {@code at}. */
    private static ModuleSidecar saved(Path root, String id, Set<String> elements, long at) throws IOException {
        ModuleSidecar sidecar = new ModuleSidecar(id, "_root_".equals(id) ? "" : id, id);
        sidecar.putBody("claude", id + " body");
        sidecar.setElementIds(elements);
        sidecar.save(root);
        Files.setLastModifiedTime(root.resolve(ModuleSidecar.SIDECAR_PREFIX + id), FileTime.fromMillis(at));
        return sidecar;
    }

    /** A module sidecar that recorded the given root granular stems, as a collapsed region does. */
    private static ModuleSidecar withStems(Path root, String id, Set<String> stems) throws IOException {
        ModuleSidecar sidecar = new ModuleSidecar(id, id, id);
        sidecar.putBody("claude", id + " body");
        sidecar.setElementIds(Set.of("element-of-" + id));
        sidecar.setGranularStems(stems);
        sidecar.save(root);
        return sidecar;
    }
}
