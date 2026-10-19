package com.acme.shop.order;

import java.util.List;

/**
 * An order as it is priced and charged.
 */
public record Order(String orderId, String customerId, List<String> skus, long amountCents, String currency) {
}
