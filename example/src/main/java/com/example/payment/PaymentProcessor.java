package com.example.payment;

import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIPerformance;

/**
 * Core payment processing interface.
 * 
 * @AILocked - This interface is tied to a legacy database schema.
 * Any changes will break production payment processing.
 */
@AILocked(reason = "Tied to legacy database schema v2.3. Changes will break production payment processing. Contact the payments team before modifying.")
@AIDraft(instructions = "Implement support for new crypto payments without breaking legacy flow.")
@AIPerformance(constraint = "HFT-level requirements: O(1) processing time expected. No database lookups in processing loop.")
public interface PaymentProcessor {
    
    /**
     * Process a payment transaction.
     * 
     * @param amount The amount to charge
     * @param currency The currency code (e.g., "USD", "EUR")
     * @param merchantId The merchant identifier
     * @return Transaction ID for tracking
     */
    String processPayment(double amount, String currency, String merchantId);
    
    /**
     * Refund a previous transaction.
     * 
     * @param transactionId The original transaction ID
     * @param amount The amount to refund (must be <= original amount)
     * @return Refund confirmation ID
     */
    String refundPayment(String transactionId, double amount);
    
    /**
     * Validate payment credentials.
     * DO NOT change validation logic - it matches banking compliance requirements.
     */
    boolean validateCredentials(String apiKey, String apiSecret);
}
