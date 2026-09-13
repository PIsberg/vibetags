package se.deversity.vibetags.processor;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The one rule every test that walks the repository tree uses to stay inside this checkout.
 *
 * <p>A directory below the root that holds its own {@code .git} is a different checkout: an agent
 * worktree under {@code .claude/worktrees/} (where {@code .git} is a file pointing at the main
 * repository) or a third-party repository cloned into the tree (where it is a directory). Neither
 * is tracked here, both carry another branch's or another project's files, and a walk that reads
 * them reports that content as this repository's (issue #678).
 *
 * <p>The root itself is exempt, because it is a checkout too. Without that exemption a walk
 * started at the root would skip everything and every test built on it would pass vacuously.
 */
final class NestedCheckouts {

    private NestedCheckouts() {
    }

    /** Whether {@code dir}, reached by a walk started at {@code root}, is a separate git checkout. */
    static boolean isNestedCheckout(Path root, Path dir) {
        return !dir.equals(root) && Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
    }
}
