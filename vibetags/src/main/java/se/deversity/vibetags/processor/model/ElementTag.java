package se.deversity.vibetags.processor.model;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The kind of Java element a {@link TaggedElement} describes — a javac-free mirror of
 * {@code javax.lang.model.element.ElementKind}.
 *
 * <p>Every constant is named <em>exactly</em> as its {@code ElementKind} counterpart, and that is
 * load-bearing rather than cosmetic: {@code LocksReportRenderer} writes {@code kind} into the
 * {@code .vibetags-locks} JSON that the bundled GitHub Action parses, and {@code GranularSections}
 * lower-cases the name into rule-file headings. Renaming a constant is a change to a published
 * output format, not a refactor.
 *
 * <p>{@link #OTHER} is the fallback for a kind a future JDK adds that this enum does not yet know.
 * Mapping to it degrades a heading rather than failing a build;
 * {@code ElementTagMappingTest} fails first so the constant gets added deliberately.
 */
public enum ElementTag {
    PACKAGE,
    ENUM,
    CLASS,
    ANNOTATION_TYPE,
    INTERFACE,
    ENUM_CONSTANT,
    FIELD,
    PARAMETER,
    LOCAL_VARIABLE,
    EXCEPTION_PARAMETER,
    METHOD,
    CONSTRUCTOR,
    STATIC_INIT,
    INSTANCE_INIT,
    TYPE_PARAMETER,
    OTHER,
    RESOURCE_VARIABLE,
    MODULE,
    RECORD,
    RECORD_COMPONENT,
    BINDING_VARIABLE,

    /**
     * The compiler reported no kind at all.
     *
     * <p>Distinct from {@link #OTHER}, which mirrors a real {@code ElementKind} constant: this one
     * has no counterpart and exists so "javac told us nothing" stays distinguishable from "javac
     * said OTHER". Rule-file headings render it as the word "element", which is what the null kind
     * produced before there was an enum here.
     */
    UNKNOWN;

    private static final Map<String, ElementTag> BY_NAME = new HashMap<>();

    static {
        for (ElementTag tag : values()) {
            BY_NAME.put(tag.name(), tag);
        }
    }

    /**
     * The tag whose name equals {@code kindName}, or {@code null} when this enum has no counterpart.
     * Callers snapshotting a real element should fall back to {@link #OTHER}; a {@code null} return
     * is reserved for callers that want to detect the gap (the mapping test).
     */
    public static @Nullable ElementTag fromName(@Nullable String kindName) {
        return kindName == null ? null : BY_NAME.get(kindName);
    }

    /** True for a class, enum, or record — same set as {@code ElementKind.isClass()}. */
    public boolean isClass() {
        return this == CLASS || this == ENUM || this == RECORD;
    }

    /** True for an interface or annotation type — same set as {@code ElementKind.isInterface()}. */
    public boolean isInterface() {
        return this == INTERFACE || this == ANNOTATION_TYPE;
    }
}
