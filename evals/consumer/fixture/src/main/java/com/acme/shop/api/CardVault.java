package com.acme.shop.api;

/**
 * Looks up a customer's stored card token.
 */
public interface CardVault {

    String defaultCardToken(String customerId);
}
