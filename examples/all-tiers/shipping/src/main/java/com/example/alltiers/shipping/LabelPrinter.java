package com.example.alltiers.shipping;

import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadAffinity;

/**
 * The second module, so Tier 2 is a real boundary rather than a formality.
 *
 * <p>Its guardrails appear in {@code shipping/CLAUDE.md} (Tier 2) and
 * {@code shipping/.claude/rules/} (Tier 3), and only its safety-tier ones — {@code @AISecure},
 * {@code @AIIgnore} — are inline in the reactor-root index. The billing module's rules are
 * nowhere in this module's files, which is the point of Tier 2.
 */
@AISecure(aspect = "Carrier credentials and label signing")
public class LabelPrinter {

    @AIIgnore(reason = "Cached carrier handshake token; meaningless outside one process")
    private transient String carrierSessionToken;

    @AIObservability(
        metrics = {"shipping.label.printed", "shipping.label.failed"},
        traces = {"shipping.label"},
        note = "The Shipping SLO dashboard alerts on these two counters")
    @AIBannedApi(
        forbidden = {"java.util.Date", "java.text.SimpleDateFormat"},
        useInstead = "java.time",
        reason = "SimpleDateFormat is not thread-safe and this runs on the printer pool")
    public byte[] print(String shipmentId) {
        carrierSessionToken = shipmentId;
        return shipmentId.getBytes();
    }

    @AICallersOnly({"com.example.alltiers.shipping.LabelPrinter"})
    @AIThreadAffinity(
        value = AIThreadAffinity.Affinity.BACKGROUND_ONLY,
        thread = "printer-pool",
        marshalVia = "PrinterExecutor.submit",
        symptomIfViolated = "The request thread blocks on the printer socket and the endpoint times out")
    void writeToPrinter(byte[] payload) {
        // Deliberately empty: the example is about where guardrails land, not about printing.
    }
}
