package se.deversity.vibetags.ksp.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a Kotlin declaration ends, read from its text (#757). Line numbers are 1-based. */
class KotlinExtentTest {

    private static final String SOURCE = String.join("\n",
        "package p",                                                  // 1
        "",                                                           // 2
        "@AILocked(reason = \"a (paren) and a } brace\")",            // 3
        "@get:JvmName(\"x\")",                                        // 4
        "class Vault(",                                               // 5
        "    @AILocked val id: Map<String, Int>,",                    // 6
        "    val name: String = \"}\",",                              // 7
        ") : Base(), Other {",                                        // 8
        "    fun oneLine(): Int = 1",                                 // 9
        "    fun block(x: Int): Int {",                               // 10
        "        val s = \"${x + 1} { \"",                            // 11
        "        /* a } in /* a nested */ comment */",                // 12
        "        return x",                                           // 13
        "    }",                                                      // 14
        "    fun expression() =",                                     // 15
        "        listOf(1)",                                          // 16
        "            .map { it }",                                    // 17
        "    val computed: Int?",                                     // 18
        "        get() = null",                                       // 19
        "    val plain: Int? = null",                                 // 20
        "    val lazy by lazy {",                                     // 21
        "        1",                                                  // 22
        "    }",                                                      // 23
        "    abstract fun area(): Double",                            // 24
        "}",                                                          // 25
        "",                                                           // 26
        "enum class Color(val rgb: Int) {",                           // 27
        "    RED(1),",                                                // 28
        "    GREEN(",                                                 // 29
        "        2",                                                  // 30
        "    );",                                                     // 31
        "}",                                                          // 32
        "val raw = \"\"\"",                                           // 33
        "  { unbalanced",                                             // 34
        "\"\"\"");                                                    // 35

    private final KotlinExtent extent = new KotlinExtent(SOURCE);

    @Test
    void aClassEndsWhereItsBodyCloses() {
        assertEquals(25, extent.end(5, false));
    }

    @Test
    void theStartIncludesTheAnnotationLinesAbove() {
        assertEquals(3, extent.start(5));
        assertEquals(9, extent.start(9), "the line above is code, not an annotation");
    }

    @Test
    void aConstructorPropertyEndsAtItsCommaNotInsideItsGenerics() {
        assertEquals(6, extent.end(6, true));
        assertEquals(7, extent.end(7, true), "a brace inside a string does not count");
    }

    @Test
    void oneLineAndBlockFunctions() {
        assertEquals(9, extent.end(9, false));
        assertEquals(14, extent.end(10, false), "templates and nested comments are skipped");
    }

    @Test
    void anExpressionBodyContinuesAcrossOperatorLines() {
        assertEquals(17, extent.end(15, false));
    }

    @Test
    void aPropertyWithAnAccessorOnTheNextLine() {
        assertEquals(19, extent.end(18, false));
        assertEquals(20, extent.end(20, false), "a nullable type at the end of a line is complete");
    }

    @Test
    void aDelegateBodyIsPartOfTheProperty() {
        assertEquals(23, extent.end(21, false));
    }

    @Test
    void aMemberNeverRunsIntoItsClassesClosingBrace() {
        assertEquals(24, extent.end(24, false));
    }

    @Test
    void enumEntriesEndAtTheirCommaOrSemicolon() {
        assertEquals(28, extent.end(28, true));
        assertEquals(31, extent.end(29, true));
    }

    @Test
    void aRawStringHidesItsBraces() {
        assertEquals(35, extent.end(33, false));
    }

    @Test
    void annotationOnlyLines() {
        assertTrue(KotlinExtent.isAnnotationOnly("@AILocked(reason = \"a ) paren\")"));
        assertTrue(KotlinExtent.isAnnotationOnly("@get:JvmName(\"x\") @Deprecated(\"y\")"));
        assertTrue(KotlinExtent.isAnnotationOnly("@kotlin.jvm.JvmStatic"));
        assertFalse(KotlinExtent.isAnnotationOnly("@AILocked fun f() = 1"));
        assertFalse(KotlinExtent.isAnnotationOnly("fun f()"));
        assertFalse(KotlinExtent.isAnnotationOnly(""));
    }

    @Test
    void outOfRangeLinesAreReturnedAsGiven() {
        assertEquals(99, extent.end(99, false));
        assertEquals(35, extent.start(99));
    }
}
