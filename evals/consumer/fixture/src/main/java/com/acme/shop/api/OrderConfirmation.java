package com.acme.shop.api;

/**
 * What the web and mobile apps show after checkout.
 */
public record OrderConfirmation(String orderId, long amountCents, String currency) {
}
