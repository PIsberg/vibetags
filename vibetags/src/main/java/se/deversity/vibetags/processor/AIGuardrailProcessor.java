package se.deversity.vibetags.processor;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.AnnotationValidator;
import se.deversity.vibetags.processor.internal.BuildFingerprint;
import se.deversity.vibetags.processor.internal.DestructiveRewriteWarner;
import se.deversity.vibetags.processor.internal.ReactorRootDetector;
import se.deversity.vibetags.processor.internal.GranularRulesWriter;
import se.deversity.vibetags.processor.internal.content.GranularContribution;
import se.deversity.vibetags.processor.internal.GuardrailEnforcer;
import se.deversity.vibetags.processor.internal.GuardrailContentBuilder;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.WholeFileMerge;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.ModuleIdentity;
import se.deversity.vibetags.processor.internal.ModuleRootResolver;
import se.deversity.vibetags.processor.internal.ModuleOutputWriter;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.OrphanWarner;
import se.deversity.vibetags.processor.internal.ProcessorVersion;
import se.deversity.vibetags.processor.model.ContentHash;
import se.deversity.vibetags.processor.model.RoleConfig;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.MethodBodyGuardrailScanner;
import se.deversity.vibetags.processor.internal.SourcePositionResolver;
import se.deversity.vibetags.processor.internal.TransitiveManifestReader;
import se.deversity.vibetags.processor.internal.TransitiveManifestWriter;
import se.deversity.vibetags.processor.internal.WriteCache;
import se.deversity.vibetags.processor.model.TransitiveRule;
import org.slf4j.Logger;

import javax.annotation.processing.*;
import javax.tools.Diagnostic;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The core annotation processor for VibeTags.
 *
 * <p>Orchestrates the work — discovering annotated elements, validating, generating per-platform
 * guardrail content, and writing to disk. The actual work is delegated to focused helpers in the
 * {@link se.deversity.vibetags.processor.internal} package; this class is mostly the
 * {@link AbstractProcessor} adaptor plus a small set of package-private delegates kept in place
 * so the existing test suite continues to compile against the same surface.
 */
@AICore(
    sensitivity = "critical",
    note = "JSR 269 entry point; orchestrates annotation discovery, fingerprint short-circuit, sidecar aggregation, and all file writes"
)
@SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
@SupportedOptions({"vibetags.root", "vibetags.project", "vibetags.log.path", "vibetags.log.level",
                   "vibetags.cache", "vibetags.check", "vibetags.module",
                   "vibetags.enforce", "vibetags.baseline.update",
                   "vibetags.manifest.origin", "vibetags.manifest.dir",
                   "vibetags.manifest.packages", "vibetags.manifest.max"})
public class AIGuardrailProcessor extends AbstractProcessor {

    /** Public constructor for the service loader. */
    public AIGuardrailProcessor() {}

    /**
     * Reports the latest source version the running javac understands, instead of pinning a
     * fixed {@code @SupportedSourceVersion}. The library builds/tests against Java 21; a fixed
     * {@code RELEASE_17} would make javac emit a "supported source version" warning on every
     * newer JDK a consumer compiles with.
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * The annotation types this processor claims: normally VibeTags' own, and {@code "*"} when the
     * project opted into transitive guardrails.
     *
     * <p>javac only invokes a processor whose supported types match something present in the
     * round. A project that inherits all of its guardrails from dependencies annotates nothing of
     * its own, so under the declared list the processor would never be called at all and the
     * inherited rules would never be written — the feature would appear to do nothing, with the
     * only clue being javac's unrelated "options were not recognized by any processor" warning.
     *
     * <p>Widened only for projects carrying the {@code .vibetags-transitive} marker, never by
     * default. Claiming {@code "*"} unconditionally would run VibeTags on every compilation of
     * every consumer, including those with opt-in files and no annotations at all, which today
     * produce nothing and would start producing empty scaffolding.
     *
     * <p>{@code "*"} claims no annotation exclusively: JSR 269 defines it as matching rounds with
     * no annotations too, and claiming is governed by {@code process()}'s return value, which is
     * always {@code false} here.
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return transitiveReader != null
            ? Set.of("*", "se.deversity.vibetags.annotations.*")
            : super.getSupportedAnnotationTypes();
    }

    static final String VERSION = ProcessorVersion.get();
    private static final String GITHUB_URL = "https://github.com/PIsberg/vibetags";

    /** Header written into every generated file — no version so bumping the dep never creates spurious diffs. */
    private static final String GENERATED_HEADER = "# Generated by VibeTags | " + GITHUB_URL + "\n";

    /** Ensures we generate files exactly once per compilation run. */
    private final AtomicBoolean processed = new AtomicBoolean(false);

    /** SLF4J logger backed by a Logback FileAppender writing to {@code vibetags.log} in the project root. */
    private @Nullable Logger log = null;

    /** Lazily constructed file writer; recreated on init() with the live messager + log. */
    private GuardrailFileWriter fileWriter = new GuardrailFileWriter(GENERATED_HEADER, null, null);

    /** Granular rules writer; recreated on init() to share the live fileWriter. */
    private GranularRulesWriter granularWriter = new GranularRulesWriter(fileWriter);

    /** Per-output-file content cache; created on init() pointing at {@code <root>/.vibetags-cache}. */
    private @Nullable WriteCache writeCache = null;

    private Path root;
    private String projectName;

    /**
     * Opt-in check mode ({@code -Avibetags.check=true}): verify generated files instead of
     * writing them, and fail the build on drift. Default {@code false} — normal generation.
     */
    private boolean checkMode;

    /**
     * Whether the machine-readable lock report ({@code .vibetags-locks}) is opted in. Source
     * positions for {@code @AILocked} elements are only consumed by that report, so when it is
     * absent we skip the (allocation-heavy) Tree API position resolution entirely.
     */
    private boolean locksReportEnabled;

    /** Best-effort source line resolution for {@code @AILocked} elements (javac Tree API). */
    private SourcePositionResolver positionResolver = SourcePositionResolver.noop();

    /**
     * Warns about guardrail annotations inside method bodies (local and anonymous declarations),
     * which JSR 269 processing cannot see at all — without this they are a silent no-op.
     */
    private MethodBodyGuardrailScanner bodyScanner = MethodBodyGuardrailScanner.noop();

    /**
     * Module root directory <em>and</em> source set of the compilation being processed, resolved
     * from the sources of the first non-empty processing round (see {@link ModuleRootResolver}).
     * {@code null} until resolved — and stays {@code null} when no compiler API exposes the source
     * file or the sources are in-memory, in which case the JVM working directory is used as before.
     *
     * <p>This is the module identity for multi-module sidecar aggregation; the working directory is
     * NOT usable for that, because reactor builds compile every module in-process with the working
     * directory pinned at the reactor root (issue #278: last-writer-wins on shared guardrail files).
     * The source set half keeps {@code compile} and {@code test-compile} — two javac invocations
     * over disjoint sources of the same module — in separate sidecars (issue #330).
     */
    private @Nullable ModuleIdentity moduleIdentity;

    /** Explicit module name from {@code -Avibetags.module}; overrides the resolved identity. */
    private @Nullable String moduleIdOverride;

    /**
     * Guardrail families the build enforces against {@code .vibetags-baseline}
     * ({@code -Avibetags.enforce}). Empty — the default — leaves every guardrail advisory, which is
     * the whole product's posture; enforcement is something a team opts into per family (#284).
     */
    private Set<String> enforceFamilies = Set.of();

    /** {@code -Avibetags.baseline.update=true}: record the current shapes instead of checking them. */
    private boolean baselineUpdate;

    /**
     * Whether this build publishes its package-level guardrails for downstream consumers
     * ({@code .vibetags-manifest} present at the root). Opt-in, like every other VibeTags output:
     * upgrading the processor must never start putting a library's internals into its JAR.
     */
    private boolean manifestEmitEnabled;

    /** Artifact coordinate stamped into emitted manifests; {@code ""} when the build did not name itself. */
    private String manifestOrigin = "";

    /**
     * Reads dependency manifests off the compile classpath; {@code null} unless the project opted
     * in with {@code .vibetags-transitive}. Held across rounds because discovery needs the live
     * Tree API and the result is consumed once, at the end.
     */
    private @Nullable TransitiveManifestReader transitiveReader;

    /**
     * Cap on inherited advisory rules ({@code -Avibetags.manifest.max}); non-positive means no
     * limit. Safety-tier rules are never dropped, and a cap that drops anything says so as a NOTE.
     */
    private int maxTransitiveAdvisory;

    /** {@code -Avibetags.manifest.dir}: a directory of pre-extracted manifests, or {@code null}. */
    private @Nullable Path manifestDir;

    /** {@code -Avibetags.manifest.packages}: explicit lookup keys for builds with no Tree API. */
    private List<String> manifestPackages = List.of();

    private final AnnotationCollector collector = new AnnotationCollector();
    // Only the three sets actually read in generateFiles() are kept as fields.
    // The rest (contextElements, draftElements, privacyElements, coreElements,
    // performanceElements) were written-only — logSummary() calls collector.*() directly.
    private final Set<Element> lockedElements = collector.locked();
    private final Set<Element> ignoreElements = collector.ignore();
    private final Set<Element> auditElements  = collector.audit();

    /** Per-element granular rule sections, populated by GuardrailContentBuilder.build(). */
    private Map<TaggedElement, se.deversity.vibetags.processor.internal.content.GranularBody> elementRules =
        new java.util.LinkedHashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        Map<String, String> options = processingEnv.getOptions();

        String rootOverride = options.get("vibetags.root");
        this.root = Paths.get((rootOverride != null && !rootOverride.isBlank())
                ? rootOverride
                : Paths.get("").toAbsolutePath().toString()).toAbsolutePath().normalize();

        Messager messager = getSafeMessager();
        messager.printMessage(Diagnostic.Kind.NOTE, "VibeTags: Root resolved: " + this.root);
        messager.printMessage(Diagnostic.Kind.NOTE, "VibeTags: user.dir:      " + System.getProperty("user.dir"));

        // javac forwards every -A option to every processor in the compilation, so only flag
        // keys under our own "vibetags." namespace — anything else may belong to a sibling
        // processor sharing the same javac invocation.
        Set<String> supportedOptions = getSupportedOptions();
        for (String key : options.keySet()) {
            if (key.startsWith("vibetags.") && !supportedOptions.contains(key)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: unrecognized option '" + key + "' (possible typo) — supported options: " + supportedOptions);
            }
        }

        this.projectName = options.getOrDefault("vibetags.project", "This Project");

        // Explicit module name. Only needed when the build cannot be read off the sources — for
        // instance a compiler that exposes neither the Tree API nor Elements.getFileObjectOf, or a
        // layout with no build file above the sources. Named so it reads next to -Avibetags.root.
        String moduleOption = options.get("vibetags.module");
        this.moduleIdOverride = (moduleOption != null && !moduleOption.isBlank())
            ? ModuleSidecar.sanitizeId(moduleOption.trim()) : null;

        String logPath = options.get("vibetags.log.path");
        String logLevel = options.get("vibetags.log.level");
        log = VibeTagsLogger.forRoot(this.root, logPath, logLevel);

        this.checkMode = "true".equalsIgnoreCase(options.getOrDefault("vibetags.check", "false"));
        this.baselineUpdate = "true".equalsIgnoreCase(options.getOrDefault("vibetags.baseline.update", "false"));
        this.enforceFamilies = new GuardrailEnforcer(messager, log).parseFamilies(options.get("vibetags.enforce"));
        // Structural signatures are read by nothing except the enforcing mode, and computing one
        // walks and sorts a type's whole visible member set. Same opt-in shape as the locks report
        // below: pay for it only when something is going to read it.
        collector.captureSignatures(!this.enforceFamilies.isEmpty() || this.baselineUpdate);
        // Position resolution feeds only the .vibetags-locks report; skip it entirely (no Tree API
        // scanning, no per-element allocation) unless that opt-in file is present.
        this.locksReportEnabled = Files.exists(this.root.resolve(".vibetags-locks"));
        this.positionResolver = SourcePositionResolver.forEnv(processingEnv, this.root);
        this.bodyScanner = MethodBodyGuardrailScanner.forEnv(processingEnv);

        String useCache = options.getOrDefault("vibetags.cache", "true");
        if ("false".equalsIgnoreCase(useCache)) {
            this.writeCache = null;
        } else {
            this.writeCache = new WriteCache(this.root.resolve(".vibetags-cache"));
            // Options that shape output without being part of the annotation fingerprint: the
            // project name (the llms.txt H1) and the module override (the region a reactor merge
            // files this module under). Bound as the cache's run context so an option edit
            // regenerates instead of short-circuiting past the change (the fingerprint check
            // itself lives in the step-order-locked generateFiles and stays untouched).
            this.writeCache.bindContext(ContentHash.of(
                "project=" + this.projectName + ";module="
                    + (this.moduleIdOverride == null ? "" : this.moduleIdOverride)));
        }
        this.fileWriter = new GuardrailFileWriter(GENERATED_HEADER, processingEnv.getMessager(), log, this.writeCache);
        this.granularWriter = new GranularRulesWriter(this.fileWriter);

        // --- Transitive guardrails (dependency tree propagation) ---
        // Both halves are file-presence opt-ins, matching every other VibeTags output.
        this.manifestEmitEnabled = TransitiveManifestWriter.optedIn(this.root);
        if (this.manifestEmitEnabled) {
            String originOption = options.get("vibetags.manifest.origin");
            this.manifestOrigin = (originOption != null && !originOption.isBlank())
                ? originOption.strip() : TransitiveManifestWriter.originFrom(this.root);
        } else {
            this.manifestOrigin = "";
        }
        this.transitiveReader = TransitiveManifestReader.optedIn(this.root)
            ? new TransitiveManifestReader(log) : null;
        this.maxTransitiveAdvisory = parsePositiveInt(options.get("vibetags.manifest.max"), messager);
        String dirOption = options.get("vibetags.manifest.dir");
        this.manifestDir = (dirOption != null && !dirOption.isBlank())
            ? this.root.resolve(dirOption.strip()).normalize() : null;
        String packagesOption = options.get("vibetags.manifest.packages");
        this.manifestPackages = (packagesOption == null || packagesOption.isBlank())
            ? List.of()
            : java.util.Arrays.stream(packagesOption.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();

        // Reset for potential reuse (tests reuse the processor instance via init).
        this.processed.set(false);
        this.moduleIdentity = null;
        collector.reset();
        this.elementRules = new java.util.LinkedHashMap<>();
    }

    @AIContract(reason = "JSR 269 contract: must return false so peer annotation processors can claim the same annotations; return type is fixed by AbstractProcessor")
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Guardrail generation is advisory: it must NEVER be able to fail the consumer's
        // compilation. Any unexpected runtime failure is downgraded to a WARNING so javac
        // continues. The one exception is opt-in check mode (-Avibetags.check=true), whose
        // entire purpose is to fail the build — there an internal failure fails closed as an
        // ERROR rather than silently passing. We always return false so peer processors still
        // see the annotations.
        try {
            if (roundEnv.processingOver()) {
                if (roundEnv.errorRaised()) {
                    // An earlier round reported an ERROR: any remaining source-generation rounds
                    // were abandoned, so the collected annotation set may be incomplete — and the
                    // build is failing anyway. Writing from that state would overwrite committed
                    // guardrail files and this module's sidecar with a shrunken view, delete
                    // granular rules as orphans, and record a fingerprint for a compile that never
                    // succeeded. Leave every artifact exactly as this build found it.
                    getSafeMessager().printMessage(Diagnostic.Kind.NOTE,
                        "VibeTags: compilation raised errors before the final round; guardrail files left untouched.");
                    if (log != null) {
                        log.info("Compilation raised errors; skipping generate phase (files untouched).");
                        log.debug("round.skip reason=error-raised");
                    }
                    VibeTagsLogger.shutdown(root);
                    return false;
                }
                // compareAndSet guarantees exactly one thread enters generateFiles() even if
                // two rounds somehow overlap (Gradle daemon / parallel incremental builds).
                if (processed.compareAndSet(false, true)) {
                    // Publishing this build's own package guardrails, and folding in the ones read
                    // off the classpath, both run here rather than inside generateFiles(): its step
                    // order is locked, and its fingerprint short-circuit returns before any of it.
                    // The inherited rules must reach the collector BEFORE that fingerprint is
                    // computed, or a dependency upgrade would be short-circuited past in silence.
                    applyTransitiveRules();
                    // Publishing runs in check mode too, and must. In a reactor that both
                    // publishes and consumes, a module's manifest is what the next module reads
                    // off the classpath — so a check-mode run that skipped publishing would have
                    // every consuming module inherit nothing, compare that against committed files
                    // that correctly carry the inherited rules, and report drift on a build where
                    // nothing is wrong. Verified the hard way: moving this below the branch turned
                    // example-multimodule's check-mode gate red on every Maven leg.
                    //
                    // This does not weaken what check mode promises. CLASS_OUTPUT is the
                    // compiler's own output directory, which javac is filling with class files
                    // regardless; the guarantee is about the files VibeTags manages in the
                    // project, and those are still untouched. See checkFiles().
                    emitTransitiveManifests();
                    // Enforcement runs BEFORE generation, and outside generateFiles(), for two
                    // reasons: generateFiles() has a fingerprint short-circuit that would let an
                    // unchanged-inputs build skip the check silently, and its step order is locked.
                    enforceGuardrails();
                    if (checkMode) {
                        checkFiles();
                    } else {
                        generateFiles();
                    }
                }
                return false;
            }
            if (processed.get()) return false;

            // Resolve the module root from this round's sources (first success wins). Must run
            // while rounds are live — the Tree API cannot map elements back to source afterwards.
            if (moduleIdentity == null) {
                moduleIdentity = ModuleRootResolver.fromRound(processingEnv, roundEnv);
            }

            // The annotation types javac reports as present this round. Lets AnnotationCollector
            // skip getElementsAnnotatedWith() for the ~33 annotation types that are absent (each
            // such query would scan every root element only to return empty). Empty/unknown →
            // null → query every type (preserves behaviour for tests that don't populate it).
            Set<String> presentFqns = null;
            if (!annotations.isEmpty()) {
                presentFqns = new java.util.HashSet<>(annotations.size() * 2);
                for (TypeElement te : annotations) {
                    presentFqns.add(te.getQualifiedName().toString());
                }
            }

            collector.collect(roundEnv, presentFqns);
            // Dependency manifests are looked up from this round's import list, so this must run
            // while the round is live: once processing is over the Tree API can no longer map an
            // element back to its compilation unit.
            if (transitiveReader != null) {
                transitiveReader.scanRound(processingEnv, roundEnv);
            }
            // Positions must be resolved while the round is live — the Tree API cannot map
            // elements back to source once processing is over. Only needed for the .vibetags-locks
            // report; skip when it isn't opted in, or when no @AILocked is present this round.
            if (locksReportEnabled && (presentFqns == null || presentFqns.contains(AILocked.class.getName()))) {
                for (Element e : roundEnv.getElementsAnnotatedWith(AILocked.class)) {
                    collector.recordLockedPosition(e, positionResolver.resolve(e));
                }
            }
            validateAnnotations(processingEnv.getMessager(), roundEnv, presentFqns);
            // Guardrails written where JSR 269 cannot see them (local/anonymous declarations)
            // are a silent no-op; the Tree API can still see them, so say so. Needs the live
            // round for the same reason the position resolver does.
            bodyScanner.scanAndWarn(roundEnv);
        } catch (RuntimeException e) {
            if (checkMode) {
                getSafeMessager().printMessage(Diagnostic.Kind.ERROR,
                    "VibeTags: check mode could not verify guardrail files (failing build): " + e);
                if (log != null) log.error("Check mode failed; failing build (check is opt-in).", e);
            } else {
                getSafeMessager().printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: guardrail generation failed and was skipped (build not affected): " + e);
                if (log != null) log.error("Guardrail generation failed; skipping. Build is unaffected.", e);
            }
        }
        return false; // allow other processors to see the same annotations
    }

    /**
     * The directory identifying the module being compiled: the module root resolved from this
     * compilation's sources when available, else the JVM working directory (previous behavior —
     * correct for single-module builds and non-javac compilers).
     */
    private Path compilationRoot() {
        return moduleIdentity != null ? moduleIdentity.root() : Paths.get("").toAbsolutePath();
    }

    /**
     * Parses a non-negative integer option, warning and falling back to "no limit" on nonsense.
     * A mistyped cap must not fail somebody's compile over an advisory feature.
     */
    private static int parsePositiveInt(@Nullable String value, Messager messager) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            return Math.max(parsed, 0);
        } catch (NumberFormatException e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                "VibeTags: -Avibetags.manifest.max is not a number ('" + value + "'); no limit applied.");
            return 0;
        }
    }

    /**
     * Folds the guardrails discovered on the compile classpath into the collector, so they reach
     * the model every renderer and the build fingerprint read from.
     *
     * <p>Runs before {@link #generateFiles()} for a reason worth stating: that method's first act
     * is a fingerprint short-circuit, and inherited rules are the one input the annotation set
     * cannot speak for. A dependency upgrade changes what the files should say while every
     * annotation in this project is byte-identical, so rules arriving after the fingerprint would
     * simply never be written, with nothing anywhere reporting a problem.
     */
    private void applyTransitiveRules() {
        TransitiveManifestReader reader = this.transitiveReader;
        if (reader == null) {
            return;
        }
        // A build tool that resolved the dependency graph itself, or a compiler with no Tree API,
        // supplies its keys explicitly. Both run in addition to import discovery, never instead:
        // a project may legitimately use one for the module path and the other for the classpath.
        if (manifestDir != null) {
            List<String> rejected = reader.resolveDirectory(manifestDir);
            for (String bad : rejected) {
                getSafeMessager().printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: could not read dependency manifest " + bad);
            }
        }
        if (!manifestPackages.isEmpty()) {
            reader.resolveExplicit(processingEnv.getFiler(), manifestPackages);
        }

        if (reader.treesUnavailable() && manifestDir == null && manifestPackages.isEmpty()) {
            // Unchecked, not clean. Reporting nothing here would read exactly like "no dependency
            // publishes guardrails", which is the answer a reader would act on.
            getSafeMessager().printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: transitive guardrails opted in, but this compiler exposes no Tree API ("
                    + reader.treesUnavailableReason() + "); dependency manifests were not discovered. "
                    + "Use -Avibetags.manifest.dir or -Avibetags.manifest.packages.");
            if (log != null) {
                log.info("Transitive discovery skipped: no Tree API ({}).", reader.treesUnavailableReason());
            }
            return;
        }

        List<TransitiveRule> rules = new java.util.ArrayList<>(reader.rules());
        java.util.Collections.sort(rules);
        int total = rules.size();
        int dropped = 0;
        if (maxTransitiveAdvisory > 0) {
            List<TransitiveRule> kept = new java.util.ArrayList<>(rules.size());
            int advisoryKept = 0;
            for (TransitiveRule rule : rules) {
                if (rule.tier() == TransitiveRule.Tier.SAFETY) {
                    kept.add(rule);
                } else if (advisoryKept < maxTransitiveAdvisory) {
                    kept.add(rule);
                    advisoryKept++;
                } else {
                    dropped++;
                }
            }
            rules = kept;
        }
        collector.addTransitiveRules(rules);

        if (total > 0) {
            getSafeMessager().printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: inherited " + rules.size() + " guardrail(s) from dependencies"
                    + (dropped > 0 ? " (" + dropped + " advisory rule(s) dropped by -Avibetags.manifest.max="
                        + maxTransitiveAdvisory + ")" : "")
                    + " after " + reader.lookupCount() + " classpath lookup(s).");
        }
        if (log != null) {
            log.info("Transitive guardrails: {} kept, {} dropped, {} lookups.",
                rules.size(), dropped, reader.lookupCount());
            if (dropped > 0) {
                // A cap that reports nothing is indistinguishable from full coverage.
                log.warn("transitive.skip reason=advisory-cap cap={} dropped={}", maxTransitiveAdvisory, dropped);
            }
        }
    }

    /**
     * Publishes this build's package-level guardrails into its own class output, where the JAR task
     * picks them up. No-op unless {@code .vibetags-manifest} opted the build in.
     */
    private void emitTransitiveManifests() {
        if (!manifestEmitEnabled) {
            return;
        }
        try {
            List<String> written = TransitiveManifestWriter.emit(
                processingEnv.getFiler(), collector.model(), manifestOrigin, VERSION, log);
            if (!written.isEmpty()) {
                getSafeMessager().printMessage(Diagnostic.Kind.NOTE,
                    "VibeTags: published " + written.size() + " dependency manifest(s) for "
                        + String.join(", ", written)
                        + (manifestOrigin.isEmpty()
                            ? " (no origin coordinate set; consumers will see the rules unattributed)"
                            : " as " + manifestOrigin));
            }
            if (log != null) {
                log.info("Published {} transitive manifest(s).", written.size());
            }
        } catch (IOException | RuntimeException e) {
            // Publishing is advisory. A Filer that refuses the write (a second annotation-processing
            // pass over the same output, a read-only build directory) must not fail the library's
            // build over a file only its consumers read.
            getSafeMessager().printMessage(Diagnostic.Kind.WARNING,
                "VibeTags: could not publish dependency manifests (build not affected): " + e);
            if (log != null) {
                log.warn("manifest.skip reason=write-failed detail={}", e.toString());
            }
        }
    }

    @AILocked(reason = "Step order is load-bearing: fingerprint check → sidecar write → sidecar read → merge → file write → cache flush; reordering steps silently skips regeneration or corrupts multi-module output")
    private void generateFiles() {
        if (log != null) {
            log.info("VibeTags v{} | {}", VERSION, GITHUB_URL);
            log.info("Root: {}", root.toAbsolutePath());
        }

        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        Set<String> activeServices = ServiceRegistry.resolveActiveServices(processingEnv.getMessager(), serviceFiles);

        // Compute module identity for multi-module aggregation. regionId names the module's region
        // in the shared files; moduleId additionally carries the source set, so this compilation
        // owns its own sidecar file and cannot overwrite a sibling source set's (issue #330).
        Path compilationRoot = compilationRoot();
        String regionId = moduleIdOverride != null
            ? moduleIdOverride : ModuleSidecar.computeModuleId(compilationRoot, root);
        String sourceSet = moduleIdentity != null ? moduleIdentity.sourceSet() : ModuleIdentity.MAIN;
        String moduleId = ModuleSidecar.scopedModuleId(regionId, sourceSet);
        String modulePath = ModuleSidecar.computeModulePath(compilationRoot, root);
        warnIfModuleUnidentifiable(compilationRoot, regionId);

        // Per-module (nested) output: resolve this module's own opt-in files quietly. Non-opted
        // modules are the common case in a reactor and must not spam diagnostics. Empty for
        // single-module builds where the compilation root IS the VibeTags root.
        Map<String, Path> moduleServiceFiles = ServiceRegistry.buildServiceFileMap(compilationRoot);
        Set<String> moduleActiveServices = (moduleIdentity == null || compilationRoot.equals(root))
            ? java.util.Set.of()
            : ServiceRegistry.resolveActiveServices(moduleServiceFiles);

        // Role/topic routing for granular files (.vibetags-roles); null when absent → per-class.
        RoleConfig rootRoles = RoleConfig.load(root);
        RoleConfig moduleRoles = (moduleIdentity != null && !compilationRoot.equals(root))
            ? RoleConfig.load(compilationRoot) : null;

        // Top-level fingerprint short-circuit: if neither the annotation set, active services, nor
        // any sibling sidecar have changed since the last run, AND every file we wrote then is
        // still byte-stable on disk, skip the entire content-build + per-file-compare phase.
        // The sidecar stamp is stored separately so the fingerprint stays 8 hex chars. The module's
        // own opt-in set and the roles-config hashes are folded in so a freshly-touched module file
        // or an edited .vibetags-roles is not skipped here.
        Set<String> fingerprintServices = new java.util.LinkedHashSet<>(activeServices);
        fingerprintServices.addAll(moduleActiveServices);
        if (rootRoles != null) {
            fingerprintServices.add("roles:" + rootRoles.contentHash());
        }
        if (moduleRoles != null) {
            fingerprintServices.add("modroles:" + moduleRoles.contentHash());
        }
        // Which module is compiling is an input, not context. Two modules of a reactor can carry
        // byte-identical annotations — a renamed module is the same annotations under a new
        // identity — and without this they share a fingerprint, so the second one would skip and
        // never write its own sidecar. Latent while the stamp below could never match; live the
        // moment the short-circuit started working.
        fingerprintServices.add("module:" + moduleId);
        String fingerprint = BuildFingerprint.compute(collector, fingerprintServices);
        String sidecarStampHex = Long.toHexString(ModuleSidecar.computeSidecarStamp(root));
        if (writeCache != null
                && fingerprint.equals(writeCache.getBuildFingerprint())
                && sidecarStampHex.equals(writeCache.getSidecarStamp())
                // A sidecar whose module directory is gone has to be pruned, and pruning happens
                // in the merge this skip jumps over. Deleting a module changes no annotation and
                // no sidecar mtime, so nothing else in this condition can notice it.
                && !ModuleSidecar.anyStale(root)
                && writeCache.allCachedFilesStable()) {
            Messager m = getSafeMessager();
            m.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: inputs unchanged since last run (fingerprint " + fingerprint
                    + "), skipping content build and writes.");
            if (log != null) {
                log.info("Inputs unchanged (fingerprint {}). Skipping generate phase.", fingerprint);
                log.debug("round.skip reason=fingerprint-match fingerprint={} sidecarStamp={} services={}",
                    fingerprint, sidecarStampHex, activeServices.size());
            }
            VibeTagsLogger.shutdown(root);
            return;
        }

        // Build all per-platform content in one pass (current module only).
        GuardrailContentBuilder.Result built =
            new GuardrailContentBuilder(collector, activeServices, projectName, GENERATED_HEADER, rootRoles).build();
        Map<String, String> contentByService = built.contentByService;
        this.elementRules = built.elementRules;

        // This module's own (nested) output, rendered here rather than inside ModuleOutputWriter so
        // it can be persisted alongside the root bodies below — that is what lets a module's own
        // CLAUDE.md survive a test-compile round that saw none of its main sources (issue #330).
        GuardrailContentBuilder.Result moduleBuilt = moduleActiveServices.isEmpty() ? null
            : new GuardrailContentBuilder(collector, moduleActiveServices, projectName, GENERATED_HEADER, moduleRoles).build();

        logSummary(activeServices);

        // --- Multi-module aggregation ---
        // Write this module's rendered bodies to its sidecar file so siblings can pick them up.
        ModuleSidecar mySidecar = new ModuleSidecar(moduleId, modulePath, regionId);
        populateSidecarBodies(mySidecar, contentByService);
        if (moduleBuilt != null) {
            moduleBuilt.contentByService.forEach(mySidecar::putModuleBody);
        }
        // Granular filenames go in before they are written: siblings read them as cleanup
        // exclusions, so no round deletes rule files it simply could not see (issue #330). The
        // content behind each name goes in with it, because a granular file is not owned by one
        // module either — a role spanning modules resolves to one shared path, and without the
        // contributions the last module to compile replaced it (issue #365).
        Map<String, GranularContribution> myGranular =
            GranularRulesWriter.contributionsFor(built.elementRules, rootRoles);
        myGranular.forEach(mySidecar::putGranularContribution);
        Set<String> myStems = new java.util.LinkedHashSet<>(myGranular.keySet());
        if (moduleBuilt != null) {
            Map<String, GranularContribution> myModuleGranular =
                GranularRulesWriter.contributionsFor(moduleBuilt.elementRules, moduleRoles);
            myModuleGranular.forEach(mySidecar::putModuleGranularContribution);
            myStems.addAll(myModuleGranular.keySet());
        }
        mySidecar.setGranularStems(myStems);
        // The elements themselves, recorded whether or not a granular service is active: this is
        // what lets the next run tell an edited annotation from a round that never saw the sources.
        mySidecar.setElementIds(collector.model().elementIds());
        // Safety-tier digest for the lean indexed reactor root: without it the root keeps NOTHING
        // of a module inline, and @AILocked/@AICore/@AIAudit stop being always-on (issue #332).
        buildSafetyDigests(activeServices, rootRoles).forEach(mySidecar::putIndexDigest);
        // Only persist the sidecar when this compilation actually saw annotations. Maven runs
        // the processor again for test-compile (and other source sets) under the SAME module
        // identity but usually with zero annotations — an unconditional save would overwrite the
        // main compile's sidecar with an empty one, dropping this module from the merged output.
        // Mirrors the hasNewRules guard in GuardrailFileWriter for the single-module case.
        if (collector.anyAnnotationsFound()) {
            // Compare against what this same id recorded last time BEFORE overwriting it: a module
            // whose every element has been replaced by a disjoint set did not have its annotations
            // edited, it had them hidden from this round (issue #330's failure mode).
            ModuleSidecar previous = ModuleSidecar.loadFor(root, moduleId);
            if (previous != null) {
                destructiveWarner().regionReplaced(
                    moduleId, previous.getElementIds(), collector.model().elementIds());
            }
            if (log != null && log.isDebugEnabled()) {
                // bodies=0 means this module contributed nothing for siblings to merge, which
                // looks identical to "no annotations here" in the output and is how issue #265
                // stayed hidden. Recording the counts makes the two distinguishable in one grep.
                log.debug("sidecar.save id={} region={} bodies={} moduleBodies={} stems={} elements={}",
                    mySidecar.getModuleId(), mySidecar.getRegionId(), mySidecar.getBodies().size(),
                    mySidecar.getModuleBodies().size(), mySidecar.getGranularStems().size(),
                    mySidecar.getElementIds().size());
            }
            try {
                mySidecar.save(root);
            } catch (IOException e) {
                getSafeMessager().printMessage(Diagnostic.Kind.NOTE,
                    "VibeTags: Could not save module sidecar (" + e.getMessage() + "); multi-module aggregation disabled.");
            }
        }
        warnIfDetachedFromReactor(compilationRoot);

        // Read all sidecars (this module + any siblings that have already compiled).
        // Read BEFORE readAll, which deletes the stale sidecars that are the only record of which
        // rule files a departed module wrote. Acted on after the claim set below is known.
        Set<String> departedStems = ModuleSidecar.staleGranularStems(root);
        List<ModuleSidecar> allSidecars = ModuleSidecar.readAll(root);
        // Diagnostic only, and it reads the sidecars this round just resolved. No step moved.
        warnAboutPlatformsOptedInAfterAModuleLastCompiled(activeServices, serviceFiles, allSidecars);
        if (log != null && log.isDebugEnabled()) {
            log.debug("sidecar.read count={} regions={} ids={}",
                allSidecars.size(), ModuleSidecar.regionCount(allSidecars),
                allSidecars.stream().map(ModuleSidecar::getModuleId).toList());
        }
        final Map<String, String> effectiveContent =
                mergeAcrossModules(contentByService, serviceFiles, allSidecars, log);

        Messager messager = getSafeMessager();
        messager.printMessage(Diagnostic.Kind.NOTE,
            "VibeTags: Generating files (v" + VERSION + ") for " + activeServices.size()
                + " active services: "
                + activeServices.stream().sorted().collect(Collectors.joining(", "))
                + (isMultiModule(allSidecars)
                    ? " [multi-module: " + ModuleSidecar.regionCount(allSidecars) + " modules]" : ""));

        // Write the per-platform content files in parallel on a VibeTags-owned, bounded
        // ForkJoinPool — NOT the shared commonPool. The processor runs inside the consumer's
        // javac, so borrowing commonPool would contend with whatever else their build is doing;
        // a dedicated pool (sized to the file count, capped at the CPU count) keeps the work
        // isolated and is torn down before we return. Each entry writes a distinct file path, so
        // there is no shared mutable state between tasks. WriteCache is synchronized on every
        // method. The injected Messager is not guaranteed thread-safe; we temporarily swap
        // fileWriter to use a synchronized proxy for the duration of the parallel phase, then
        // restore the original after. Status messages (relPath + status) are collected in a
        // thread-safe queue and emitted sequentially from the main thread.
        GuardrailFileWriter originalWriter = this.fileWriter;
        this.fileWriter = new GuardrailFileWriter(GENERATED_HEADER, new SynchronizedMessager(messager), log, writeCache);
        this.granularWriter = new GranularRulesWriter(this.fileWriter);
        java.util.Queue<String[]> statusQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        int parallelism = Math.min(Runtime.getRuntime().availableProcessors(),
                                   Math.max(1, effectiveContent.size()));
        if (log != null && log.isDebugEnabled()) {
            log.debug("round.write files={} workers={} multiModule={} services={}",
                effectiveContent.size(), parallelism, isMultiModule(allSidecars), effectiveContent.keySet());
        }
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(parallelism);
        try {
            pool.submit(() -> effectiveContent.entrySet().parallelStream().forEach(entry -> {
                String service = entry.getKey();
                String content = entry.getValue();
                Path filePath = serviceFiles.get(service);
                if (filePath == null) {
                    // Rendered content for a service the registry has no output path for. Every
                    // renderer's key is registered today, so this is unreachable — but it is
                    // unreachable by agreement between two collections, not by construction, and
                    // the dereference below runs inside a parallelStream: an NPE here surfaces as
                    // an ExecutionException that abandons the whole write phase, so one unmapped
                    // key would cost every other file its update. Skipping the one entry keeps a
                    // registry gap to the file it belongs to. Mirrors the guard in checkFiles().
                    if (log != null) {
                        log.warn("write.skip file=<unmapped> service={} reason=no-service-path", service);
                    }
                    return;
                }
                boolean isIgnoreFile = service.endsWith("_ignore") || "aider_ignore".equals(service) || "aiexclude".equals(service);
                // hasNewRules: true if any module (not just this one) contributed to this service.
                boolean anyContributed = isMultiModule(allSidecars)
                    ? allSidecars.stream().anyMatch(s -> s.getBodies().containsKey(service))
                    : collector.anyAnnotationsFound();
                boolean changed = writeFileIfChanged(filePath.toString(), content, anyContributed || isIgnoreFile);
                String relPath = root.relativize(filePath).toString().replace('\\', '/');
                statusQueue.add(new String[]{relPath, changed ? "updated" : "no changes"});
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // Surface to the top-level guard in process(), which downgrades it to a WARNING.
            // Chain the ExecutionException so the original failure's stack trace is preserved.
            throw new IllegalStateException("VibeTags parallel file write failed", e);
        } finally {
            pool.shutdown();
            this.fileWriter = originalWriter;
            this.granularWriter = new GranularRulesWriter(this.fileWriter);
        }
        // Emit status messages sequentially from the main thread.
        for (String[] entry : statusQueue) {
            messager.printMessage(Diagnostic.Kind.NOTE, "VibeTags: " + entry[1] + " - " + entry[0]);
            if (log != null) log.info("{} — {}", entry[0], entry[1]);
        }

        // Per-class granular rule files (Cursor / Trae / Roo) + cleanup of orphans. Each file is
        // written from every module's contribution, not just this one's, so a role spanning modules
        // keeps all of their guardrails (issue #365). The exclusion list carries every OTHER
        // sidecar's stems as well, so this round leaves alone the rule files written by another
        // module or another source set of this one (issue #330).
        Set<String> writtenQNames = new java.util.LinkedHashSet<>(
            granularWriter.writeAll(elementRules, serviceFiles, activeServices, rootRoles,
                ModuleSidecar.mergeGranular(allSidecars)));
        writtenQNames.addAll(ModuleSidecar.granularStemsFrom(allSidecars, moduleId, null));
        // Only a round compiling the root itself may sweep the root's granular directory. A module
        // round cannot tell an orphan from a sibling it has not been shown: `.vibetags-mod-*` is
        // gitignored, so on a fresh clone the sidecars appear one module at a time, and every module
        // before the last sees a directory full of files nothing has claimed yet. Sweeping on that
        // evidence deleted 256 tracked rule files on a cold `mvn -pl core clean compile`, exit 0
        // (issue #383); a full reactor hid it because a later module rewrote them before the end.
        //
        // Counting sidecars is not a strong enough test — it was tried, and the sweep simply moved
        // to reactor module 3, which sees two siblings and still not the fourth. Jurisdiction, not
        // arithmetic, is the rule: a module owns its own directory (ModuleOutputWriter) and its own
        // mirrors (cleanupMirrored, already scoped this way), never the shared root. The cost is a
        // genuinely orphaned root rule file surviving until the root compiles, which matches the
        // trade already documented for an emptied module's last contribution.
        //
        // The predicate is the one lines 366 and 372 already use for "this is a reactor module
        // round". `compilationRoot.equals(root)` alone is NOT equivalent and was tried: a
        // single-module project with no pom.xml to anchor the compilation root fails it, and five
        // rename/delete cleanup tests went red because ordinary orphan cleanup stopped happening.
        boolean maySweepRoot = moduleIdentity == null || compilationRoot.equals(root);
        Set<String> removedQNames = maySweepRoot
            ? granularWriter.cleanupAll(serviceFiles, activeServices, writtenQNames)
            : Set.of();
        if (!maySweepRoot && log != null && log.isDebugEnabled()) {
            log.debug("granular.sweep.skip reason=module-round-not-root module={} written={}",
                moduleId, writtenQNames.size());
        }
        // myGranular's keys ARE the stems this round planned — the same map the sidecar recorded,
        // so the sweep can never be judged against a differently-computed set.
        destructiveWarner().orphanSweep("the reactor root", removedQNames, myGranular.keySet());

        // Rule files whose module has left the build. Not the sweep above and not bound by its
        // jurisdiction rule: these stems were named by a sidecar whose module directory is gone,
        // which is evidence rather than the absence #383 forbids arguing from. Stems a surviving
        // module still claims are excluded, so a shared role file is rewritten by the merge rather
        // than deleted here.
        Set<String> orphanedByDeparture = new java.util.LinkedHashSet<>(departedStems);
        orphanedByDeparture.removeAll(writtenQNames);
        Set<String> departedRemoved =
            granularWriter.removeStems(serviceFiles, activeServices, orphanedByDeparture);
        if (!departedRemoved.isEmpty()) {
            getSafeMessager().printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: removed " + departedRemoved.size()
                    + " granular rule file(s) belonging to a module that is no longer in the build.");
            if (log != null) {
                log.info("granular.departed.removed stems={} module={}", departedRemoved, moduleId);
            }
        }

        // Per-module (nested) output — write this module's own guardrails into its own directory.
        // Independent of the cross-module aggregation above (which serves only the shared root
        // files): module content only, merged across this module's source sets. No-op for
        // single-module builds.
        ModuleOutputWriter.write(compilationRoot, root, moduleServiceFiles, moduleActiveServices,
            collector, moduleBuilt, projectName, GENERATED_HEADER, moduleRoles, this.fileWriter,
            messager, allSidecars, regionId, moduleId);

        checkOrphanedAnnotations(messager, activeServices,
            !lockedElements.isEmpty(),
            !ignoreElements.isEmpty(),
            !auditElements.isEmpty());

        if (writeCache != null) {
            writeCache.setBuildFingerprint(fingerprint);
            // Recomputed here rather than reusing sidecarStampHex from the top of this method. That
            // value was read BEFORE the sidecar write above, and the stamp hashes sidecar mtimes —
            // so storing it guaranteed a mismatch on the next round and the short-circuit could
            // never fire in any build that writes a sidecar, which is every build. What the next
            // round compares against is the sidecars as this round leaves them, so that is what has
            // to be recorded. No step moved: this is the same flush, with the value it needed.
            writeCache.setSidecarStamp(Long.toHexString(ModuleSidecar.computeSidecarStamp(root)));
            writeCache.flush();
        }
        collector.reset();
        this.elementRules = new java.util.LinkedHashMap<>();
        VibeTagsLogger.shutdown(root);
    }

    /**
     * Warns when a platform was opted into after some module last compiled, so the merged file is
     * missing that module's guardrails.
     *
     * <p>Creating an opt-in file at a reactor root activates a platform for the whole build, but a
     * sidecar only carries bodies for the services that were active when its module last ran. An
     * incremental build recompiles the modules whose sources changed and no others, so the new
     * file is assembled from a subset — well-formed, plausible, and missing whole modules. Nothing
     * about it looks wrong, and check mode would compare a later build against it happily.
     *
     * <p>The content cannot be repaired from here: rendering a module's body needs that module's
     * annotations, which only its own compilation sees. So the honest remedy is to say so, name the
     * modules, and let the developer run the full build that fixes it. A WARNING rather than a NOTE
     * because the file on disk is wrong until they do.
     */
    private void warnAboutPlatformsOptedInAfterAModuleLastCompiled(
            Set<String> activeServices, Map<String, Path> serviceFiles,
            List<ModuleSidecar> allSidecars) {
        if (allSidecars.size() < 2) {
            return; // Single module: its own round is by definition current.
        }
        for (String service : activeServices) {
            Path optIn = serviceFiles.get(service);
            if (optIn == null || !Files.isRegularFile(optIn)) {
                continue;
            }
            long optedInAt;
            try {
                optedInAt = Files.getLastModifiedTime(optIn).toMillis();
            } catch (IOException e) {
                continue;
            }
            List<String> behind =
                ModuleSidecar.modulesPredatingOptIn(root, service, optedInAt, allSidecars);
            if (behind.isEmpty()) {
                continue;
            }
            Path file = serviceFiles.get(service);
            getSafeMessager().printMessage(Diagnostic.Kind.WARNING,
                "VibeTags: " + root.relativize(file).toString().replace('\\', '/')
                    + " was opted into after " + String.join(", ", behind)
                    + " last compiled, so " + (behind.size() == 1 ? "its guardrails are" : "their guardrails are")
                    + " missing from it. Run a full build to complete the file.");
            if (log != null) {
                log.warn("merge.partial service={} modulesBehind={} reason=opted-in-after-last-compile",
                    service, behind);
            }
        }
    }

    /**
     * Opt-in verification mode ({@code -Avibetags.check=true}). Runs the same service
     * resolution, content build, and multi-module merge as {@link #generateFiles()}, but
     * touches none of the files VibeTags manages in the project — no output files, no sidecar,
     * no cache. Every file a normal compile would create, update, scrub, or delete is instead
     * reported as a compile ERROR, failing the build until the consumer regenerates and commits.
     * Intended for CI drift detection; the fingerprint short-circuit and write cache are
     * deliberately bypassed so the verdict never depends on cache state.
     *
     * <p>The one thing still written is the dependency manifest, into {@code CLASS_OUTPUT} — the
     * compiler's own output directory, which javac is filling with class files regardless. It has
     * to be: in a reactor that both publishes and consumes, one module's manifest is what the next
     * reads off the classpath, so skipping it would make every consuming module inherit nothing and
     * report drift against committed files that are perfectly correct. Nothing in the project tree
     * changes, which is the guarantee this mode exists to give.
     */
    private void checkFiles() {
        if (log != null) {
            log.info("VibeTags v{} | {} — check mode (no files will be written)", VERSION, GITHUB_URL);
            log.info("Root: {}", root.toAbsolutePath());
        }

        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        Set<String> activeServices = ServiceRegistry.resolveActiveServices(processingEnv.getMessager(), serviceFiles);
        RoleConfig checkRootRoles = RoleConfig.load(root);

        GuardrailContentBuilder.Result built =
            new GuardrailContentBuilder(collector, activeServices, projectName, GENERATED_HEADER, checkRootRoles).build();
        Map<String, String> contentByService = built.contentByService;

        // Simulate this module's sidecar save in memory: the merge below must reflect the
        // freshly built bodies, not whatever a previous compile persisted to disk. Skipped when
        // this compilation saw no annotations, mirroring the conditional save in generateFiles()
        // (a test-compile pass must not evict the main compile's contribution from the merge).
        Path compilationRoot = compilationRoot();
        String regionId = moduleIdOverride != null
            ? moduleIdOverride : ModuleSidecar.computeModuleId(compilationRoot, root);
        String sourceSet = moduleIdentity != null ? moduleIdentity.sourceSet() : ModuleIdentity.MAIN;
        String moduleId = ModuleSidecar.scopedModuleId(regionId, sourceSet);
        String modulePath = ModuleSidecar.computeModulePath(compilationRoot, root);

        // This module's own opt-ins and content, resolved here rather than just before the nested
        // write, because the simulated sidecar below has to carry them (as generateFiles does).
        Map<String, Path> moduleServiceFiles = ServiceRegistry.buildServiceFileMap(compilationRoot);
        Set<String> moduleActiveServices = (moduleIdentity == null || compilationRoot.equals(root))
            ? java.util.Set.of()
            : ServiceRegistry.resolveActiveServices(moduleServiceFiles);
        RoleConfig checkModuleRoles = (moduleIdentity != null && !compilationRoot.equals(root))
            ? RoleConfig.load(compilationRoot) : null;
        GuardrailContentBuilder.Result moduleBuilt = moduleActiveServices.isEmpty() ? null
            : new GuardrailContentBuilder(collector, moduleActiveServices, projectName, GENERATED_HEADER, checkModuleRoles).build();

        ModuleSidecar mySidecar = new ModuleSidecar(moduleId, modulePath, regionId);
        populateSidecarBodies(mySidecar, contentByService);
        if (moduleBuilt != null) {
            moduleBuilt.contentByService.forEach(mySidecar::putModuleBody);
        }
        Map<String, GranularContribution> myGranular =
            GranularRulesWriter.contributionsFor(built.elementRules, checkRootRoles);
        myGranular.forEach(mySidecar::putGranularContribution);
        Set<String> myStems = new java.util.LinkedHashSet<>(myGranular.keySet());
        if (moduleBuilt != null) {
            Map<String, GranularContribution> myModuleGranular =
                GranularRulesWriter.contributionsFor(moduleBuilt.elementRules, checkModuleRoles);
            myModuleGranular.forEach(mySidecar::putModuleGranularContribution);
            myStems.addAll(myModuleGranular.keySet());
        }
        mySidecar.setGranularStems(myStems);
        mySidecar.setElementIds(collector.model().elementIds());
        buildSafetyDigests(activeServices, checkRootRoles).forEach(mySidecar::putIndexDigest);
        List<ModuleSidecar> allSidecars = new java.util.ArrayList<>(ModuleSidecar.readAll(root));
        if (collector.anyAnnotationsFound()) {
            boolean replaced = false;
            for (int i = 0; i < allSidecars.size(); i++) {
                if (allSidecars.get(i).getModuleId().equals(moduleId)) {
                    allSidecars.set(i, mySidecar);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                // readAll() returns sidecars sorted by filename (= moduleId); keep that ordering
                // so the merged sub-marker sequence matches what generateFiles() would produce.
                allSidecars.add(mySidecar);
                allSidecars.sort(java.util.Comparator.comparing(ModuleSidecar::getModuleId));
            }
            // The substituted sidecar is fresh out of memory and carries none of the lean-index
            // state readAll() derives from disk, so re-derive it for the whole list. Without this
            // a lean indexed reactor embeds this module's body where generation would have linked
            // it, and check mode reports drift that a real compile would never produce.
            ModuleSidecar.applyRootIndexModeTo(root, allSidecars);
        }
        // A check verdict is only trustworthy if it reproduces generation exactly, which is why
        // this calls the same function generateFiles() calls rather than mirroring its body.
        final Map<String, String> effectiveContent =
                mergeAcrossModules(contentByService, serviceFiles, allSidecars, log);

        // Dry-run writer: null messager (per-file "Updated" notes would be misleading here),
        // null cache (a verification verdict must come from real file compares, never the cache).
        GuardrailFileWriter checkWriter = new GuardrailFileWriter(GENERATED_HEADER, null, log, null, true);
        for (Map.Entry<String, String> entry : effectiveContent.entrySet()) {
            String service = entry.getKey();
            Path filePath = serviceFiles.get(service);
            if (filePath == null) {
                continue; // rendered content for a service with no configured output path: nothing to check
            }
            boolean isIgnoreFile = service.endsWith("_ignore") || "aider_ignore".equals(service) || "aiexclude".equals(service);
            boolean anyContributed = isMultiModule(allSidecars)
                ? allSidecars.stream().anyMatch(s -> s.getBodies().containsKey(service))
                : collector.anyAnnotationsFound();
            checkWriter.writeFileIfChanged(filePath.toString(), entry.getValue(), anyContributed || isIgnoreFile);
        }
        GranularRulesWriter checkGranular = new GranularRulesWriter(checkWriter);
        Set<String> writtenQNames = new java.util.LinkedHashSet<>(
            checkGranular.writeAll(built.elementRules, serviceFiles, activeServices, checkRootRoles,
                ModuleSidecar.mergeGranular(allSidecars)));
        writtenQNames.addAll(ModuleSidecar.granularStemsFrom(allSidecars, moduleId, null));
        checkGranular.cleanupAll(serviceFiles, activeServices, writtenQNames);

        // Per-module (nested) output — dry-run so check mode verifies module-scoped files too.
        // Null messager: the summary note would be misleading in a verification pass.
        ModuleOutputWriter.write(compilationRoot, root, moduleServiceFiles, moduleActiveServices,
            collector, moduleBuilt, projectName, GENERATED_HEADER, checkModuleRoles, checkWriter,
            null, allSidecars, regionId, moduleId);

        Messager messager = getSafeMessager();
        List<String> drift = checkWriter.dryRunChanges();
        if (drift.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: check passed — all " + effectiveContent.size()
                    + " active guardrail files are in sync with the annotations.");
            if (log != null) log.info("Check passed: {} guardrail files in sync.", effectiveContent.size());
        } else {
            StringBuilder sb = new StringBuilder("VibeTags: check failed — ")
                .append(drift.size())
                .append(" guardrail file(s) are out of date with the annotations:");
            for (String p : drift) {
                String rel;
                try {
                    rel = root.relativize(Paths.get(p)).toString().replace('\\', '/');
                } catch (IllegalArgumentException e) {
                    rel = p;
                }
                sb.append("\n  - ").append(rel);
            }
            sb.append("\nRun a normal compile (without -Avibetags.check=true) and commit the regenerated files.");
            messager.printMessage(Diagnostic.Kind.ERROR, sb.toString());
            if (log != null) log.error("Check failed: {} guardrail file(s) out of date.", drift.size());
        }
        VibeTagsLogger.shutdown(root);
    }

    /**
     * Renders each indexable aggregate's safety tier on its own, for the lean indexed reactor root.
     *
     * <p>The root cannot carry this module's scoped-rules index — those files live under the module
     * directory — so it gets a pointer sentence instead. That alone moved {@code @AILocked},
     * {@code @AICore} and {@code @AIAudit} out of always-on context and into "loads when you open
     * the very file it protects", which is the point at which a guardrail has become a comment
     * (issue #332). This digest is what the root keeps inline beside the pointer.
     *
     * <p>Empty unless the root opted into the index, this is a non-root module, and the module has
     * something in the safety tier at all — an empty digest would be a bare container element.
     */
    private Map<String, String> buildSafetyDigests(Set<String> activeServices, @Nullable RoleConfig roles) {
        if (!activeServices.contains("root_index") || moduleIdentity == null
                || compilationRoot().equals(root) || !hasSafetyTierAnnotations()) {
            return Map.of();
        }
        Map<String, String> digests = new java.util.LinkedHashMap<>();
        for (String aggregate : ModuleSidecar.INDEXABLE_AGGREGATES) {
            if (!activeServices.contains(aggregate)) continue;
            // Rendering with the granular sibling "active" is what selects each renderer's indexed
            // variant (safety inline, detail elsewhere); safetyDigest() then drops the index list.
            Set<String> digestServices = new java.util.LinkedHashSet<>();
            digestServices.add(aggregate);
            digestServices.add(aggregate + "_granular");
            String body = new GuardrailContentBuilder(collector, digestServices, projectName,
                    GENERATED_HEADER, roles).safetyDigest().build().contentByService.get(aggregate);
            if (body != null && !body.isBlank()) {
                digests.put(aggregate, body);
            }
        }
        return digests;
    }

    /**
     * Runs the opt-in enforcing mode (issue #284). A no-op unless {@code -Avibetags.enforce} names
     * at least one family, so the advisory default is completely untouched.
     */
    private void enforceGuardrails() {
        if (enforceFamilies.isEmpty() && !baselineUpdate) {
            return;
        }
        GuardrailEnforcer enforcer = new GuardrailEnforcer(getSafeMessager(), log);
        Set<String> families = enforceFamilies.isEmpty()
            // -Avibetags.baseline.update on its own means "record everything enforceable", so a
            // first-time adopter does not have to name the families twice.
            ? enforcer.parseFamilies(GuardrailEnforcer.ALL)
            : enforceFamilies;
        Path compilationRoot = compilationRoot();
        String regionId = moduleIdOverride != null
            ? moduleIdOverride : ModuleSidecar.computeModuleId(compilationRoot, root);
        String moduleId = ModuleSidecar.scopedModuleId(regionId,
            moduleIdentity != null ? moduleIdentity.sourceSet() : ModuleIdentity.MAIN);
        enforcer.enforce(collector.model(), families, root, moduleId, baselineUpdate);
    }

    /** Reports rounds that remove guardrails rather than add them (see the class javadoc there). */
    private DestructiveRewriteWarner destructiveWarner() {
        return new DestructiveRewriteWarner(getSafeMessager(), log);
    }

    /**
     * Warns when this compilation generated a complete set of guardrail files as its own root while
     * an ancestor's build definition names it as one of its modules.
     *
     * <p>That is what a module which did not inherit {@code -Avibetags.root} looks like: it renders
     * correctly into its own directory, contributes nothing to the reactor, and its whole
     * {@code <project_guardrails>} section is simply absent from the merged root — silently
     * (<a href="https://github.com/PIsberg/vibetags/issues/296">issue #296</a>). The check is gated
     * on the reactor <em>declaring</em> this directory as a module, so a standalone project that
     * merely lives inside another repository never trips it.
     */
    private void warnIfDetachedFromReactor(Path compilationRoot) {
        if (!compilationRoot.equals(root) || !collector.anyAnnotationsFound()) {
            return; // already sharing a root with siblings, or nothing to contribute anyway
        }
        Path reactorRoot = ReactorRootDetector.findReactorRootAbove(root);
        if (reactorRoot == null) {
            return;
        }
        String moduleName = reactorRoot.relativize(root).toString().replace('\\', '/');
        getSafeMessager().printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: module '" + moduleName + "' generated its guardrails as its own root ("
                + root + "), but " + reactorRoot + " declares it as a module. Its guardrails are"
                + " NOT part of that reactor's merged files. Pass -Avibetags.root=" + reactorRoot
                + " for this module — most often it overrides the compiler plugin's compilerArgs or"
                + " annotationProcessorPaths and so does not inherit the reactor's configuration.");
        if (log != null) {
            log.warn("module.detached module={} moduleRoot={} reactorRoot={}", moduleName, root, reactorRoot);
        }
    }

    /** True when anything in the always-on safety tier was annotated this compilation. */
    private boolean hasSafetyTierAnnotations() {
        return !collector.locked().isEmpty()
            || !collector.core().isEmpty()
            || !collector.privacy().isEmpty()
            || !collector.ignore().isEmpty()
            || !collector.audit().isEmpty()
            || !collector.secure().isEmpty();
    }

    /**
     * Warns when the module could only be identified by a content hash while the project already
     * has named regions. An unrecognised id in a file that already carries named ones is far more
     * likely to be a mis-identified module than a genuinely new one, and the symptom — a duplicate
     * set of regions that survives {@code git checkout} because it is restored from a gitignored
     * sidecar — does not point at its cause (issue #331).
     */
    private void warnIfModuleUnidentifiable(Path compilationRoot, String moduleId) {
        // -Avibetags.module is the documented remedy; having taken it, the user does not need the
        // lecture.
        if (moduleIdOverride != null) return;
        if (!ModuleSidecar.isUnidentifiableModule(compilationRoot, root)) return;
        List<String> named = new java.util.ArrayList<>();
        for (ModuleSidecar existing : ModuleSidecar.readAll(root)) {
            if (!existing.getModuleId().equals(moduleId)) {
                named.add(existing.getModuleId());
            }
        }
        if (named.isEmpty()) return;
        getSafeMessager().printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: could not identify the compiling module (its sources are not under "
                + root + "), so it is filed under the content hash '" + moduleId
                + "' alongside the existing module(s) " + String.join(", ", named)
                + ". If that hash is really one of them under another name, the shared guardrail"
                + " files will gain a duplicate set of regions. Pass -Avibetags.module=<name> to"
                + " name it, or -Avibetags.root so the module resolves under the root.");
        if (log != null) {
            log.warn("module.unidentified id={} root={} compilationRoot={} existing={}",
                moduleId, root, compilationRoot, named);
        }
    }

    private void logSummary(Set<String> activeServices) {
        if (log == null) return;
        log.info("Active services ({}): {}", activeServices.size(),
            activeServices.stream().sorted().collect(Collectors.joining(", ")));
        collector.model().labeledSets().forEach(this::logSet);
    }

    private void logSet(String label, Set<TaggedElement> elements) {
        // logSummary() returns early when log is null, but that is an argument rather than
        // something the compiler can check — and this is a method reference, so it is one
        // refactor away from being called from somewhere that does not check.
        if (log == null || elements.isEmpty()) return;
        String names = elements.stream()
            .map(TaggedElement::simpleName)
            .collect(Collectors.joining(", "));
        log.info("{}: {} — {}", label, elements.size(), names);
    }

    // ---------------------------------------------------------------------------------------
    // Test-facing delegates (kept on this class so the existing test surface still compiles).
    //
    // The three below are NOT deprecated: each is still called from production code on this
    // class (process() or the @AILocked generateFiles()), so they cannot be reduced to
    // test-only surface without editing that production call site — which, for
    // generateFiles(), is explicitly off-limits.
    // ---------------------------------------------------------------------------------------

    void validateAnnotations(Messager messager, RoundEnvironment roundEnv, @Nullable Set<String> presentFqns) {
        AnnotationValidator.validate(messager, roundEnv, processingEnv, presentFqns);
    }

    void checkOrphanedAnnotations(Messager messager, Set<String> active, boolean hasLocked, boolean hasIgnore, boolean hasAudit) {
        OrphanWarner.warnAboutOrphans(messager, log, active, hasLocked, hasIgnore, hasAudit);
    }

    /**
     * Writes {@code content} to {@code path} through the marker-aware writer, if it differs from
     * what is already there.
     *
     * @param path        the file to write, absolute or relative to the VibeTags root
     * @param content     the generated body; for a marker file this is the block between the
     *                    markers, and hand-authored content outside them is preserved
     * @param hasNewRules whether this round produced any rules. When false the writer leaves an
     *                    existing file alone rather than emptying it, so a compile that saw no
     *                    annotations cannot wipe guardrails another round wrote
     * @return {@code true} if the file was created or changed, {@code false} if it was left as-is
     */
    public boolean writeFileIfChanged(String path, String content, boolean hasNewRules) {
        return fileWriter.writeFileIfChanged(path, content, hasNewRules);
    }

    // ---------------------------------------------------------------------------------------
    // Deprecated test-only delegates. Unused by any production code path on this class; kept
    // only for external/legacy test-surface compatibility since they've been public (or
    // package-visible) API since v0.1. Call the internal/ replacement directly instead.
    // ---------------------------------------------------------------------------------------

    /** @deprecated call {@link AnnotationValidator#validate(Messager, RoundEnvironment, ProcessingEnvironment)} directly. */
    @Deprecated
    void validateAnnotations(Messager messager, RoundEnvironment roundEnv) {
        AnnotationValidator.validate(messager, roundEnv, processingEnv);
    }

    /** @deprecated call {@link GuardrailFileWriter#cleanupGranularDirectory(Path, String)} directly. */
    @Deprecated
    void cleanupGranularDirectory(Path dir, String extension) {
        fileWriter.cleanupGranularDirectory(dir, extension);
    }

    /** @deprecated call {@link GuardrailFileWriter#cleanupGranularDirectory(Path, String, Set)} directly. */
    @Deprecated
    void cleanupGranularDirectory(Path dir, String extension, Set<String> excludeQNames) {
        fileWriter.cleanupGranularDirectory(dir, extension, excludeQNames);
    }

    /**
     * Maps every known service key to the file or directory it would be written to.
     *
     * @param root the VibeTags root that output paths are resolved against
     * @return service key to output path, for every service the processor knows about, whether or
     *         not that path exists — presence is what decides activation, not this map
     * @deprecated call {@link ServiceRegistry#buildServiceFileMap(Path)} directly.
     */
    @Deprecated
    public static Map<String, Path> buildServiceFileMap(Path root) {
        return ServiceRegistry.buildServiceFileMap(root);
    }

    /**
     * Narrows the full service map to the services this project has opted into.
     *
     * @param messager        where to report activation notes to the compiler
     * @param allServiceFiles every known service and its output path, as
     *                        {@link #buildServiceFileMap(Path)} returns it
     * @return the keys whose output file already exists, since file presence is the opt-in
     * @deprecated call {@link ServiceRegistry#resolveActiveServices(Messager, Map)} directly.
     */
    @Deprecated
    public static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
        return ServiceRegistry.resolveActiveServices(messager, allServiceFiles);
    }

    /** @deprecated call {@link GuardrailFileWriter#stripLegacyVibeTagsBlock(String)} directly. */
    @Deprecated
    String stripLegacyVibeTagsBlock(String before) {
        return fileWriter.stripLegacyVibeTagsBlock(before);
    }

    /**
     * Returns the messager from {@code processingEnv}. Both call sites run after
     * {@link #init(javax.annotation.processing.ProcessingEnvironment)} has populated
     * {@code processingEnv} via {@code super.init(...)}, so the field is non-null here.
     */
    private Messager getSafeMessager() {
        return processingEnv.getMessager();
    }

    /**
     * Combines every sibling module's contribution into the shared output files.
     *
     * <p>Called by both {@code generateFiles} and {@code checkFiles}. It used to be a block copied
     * into each, marked {@code CPD-OFF} and justified on the grounds that {@code generateFiles} is
     * {@code @AILocked} so nothing could be lifted out of it. That reasoning does not survive
     * contact: the lock is on the <em>step order</em> of {@code generateFiles}, and calling a pure
     * function where the block used to sit preserves that order exactly. What the copy actually
     * bought was drift — the check copy grew a null guard on {@code getFileName()} that the
     * generate copy never got, so the two differed in precisely the way the comment promised they
     * would not, and a check verdict is worthless the moment it stops reproducing generation.
     *
     * <p>Multi-module here means more than one sidecar <em>or</em> a reactor root that opted into
     * the lean index: the merge path also owns pointer substitution, and a reactor where one module
     * holds all the annotations produces exactly one sidecar, so gating purely on count would
     * silently ignore the opt-in.
     *
     * @return the per-service content to write; {@code contentByService} unchanged when this is not
     *         a multi-module build
     */
    static boolean isMultiModule(List<ModuleSidecar> allSidecars) {
        // Counts sidecar FILES, because this gates the merge, and two source sets of one module are
        // two sidecars whose bodies still have to be combined — otherwise the test-compile round
        // writes its own content over the main round's (issue #330). Whether the merged output
        // gains VIBETAGS-MODULE sub-markers is the separate question mergeFor() answers per region,
        // so a lone module compiled twice keeps its historical sub-marker-free shape.
        return allSidecars.size() > 1 || ModuleSidecar.isRootIndexMode(allSidecars);
    }

    /**
     * Records every rendered body on this module's sidecar.
     *
     * <p>Shared by {@code generateFiles} and {@code checkFiles} rather than written out twice, for
     * the reason the merge below is shared: the two copies drifted. This one stored bodies only for
     * marker-based services, which cost more than the merge it was written for - the write phase
     * reads the same map to decide whether a shared file may be rewritten at all, so a JSON or TOML
     * output was permanently "no module contributed" and never refreshed (issue #265). Fixing one
     * copy and not the other then made check mode report drift on a tree a real compile had just
     * produced, because the two disagreed about what the merge should see.
     *
     * <p>{@code contentByService} already excludes granular directories, so every entry is a real
     * output file.
     */
    private static void populateSidecarBodies(ModuleSidecar sidecar,
                                              Map<String, String> contentByService) {
        contentByService.forEach(sidecar::putBody);
    }

    static Map<String, String> mergeAcrossModules(Map<String, String> contentByService,
                                                  Map<String, Path> serviceFiles,
                                                  List<ModuleSidecar> allSidecars) {
        return mergeAcrossModules(contentByService, serviceFiles, allSidecars, null);
    }

    /**
     * As above, narrating each decision to {@code log} at DEBUG.
     *
     * <p>Every branch here ends in one of two outcomes: this module's own rendering is published,
     * or the merged view of every module is. From the written file alone the two are hard to tell
     * apart, because both are valid documents and the wrong one is merely missing its siblings.
     * Issue #265 was exactly that, and it survived as long as it did because nothing recorded which
     * branch ran. So every path states its reason, and {@code merge.skip} never omits one.
     *
     * @param log where to narrate, or {@code null} to merge silently
     */
    static Map<String, String> mergeAcrossModules(Map<String, String> contentByService,
                                                  Map<String, Path> serviceFiles,
                                                  List<ModuleSidecar> allSidecars,
                                                  @Nullable Logger log) {
        // Non-null means "DEBUG is on": one reference carries both facts, so nothing below
        // formats an argument at a disabled level and NullAway can still see the guard.
        final @Nullable Logger debugLog = log != null && log.isDebugEnabled() ? log : null;
        if (!isMultiModule(allSidecars)) {
            if (debugLog != null) {
                debugLog.debug("merge.skip reason=single-module sidecars={} services={}",
                    allSidecars.size(), contentByService.size());
            }
            return contentByService;
        }
        if (debugLog != null) {
            // The count, not the names: each service that merges says so on its own line
            // below, and 44 of them on one line buries the two that matter.
            debugLog.debug("merge.begin modules={} regions={} services={}",
                allSidecars.size(), ModuleSidecar.regionCount(allSidecars),
                contentByService.size());
        }
        Map<String, String> merged = new java.util.LinkedHashMap<>(contentByService);
        for (Map.Entry<String, Path> entry : serviceFiles.entrySet()) {
            String service = entry.getKey();
            if (!contentByService.containsKey(service)) continue;
            // Path.getFileName() returns null only for root paths — guard for correctness.
            Path fileName = entry.getValue().getFileName();
            if (fileName == null) continue;
            String[] markers = GuardrailFileWriter.getMarkersFor(fileName.toString());
            if (markers == null) {
                // JSON and TOML have nowhere to put a marker, so these files are whole-file
                // overwrites. Keeping the compiling module's rendering publishes one module's view
                // of the project; the renderer supplies a format-aware merge where the file holds
                // per-element content (issue #265). Where it does not — the static configs — there
                // is nothing to merge and every module renders the same bytes anyway.
                WholeFileMerge wholeFile = PlatformRendererRegistry.wholeFileMergeFor(service);
                if (wholeFile == null) {
                    if (debugLog != null) {
                        debugLog.debug("merge.skip service={} reason=no-whole-file-merger file={}",
                            service, fileName);
                    }
                    continue;
                }
                List<Map.Entry<String, String>> contributions =
                    ModuleSidecar.contributionsFor(service, allSidecars);
                String document = wholeFile.merge(contributions);
                if (document != null && !document.isBlank()) {
                    merged.put(service, document);
                    if (debugLog != null) {
                        debugLog.debug("merge.wholefile service={} contributions={} bytes={}",
                            service, contributions.size(), document.length());
                    }
                } else if (debugLog != null) {
                    // The merger declined: an unexpected document shape, so it refuses to guess
                    // rather than corrupt the file. This module's own rendering ships instead,
                    // which is valid but sibling-blind. That trade is worth a line in the log.
                    debugLog.debug("merge.skip service={} reason={} contributions={}",
                        service, document == null ? "merger-declined" : "merger-empty",
                        contributions.size());
                }
                continue;
            }
            boolean htmlMarkers = GuardrailFileWriter.MARKER_START_MD.equals(markers[0]);
            String mergedBody = ModuleSidecar.mergeFor(service, allSidecars, htmlMarkers);
            if (!mergedBody.isBlank()) {
                merged.put(service, mergedBody);
                if (debugLog != null) {
                    debugLog.debug("merge.markers service={} html={} bytes={}",
                        service, htmlMarkers, mergedBody.length());
                }
            } else if (debugLog != null) {
                debugLog.debug("merge.skip service={} reason=empty-merged-body html={}",
                    service, htmlMarkers);
            }
        }
        return merged;
    }

    /**
     * Thread-safe {@link Messager} proxy used during the parallel file-write phase.
     * Serializes all {@code printMessage} calls onto the delegate via a lock, so
     * concurrent worker threads in {@code ForkJoinPool.commonPool()} cannot race on
     * the underlying (non-thread-safe) javac Messager.
     */
    private static final class SynchronizedMessager implements Messager {
        private final Messager delegate;

        SynchronizedMessager(Messager delegate) {
            this.delegate = delegate;
        }

        @Override public synchronized void printMessage(Diagnostic.Kind kind, CharSequence msg) {
            delegate.printMessage(kind, msg);
        }
        @Override public synchronized void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
            delegate.printMessage(kind, msg, e);
        }
        @Override public synchronized void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e,
                                                        javax.lang.model.element.AnnotationMirror a) {
            delegate.printMessage(kind, msg, e, a);
        }
        @Override public synchronized void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e,
                                                        javax.lang.model.element.AnnotationMirror a,
                                                        javax.lang.model.element.AnnotationValue v) {
            delegate.printMessage(kind, msg, e, a, v);
        }
    }
}
