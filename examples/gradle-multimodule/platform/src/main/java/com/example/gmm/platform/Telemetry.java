package com.example.gmm.platform;

import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;

/**
 * Metric emission for the reactor.
 *
 * <p>In {@code com.example.gmm.platform}, which the {@code reactor-spine} role deliberately does
 * not match, so this class keeps a per-class granular rule file while core/ and app/ merge into a
 * shared one.
 *
 * <p>Locked as well as instrumented, and the two say the same thing from different angles: the
 * metric names are a published contract. It also gives this module a safety-tier guardrail, so it
 * appears in the renderers that emit safety families only.
 */
@AILocked(reason = "Metric names are a published contract; renaming one breaks every dashboard "
    + "and alert reading them")
@AIObservability(metrics = {"reactor.render.count", "reactor.render.duration"},
    note = "Metric names are a published contract; renaming one breaks every dashboard reading it")
public class Telemetry {

    public void record(String metric, long value) {
        // Demo fixture: a real implementation would forward to a metrics backend.
    }
}
