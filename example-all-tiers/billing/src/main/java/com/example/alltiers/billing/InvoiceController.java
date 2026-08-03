package com.example.alltiers.billing;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AISecureLogging;

/**
 * Every level a guardrail can attach to, in one class.
 *
 * <p>Read this next to the generated files to see where each one lands:
 *
 * <ul>
 *   <li><b>type</b> — {@code @AIAudit} is safety tier, so it stays inline in the Tier-1 root even
 *       though this module has scoped rules. {@code @AIContext} is verbose tier and does not.</li>
 *   <li><b>field</b> — {@code @AIPrivacy} is safety tier and inline; {@code @AISecureLogging} sits
 *       beside it on the same field and is verbose tier.</li>
 *   <li><b>method</b> — {@code @AILocked} inline, {@code @AIContract} and {@code @AIPerformance}
 *       scoped.</li>
 *   <li><b>parameter</b> — {@code @AIInputSanitized}, which can only go on a parameter, and shows
 *       up in the generated rules addressed as {@code method(...)#parameterName}.</li>
 * </ul>
 *
 * <p>The roles file puts this class in the {@code api-endpoints} topic, so its Tier-3 detail is in
 * {@code billing/.claude/rules/api-endpoints.md} rather than a file named after the class.
 */
@AIAudit(checkFor = {"SQL Injection", "Broken Access Control"})
@AIContext(
    focus = "The public invoicing surface; keep responses stable for partner integrations",
    avoids = "Returning entities directly — always map to a response record")
@AIPublicAPI(reason = "Three partner integrations are pinned to v1 of these endpoints")
public class InvoiceController {

    @AIPrivacy(reason = "Billing email identifies a natural person; never log it or echo it back")
    @AISecureLogging(AISecureLogging.MaskingPolicy.HASH)
    private final String billingEmail;

    @AIPerformance(constraint = "Read on every request; keep it a field, never a lookup")
    private final String tenantId;

    public InvoiceController(String billingEmail, String tenantId) {
        this.billingEmail = billingEmail;
        this.tenantId = tenantId;
    }

    @AILocked(reason = "The invoice number format is on partner contracts; changing it breaks reconciliation")
    public String invoiceNumber(long sequence) {
        return tenantId + "-INV-" + sequence;
    }

    @AIContract(reason = "Serialized straight to the partner API; the parameter order is the wire format")
    @AIPerformance(constraint = "Must complete in <20ms p99 — it is on the checkout path")
    public String renderInvoice(
            String templateId,
            @AIInputSanitized({AIInputSanitized.SanitizerType.XSS,
                               AIInputSanitized.SanitizerType.SQL_INJECTION}) String customerNote) {
        return templateId + ":" + customerNote + ":" + billingEmail.hashCode();
    }
}
