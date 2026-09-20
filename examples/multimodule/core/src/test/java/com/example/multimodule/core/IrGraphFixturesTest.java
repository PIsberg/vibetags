package com.example.multimodule.core;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;

/**
 * The reactor's one annotated test source set, so {@code TESTING.md} has something to merge.
 *
 * <p>Routing is decided per compilation, and a reactor compiles each module's main and test source
 * sets separately. Without an annotated test round somewhere in the reactor, the root
 * {@code TESTING.md} would be an opted-in file holding nothing but a header, which is the one
 * shape this repository's own notes call a defect rather than an output.
 *
 * <p>Both halves of the routing rule are here, so the committed files prove the split rather than
 * just the move: the {@code @AIContext} goes to {@code TESTING.md}, the {@code @AILocked} stays in
 * the always-loaded aggregates with the rest of the safety tier.
 *
 * <p>No test framework: the reactor declares none, and these are annotated sources for the
 * processor to read, not tests to run. {@code multimodule-tests} is the module that stands in for
 * a real suite, and it deliberately carries no annotations at all ({@code issues/312}).
 */
@AIContext(
    focus = "Build IrGraph fixtures through IrGraphFixtures.of(...), never by mutating a graph "
        + "in place: the engine's layout cache keys on node identity",
    avoids = "Sharing one fixture graph between cases; the engine mutates what it lays out"
)
@AILocked(
    reason = "The golden graphs below are the layout engine's recorded output for the 1.0 "
        + "release. Regenerating them makes the suite agree with the current engine instead of "
        + "with the release it is meant to protect."
)
public final class IrGraphFixturesTest {

    private IrGraphFixturesTest() {
    }
}
