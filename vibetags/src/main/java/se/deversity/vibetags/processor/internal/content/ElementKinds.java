package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.ElementTag;

import java.util.HashMap;
import java.util.Map;

/**
 * Lookups and classifications over {@link ElementTag}, kept out of the enum so the model holds
 * constants and nothing else.
 *
 * <p>{@link #isClass} and {@link #isInterface} answer exactly as {@code ElementKind.isClass()} and
 * {@code ElementKind.isInterface()} do for the same name; {@code ElementTagMappingTest} checks that
 * against every constant javac defines.
 */
public final class ElementKinds {

    private static final Map<String, ElementTag> BY_NAME = new HashMap<>();

    static {
        for (ElementTag tag : ElementTag.values()) {
            BY_NAME.put(tag.name(), tag);
        }
    }

    private ElementKinds() {}

    /**
     * The tag whose name equals {@code kindName}, or {@code null} when {@link ElementTag} has no
     * counterpart. Callers snapshotting a real element should fall back to {@link ElementTag#OTHER};
     * a {@code null} return is reserved for callers that want to detect the gap (the mapping test).
     */
    public static @Nullable ElementTag fromName(@Nullable String kindName) {
        return kindName == null ? null : BY_NAME.get(kindName);
    }

    /** True for a class, enum, or record: the same set as {@code ElementKind.isClass()}. */
    public static boolean isClass(ElementTag kind) {
        return kind == ElementTag.CLASS || kind == ElementTag.ENUM || kind == ElementTag.RECORD;
    }

    /** True for an interface or annotation type: the same set as {@code ElementKind.isInterface()}. */
    public static boolean isInterface(ElementTag kind) {
        return kind == ElementTag.INTERFACE || kind == ElementTag.ANNOTATION_TYPE;
    }
}
