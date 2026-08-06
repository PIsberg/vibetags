package se.deversity.vibetags.processor.model;

import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One annotated Java element, snapshotted into plain data.
 *
 * <p>This is the boundary between the two halves of the processor. Everything above it —
 * {@code AnnotationCollector}, {@code ElementNaming}, {@code SourcePositionResolver} — talks to
 * javac and to {@code javax.lang.model}. Everything below it — every formatter and every platform
 * renderer — reads only this type, and so can be exercised without a compiler in the loop.
 *
 * <p>Names are <em>precomputed</em> rather than derived on demand, because deriving them needs the
 * element hierarchy and therefore javac. The five name forms are the ones the renderers actually
 * use; they are produced by {@code ElementNaming} at snapshot time and are the single source of
 * truth afterwards.
 *
 * <p>The {@code @AI...} annotation instances are carried as-is. They are ordinary
 * {@link Annotation} proxies whose members are Strings, enums, primitives, and arrays of those —
 * reading them needs no compiler. The one exception is a {@code Class}-valued member
 * ({@code AISunset.replacement()}), which throws {@code MirroredTypeException} during annotation
 * processing; those are resolved to their type name at snapshot time and read back via
 * {@link #typeMember}.
 *
 * <p>Equality is by {@link #path()} and {@link #kind()} — a value identity, not javac's reference
 * identity. That is what lets a granular-rules map key on an element without holding the compiler's
 * object graph alive after the round that produced it.
 */
public final class TaggedElement {

    private final String path;
    private final String qualifiedName;
    private final String simpleName;
    private final String displayName;
    private final String granularQName;
    private final ElementTag kind;
    private final Map<Class<? extends Annotation>, Annotation> annotations;
    private final Map<String, String> typeMembers;
    private final String signature;

    /**
     * The class or package this element's granular rules are filed under, or {@code null} when the
     * element <em>is</em> its own owner. Null rather than {@code this} so equality and hashing stay
     * finite — {@link #owner()} resolves it.
     */
    private final @Nullable TaggedElement owner;

    private TaggedElement(Builder b) {
        this.path = b.path;
        this.qualifiedName = b.qualifiedName;
        this.simpleName = b.simpleName;
        this.displayName = b.displayName;
        this.granularQName = b.granularQName;
        this.kind = b.kind;
        this.annotations = b.annotations.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(b.annotations));
        this.typeMembers = b.typeMembers.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(b.typeMembers));
        this.owner = b.owner;
        this.signature = b.signature;
    }

    /**
     * The element's structural shape — a method's parameter and return types, a type's visible
     * member set — as a compiler-free string, or {@code ""} when it was not captured.
     *
     * <p>Read only by the opt-in enforcing mode, which compares it against a committed baseline
     * (<a href="https://github.com/PIsberg/vibetags/issues/284">issue #284</a>). Captured in the
     * collector, because it needs the javac model and the rendering half must stay compiler-free.
     */
    public String signature() {
        return signature;
    }

    /**
     * Fully-qualified path, with the enclosing type prepended for members and a {@code #} separator
     * for parameters (e.g. {@code com.example.Foo.export(java.lang.String)#filePath}). The stable
     * identity used in generated output and in the build fingerprint.
     */
    public String path() {
        return path;
    }

    /** The element's own fully-qualified name, without any enclosing-type prefix. */
    public String qualifiedName() {
        return qualifiedName;
    }

    /** The element's simple name. */
    public String simpleName() {
        return simpleName;
    }

    /** Short display name for link text: {@code EnclosingSimpleName.member} for members. */
    public String displayName() {
        return displayName;
    }

    /** Granular rule filename stem: the FQN with every non-{@code [A-Za-z0-9-]} character hyphenated. */
    public String granularQName() {
        return granularQName;
    }

    /** What kind of element this is. */
    public ElementTag kind() {
        return kind;
    }

    /** The class or package this element's granular rules file under; itself for a type or package. */
    public TaggedElement owner() {
        return owner != null ? owner : this;
    }

    /** True when this element is its own granular-rules owner (a type or a package). */
    public boolean isOwner() {
        return owner == null;
    }

    /** The {@code @AI...} annotation of the given type carried by this element, or {@code null}. */
    public <A extends Annotation> @Nullable A annotation(Class<A> type) {
        return type.cast(annotations.get(type));
    }

    /** True when this element carries the given {@code @AI...} annotation. */
    public boolean has(Class<? extends Annotation> type) {
        return annotations.containsKey(type);
    }

    /**
     * A {@code Class}-valued annotation member resolved to its type name at snapshot time, or
     * {@code fallback} when it was not recorded. The key is {@code SimpleAnnotationName.member},
     * e.g. {@code AISunset.replacement}.
     */
    public String typeMember(String key, String fallback) {
        return typeMembers.getOrDefault(key, fallback);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaggedElement other)) {
            return false;
        }
        return kind == other.kind && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, kind);
    }

    /** The qualified name, so string concatenation reads like the {@code Element} it replaced. */
    @Override
    public String toString() {
        return qualifiedName;
    }

    /** Starts a builder; {@code path} is the value identity and cannot be changed afterwards. */
    public static Builder builder(String path) {
        return new Builder(path);
    }

    /**
     * Accumulates one element's snapshot. The collector fills a builder per element across all
     * annotation buckets before materializing, so an element carrying five annotations still
     * produces one {@code TaggedElement} shared by all five buckets.
     */
    public static final class Builder {
        private final String path;
        private String qualifiedName = "";
        private String simpleName = "";
        private String displayName = "";
        private String granularQName = "";
        private ElementTag kind = ElementTag.OTHER;
        private final Map<Class<? extends Annotation>, Annotation> annotations = new LinkedHashMap<>();
        private final Map<String, String> typeMembers = new LinkedHashMap<>();
        private String signature = "";
        private @Nullable TaggedElement owner;

        private Builder(String path) {
            this.path = path;
        }

        public Builder names(String qualifiedName, String simpleName, String displayName, String granularQName) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.displayName = displayName;
            this.granularQName = granularQName;
            return this;
        }

        public Builder kind(ElementTag kind) {
            this.kind = kind;
            return this;
        }

        /** Sets the owning type/package; pass {@code null} when the element owns itself. */
        public Builder owner(@Nullable TaggedElement owner) {
            this.owner = owner;
            return this;
        }

        /** Records an annotation instance. A {@code null} value is ignored, so callers need no guard. */
        public <A extends Annotation> Builder annotation(Class<A> type, @Nullable A value) {
            if (value != null) {
                annotations.put(type, value);
            }
            return this;
        }

        /** Records the element's structural signature for the enforcing mode; empty when unknown. */
        public Builder signature(@Nullable String signature) {
            this.signature = signature == null ? "" : signature;
            return this;
        }

        /** Records a {@code Class}-valued member already resolved to its type name. */
        public Builder typeMember(String key, String typeName) {
            typeMembers.put(key, typeName);
            return this;
        }

        public TaggedElement build() {
            return new TaggedElement(this);
        }
    }
}
