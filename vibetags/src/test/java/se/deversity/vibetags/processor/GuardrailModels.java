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

    /**
     * The same model with every <em>optional</em> member left at its unset value: strings empty,
     * booleans false, numbers zero, arrays empty, and no resolved type members.
     *
     * <p>Almost every annotation has one required member and several optional ones, and almost
     * every formatter reads an optional one through a ternary — {@code reason.isEmpty() ? "" : ...}
     * — repeated once per platform. {@link #everyAnnotation} only ever takes the populated side of
     * those, so the branch that runs when a user writes the bare {@code @AISecure} was the one
     * nothing exercised, on every formatter at once. That is the common way the annotation is
     * actually written.
     *
     * <p>Enums have no unset value, so this fixture answers with the <em>last</em> constant where
     * {@link #everyAnnotation} answers with the first: the two together cover both ends of the
     * switch a formatter writes over an enum member.
     */
    public static GuardrailModel everyAnnotationWithMembersUnset() {
        GuardrailModel.Builder builder = GuardrailModel.builder();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            builder.add(type, unsetElement(type));
        }
        return builder.build();
    }

    /** The fixture element for one annotation type with every optional member unset. */
    public static TaggedElement elementWithMembersUnset(Class<? extends Annotation> type) {
        return unsetElement(type);
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

    /** An instance of {@code type} answering every member with its unset value. */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A unsetInstance(Class<A> type) {
        return (A) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> type;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "@" + type.getName() + "(unset)";
                default -> unsetMember(method);
            });
    }

    /** Binds the wildcard for {@link #everyAnnotationWithMembersUnset}. */
    private static <A extends Annotation> TaggedElement unsetElementFor(Class<A> type) {
        String qualified = marker(type);
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        // No .typeMember("replacement", ...): an unresolved Class member is what "unset" means for
        // AISunset, and the formatter has to fall back rather than print an empty replacement.
        return TaggedElement.builder(qualified)
            .names(qualified, simple, qualified, qualified)
            .kind(ElementTag.CLASS)
            .signature(qualified)
            .annotation(type, unsetInstance(type))
            .build();
    }

    private static TaggedElement unsetElement(Class<? extends Annotation> type) {
        return unsetElementFor(type);
    }

    /**
     * The value the compiler hands a formatter for one member when the annotation is written bare.
     *
     * <p>That is the member's <em>declared default</em>, and nothing else. This used to fabricate a
     * zero-value per type instead — {@code 0} for an int, the last constant for an enum — which
     * made the fixture model an annotation whose members had been zeroed rather than one nobody
     * filled in. For {@code String} members the two agree, because the default is {@code ""}, which
     * is why the empty-label defects this fixture found were all real. For the rest they do not:
     * {@code @AITestDriven}'s {@code coverageGoal} defaults to 100 and rendered as
     * {@code Coverage goal: 0%}, and {@code @AIThreadSafe}'s {@code strategy} defaults to
     * {@code SYNCHRONIZED} and rendered as {@code Strategy: OTHER}. Both read as bugs in the
     * renderer and were bugs in the fixture — a state no user can put the processor into.
     *
     * <p>A member with no declared default cannot be omitted: the annotation does not compile
     * without it. Those fall through to an empty value, because "bare" is not a state they have
     * and the emptiest thing the formatter could see is the useful one to exercise.
     */
    private static Object unsetMember(Method method) {
        Object declared = method.getDefaultValue();
        if (declared != null) {
            return declared;
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == String.class) {
            return "";
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == Class.class) {
            return Object.class;
        }
        if (returnType.isEnum()) {
            return lastConstant(returnType);
        }
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }
        throw new IllegalStateException(
            "No unset fixture value for " + method.getDeclaringClass().getSimpleName()
                + "." + method.getName() + "() of type " + returnType
                + " - add a case to GuardrailModels.unsetMember");
    }

    /** The enum's last constant, so the two fixtures between them reach both ends of a switch. */
    private static Object lastConstant(Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        if (constants.length == 0) {
            throw new IllegalStateException(enumType + " has no constants");
        }
        return constants[constants.length - 1];
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
