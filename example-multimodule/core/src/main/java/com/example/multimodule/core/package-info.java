/**
 * The IR data model every other module of this reactor builds on.
 *
 * <p>The guardrails below are declared on the <em>package</em>, not on a class, which is what makes
 * them travel. When this module is built with a {@code .vibetags-manifest} marker at the VibeTags
 * root, VibeTags publishes them into {@code vibetags/manifests/com.example.multimodule.core.json}
 * inside the module's own output — and any project that imports this package and opted into
 * {@code .vibetags-transitive} renders them into its own AI configuration, under "Inherited
 * Guardrails".
 *
 * <p>In this reactor the consumers are {@code engine}, {@code cli} and {@code tests}. In a real
 * project they would be whoever depends on the published artifact, which is the point: a constraint
 * the library author knows about does not stop at the JAR boundary.
 *
 * <p>Only package-level annotations propagate. The class-level guardrails on {@link IrNode} and
 * {@link IrGraph} stay local, because a consumer cannot act on a rule about a class it never sees.
 */
@AISecure(aspect = "Node identity is a security boundary: never build an IrNode from unvalidated "
    + "external input, and never expose its raw id in a URL or log line.")
@AIContext(focus = "Immutable IR data model shared across every module of the reactor.",
    avoids = "Adding mutable state, framework annotations, or a dependency on any sibling module.")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE,
    note = "Every type in this package is safe to publish across threads without synchronization.")
package com.example.multimodule.core;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
