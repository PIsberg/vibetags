package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Every platform must resolve to a renderer.
 *
 * <p>{@code findRenderer} was a switch listing twelve of the thirteen {@code *_GRANULAR} constants.
 * {@code GEMINI_GRANULAR} was missing, so {@code getRenderer} threw
 * {@code IllegalArgumentException: Unsupported platform} for it. Nothing ever failed, because
 * {@code GuardrailContentBuilder} filters {@code *_granular} service keys out before the registry
 * is asked — the defect was one refactor away from being reachable and nothing would have caught it.
 *
 * <p>A hand-maintained list of constants beside an enum is the shape this repository keeps getting
 * bitten by: four renderers stopped rendering the annotations added after them, and a test fixture
 * hand-listed 39 of 44 annotations. The registry now derives the granular case from the platform's
 * own name, so the thirteenth constant needed no edit and the fourteenth will not either.
 */
class PlatformRendererRegistryCoverageTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Platform.class)
    @DisplayName("resolves to a renderer rather than throwing")
    void everyPlatformResolves(Platform platform) {
        PlatformRenderer renderer = assertDoesNotThrow(() -> PlatformRendererRegistry.getRenderer(platform),
            platform + " has no arm in PlatformRendererRegistry.findRenderer. Nothing may reach it "
                + "today, but a platform the registry cannot answer for is a crash waiting for the "
                + "call site that stops filtering it out");
        assertNotNull(renderer, platform + " resolved to null");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Platform.class, names = ".*_GRANULAR", mode = EnumSource.Mode.MATCH_ALL)
    @DisplayName("every granular platform resolves to the one granular renderer")
    void granularPlatformsShareTheGranularRenderer(Platform platform) {
        assertSame(PlatformRendererRegistry.granularRenderer(),
            PlatformRendererRegistry.getRenderer(platform),
            platform + " must resolve to the shared GranularRenderer — granular output is written "
                + "per element by GranularRulesWriter, and a second renderer here would silently "
                + "produce a different file for one platform");
    }
}
