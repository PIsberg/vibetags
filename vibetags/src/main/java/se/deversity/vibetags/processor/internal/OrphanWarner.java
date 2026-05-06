package se.deversity.vibetags.processor.internal;

import org.slf4j.Logger;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Warns about "orphaned" annotation usage — when an annotation is present but the
 * corresponding ignore-file isn't, so the protection won't actually take effect.
 */
@SuppressWarnings("PMD.GuardLogStatement")
public final class OrphanWarner {

    private OrphanWarner() {}

    public static void warnAboutOrphans(Messager messager, Logger log, Set<String> active,
                                        boolean hasLocked, boolean hasIgnore, boolean hasAudit) {
        if (hasIgnore) {
            warn(messager, log, active.contains("cursor") && !active.contains("cursor_ignore"),
                "VibeTags: @AIIgnore used but .cursorignore is missing for Cursor support. Consider creating it.");
            warn(messager, log, active.contains("claude") && !active.contains("claude_ignore"),
                "VibeTags: @AIIgnore used but .claudeignore is missing for Claude support. Consider creating it.");
            warn(messager, log, active.contains("copilot") && !active.contains("copilot_ignore"),
                "VibeTags: @AIIgnore used but .copilotignore is missing for Copilot support. Consider creating it.");
            warn(messager, log, active.contains("qwen") && !active.contains("qwen_ignore"),
                "VibeTags: @AIIgnore used but .qwenignore is missing for Qwen support. Consider creating it.");
            warn(messager, log, (active.contains("gemini") || active.contains("codex")) && !active.contains("aiexclude"),
                "VibeTags: @AIIgnore used but .aiexclude is missing for Gemini/Codex support. Consider creating it.");
        }

        if (hasLocked) {
            warn(messager, log, (active.contains("gemini") || active.contains("codex")) && !active.contains("aiexclude"),
                "VibeTags: @AILocked used but .aiexclude (hard guardrail) is missing for Gemini/Codex support. Consider creating it.");
        }
    }

    private static void warn(Messager messager, Logger log, boolean condition, String message) {
        if (!condition) return;
        messager.printMessage(Diagnostic.Kind.WARNING, message);
        if (log != null) log.warn(message);
    }
}
