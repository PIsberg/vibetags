package com.example.gmm.platform;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIObservability;

/**
 * Metric emission for the reactor.
 *
 * <p>In {@code com.example.gmm.platform}, which the {@code reactor-spine} role deliberately does
 * not match, so this class keeps a per-class granular rule file while core/ and app/ merge into a
 * shared one.
 *
 * <p>Audited as well as instrumented: metric labels are a classic route for user identifiers to
 * reach a dashboard nobody classified as holding PII. It also gives this module a safety-tier
 * guardrail, so it appears in the renderers that emit safety families only.
 *
 * <p>Deliberately {@code @AIAudit} rather than {@code @AILocked}. This reactor opts into
 * {@code .vibetags-locks}, and that report feeds the repository's own locked-files PR guard, so a
 * newly locked example class makes the very commit that introduces it fail the guard. Locking an
 * example class also freezes it against future edits for no benefit to what this fixture
 * demonstrates.
 */
@AIAudit(checkFor = {"PII in metric labels"})
@AIObservability(metrics = {"reactor.render.count", "reactor.render.duration"},
    note = "Metric names are a published contract; renaming one breaks every dashboard reading it")
public class Telemetry {

    public void record(String metric, long value) {
        // Demo fixture: a real implementation would forward to a metrics backend.
    }
}
