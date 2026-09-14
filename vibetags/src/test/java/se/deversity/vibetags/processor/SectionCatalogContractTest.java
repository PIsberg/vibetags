package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SectionCatalog} is the one lookup every catalog-driven renderer trusts for its section
 * headers, and the mutation report showed 7 of its 9 mutants surviving: {@code header} could stop
 * honouring a platform override, return the empty string for a headerless section, or disagree
 * with {@code isHeaderless}, all without a failure.
 *
 * <p>The three behaviours a caller depends on, each pinned with a platform/key pair read from the
 * catalog's own tables: null exactly for headerless pairs, the platform override when one is
 * registered, and the shared default otherwise.
 */
class SectionCatalogContractTest {

    @Test
    @DisplayName("header() is null exactly when isHeaderless() says so, for every pair")
    void headerAndIsHeaderlessAgreeEverywhere() {
        for (Platform platform : Platform.values()) {
            for (SectionCatalog.Key key : SectionCatalog.Key.values()) {
                boolean headerless = SectionCatalog.isHeaderless(platform, key);
                String header = SectionCatalog.header(platform, key);
                assertEquals(headerless, header == null,
                    platform + "/" + key + ": a renderer asks isHeaderless() before printing and "
                        + "header() for the text — the two answers must describe the same catalog");
            }
        }
    }

    @Test
    @DisplayName("Windsurf renders THREAD_SAFE headerless but keeps its AUDIT header")
    void headerlessIsPerPairNotPerPlatform() {
        assertTrue(SectionCatalog.isHeaderless(Platform.WINDSURF, SectionCatalog.Key.THREAD_SAFE),
            "the Windsurf THREAD_SAFE section is registered headerless");
        assertNull(SectionCatalog.header(Platform.WINDSURF, SectionCatalog.Key.THREAD_SAFE),
            "a headerless section must yield no text at all — the empty string would still be "
                + "appended and ship a stray blank line");
        assertFalse(SectionCatalog.isHeaderless(Platform.WINDSURF, SectionCatalog.Key.AUDIT),
            "headerless is a per-pair property; Windsurf's audit section keeps its heading");
        assertNotNull(SectionCatalog.header(Platform.WINDSURF, SectionCatalog.Key.AUDIT));
    }

    @Test
    @DisplayName("a registered platform override wins over the shared default")
    void overrideWinsOverDefault() {
        String gemini = SectionCatalog.header(Platform.GEMINI, SectionCatalog.Key.SANDBOX_ONLY);
        String windsurf = SectionCatalog.header(Platform.WINDSURF, SectionCatalog.Key.SANDBOX_ONLY);

        assertNotNull(gemini);
        assertNotNull(windsurf);
        assertTrue(gemini.startsWith("\n## SANDBOX & TEST HARNESS EXCLUSION"),
            "Gemini registers its own emoji-free heading for this section: " + gemini);
        assertTrue(windsurf.contains("🛡️ SANDBOX & TEST HARNESS EXCLUSION"),
            "Windsurf has no SANDBOX_ONLY override and must fall back to the shared default: "
                + windsurf);
    }

    @Test
    @DisplayName("GEMINI.md and gemini_instructions.md look up the same wording for every section")
    void bothGeminiFilesShareOneWording() {
        // GEMINI.md's full render asks for Platform.GEMINI and its collapsed render for
        // Platform.GEMINI_MD; when only the first was registered, the collapsed file printed the
        // shared headings (#721).
        for (SectionCatalog.Key key : SectionCatalog.Key.values()) {
            assertEquals(SectionCatalog.header(Platform.GEMINI, key), SectionCatalog.header(Platform.GEMINI_MD, key),
                key + ": the two Gemini platforms must not drift apart");
        }
        String audit = SectionCatalog.header(Platform.GEMINI_MD, SectionCatalog.Key.AUDIT);
        assertNotNull(audit);
        assertTrue(audit.startsWith("\n## CONTINUOUS AUDIT REQUIREMENTS\n"),
            "GEMINI_MD must resolve Gemini's override, not the shared default: " + audit);
    }

    @Test
    @DisplayName("every header opens with its own newline, for every platform and key")
    void everyHeaderOpensWithANewline() {
        // A renderer appends a header straight after the previous section's last bullet, which
        // ends in a single newline. A header that does not open with one of its own prints
        // directly under that bullet; Gemini's IGNORE, DRAFT and PRIVACY did (#723).
        for (Platform platform : Platform.values()) {
            for (SectionCatalog.Key key : SectionCatalog.Key.values()) {
                String header = SectionCatalog.header(platform, key);
                if (header != null) {
                    assertTrue(header.startsWith("\n"),
                        platform + "/" + key + " must open with a newline: " + header);
                }
            }
        }
    }
}
