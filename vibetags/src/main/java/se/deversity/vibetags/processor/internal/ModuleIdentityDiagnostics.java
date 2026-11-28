package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The warnings about a compilation whose module identity will cost it guardrails: generated as its
 * own root although a reactor declares it, sharing the root's identity with every Gradle subproject
 * that has no build file, filed under a content hash beside named modules, or filed under another
 * live module's id.
 *
 * <p>None of these can be repaired from inside a javac round, which does not know which module it
 * belongs to. Each names the option that fixes it: {@code -Avibetags.root} or
 * {@code -Avibetags.module}. Having passed {@code -Avibetags.module}, a build is not told again.
 */
public final class ModuleIdentityDiagnostics {

    private final Path root;
    private final Messager messager;
    private final @Nullable String moduleIdOverride;
    private final @Nullable Logger log;

    /**
     * @param root             the VibeTags root this compilation writes under
     * @param messager         where the warnings go
     * @param moduleIdOverride the {@code -Avibetags.module} value, or {@code null} when none was passed
     * @param log              the diagnostic log, or {@code null}
     */
    public ModuleIdentityDiagnostics(Path root, Messager messager, @Nullable String moduleIdOverride,
                                     @Nullable Logger log) {
        this.root = root;
        this.messager = messager;
        this.moduleIdOverride = moduleIdOverride;
        this.log = log;
    }

    /**
     * Warns when this compilation generated a complete set of guardrail files as its own root while
     * an ancestor's build definition names it as one of its modules.
     *
     * <p>That is what a module which did not inherit {@code -Avibetags.root} looks like: it renders
     * correctly into its own directory, contributes nothing to the reactor, and its whole
     * {@code <project_guardrails>} section is simply absent from the merged root, silently
     * (<a href="https://github.com/PIsberg/vibetags/issues/296">issue #296</a>). The check is gated
     * on the reactor <em>declaring</em> this directory as a module, so a standalone project that
     * merely lives inside another repository never trips it.
     */
    public void warnIfDetachedFromReactor(Path compilationRoot, boolean contributes) {
        if (!compilationRoot.equals(root) || !contributes) {
            return; // already sharing a root with siblings, or nothing to contribute anyway
        }
        ReactorRootDetector.findReactorRootAbove(root).ifPresent(this::warnDetachedFrom);
    }

    /** Says that this module generated as its own root although {@code reactorRoot} declares it. */
    private void warnDetachedFrom(Path reactorRoot) {
        String moduleName = PathStrings.relative(reactorRoot, root);
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: module '" + moduleName + "' generated its guardrails as its own root ("
                + root + "), but " + reactorRoot + " declares it as a module. Its guardrails are"
                + " NOT part of that reactor's merged files. Pass -Avibetags.root=" + reactorRoot
                + " for this module — most often it overrides the compiler plugin's compilerArgs or"
                + " annotationProcessorPaths and so does not inherit the reactor's configuration.");
        if (log != null) {
            log.warn("module.detached module={} moduleRoot={} reactorRoot={}", moduleName, root, reactorRoot);
        }
    }

    /**
     * Warns when the module could only be identified by a content hash while the project already
     * has named regions. An unrecognised id in a file that already carries named ones is far more
     * likely to be a mis-identified module than a genuinely new one, and the symptom, a duplicate
     * set of regions that survives {@code git checkout} because it is restored from a gitignored
     * sidecar, does not point at its cause (issue #331).
     *
     * <p>Runs {@code warnIfSubprojectsShareTheRootIdentity} first: the same mistake seen from the
     * root, where every buildless Gradle subproject resolves to one identity.
     */
    public void warnIfModuleUnidentifiable(Path compilationRoot, String moduleId) {
        warnIfSubprojectsShareTheRootIdentity(compilationRoot);
        // -Avibetags.module is the documented remedy; having taken it, the user does not need the
        // lecture.
        if (moduleIdOverride != null) return;
        if (!ModuleSidecar.isUnidentifiableModule(compilationRoot, root)) return;
        List<String> named = new ArrayList<>();
        // A read for a diagnostic, and it runs before generateFiles() reads the departed stems:
        // pruning here would throw that record away first.
        for (ModuleSidecar existing : ModuleSidecar.peekAll(root)) {
            if (!existing.getModuleId().equals(moduleId)) {
                named.add(existing.getModuleId());
            }
        }
        if (named.isEmpty()) return;
        messager.printMessage(Diagnostic.Kind.WARNING,
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

    /**
     * Warns when Gradle subprojects are declared in {@code settings.gradle} but carry no build
     * file of their own, so they all resolve to the same module identity.
     *
     * <p>A module root is the nearest ancestor holding a {@code pom.xml} or {@code build.gradle
     * (.kts)}; {@code settings.gradle} is deliberately not one of those markers, because it names
     * the root of a build rather than a module inside it. When every subproject is configured from
     * the root build file the walk passes straight through them, so each writes the same sidecar
     * and overwrites the one before it. The surviving aggregate carries the last subproject to
     * compile and nothing else, with no duplicate and no stale text to give it away: issue #278's
     * last-writer-wins, in the one layout where the tree cannot supply an identity.
     *
     * <p>Nothing in a javac round says which Gradle subproject it belongs to, so this cannot be
     * repaired here. Naming the option that fixes it is the whole remedy.
     *
     * <p>Narrow on purpose. It fires only when the compiling module resolved to the VibeTags root
     * itself, only for included directories that exist, carry sources, and have no build file of
     * their own, and never when {@code -Avibetags.module} has already been passed.
     */
    private void warnIfSubprojectsShareTheRootIdentity(Path compilationRoot) {
        if (moduleIdOverride != null || !compilationRoot.equals(root)) {
            return;
        }
        List<String> collapsing = collapsingGradleSubprojects();
        if (collapsing.isEmpty()) {
            return;
        }
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + String.join(", ", collapsing)
                + (collapsing.size() == 1 ? " is declared as a Gradle subproject but has"
                                          : " are declared as Gradle subprojects but have")
                + " no build file of its own, so it resolves to this root and shares one module"
                + " identity with every other such subproject. They overwrite one another's"
                + " guardrails and only the last to compile survives. Pass"
                + " -Avibetags.module=${project.name} in the shared build file to give each one"
                + " its own identity.");
        if (log != null) {
            log.warn("module.collapsed subprojects={} root={} reason=no-build-file-of-their-own",
                collapsing, root);
        }
    }

    private List<String> collapsingGradleSubprojects() {
        List<String> collapsing = new ArrayList<>();
        for (String settingsName : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(settingsName);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            for (String included : ReactorRootDetector.gradleIncludes(settings)) {
                if (!collapsing.contains(included) && isBuildlessSubprojectWithSources(root.resolve(included))) {
                    collapsing.add(included);
                }
            }
        }
        return collapsing;
    }

    private static boolean isBuildlessSubprojectWithSources(Path dir) {
        return Files.isDirectory(dir)
            && Files.isDirectory(dir.resolve("src"))
            && !ModuleRootResolver.hasBuildFile(dir);
    }

    /**
     * Warns when another live module files its sidecar under this compilation's id (#869): the two
     * share one region, so each one's build replaces the other's guardrails in the shared files,
     * and the only other thing said about it is the replaced-region warning, which blames the
     * compilation's sources. Renaming ids would move every committed region marker, so the fix is
     * named instead: {@code -Avibetags.module} gives one of them an id of its own. Raised on the
     * early exit too, like every warning a no-op rebuild must repeat (#859).
     */
    public void warnAboutIdCollision(String moduleId, String modulePath) {
        String theirs = ModuleSidecar.collidingModulePath(root, moduleId, modulePath);
        if (theirs == null) {
            return;
        }
        String here = modulePath.isEmpty() ? "(root)" : PathStrings.forwardSlashes(modulePath);
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: modules '" + here + "' and '" + theirs + "' both resolve to the module id '" + moduleId
                + "', so they share one sidecar and one region, and each build of one replaces the other's"
                + " guardrails in the shared files. Give one of them an id of its own with -Avibetags.module=<name>.");
        if (log != null) {
            log.warn("sidecar.collision moduleId={} path={} other={}", moduleId, here, theirs);
        }
    }
}
