package com.example;

import com.example.payment.PaymentProcessor;
import com.example.service.NotificationService;
import com.example.service.OrderService;
import com.example.strategy.PaymentStrategy;
import com.example.strategy.impl.CreditCardStrategy;
import com.example.utils.StringParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Main application demonstrating VibeTags usage.
 * 
 * This is a simple e-commerce application that uses all the VibeTags annotations:
 * - @AILocked: Protects critical payment and security code
 * - @AIContext: Provides guidance on how to work with specific classes
 * - @AIDraft: Marks methods that need AI implementation
 * 
 * When you compile this project with Maven, the VibeTags annotation processor
 * will automatically generate AI configuration files:
 * - .cursorrules (for Cursor)
 * - CLAUDE.md (for Claude)
 * - .aiexclude (for Gemini)
 * - chatgpt_instructions.md (for ChatGPT)
 */
public class MainApplication {
    
    public static void main(String[] args) {
        System.out.println("=== VibeTags Example Application ===\n");
        
        // 1. Payment Processing (LOCKED - AI cannot modify)
        System.out.println("1. Payment Processing:");
        PaymentProcessor processor = new PaymentProcessorImpl();
        String transactionId = processor.processPayment(99.99, "USD", "MERCH-001");
        System.out.println("   Transaction ID: " + transactionId);
        
        // 2. String Parsing (AI-guided implementation)
        System.out.println("\n2. String Parsing:");
        String[] parts = StringParser.parseDelimited("apple,banana,cherry", ',');
        System.out.println("   Parsed: " + (parts != null ? String.join(", ", parts) : "Not implemented yet"));
        
        // 3. Order Service (Mixed: some locked, some draft)
        System.out.println("\n3. Order Processing:");
        OrderService orderService = new OrderService();
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("items", "sample-item");
        boolean valid = orderService.validateOrder(orderData);
        System.out.println("   Order valid: " + valid);
        
        // 4. Notification Service (All drafts - AI should implement)
        System.out.println("\n4. Notifications:");
        NotificationService notificationService = new NotificationService();
        boolean emailSent = notificationService.sendEmail("user@example.com", "Order Confirmation", "Your order is confirmed!");
        System.out.println("   Email sent: " + emailSent);
        
        // 5. Payment Strategy (AI-guided pattern implementation)
        System.out.println("\n5. Payment Strategy:");
        PaymentStrategy creditCard = new CreditCardStrategy("4111111111111111", "12/25", "123");
        System.out.println("   Payment method: " + creditCard.getPaymentMethodName());
        System.out.println("   Supports refunds: " + creditCard.supportsRefunds());
        
        System.out.println("\n=== Example Complete ===");
        System.out.println("\nRun 'mvn clean compile' to generate AI guardrail files!");
    }
    
    /**
     * Simple implementation of PaymentProcessor for demonstration.
     * In real applications, this would connect to actual payment gateways.
     */
    static class PaymentProcessorImpl implements PaymentProcessor {
        @Override
        public String processPayment(double amount, String currency, String merchantId) {
            return "TXN-" + System.currentTimeMillis();
        }
        
        @Override
        public String refundPayment(String transactionId, double amount) {
            return "REFUND-" + System.currentTimeMillis();
        }
        
        @Override
        public boolean validateCredentials(String apiKey, String apiSecret) {
            return apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty();
        }
    }
}
