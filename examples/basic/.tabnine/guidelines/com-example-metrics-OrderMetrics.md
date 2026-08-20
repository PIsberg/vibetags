<!-- VIBETAGS-START -->
# AI Guidelines for OrderMetrics

### Rules for method recordOrderPlaced
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: orders.placed.total, orders.placed.failed. Traces: order.place. Logs: OrderPlaced, OrderPlacementFailed. Note: Watched by the Orders SLO dashboard (https://grafana.internal/d/orders-slo).
<!-- VIBETAGS-END -->
