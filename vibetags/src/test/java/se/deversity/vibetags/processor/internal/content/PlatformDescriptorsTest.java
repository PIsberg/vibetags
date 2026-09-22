package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptor.Kind;
import se.deversity.vibetags.processor.internal.content.platforms.IgnoreFileRenderer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link PlatformDescriptors#ALL} must keep true now that the service map, the opt-in set,
 * the file-or-directory question, the renderer lookup and the two exclusion-file lists are all
 * derived from it (<a href="https://github.com/PIsberg/vibetags/issues/762">issue #762</a>).
 *
 * <p>The table removed one kind of mistake, a platform added to four of five structures, and
 * concentrated another: an entry with its neighbour's path or renderer is now wrong everywhere at
 * once. A count or a set comparison cannot see a swapped pair, so every row is pinned by value and
 * in order.
 *
 * <p>The pinned rows below were taken from the five hand-written structures before the table
 * replaced them. A red result here is a generated file moving to a different path, an opt-in
 * changing, or the order of the "create one of these files" note changing for every new user. Each
 * of those is a decision; none of them is a refactor.
 */
@DisplayName("The per-platform descriptor table")
class PlatformDescriptorsTest {

    /** {@code serviceKey|relativePath|opt-in or implicit|file or dir}, in the pinned order. */
    private static final List<String> PINNED = List.of(
        "cursor|.cursorrules|opt-in|file",
        "claude|CLAUDE.md|opt-in|file",
        "aiexclude|.aiexclude|opt-in|file",
        "codex|AGENTS.md|opt-in|file",
        "gemini|gemini_instructions.md|opt-in|file",
        "copilot|.github/copilot-instructions.md|opt-in|file",
        "qwen|QWEN.md|opt-in|file",
        "cursor_ignore|.cursorignore|opt-in|file",
        "claude_ignore|.claudeignore|opt-in|file",
        "copilot_ignore|.copilotignore|opt-in|file",
        "qwen_ignore|.qwenignore|opt-in|file",
        "codex_config|.codex/config.toml|implicit|file",
        "codex_rules|.codex/rules/vibetags.rules|implicit|file",
        "qwen_refactor|.qwen/commands/refactor.md|opt-in|file",
        "llms|llms.txt|opt-in|file",
        "llms_full|llms-full.txt|opt-in|file",
        "aider_conventions|CONVENTIONS.md|opt-in|file",
        "aider_ignore|.aiderignore|opt-in|file",
        "aider_conf|.aider.conf.yml|opt-in|file",
        "cursor_granular|.cursor/rules|opt-in|dir",
        "roo_granular|.roo/rules|opt-in|dir",
        "trae_granular|.trae/rules|opt-in|dir",
        "windsurf|.windsurfrules|opt-in|file",
        "zed|.rules|opt-in|file",
        "cody|.cody/config.json|opt-in|file",
        "cody_ignore|.codyignore|opt-in|file",
        "supermaven_ignore|.supermavenignore|opt-in|file",
        "windsurf_granular|.windsurf/rules|opt-in|dir",
        "windsurf_safety|.windsurf/rules/+vibetags-safety.md|implicit|file",
        "continue_granular|.continue/rules|opt-in|dir",
        "tabnine_granular|.tabnine/guidelines|opt-in|dir",
        "amazonq_granular|.amazonq/rules|opt-in|dir",
        "ai_rules_granular|.ai/rules|opt-in|dir",
        "pearai_granular|.pearai/rules|opt-in|dir",
        "mentat|.mentatconfig.json|opt-in|file",
        "sweep|sweep.yaml|opt-in|file",
        "plandex|.plandex.yaml|opt-in|file",
        "double_ignore|.doubleignore|opt-in|file",
        "interpreter|.interpreter/profiles/vibetags.yaml|opt-in|file",
        "codeium_ignore|.codeiumignore|opt-in|file",
        "roo_ignore|.rooignore|opt-in|file",
        "continue_ignore|.continueignore|opt-in|file",
        "augment_ignore|.augmentignore|opt-in|file",
        "gemini_md|GEMINI.md|opt-in|file",
        "antigravity_ignore|.antigravityignore|opt-in|file",
        "cline|.clinerules|opt-in|file",
        "cline_granular|.clinerules|opt-in|dir",
        "cline_safety|.clinerules/+vibetags-safety.md|implicit|file",
        "junie|.junie/guidelines.md|opt-in|file",
        "junie_agents|.junie/AGENTS.md|opt-in|file",
        "kiro_granular|.kiro/steering|opt-in|dir",
        "firebase|.idx/airules.md|opt-in|file",
        "claude_local|CLAUDE.local.md|opt-in|file",
        "claude_skill|.claude/skills/vibetags-guardrails/SKILL.md|opt-in|file",
        "agents_skill|.agents/skills/vibetags-guardrails/SKILL.md|opt-in|file",
        "claude_granular|.claude/rules|opt-in|dir",
        "copilot_granular|.github/instructions|opt-in|dir",
        "gemini_granular|.gemini/rules|opt-in|dir",
        "grok_granular|.grok/rules|opt-in|dir",
        "antigravity_granular|.agents/rules|opt-in|dir",
        "aiassistant_granular|.aiassistant/rules|opt-in|dir",
        "augment_granular|.augment/rules|opt-in|dir",
        "zencoder_granular|.zencoder/rules|opt-in|dir",
        "devin_granular|.devin/rules|opt-in|dir",
        "devin_safety|.devin/rules/+vibetags-safety.md|implicit|file",
        "devin_ignore|.devinignore|opt-in|file",
        "replit|replit.md|opt-in|file",
        "goose|.goosehints|opt-in|file",
        "repomix_ignore|.repomixignore|opt-in|file",
        "gitingest_ignore|.gitingestignore|opt-in|file",
        "gpt_ignore|.gptignore|opt-in|file",
        "ghostcoder_ignore|.ghostcoderignore|opt-in|file",
        "pieces_ignore|.piecesignore|opt-in|file",
        "coderabbit|.coderabbit.yaml|opt-in|file",
        "pr_agent|.pr_agent.toml|opt-in|file",
        "ellipsis|ellipsis.yaml|opt-in|file",
        "gemini_styleguide|.gemini/styleguide.md|opt-in|file",
        "greptile|greptile.json|opt-in|file",
        "greptile_rules|.greptile/rules.md|opt-in|file",
        "greptile_config|.greptile/config.json|opt-in|file",
        "void|.void/rules.md|opt-in|file",
        "roo_modes|.roomodes|opt-in|file",
        "locks_report|.vibetags-locks|opt-in|file",
        "root_index|.vibetags-root-index|opt-in|file",
        "testing|TESTING.md|opt-in|file"
    );

    @Test
    @DisplayName("every row is pinned, by value and in order")
    void tableMatchesThePinnedRows() {
        List<String> actual = new ArrayList<>();
        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            actual.add(d.serviceKey() + "|" + d.relativePath()
                + "|" + (d.optIn() ? "opt-in" : "implicit")
                + "|" + (d.kind() == Kind.DIRECTORY ? "dir" : "file"));
        }
        assertEquals(PINNED, actual,
            "the table is append only: its order is the order buildServiceFileMap returns, which "
                + "is the order of the note a new user copies file names from and of merge and log "
                + "iteration across a reactor");
    }

    @Test
    @DisplayName("the service map is the table, resolved against a root")
    void serviceMapIsDerivedFromTheTable() {
        Path root = Path.of("project");
        List<String> fromMap = new ArrayList<>();
        ServiceRegistry.buildServiceFileMap(root)
            .forEach((key, path) -> fromMap.add(key + "|" + root.relativize(path).toString().replace(File.separatorChar, '/')));

        List<String> fromTable = new ArrayList<>();
        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            fromTable.add(d.serviceKey() + "|" + d.relativePath());
        }
        assertEquals(fromTable, fromMap,
            "buildServiceFileMap no longer holds paths of its own; a difference here means the map "
                + "grew a key the table does not have, or lost one it does");
    }

    @Test
    @DisplayName("the opt-in set is exactly the entries with no parent")
    void optInKeysAreTheEntriesWithoutAParent() {
        Set<String> expected = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            keys.add(d.serviceKey());
            if (d.implicitParent() == null) {
                expected.add(d.serviceKey());
            }
        }
        assertEquals(expected, ServiceRegistry.optInKeys(),
            "an opt-in key with no path is an opt-in that resolves to nothing; a path with no "
                + "opt-in key is an output the user cannot ask for");

        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            String parent = d.implicitParent();
            if (parent != null) {
                assertTrue(keys.contains(parent),
                    d.serviceKey() + " is activated by " + parent + ", which is not a service");
            }
        }
    }

    @Test
    @DisplayName("every platform has exactly one entry, and every entry but one has a platform")
    void platformsAndEntriesAgreeOneForOne() {
        List<String> wrong = new ArrayList<>();
        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            Platform byKey = Platform.fromServiceKey(d.serviceKey());
            if (byKey != d.platform()) {
                wrong.add(d.serviceKey() + ": entry says " + d.platform() + ", enum says " + byKey);
            }
        }
        assertEquals(List.of(), wrong, "an entry naming another platform renders the wrong file");

        for (Platform p : Platform.values()) {
            PlatformDescriptor d = PlatformDescriptors.byPlatform(p);
            assertNotNull(d, p + " has no entry, so it renders content that is dropped at the write step");
            assertNotNull(d.renderer(), p + " has an entry with no renderer");
        }
    }

    @Test
    @DisplayName("root_index is the one entry that is a marker and nothing else")
    void rootIndexHasNoPlatformAndNoRenderer() {
        PlatformDescriptor d = PlatformDescriptors.byKey("root_index");
        assertNotNull(d);
        assertNull(d.platform(), "root_index renders nothing; its presence only flips the reactor-root merge");
        assertNull(d.renderer());

        List<String> others = new ArrayList<>();
        for (PlatformDescriptor other : PlatformDescriptors.ALL) {
            if (other.platform() == null && !"root_index".equals(other.serviceKey())) {
                others.add(other.serviceKey());
            }
        }
        assertEquals(List.of(), others,
            "a second platform-less key is a decision, not drift: say why it renders nothing");
    }

    @Test
    @DisplayName("an exclusion file is labelled and takes globs, and nothing else is")
    void exclusionFilesAreLabelledAndTakeGlobs() {
        List<String> labelled = new ArrayList<>();
        List<String> renderedByIgnoreRenderer = new ArrayList<>();
        List<String> globbed = new ArrayList<>();
        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            if (d.ignoreLabel() != null) {
                labelled.add(d.serviceKey());
            }
            if (d.renderer() instanceof IgnoreFileRenderer) {
                renderedByIgnoreRenderer.add(d.serviceKey());
            }
            if (d.globSyntax()) {
                globbed.add(d.serviceKey());
            }
        }
        assertEquals(renderedByIgnoreRenderer, labelled,
            "an exclusion file with no label gets the header \"AI Platform\", which reads like a "
                + "deliberate choice and is not one");
        assertEquals(List.of("aiexclude", "greptile", "greptile_config"),
            globbed.stream().filter(k -> !labelled.contains(k)).toList(),
            "these three carry .gitignore globs without being *_ignore files: .aiexclude, and the "
                + "ignorePatterns span inside greptile.json and .greptile/config.json (#651)");
        assertTrue(globbed.containsAll(labelled),
            "an exclusion file that does not take globs writes a header and no patterns");
    }

    @Test
    @DisplayName("the safety-tier file name is not spelled twice")
    void safetyEntriesUseTheDeclaredFileName() {
        for (PlatformDescriptor d : PlatformDescriptors.ALL) {
            if (d.serviceKey().endsWith("_safety")) {
                assertTrue(d.relativePath().endsWith("/" + ServiceRegistry.SAFETY_TIER_FILE),
                    d.serviceKey() + " no longer lands on ServiceRegistry.SAFETY_TIER_FILE, so the "
                        + "orphan sweep's exclusion of that name stops covering it");
            }
        }
        PlatformDescriptor locks = PlatformDescriptors.byKey("locks_report");
        PlatformDescriptor rootIndex = PlatformDescriptors.byKey("root_index");
        assertNotNull(locks);
        assertNotNull(rootIndex);
        assertEquals(ServiceRegistry.LOCKS_REPORT_FILE, locks.relativePath(),
            "the constant and the table are two spellings of one path");
        assertEquals(ServiceRegistry.ROOT_INDEX_FILE, rootIndex.relativePath(),
            "the constant and the table are two spellings of one path");
    }
}
