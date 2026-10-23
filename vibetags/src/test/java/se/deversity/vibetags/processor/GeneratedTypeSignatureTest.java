package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A member whose signature names a type another annotation processor generates gets the same
 * fully qualified path as any other member (#989).
 *
 * <p>In the round VibeTags collects the member, the generated type does not exist yet: javac hands
 * over an error type, which renders as the bare name the source wrote. The path is the element's
 * identity in {@code .vibetags-locks}, granular rule names and the enforcement baseline, so
 * {@code total(ImmutableMoney)} beside {@code toDto(com.example.Customer)} was a second naming
 * scheme that depended on which processor generated what. Seen with Immutables and
 * hibernate-processor; the peer here generates two types in its first round, one in the annotated
 * class's own package and one in another package the source imports explicitly.
 */
@Tag("e2e")
class GeneratedTypeSignatureTest {

    private static final String WALLET =
        "package com.example.gen;\n"
            + "import com.example.meta.Order_;\n"
            + "import se.deversity.vibetags.annotations.AIInputSanitized;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "public class Wallet {\n"
            + "    @AILocked(reason = \"ledger maths\")\n"
            + "    public ImmutableMoney total(ImmutableMoney start, java.util.List<ImmutableMoney> all) { return start; }\n"
            + "    @AILocked(reason = \"criteria contract\")\n"
            + "    public String attr(Order_ attribute) { return \"\"; }\n"
            + "    public void find(@AIInputSanitized(AIInputSanitized.SanitizerType.SQL_INJECTION) String query, Order_ by) {}\n"
            + "}\n";

    @Test
    void lockedMemberNamingAGeneratedType_isQualified(@TempDir Path tmp) throws IOException {
        String claude = compile(tmp).readFile("CLAUDE.md");

        assertTrue(claude.contains("com.example.gen.Wallet.total(com.example.gen.ImmutableMoney,"
                + "java.util.List&lt;com.example.gen.ImmutableMoney&gt;)")
                || claude.contains("com.example.gen.Wallet.total(com.example.gen.ImmutableMoney,"
                + "java.util.List<com.example.gen.ImmutableMoney>)"),
            "A type generated in the annotated class's own package must render qualified. CLAUDE.md:\n" + claude);
        assertTrue(claude.contains("com.example.gen.Wallet.attr(com.example.meta.Order_)"),
            "An explicitly imported generated type must render qualified. CLAUDE.md:\n" + claude);
        assertFalse(claude.contains("(ImmutableMoney") || claude.contains("(Order_)"),
            "No path may keep the bare name of the error type javac saw in round 1. CLAUDE.md:\n" + claude);
    }

    @Test
    void parameterOfAMemberNamingAGeneratedType_isQualified(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness harness = compile(tmp);
        String all = harness.readFile("CLAUDE.md") + harness.readFile("llms-full.txt");

        assertTrue(all.contains("com.example.gen.Wallet.find(java.lang.String,com.example.meta.Order_)#query"),
            "A parameter's path is its method's path, so it must be qualified too. Output:\n" + all);
    }

    @Test
    void baselineSignature_namesTheGeneratedTypeQualified(@TempDir Path tmp) throws IOException {
        String baseline = compile(tmp, "-Avibetags.baseline.update=true").readFile(".vibetags-baseline");

        assertTrue(baseline.contains("total(com.example.gen.ImmutableMoney,java.util.List<com.example.gen.ImmutableMoney>)"
                + ":com.example.gen.ImmutableMoney"),
            "The enforced signature must not depend on whether the peer had run yet. Baseline:\n" + baseline);
    }

    private static ProcessorTestHarness compile(Path tmp, String... options) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(tmp);
        harness.addSource("com.example.gen.Wallet", WALLET);
        harness.compileAlongside(new GeneratingPeer(), options);
        return harness;
    }

    /** Generates the types {@code Wallet} names in its first round, as Immutables and hibernate-processor do. */
    @SupportedAnnotationTypes("*")
    static final class GeneratingPeer extends AbstractProcessor {
        private boolean generated;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (generated || roundEnv.processingOver()) {
                return false;
            }
            generated = true;
            write("com.example.gen.ImmutableMoney", "package com.example.gen; public class ImmutableMoney {}");
            write("com.example.meta.Order_", "package com.example.meta; public class Order_ {}");
            return false;
        }

        private void write(String name, String code) {
            try (Writer out = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                out.write(code);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
