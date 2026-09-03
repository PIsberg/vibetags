package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Hands out a guardrail annotation whose string members read as one line.
 *
 * <p>The string members are prose, and a Java text block is the natural way to write a long
 * reason. Every Markdown platform appends the value straight after a bullet, so a line break in it
 * ended the bullet and left the rest as a bare paragraph; the XML block carried the raw newline.
 * Normalising here, where the collector records the annotation, reaches all 37 platforms, the
 * granular files and the fingerprint through the one accessor they share,
 * {@code TaggedElement.annotation}, rather than through forty-four formatters.
 *
 * <p>The rule: every line break, together with the indentation after it, becomes a single space,
 * and blank lines add nothing. A value with no line break is handed back as the very same
 * string, so no committed file changes for anything written on one line.
 */
final class SingleLineAnnotation {

    private SingleLineAnnotation() {}

    /**
     * {@code value} with single-line string members, or {@code value} itself when the annotation
     * type has no string member and there is nothing to normalise.
     */
    // The proxy must live where the annotation interface lives, which is that interface's own
    // loader — the context loader PMD prefers is the build tool's and may not see the annotation.
    // Its equals is identity on purpose: the javac annotation it wraps would otherwise be asked to
    // compare itself with the proxy and reflect over an object it did not create.
    @SuppressWarnings({"PMD.UseProperClassLoader", "PMD.CompareObjectsWithEquals"})
    static <A extends Annotation> @Nullable A of(Class<A> type, @Nullable A value) {
        if (value == null || !hasStringMember(type)) {
            return value;
        }
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (self, method, args) -> {
                if (method.getDeclaringClass() == Object.class && "equals".equals(method.getName())) {
                    return args != null && self == args[0];
                }
                try {
                    return singleLine(method.invoke(value, args));
                } catch (InvocationTargetException e) {
                    // Whatever the real accessor threw is what the caller must see, unwrapped.
                    Throwable cause = e.getCause();
                    throw cause != null ? cause : e;
                }
            });
        return type.cast(proxy);
    }

    private static boolean hasStringMember(Class<?> type) {
        for (Method member : type.getDeclaredMethods()) {
            Class<?> returned = member.getReturnType();
            if (returned == String.class || returned == String[].class) {
                return true;
            }
        }
        return false;
    }

    /** A member's value with every string in it on one line; anything else passes through. */
    private static @Nullable Object singleLine(@Nullable Object result) {
        if (result instanceof String s) {
            return singleLine(s);
        }
        if (result instanceof String[] array) {
            String[] copy = new String[array.length];
            for (int i = 0; i < array.length; i++) {
                copy[i] = singleLine(array[i]);
            }
            return copy;
        }
        return result;
    }

    /** {@code value} as one line; the same instance when it already is one. */
    static String singleLine(String value) {
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (String line : value.split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(text);
        }
        return sb.toString();
    }
}
