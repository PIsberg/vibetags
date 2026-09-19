package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.symbol.AnnotationUseSiteTarget;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSAnnotation;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSName;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSValueArgument;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies annotations out of KSP into {@link AnnotationData}, and answers the one question the
 * placement rules need about an annotation type: which JVM element kinds it may target.
 */
final class AnnotationReader {

    /** One annotation use: its data, and the use-site target written in front of it, if any. */
    record Use(AnnotationData data, @Nullable AnnotationUseSiteTarget target) {
    }

    private final ClassLoader loader;
    private final Map<String, Set<ElementType>> targets = new HashMap<>();

    AnnotationReader(ClassLoader loader) {
        this.loader = loader;
    }

    /** Every annotation on {@code annotated} whose type resolves, in source order. */
    List<Use> read(KSAnnotated annotated) {
        List<Use> uses = new ArrayList<>();
        Iterator<KSAnnotation> it = annotated.getAnnotations().iterator();
        while (it.hasNext()) {
            KSAnnotation annotation = it.next();
            AnnotationData data = toData(annotation);
            if (data != null) {
                uses.add(new Use(data, annotation.getUseSiteTarget()));
            }
        }
        return uses;
    }

    /**
     * The element kinds {@code annotationType} may target, read from its {@link Target}. An
     * annotation whose class is not loadable here, or which declares no {@code @Target}, may go
     * anywhere a Java annotation may.
     */
    Set<ElementType> targetsOf(String annotationType) {
        return targets.computeIfAbsent(annotationType, this::loadTargets);
    }

    boolean allows(String annotationType, ElementType target) {
        return targetsOf(annotationType).contains(target);
    }

    private Set<ElementType> loadTargets(String annotationType) {
        try {
            Class<?> type = Class.forName(annotationType, false, loader);
            Target target = type.getAnnotation(Target.class);
            if (target != null) {
                Set<ElementType> allowed = EnumSet.noneOf(ElementType.class);
                allowed.addAll(List.of(target.value()));
                return allowed;
            }
        } catch (ClassNotFoundException | LinkageError notLoadable) {
            // Not on the processor's classpath (a Kotlin-only annotation, most often): no
            // @AI* annotation is ever in that position, so any answer that keeps it is fine.
        }
        return EnumSet.allOf(ElementType.class);
    }

    private @Nullable AnnotationData toData(KSAnnotation annotation) {
        KSType type = annotation.getAnnotationType().resolve();
        if (type.isError()) {
            return null;
        }
        KSName name = type.getDeclaration().getQualifiedName();
        if (name == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (KSValueArgument argument : annotation.getArguments()) {
            KSName argumentName = argument.getName();
            Object value = convert(argument.getValue());
            if (value != null) {
                values.put(argumentName == null ? "value" : argumentName.asString(), value);
            }
        }
        return new AnnotationData(name.asString(), values);
    }

    /** Converts a KSP argument value to the plain form {@link AnnotationData} holds. */
    private @Nullable Object convert(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof KSAnnotation nested) {
            return toData(nested);
        }
        if (value instanceof KSType type) {
            return fromDeclaration(type.getDeclaration());
        }
        if (value instanceof KSDeclaration declaration) {
            return fromDeclaration(declaration);
        }
        if (value instanceof Collection<?> items) {
            List<Object> converted = new ArrayList<>(items.size());
            for (Object item : items) {
                Object c = convert(item);
                if (c != null) {
                    converted.add(c);
                }
            }
            return converted;
        }
        if (value instanceof Object[] items) {
            return convert(List.of(items));
        }
        return value; // String, a boxed primitive, or a Character
    }

    /** An enum entry, or otherwise the class a class literal names. */
    private static Object fromDeclaration(KSDeclaration declaration) {
        if (declaration instanceof KSClassDeclaration cls && cls.getClassKind() == ClassKind.ENUM_ENTRY) {
            KSDeclaration parent = cls.getParentDeclaration();
            String enumType = parent == null ? "" : JvmTypes.qualifiedName(parent);
            return new AnnotationData.EnumValue(enumType, cls.getSimpleName().asString());
        }
        return new AnnotationData.ClassValue(JvmTypes.javaName(JvmTypes.qualifiedName(declaration)));
    }
}
