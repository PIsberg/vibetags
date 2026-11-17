package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuardrailEnforcer#enforce} called directly: every branch, with the exact diagnostics, the
 * {@code enforce.*} log events and the baseline file it leaves behind.
 *
 * <p>{@code EnforcingModeEndToEndTest} proves the gate through javac, one scenario per compile. This
 * pins what that test reads only loosely: the return value (the number of violations, which decides
 * whether a consumer's build fails), the diagnostic kind and wording a developer acts on, the log
 * events docs/LOGGING.md treats as contracts, and which inputs reach the baseline. Written before
 * {@code enforce} was split into smaller methods, so the split can be checked against it.
 */
class GuardrailEnforcerTest {

    private static final String MODULE = "core";
    private static final String BASELINE = EnforcementBaseline.FILE_NAME;
    /** The em dash the enforcer's messages use, spelled by code point. */
    private static final String DASH = String.valueOf((char) 0x2014);
    private static final String HOW_TO_APPROVE = "If the change is intended, run once with"
        + " -Avibetags.baseline.update=true and commit the new baseline, so the change is reviewed rather"
        + " than assumed.";

    @TempDir
    Path root;

    private final List<String> diagnostics = new ArrayList<>();
    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog(TestInfo testInfo) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(GuardrailEnforcerTest.class.getName() + "." + testInfo.getDisplayName());
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
            diagnostics.add(kind + ": " + msg);
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

    // ------------------------------------------------------------------ fixtures

    private GuardrailEnforcer enforcer() {
        return new GuardrailEnforcer(messager, logger);
    }

    private static TaggedElement method(String path, String signature) {
        return TaggedElement.builder(path).kind(ElementTag.METHOD).signature(signature).build();
    }

    private static TaggedElement constructor(String path, String signature) {
        return TaggedElement.builder(path).kind(ElementTag.CONSTRUCTOR).signature(signature).build();
    }

    private static TaggedElement aPackage(String path) {
        return TaggedElement.builder(path).kind(ElementTag.PACKAGE).build();
    }

    private static final class Model {
        private final GuardrailModelBuilder builder = new GuardrailModelBuilder();

        Model with(Class<? extends Annotation> type, TaggedElement element) {
            builder.add(type, element);
            return this;
        }

        GuardrailModel build() {
            return builder.build();
        }
    }

    private static Model model() {
        return new Model();
    }

    private static GuardrailModel locked(TaggedElement... elements) {
        Model m = model();
        for (TaggedElement e : elements) {
            m.with(AILocked.class, e);
        }
        return m.build();
    }

    /** The families as the processor builds them: parsed from an -Avibetags.enforce value. */
    private Set<GuardrailEnforcer.Family> families(String... names) {
        return enforcer().parseFamilies(String.join(",", names));
    }

    /** Records a baseline for {@link #MODULE} and forgets what the recording printed. */
    private void record(GuardrailModel model, Set<GuardrailEnforcer.Family> families) {
        record(model, families, MODULE);
    }

    private void record(GuardrailModel model, Set<GuardrailEnforcer.Family> families, String moduleId) {
        enforcer().enforce(model, families, root, moduleId, true);
        diagnostics.clear();
        appender.list.clear();
    }

    private int check(GuardrailModel model, Set<GuardrailEnforcer.Family> families) {
        return enforcer().enforce(model, families, root, MODULE, false);
    }

    private List<String> logLines() {
        return appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
    }

    private List<String> diagnosticsOfKind(Diagnostic.Kind kind) {
        return diagnostics.stream().filter(d -> d.startsWith(kind + ": ")).toList();
    }

    private String baselineText() throws IOException {
        return Files.readString(root.resolve(BASELINE), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ nothing asked for

    @Test
    void withNoFamiliesItDoesNothingAtAll() {
        GuardrailModel m = locked(method("a.B#m()", "void m()"));

        assertEquals(0, enforcer().enforce(m, Set.of(), root, MODULE, false));
        assertEquals(0, enforcer().enforce(m, Set.of(), root, MODULE, true));
        assertEquals(List.of(), diagnostics);
        assertEquals(List.of(), logLines());
        assertFalse(Files.exists(root.resolve(BASELINE)), "no family means no baseline is ever written");
    }

    // ------------------------------------------------------------------ recording

    @Test
    void recordingWritesEveryGuardedElementAndSaysHowMany() throws IOException {
        GuardrailModel m = model()
            .with(AILocked.class, method("a.B#lock()", "void lock()"))
            .with(AIContract.class, method("a.B#pay(String)", "void pay(String)"))
            .build();

        assertEquals(0, enforcer().enforce(m, families("locked", "contract"), root, MODULE, true));

        assertEquals(List.of("NOTE: VibeTags: enforcement baseline updated for module 'core' (2 guarded"
            + " element(s)). Commit " + BASELINE + "."), diagnostics);
        assertEquals(List.of("INFO enforce.baseline.update module=core elements=2"), logLines());
        String text = baselineText();
        assertTrue(text.contains("core\tlocked\ta.B#lock()\tvoid lock()"), text);
        assertTrue(text.contains("core\tcontract\ta.B#pay(String)\tvoid pay(String)"), text);
    }

    @Test
    void recordingSkipsElementsWithNoProvableShape() throws IOException {
        enforcer().enforce(locked(method("a.B#lock()", "void lock()"), aPackage("a")),
            families("locked"), root, MODULE, true);

        assertTrue(diagnostics.get(0).contains("(1 guarded element(s))"), diagnostics.toString());
        assertFalse(baselineText().contains("core\tlocked\ta\t"), "a package has no signature to approve");
    }

    @Test
    void recordingOnlyCoversTheFamiliesAskedFor() throws IOException {
        record(model()
            .with(AILocked.class, method("a.B#lock()", "void lock()"))
            .with(AIContract.class, method("a.B#pay(String)", "void pay(String)"))
            .build(), families("locked"));

        String text = baselineText();
        assertTrue(text.contains("core\tlocked\ta.B#lock()"), text);
        assertFalse(text.contains("\tcontract\t"), "an unselected family must not be approved: " + text);
    }

    @Test
    void recordingOneFamilyKeepsTheOtherFamiliesApproved() {
        TaggedElement pay = method("a.B#pay(String)", "void pay(String)");
        record(model().with(AILocked.class, method("a.B#lock()", "void lock()")).with(AIContract.class, pay).build(),
            families("locked", "contract"));
        record(model().with(AILocked.class, method("a.B#lock()", "int lock()")).with(AIContract.class, pay).build(),
            families("locked"));

        assertEquals(0, check(model().with(AIContract.class, pay).build(), families("contract")),
            "re-recording locked must not drop contract's approval: " + diagnostics);
    }

    @Test
    void recordingOneModuleLeavesAnotherModulesApprovals() {
        GuardrailModel core = locked(method("a.B#lock()", "void lock()"));
        record(core, families("locked"));
        record(locked(method("x.Y#api()", "void api()")), families("locked"), "api");

        assertEquals(0, check(core, families("locked")), diagnostics.toString());
    }

    // ------------------------------------------------------------------ checking: passes

    @Test
    void anUnchangedCompilationPassesWithOneNote() {
        GuardrailModel m = model()
            .with(AILocked.class, method("a.B#lock()", "void lock()"))
            .with(AIContract.class, method("a.B#pay(String)", "void pay(String)"))
            .build();
        record(m, families("locked", "contract"));

        assertEquals(0, check(m, families("locked", "contract")));

        assertEquals(List.of("NOTE: VibeTags: enforcement passed " + DASH + " 2 guarded element(s) unchanged"
            + " (contract, locked)."), diagnostics);
        assertEquals(List.of("INFO enforce.result module=core families=[locked, contract] checked=2 violations=0"),
            logLines());
    }

    @Test
    void aNewlyGuardedElementIsUnapprovedRatherThanAViolation() {
        record(locked(method("a.B#lock()", "void lock()")), families("locked"));

        assertEquals(0, check(locked(method("a.B#lock()", "void lock()"), method("a.B#fresh()", "void fresh()")),
            families("locked")));
        assertTrue(diagnostics.get(0).contains("2 guarded element(s) unchanged"), diagnostics.toString());
    }

    @Test
    void anElementWithoutASignatureIsNeitherCheckedNorCounted() {
        record(locked(method("a.B#lock()", "void lock()")), families("locked"));

        assertEquals(0, check(locked(method("a.B#lock()", "void lock()"), aPackage("a")), families("locked")));
        assertTrue(logLines().get(0).contains("checked=1"), logLines().toString());
    }

    @Test
    void aMissingElementOfAnUncheckedFamilyIsNotReported() {
        record(model()
            .with(AILocked.class, method("a.B#lock()", "void lock()"))
            .with(AIContract.class, method("a.B#pay(String)", "void pay(String)"))
            .build(), families("locked", "contract"));

        assertEquals(0, check(locked(method("a.B#lock()", "void lock()")), families("locked")),
            "contract was not asked for, so its missing element is not checked: " + diagnostics);
    }

    // ------------------------------------------------------------------ checking: violations

    @Test
    void aChangedShapeIsOneViolationNamingBothShapes() {
        record(locked(method("a.B#lock()", "void lock()")), families("locked"));

        assertEquals(1, check(locked(method("a.B#lock()", "int lock()")), families("locked")));

        assertEquals(List.of("ERROR: VibeTags: @AILocked violation " + DASH + " the shape of a.B#lock() changed"
            + " from what " + BASELINE + " approved.\n  approved: void lock()\n  now:      int lock()\n"
            + HOW_TO_APPROVE), diagnostics);
        assertEquals(List.of(
            "ERROR enforce.violation family=locked element=a.B#lock() approved=void lock() actual=int lock()",
            "INFO enforce.result module=core families=[locked] checked=1 violations=1"), logLines());
    }

    @Test
    void aVanishedElementIsOneViolationNamingThePath() {
        record(model().with(AIContract.class, method("a.B#pay(String)", "void pay(String)")).build(),
            families("contract"));

        // A changed parameter type moves the path, so the approved entry is abandoned, not edited.
        assertEquals(1, check(model().with(AIContract.class, method("a.B#pay(long)", "void pay(long)")).build(),
            families("contract")));

        assertEquals(List.of("ERROR: VibeTags: @AIContract violation " + DASH + " a.B#pay(String) was approved in "
            + BASELINE + " but this compilation has no such guarded element. It was renamed, deleted, had its"
            + " signature changed, or lost its annotation.\n" + HOW_TO_APPROVE), diagnostics);
        assertEquals(List.of(
            "ERROR enforce.violation family=contract element=a.B#pay(String) reason=absent",
            "INFO enforce.result module=core families=[contract] checked=1 violations=1"), logLines());
    }

    @Test
    void changedAndVanishedAreCountedTogetherChangedFirst() {
        record(locked(method("a.B#lock()", "void lock()"), method("a.B#gone()", "void gone()")), families("locked"));

        assertEquals(2, check(locked(method("a.B#lock()", "long lock()")), families("locked")));

        List<String> errors = diagnosticsOfKind(Diagnostic.Kind.ERROR);
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("the shape of a.B#lock() changed"), errors.get(0));
        assertTrue(errors.get(1).contains("a.B#gone() was approved"), errors.get(1));
        assertEquals(List.of(), diagnosticsOfKind(Diagnostic.Kind.NOTE), "no 'passed' note on a failing check");
    }

    @Test
    void everyChangedElementIsReported() {
        record(locked(method("a.B#one()", "void one()"), method("a.B#two()", "void two()"),
            method("a.B#three()", "void three()")), families("locked"));

        assertEquals(3, check(locked(method("a.B#one()", "int one()"), method("a.B#two()", "int two()"),
            method("a.B#three()", "int three()")), families("locked")));
        assertEquals(3, diagnosticsOfKind(Diagnostic.Kind.ERROR).size());
    }

    @Test
    void thePublicApiFamilyIsReportedUnderItsAnnotationName() {
        record(model().with(AIPublicAPI.class, method("a.B#api()", "void api()")).build(), families("publicapi"));

        assertEquals(1, check(model().with(AIPublicAPI.class, method("a.B#api()", "String api()")).build(),
            families("publicapi")));
        assertTrue(diagnostics.get(0).startsWith("ERROR: VibeTags: @AIPublicAPI violation"), diagnostics.get(0));
    }

    @Test
    void aConstructorAndAMethodWithTheSamePathAreSeparateEntries() {
        record(locked(constructor("a.B#B(String)", "public B(String)"),
            method("a.B#B(String)", "public void B(String)")), families("locked"));

        assertEquals(1, check(locked(constructor("a.B#B(String)", "protected B(String)"),
            method("a.B#B(String)", "public void B(String)")), families("locked")), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("approved: public B(String)"), diagnostics.get(0));
    }

    // ------------------------------------------------------------------ order and overlap

    @Test
    void changedElementsAreReportedFamilyByFamilyInNameOrderThenInModelOrder() {
        record(model()
            .with(AILocked.class, method("a.B#l2()", "void l2()"))
            .with(AILocked.class, method("a.B#l1()", "void l1()"))
            .with(AIContract.class, method("a.B#c2()", "void c2()"))
            .with(AIContract.class, method("a.B#c1()", "void c1()"))
            .build(), families("locked", "contract"));

        assertEquals(4, check(model()
            .with(AILocked.class, method("a.B#l2()", "int l2()"))
            .with(AILocked.class, method("a.B#l1()", "int l1()"))
            .with(AIContract.class, method("a.B#c2()", "int c2()"))
            .with(AIContract.class, method("a.B#c1()", "int c1()"))
            .build(), families("locked", "contract")));

        List<String> named = diagnosticsOfKind(Diagnostic.Kind.ERROR).stream()
            .map(d -> d.substring(d.indexOf("the shape of ") + "the shape of ".length(), d.indexOf(" changed")))
            .toList();
        assertEquals(List.of("a.B#c1()", "a.B#c2()", "a.B#l1()", "a.B#l2()"), named,
            "contract before locked (family name order), and within a family the model's path order");
    }

    @Test
    void vanishedElementsAreReportedInTheBaselinesOrder() {
        record(locked(method("a.B#z()", "void z()"), method("a.B#m()", "void m()"), method("a.B#a()", "void a()")),
            families("locked"));

        assertEquals(3, check(locked(), families("locked")));
        List<String> named = diagnosticsOfKind(Diagnostic.Kind.ERROR).stream()
            .map(d -> d.substring(d.indexOf(DASH + " ") + 2, d.indexOf(" was approved")))
            .toList();
        assertEquals(List.of("a.B#a()", "a.B#m()", "a.B#z()"), named);
    }

    @Test
    void anElementInTwoFamiliesIsRecordedCheckedAndCountedUnderBoth() throws IOException {
        TaggedElement both = method("a.B#pay(String)", "void pay(String)");
        record(model().with(AILocked.class, both).with(AIContract.class, both).build(), families("locked", "contract"));

        String text = baselineText();
        assertTrue(text.contains("core\tlocked\ta.B#pay(String)\tvoid pay(String)"), text);
        assertTrue(text.contains("core\tcontract\ta.B#pay(String)\tvoid pay(String)"), text);

        assertEquals(0, check(model().with(AILocked.class, both).with(AIContract.class, both).build(),
            families("locked", "contract")));
        assertTrue(diagnostics.get(0).contains("2 guarded element(s) unchanged"), diagnostics.toString());

        diagnostics.clear();
        TaggedElement changed = method("a.B#pay(String)", "int pay(String)");
        assertEquals(2, check(model().with(AILocked.class, changed).with(AIContract.class, changed).build(),
            families("locked", "contract")), "one change, two families, two violations");
        List<String> errors = diagnosticsOfKind(Diagnostic.Kind.ERROR);
        assertTrue(errors.get(0).startsWith("ERROR: VibeTags: @AIContract violation"), errors.get(0));
        assertTrue(errors.get(1).startsWith("ERROR: VibeTags: @AILocked violation"), errors.get(1));
    }

    @Test
    void aChangedConstructorIsNamedByItsOwnPathNotItsBaselineKey() {
        record(locked(constructor("a.B#B(String)", "public B(String)")), families("locked"));

        assertEquals(1, check(locked(constructor("a.B#B(String)", "B(String)")), families("locked")));
        String error = diagnostics.get(0);
        assertTrue(error.contains("the shape of a.B#B(String) changed"), error);
        assertTrue(logLines().get(0).contains("element=a.B#B(String) approved=public B(String) actual=B(String)"),
            logLines().get(0));
    }

    @Test
    void theBaselineRecordsEachElementsOwnSignature() throws IOException {
        record(locked(method("a.B#one()", "sig-one"), method("a.B#two()", "sig-two"),
            constructor("a.B#B()", "sig-ctor")), families("locked"));

        List<String> lines = baselineText().lines().filter(l -> !l.startsWith("#")).toList();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\ta.B#one()\tsig-one")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\ta.B#two()\tsig-two")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("\tlocked\t") && l.endsWith("\tsig-ctor")
            && !l.contains("\ta.B#B()\tsig-ctor")), "a constructor is stored under its constructor key: " + lines);
    }

    // ------------------------------------------------------------------ nothing recorded

    @Test
    void withNoBaselineFileItWarnsAndPasses() {
        assertEquals(0, check(locked(method("a.B#lock()", "void lock()")), families("locked")));

        assertEquals(List.of("WARNING: VibeTags: -Avibetags.enforce is on but " + BASELINE + " records nothing"
            + " for module 'core'. Nothing was checked. Run once with -Avibetags.baseline.update=true and commit"
            + " the result."), diagnostics);
        assertEquals(List.of(), logLines(), "no enforce.result: nothing was checked");
    }

    @Test
    void aBaselineForAnotherModuleOnlyIsNothingRecordedForThisOne() {
        record(locked(method("x.Y#api()", "void api()")), families("locked"), "api");

        assertEquals(0, check(locked(method("a.B#lock()", "int lock()")), families("locked")));
        assertEquals(1, diagnosticsOfKind(Diagnostic.Kind.WARNING).size(), diagnostics.toString());
    }

    @Test
    void aBaselineWithoutTheFormatHeaderCountsAsNotRecorded() throws IOException {
        Files.writeString(root.resolve(BASELINE), "core\tlocked\ta.B#lock()\tvoid lock()\n", StandardCharsets.UTF_8);

        assertEquals(0, check(locked(method("a.B#lock()", "int lock()")), families("locked")),
            "an unrecognised format is treated as absent, as EnforcementBaseline documents");
        assertEquals(1, diagnosticsOfKind(Diagnostic.Kind.WARNING).size(), diagnostics.toString());
    }

    // ------------------------------------------------------------------ unreadable and unwritable

    private void writeUndecodableBaseline() throws IOException {
        // A Cp1252 byte from a Windows editor: present, but not UTF-8.
        Files.write(root.resolve(BASELINE), new byte[]{'#', ' ', (byte) 0xE9, '\n'});
    }

    @Test
    void anUnreadableBaselineFailsTheCheckRatherThanPassingIt() throws IOException {
        writeUndecodableBaseline();

        assertEquals(1, check(locked(method("a.B#lock()", "void lock()")), families("locked")));

        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String error = diagnostics.get(0);
        assertTrue(error.startsWith("ERROR: VibeTags: could not read " + BASELINE + " ("), error);
        assertTrue(error.endsWith("); enforcement did not run. Fix the file, or delete it and record it again"
            + " with -Avibetags.baseline.update=true."), error);
        assertEquals(1, logLines().size(), logLines().toString());
        assertTrue(logLines().get(0).startsWith("ERROR enforce.skip module=core reason=baseline-unreadable error="),
            logLines().get(0));
    }

    @Test
    void anUnreadableBaselineIsNotOverwrittenByARecordingRun() throws IOException {
        writeUndecodableBaseline();
        byte[] before = Files.readAllBytes(root.resolve(BASELINE));

        assertEquals(1, enforcer().enforce(locked(method("a.B#lock()", "void lock()")),
            families("locked"), root, MODULE, true), "the read comes first, even when recording");

        assertTrue(diagnostics.get(0).startsWith("ERROR: VibeTags: could not read"), diagnostics.toString());
        assertEquals(new String(before, StandardCharsets.ISO_8859_1),
            new String(Files.readAllBytes(root.resolve(BASELINE)), StandardCharsets.ISO_8859_1),
            "a file this cannot read may hold approvals for other modules; it must not be replaced");
    }

    @Test
    void aBaselineThatCannotBeWrittenIsAnErrorButNotAViolation() throws IOException {
        // A non-empty directory where the file should go: nothing to read, and nothing can replace it.
        Files.createDirectories(root.resolve(BASELINE).resolve("occupied"));

        assertEquals(0, enforcer().enforce(locked(method("a.B#lock()", "void lock()")),
            families("locked"), root, MODULE, true));

        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).startsWith("ERROR: VibeTags: could not write " + BASELINE + ": "),
            diagnostics.get(0));
        assertEquals(List.of(), logLines(), "no enforce.baseline.update for a write that failed");
    }

    // ------------------------------------------------------------------ no logger

    @Test
    void runsWithoutALogger() {
        GuardrailEnforcer quiet = new GuardrailEnforcer(messager, null);
        GuardrailModel m = locked(method("a.B#lock()", "void lock()"));
        assertEquals(0, quiet.enforce(m, families("locked"), root, MODULE, true));
        assertEquals(1, quiet.enforce(locked(method("a.B#lock()", "int lock()")), families("locked"), root, MODULE, false));
        assertEquals(0, quiet.enforce(m, families("locked"), root, MODULE, false));
    }
}
