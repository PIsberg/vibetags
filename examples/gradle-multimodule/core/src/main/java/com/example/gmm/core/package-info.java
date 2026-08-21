/**
 * The IR data model both other modules of this reactor build on.
 *
 * <p>The guardrails below are declared on the <em>package</em>, not on a class, which is what makes
 * them travel. Built with a {@code .vibetags-manifest} marker at the VibeTags root, this module
 * publishes them into {@code vibetags/manifests/com.example.gmm.core.json} inside its own class
 * output, and any module importing this package with {@code .vibetags-transitive} opted in renders
 * them under "Inherited Guardrails".
 *
 * <p>Under Gradle that output directory is {@code build/classes/java/main/}, and each subproject
 * compiles in its own worker directory, which is precisely why this path is worth asserting on the
 * Gradle side rather than assuming the Maven fixture covers it.
 *
 * <p>Only package-level annotations propagate. The class-level guardrail on {@link IrNode} stays
 * local, because a consumer cannot act on a rule about a class it never sees.
 */
@AISecure(aspect = "Node identity is a security boundary: never build an IrNode from unvalidated "
    + "external input, and never expose its raw name in a URL or log line.")
@AIContext(focus = "Immutable IR data model shared across every module of the reactor.",
    avoids = "Adding mutable state, framework annotations, or a dependency on any sibling module.")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE,
    note = "Every type in this package is safe to publish across threads without synchronization.")
package com.example.gmm.core;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
