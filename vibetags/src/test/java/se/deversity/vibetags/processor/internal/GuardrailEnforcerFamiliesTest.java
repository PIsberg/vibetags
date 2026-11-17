package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enforceable guardrail families: how {@code -Avibetags.enforce} is parsed into them, which
 * annotation each one guards, and how a family set is rendered in what the enforcer prints.
 *
 * <p>Families are compared here by name ({@link String#valueOf} of each member), so these
 * assertions state the observable contract whatever type carries a family: the option a user writes,
 * the names in the warning, the {@code families=} log field, the sorted list in the "passed" note,
 * and the family column of {@code .vibetags-baseline}. Written before the family strings became an
 * enum, so that change can be checked against them.
 */
class GuardrailEnforcerFamiliesTest {

    private static final String DASH = String.valueOf((char) 0x2014);

    @TempDir
    Path root;

    private final List<String> diagnostics = new ArrayList<>();

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

    private GuardrailEnforcer enforcer() {
        return new GuardrailEnforcer(messager, null);
    }

    /** The family names in iteration order, whatever type the set holds. */
    private static List<String> names(Set<?> families) {
        return families.stream().map(String::valueOf).toList();
    }

    private static List<String> sortedNames(Set<?> families) {
        return names(families).stream().sorted().toList();
    }

    private List<String> parse(String option) {
        return names(enforcer().parseFamilies(option));
    }

    private static String unknownFamilyWarning(String family) {
        return "WARNING: VibeTags: -Avibetags.enforce names an unknown guardrail family '" + family
            + "'. Enforceable families are contract, locked, publicapi (or 'all'). Families whose meaning"
            + " cannot be proved statically " + DASH + " @AICallersOnly, @AIStrictClasspath, @AIThreadSafe,"
            + " @AITestDriven " + DASH + " stay advisory and are not enforceable.";
    }

    // ------------------------------------------------------------------ parsing: off

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", ",", " , ,, "})
    void anAbsentBlankOrEmptyOptionSelectsNothingAndSaysNothing(String option) {
        assertEquals(List.of(), parse(option));
        assertEquals(List.of(), diagnostics);
    }

    // ------------------------------------------------------------------ parsing: names

    @ParameterizedTest
    @ValueSource(strings = {"locked", "contract", "publicapi"})
    void eachFamilyIsSelectedByItsName(String family) {
        assertEquals(List.of(family), parse(family));
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void theOrderTheUserWroteIsKept() {
        assertEquals(List.of("contract", "locked"), parse("contract,locked"));
        assertEquals(List.of("publicapi", "contract", "locked"), parse("publicapi,contract,locked"));
        assertEquals(List.of("locked", "publicapi"), parse("locked,publicapi"));
    }

    @Test
    void namesAreTrimmedAndCaseInsensitive() {
        assertEquals(List.of("locked", "contract"), parse(" LOCKED , Contract\t"));
        assertEquals(List.of("publicapi"), parse("PublicAPI"));
    }

    @Test
    void emptyEntriesAreSkippedWhereverTheyAppear() {
        assertEquals(List.of("contract"), parse("contract,"));
        assertEquals(List.of("contract", "locked"), parse(",contract,,locked,"));
        assertEquals(List.of(), diagnostics, "an empty entry is not an unknown family");
    }

    @Test
    void aRepeatedFamilyIsSelectedOnce() {
        assertEquals(List.of("locked"), parse("locked,locked,LOCKED"));
    }

    // ------------------------------------------------------------------ parsing: all

    @ParameterizedTest
    @ValueSource(strings = {"all", "ALL", " All ", "all,", ",all"})
    void allSelectsEveryEnforceableFamily(String option) {
        assertEquals(List.of("contract", "locked", "publicapi"), sortedNames(enforcer().parseFamilies(option)));
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void theAllConstantIsWhatTheOptionAccepts() {
        assertEquals("all", GuardrailEnforcer.ALL);
        assertEquals(3, enforcer().parseFamilies(GuardrailEnforcer.ALL).size());
    }

    @Test
    void allTogetherWithANamedFamilyHasNoDuplicates() {
        assertEquals(3, enforcer().parseFamilies("locked,all").size());
        assertEquals(3, enforcer().parseFamilies("all,contract,all").size());
        assertEquals(List.of("locked"), parse("locked,all").subList(0, 1), "a named family keeps its place first");
    }

    // ------------------------------------------------------------------ parsing: unknown names

    @ParameterizedTest
    @ValueSource(strings = {"callersonly", "strictclasspath", "threadsafe", "testdriven", "lock", "public-api", "*"})
    void anUnknownFamilyIsWarnedAboutAndNotSelected(String family) {
        assertEquals(List.of(), parse(family));
        assertEquals(List.of(unknownFamilyWarning(family)), diagnostics);
    }

    @Test
    void theWarningNamesTheFamilyAsNormalised() {
        parse("  Bogus ");
        assertEquals(List.of(unknownFamilyWarning("bogus")), diagnostics);
    }

    @Test
    void knownFamiliesAroundAnUnknownOneAreStillSelected() {
        assertEquals(List.of("locked", "contract"), parse("locked,bogus,contract"));
        assertEquals(List.of(unknownFamilyWarning("bogus")), diagnostics);
    }

    @Test
    void everyUnknownFamilyGetsItsOwnWarning() {
        assertEquals(List.of("locked"), parse("one,locked,two"));
        assertEquals(List.of(unknownFamilyWarning("one"), unknownFamilyWarning("two")), diagnostics);
    }

    // ------------------------------------------------------------------ what each family guards

    private static TaggedElement method(String path) {
        return TaggedElement.builder(path).kind(ElementTag.METHOD).signature("void " + path + "()").build();
    }

    private static GuardrailModel oneOfEach() {
        return new GuardrailModelBuilder()
            .add(AILocked.class, method("a.B#locked"))
            .add(AIContract.class, method("a.B#contract"))
            .add(AIPublicAPI.class, method("a.B#publicapi"))
            .build();
    }

    static Stream<Arguments> familyToAnnotation() {
        return Stream.of(
            Arguments.of("locked", AILocked.class),
            Arguments.of("contract", AIContract.class),
            Arguments.of("publicapi", AIPublicAPI.class));
    }

    @ParameterizedTest(name = "{0} guards @{1}")
    @MethodSource("familyToAnnotation")
    void eachFamilyRecordsOnlyTheElementsOfItsAnnotation(String family, Class<? extends Annotation> annotation)
            throws IOException {
        enforcer().enforce(oneOfEach(), enforcer().parseFamilies(family), root, "core", true);

        List<String> recorded = Files.readAllLines(root.resolve(EnforcementBaseline.FILE_NAME), StandardCharsets.UTF_8)
            .stream().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
        assertEquals(List.of("core\t" + family + "\ta.B#" + family + "\tvoid a.B#" + family + "()"), recorded,
            family + " must record its own @" + annotation.getSimpleName() + " element and nothing else");
    }

    @ParameterizedTest(name = "{0} reports as @{1}")
    @MethodSource("familyToAnnotation")
    void aViolationIsReportedUnderTheFamilysAnnotationName(String family, Class<? extends Annotation> annotation) {
        enforcer().enforce(oneOfEach(), enforcer().parseFamilies(family), root, "core", true);
        diagnostics.clear();

        assertEquals(1, enforcer().enforce(new GuardrailModelBuilder().build(), enforcer().parseFamilies(family),
            root, "core", false));
        assertTrue(diagnostics.get(0).startsWith("ERROR: VibeTags: @" + annotation.getSimpleName() + " violation"),
            diagnostics.get(0));
    }

    @Test
    void allRecordsEveryFamilyInOneSortedBaseline() throws IOException {
        enforcer().enforce(oneOfEach(), enforcer().parseFamilies("publicapi,locked,contract"), root, "core", true);

        List<String> recorded = Files.readAllLines(root.resolve(EnforcementBaseline.FILE_NAME), StandardCharsets.UTF_8)
            .stream().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
        assertEquals(List.of(
            "core\tcontract\ta.B#contract\tvoid a.B#contract()",
            "core\tlocked\ta.B#locked\tvoid a.B#locked()",
            "core\tpublicapi\ta.B#publicapi\tvoid a.B#publicapi()"), recorded);
    }

    // ------------------------------------------------------------------ how a family set is printed

    @Test
    void thePassedNoteListsTheFamiliesSortedByName() {
        enforcer().enforce(oneOfEach(), enforcer().parseFamilies("all"), root, "core", true);
        for (String option : List.of("all", "publicapi,locked,contract", "locked,contract,publicapi")) {
            diagnostics.clear();
            assertEquals(0, enforcer().enforce(oneOfEach(), enforcer().parseFamilies(option), root, "core", false));
            assertEquals(List.of("NOTE: VibeTags: enforcement passed " + DASH + " 3 guarded element(s) unchanged"
                + " (contract, locked, publicapi)."), diagnostics, option);
        }
    }

    @Test
    void theUnknownFamilyWarningListsTheFamiliesSortedByName() {
        parse("x");
        assertTrue(diagnostics.get(0).contains("Enforceable families are contract, locked, publicapi (or 'all')."),
            diagnostics.get(0));
    }

    @Test
    void aFamilySetPrintsAsItsNamesInTheUsersOrder() {
        assertEquals("[contract, locked]", String.valueOf(enforcer().parseFamilies("contract,locked")));
        assertEquals("[locked, contract]", String.valueOf(enforcer().parseFamilies("locked,contract")));
    }

    @Test
    void theParsedSetIsAFreshMutableSetEachTime() {
        Set<?> first = enforcer().parseFamilies("locked");
        Set<?> second = enforcer().parseFamilies("locked");
        assertFalse(first == second, "each parse returns its own set");
        assertEquals(names(first), names(second));
    }
}
