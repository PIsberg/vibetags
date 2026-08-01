package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detectors that compare an annotation against the declaration it sits on, where the
 * contradiction only exists because of a language feature newer than Java 8 — records, sealed
 * types, virtual threads, the unnamed package.
 *
 * <p>Every source below trips exactly one intended condition, and the clean sources at the end
 * assert the detector stays silent when it does not apply. A validator nobody can trust to be quiet
 * is a validator everybody filters out of their build log.
 */
class ModernJavaDetectorTest {

    @TempDir
    static Path tempDir;

    private static List<String> messages;

    @BeforeAll
    static void compileAndCollect() throws IOException {
        messages = new ArrayList<>();

        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);

        List<JavaFileObject> sources = List.of(
            // --- shallow immutability: an array component under @AIImmutable ---------------
            new StringSource("com/example/mj/Palette.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                    + "@AIImmutable\n"
                    + "public record Palette(String name, int[] argb) {}\n"),

            new StringSource("com/example/mj/FrozenBuffer.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                    + "@AIImmutable\n"
                    + "public final class FrozenBuffer {\n"
                    + "    private final byte[] bytes = new byte[8];\n"
                    + "}\n"),

            // --- @AIExtensible on declarations that cannot be extended --------------------
            new StringSource("com/example/mj/ExtensibleRecord.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIExtensible;\n"
                    + "@AIExtensible\n"
                    + "public record ExtensibleRecord(String id) {}\n"),

            new StringSource("com/example/mj/ExtensibleFinal.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIExtensible;\n"
                    + "@AIExtensible\n"
                    + "public final class ExtensibleFinal {}\n"),

            new StringSource("com/example/mj/ExtensibleSealed.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIExtensible;\n"
                    + "@AIExtensible\n"
                    + "public sealed interface ExtensibleSealed permits OnlyPermitted {}\n"),

            new StringSource("com/example/mj/OnlyPermitted.java",
                "package com.example.mj;\n"
                    + "public record OnlyPermitted(int v) implements ExtensibleSealed {}\n"),

            // --- @AIPure on a void method -------------------------------------------------
            new StringSource("com/example/mj/PureVoid.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIPure;\n"
                    + "public class PureVoid {\n"
                    + "    @AIPure(reason = \"no side effects\")\n"
                    + "    public void recompute(int n) {}\n"
                    + "}\n"),

            // --- @AIPublicAPI on something no external caller can reach --------------------
            new StringSource("com/example/mj/HiddenApi.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIPublicAPI;\n"
                    + "@AIPublicAPI(reason = \"published surface\")\n"
                    + "class HiddenApi {}\n"),

            new StringSource("com/example/mj/EnclosedApi.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIPublicAPI;\n"
                    + "class EnclosedApi {\n"
                    + "    @AIPublicAPI(reason = \"published surface\")\n"
                    + "    public void reachable() {}\n"
                    + "}\n"),

            // --- ThreadLocal strategy in a virtual-thread world ---------------------------
            new StringSource("com/example/mj/PerThreadCache.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                    + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL)\n"
                    + "public class PerThreadCache {\n"
                    + "    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();\n"
                    + "    public String tenant() { return TENANT.get(); }\n"
                    + "}\n"),

            // --- the unnamed package: reachable without meaning to since JDK 25 -----------
            new StringSource("LooseMain.java",
                "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "@AILocked(reason = \"entry point\")\n"
                    + "public class LooseMain {\n"
                    + "    public static void main(String[] args) {}\n"
                    + "}\n"),

            // --- clean sources: none of the above must fire -------------------------------
            new StringSource("com/example/mj/CleanImmutable.java",
                "package com.example.mj;\n"
                    + "import java.util.List;\n"
                    + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                    + "@AIImmutable\n"
                    + "public record CleanImmutable(String name, List<Integer> argb) {}\n"),

            new StringSource("com/example/mj/CleanExtensible.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIExtensible;\n"
                    + "@AIExtensible\n"
                    + "public abstract class CleanExtensible {}\n"),

            new StringSource("com/example/mj/CleanPure.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIPure;\n"
                    + "public class CleanPure {\n"
                    + "    @AIPure(reason = \"no side effects\")\n"
                    + "    public int twice(int n) { return n * 2; }\n"
                    + "}\n"),

            new StringSource("com/example/mj/CleanPublicApi.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIPublicAPI;\n"
                    + "@AIPublicAPI(reason = \"published surface\")\n"
                    + "public class CleanPublicApi {\n"
                    + "    @AIPublicAPI(reason = \"published surface\")\n"
                    + "    public void reachable() {}\n"
                    + "}\n"),

            new StringSource("com/example/mj/CleanThreadSafe.java",
                "package com.example.mj;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                    + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL)\n"
                    + "public class CleanThreadSafe {\n"
                    + "    private final String tenant = \"\";\n"
                    + "    public String tenant() { return tenant; }\n"
                    + "}\n"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + tempDir.toAbsolutePath()
            );
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, options, null, sources);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        }

        for (var d : diagnostics.getDiagnostics()) {
            messages.add(d.getMessage(Locale.ROOT));
        }
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static void assertReported(String about, String... fragments) {
        assertTrue(messages.stream().anyMatch(m -> {
            for (String fragment : fragments) {
                if (!m.contains(fragment)) {
                    return false;
                }
            }
            return true;
        }), "Expected a diagnostic about " + about + ". Messages: " + messages);
    }

    private static void assertSilentAbout(String subject) {
        assertFalse(messages.stream().anyMatch(m -> m.startsWith("VibeTags:") && m.contains(subject)),
            "No VibeTags diagnostic expected for " + subject + ". Messages: " + messages);
    }

    // ------------------------------------------------------------------ shallow immutability

    @Test
    void warns_whenAnImmutableRecordHasAnArrayComponent() {
        assertReported("an array component under @AIImmutable",
            "@AIImmutable", "Palette", "argb", "freezes the reference");
    }

    @Test
    void namesTheGeneratedAccessor_whenTheArrayIsARecordComponent() {
        assertReported("the record accessor handing out the array",
            "Palette", "generated accessor");
    }

    @Test
    void warns_whenAnImmutableClassHasAnArrayField() {
        assertReported("an array field under @AIImmutable",
            "@AIImmutable", "FrozenBuffer", "bytes");
    }

    @Test
    void staysSilent_whenTheImmutableComponentIsNotAnArray() {
        assertSilentAbout("CleanImmutable");
    }

    // ------------------------------------------------------------------ @AIExtensible

    @Test
    void warns_whenExtensibleSitsOnARecord() {
        assertReported("@AIExtensible on a record",
            "@AIExtensible", "ExtensibleRecord", "implicitly final");
    }

    @Test
    void warns_whenExtensibleSitsOnAFinalClass() {
        assertReported("@AIExtensible on a final class",
            "@AIExtensible", "ExtensibleFinal", "declared final");
    }

    @Test
    void warns_whenExtensibleSitsOnASealedType() {
        assertReported("@AIExtensible on a sealed type",
            "@AIExtensible", "ExtensibleSealed", "sealed", "permits clause");
    }

    @Test
    void staysSilent_whenExtensibleSitsOnAnAbstractClass() {
        assertSilentAbout("CleanExtensible");
    }

    // ------------------------------------------------------------------ @AIPure

    @Test
    void warns_whenPureSitsOnAVoidMethod() {
        assertReported("@AIPure on a void method",
            "@AIPure", "recompute", "returns void");
    }

    @Test
    void staysSilent_whenThePureMethodReturnsAValue() {
        assertSilentAbout("CleanPure");
    }

    // ------------------------------------------------------------------ @AIPublicAPI

    @Test
    void warns_whenPublicApiSitsOnANonPublicType() {
        assertReported("@AIPublicAPI on a package-private type",
            "@AIPublicAPI", "HiddenApi", "it is not public");
    }

    @Test
    void warns_whenPublicApiSitsInsideANonPublicType() {
        assertReported("@AIPublicAPI inside a package-private type",
            "@AIPublicAPI", "reachable", "enclosing", "not public");
    }

    @Test
    void staysSilent_whenTheWholeEnclosingChainIsPublic() {
        assertSilentAbout("CleanPublicApi");
    }

    // ------------------------------------------------------------------ ThreadLocal / ScopedValue

    @Test
    void notes_whenAThreadLocalStrategyMeetsVirtualThreads() {
        assertReported("a ThreadLocal strategy under virtual threads",
            "PerThreadCache", "TENANT", "virtual threads", "ScopedValue");
    }

    @Test
    void staysSilent_whenTheThreadLocalStrategyHoldsNoThreadLocal() {
        assertSilentAbout("CleanThreadSafe");
    }

    // ------------------------------------------------------------------ the unnamed package

    @Test
    void warns_whenAGuardedElementIsInTheUnnamedPackage() {
        assertReported("a guarded element in the unnamed package",
            "@AILocked", "LooseMain", "unnamed package", "collide");
    }

    @Test
    void staysSilent_forGuardedElementsThatDeclareAPackage() {
        assertSilentAbout("com.example.mj.CleanPublicApi, which is in the unnamed package");
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String name, String code) {
            super(URI.create("string:///" + name), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
