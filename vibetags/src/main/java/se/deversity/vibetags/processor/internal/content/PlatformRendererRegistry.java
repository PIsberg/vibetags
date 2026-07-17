package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.internal.content.platforms.*;

/**
 * A central registry for retrieving the stateless PlatformRenderer for any given Platform.
 */
public final class PlatformRendererRegistry {

    private static final CursorRenderer CURSOR_RENDERER = new CursorRenderer();
    private static final ClaudeRenderer CLAUDE_RENDERER = new ClaudeRenderer();
    private static final AiExcludeRenderer AI_EXCLUDE_RENDERER = new AiExcludeRenderer();
    private static final CodexRenderer CODEX_RENDERER = new CodexRenderer();
    private static final CopilotRenderer COPILOT_RENDERER = new CopilotRenderer();
    private static final QwenRenderer QWEN_RENDERER = new QwenRenderer();
    private static final GeminiRenderer GEMINI_RENDERER = new GeminiRenderer();
    private static final LlmsRenderer LLMS_RENDERER = new LlmsRenderer();
    private static final AiderConventionsRenderer AIDER_CONVENTIONS_RENDERER = new AiderConventionsRenderer();
    private static final IgnoreFileRenderer IGNORE_FILE_RENDERER = new IgnoreFileRenderer();
    private static final WindsurfRenderer WINDSURF_RENDERER = new WindsurfRenderer();
    private static final ZedRenderer ZED_RENDERER = new ZedRenderer();
    private static final CodyRenderer CODY_RENDERER = new CodyRenderer();
    private static final MentatRenderer MENTAT_RENDERER = new MentatRenderer();
    private static final SweepRenderer SWEEP_RENDERER = new SweepRenderer();
    private static final PlandexRenderer PLANDEX_RENDERER = new PlandexRenderer();
    private static final InterpreterRenderer INTERPRETER_RENDERER = new InterpreterRenderer();
    private static final ClineRenderer CLINE_RENDERER = new ClineRenderer();
    private static final JunieRenderer JUNIE_RENDERER = new JunieRenderer();
    private static final FirebaseRenderer FIREBASE_RENDERER = new FirebaseRenderer();
    private static final ClaudeLocalRenderer CLAUDE_LOCAL_RENDERER = new ClaudeLocalRenderer();
    private static final ClaudeSkillRenderer CLAUDE_SKILL_RENDERER = new ClaudeSkillRenderer();
    private static final CodeRabbitRenderer CODERABBIT_RENDERER = new CodeRabbitRenderer();
    private static final PrAgentRenderer PR_AGENT_RENDERER = new PrAgentRenderer();
    private static final EllipsisRenderer ELLIPSIS_RENDERER = new EllipsisRenderer();
    private static final VoidRenderer VOID_RENDERER = new VoidRenderer();
    private static final RooModesRenderer ROO_MODES_RENDERER = new RooModesRenderer();
    private static final LocksReportRenderer LOCKS_REPORT_RENDERER = new LocksReportRenderer();
    private static final GranularRenderer GRANULAR_RENDERER = new GranularRenderer();

    private PlatformRendererRegistry() {}

    /**
     * Retrieves the renderer mapped to the specified platform enum.
     *
     * @param platform the platform type
     * @return the associated PlatformRenderer
     */
    public static PlatformRenderer getRenderer(Platform platform) {
        switch (platform) {
            case CURSOR:
                return CURSOR_RENDERER;
            case CLAUDE:
                return CLAUDE_RENDERER;
            case AI_EXCLUDE:
                return AI_EXCLUDE_RENDERER;
            case CODEX:
            case CODEX_CONFIG:
            case CODEX_RULES:
                return CODEX_RENDERER;
            case COPILOT:
                return COPILOT_RENDERER;
            case QWEN:
            case QWEN_SETTINGS:
            case QWEN_REFACTOR:
                return QWEN_RENDERER;
            case GEMINI:
            case GEMINI_MD:
                return GEMINI_RENDERER;
            case LLMS:
            case LLMS_FULL:
                return LLMS_RENDERER;
            case AIDER_CONVENTIONS:
                return AIDER_CONVENTIONS_RENDERER;
            case AIDER_IGNORE:
            case CURSOR_IGNORE:
            case CLAUDE_IGNORE:
            case COPILOT_IGNORE:
            case QWEN_IGNORE:
            case CODY_IGNORE:
            case SUPERMAVEN_IGNORE:
            case DOUBLE_IGNORE:
            case CODEIUM_IGNORE:
            case ANTIGRAVITY_IGNORE:
            case REPOMIX_IGNORE:
            case GITINGEST_IGNORE:
            case GPT_IGNORE:
            case GHOSTCODER_IGNORE:
            case PIECES_IGNORE:
                return IGNORE_FILE_RENDERER;
            case WINDSURF:
                return WINDSURF_RENDERER;
            case ZED:
                return ZED_RENDERER;
            case CODY:
                return CODY_RENDERER;
            case MENTAT:
                return MENTAT_RENDERER;
            case SWEEP:
                return SWEEP_RENDERER;
            case PLANDEX:
                return PLANDEX_RENDERER;
            case INTERPRETER:
                return INTERPRETER_RENDERER;
            case CLINE:
                return CLINE_RENDERER;
            case JUNIE:
                return JUNIE_RENDERER;
            case FIREBASE:
                return FIREBASE_RENDERER;
            case CLAUDE_LOCAL:
                return CLAUDE_LOCAL_RENDERER;
            case CLAUDE_SKILL:
                return CLAUDE_SKILL_RENDERER;
            case CODERABBIT:
                return CODERABBIT_RENDERER;
            case PR_AGENT:
                return PR_AGENT_RENDERER;
            case ELLIPSIS:
                return ELLIPSIS_RENDERER;
            case VOID:
                return VOID_RENDERER;
            case ROO_MODES:
                return ROO_MODES_RENDERER;
            case LOCKS_REPORT:
                return LOCKS_REPORT_RENDERER;
            case CURSOR_GRANULAR:
            case TRAE_GRANULAR:
            case ROO_GRANULAR:
            case WINDSURF_GRANULAR:
            case CONTINUE_GRANULAR:
            case TABNINE_GRANULAR:
            case AMAZONQ_GRANULAR:
            case AI_RULES_GRANULAR:
            case PEARAI_GRANULAR:
            case KIRO_GRANULAR:
            case CLAUDE_GRANULAR:
            case COPILOT_GRANULAR:
                return GRANULAR_RENDERER;
            default:
                throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
    }

    public static GranularRenderer granularRenderer() {
        return GRANULAR_RENDERER;
    }
}
