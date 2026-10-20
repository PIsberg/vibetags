package com.acme.shop.payment;

/**
 * The acquirer's answer to a charge or a refund.
 */
public record ChargeResult(String chargeId, boolean approved, String declineCode) {
}
