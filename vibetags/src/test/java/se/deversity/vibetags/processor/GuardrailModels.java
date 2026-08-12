package se.deversity.vibetags.processor;

import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Builds a {@link GuardrailModel} that carries <em>every</em> annotation in
 * {@link GuardrailAnnotations#ALL}, one distinctly named element each.
 *
 * <p>Exists because most renderer unit tests only ever rendered {@link GuardrailModel#EMPTY}. An
 * empty model runs the scaffolding and none of the 44 per-annotation branches, so a renderer that
 * silently dropped an annotation still produced its header and the test still passed.
 *
 * <p>The element for annotation {@code AIFoo} is named {@code com.example.fixture.AIFooTarget},
 * and that name is what {@link #marker(Class)} returns. Asserting a rendering contains the marker
 * is therefore the same question as "did this platform render {@code @AIFoo} at all?", whichever
 * accessor the formatter reached for — the qualified name, the simple name and the display name
 * all carry it.
 *
 * <p>Annotation instances are {@link Proxy} objects rather than 44 hand-written anonymous classes:
 * a hand-written set is a second declaration of the annotation surface, and the members people
 * forget to update are the ones added last. {@link #member} supplies a distinctive value per
 * member type, so a formatter that prints a member prints something a test can look for.
 */
public final class GuardrailModels {

    /** Package the fixture elements pretend to live in. */
    private static final String PKG = "com.example.fixture";

    private GuardrailModels() {}

    /**
     * The element name carried by {@code type}'s fixture element — the string a rendering must
     * contain if it rendered that annotation.
     */
    public static String marker(Class<? extends Annotation> type) {
        return PKG + "." + type.getSimpleName() + "Target";
    }

    /** The fixture element for one annotation type, tagged with exactly that annotation. */
    public static TaggedElement element(Class<? extends Annotation> type) {
        return elementFor(type);
    }

    /**
     * A model holding one element per annotation type. Every bucket is non-empty, so a renderer
     * has to make all 44 decisions rather than short-circuiting on an empty model.
     */
    public static GuardrailModel everyAnnotation() {
        GuardrailModel.Builder builder = GuardrailModel.builder();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            builder.add(type, element(type));
        }
        return builder.build();
    }

    /** An instance of {@code type} answering every member with a value {@link #member} chose. */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A instance(Class<A> type) {
        return (A) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@" + type.getName() + "(fixture)";
                default -> member(method);
            });
    }

    /** Binds the wildcard, so {@code annotation(Class<A>, A)} sees one type variable and not two captures. */
    private static <A extends Annotation> TaggedElement elementFor(Class<A> type) {
        String qualified = marker(type);
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        return TaggedElement.builder(qualified)
            .names(qualified, simple, qualified, qualified)
            .kind(ElementTag.CLASS)
            .signature(qualified)
            .annotation(type, instance(type))
            // AISunset.replacement() is a Class member the collector resolves into a type member
            // rather than reading off the annotation, so the fixture has to supply it the same way.
            .typeMember("replacement", qualified + "Replacement")
            .build();
    }

    /**
     * A value for one annotation member. Strings carry the member's own name, so a formatter that
     * prints the wrong member is visible in the failure, and booleans answer {@code true} so the
     * "flag is set" branch is the one exercised.
     */
    private static Object member(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == String.class) {
            return "fixture-" + method.getName();
        }
        if (returnType == boolean.class) {
            return true;
        }
        if (returnType == int.class) {
            return 7;
        }
        if (returnType == long.class) {
            return 7L;
        }
        if (returnType == Class.class) {
            Object declared = method.getDefaultValue();
            return declared != null ? declared : Object.class;
        }
        if (returnType.isEnum()) {
            return constant(returnType);
        }
        if (returnType.isArray()) {
            Class<?> component = returnType.getComponentType();
            if (component == String.class) {
                return new String[]{"fixture-" + method.getName()};
            }
            Object array = Array.newInstance(component, 1);
            Array.set(array, 0, component.isEnum() ? constant(component) : null);
            return array;
        }
        Object declared = method.getDefaultValue();
        if (declared != null) {
            return declared;
        }
        throw new IllegalStateException(
            "No fixture value for " + method.getDeclaringClass().getSimpleName()
                + "." + method.getName() + "() of type " + returnType
                + " — add a case to GuardrailModels.member");
    }

    /** The enum's first constant; every enum member here has at least one. */
    private static Object constant(Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        if (constants.length == 0) {
            throw new IllegalStateException(enumType + " has no constants");
        }
        return constants[0];
    }
}
