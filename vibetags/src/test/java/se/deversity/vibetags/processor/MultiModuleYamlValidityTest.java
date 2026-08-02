package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Six of the generated files are YAML documents, and a YAML document has exactly one of each
 * top-level key. The multi-module merge used to concatenate each module's <em>whole</em> rendered
 * document between {@code VIBETAGS-MODULE} sub-markers, so an N-module reactor produced N copies of
 * {@code rules:} / {@code reviews:} / {@code customModes:}. That is not a cosmetic defect: a strict
 * parser rejects the file outright, and a lenient one (SnakeYAML's default, PyYAML) keeps only the
 * last module — so every module but one silently lost its guardrails, with nothing in the build log
 * to say so.
 *
 * <p>Measured on {@code example-multimodule} (four modules) before the fix: {@code .roomodes} and
 * {@code .coderabbit.yaml} exposed 1 of 4 modules, {@code ellipsis.yaml} 90 of 100 rules,
 * {@code sweep.yaml} 54 of 59.
 *
 * <p>These tests therefore assert what the consuming tool observes, not what the bytes look like:
 * the file parses with duplicate keys forbidden, and both modules' elements survive the parse.
 */
class MultiModuleYamlValidityTest {

    private static final String ALPHA = "com.example.alpha.AlphaService";
    private static final String BETA = "com.example.beta.BetaService";

    /** Every service whose generated file is a YAML document, with the file name it is written to. */
    static Stream<Object[]> yamlServices() {
        return Stream.of(
            new Object[]{"sweep", Platform.SWEEP, "sweep.yaml"},
            new Object[]{"plandex", Platform.PLANDEX, ".plandex.yaml"},
            new Object[]{"interpreter", Platform.INTERPRETER, "vibetags.yaml"},
            new Object[]{"coderabbit", Platform.CODERABBIT, ".coderabbit.yaml"},
            new Object[]{"ellipsis", Platform.ELLIPSIS, "ellipsis.yaml"},
            new Object[]{"roo_modes", Platform.ROO_MODES, ".roomodes"}
        );
    }

    // -----------------------------------------------------------------------
    // The defect itself
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{2}")
    @MethodSource("yamlServices")
    void mergedAcrossTwoModules_isOneValidYamlDocument(String serviceKey, Platform platform, String fileName,
                                                       @TempDir Path root) throws IOException {
        String merged = mergeTwoModules(serviceKey, platform);
        Path file = root.resolve(fileName);
        new AIGuardrailProcessor().writeFileIfChanged(file.toString(), merged, true);
        String onDisk = Files.readString(file);

        Object parsed = parseStrict(onDisk, fileName);
        String flattened = String.valueOf(parsed);

        assertTrue(flattened.contains(ALPHA),
            fileName + ": the first module's guardrails must survive the parse, not just the write");
        assertTrue(flattened.contains(BETA),
            fileName + ": the second module's guardrails must survive the parse, not just the write");
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("yamlServices")
    void mergedAcrossTwoModules_repeatsNoTopLevelKey(String serviceKey, Platform platform, String fileName) {
        String merged = mergeTwoModules(serviceKey, platform);
        for (String key : topLevelKeys(merged)) {
            assertEquals(1, countLines(merged, key),
                fileName + ": '" + key + "' appears more than once at the top level, which makes the "
                    + "document ambiguous; the merge must emit the scaffold once");
        }
    }

    /**
     * A module contributing nothing must not leave a stray empty-collection placeholder next to
     * another module's entries — {@code rules:} cannot hold both {@code []} and a block sequence.
     */
    @ParameterizedTest(name = "{2}")
    @MethodSource("yamlServices")
    void oneEmptyModule_doesNotBreakTheOtherModulesEntries(String serviceKey, Platform platform, String fileName) {
        ModuleSidecar alpha = new ModuleSidecar("alpha", "alpha");
        alpha.putBody(serviceKey, render(platform, modelWith(ALPHA)));
        ModuleSidecar empty = new ModuleSidecar("empty", "empty");
        empty.putBody(serviceKey, render(platform, GuardrailModel.EMPTY));

        String merged = ModuleSidecar.mergeFor(serviceKey, List.of(alpha, empty), false);
        Object parsed = parseStrict(merged, fileName);
        assertTrue(String.valueOf(parsed).contains(ALPHA),
            fileName + ": an empty sibling module must not displace the contributing module");
    }

    /** All modules empty is still a document the platform has to be able to read. */
    @ParameterizedTest(name = "{2}")
    @MethodSource("yamlServices")
    void allModulesEmpty_stillParses(String serviceKey, Platform platform, String fileName) {
        ModuleSidecar a = new ModuleSidecar("a", "a");
        a.putBody(serviceKey, render(platform, GuardrailModel.EMPTY));
        ModuleSidecar b = new ModuleSidecar("b", "b");
        b.putBody(serviceKey, render(platform, GuardrailModel.EMPTY));

        parseStrict(ModuleSidecar.mergeFor(serviceKey, List.of(a, b), false), fileName);
    }

    /** A single module keeps its historical, sub-marker-free shape and must still parse. */
    @ParameterizedTest(name = "{2}")
    @MethodSource("yamlServices")
    void singleModule_isUnchangedAndParses(String serviceKey, Platform platform, String fileName) {
        String rendered = render(platform, modelWith(ALPHA));
        ModuleSidecar only = new ModuleSidecar("only", "only");
        only.putBody(serviceKey, rendered);

        String merged = ModuleSidecar.mergeFor(serviceKey, List.of(only), false);
        assertEquals(rendered.strip(), merged,
            fileName + ": a lone module must still be emitted verbatim");
        assertTrue(String.valueOf(parseStrict(merged, fileName)).contains(ALPHA));
    }

    /** Provenance must survive the fix: a reader still sees which module contributed what. */
    @ParameterizedTest(name = "{2}")
    @MethodSource("yamlServices")
    void mergedOutput_stillNamesEveryContributingModule(String serviceKey, Platform platform, String fileName) {
        String merged = mergeTwoModules(serviceKey, platform);
        assertTrue(merged.contains("VIBETAGS-MODULE: alpha"),
            fileName + ": the merged document must still say which module a rule came from");
        assertTrue(merged.contains("VIBETAGS-MODULE: beta"),
            fileName + ": the merged document must still say which module a rule came from");
    }

    // -----------------------------------------------------------------------
    // Falling back rather than guessing
    // -----------------------------------------------------------------------

    /**
     * Sidecars outlive the processor that wrote them, so a body rendered by an older scaffold can
     * turn up in a merge. Losing it would be worse than the duplicate key: the shape declines and
     * the previous concatenation runs, which is wrong about YAML but right about not discarding a
     * module.
     */
    @Test
    void bodyWithoutTheDeclaredAnchor_keepsEveryModulesContent() {
        ModuleSidecar older = new ModuleSidecar("older", "older");
        older.putBody("sweep", "# written by an older VibeTags\nreview_rules:\n  - \"a legacy rule\"\n");
        ModuleSidecar current = new ModuleSidecar("current", "current");
        current.putBody("sweep", render(Platform.SWEEP, modelWith(ALPHA)));

        String merged = ModuleSidecar.mergeFor("sweep", List.of(older, current), false);
        assertTrue(merged.contains("a legacy rule"),
            "a body the shape could not parse must survive, not be dropped: " + merged);
        assertTrue(merged.contains(ALPHA),
            "the current module must survive the fallback too: " + merged);
    }

    /** Same rule for the keyed shape: an undecomposable block falls back instead of vanishing. */
    @Test
    void keyedBodyWithoutBuckets_keepsEveryModulesContent() {
        ModuleSidecar odd = new ModuleSidecar("odd", "odd");
        odd.putBody("plandex", "# older scaffold\nguardrails:\n  - \"an unbucketed rule\"\n");
        ModuleSidecar current = new ModuleSidecar("current", "current");
        current.putBody("plandex", render(Platform.PLANDEX, modelWith(ALPHA)));

        String merged = ModuleSidecar.mergeFor("plandex", List.of(odd, current), false);
        assertTrue(merged.contains("an unbucketed rule"),
            "a guardrails block with no buckets must survive: " + merged);
        assertTrue(merged.contains(ALPHA),
            "the current module must survive the fallback too: " + merged);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String mergeTwoModules(String serviceKey, Platform platform) {
        ModuleSidecar alpha = new ModuleSidecar("alpha", "alpha");
        alpha.putBody(serviceKey, render(platform, modelWith(ALPHA)));
        ModuleSidecar beta = new ModuleSidecar("beta", "beta");
        beta.putBody(serviceKey, render(platform, modelWith(BETA)));
        return ModuleSidecar.mergeFor(serviceKey, List.of(alpha, beta), false);
    }

    private static String render(Platform platform, GuardrailModel model) {
        RenderingContext ctx = new RenderingContext(
            "Demo",
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n",
            Set.of("sweep", "plandex", "interpreter", "coderabbit", "ellipsis", "roo_modes"));
        String out = PlatformRendererRegistry.getRenderer(platform).render(model, platform, ctx);
        if (out == null) {
            throw new IllegalStateException("renderer returned null for " + platform);
        }
        return out;
    }

    /** One class carrying {@code @AILocked} and {@code @AIAudit}, so every renderer has content. */
    static GuardrailModel modelWith(String qualifiedName) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        TaggedElement element = TaggedElement.builder(qualifiedName)
            .names(qualifiedName, simple, qualifiedName, qualifiedName)
            .kind(ElementTag.CLASS)
            .annotation(AILocked.class, locked("Owned by the " + simple + " team"))
            .annotation(AIAudit.class, audit())
            .build();
        return GuardrailModel.builder()
            .add(AILocked.class, element)
            .add(AIAudit.class, element)
            .build();
    }

    private static AILocked locked(String reason) {
        return new AILocked() {
            @Override public String reason() { return reason; }
            @Override public Class<? extends Annotation> annotationType() { return AILocked.class; }
        };
    }

    private static AIAudit audit() {
        return new AIAudit() {
            @Override public String[] checkFor() { return new String[]{"Path Traversal"}; }
            @Override public Class<? extends Annotation> annotationType() { return AIAudit.class; }
        };
    }

    /**
     * Parses with duplicate keys forbidden. SnakeYAML allows them by default and silently keeps the
     * last, which is exactly the failure mode under test — a test that tolerated it would go green
     * on a file that has already lost a module.
     */
    private static Object parseStrict(String yaml, String fileName) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            return new Yaml(options).load(yaml);
        } catch (RuntimeException e) {
            throw new AssertionError(fileName + " is not a valid YAML document: " + e.getMessage()
                + "\n---- content ----\n" + yaml, e);
        }
    }

    /** Keys written at column zero — the ones a duplicate would make ambiguous. */
    private static List<String> topLevelKeys(String yaml) {
        return yaml.lines()
            .filter(l -> !l.isBlank() && !l.startsWith("#") && !Character.isWhitespace(l.charAt(0)))
            .filter(l -> l.matches("^[A-Za-z_][A-Za-z0-9_]*:.*"))
            .map(l -> l.substring(0, l.indexOf(':') + 1))
            .distinct()
            .toList();
    }

    private static long countLines(String yaml, String prefix) {
        return yaml.lines().filter(l -> l.startsWith(prefix)).count();
    }
}
