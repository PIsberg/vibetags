package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this element is duplicated at named sites which must move together.
 *
 * <p>The element is free to change — the failure mode is a <em>partial</em> change that silently
 * desyncs a mirror no compiler checks. {@link AIContract} freezes one signature so it cannot change
 * at all, and {@code AISchemaSafe} is storage-specific; neither expresses "edit A ⇒ you must also
 * edit B".
 *
 * <p>Mirrors routinely point outside the compilation unit — a {@code build.gradle} version, a
 * README badge, a TypeScript twin — so VibeTags can only <em>name</em> them, not verify them the
 * way {@link AIImmutable} can be checked against final fields. Name a parity test or CI check in
 * {@link #enforcedBy()} when one exists; that is the difference between a note and a guarantee.
 *
 * <pre>{@code
 * @AIKeepInSync(
 *     mirrors = {"pom.xml:<version>", "README.md badge", "docs/CHANGELOG.md"},
 *     reason = "The release version is asserted in three places and drifts silently",
 *     enforcedBy = "ProjectFactsConsistencyTest")
 * public static final String VERSION = "1.0.0";
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface AIKeepInSync {

    /**
     * The sites that must move together with this element — file paths, fully-qualified names, or
     * a path plus a locator. Required, and must be non-empty: an element with no mirrors is not
     * mirrored, and the processor warns about it.
     * @return the mirror sites
     */
    String[] mirrors();

    /**
     * Why the duplication exists and what desync would break. Without it an agent updating one
     * side has no way to judge whether the others are genuinely equivalent.
     * @return the rationale, or empty string if unspecified
     */
    String reason() default "";

    /**
     * The parity test or CI check that fails when the mirrors diverge, if one exists. Naming it
     * tells an agent the drift will be caught — and, when absent, that it will not be.
     * @return the enforcing check, or empty string if the mirrors are unenforced
     */
    String enforcedBy() default "";
}
