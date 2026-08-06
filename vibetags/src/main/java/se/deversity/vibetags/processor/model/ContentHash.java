package se.deversity.vibetags.processor.model;

/**
 * The 8-hex-character content hash VibeTags folds into its build fingerprint.
 *
 * <p>Deliberately {@link String#hashCode()} rather than a cryptographic digest: this guards against
 * <em>accidental</em> change (an edited config, a regenerated body), never against a forged one, and
 * it has to run once per file on every compile. The width is fixed at 8 because the fingerprint
 * stored in {@code .vibetags-cache} is parsed by offset.
 *
 * <p>Changing the algorithm or the width invalidates every consumer's cached fingerprint, causing
 * one extra regeneration on first compile after upgrade. That is acceptable at a release boundary
 * and nowhere else.
 */
public final class ContentHash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ContentHash() {}

    /** The 8-hex-character hash of {@code s}. */
    public static String of(String s) {
        int h = s.hashCode();
        char[] out = new char[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = HEX[h & 0xF];
            h >>>= 4;
        }
        return new String(out);
    }
}
