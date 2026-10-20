package com.acme.shop.order;

import com.acme.shop.payment.ChargeResult;
import com.acme.shop.payment.PaymentGateway;

import java.util.List;
import java.util.UUID;

/**
 * Prices an order and charges the customer's card.
 */
public class OrderService {

    private final PriceList prices;
    private final PaymentGateway gateway;

    public OrderService(PriceList prices, PaymentGateway gateway) {
        this.prices = prices;
        this.gateway = gateway;
    }

    public Order place(String customerId, List<String> skus, String cardToken) {
        long amountCents = 0;
        for (String sku : skus) {
            amountCents += prices.priceCents(sku);
        }
        Order order = new Order(UUID.randomUUID().toString(), customerId, List.copyOf(skus), amountCents, prices.currency());
        ChargeResult result = gateway.charge(order.amountCents(), order.currency(), cardToken);
        if (!result.approved()) {
            throw new IllegalStateException("payment declined: " + result.declineCode());
        }
        return order;
    }
}
