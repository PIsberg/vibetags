package com.acme.shop.order;

/**
 * Current unit prices, in minor units of the shop currency.
 */
public interface PriceList {

    long priceCents(String sku);

    String currency();
}
