package se.deversity.vibetags.ksp.internal;

import java.util.List;
import java.util.Map;

/**
 * One annotation use, copied out of KSP while its round was live.
 *
 * <p>Values are plain Java: a {@link String}, a boxed primitive, an {@link EnumValue}, a
 * {@link ClassValue}, a nested {@link AnnotationData}, or a {@link List} of those for an array
 * member. Only members written at the use site are present; {@link AnnotationProxies} supplies
 * the declared defaults for the rest, which is where javac gets them too.
 *
 * @param type   the annotation's qualified name
 * @param values member name to value, for the members written at the use site
 */
record AnnotationData(String type, Map<String, Object> values) {

    AnnotationData {
        values = Map.copyOf(values);
    }

    /** An enum constant, by its enum's qualified name and the constant's name. */
    record EnumValue(String enumType, String constant) {
    }

    /** A class literal, by the class's qualified name. */
    record ClassValue(String qualifiedName) {
    }
}
