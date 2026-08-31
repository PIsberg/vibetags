package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The archive READMEs' tables against the directories they describe (#504).
 *
 * <p>{@code docs/archive/README.md} carries one row per archived document — what it is, when it
 * was retired, where the current answer lives — and that table is what makes the archive usable.
 * One direction was already guarded: a row linking a file that no longer exists is a dead link
 * and fails {@code DocumentationLinksTest}. The other direction was not: a document moved into
 * {@code docs/archive/} needs only <em>a</em> link from <em>any</em> reachable page to satisfy
 * {@code DocsIndexCompletenessTest} (the Design History section links archived documents
 * directly), so it can be perfectly reachable and still be missing from the table that says how
 * stale it is — the exact failure the archive exists to prevent.
 *
 * <p>{@code docs/diagrams/archive/README.md} has the same table-versus-directory relationship,
 * but names its files as code spans rather than links ({@code `class-diagram.puml` / `.png`}),
 * so its check matches on the filename stem and neither direction was guarded before.
 */
class ArchiveIndexCompletenessTest {

    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("every document in docs/archive/ has a row in its README's table, and vice versa")
    void archivedDocumentsAndTableRowsAgree() throws IOException {
        Path dir = REPO_ROOT.resolve("docs/archive");
        assumeTrue(Files.isDirectory(dir), "repo layout not reachable from " + REPO_ROOT + "; skipping");

        Set<String> onDisk = filesIn(dir, name -> name.endsWith(".md") && !name.equals("README.md"));

        // First-cell links only: "| [`SPEC.md`](SPEC.md) | ..." — the row's identity is the file
        // it links, and the later columns link reference docs that must not count as rows.
        Set<String> inTable = new TreeSet<>();
        Pattern firstCellLink = Pattern.compile("^\\|\\s*\\[[^]]+]\\(([^)#]+)\\)");
        for (String row : tableRows(dir.resolve("README.md"))) {
            Matcher m = firstCellLink.matcher(row);
            if (m.find()) {
                inTable.add(m.group(1));
            }
        }

        assertEquals(inTable, onDisk,
            "docs/archive/README.md's table and the directory disagree. A document in the "
                + "archive without a row has no retirement date and nothing saying what superseded "
                + "it, which is the staleness signal the archive exists to give; a row without a "
                + "document points readers at nothing. Add the missing row (see 'Adding to this "
                + "directory' in that README) or remove the stale one");
    }

    @Test
    @DisplayName("every file in docs/diagrams/archive/ is named by a row in its README's table")
    void archivedDiagramsAllHaveTableRows() throws IOException {
        Path dir = REPO_ROOT.resolve("docs/diagrams/archive");
        assumeTrue(Files.isDirectory(dir), "repo layout not reachable from " + REPO_ROOT + "; skipping");

        // The table names files as code spans, shortening extension pairs to
        // "`class-diagram.puml` / `.png`" — so the stable identity of a row is the stem.
        List<String> rows = tableRows(dir.resolve("README.md"));
        String rowText = String.join("\n", rows);
        for (String name : filesIn(dir, n -> !n.equals("README.md"))) {
            String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            assertTrue(rowText.contains("`" + stem),
                "docs/diagrams/archive/" + name + " has no row in that directory's README table, "
                    + "so nothing records when it was retired or what superseded it");
        }

        // Reverse direction: a fully-named code span in a row must exist on disk. The shorthand
        // "`.png`" halves carry no stem and are covered by the forward check above.
        Pattern span = Pattern.compile("`([^`/\\s]+\\.[a-z0-9]+)`");
        for (String row : rows) {
            Matcher m = span.matcher(row);
            while (m.find()) {
                String named = m.group(1);
                if (named.startsWith(".")) {
                    continue;
                }
                assertTrue(Files.exists(dir.resolve(named)),
                    "docs/diagrams/archive/README.md's table names '" + named
                        + "' but the file is not in the directory");
            }
        }
    }

    /** The table body of {@code readme}: its pipe-prefixed lines minus header and separator. */
    private static List<String> tableRows(Path readme) throws IOException {
        return Files.readAllLines(readme, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("|"))
            .filter(line -> !line.startsWith("|---") && !line.matches("\\|[\\s|:-]+"))
            .filter(line -> !line.contains("| Document |") && !line.contains("| Diagram |"))
            .toList();
    }

    private static Set<String> filesIn(Path dir, java.util.function.Predicate<String> keep) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                .map(p -> String.valueOf(p.getFileName()))
                .filter(keep)
                .forEach(names::add);
        }
        return names;
    }
}
