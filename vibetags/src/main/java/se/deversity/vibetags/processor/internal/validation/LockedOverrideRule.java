package se.deversity.vibetags.processor.internal.validation;

import se.deversity.vibetags.annotations.AILocked;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * An override of an {@code @AILocked} method carries none of the lock's protection: SOURCE
 * retention and the absence of {@code @Inherited} mean the override never reaches
 * {@code getElementsAnnotatedWith}, so no generated guardrail file mentions it and an AI agent is
 * free to rewrite the replacement logic while the locked original stays byte-identical. The
 * processor cannot make the lock follow — what it can do is warn at the one moment the author is
 * looking.
 *
 * <p>Deliberately narrow, because a warning that fires on ordinary work is a warning people learn
 * to skip: an <em>abstract</em> locked method has no body to lock and implementing it is the
 * intended use, and an override that carries {@code @AILocked} itself means the guardrail followed
 * — neither is reported. Only same-compilation overrides are visible at all; a subclass compiled
 * separately cannot be (the annotation is not in the class file), which {@code docs/ANNOTATIONS.md}
 * states as a boundary.
 *
 * <p>Cost: the subtype walk runs once per locked <em>method</em> (not per locked element), over
 * the round's root types. Locked methods are rare and the walk is allocation-free, so this stays
 * inside the registry's one-query-per-annotation budget.
 */
final class LockedOverrideRule implements ValidationRule {

    @Override
    public Class<? extends Annotation> scans() {
        return AILocked.class;
    }

    @Override
    public void check(ValidationContext ctx, Element element) {
        if (element.getKind() != ElementKind.METHOD
                || element.getModifiers().contains(Modifier.ABSTRACT)) {
            return;
        }
        ProcessingEnvironment env = ctx.processingEnv();
        if (env == null) {
            return; // mocked environment: no utils to resolve overriding with
        }
        Elements elements = env.getElementUtils();
        Types types = env.getTypeUtils();
        Set<? extends Element> roots = ctx.rootElements();
        if (elements == null || types == null || roots == null) {
            return;
        }
        ExecutableElement locked = (ExecutableElement) element;
        if (!(locked.getEnclosingElement() instanceof TypeElement owner)) {
            return;
        }
        for (Element root : roots) {
            visitTypes(ctx, root, owner, locked, elements, types);
        }
    }

    /** Walks {@code root} and its nested types, reporting unlocked overrides of {@code locked}. */
    private void visitTypes(ValidationContext ctx, Element root, TypeElement owner,
                            ExecutableElement locked, Elements elements, Types types) {
        if (!(root instanceof TypeElement candidate)) {
            return;
        }
        if (!candidate.equals(owner)
                && types.isSubtype(types.erasure(candidate.asType()), types.erasure(owner.asType()))) {
            for (Element member : candidate.getEnclosedElements()) {
                if (member.getKind() != ElementKind.METHOD
                        || member.getAnnotation(AILocked.class) != null) {
                    continue; // not a method, or the guardrail followed the override
                }
                if (elements.overrides((ExecutableElement) member, locked, candidate)) {
                    AILocked lock = locked.getAnnotation(AILocked.class);
                    ctx.warn(member, member + " in " + candidate.getQualifiedName() + " overrides "
                        + owner.getQualifiedName() + "." + locked + ", which is @AILocked"
                        + (lock != null && !lock.reason().isBlank() ? " (\"" + lock.reason() + "\")" : "")
                        + ". A lock does not follow overrides — no generated guardrail file mentions"
                        + " this method, so the locked behaviour can be replaced here unseen."
                        + " Annotate the override with @AILocked as well, or name the extension"
                        + " point in the locked reason.");
                }
            }
        }
        for (Element enclosed : root.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                visitTypes(ctx, enclosed, owner, locked, elements, types);
            }
        }
    }
}
