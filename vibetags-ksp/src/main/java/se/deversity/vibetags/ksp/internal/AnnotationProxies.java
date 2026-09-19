package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.Nullable;

import javax.lang.model.type.MirroredTypeException;
import java.lang.annotation.Annotation;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.StringJoiner;

/**
 * Builds annotation instances from {@link AnnotationData}, behaving the way javac's
 * {@code Element.getAnnotation} proxies do in the three ways the processor depends on:
 * <ul>
 *   <li>a member not written at the use site returns its declared default;</li>
 *   <li>a {@code Class}-valued member throws {@link MirroredTypeException} carrying the type, since
 *       the class need not be loadable during processing ({@code AISunset.replacement()} is read
 *       exactly that way);</li>
 *   <li>a required member that was never written throws {@link IncompleteAnnotationException},
 *       which is what reading one from a broken annotation does under javac.</li>
 * </ul>
 */
final class AnnotationProxies {

    private AnnotationProxies() {
    }

    // The proxy must live where the annotation interface lives, which is that interface's own
    // loader. The context loader PMD prefers is KSP's host's, not the loader KSP gave this
    // processor, and may not see the annotation (as in SingleLineAnnotation, for the same reason).
    @SuppressWarnings("PMD.UseProperClassLoader")
    static <A extends Annotation> A create(Class<A> type, AnnotationData data) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            new Handler(type, data));
        return type.cast(proxy);
    }

    /** Dispatches member reads to the recorded values, falling back to the declared defaults. */
    private static final class Handler implements InvocationHandler {
        private final Class<? extends Annotation> type;
        private final AnnotationData data;

        Handler(Class<? extends Annotation> type, AnnotationData data) {
            this.type = type;
            this.data = data;
        }

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) {
            String name = method.getName();
            if (args != null && args.length == 1 && "equals".equals(name)) {
                // Annotation.equals compares type and member values; the rendering carries both.
                return type.isInstance(args[0]) && describe().equals(String.valueOf(args[0]));
            }
            if (args != null && args.length > 0) {
                throw new UnsupportedOperationException(name);
            }
            return switch (name) {
                case "annotationType" -> type;
                case "hashCode" -> data.hashCode();
                case "toString" -> describe();
                default -> member(method);
            };
        }

        private Object member(Method method) {
            Object value = data.values().get(method.getName());
            if (value == null) {
                value = method.getDefaultValue();
            }
            if (value == null) {
                throw new IncompleteAnnotationException(type, method.getName());
            }
            return convert(value, method.getReturnType());
        }

        /** javac's rendering: {@code @pkg.Type(member=value, ...)}, members in declaration order. */
        private String describe() {
            StringJoiner joined = new StringJoiner(", ", "@" + type.getName() + "(", ")");
            for (Method method : type.getDeclaredMethods()) {
                if (data.values().containsKey(method.getName())) {
                    joined.add(method.getName() + "=" + data.values().get(method.getName()));
                }
            }
            return joined.toString();
        }
    }

    /** Converts a recorded value to the member's declared return type. */
    static Object convert(Object value, Class<?> target) {
        if (target.isArray()) {
            return toArray(value, target.getComponentType());
        }
        if (target == Class.class) {
            if (value instanceof AnnotationData.ClassValue literal) {
                throw new MirroredTypeException(
                    new KTypeMirror.Declared(KTypeElement.reference(literal.qualifiedName()), List.of()));
            }
            return value; // a declared default, already a Class
        }
        if (target.isEnum() && value instanceof AnnotationData.EnumValue constant) {
            return enumConstant(target, constant.constant());
        }
        if (target.isAnnotation() && value instanceof AnnotationData nested) {
            return create(target.asSubclass(Annotation.class), nested);
        }
        if (value instanceof Number number) {
            return toNumber(number, target);
        }
        return value;
    }

    private static Object toArray(Object value, Class<?> component) {
        if (value.getClass().isArray()) {
            // A declared default: hand out a copy, as javac does, so a caller cannot mutate it.
            int length = Array.getLength(value);
            Object copy = Array.newInstance(component, length);
            System.arraycopy(value, 0, copy, 0, length);
            return copy;
        }
        List<?> items = value instanceof List<?> list ? list : List.of(value);
        Object array = Array.newInstance(component, items.size());
        for (int i = 0; i < items.size(); i++) {
            Array.set(array, i, convert(items.get(i), component));
        }
        return array;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumType, String constant) {
        return Enum.valueOf((Class) enumType, constant);
    }

    private static Object toNumber(Number number, Class<?> target) {
        if (target == int.class || target == Integer.class) {
            return number.intValue();
        }
        if (target == long.class || target == Long.class) {
            return number.longValue();
        }
        if (target == double.class || target == Double.class) {
            return number.doubleValue();
        }
        if (target == float.class || target == Float.class) {
            return number.floatValue();
        }
        if (target == short.class || target == Short.class) {
            return number.shortValue();
        }
        if (target == byte.class || target == Byte.class) {
            return number.byteValue();
        }
        return number;
    }
}
