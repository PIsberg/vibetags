package com.acme.shop.payment;

/**
 * One authorisation request as the acquirer receives it.
 */
public record ChargeRequest(long amountCents, String currency, String cardToken) {
}
