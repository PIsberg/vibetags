package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

/**
 * What a {@link TaggedElement}'s recorded file names say about which file it may stand for.
 *
 * <p>The rule lives here rather than on {@code TaggedElement} because the model holds data and
 * nothing else; the formatters that write file globs are its only callers.
 */
public final class ElementFiles {

    private ElementFiles() {}

    /**
     * Whether {@code element} is the type its source file is named after, the one element a file
     * glob may stand for. A member is not a file, and neither is a nested type (#926), nor a second
     * top-level type declared in another type's file: a glob for any of them hides, or would hide,
     * every unmarked line of that file too (#940).
     */
    public static boolean ownsSourceFile(TaggedElement element) {
        String sourceFile = element.sourceFile();
        int dot = sourceFile.lastIndexOf('.');
        String fileName = dot < 0 ? sourceFile : sourceFile.substring(0, dot);
        ElementTag kind = element.kind();
        String simpleName = element.simpleName();
        return (ElementKinds.isClass(kind) || ElementKinds.isInterface(kind)) && element.fileStem().equals(simpleName)
            && fileName.equals(simpleName);
    }
}
