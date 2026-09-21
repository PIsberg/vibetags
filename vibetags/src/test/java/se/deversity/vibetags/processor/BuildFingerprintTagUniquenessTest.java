package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every annotation's fingerprint tag is its own.
 *
 * <p>{@code BuildFingerprint} folds each annotation's contents into the hash under a short tag,
 * {@code appendAnnotationSet(sb, "L", model.locked(), ...)}. The {@code add-annotation} skill says
 * those tags must be unique, and until this test nothing checked it: {@code "LB"} was used by both
 * {@code legacyBridge} and {@code loadBearing}
 * (<a href="https://github.com/PIsberg/vibetags/issues/765">issue #765</a>).
 *
 * <p>Two annotations sharing a tag was harmless, because the sections are positional: each
 * call appends at a fixed point in a fixed order, so the tag is a label in the hashed string rather
 * than a key anything looks up. It stops being harmless the moment anything keys on the tag, or the
 * order stops being fixed, and the failure then is a fingerprint that cannot tell two different
 * builds apart, which is the quietest kind of wrong this processor can produce.
 *
 * <p>That collision was first allowed here by name, because fixing it changes the hashed string and
 * so invalidates every consumer's {@code .vibetags-cache} once. It was fixed right after a release was
 * prepared ({@code loadBearing} is now {@code "LDB"}), the cost being one missed short-circuit
 * per consumer, and {@link #KNOWN_COLLISIONS} is empty: the rule is enforced
 * outright. The map stays so that a future collision that truly cannot be fixed at once has
 * somewhere to be recorded as a decision rather than a relaxed rule.
 */
@DisplayName("Fingerprint tags are unique per annotation")
class BuildFingerprintTagUniquenessTest {

    /**
     * Collisions that exist today and are deliberately not fixed yet, tag to reason.
     *
     * <p>An entry here is a decision somebody has to remove, not a rule somebody quietly relaxed.
     */
    private static final Map<String, String> KNOWN_COLLISIONS = Map.of();

    /** {@code appendAnnotationSet(sb, "TAG", model.accessor(), ...)}. */
    private static final Pattern CALL = Pattern.compile(
        "appendAnnotationSet\\(\\s*sb\\s*,\\s*\"([^\"]+)\"\\s*,\\s*model\\.([A-Za-z]+)\\(\\)");

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path SOURCE = Paths.get("").toAbsolutePath()
        .resolve("src/main/java/se/deversity/vibetags/processor/internal/BuildFingerprint.java");

    @Test
    @DisplayName("no two annotations share a tag, beyond the collisions recorded here")
    void everyFingerprintTagIsUniqueOrAKnownCollision() {
        Map<String, List<String>> byTag = tagsToAccessors();

        Map<String, List<String>> collisions = new LinkedHashMap<>();
        byTag.forEach((tag, accessors) -> {
            if (accessors.size() > 1) {
                collisions.put(tag, accessors);
            }
        });

        Set<String> unexpected = new LinkedHashSet<>(collisions.keySet());
        unexpected.removeAll(KNOWN_COLLISIONS.keySet());
        assertEquals(Set.of(), unexpected,
            "two annotations share a fingerprint tag: " + collisions
                + ". The add-annotation skill requires tags to be unique. A tag is a label in the "
                + "hashed string today, so this is not yet a wrong fingerprint, but it is the rule "
                + "being broken silently. Pick a free tag, or record it in KNOWN_COLLISIONS with "
                + "the reason it cannot be fixed now.");
    }

    /**
     * The allowance cannot outlive the collision it excuses. A stale entry here would silently
     * permit a future collision on the same tag, which is the failure this file exists to prevent.
     */
    @Test
    @DisplayName("every recorded collision still exists")
    void noKnownCollisionIsStale() {
        Map<String, List<String>> byTag = tagsToAccessors();

        KNOWN_COLLISIONS.forEach((tag, reason) -> {
            List<String> accessors = byTag.getOrDefault(tag, List.of());
            assertTrue(accessors.size() > 1,
                "KNOWN_COLLISIONS still allows the tag " + tag + ", but it is no longer shared "
                    + "(used by " + accessors + "). Delete the entry: leaving it in place lets a "
                    + "new collision on that tag pass unnoticed.");
        });
    }

    /** Every annotation in the registry is folded in, and each call is a tag plus an accessor. */
    @Test
    @DisplayName("one fingerprint call per registered annotation")
    void everyRegisteredAnnotationIsHashed() {
        Map<String, List<String>> byTag = tagsToAccessors();
        int calls = byTag.values().stream().mapToInt(List::size).sum();

        assertEquals(se.deversity.vibetags.processor.model.GuardrailAnnotations.ALL.size(), calls,
            "BuildFingerprint folds in " + calls + " annotation sets but the registry has "
                + se.deversity.vibetags.processor.model.GuardrailAnnotations.ALL.size()
                + ". Anything that becomes generated content reaches BuildFingerprint (invariant "
                + "12); an annotation missing here changes the output without changing the hash, "
                + "so the write cache serves the old file.");
    }

    /** Tag to the accessors that use it, read from the source rather than from behaviour. */
    private static Map<String, List<String>> tagsToAccessors() {
        Map<String, List<String>> byTag = new LinkedHashMap<>();
        Matcher m = CALL.matcher(read());
        while (m.find()) {
            byTag.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(m.group(2));
        }
        assertTrue(byTag.size() > 30,
            "only " + byTag.size() + " fingerprint tags were parsed out of " + SOURCE
                + ". The call shape this test matches has probably changed, and a pattern that "
                + "matches nothing passes every assertion below it.");
        return byTag;
    }

    private static String read() {
        try {
            return Files.readString(SOURCE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }
}
