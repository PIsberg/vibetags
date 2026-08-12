package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every ignore file has to say which tool it is for, and say something different from its siblings.
 *
 * <p>The fifteen {@code *_IGNORE} services share one renderer, and the only thing that varies
 * between their outputs is one word in the header: {@code "# Cursor-specific exclusion list."}.
 * That word came from a fifteen-arm switch nothing asserted — PIT could replace any of its returns
 * with the empty string and every test still passed, which means the file could have shipped
 * saying {@code "# -specific exclusion list."}, or Cody's file could have claimed to be Cursor's,
 * without a failure anywhere.
 *
 * <p>The two properties here are what the switch is actually for, and neither needs a table of
 * expected strings: the name must be there, and it must identify one platform rather than another.
 * The default arm is treated as a miss on purpose — a new {@code *_IGNORE} platform falling
 * through to the generic label is the drift this catches.
 */
class IgnoreFileHeaderNamesTest {

    private static final RenderingContext CONTEXT =
        new RenderingContext("Test Project", "# Generated Header\n", Set.of());

    /** The header line's shape; the name is whatever sits between these. */
    private static final String PREFIX = "# ";
    private static final String SUFFIX = "-specific exclusion list.";

    /** The label {@code getPlatformSpecificName} falls back to when a platform has no arm. */
    private static final String FALLBACK = "AI Platform";

    static Stream<Platform> ignorePlatforms() {
        return Stream.of(Platform.values()).filter(p -> p.name().endsWith("_IGNORE"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ignorePlatforms")
    @DisplayName("names the tool it is for, and does not fall through to the generic label")
    void headerNamesThePlatform(Platform platform) {
        String name = headerName(platform);

        assertFalse(name.isBlank(),
            platform + " renders \"" + PREFIX + name + SUFFIX + "\" — the header lost the tool's "
                + "name, so the generated file no longer says what it excludes things from");
        assertFalse(FALLBACK.equals(name),
            platform + " fell through to the generic \"" + FALLBACK + "\" label — add an arm for "
                + "it in IgnoreFileRenderer.getPlatformSpecificName");
    }

    @Test
    @DisplayName("no two ignore platforms claim the same name")
    void namesAreDistinct() {
        Map<String, Platform> byName = new LinkedHashMap<>();
        ignorePlatforms().forEach(platform -> {
            Platform clash = byName.put(headerName(platform), platform);
            assertTrue(clash == null,
                platform + " and " + clash + " both render \"" + headerName(platform) + "\" — one "
                    + "of them is reading the wrong switch arm, and its file will name the other "
                    + "tool");
        });
        assertEquals((int) ignorePlatforms().count(), byName.size());
    }

    /** The word between {@code "# "} and {@code "-specific exclusion list."} in the rendering. */
    private static String headerName(Platform platform) {
        String rendered = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotation(), platform, CONTEXT);
        assertTrue(rendered != null && !rendered.isEmpty(),
            platform + " rendered nothing for a model that carries an @AIIgnore element");

        for (String line : rendered.split("\n", -1)) {
            if (line.startsWith(PREFIX) && line.endsWith(SUFFIX)) {
                return line.substring(PREFIX.length(), line.length() - SUFFIX.length());
            }
        }
        throw new AssertionError(
            platform + " rendered no \"" + PREFIX + "<tool>" + SUFFIX + "\" line at all:\n"
                + rendered);
    }
}
