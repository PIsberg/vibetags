package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSAnnotation;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSName;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeAlias;
import com.google.devtools.ksp.symbol.KSTypeArgument;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.google.devtools.ksp.symbol.KSValueArgument;
import com.google.devtools.ksp.symbol.Modifier;
import com.google.devtools.ksp.symbol.Variance;
import org.jspecify.annotations.Nullable;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a Kotlin type the way kapt's stub spells it in a JVM signature.
 *
 * <p>Every rule here was read off kapt's own output for the same declaration (the fixture in this
 * module's tests), not off the Kotlin specification, because the stub is what the path is made of:
 * <ul>
 *   <li>A non-null Kotlin primitive is a Java primitive at the top of a parameter, return or field
 *       type, and boxed everywhere else ({@code Int?}, a type argument, an {@code Array<Int>}).</li>
 *   <li>Kotlin's built-in types become their Java counterparts: {@code List} and {@code MutableList}
 *       are both {@code java.util.List}, {@code Any} is {@code Object}, {@code Nothing} is
 *       {@code Void}, {@code Result} at the top of a signature erases to {@code Object}.</li>
 *   <li>Function types become {@code kotlin.jvm.functions.FunctionN}; a suspend function type gains
 *       a {@code Continuation} parameter and returns {@code Object}.</li>
 *   <li>In a parameter, declaration-site variance becomes a wildcard: {@code in} always gives
 *       {@code ? super}, and {@code out} gives {@code ? extends} unless the argument's class is final
 *       ({@code List<String>} stays as written, {@code Set<Animal>} becomes
 *       {@code Set<? extends Animal>}). Return and field types get none.</li>
 * </ul>
 * {@code @JvmSuppressWildcards} (on a class, function, property, type or argument) removes them,
 * and {@code @JvmWildcard} on an argument forces one; see {@link #argument}.
 */
final class JvmTypes {

    /** Where a type sits; it decides primitive-versus-boxed and whether wildcards apply. */
    enum Position {
        /** The type of a method or constructor parameter. */
        PARAMETER,
        /** A method's return type. */
        RETURN,
        /** A field's type. */
        FIELD,
        /** A type argument, or an array's component. Never a primitive. */
        ARGUMENT
    }

    private static final Pattern FUNCTION = Pattern.compile("kotlin\\.Function(\\d+)");
    private static final Pattern SUSPEND_FUNCTION = Pattern.compile("kotlin\\.coroutines\\.SuspendFunction(\\d+)");
    private static final String CONTINUATION = "kotlin.coroutines.Continuation";
    private static final String OBJECT = "java.lang.Object";
    private static final String JVM_SUPPRESS_WILDCARDS = "kotlin.jvm.JvmSuppressWildcards";
    private static final String JVM_WILDCARD = "kotlin.jvm.JvmWildcard";

    private static final Map<String, TypeKind> PRIMITIVES = Map.of(
        "kotlin.Int", TypeKind.INT, "kotlin.Long", TypeKind.LONG, "kotlin.Short", TypeKind.SHORT,
        "kotlin.Byte", TypeKind.BYTE, "kotlin.Char", TypeKind.CHAR, "kotlin.Boolean", TypeKind.BOOLEAN,
        "kotlin.Float", TypeKind.FLOAT, "kotlin.Double", TypeKind.DOUBLE);

    private static final Map<String, TypeKind> PRIMITIVE_ARRAYS = Map.of(
        "kotlin.IntArray", TypeKind.INT, "kotlin.LongArray", TypeKind.LONG,
        "kotlin.ShortArray", TypeKind.SHORT, "kotlin.ByteArray", TypeKind.BYTE,
        "kotlin.CharArray", TypeKind.CHAR, "kotlin.BooleanArray", TypeKind.BOOLEAN,
        "kotlin.FloatArray", TypeKind.FLOAT, "kotlin.DoubleArray", TypeKind.DOUBLE);

    private static final Map<String, String> JAVA_NAMES = javaNames();

    private final Map<String, KTypeElement> declared;
    private final Map<String, KTypeElement> references = new HashMap<>();

    /**
     * @param declared the types this compilation declares, by qualified name, so a signature that
     *                 names one points at the model's own element
     */
    JvmTypes(Map<String, KTypeElement> declared) {
        this.declared = declared;
    }

    private static Map<String, String> javaNames() {
        Map<String, String> names = new HashMap<>();
        names.put("kotlin.Any", OBJECT);
        names.put("kotlin.String", "java.lang.String");
        names.put("kotlin.CharSequence", "java.lang.CharSequence");
        names.put("kotlin.Number", "java.lang.Number");
        names.put("kotlin.Throwable", "java.lang.Throwable");
        names.put("kotlin.Comparable", "java.lang.Comparable");
        names.put("kotlin.Enum", "java.lang.Enum");
        names.put("kotlin.Annotation", "java.lang.annotation.Annotation");
        names.put("kotlin.Cloneable", "java.lang.Cloneable");
        names.put("kotlin.Nothing", "java.lang.Void");
        names.put("kotlin.Int", "java.lang.Integer");
        names.put("kotlin.Long", "java.lang.Long");
        names.put("kotlin.Short", "java.lang.Short");
        names.put("kotlin.Byte", "java.lang.Byte");
        names.put("kotlin.Char", "java.lang.Character");
        names.put("kotlin.Boolean", "java.lang.Boolean");
        names.put("kotlin.Float", "java.lang.Float");
        names.put("kotlin.Double", "java.lang.Double");
        for (String mutable : List.of("", "Mutable")) {
            names.put("kotlin.collections." + mutable + "Iterable", "java.lang.Iterable");
            names.put("kotlin.collections." + mutable + "Iterator", "java.util.Iterator");
            names.put("kotlin.collections." + mutable + "Collection", "java.util.Collection");
            names.put("kotlin.collections." + mutable + "List", "java.util.List");
            names.put("kotlin.collections." + mutable + "Set", "java.util.Set");
            names.put("kotlin.collections." + mutable + "Map", "java.util.Map");
            names.put("kotlin.collections." + mutable + "ListIterator", "java.util.ListIterator");
        }
        names.put("kotlin.collections.Map.Entry", "java.util.Map.Entry");
        names.put("kotlin.collections.MutableMap.MutableEntry", "java.util.Map.Entry");
        return Map.copyOf(names);
    }

    /** The Java name kapt uses for a Kotlin class: its mapped Java type, or the name unchanged. */
    static String javaName(String kotlinQualifiedName) {
        return JAVA_NAMES.getOrDefault(kotlinQualifiedName, kotlinQualifiedName);
    }

    /** The qualified name of a declaration, or its simple name when it has none. */
    static String qualifiedName(KSDeclaration declaration) {
        KSName name = declaration.getQualifiedName();
        return name != null ? name.asString() : declaration.getSimpleName().asString();
    }

    /** Renders {@code reference} at {@code position}, with {@code scope} resolving type parameters. */
    TypeMirror render(@Nullable KSTypeReference reference, Position position, Scope scope) {
        return render(reference, position, scope, false);
    }

    /**
     * As {@link #render(KSTypeReference, Position, Scope)}, inside a declaration whose
     * {@code @JvmSuppressWildcards} (its own, or its class's) is {@code suppressed}.
     */
    TypeMirror render(@Nullable KSTypeReference reference, Position position, Scope scope, boolean suppressed) {
        return render(reference, position, scope, suppressed, false);
    }

    /**
     * As {@link #render(KSTypeReference, Position, Scope, boolean)}, and with {@code boxed} a value
     * class at the top of the signature keeps its own type instead of its underlying one, as in the
     * variant {@code @JvmExposeBoxed} exposes ({@code exposed(com.fx.wc.Eid)}).
     */
    TypeMirror render(@Nullable KSTypeReference reference, Position position, Scope scope, boolean suppressed,
                      boolean boxed) {
        if (reference == null) {
            return declared(OBJECT, List.of());
        }
        Boolean own = suppression(reference);
        Mode mode = new Mode(position == Position.PARAMETER, own != null ? own : suppressed, boxed);
        return render(reference.resolve(), position, scope, mode);
    }

    /**
     * Where a type is being rendered: inside a parameter (the only place Kotlin writes wildcards),
     * and whether {@code @JvmSuppressWildcards} is in force there.
     */
    private record Mode(boolean parameter, boolean suppressed, boolean boxed) {
        boolean wildcards() {
            return parameter && !suppressed;
        }

        Mode suppressing(boolean suppress) {
            return new Mode(parameter, suppress, boxed);
        }
    }

    /**
     * The {@code @JvmSuppressWildcards} written on {@code annotated}: its {@code suppress} value,
     * or {@code null} when there is none, so the enclosing declaration's setting applies.
     */
    static @Nullable Boolean suppression(KSAnnotated annotated) {
        Iterator<KSAnnotation> it = annotated.getAnnotations().iterator();
        while (it.hasNext()) {
            KSAnnotation annotation = it.next();
            if (JVM_SUPPRESS_WILDCARDS.equals(annotationName(annotation))) {
                for (KSValueArgument argument : annotation.getArguments()) {
                    if (argument.getValue() instanceof Boolean value) {
                        return value;
                    }
                }
                return Boolean.TRUE;
            }
        }
        return null;
    }

    private static boolean forcesWildcard(@Nullable KSAnnotated annotated) {
        if (annotated == null) {
            return false;
        }
        Iterator<KSAnnotation> it = annotated.getAnnotations().iterator();
        while (it.hasNext()) {
            if (JVM_WILDCARD.equals(annotationName(it.next()))) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String annotationName(KSAnnotation annotation) {
        KSType type = annotation.getAnnotationType().resolve();
        KSName name = type.isError() ? null : type.getDeclaration().getQualifiedName();
        return name == null ? null : name.asString();
    }

    /** A reference to a declared type by name, pointing at the model's element when there is one. */
    TypeMirror declared(String qualifiedName, List<TypeMirror> arguments) {
        KTypeElement element = declared.get(qualifiedName);
        if (element == null) {
            element = references.computeIfAbsent(qualifiedName, KTypeElement::reference);
        }
        return new KTypeMirror.Declared(element, arguments);
    }

    private TypeMirror render(KSType original, Position position, Scope scope, Mode mode) {
        KSType type = expandAliases(original);
        boolean nullable = original.isMarkedNullable() || type.isMarkedNullable();
        if (type.isError()) {
            return declared("error.NonExistentClass", List.of());
        }
        KSDeclaration declaration = type.getDeclaration();
        if (declaration instanceof KSTypeParameter parameter) {
            return scope.variable(parameter.getName().asString());
        }
        String name = qualifiedName(declaration);
        if ("kotlin.Unit".equals(name) && position == Position.RETURN) {
            return KTypeMirror.NoneType.VOID;
        }
        boolean top = position != Position.ARGUMENT;
        TypeKind primitive = PRIMITIVES.get(name);
        if (primitive != null && top && !nullable) {
            return new KTypeMirror.Primitive(primitive);
        }
        TypeKind primitiveArray = PRIMITIVE_ARRAYS.get(name);
        if (primitiveArray != null) {
            return new KTypeMirror.Arr(new KTypeMirror.Primitive(primitiveArray));
        }
        if ("kotlin.Array".equals(name)) {
            return new KTypeMirror.Arr(arrayComponent(type, scope, mode));
        }
        if ("kotlin.Result".equals(name) && top) {
            return declared(OBJECT, List.of());
        }
        if (top && !mode.boxed() && isValueClass(declaration) && declaration instanceof KSClassDeclaration valueClass) {
            TypeMirror underlying = underlyingOf(valueClass, position, scope);
            if (underlying != null) {
                return underlying;
            }
        }
        Matcher function = FUNCTION.matcher(name);
        if (function.matches()) {
            return function("kotlin.jvm.functions.Function" + function.group(1), type.getArguments(),
                null, scope, mode);
        }
        Matcher suspend = SUSPEND_FUNCTION.matcher(name);
        if (suspend.matches()) {
            int arity = Integer.parseInt(suspend.group(1));
            return function("kotlin.jvm.functions.Function" + (arity + 1), type.getArguments(),
                CONTINUATION, scope, mode);
        }
        return declaredWithArguments(javaName(name), declaration, type.getArguments(), scope, mode);
    }

    /** {@code type} with every type alias expanded to what it names. */
    static KSType expandAliases(KSType type) {
        KSType current = type;
        // Bounded: an alias chain cannot be longer than the aliases a project declares, and a
        // cycle is a compile error, but a bound costs nothing against a malformed input.
        for (int depth = 0; depth < 32 && current.getDeclaration() instanceof KSTypeAlias alias; depth++) {
            current = alias.getType().resolve();
        }
        return current;
    }

    private TypeMirror arrayComponent(KSType array, Scope scope, Mode mode) {
        List<KSTypeArgument> arguments = array.getArguments();
        if (arguments.isEmpty() || arguments.get(0).getType() == null
                || arguments.get(0).getVariance() == Variance.STAR) {
            return declared(OBJECT, List.of());
        }
        KSTypeReference component = arguments.get(0).getType();
        // An array's element keeps its own wildcards but loses the array's projection:
        // Array<out Any> is Object[], not ? extends Object[].
        return component == null ? declared(OBJECT, List.of())
            : render(component.resolve(), Position.ARGUMENT, scope, mode);
    }

    private static boolean isValueClass(KSDeclaration declaration) {
        return declaration instanceof KSClassDeclaration cls
            && (cls.getModifiers().contains(Modifier.VALUE) || cls.getModifiers().contains(Modifier.INLINE));
    }

    private @Nullable TypeMirror underlyingOf(KSClassDeclaration valueClass, Position position, Scope scope) {
        var constructor = valueClass.getPrimaryConstructor();
        if (constructor == null || constructor.getParameters().size() != 1) {
            return null;
        }
        return render(constructor.getParameters().get(0).getType(), position, scope);
    }

    /**
     * {@code kotlin.jvm.functions.FunctionN}: every parameter contravariant, the result covariant.
     * A suspend function type appends {@code Continuation<result>} and returns {@code Any?}.
     */
    private TypeMirror function(String javaName, List<KSTypeArgument> arguments,
                                @Nullable String continuation, Scope scope, Mode mode) {
        boolean wildcards = mode.wildcards();
        List<TypeMirror> rendered = new ArrayList<>();
        int last = arguments.size() - 1;
        for (int i = 0; i < last; i++) {
            rendered.add(argument(arguments.get(i), Variance.CONTRAVARIANT, scope, mode));
        }
        if (continuation != null) {
            TypeMirror result = last >= 0 ? argument(arguments.get(last), Variance.CONTRAVARIANT, scope, mode)
                : declared("kotlin.Unit", List.of());
            TypeMirror inner = last >= 0 ? stripWildcard(result) : result;
            TypeMirror continuationType = declared(continuation,
                List.of(wildcards ? new KTypeMirror.Wildcard(null, inner) : inner));
            rendered.add(wildcards ? new KTypeMirror.Wildcard(null, continuationType) : continuationType);
            TypeMirror object = declared(OBJECT, List.of());
            rendered.add(wildcards ? new KTypeMirror.Wildcard(object, null) : object);
        } else if (last >= 0) {
            rendered.add(argument(arguments.get(last), Variance.COVARIANT, scope, mode));
        }
        return declared(javaName, rendered);
    }

    private static TypeMirror stripWildcard(TypeMirror type) {
        if (type instanceof KTypeMirror.Wildcard wildcard) {
            TypeMirror bound = wildcard.getSuperBound() != null ? wildcard.getSuperBound() : wildcard.getExtendsBound();
            return bound != null ? bound : type;
        }
        return type;
    }

    private TypeMirror declaredWithArguments(String javaName, KSDeclaration declaration,
                                             List<KSTypeArgument> arguments, Scope scope, Mode mode) {
        List<KSTypeParameter> parameters = declaration.getTypeParameters();
        List<TypeMirror> rendered = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Variance declared = i < parameters.size() ? parameters.get(i).getVariance() : Variance.INVARIANT;
            rendered.add(argument(arguments.get(i), declared, scope, mode));
        }
        return declared(javaName, rendered);
    }

    /**
     * One type argument, with the wildcard Kotlin's JVM signature gives it in a parameter.
     *
     * <p>{@code @JvmSuppressWildcards} on the argument applies to the argument's own wildcard as
     * well as to everything inside it ({@code Map<String, @JvmSuppressWildcards List<Base>>} is
     * {@code Map<String,List<Base>>}); {@code @JvmWildcard} forces the wildcard even on a final class
     * and even where suppression is in force. Both measured against kapt ({@code StubParityTest}).
     */
    private TypeMirror argument(KSTypeArgument argument, Variance declared, Scope scope, Mode mode) {
        Variance use = argument.getVariance();
        KSTypeReference reference = argument.getType();
        if (use == Variance.STAR || reference == null) {
            return mode.wildcards() ? new KTypeMirror.Wildcard(null, null) : declared(OBJECT, List.of());
        }
        Boolean own = suppression(reference);
        if (own == null) {
            own = suppression(argument);
        }
        Mode inner = own == null ? mode : mode.suppressing(own);
        boolean forced = mode.parameter() && (forcesWildcard(reference) || forcesWildcard(argument));
        KSType type = reference.resolve();
        TypeMirror rendered = render(type, Position.ARGUMENT, scope, inner);
        boolean contravariant = use == Variance.CONTRAVARIANT
            || use == Variance.INVARIANT && declared == Variance.CONTRAVARIANT;
        boolean covariant = !contravariant && (use == Variance.COVARIANT || declared == Variance.COVARIANT);
        if (forced) {
            if (contravariant) {
                return new KTypeMirror.Wildcard(null, rendered);
            }
            return covariant ? new KTypeMirror.Wildcard(rendered, null) : rendered;
        }
        if (!inner.wildcards()) {
            return rendered;
        }
        if (contravariant) {
            return new KTypeMirror.Wildcard(null, rendered);
        }
        if (covariant && !isFinal(expandAliases(type))) {
            return new KTypeMirror.Wildcard(rendered, null);
        }
        return rendered;
    }

    /**
     * Whether Kotlin treats {@code type}'s class as final for wildcard purposes. A type parameter
     * is not; an interface, abstract, open or sealed class is not; everything else is, including
     * the primitives, {@code String} and a Kotlin {@code object}.
     */
    private static boolean isFinal(KSType type) {
        KSDeclaration declaration = type.getDeclaration();
        if (!(declaration instanceof KSClassDeclaration cls)) {
            return false;
        }
        if ("kotlin.Any".equals(qualifiedName(cls))) {
            return false;
        }
        return cls.getClassKind() != ClassKind.INTERFACE && !UtilsKt.isOpen(cls);
    }

    /** Type parameters in scope for a declaration: its own, then its enclosing class's. */
    static final class Scope {
        private final Map<String, KTypeParameterElement> parameters;
        private final @Nullable Scope outer;

        Scope(Map<String, KTypeParameterElement> parameters, @Nullable Scope outer) {
            this.parameters = parameters;
            this.outer = outer;
        }

        static Scope empty() {
            return new Scope(Map.of(), null);
        }

        TypeMirror variable(String name) {
            for (Scope scope = this; scope != null; scope = scope.outer) {
                KTypeParameterElement parameter = scope.parameters.get(name);
                if (parameter != null) {
                    return parameter.asType();
                }
            }
            // A parameter this model did not declare (a local generic); render it by name.
            KTypeParameterElement free = new KTypeParameterElement(name, KTypeElement.reference(OBJECT));
            return free.asType();
        }
    }
}
