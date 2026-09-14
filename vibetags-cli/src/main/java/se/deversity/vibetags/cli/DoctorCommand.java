package se.deversity.vibetags.cli;

import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code vibetags doctor} — reports the project's VibeTags health without compiling anything.
 *
 * <p>Checks, in order: which build tool the directory uses, whether the processor and the
 * annotations artifact are wired into it, which platforms are active (same file-existence
 * resolution the processor runs, via {@link ServiceRegistry}), and whether every active
 * marker file still carries a balanced {@code VIBETAGS-START} / {@code VIBETAGS-END} pair.
 * Exit code 0 means healthy; 1 means at least one finding needs action.
 *
 * <p>A file doctor cannot read (permissions, not UTF-8) is a finding, never a silent pass:
 * "could not check" reported as "checked, fine" is the one lie a health tool must not tell.
 */
final class DoctorCommand {

    private final PrintStream out;
    private final Path dir;
    private final List<Path> classpath;
    private final List<String> problems = new ArrayList<>();

    /**
     * @param classpath jars and class directories to read Kotlin value classes from, for value
     *                  classes declared in a dependency or a module outside {@code dir}; may be empty
     */
    DoctorCommand(PrintStream out, Path dir, List<Path> classpath) {
        this.out = out;
        this.dir = dir;
        this.classpath = List.copyOf(classpath);
    }

    int run() {
        out.println("vibetags doctor — " + dir);

        checkWiring(detectBuildFile());
        Set<String> active = checkActivePlatforms();
        checkMarkers(active);
        checkGroovyFieldGuardrails();
        checkKotlinValueClassGuardrails();

        out.println();
        if (problems.isEmpty()) {
            out.println("result: healthy");
            return 0;
        }
        out.println("result: " + problems.size() + " finding(s) need action:");
        problems.forEach(p -> out.println("  - " + p));
        return 1;
    }

    /** The build file doctor will grep for wiring, or empty when none is recognised. */
    private Optional<String> detectBuildFile() {
        for (String candidate : new String[]{"pom.xml", "build.gradle", "build.gradle.kts"}) {
            if (Files.isRegularFile(dir.resolve(candidate))) {
                out.println("build tool:      " + candidate);
                return Optional.of(candidate);
            }
        }
        out.println("build tool:      none recognised (no pom.xml or build.gradle[.kts])");
        problems.add("no build file found — doctor can only check opt-in files here");
        return Optional.empty();
    }

    private void checkWiring(Optional<String> detected) {
        if (detected.isEmpty()) {
            return;
        }
        String buildFile = detected.get();
        Optional<String> read = tryRead(dir.resolve(buildFile));
        if (read.isEmpty()) {
            out.println("processor wired: unknown — could not read " + buildFile);
            out.println("annotations dep: unknown — could not read " + buildFile);
            problems.add("could not read " + buildFile + " (permissions? not UTF-8?) — "
                + "doctor cannot verify the build wiring");
            return;
        }
        String text = read.get();
        boolean processor = text.contains("vibetags-processor");
        boolean annotations = text.contains("vibetags-annotations");
        out.println("processor wired: " + (processor ? "yes" : "NO — not found in " + buildFile));
        out.println("annotations dep: " + (annotations ? "yes" : "NO — not found in " + buildFile));
        if (!processor) {
            problems.add("vibetags-processor is not in " + buildFile
                + " — nothing regenerates the guardrail files (see the README install snippet)");
        }
        if (!annotations) {
            problems.add("vibetags-annotations is not in " + buildFile
                + " — the @AI* annotations will not compile");
        }
    }

    private Set<String> checkActivePlatforms() {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(dir);
        Set<String> active = new TreeSet<>(ServiceRegistry.resolveActiveServices(serviceFiles));
        if (active.isEmpty()) {
            out.println("active platforms: none");
            problems.add("no opt-in files present — run `vibetags init --platforms <key,...>` "
                + "(file presence is the opt-in; the processor never creates files itself)");
            return active;
        }
        out.println("active platforms (" + active.size() + "):");
        active.forEach(key -> out.println("  " + key + " -> " + dir.relativize(serviceFiles.get(key))));

        // Mirror of the processor's AGENTS.md sole-file rule, so doctor explains the one case
        // that reliably surprises people: the file exists but is deliberately not managed.
        Path agents = serviceFiles.get("codex");
        if (agents != null && Files.exists(agents) && !active.contains("codex")) {
            out.println("note: AGENTS.md exists but is treated as a hand-authored pointer "
                + "(other AI config files are present); paste a VIBETAGS-START/END pair into it "
                + "to have VibeTags manage it");
        }
        return active;
    }

    /**
     * A marker file with a START but no END (or the reverse) is how a half-lost merge or a
     * hand edit silently turns partial regeneration into whole-file confusion — the exact
     * state the writer refuses to touch. Only balanced-or-absent passes, and only a file
     * that could actually be read counts as checked.
     */
    private void checkMarkers(Set<String> active) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(dir);
        int broken = 0;
        for (String key : active) {
            // active is resolveActiveServices(serviceFiles), so it can only name keys this map
            // has. ServiceRegistryKeyParityTest pins the wider version of the same invariant.
            Path path = Objects.requireNonNull(serviceFiles.get(key),
                "active service " + key + " has no path in buildServiceFileMap");
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Optional<String> read = tryRead(path);
            if (read.isEmpty()) {
                broken++;
                problems.add("could not read " + dir.relativize(path)
                    + " (permissions? not UTF-8?) — marker state unknown, and the writer must "
                    + "be able to read this file to preserve hand-authored content around the block");
                continue;
            }
            String text = read.get();
            boolean brokenMd = text.contains(GuardrailFileWriter.MARKER_START_MD)
                != text.contains(GuardrailFileWriter.MARKER_END_MD);
            boolean brokenHash = text.contains(GuardrailFileWriter.MARKER_START_HASH)
                != text.contains(GuardrailFileWriter.MARKER_END_HASH);
            if (brokenMd || brokenHash) {
                broken++;
                problems.add("unbalanced VIBETAGS markers in " + dir.relativize(path)
                    + " — restore the missing marker (or delete both and recompile) before "
                    + "the next build, or hand-authored content around the block is at risk");
            }
        }
        out.println("markers:         "
            + (broken == 0 ? "all intact" : broken + " file(s) unbalanced or unreadable"));
    }

    /**
     * Field-level guardrails in Groovy sources are silently dropped: groovyc's Java stubs carry
     * the class, its constructors, methods and parameters — and no fields at all — so every
     * {@code ElementType.FIELD} annotation ({@code @AIPrivacy} included, a safety-tier guardrail)
     * generates nothing while the build stays green
     * (<a href="https://github.com/PIsberg/vibetags/issues/494">issue #494</a>). The processor
     * cannot warn: the annotation never reaches it. Doctor can still read the {@code .groovy}
     * source, so this is the tool that names the specific annotations being lost, without adding
     * a diagnostic to anyone's build.
     *
     * <p>The scan is a line heuristic, not a parser: a {@code @AI*} annotation whose annotated
     * declaration has no parameter list before any {@code =} is read as a field. Only annotations
     * that both exist in {@code GuardrailAnnotations.ALL} and target {@code FIELD} count, so a
     * method-level {@code @AILocked} or somebody else's {@code @Autowired} never trips it.
     */
    private void checkGroovyFieldGuardrails() {
        List<Path> sources = groovySources();
        if (sources.isEmpty()) {
            return;
        }
        List<String> dropped = new ArrayList<>();
        for (Path file : sources) {
            Optional<String> read = tryRead(file);
            if (read.isEmpty()) {
                problems.add("could not read " + dir.relativize(file)
                    + " (permissions? not UTF-8?) — cannot check it for field-level guardrails");
                continue;
            }
            scanGroovySource(file, read.get(), dropped);
        }
        out.println("groovy sources:  " + sources.size() + " file(s); "
            + (dropped.isEmpty()
                ? "no field-level guardrails found"
                : dropped.size() + " field-level guardrail(s) will be dropped by groovyc"));
        problems.addAll(dropped);
    }

    /**
     * Kotlin declarations whose JVM name a value class mangles are left out of kapt's Java stubs, so
     * their guardrails, and their parameters' guardrails, generate nothing while the build stays
     * green (<a href="https://github.com/PIsberg/vibetags/issues/681">#681</a>; the shapes measured
     * in <a href="https://github.com/PIsberg/vibetags/issues/692">#692</a>). The processor cannot
     * warn, for the same reason as the Groovy fields above. {@link KotlinValueClassScan} reads the
     * {@code .kt} sources instead, as a heuristic that prefers a miss to a false finding, and doctor
     * says so on every run so that "none found" is not read as "none lost"
     * (<a href="https://github.com/PIsberg/vibetags/issues/688">#688</a>).
     */
    private void checkKotlinValueClassGuardrails() {
        List<Path> files = sources(".kt");
        if (files.isEmpty()) {
            return;
        }
        // -Xjvm-expose-boxed gives value-class functions a boxed, unmangled variant, which kapt's stub
        // carries, so under a build file passing it only what the option does not expose is reported
        // (#692). The option set somewhere doctor does not read, a convention plugin, is not seen.
        List<Path> buildFiles = new ArrayList<>(sources("pom.xml"));
        buildFiles.addAll(sources("build.gradle"));
        buildFiles.addAll(sources("build.gradle.kts"));
        List<Path> exposeBoxedRoots = new ArrayList<>();
        for (Path buildFile : buildFiles) {
            if (tryRead(buildFile).map(text -> text.contains("-Xjvm-expose-boxed")).orElse(false)) {
                exposeBoxedRoots.add(Objects.requireNonNull(buildFile.toAbsolutePath().getParent(),
                    "a build file found under --dir has a parent directory"));
                out.println("kotlin option:   " + dir.relativize(buildFile) + " passes -Xjvm-expose-boxed; "
                    + "under it only suspend, open, abstract and interface members and value-class "
                    + "secondary constructors are reported");
            }
        }
        List<KotlinValueClassScan.Source> readable = new ArrayList<>();
        for (Path file : files) {
            Optional<String> read = tryRead(file);
            if (read.isEmpty()) {
                problems.add("could not read " + dir.relativize(file)
                    + " (permissions? not UTF-8?): cannot check it for guardrails on value-class declarations");
                continue;
            }
            Path absolute = file.toAbsolutePath();
            boolean exposeBoxed = exposeBoxedRoots.stream().anyMatch(absolute::startsWith);
            readable.add(new KotlinValueClassScan.Source(dir.relativize(file).toString(), read.get(), exposeBoxed));
        }
        Set<String> fromClasspath = Set.of();
        if (!classpath.isEmpty()) {
            JvmInlineClasses.Result result = JvmInlineClasses.read(classpath);
            fromClasspath = result.valueClasses();
            problems.addAll(result.problems());
            out.println("kotlin classpath: " + classpath.size() + " entr" + (classpath.size() == 1 ? "y" : "ies")
                + ", " + result.classFiles() + " class file(s) read, " + fromClasspath.size()
                + " value class(es) found");
        }
        KotlinValueClassScan.Report report = KotlinValueClassScan.scan(readable, fromClasspath);
        List<String> lost = report.findings();
        out.println("kotlin sources:  " + files.size() + " file(s), " + report.declaredValueClasses()
            + " value class(es) declared; "
            + (lost.isEmpty()
                ? "no guardrails found on value-class declarations"
                : lost.size() + " declaration(s) whose guardrails kapt will drop"));
        out.println("note: the Kotlin value-class check is a heuristic source scan. It sees value "
            + "classes declared under this directory"
            + (classpath.isEmpty() ? "" : " or in the --classpath entries")
            + " plus UByte, UShort, UInt, ULong and kotlin.time.Duration; one declared elsewhere"
            + (classpath.isEmpty()
                ? " (another module or a dependency: pass the compile classpath with --classpath to see those)"
                : "")
            + ", or reached through a typealias, is not seen, so no finding here is not proof that "
            + "nothing is lost");
        problems.addAll(lost);
    }

    /** Developer-authored {@code .groovy} files: everything outside build output directories. */
    private List<Path> groovySources() {
        return sources(".groovy");
    }

    /** Developer-authored files with the given extension, outside build output directories. */
    private List<Path> sources(String extension) {
        List<Path> sources = new ArrayList<>();
        Set<String> buildDirs = Set.of("build", "target", ".gradle", ".git");
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> String.valueOf(p.getFileName()).endsWith(extension))
                .filter(p -> {
                    for (Path part : dir.relativize(p)) {
                        if (buildDirs.contains(part.toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .forEach(sources::add);
        } catch (IOException e) {
            problems.add("could not walk " + dir + " for " + extension + " sources: " + e.getMessage());
        }
        sources.sort(java.util.Comparator.comparing(Path::toString));
        return sources;
    }

    private void scanGroovySource(Path file, String text, List<String> dropped) {
        List<String> lines = text.lines().toList();
        java.util.regex.Pattern annotation = java.util.regex.Pattern.compile(
            "@(?:se\\.deversity\\.vibetags\\.annotations\\.)?(" +
                String.join("|", guardrailsTargeting(java.lang.annotation.ElementType.FIELD)) + ")\\b");
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) {
                continue;   // an annotation in a comment binds nothing
            }
            java.util.regex.Matcher m = annotation.matcher(lines.get(i));
            while (m.find()) {
                String name = m.group(1);
                Optional<String> declaration = declarationAfter(lines, i, m.end());
                if (declaration.isEmpty() || !looksLikeField(declaration.get())) {
                    continue;
                }
                dropped.add(dir.relativize(file) + ":" + (i + 1) + " @" + name + " on field '"
                    + fieldName(declaration.get()) + "' — groovyc's Java stubs carry no fields, so this "
                    + "guardrail is dropped before any processor sees it. Move it to the class or "
                    + "an accessor, or see USAGE.md's Groovy section");
            }
        }
    }

    /** Simple names of every guardrail annotation that can sit on the given kind of element. */
    static List<String> guardrailsTargeting(java.lang.annotation.ElementType elementType) {
        List<String> names = new ArrayList<>();
        for (Class<? extends java.lang.annotation.Annotation> type
                : se.deversity.vibetags.processor.model.GuardrailAnnotations.ALL) {
            java.lang.annotation.Target target =
                type.getAnnotation(java.lang.annotation.Target.class);
            if (target == null) {
                continue;
            }
            for (java.lang.annotation.ElementType t : target.value()) {
                if (t == elementType) {
                    names.add(type.getSimpleName());
                    break;
                }
            }
        }
        return names;
    }

    /**
     * The declaration the annotation at {@code (line, col)} binds: the rest of its own line if
     * non-blank, else the next line that is not blank, a comment, or another annotation. Empty
     * when the file ends first.
     */
    private static Optional<String> declarationAfter(List<String> lines, int line, int col) {
        String rest = lines.get(line).substring(col)
            .replaceFirst("^\\s*\\([^)]*\\)", "")   // the annotation's own argument list
            .strip();
        if (!rest.isEmpty() && !rest.startsWith("@")) {
            return Optional.of(rest);
        }
        Optional<String> declaration = Optional.empty();
        for (int i = line + 1; i < lines.size() && declaration.isEmpty(); i++) {
            String candidate = lines.get(i).strip();
            if (!skippableBeforeDeclaration(candidate)) {
                declaration = Optional.of(candidate);
            }
        }
        return declaration;
    }

    /** Blank lines, further annotations and comments sit between an annotation and its target. */
    private static boolean skippableBeforeDeclaration(String candidate) {
        return candidate.isEmpty() || candidate.startsWith("@") || candidate.startsWith("//")
            || candidate.startsWith("*") || candidate.startsWith("/*");
    }

    /**
     * A field declaration has no parameter list before any initializer: {@code String email} and
     * {@code def token = make()} are fields, {@code def charge(amount)} and
     * {@code class Customer} are not.
     */
    private static boolean looksLikeField(String declaration) {
        String beforeInitializer = declaration.split("=", 2)[0];
        if (beforeInitializer.contains("(")) {
            return false;
        }
        String first = beforeInitializer.strip().split("\\s+")[0];
        return !Set.of("class", "interface", "enum", "trait", "record", "import", "package")
            .contains(first);
    }

    /** The last identifier before the initializer, which is the field's name. */
    private static String fieldName(String declaration) {
        String[] words = declaration.split("=", 2)[0].strip().split("\\s+");
        return words[words.length - 1];
    }

    private static Optional<String> tryRead(Path path) {
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
