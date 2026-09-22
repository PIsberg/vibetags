package se.deversity.vibetags.loadtest;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Generates annotated sources for the annotations {@link SyntheticClassGenerator} does not emit.
 *
 * <p>That generator rotates six annotations, and has since 0.5.4. The project has 44, so 38
 * formatters never ran in any sweep under {@code load-tests/} and no committed baseline says
 * anything about their cost (issue #835). The six-annotation fixture is not widened in place,
 * deliberately: its output is what every folder under {@code load-tests/results/} measured, and
 * changing the mix silently makes the release trend incomparable. This is a second fixture beside
 * it, which is the route {@code PlatformBreadthStressTest} took for the same reason.
 *
 * <h2>Read out of the processor jar, not written here</h2>
 *
 * <p>The annotation set comes from {@code GuardrailAnnotations.ALL} and every usage is synthesised
 * from the annotation type itself: {@code @Target} decides where it can sit and the declared
 * members decide what to pass. A hand-kept list of 44 annotations and their members is the defect
 * #762 and #765 were opened for, and it would go stale the first time an annotation was added, in
 * the one fixture whose whole purpose is to leave none out.
 *
 * <p>Read reflectively for the same reason {@code PlatformBreadthStressTest} reads
 * {@code ServiceRegistry} that way: this module is compiled against older processor jars on
 * purpose, to compare releases, and a direct reference to a class or annotation an old jar lacks
 * fails the module's {@code testCompile} and takes the volume sweeps down with it. Against a jar
 * too old to answer, {@link #annotations()} returns an empty list and the caller skips.
 *
 * <h2>What a generated class looks like</h2>
 *
 * <p>Every annotation is written fully qualified, so no import list has to be kept in step with
 * the set, and every member is passed explicitly, defaults included, so each formatter renders
 * everything it can rather than the shortest thing it accepts. Each class carries a field, a
 * constructor and a method, so an annotation can be placed wherever its {@code @Target} allows.
 */
public final class WideAnnotationClassGenerator {

    private static final String TABLE_CLASS = "se.deversity.vibetags.processor.model.GuardrailAnnotations";

    private WideAnnotationClassGenerator() {}

    /**
     * Every annotation the processor jar on the classpath declares.
     *
     * @return the table's contents, or an empty list when the jar under test predates
     *     {@code GuardrailAnnotations.ALL} or names an annotation this classpath's annotations jar
     *     does not have
     */
    @SuppressWarnings("unchecked")
    public static List<Class<? extends Annotation>> annotations() {
        try {
            Field all = Class.forName(TABLE_CLASS).getField("ALL");
            List<Class<? extends Annotation>> table =
                (List<Class<? extends Annotation>>) all.get(null);
            return List.copyOf(table);
        } catch (ReflectiveOperationException | RuntimeException tooOld) {
            return Collections.emptyList();
        }
    }

    /** {@code [className, sourceCode]} for {@code n} classes carrying every annotation. */
    public static List<String[]> generate(int n) {
        return generate(n, annotations());
    }

    /** {@code [className, sourceCode]} for {@code n} classes carrying exactly {@code subset}. */
    public static List<String[]> generate(int n, List<Class<? extends Annotation>> subset) {
        List<String[]> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String className = "WideAnnotated" + i;
            result.add(new String[]{className, buildSource(className, i, subset)});
        }
        return result;
    }

    /**
     * {@code n} classes of the same shape carrying no VibeTags annotation at all.
     *
     * <p>The control column. A per-annotation number is only a per-annotation number once the
     * fixed cost of a round that renders anything has been taken out of it, and that fixed cost is
     * what this measures with the processor still active.
     */
    public static List<String[]> generateUnannotated(int n) {
        return generate(n, Collections.emptyList());
    }

    /**
     * Where one annotation can sit.
     *
     * @return the first placement its {@code @Target} allows, or {@link Placement#UNPLACEABLE}
     *     when this fixture offers no site it accepts
     */
    public static Placement placementOf(Class<? extends Annotation> annotation) {
        Target target = annotation.getAnnotation(Target.class);
        Set<ElementType> targets = target == null
            ? EnumSet.allOf(ElementType.class)
            : EnumSet.copyOf(List.of(target.value()));
        if (targets.contains(ElementType.TYPE)) {
            return Placement.TYPE;
        }
        if (targets.contains(ElementType.METHOD)) {
            return Placement.METHOD;
        }
        if (targets.contains(ElementType.FIELD)) {
            return Placement.FIELD;
        }
        if (targets.contains(ElementType.CONSTRUCTOR)) {
            return Placement.CONSTRUCTOR;
        }
        return Placement.UNPLACEABLE;
    }

    /**
     * The four places a generated class offers an annotation, and the answer for one it does not.
     *
     * <p>A {@code PACKAGE}-only annotation would need a {@code package-info.java}, which this
     * fixture does not emit. It is named rather than dropped quietly: the caller asserts every
     * annotation was placed, so one this cannot reach fails by name instead of going unmeasured,
     * which is the whole failure mode #835 is about.
     */
    public enum Placement { TYPE, FIELD, CONSTRUCTOR, METHOD, UNPLACEABLE }

    // -------------------------------------------------------------------------

    private static String buildSource(String className, int index,
                                      List<Class<? extends Annotation>> subset) {
        List<String> onType = new ArrayList<>();
        List<String> onField = new ArrayList<>();
        List<String> onConstructor = new ArrayList<>();
        List<String> onMethod = new ArrayList<>();
        for (Class<? extends Annotation> annotation : subset) {
            String usage = usage(annotation, index);
            switch (placementOf(annotation)) {
                case TYPE -> onType.add(usage);
                case FIELD -> onField.add(usage);
                case CONSTRUCTOR -> onConstructor.add(usage);
                case METHOD -> onMethod.add(usage);
                case UNPLACEABLE -> { /* named by the caller's assertion, not dropped silently */ }
                default -> throw new IllegalStateException("unhandled placement");
            }
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("package com.example.generated;\n\n");
        appendAll(sb, onType, "");
        sb.append("public class ").append(className).append(" {\n\n");
        appendAll(sb, onField, "    ");
        sb.append("    private String field").append(index).append(" = \"v").append(index).append("\";\n\n");
        appendAll(sb, onConstructor, "    ");
        sb.append("    public ").append(className).append("() { }\n\n");
        appendAll(sb, onMethod, "    ");
        sb.append("    public int id() { return ").append(index).append("; }\n")
          .append("}\n");
        return sb.toString();
    }

    private static void appendAll(StringBuilder sb, List<String> usages, String indent) {
        for (String usage : usages) {
            sb.append(indent).append(usage).append('\n');
        }
    }

    /**
     * One fully qualified annotation usage with every declared member passed explicitly.
     *
     * <p>Members are sorted by name. {@code getDeclaredMethods} does not promise an order, so
     * without this the fixture's text, and therefore its measurement, could differ between two
     * runs of the same JVM version for no reason anyone would ever find.
     */
    private static String usage(Class<? extends Annotation> annotation, int index) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('@').append(annotation.getCanonicalName());
        List<Method> members = new ArrayList<>(List.of(annotation.getDeclaredMethods()));
        members.sort(Comparator.comparing(Method::getName));
        if (members.isEmpty()) {
            return sb.toString();
        }
        sb.append('(');
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Method member = members.get(i);
            sb.append(member.getName()).append(" = ")
              .append(literal(member.getReturnType(), annotation, member, index));
        }
        return sb.append(')').toString();
    }

    /**
     * A literal of {@code type} that is valid Java and plausible content.
     *
     * <p>Plausible rather than minimal: a renderer that skips a blank value would otherwise be
     * measured doing nothing, which is the failure this fixture exists to stop. Every member type
     * the 44 annotations use is handled, and anything else throws by name rather than emitting
     * something that will not compile fifty classes later.
     */
    private static String literal(Class<?> type, Class<? extends Annotation> annotation,
                                  Method member, int index) {
        if (type == String.class) {
            return '"' + annotation.getSimpleName() + '.' + member.getName() + " #" + index + '"';
        }
        if (type == boolean.class) {
            return "true";
        }
        if (type == int.class) {
            return "1";
        }
        if (type == long.class) {
            return "1L";
        }
        if (type == double.class || type == float.class) {
            return "1";
        }
        if (type.isEnum()) {
            return enumConstant(type);
        }
        if (type == Class.class) {
            // AISunset.replacement is the only Class-typed member. A class literal the generated
            // source can always name, and one whose rendered form is recognisable in the output.
            return "java.lang.String.class";
        }
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            return "{" + literal(component, annotation, member, index) + ", "
                + literal(component, annotation, member, index + 1) + "}";
        }
        throw new IllegalStateException("no literal for " + type + " on "
            + annotation.getSimpleName() + "." + member.getName()
            + " — add it here rather than letting this annotation go unmeasured");
    }

    private static String enumConstant(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalStateException(type + " is an enum with no constants");
        }
        return type.getCanonicalName() + "." + ((Enum<?>) constants[0]).name();
    }
}
