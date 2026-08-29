package com.example.service;

import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIThreadAffinity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Showcase for the five evidence-based annotations added in v1.0.0, each reverse-engineered from
 * guardrails that real maintainers wrote by hand in open-source {@code CLAUDE.md} files
 * (see {@code docs/proposals/proposed-annotations.md}).
 *
 * <p>Four of the five close the same structural gap: VibeTags owned the <em>positive</em> pole of an
 * axis and was missing the <em>negative</em> one — and an AI reading the absence of a tag reliably
 * does the wrong thing.
 */
@AIGenerated(
    from = "src/main/resources/openapi/checkout.yaml",
    regenerateWith = "mvn generate-sources",
    editInstead = "src/main/resources/openapi/checkout.yaml"
)
public final class EvidenceBasedShowcase {

    private EvidenceBasedShowcase() {}

    /**
     * 1. Mirrored constant.
     * The release version is asserted in several places that no compiler cross-checks; the field is
     * free to change, and the bug is changing only one side.
     */
    @AIKeepInSync(
        mirrors = {"pom.xml:<version>", "README.md version badge", "docs/CHANGELOG.md"},
        reason = "The release version is duplicated across build config, docs, and the badge",
        enforcedBy = "ProjectFactsConsistencyTest"
    )
    public static final String CATALOG_VERSION = "1.0.0";

    /**
     * 2. Load-bearing oddity.
     * Looks like a leak — entries are never removed — and removing it is the bug, not the fix.
     */
    @AILoadBearing(
        invariant = "Settled orders stay in the list until the reconciliation job drains it",
        breaksIf = "Clearing eagerly drops in-flight settlements and silently under-reports revenue",
        suppressAudit = true
    )
    private static final List<String> settledOrderIds = new ArrayList<>();

    /**
     * 3. Banned APIs.
     * The prohibition is hosted here, on the consumer, because the banned symbols belong to the JDK
     * and cannot be annotated themselves.
     */
    @AIBannedApi(
        forbidden = {"java.lang.System.out", "java.util.Date", "java.lang.Double"},
        useInstead = "the injected org.slf4j.Logger, java.time.Instant, and java.math.BigDecimal",
        reason = "Console output bypasses structured logging; Date and Double are unsafe for money and time"
    )
    public static BigDecimal totalWithTax(BigDecimal subtotal, BigDecimal taxRate) {
        return subtotal.add(subtotal.multiply(taxRate));
    }

    /**
     * 4. Thread affinity.
     * The inverse of {@code @AIThreadSafe}: an AI told to "make this thread-safe" would add a lock,
     * which is exactly the wrong fix — the call has to move, not synchronize.
     */
    @AIThreadAffinity(
        value = AIThreadAffinity.Affinity.NAMED,
        thread = "checkout-ui",
        marshalVia = "CheckoutDispatcher.runOnUiThread",
        symptomIfViolated = "Cart totals render stale under load; no exception is thrown"
    )
    public static void refreshCartBadge() {
        // Repaints the cart badge; the UI toolkit asserts the calling thread only in debug builds.
    }

    /** Registers a settled order id. Kept alongside the load-bearing list above. */
    public static void recordSettled(String orderId) {
        settledOrderIds.add(orderId);
    }
}
