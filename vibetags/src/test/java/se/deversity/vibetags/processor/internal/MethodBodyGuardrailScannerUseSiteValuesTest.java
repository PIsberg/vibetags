package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.annotations.AILocked;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * {@link MethodBodyGuardrailScanner#valuesFromOutsideTheRound()} for an element model that cannot
 * look the annotation types up by name and carries no mirrors, the model {@code vibetags-ksp} builds
 * (#1121). Its values come from {@link UseSiteAnnotationValues}.
 *
 * <p>A zero here lets the source digest vouch for the round, and the next unchanged build is skipped.
 * So every value the round's sources do not spell out must count, and the ones they do (an enum
 * constant, a class literal, a string literal in the file) must not, or the exit never fires. The
 * KSP module's own tests reach this code only through the processor jar installed in the local
 * repository, so the rule is pinned here, against the scanner itself.
 */
class MethodBodyGuardrailScannerUseSiteValuesTest {

    @TempDir
    Path dir;

    @Test
    void enumConstantsAndClassLiteralsAreSpelledOutAloneOrInALists() throws IOException {
        Element ledger = siteElement(Map.of("category", UseSiteAnnotationValues.NAMED,
            "types", List.of(UseSiteAnnotationValues.NAMED, UseSiteAnnotationValues.NAMED)));

        assertEquals(0, valuesFromOutside(ledger, "class Ledger"));
    }

    @Test
    void aStringIsSpelledOutOnlyWhenItsLiteralIsInTheFile() throws IOException {
        String source = "@AILocked(reason = \"ledger maths\") class Ledger";

        assertEquals(0, valuesFromOutside(siteElement(Map.of("reason", "ledger maths")), source));
        assertEquals(1, valuesFromOutside(siteElement(Map.of("reason", "settlement")), source),
            "a value the file does not spell out came from a constant outside the round");
    }

    @Test
    void aListCountsWhenAnyItemIsNotSpelledOut() throws IOException {
        Element ledger = siteElement(Map.of("tags", List.of(UseSiteAnnotationValues.NAMED, "elsewhere")));

        assertEquals(1, valuesFromOutside(ledger, "class Ledger"));
    }

    @Test
    void eachValueNotSpelledOutCountsOnItsOwn() throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reason", "from a constant");
        values.put("owner", "from another constant");
        values.put("nested", new Object()); // a nested annotation: nothing reads it as spelled out

        assertEquals(3, valuesFromOutside(siteElement(values), "class Ledger"));
    }

    /** Nothing says the values of an element that offers none were spelled out, so it counts once. */
    @Test
    void anElementThatOffersNoUseSiteValuesCountsOnce() throws IOException {
        TypeElement plain = typeElement(mock(TypeElement.class));
        Element silent = siteElement(null);

        assertEquals(1, valuesFromOutside(plain, "class Ledger"));
        assertEquals(1, valuesFromOutside(silent, "class Ledger"));
    }

    // ---------------------------------------------------------------- helpers

    private static Element siteElement(Map<String, Object> values) {
        TypeElement element = typeElement(mock(TypeElement.class,
            withSettings().extraInterfaces(UseSiteAnnotationValues.class)));
        when(((UseSiteAnnotationValues) element).useSiteValues(AILocked.class.getName())).thenReturn(values);
        return element;
    }

    private static TypeElement typeElement(TypeElement element) {
        PackageElement pkg = mock(PackageElement.class);
        when(pkg.getKind()).thenReturn(ElementKind.PACKAGE);
        when(element.getKind()).thenReturn(ElementKind.CLASS);
        doReturn(pkg).when(element).getEnclosingElement();
        return element;
    }

    /**
     * The count for one round whose single root, {@code element}, carries {@code @AILocked} and is
     * declared in a file holding {@code source}, with the annotation types unknown to the model.
     */
    private int valuesFromOutside(Element element, String source) throws IOException {
        Path file = Files.writeString(dir.resolve("Ledger.java"), source, StandardCharsets.UTF_8);
        JavaFileObject object = mock(JavaFileObject.class);
        when(object.toUri()).thenReturn(file.toUri());
        Elements elements = mock(Elements.class);
        when(elements.getTypeElement(any())).thenReturn(null);
        when(elements.getFileObjectOf(element)).thenReturn(object);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getElementUtils()).thenReturn(elements);
        RoundEnvironment round = mock(RoundEnvironment.class);
        doReturn(Set.of(element)).when(round).getRootElements();
        doReturn(Set.of(element)).when(round).getElementsAnnotatedWith(AILocked.class);

        MethodBodyGuardrailScanner scanner = MethodBodyGuardrailScanner.forEnv(env);
        scanner.scanAndWarn(round, RoundSources.of(env, round));
        return scanner.valuesFromOutsideTheRound();
    }
}
