package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A granular rule's glob, and an ignore file's, names the file the code is in, in the language it
 * is written in (#939).
 *
 * <p>The glob was always {@code **}{@code /<Type>.java}. Under kapt and Groovy's stub generation
 * javac sees a generated {@code .java} stub, so a Kotlin class in {@code Invoice.kt} got a rule
 * scoped to {@code Invoice.java}, a file that does not exist, and Claude Code, Cursor, Copilot and
 * the other path-scoped platforms never loaded it. The stubs say which language they came from:
 * kapt keeps {@code @kotlin.Metadata} on every class, and a Groovy class implements
 * {@code groovy.lang.GroovyObject}. The KSP front end, which sees the real {@code .kt} file, is
 * covered in vibetags-ksp.
 *
 * <p>The two marker types are declared here as sources, which is all the processor reads of them.
 */
@Tag("e2e")
@DisplayName("Granular and ignore globs name the source file in its own language (#939)")
class GranularSourceFileGlobTest {

    private static final String KOTLIN_METADATA =
        "package kotlin;\npublic @interface Metadata { int k() default 1; }\n";
    private static final String GROOVY_OBJECT =
        "package groovy.lang;\npublic interface GroovyObject {}\n";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness granular(Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(".claude/rules"));
        h.touchOptIn(".cursorignore");
        h.addSource("kotlin.Metadata", KOTLIN_METADATA);
        h.addSource("groovy.lang.GroovyObject", GROOVY_OBJECT);
        return h;
    }

    private static String rule(ProcessorTestHarness h, String qualifiedName) throws IOException {
        return h.readFile(".claude/rules/" + qualifiedName.replace('.', '-') + ".md");
    }

    @Test
    @DisplayName("a kapt stub's class is scoped to its .kt file")
    void aKotlinClassIsScopedToItsKtFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.Invoice", """
            package com.example;
            @kotlin.Metadata(k = 1)
            public final class Invoice {
                @se.deversity.vibetags.annotations.AILocked(reason = "wire format")
                public long total() { return 0L; }
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Invoice");
        assertTrue(rule.contains("**/Invoice.kt"), "the rule must load when Invoice.kt is opened:\n" + rule);
        assertFalse(rule.contains("Invoice.java"), "no Invoice.java exists in a Kotlin module:\n" + rule);
    }

    @Test
    @DisplayName("a Kotlin file facade (top-level functions) is scoped to the file it is named after")
    void aKotlinFileFacadeIsScopedToItsFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.BillingKt", """
            package com.example;
            @kotlin.Metadata(k = 2)
            public final class BillingKt {
                @se.deversity.vibetags.annotations.AILocked(reason = "settlement order")
                public static void settle() {}
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.BillingKt");
        assertTrue(rule.contains("**/Billing.kt"),
            "top-level functions of Billing.kt compile into BillingKt; the rule must name Billing.kt:\n" + rule);
    }

    @Test
    @DisplayName("a Groovy stub's class is scoped to its .groovy file")
    void aGroovyClassIsScopedToItsGroovyFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.Report", """
            package com.example;
            public class Report implements groovy.lang.GroovyObject {
                @se.deversity.vibetags.annotations.AILocked(reason = "audited layout")
                public void render() {}
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Report");
        assertTrue(rule.contains("**/Report.groovy"), "the rule must load when Report.groovy is opened:\n" + rule);
        assertFalse(rule.contains("Report.java"), rule);
    }

    /**
     * kapt's stub has the type's name, and the {@code .kapt_metadata} kapt writes beside it names
     * the file it came from (#955). The layout is the one kapt 2.4.10 was measured writing; see
     * {@code StubSourceNamesTest}.
     */
    private static void kaptStub(ProcessorTestHarness h, Path root, String type, String body, String ktFile)
            throws IOException {
        String internal = "com/example/" + type;
        Path stub = h.writeSourceFile("build/tmp/kapt3/stubs/main/" + internal + ".java", body);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(internal);
            out.writeUTF(root.resolve("src/main/kotlin/com/example/" + ktFile).toString().replace('\\', '/'));
            out.writeBoolean(false);
            out.writeInt(0);
        }
        Files.write(stub.resolveSibling(type + ".kapt_metadata"), bytes.toByteArray());
    }

    @Test
    @DisplayName("a kapt class in a differently named file is scoped to that file (#955)")
    void aKotlinClassInAnotherFileNamesThatFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        kaptStub(h, root, "Invoice", """
            package com.example;
            @kotlin.Metadata(k = 1)
            public final class Invoice {
                @se.deversity.vibetags.annotations.AILocked(reason = "wire format")
                public long total() { return 0L; }
            }
            """, "Billing.kt");

        h.compile();

        String rule = rule(h, "com.example.Invoice");
        assertTrue(rule.contains("**/Billing.kt"), "Invoice is declared in Billing.kt:\n" + rule);
        assertFalse(rule.contains("Invoice.kt"), "no Invoice.kt exists:\n" + rule);
    }

    @Test
    @DisplayName("a facade renamed by @file:JvmName is scoped to its file, not to the JVM name (#955)")
    void aRenamedFacadeNamesItsFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        kaptStub(h, root, "TypesFacade", """
            package com.example;
            @kotlin.Metadata(k = 2)
            public final class TypesFacade {
                @se.deversity.vibetags.annotations.AILocked(reason = "type table")
                public static void register() {}
            }
            """, "Types.kt");

        h.compile();

        String rule = rule(h, "com.example.TypesFacade");
        assertTrue(rule.contains("**/Types.kt"), "@file:JvmName(\"TypesFacade\") in Types.kt:\n" + rule);
        assertFalse(rule.contains("TypesFacade.kt"), rule);
    }

    @Test
    @DisplayName("a Groovy interface's stub, which carries no marker, is scoped to a .groovy file (#955)")
    void aGroovyInterfaceUnderGradlesStubDirectory(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.writeSourceFile("build/tmp/compileGroovy/groovy-java-stubs/com/example/Tracked.java", """
            package com.example;
            public interface Tracked {
                @se.deversity.vibetags.annotations.AILocked(reason = "tracking contract")
                String trackingId();
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Tracked");
        assertTrue(rule.contains("**/Tracked.groovy"), rule);
        assertFalse(rule.contains("Tracked.java"), "the .java file is Gradle's stub, gone after the build:\n" + rule);
    }

    @Test
    @DisplayName("a Groovy type in a differently named file is scoped to that file (#973)")
    void aGroovyTypeInAnotherFileNamesThatFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        // groovyc's stub names nothing, but the sources sit where Gradle's convention puts them.
        writeGroovy(root, "src/main/groovy/com/example/Shipping.groovy", """
            package com.example

            /** Declares two types, so neither is in a file named after it. */
            class Shipment {}

            @groovy.transform.CompileStatic
            final class Parcel {
                String trackingId() { 'p-1' }
            }
            """);
        h.writeSourceFile("build/tmp/compileGroovy/groovy-java-stubs/com/example/Parcel.java", """
            package com.example;
            public final class Parcel implements groovy.lang.GroovyObject {
                @se.deversity.vibetags.annotations.AILocked(reason = "tracking contract")
                public String trackingId() { return null; }
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Parcel");
        assertTrue(rule.contains("**/Shipping.groovy"), "Parcel is declared in Shipping.groovy:\n" + rule);
        assertFalse(rule.contains("Parcel.groovy"), "no Parcel.groovy exists:\n" + rule);
    }

    @Test
    @DisplayName("a test source set's Groovy type is looked up under src/test/groovy (#973)")
    void aGroovyTestTypeIsLookedUpInItsSourceSet(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        writeGroovy(root, "src/test/groovy/com/example/Fixtures.groovy", """
            package com.example

            interface Tracked {
                String trackingId()
            }
            """);
        h.writeSourceFile("build/tmp/compileTestGroovy/groovy-java-stubs/com/example/Tracked.java", """
            package com.example;
            public interface Tracked {
                @se.deversity.vibetags.annotations.AILocked(reason = "tracking contract")
                String trackingId();
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Tracked");
        assertTrue(rule.contains("**/Fixtures.groovy"), "Tracked is declared in src/test/groovy's Fixtures.groovy:\n" + rule);
    }

    @Test
    @DisplayName("a Groovy type keeps <Type>.groovy when no file, or more than one, declares it (#973)")
    void aGroovyTypeKeepsItsOwnNameWhenTheLookupIsNotSure(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        // A comment that starts a line with the declaration is not a declaration, and two files
        // that both seem to declare the type leave the answer open.
        writeGroovy(root, "src/main/groovy/com/example/Notes.groovy", """
            package com.example
            /*
            class Parcel was moved out of here
            */
            class Notes {}
            """);
        writeGroovy(root, "src/main/groovy/com/example/A.groovy", "package com.example\nclass Crate {}\n");
        writeGroovy(root, "src/main/groovy/com/example/B.groovy", "package com.example\nclass Crate {}\n");
        for (String type : new String[] {"Parcel", "Crate"}) {
            h.writeSourceFile("build/tmp/compileGroovy/groovy-java-stubs/com/example/" + type + ".java",
                "package com.example;\npublic final class " + type + " implements groovy.lang.GroovyObject {\n"
                    + "    @se.deversity.vibetags.annotations.AILocked(reason = \"r\")\n"
                    + "    public void run() {}\n}\n");
        }

        h.compile();

        assertTrue(rule(h, "com.example.Crate").contains("**/Crate.groovy"), rule(h, "com.example.Crate"));
        String parcel = rule(h, "com.example.Parcel");
        assertTrue(parcel.contains("**/Parcel.groovy"), "a block comment is not a declaration:\n" + parcel);
        assertFalse(parcel.contains("Notes.groovy"), parcel);
    }

    @Test
    @DisplayName("GMavenPlus's stubs are Groovy's too, interfaces included, and name their file (#973)")
    void gmavenPlusStubsNameTheirFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        // Measured with gmavenplus-plugin 4.2.1 and Groovy 5.0.2: generateStubs writes
        // target/generated-sources/groovy-stubs/main/<package>/<Type>.java, and an interface's stub
        // carries no Groovy marker.
        writeGroovy(root, "src/main/groovy/com/example/Shipping.groovy", """
            package com.example

            interface Tracked {
                String carrierId()
            }
            """);
        h.writeSourceFile("target/generated-sources/groovy-stubs/main/com/example/Tracked.java", """
            package com.example;
            public interface Tracked {
                @se.deversity.vibetags.annotations.AILocked(reason = "carrier contract")
                String carrierId();
            }
            """);

        h.compile();

        String rule = rule(h, "com.example.Tracked");
        assertTrue(rule.contains("**/Shipping.groovy"), "Tracked is declared in Shipping.groovy:\n" + rule);
        assertFalse(rule.contains("Tracked.java"), "the .java file is GMavenPlus's stub, removed by removeStubs:\n" + rule);
    }

    private static void writeGroovy(Path root, String relative, String source) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    @DisplayName("a Java type is unchanged, and a second top-level type is scoped to the file it is in")
    void javaIsUnchangedAndASecondaryTypeNamesItsFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.Ledger", """
            package com.example;
            public class Ledger {
                @se.deversity.vibetags.annotations.AILocked(reason = "balance invariant")
                public void post() {}
            }
            class LedgerIndex {
                @se.deversity.vibetags.annotations.AILocked(reason = "index order")
                void rebuild() {}
            }
            """);

        h.compile();

        assertTrue(rule(h, "com.example.Ledger").contains("**/Ledger.java"), rule(h, "com.example.Ledger"));
        String secondary = rule(h, "com.example.LedgerIndex");
        assertTrue(secondary.contains("**/Ledger.java"),
            "LedgerIndex is declared in Ledger.java, and no LedgerIndex.java exists:\n" + secondary);
        assertFalse(secondary.contains("LedgerIndex.java"), secondary);
    }

    /**
     * Invariant 12: the file name becomes generated content, so it has to reach the build
     * fingerprint. A build whose only change is the language a type is written in must regenerate,
     * not short-circuit past a rule still scoped to the old file.
     */
    @Test
    @DisplayName("a change of source language alone regenerates the rule")
    void aLanguageChangeAloneRegenerates(@TempDir Path root) throws IOException {
        String body = """
            public final class Invoice {
                @se.deversity.vibetags.annotations.AILocked(reason = "wire format")
                public long total() { return 0L; }
            }
            """;
        ProcessorTestHarness java = granular(root);
        java.addSource("com.example.Invoice", "package com.example;\n" + body);
        java.compile();
        VibeTagsLogger.shutdown();
        assertTrue(rule(java, "com.example.Invoice").contains("**/Invoice.java"), "precondition");

        ProcessorTestHarness kotlin = granular(root);
        kotlin.addSource("com.example.Invoice", "package com.example;\n@kotlin.Metadata(k = 1)\n" + body);
        kotlin.compile();

        String rule = rule(kotlin, "com.example.Invoice");
        assertTrue(rule.contains("**/Invoice.kt") && !rule.contains("Invoice.java"),
            "the second build short-circuited on an unchanged fingerprint:\n" + rule);
    }

    @Test
    @DisplayName("an ignored Kotlin class is excluded by its .kt file")
    void anIgnoredKotlinClassIsExcludedByItsKtFile(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = granular(root);
        h.addSource("com.example.LegacyCodec", """
            package com.example;
            @kotlin.Metadata(k = 1)
            @se.deversity.vibetags.annotations.AIIgnore(reason = "generated")
            public final class LegacyCodec {}
            """);

        h.compile();

        String ignore = h.readFile(".cursorignore");
        assertTrue(ignore.contains("**/LegacyCodec.kt"), ".cursorignore must hide the .kt file:\n" + ignore);
        assertFalse(ignore.contains("LegacyCodec.java"), ignore);
    }
}
