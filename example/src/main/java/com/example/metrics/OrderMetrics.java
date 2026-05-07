package com.example.metrics;

import se.deversity.vibetags.annotations.AIObservability;

/**
 * Order placement metrics emitter — wired to the orders SLO dashboard.
 *
 * <p>Demonstrates {@code @AIObservability} — declares the metric counters, trace spans,
 * and log statements that downstream dashboards depend on. AI assistants must not remove
 * or rename the listed instrumentation without flagging the affected dashboard.
 */
public class OrderMetrics {

    @AIObservability(
        metrics = {"orders.placed.total", "orders.placed.failed"},
        traces  = {"order.place"},
        logs    = {"OrderPlaced", "OrderPlacementFailed"},
        note    = "Watched by the Orders SLO dashboard (https://grafana.internal/d/orders-slo)."
    )
    public void recordOrderPlaced(String orderId, boolean success) {
        // Implementation would publish counters, open a span, and log structured events.
    }
}
