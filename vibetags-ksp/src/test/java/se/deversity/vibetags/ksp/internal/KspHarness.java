package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.impl.KotlinSymbolProcessing;
import com.google.devtools.ksp.processing.KSPJvmConfig;
import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSNode;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.ksp.VibeTagsSymbolProcessorProvider;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs real KSP2 symbol processing in-process over a directory of Kotlin sources, with VibeTags'
 * provider registered, and reports what KSP logged. No Gradle: the same engine the KSP Gradle
 * plugin drives, called directly.
 */
final class KspHarness {

    /** What one KSP run produced: its exit code and every message the processors logged. */
    record Result(String exitCode, List<String> warnings, List<String> errors, List<String> infos) {
    }

    private final Path sources;
    private final Path root;
    private final Map<String, String> options = new HashMap<>();
    private String moduleName = "main";
    private @Nullable String jvmDefault;
    private boolean incremental;
    private List<File> modified = List.of();

    /**
     * @param sources the Kotlin source root
     * @param root    the project root VibeTags writes into ({@code vibetags.root})
     */
    KspHarness(Path sources, Path root) {
        this.sources = sources;
        this.root = root;
        options.put("vibetags.root", root.toString());
    }

    KspHarness option(String key, String value) {
        options.put(key, value);
        return this;
    }

    KspHarness withoutRootOption() {
        options.remove("vibetags.root");
        return this;
    }

    /** The Kotlin module name, which internal functions' JVM names embed. */
    KspHarness moduleName(String name) {
        this.moduleName = name;
        return this;
    }

    KspHarness jvmDefault(String mode) {
        this.jvmDefault = mode;
        return this;
    }

    /** Runs KSP incrementally, reusing the caches under the root, with {@code changed} dirty. */
    KspHarness incremental(List<Path> changed) {
        this.incremental = true;
        this.modified = changed.stream().map(Path::toFile).toList();
        return this;
    }

    Result run() {
        return run(new VibeTagsSymbolProcessorProvider());
    }

    Result run(SymbolProcessorProvider... providers) {
        Path out = root.resolve("build/ksp");
        KSPJvmConfig.Builder config = new KSPJvmConfig.Builder();
        config.setModuleName(moduleName);
        config.setSourceRoots(List.of(sources.toFile()));
        config.setJavaSourceRoots(List.of());
        config.setCommonSourceRoots(List.of());
        config.setLibraries(List.of(jarOf(AILocked.class), jarOf(kotlin.Unit.class)));
        config.setJdkHome(new File(System.getProperty("java.home")));
        config.setJvmTarget("21");
        config.setLanguageVersion("2.2");
        config.setApiVersion("2.2");
        config.setProjectBaseDir(root.toFile());
        config.setOutputBaseDir(out.toFile());
        config.setCachesDir(out.resolve("caches").toFile());
        config.setClassOutputDir(out.resolve("classes").toFile());
        config.setKotlinOutputDir(out.resolve("kotlin").toFile());
        config.setJavaOutputDir(out.resolve("java").toFile());
        config.setResourceOutputDir(out.resolve("resources").toFile());
        config.setProcessorOptions(Map.copyOf(options));
        if (jvmDefault != null) {
            config.setJvmDefaultMode(jvmDefault);
        }
        config.setIncremental(incremental);
        config.setModifiedSources(modified);
        config.setRemovedSources(List.of());
        config.setChangedClasses(List.of());
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<String> infos = Collections.synchronizedList(new ArrayList<>());
        KSPLogger logger = new KSPLogger() {
            @Override public void logging(String message, @Nullable KSNode symbol) { }
            @Override public void info(String message, @Nullable KSNode symbol) { infos.add(message); }
            @Override public void warn(String message, @Nullable KSNode symbol) { warnings.add(message); }
            @Override public void error(String message, @Nullable KSNode symbol) { errors.add(message); }
            @Override public void exception(Throwable e) { errors.add(String.valueOf(e)); }
        };
        Object exit = new KotlinSymbolProcessing(config.build(), List.of(providers), logger).execute();
        return new Result(String.valueOf(exit), List.copyOf(warnings), List.copyOf(errors), List.copyOf(infos));
    }

    private static File jarOf(Class<?> type) {
        try {
            return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Copies a test-resource directory of Kotlin sources into {@code target}. */
    static Path copySources(String resource, Path target) {
        try {
            Path from = Path.of(KspHarness.class.getResource("/" + resource).toURI());
            try (Stream<Path> files = Files.walk(from)) {
                for (Path file : files.toList()) {
                    Path to = target.resolve(from.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(file, to);
                    }
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
