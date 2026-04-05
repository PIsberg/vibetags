package com.example.service;

import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIPrivacy;

import java.util.List;
import java.util.Map;

/**
 * Order processing service.
 * 
 * Demonstrates mixed usage of @AILocked, @AIContext, and @AIDraft
 * within the same class to show fine-grained control.
 */
@AIContext(
    focus = "Maintain transactional integrity. All database operations must use proper transaction management.",
    avoids = "Raw SQL queries, direct database connections without connection pooling"
)
public class OrderService {
    
    /**
     * LOCKED: Order validation logic.
     * This method implements complex business rules that took months to get right.
     */
    @AILocked(reason = "Order validation implements 47 business rules. Last changed in Q2 2024 after 3-month testing cycle. DO NOT MODIFY without running full test suite.")
    public boolean validateOrder(Map<String, Object> orderData) {
        // Complex validation - DO NOT CHANGE
        return orderData != null && orderData.containsKey("items");
    }
    
    /**
     * LOCKED: Tax calculation.
     * Integrates with external tax service API.
     */
    @AILocked(reason = "Tax calculation uses Avalara API integration. Credentials and endpoint configuration managed by finance team.")
    public double calculateTax(String address, double subtotal) {
        // External API call - DO NOT CHANGE
        return subtotal * 0.08; // Simplified for example
    }
    
    /**
     * DRAFT: Order discount logic.
     * AI is encouraged to help implement various discount strategies.
     */
    @AIDraft(instructions = "Implement discount calculation supporting: percentage discounts, fixed amount discounts, buy-one-get-one-free, and tiered discounts based on cart value. Apply maximum one discount per order unless overridden by admin.")
    public double calculateDiscount(String orderId, String discountCode) {
        // @AIDraft: Implement discount logic
        return 0.0;
    }
    
    /**
     * DRAFT: Order status tracking.
     * Need help implementing comprehensive status workflow.
     */
    @AIDraft(instructions = "Implement order status workflow: CREATED -> PAYMENT_PENDING -> PAYMENT_CONFIRMED -> PROCESSING -> SHIPPED -> DELIVERED. Support status history tracking with timestamps. Allow cancellation only before SHIPPED status.")
    public String updateOrderStatus(String orderId, String newStatus) {
        // @AIDraft: Implement status tracking
        return "CREATED";
    }
    
    /**
     * DRAFT: Order search and filtering.
     */
    @AIDraft(instructions = "Implement order search with filters: date range, status, customer ID, minimum/maximum amount. Support pagination (default 20 items per page). Return results sorted by creation date descending.")
    public List<Map<String, Object>> searchOrders(Map<String, String> filters, int page, int pageSize) {
        // @AIDraft: Implement search logic
        return null;
    }
    
    /**
     * LOCKED: Payment integration.
     */
    @AILocked(reason = "Payment processing uses Stripe API v2024.10. Changes require PCI compliance review.")
    private boolean processPayment(String orderId, double amount) {
        // Stripe integration - DO NOT CHANGE
        return true;
    }
    
    /**
     * DRAFT: Order confirmation generation.
     */
    @AIPrivacy(reason = "Output contains customer shipping address and contact details (PII)")
    @AIDraft(instructions = "Generate order confirmation email content including: order summary, itemized list, shipping address, estimated delivery date, and customer support contact information. Support HTML and plain text formats.")
    public String generateOrderConfirmation(String orderId) {
        // @AIDraft: Implement confirmation generation
        return "";
    }
}
