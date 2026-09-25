package se.deversity.vibetags.cli;

import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code vibetags doctor --context}: what the active guardrail files weigh, and which generated
 * sections the weight is in (<a href="https://github.com/PIsberg/vibetags/issues/840">issue #840</a>).
 *
 * <p>Every file an agent loads on every session spends context before the agent has read a line of
 * code, and the generated block is usually most of it. This reports bytes rather than an opinion:
 * per file, the part inside {@code VIBETAGS-START}/{@code END} markers, and per generated section
 * how many bytes and entries it holds and how many times it appears. A section appearing twice is
 * the signature of a block rendered once per source set (issue #839).
 *
 * <p>Tokens are shown as bytes / 4. That is a rough estimate for English and XML, not a tokenizer,
 * and the report says so; the byte counts are exact.
 *
 * <p>Scoped rule directories are listed apart from the files: the tools that read them load a rule
 * file when a matching source file is opened, not on every session. Exclusion lists
 * ({@code .aiexclude}, {@code *ignore}) are left out: they steer what a tool reads and are not
 * instructions to it.
 */
final class ContextWeight {

    private static final Pattern XML_SECTION = Pattern.compile(" {2}<([a-z_]+)>");
    private static final String XML_CLOSE = "</project_guardrails>";
    private static final String MD_SECTION = "## ";

    private final PrintStream out;
    private final Path dir;

    ContextWeight(PrintStream out, Path dir) {
        this.out = out;
        this.dir = dir;
    }

    /** Prints the report. Unreadable files are named, never counted as empty. */
    void run() {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(dir);
        Set<String> active = ServiceRegistry.resolveActiveServices(serviceFiles);
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        for (String key : active) {
            Path path = serviceFiles.get(key);
            if (path == null || ServiceRegistry.isIgnoreService(key)) {
                continue;
            }
            if (ServiceRegistry.writesDirectory(key)) {
                directories.add(path);
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        directories.sort(Comparator.comparing(Path::toString));

        out.println();
        out.println("context weight (bytes are exact UTF-8; ~tokens is bytes / 4, an estimate, not a tokenizer):");
        if (files.isEmpty() && directories.isEmpty()) {
            out.println("  no active guardrail files");
            return;
        }
        Map<Path, List<Section>> breakdowns = new LinkedHashMap<>();
        long totalBytes = 0;
        long totalGenerated = 0;
        for (Path file : files) {
            Optional<String> read = read(file);
            if (read.isEmpty()) {
                out.println("  " + name(file) + ": could not read (permissions? not UTF-8?), not counted");
                continue;
            }
            String text = read.get();
            long bytes = bytes(text);
            Optional<String> generated = generatedRegion(text);
            long generatedBytes = generated.map(ContextWeight::bytes).orElse(0L);
            totalBytes += bytes;
            totalGenerated += generatedBytes;
            out.println(String.format(Locale.ROOT, "  %-44s %,9d B  ~%,7d tokens  generated %s",
                name(file), bytes, bytes / 4,
                generated.isEmpty() ? "n/a (no markers)" : String.format(Locale.ROOT, "%,d B (%d%%)",
                    generatedBytes, bytes == 0 ? 0 : generatedBytes * 100 / bytes)));
            if (generated.isPresent()) {
                List<Section> sections = sections(generated.get());
                if (!sections.isEmpty()) {
                    breakdowns.put(file, sections);
                }
            }
        }
        out.println(String.format(Locale.ROOT, "  %-44s %,9d B  ~%,7d tokens  generated %,d B",
            "total (" + files.size() + " file" + (files.size() == 1 ? "" : "s") + ")",
            totalBytes, totalBytes / 4, totalGenerated));

        for (Map.Entry<Path, List<Section>> breakdown : breakdowns.entrySet()) {
            out.println("  generated sections in " + name(breakdown.getKey()) + ", largest first:");
            for (Section s : breakdown.getValue()) {
                // The name goes last: headings carry emoji, whose display width no padding can predict.
                out.println(String.format(Locale.ROOT, "    %,9d B  %4d entr%s  %s%s",
                    s.bytes, s.entries, s.entries == 1 ? "y  " : "ies", s.name,
                    s.occurrences > 1 ? "  (appears " + s.occurrences + " times)" : ""));
            }
        }

        if (!directories.isEmpty()) {
            out.println("  scoped rule directories (a tool loads a file here when a matching source is opened):");
            for (Path directory : directories) {
                reportDirectory(directory);
            }
        }
    }

    private void reportDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            out.println("  " + name(directory) + "/: not a directory");
            return;
        }
        long count = 0;
        long bytes = 0;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                count++;
                bytes += Files.size(file);
            }
        } catch (IOException e) {
            out.println("  " + name(directory) + "/: could not walk (" + e.getMessage() + "), not counted");
            return;
        }
        out.println(String.format(Locale.ROOT, "    %-42s %,9d B  %d file%s",
            name(directory) + "/", bytes, count, count == 1 ? "" : "s"));
    }

    /** Everything inside the file's marker pairs, or empty when it has none. */
    static Optional<String> generatedRegion(String text) {
        StringBuilder region = new StringBuilder();
        boolean found = false;
        for (String[] markers : new String[][]{
                {GuardrailFileWriter.MARKER_START_MD, GuardrailFileWriter.MARKER_END_MD},
                {GuardrailFileWriter.MARKER_START_HASH, GuardrailFileWriter.MARKER_END_HASH}}) {
            int from = 0;
            while (true) {
                int start = text.indexOf(markers[0], from);
                if (start < 0) {
                    break;
                }
                int end = text.indexOf(markers[1], start);
                if (end < 0) {
                    break;
                }
                region.append(text, start + markers[0].length(), end);
                found = true;
                from = end + markers[1].length();
            }
            if (found) {
                break;
            }
        }
        return found ? Optional.of(region.toString()) : Optional.empty();
    }

    /**
     * The generated region's sections, largest first: {@code <tag>} sections of a
     * {@code <project_guardrails>} block (each with the rule sentence after it), or {@code ## }
     * headings of a Markdown one. Whatever no section covers is reported as the scaffold.
     */
    static List<Section> sections(String generated) {
        Map<String, Section> byName = new LinkedHashMap<>();
        String[] lines = generated.split("\n", -1);
        String current = null;
        long covered = 0;
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Matcher xml = XML_SECTION.matcher(line);
            String opened = null;
            if (xml.matches()) {
                opened = "<" + xml.group(1) + ">";
            } else if (line.startsWith(MD_SECTION)) {
                opened = line.substring(MD_SECTION.length()).strip();
            } else if (line.equals(XML_CLOSE) || line.startsWith("<!-- #") || line.equals("<project_guardrails>")) {
                current = null;
            }
            if (opened != null) {
                current = opened;
                byName.computeIfAbsent(current, Section::new).occurrences++;
            }
            if (current == null) {
                continue;
            }
            Section section = byName.computeIfAbsent(current, Section::new);
            long lineBytes = bytes(raw) + 1;
            section.bytes += lineBytes;
            covered += lineBytes;
            if (isEntry(line)) {
                section.entries++;
            }
        }
        List<Section> result = new ArrayList<>(byName.values());
        if (!result.isEmpty()) {
            Section scaffold = new Section("(header, wrapper, text outside sections)");
            scaffold.bytes = Math.max(0, bytes(generated) - covered);
            result.add(scaffold);
        }
        result.sort(Comparator.comparingLong((Section s) -> s.bytes).reversed());
        return result;
    }

    /** An element line of an XML section, or a bullet of a Markdown one. */
    private static boolean isEntry(String line) {
        boolean xmlElement = line.startsWith("    <") && !line.startsWith("    </") && !line.startsWith("    <note>");
        return xmlElement || line.startsWith("- ");
    }

    private String name(Path path) {
        return dir.relativize(path).toString().replace('\\', '/');
    }

    private static long bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Optional<String> read(Path file) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** One generated section: bytes and entries summed over every time it appears. */
    static final class Section {
        final String name;
        long bytes;
        int entries;
        int occurrences;

        Section(String name) {
            this.name = name;
        }
    }
}
