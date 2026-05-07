package com.example.legacy;

import se.deversity.vibetags.annotations.AIDeprecated;

/**
 * Legacy payment API kept around for backwards compatibility while callers migrate.
 *
 * <p>Demonstrates {@code @AIDeprecated} — points AI assistants at the replacement
 * ({@code com.example.payment.PaymentProcessor}) and at the migration path. AI tools
 * should suggest migrating any caller to the replacement rather than extending this
 * class.
 */
@AIDeprecated(
    replacedBy = "com.example.payment.PaymentProcessor",
    migrationGuide = "Switch callers to PaymentProcessor.charge(). The new API uses Money instead of double.",
    deadline = "v2.0 (2026-Q4)"
)
public class OldPaymentApi {

    public boolean pay(String customerId, double amount) {
        return amount > 0;
    }
}
