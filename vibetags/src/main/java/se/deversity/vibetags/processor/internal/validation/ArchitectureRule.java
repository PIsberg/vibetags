package se.deversity.vibetags.processor.internal.validation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import se.deversity.vibetags.annotations.AIArchitecture;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;

/**
 * {@code @AIArchitecture} — the one guardrail whose violation the processor can see directly in the
 * source, and therefore the one that reports an {@code ERROR} rather than a warning.
 *
 * <p>{@code cannotReference} names packages or types this element must not depend on. The check
 * reads the compilation unit's import list through the Tree API, which is javac-only: under a
 * compiler that does not expose {@link Trees} the scan is skipped with a {@code NOTE} rather than
 * failing, because a layering rule going unchecked must not become a build that cannot run at all.
 * The import list is also the limit of what this proves — a fully-qualified reference written
 * inline is invisible here, by design, since chasing it needs body analysis the processor does not
 * do portably.
 */
public final class ArchitectureRule implements ValidationRule {

    @Override
    public Class<? extends Annotation> scans() {
        return AIArchitecture.class;
    }

    @Override
    public void check(ValidationContext ctx, Element element) {
        AIArchitecture arch = element.getAnnotation(AIArchitecture.class);
        if (arch == null) {
            return;
        }
        if (arch.belongsTo() == null || arch.belongsTo().isBlank()) {
            ctx.warn(element, "@AIArchitecture on " + element
                + " has a blank 'belongsTo' attribute. Name the layer or component it belongs to.");
        }

        String[] forbidden = arch.cannotReference();
        ProcessingEnvironment env = ctx.processingEnv();
        if (forbidden == null || forbidden.length == 0 || env == null) {
            return;
        }
        try {
            scanImports(ctx, element, forbidden, env);
        } catch (Throwable t) {
            // Trees is unavailable (Gradle's compiler, ECJ, a mocked environment) or threw. A
            // layering rule that cannot be checked is reported as unchecked, never as a failure.
            ctx.note(element, "Trees API not available for AST architectural import scanning: " + t.getMessage());
        }
    }

    private static void scanImports(ValidationContext ctx, Element element, String[] forbidden,
                                    ProcessingEnvironment env) {
        Trees trees = Trees.instance(env);
        if (trees == null) {
            return;
        }
        TreePath path = trees.getPath(element);
        if (path == null) {
            return;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        if (unit == null) {
            return;
        }
        for (ImportTree imported : unit.getImports()) {
            String importStr = imported.getQualifiedIdentifier().toString();
            for (String forbiddenPkg : forbidden) {
                if (forbiddenPkg == null || forbiddenPkg.isBlank()) {
                    continue;
                }
                if (matches(importStr, forbiddenPkg)) {
                    ctx.error(element, "Class " + element
                        + " is annotated with @AIArchitecture and is strictly prohibited from referencing '"
                        + forbiddenPkg + "', but has an illegal import of '" + importStr + "'.");
                }
            }
        }
    }

    /**
     * An import matches when it names the forbidden entry exactly, sits under it as a package, or
     * is the on-demand form of it — {@code com.example.forbidden}, {@code com.example.forbidden.Foo}
     * and {@code com.example.forbidden.*} all match {@code com.example.forbidden}.
     */
    private static boolean matches(String importStr, String forbiddenPkg) {
        return importStr.equals(forbiddenPkg)
            || importStr.startsWith(forbiddenPkg + ".")
            || importStr.startsWith(forbiddenPkg + "*");
    }
}
