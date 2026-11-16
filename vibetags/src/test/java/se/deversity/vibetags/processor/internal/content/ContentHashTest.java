package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.model.ContentHash;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact output of the fingerprint hash.
 *
 * <p>The expected values are literals computed outside this code base
 * ({@code String.format("%08x", s.hashCode())} in jshell), not derived from the code under test.
 * A changed digit invalidates every consumer's {@code .vibetags-cache} fingerprint, which the
 * class javadoc allows only at a release boundary, so any change here has to be deliberate.
 */
class ContentHashTest {

    /** The one line that names the code under test, so moving it changes nothing else here. */
    private static String hash(String s) {
        return ContentHash.of(s);
    }

    @Test
    void emptyStringHashesToEightZeros() {
        assertEquals("00000000", hash(""));
    }

    @Test
    void smallHashesArePaddedToEightCharacters() {
        assertEquals("00000061", hash("a"));
        assertEquals("00017862", hash("abc"));
        assertEquals("000005a4", hash("-1"));
    }

    @Test
    void aNegativeHashCodeIsRenderedUnsigned() {
        // "polygenelubricants".hashCode() == Integer.MIN_VALUE: the sign bit lands in the top digit
        // as an "8", with no minus sign and no widening to 16 digits. (Whether the loop shifts
        // with >>> or >> does not matter: exactly eight nibbles are read, so the bits a signed
        // shift fills in are never consumed. Measured: that mutant leaves this class green.)
        assertEquals("80000000", hash("polygenelubricants"));
    }

    @Test
    void allEightDigitsAreUsedAndLowerCase() {
        assertEquals("6aefe2c4", hash("hello world"));
    }

    @Test
    void stringsWithEqualHashCodesCollide() {
        // Documents the trade-off the class javadoc chose: this guards against accidental change,
        // not a forged one.
        assertEquals("00000840", hash("Aa"));
        assertEquals(hash("Aa"), hash("BB"));
    }
}
