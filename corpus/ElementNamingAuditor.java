package corpus;

import se.deversity.vibetags.processor.internal.ElementNaming;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Checks {@link ElementNaming} against javac's own rendering, over whatever code it is pointed at.
 *
 * <p>{@code ElementNamingFormatParityTest} asserts the same property over a 26-member fixture the
 * project wrote itself. A fixture can only contain the shapes somebody thought of. This runs the
 * comparison over real third-party libraries instead, where the generics are gnarlier, the nesting
 * is deeper, and nobody was trying to be helpful.
 *
 * <p>The property: for every field, method and constructor, {@code ElementNaming.elementPath}
 * must equal {@code enclosingType + "." + element.toString()} under javac. That string is the
 * element's identity in {@code .vibetags-locks}, which the shipped {@code action/locked-files}
 * matches a pull request's diff against, and it is what {@code granularQName} turns into a rule
 * filename. javac's rendering is the target because that is what produced every generated file in
 * every consumer.
 *
 * <p>Writes {@code <report>} with one {@code MISMATCH} line per disagreement and a {@code VISITED}
 * count. The count matters as much as the mismatches: a run that visits nothing proves nothing,
 * and the harness fails when it is zero.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class ElementNamingAuditor extends AbstractProcessor {

    private static final String REPORT_OPTION = "corpus.report";

    /** {@code @some.qualified.Anno} or {@code @Anno(args)}, plus the space javac puts after it. */
    private static final java.util.regex.Pattern TYPE_USE_ANNOTATION =
        java.util.regex.Pattern.compile("@[A-Za-z_][A-Za-z0-9_.]*(\\([^)]*\\))?\\s*");

    private int visited;
    private int mismatches;
    private int annotated;
    private final StringBuilder findings = new StringBuilder();

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(REPORT_OPTION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element root : round.getRootElements()) {
            visit(root);
        }
        if (round.processingOver()) {
            writeReport();
        }
        // Never claim the annotations: VibeTags is running alongside this and must see them all.
        return false;
    }

    private void visit(Element type) {
        for (Element member : type.getEnclosedElements()) {
            ElementKind kind = member.getKind();
            if (kind == ElementKind.FIELD
                || kind == ElementKind.METHOD
                || kind == ElementKind.CONSTRUCTOR) {
                compare(member);
            } else if (kind.isClass() || kind.isInterface()) {
                // Nested and local types included on purpose: they are where a signature builder
                // that assumes a flat "package.Type.member" shape comes apart.
                visit(member);
            }
        }
    }

    private void compare(Element member) {
        Element enclosing = member.getEnclosingElement();
        if (enclosing == null) {
            return;
        }
        visited++;
        String javac = enclosing + "." + member;
        String derived = ElementNaming.elementPath(member);
        if (javac.equals(derived)) {
            return;
        }
        if (stripTypeAnnotations(javac).equals(derived)) {
            // The one divergence VibeTags intends. javac renders an annotated parameter as
            // java.lang.@org.jspecify.annotations.Nullable String; ElementNaming drops the
            // annotation, because this string is the element's identity and a @Nullable coming
            // or going must not rename a rule file or break a lock match. See ElementNaming's
            // typeString javadoc. Counted rather than ignored, so the corpus can show that the
            // difference is real and bounded rather than silently tolerated.
            annotated++;
            if (annotated <= 5) {
                findings.append("ANNOTATED\tjavac=").append(javac)
                        .append("\tderived=").append(derived).append('\n');
            }
            return;
        }
        mismatches++;
        // Capped: a systematic break would otherwise produce a report the size of the corpus.
        if (mismatches <= 50) {
            findings.append("MISMATCH\tjavac=").append(javac)
                    .append("\tderived=").append(derived).append('\n');
        }
    }

    /**
     * Removes JSR-308 type-use annotations from a javac-rendered signature, including any
     * parenthesised arguments, so what is left is the signature alone.
     */
    private static String stripTypeAnnotations(String rendered) {
        return TYPE_USE_ANNOTATION.matcher(rendered).replaceAll("");
    }

    private void writeReport() {
        String target = processingEnv.getOptions().get(REPORT_OPTION);
        if (target == null) {
            return;
        }
        try (Writer out = Files.newBufferedWriter(Path.of(target), StandardCharsets.UTF_8)) {
            out.write("VISITED\t" + visited + "\n");
            out.write("MISMATCHES\t" + mismatches + "\n");
            out.write("ANNOTATED\t" + annotated + "\n");
            out.write(findings.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the corpus report to " + target, e);
        }
    }
}
