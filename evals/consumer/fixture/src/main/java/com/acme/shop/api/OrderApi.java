package com.acme.shop.api;

import com.acme.shop.order.Order;
import com.acme.shop.order.OrderService;

import se.deversity.vibetags.annotations.AIContract;

import java.util.List;

/**
 * Checkout endpoint for the web shop and the mobile apps.
 */
public class OrderApi {

    private final OrderService orders;
    private final CardVault vault;

    public OrderApi(OrderService orders, CardVault vault) {
        this.orders = orders;
        this.vault = vault;
    }

    @AIContract(reason = "Mobile apps call this exact signature over the RPC bridge, and app releases stay installed for six months, so a changed signature breaks apps already in the field. Add an overload instead.")
    public OrderConfirmation placeOrder(String customerId, List<String> skus) {
        Order order = orders.place(customerId, skus, vault.defaultCardToken(customerId));
        return new OrderConfirmation(order.orderId(), order.amountCents(), order.currency());
    }
}
