package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which services give up a test round's non-safety guardrails to {@code TESTING.md}.
 *
 * <p>The answer is a rule in {@link ServiceRegistry#routesTestGuardrails}, and a rule is easy to
 * get subtly wrong for one key out of eighty: a routed ignore file stops excluding a test file, a
 * routed YAML review config loses rules with nothing to point at {@code TESTING.md}, and an
 * unrouted instruction file keeps paying for test guardrails on every session, which is the cost
 * the feature exists to remove. So the rule's output is pinned key by key, by hand, against what
 * each file is for. A new service key fails here until someone decides which side it is on.
 */
class ServiceRoutingContractTest {

    /**
     * Instruction files an agent loads as prose: one file, VibeTags markers, not YAML. Reviewed
     * key by key on 2026-09-20 against {@code docs/PLATFORMS.md}. {@code codex_rules} is here
     * because the rule has no reason to exclude it, not because it matters: its content does not
     * vary with the annotations.
     */
    private static final Set<String> ROUTED = Set.of(
        "cursor", "claude", "codex", "gemini", "copilot", "qwen", "qwen_refactor", "codex_rules",
        "aider_conventions", "windsurf", "zed", "gemini_md", "cline", "junie", "junie_agents",
        "firebase", "claude_local", "claude_skill", "agents_skill", "replit", "goose",
        "gemini_styleguide", "greptile_rules", "void");

    @Test
    void exactlyTheInstructionAggregatesAreRouted(@TempDir Path root) {
        Set<String> routed = new TreeSet<>();
        for (String key : ServiceRegistry.buildServiceFileMap(root).keySet()) {
            if (ServiceRegistry.routesTestGuardrails(key)) {
                routed.add(key);
            }
        }
        assertEquals(new TreeSet<>(ROUTED), routed,
            "a key on the left only is no longer routed; a key on the right only is newly routed, "
                + "or is a new service nobody has classified. Decide what the file is for, then "
                + "update ROUTED or the rule, never just the list");
    }

    /** The exclusions that are decisions rather than consequences of the file format. */
    @Test
    void theNamedExclusionsHold() {
        assertFalse(ServiceRegistry.routesTestGuardrails("testing"), "TESTING.md is the destination");
        assertFalse(ServiceRegistry.routesTestGuardrails("cursor_ignore"),
            "an @AIIgnore on a test file must still exclude it");
        assertFalse(ServiceRegistry.routesTestGuardrails("cline_safety"),
            "a safety file holds only what never moves");
        assertFalse(ServiceRegistry.routesTestGuardrails("locks_report"), "the CI diff guard reads every lock");
        assertFalse(ServiceRegistry.routesTestGuardrails("llms"), "llms.txt describes the project, not rules for an agent");
        assertFalse(ServiceRegistry.routesTestGuardrails("llms_full"), "same file family as llms.txt");
        assertFalse(ServiceRegistry.routesTestGuardrails("coderabbit"), "YAML review config has no prose to carry a pointer");
        assertFalse(ServiceRegistry.routesTestGuardrails("claude_granular"), "rule files are per element, not aggregates");
        assertFalse(ServiceRegistry.routesTestGuardrails("root_index"),
            "an opt-in marker with no renderer has nothing to route, whatever its file name implies");
        assertFalse(ServiceRegistry.routesTestGuardrails("no_such_service"));
    }
}
