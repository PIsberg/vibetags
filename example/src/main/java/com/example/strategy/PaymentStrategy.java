package com.example.strategy;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIDraft;

/**
 * Payment strategy pattern implementation.
 * 
 * This interface enforces the Strategy pattern for payment processing.
 * Each payment method (Credit Card, PayPal, Crypto, etc.) should implement
 * this interface with its own specific logic.
 */
@AIContext(
    focus = "Follow the Strategy pattern strictly. Each payment method should be a separate strategy class implementing this interface.",
    avoids = "Monolithic if-else chains, hard-coded payment logic, single class handling all payment types"
)
public interface PaymentStrategy {
    
    /**
     * Execute payment using this strategy.
     */
    @AIDraft(instructions = "Implement payment execution specific to the payment method (credit card, PayPal, cryptocurrency, etc.). Return transaction ID on success.")
    String executePayment(double amount);
    
    /**
     * Validate payment method details.
     */
    @AIDraft(instructions = "Validate payment method specific data (card numbers, email addresses, wallet addresses, etc.). Return true if valid, false otherwise.")
    boolean validatePaymentMethod();
    
    /**
     * Get the name of this payment method.
     */
    String getPaymentMethodName();
    
    /**
     * Check if this payment method supports refunds.
     */
    boolean supportsRefunds();
}
