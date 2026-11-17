package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which element a file glob may stand for, and the file names it is derived from.
 *
 * <p>{@code @AILocked} and {@code @AIIgnore} write a glob for the annotated type's file. Only the
 * type the file is named after may become one: a glob for a member, a nested type (#926) or a
 * second top-level type (#940) covers every unmarked line of that file as well. Each case below
 * was seen red against a mutant that drops the check it pins.
 */
class ElementFilesTest {

    /** The one line that names the code under test, so moving it changes nothing else here. */
    private static boolean owns(TaggedElement element) {
        return ElementFiles.ownsSourceFile(element);
    }

    private static TaggedElement.Builder type(String simpleName, ElementTag kind) {
        return TaggedElement.builder("com.acme." + simpleName)
            .names("com.acme." + simpleName, simpleName, simpleName, "com-acme-" + simpleName)
            .kind(kind);
    }

    @Test
    void aTopLevelTypeOwnsTheFileNamedAfterIt() {
        assertTrue(owns(type("Ledger", ElementTag.CLASS).build()));
        assertTrue(owns(type("Api", ElementTag.INTERFACE).build()));
        assertTrue(owns(type("Mode", ElementTag.ENUM).build()));
        assertTrue(owns(type("Point", ElementTag.RECORD).build()));
        assertTrue(owns(type("Marker", ElementTag.ANNOTATION_TYPE).build()));
    }

    @Test
    void aTypeInAnotherLanguagesFileOwnsIt() {
        assertTrue(owns(type("Invoice", ElementTag.CLASS).sourceFile("Invoice.kt").build()));
        assertTrue(owns(type("Report", ElementTag.CLASS).sourceFile("Report.groovy").build()));
    }

    @Test
    void aNestedTypeDoesNotOwnItsOuterTypesFile() {
        assertFalse(owns(type("Entry", ElementTag.CLASS).fileStem("Ledger").build()));
    }

    @Test
    void aNestedTypeIsRefusedOnItsStemEvenWhenTheFileNameMatches() {
        // Not a state the collector records today; pinned so the stem check is not dropped as
        // redundant with the file-name check.
        assertFalse(owns(type("Entry", ElementTag.CLASS).fileStem("Ledger").sourceFile("Entry.java").build()));
    }

    @Test
    void aSecondTopLevelTypeDoesNotOwnTheFileItIsDeclaredIn() {
        assertFalse(owns(type("Helper", ElementTag.CLASS).sourceFile("Ledger.java").build()));
    }

    @Test
    void aMemberOrPackageNeverOwnsAFile() {
        assertFalse(owns(type("export", ElementTag.METHOD).build()));
        assertFalse(owns(type("amount", ElementTag.FIELD).build()));
        assertFalse(owns(type("acme", ElementTag.PACKAGE).build()));
        assertFalse(owns(type("Thing", ElementTag.UNKNOWN).build()));
    }

    @Test
    void onlyTheLastDotStartsTheExtension() {
        assertFalse(owns(type("Ledger", ElementTag.CLASS).sourceFile("Ledger.generated.java").build()));
    }

    @Test
    void aFileNameWithNoExtensionIsComparedWhole() {
        assertTrue(owns(type("Ledger", ElementTag.CLASS).sourceFile("Ledger").build()));
    }

    @Test
    void anUnsetFileStemIsTheSimpleName() {
        assertEquals("Ledger", type("Ledger", ElementTag.CLASS).build().fileStem());
        assertEquals("Ledger", type("Ledger", ElementTag.CLASS).fileStem("").build().fileStem());
    }

    @Test
    void anUnsetSourceFileIsTheStemWithJavaExtension() {
        assertEquals("Ledger.java", type("Ledger", ElementTag.CLASS).build().sourceFile());
        assertEquals("Ledger.java", type("Entry", ElementTag.CLASS).fileStem("Ledger").build().sourceFile(),
            "a nested type's default file is its outer type's");
        assertEquals("Ledger.java", type("Ledger", ElementTag.CLASS).sourceFile("").build().sourceFile());
    }

    @Test
    void aRecordedSourceFileIsKeptAsGiven() {
        assertEquals("Invoice.kt", type("Invoice", ElementTag.CLASS).sourceFile("Invoice.kt").build().sourceFile());
    }
}
