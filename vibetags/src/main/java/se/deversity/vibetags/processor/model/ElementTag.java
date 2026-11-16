package se.deversity.vibetags.processor.model;

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
 *
 * <p>Constants only. Looking a tag up by name and classifying it as a class or an interface live in
 * {@code internal.content.ElementKinds}.
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
    UNKNOWN
}
