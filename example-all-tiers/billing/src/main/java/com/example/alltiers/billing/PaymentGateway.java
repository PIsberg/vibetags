package com.example.alltiers.billing;

import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;

/**
 * The guardrails about a thing's <em>lifecycle</em> — what is on its way out, what is behind a flag,
 * what must not be tidied up.
 *
 * <p>None of these are safety tier, so in the Tier-1 root this whole class is represented by the
 * module pointer and nothing else. That is the index working: an agent editing a shipping label
 * never loads any of it.
 */
@AIStrictClasspath(reason = "Runs inside the PCI sandbox where reflection and custom classloaders are refused")
@AIStrictExceptions(reason = "A bare catch(Exception) here once swallowed a timeout and double-charged")
public class PaymentGateway {

    @AIKeepInSync(
        mirrors = {"com.example.alltiers.billing.InvoiceController"},
        reason = "The gateway's tenant prefix and the invoice number format encode the same tenant id",
        enforcedBy = "TaxRulesTest#tenantPrefixMatchesInvoiceFormat")
    static final String TENANT_PREFIX = "acme";

    @AIFeatureFlag(flag = "billing.gateway.v2", defaultValue = false)
    public boolean useV2Gateway() {
        return false;
    }

    @AIDeprecated(
        replacedBy = "charge(Money)",
        migrationGuide = "Switch callers to the Money overload; the double form loses minor units",
        deadline = "v2.0 (2027-Q1)")
    @AISunset(jira = "BILL-2201")
    public String charge(double amount) {
        return "legacy-" + amount;
    }

    @AILegacyBridge(reason = "Mirrors the acquirer's fixed-width wire format, spaces and all; tidying it broke settlement in 2024")
    String toWireFormat(String reference) {
        return String.format("%-16s", reference);
    }

    @AITemporary(
        expiresOn = "2027-06-30",
        reason = "Bridges the acquirer's rate-limit bug until their 4.2 release ships")
    void backOffWorkaround() {
        // Deliberately empty: the example is about where guardrails land.
    }
}
