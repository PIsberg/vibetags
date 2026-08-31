package se.deversity.vibetags.processor;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fails a test that deposits VibeTags state into the module directory — the JVM working
 * directory the whole suite shares.
 *
 * <p>This is the regression guard for #521: tests that ran the processor without
 * {@code vibetags.root} left {@code .vibetags-mod-*} sidecars, {@code .vibetags-cache} and
 * {@code vibetags.log} in {@code vibetags/}. All of them are gitignored, so the tree still read
 * as clean, and the <em>next</em> run's tests read them back — a stale sidecar with a foreign
 * module id merged phantom sections into a build with no annotations at all. CI never sees the
 * second run, so without this guard the only symptom is a developer machine where the suite is
 * green exactly once per clean checkout.
 *
 * <p>The comparison is first-run-shaped on purpose: only files that <em>appear</em> during the
 * test fail it, so pre-existing debris on a developer machine does not turn the guard red — the
 * fixed tests must pass with that debris present, which is the other half of the same bug.
 */
public final class ModuleDirHygiene implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS =
        ExtensionContext.Namespace.create(ModuleDirHygiene.class);

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        context.getStore(NS).put("before", vibetagsStateInModuleDir());
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        @SuppressWarnings("unchecked")
        Set<String> before = (Set<String>) context.getStore(NS).get("before", Set.class);
        Set<String> after = vibetagsStateInModuleDir();
        after.removeAll(before);
        assertEquals(Set.of(), after,
            "the test deposited VibeTags state into the module directory. Everything the "
                + "processor writes must go under a per-test vibetags.root — a gitignored leftover "
                + "here fails the NEXT suite run on this machine (#521)");
    }

    /** The gitignored processor-state files at the JVM working directory, by name. */
    private static Set<String> vibetagsStateInModuleDir() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> entries = Files.list(Path.of("").toAbsolutePath())) {
            entries.map(p -> String.valueOf(p.getFileName()))
                .filter(name -> name.startsWith(".vibetags-") || name.equals("vibetags.log"))
                .forEach(found::add);
        }
        return found;
    }
}
