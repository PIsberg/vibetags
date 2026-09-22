package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.RoleConfig;
import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.Map;
import java.util.Set;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptor;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptors;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.TransitiveSection;

/**
 * A highly decoupled, thin coordinator that builds AI guardrail files by delegating
 * file rendering to specific PlatformRenderer implementations.
 */
public final class GuardrailContentBuilder {

    /**
     * What an always-loaded file says once a test round's guardrails have left it for
     * {@code TESTING.md}. The wording is a contract from the first release that carries it:
     * consumers commit the files this sentence is written into, so editing it rewrites a generated
     * file in every consuming build. {@code TestingMdRoutingEndToEndTest} holds it to a literal.
     */
    static final String TESTING_POINTER =
        "Guardrails for test code are in TESTING.md. Read it before modifying anything under a test source set.";

    private final AnnotationCollector collector;
    private final Set<String> activeServices;
    private final String projectName;
    private final String generatedHeader;
    private final @Nullable RoleConfig roles;
    private boolean safetyDigest;
    private boolean unrouted;

    public GuardrailContentBuilder(AnnotationCollector collector,
                                   Set<String> activeServices,
                                   String projectName,
                                   String generatedHeader) {
        this(collector, activeServices, projectName, generatedHeader, null);
    }

    public GuardrailContentBuilder(AnnotationCollector collector,
                                   Set<String> activeServices,
                                   String projectName,
                                   String generatedHeader,
                                   @Nullable RoleConfig roles) {
        this.collector = collector;
        // Defensive copy: callers must not be able to mutate the active-services set
        // through the reference they passed in.
        this.activeServices = new java.util.LinkedHashSet<>(activeServices);
        this.projectName = projectName;
        this.generatedHeader = generatedHeader;
        this.roles = roles;
    }

    /**
     * Renders the safety tier only, with no scoped-rules index — the shape a module contributes to
     * a lean indexed reactor root (issue #332). Fluent so the ordinary call sites are untouched.
     */
    public GuardrailContentBuilder safetyDigest() {
        this.safetyDigest = true;
        return this;
    }

    /**
     * Renders every service from the whole model even in a test round with {@code TESTING.md}
     * present: what the round would have written had the file not been there. The processor stores
     * that on the sidecar, so that deleting {@code TESTING.md} and rebuilding only the main sources
     * puts the test guardrails back instead of leaving them in no file.
     */
    public GuardrailContentBuilder unrouted() {
        this.unrouted = true;
        return this;
    }



    /**
     * Result of {@link #build} — service-key → file content, plus per-element granular rule map.
     */
    public static final class Result {
        public final Map<String, String> contentByService;
        public final Map<TaggedElement, GranularBody> elementRules;

        Result(Map<String, String> contentByService, Map<TaggedElement, GranularBody> elementRules) {
            this.contentByService = contentByService;
            this.elementRules = elementRules;
        }
    }

    public Result build() {
        // publishedModel, not model: anything -Avibetags.exclude names is collected so the source
        // ledger stays satisfied, and dropped here so it reaches no file (#792).
        GuardrailModel model = collector.publishedModel();
        // Pre-size renderer output buffers from the collected element count: ~160 bytes of rendered
        // content per annotated reference plus a fixed preamble allowance. Avoids repeated
        // grow-and-copy reallocation of the per-platform StringBuilders on large projects.
        int estimatedContentSize = model.totalAnnotatedReferences() * 160 + 2048;

        // Compute the granular owner set once, before rendering. Aggregate renderers whose granular
        // sibling is active read it from the RenderingContext to emit a scoped-rules index instead
        // of duplicating each element's full guardrails inline. renderGranular depends only on the
        // model, so ordering it ahead of the per-service loop is safe and avoids a redundant
        // per-element walk inside each renderer.
        boolean granularActive = activeServices.stream().anyMatch(ServiceRegistry::writesDirectory);
        Map<TaggedElement, GranularBody> elementRules = granularActive
                ? collector.granularRules()
                : new java.util.LinkedHashMap<>();

        RenderingContext context = new RenderingContext(projectName, generatedHeader, activeServices,
                estimatedContentSize, elementRules.keySet(), roles);
        if (safetyDigest) {
            context = context.asSafetyDigest();
        }
        if (collector.isTestRound()) {
            // The one thing a renderer may know about where the round's sources came from, and
            // today only TESTING.md's renderer asks.
            context = context.asTestRound();
        }
        Map<String, String> contentByService = new java.util.LinkedHashMap<>();
        RoutedViews views = new RoutedViews(model,
            !unrouted && collector.isTestRound() && activeServices.contains("testing"));

        // Render each active service (excluding granular directories and special-case exclusions)
        for (String serviceKey : activeServices) {
            if (serviceKey.equals("aiexclude")) {
                continue;
            }
            if (ServiceRegistry.writesDirectory(serviceKey)) {
                continue;
            }

            Platform platform = Platform.fromServiceKey(serviceKey);
            if (platform != null) {
                GuardrailModel view = views.of(serviceKey);
                String content = PlatformRendererRegistry.getRenderer(platform).render(view, platform, context);
                if (content != null) {
                    contentByService.put(serviceKey,
                        views.withPointer(serviceKey, withTransitiveAppendix(content, view, platform)));
                }
            }
        }

        // Implicit platform activations, the documented exceptions to invariant 1: an output whose
        // own presence on disk is not the opt-in, because another service's is. The descriptor
        // table names the parent (PlatformDescriptor.implicitParent), ServiceRegistry.optInKeys()
        // is derived from it, and this walks the same field rather than repeating it as one `if`
        // per child (issue #830). ImplicitActivationFanOutTest drives this loop off the table, so a
        // sixth entry is rendered the day it is added rather than silently written by nobody.
        //
        // The two Codex sidecars are the file-per-tool exception (.codex/config.toml and
        // .codex/rules/vibetags.rules). The three *_safety files are the rules-directory exception:
        // a directory whose aggregate is the same path has nowhere else to put the always-loaded
        // safety tier, so it gets a file inside the directory (issues #648, #684), and the renderer
        // decides whether the tier belongs there or is already loaded from another file.
        //
        // Qwen has no implicit outputs. .qwen/settings.json is the user's Qwen Code settings file
        // and is never written (#650); .qwen/commands/refactor.md is an ordinary opt-in, rendered
        // by the loop above only when the file exists (#655). Cody is an ordinary opt-in too.
        //
        // views.of for everyone, where the Codex pair asked the router before and the three safety
        // files were handed the whole model. That is the same thing for them: routesTestGuardrails
        // answers false for every *_safety key, so views.of gives back the whole model, and it
        // answers false for a reason rather than by accident, since a safety file carries the six
        // always-loaded buckets and those are exactly the guardrails that never move to TESTING.md.
        // (codex_rules does route, and routed before this too.) Asking the router is also what
        // keeps a future child's answer in ServiceRoutingContractTest, where every key is decided
        // by hand, rather than in an `if` written here. Pinned by the last case in
        // ImplicitActivationFanOutTest, which is where the first draft of this comment, claiming
        // no implicit child routes at all, was caught being wrong about codex_rules.
        for (PlatformDescriptor child : PlatformDescriptors.ALL) {
            String parent = child.implicitParent();
            Platform platform = child.platform();
            if (parent == null || platform == null || !activeServices.contains(parent)) {
                continue;
            }
            putRendered(contentByService, child.serviceKey(), platform,
                views.of(child.serviceKey()), context);
        }

        // Special case for AIExclude platform, which has strict activation criteria
        if (activeServices.contains("aiexclude") && (activeServices.contains("gemini") || activeServices.contains("codex"))) {
            Platform p = Platform.AI_EXCLUDE;
            String content = PlatformRendererRegistry.getRenderer(p).render(model, p, context);
            if (content != null) {
                contentByService.put("aiexclude", content);
            }
        }

        // (granular owner set + elementRules are computed above, before the render loop)

        return new Result(contentByService, elementRules);
    }

    private static void putRendered(Map<String, String> contentByService, String serviceKey,
                                    Platform platform, GuardrailModel model, RenderingContext context) {
        String content = PlatformRendererRegistry.getRenderer(platform).render(model, platform, context);
        if (content != null) {
            contentByService.put(serviceKey, content);
        }
    }

    /**
     * Which slice of the model each service renders. In every round but one, all of it.
     *
     * <p>A test round of a project that has {@code TESTING.md} is routed: {@code TESTING.md} renders
     * the model without its safety annotations, the instruction aggregates
     * ({@link ServiceRegistry#routesTestGuardrails}) render the safety annotations only, and every
     * other service renders the whole model as before. Done here, by handing renderers a smaller
     * model, because a renderer only ever sees a model: none of them learns that routing exists,
     * and a tests-only module with a single sidecar, which the reactor merge skips, is routed the
     * same way as any other.
     */
    private static final class RoutedViews {
        private final GuardrailModel model;
        private final @Nullable GuardrailModel safetyOnly;
        private final @Nullable GuardrailModel withoutSafety;

        RoutedViews(GuardrailModel model, boolean routed) {
            this.model = model;
            this.safetyOnly = routed ? model.safetyOnly() : null;
            this.withoutSafety = routed ? model.withoutSafety() : null;
        }

        GuardrailModel of(String serviceKey) {
            if (safetyOnly == null || withoutSafety == null) {
                return model;
            }
            if ("testing".equals(serviceKey)) {
                return withoutSafety;
            }
            return ServiceRegistry.routesTestGuardrails(serviceKey) ? safetyOnly : model;
        }

        /**
         * {@code content} with the pointer to {@code TESTING.md} appended, when this round moved
         * guardrails out of {@code serviceKey}'s file; otherwise {@code content} unchanged.
         *
         * <p>Appended after the rendered body, never spliced into it, so it cannot land inside a
         * structured element such as {@code <project_guardrails>} and be read as one of the rules.
         * A Markdown file gets it as a sentence; a hash-marker file has no prose, so there it is a
         * comment. Nothing is appended when the test code carried only safety annotations: nothing
         * moved, and an empty {@code TESTING.md} is not worth an agent's read.
         */
        String withPointer(String serviceKey, String content) {
            if (withoutSafety == null || !withoutSafety.anyAnnotationsFound()
                    || !ServiceRegistry.routesTestGuardrails(serviceKey)) {
                return content;
            }
            java.nio.file.Path file = ServiceRegistry.buildServiceFileMap(java.nio.file.Path.of("")).get(serviceKey);
            java.nio.file.Path name = file == null ? null : file.getFileName();
            String[] markers = name == null ? null : GuardrailFileWriter.getMarkersFor(name.toString());
            boolean prose = markers != null && markers[0].startsWith("<!--");
            return content + (content.endsWith("\n") ? "" : "\n") + "\n"
                + (prose ? "" : "# ") + TESTING_POINTER + "\n";
        }
    }

    /**
     * Appends the inherited-guardrail block to a rendered file, when the model has transitive rules
     * and the platform is one that carries them.
     *
     * <p>Applied here rather than inside each renderer for two reasons. Every renderer would
     * otherwise need the same three lines, and thirty copies of a rule is twenty-nine chances for
     * one to drift. More importantly, appending centrally means a platform that has <em>no</em>
     * transitive rules to show renders byte-identically to before this feature existed, so the
     * existing golden-output tests keep their meaning.
     *
     * <p>The block lands inside the module's own rendered body, which is what carries it through
     * the reactor merge into that module's region. A module inherits the rules its own sources
     * import, which is the answer that is true per module rather than per repository.
     */
    private String withTransitiveAppendix(String content, GuardrailModel model, Platform platform) {
        if (!model.anyTransitiveRules() || safetyDigest) {
            // safetyDigest renders the lean indexed reactor root's inline slice. Inherited rules
            // are not a safety tier of this project's own code, so they stay out of it.
            return content;
        }
        String appendix = TransitiveSection.render(model, platform);
        if (appendix.isEmpty()) {
            return content;
        }
        if (content.endsWith("\n\n") && appendix.startsWith("\n")) {
            return content + appendix.substring(1);
        }
        return content + appendix;
    }
}
