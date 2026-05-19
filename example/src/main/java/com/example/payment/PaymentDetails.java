package com.example.payment;

import se.deversity.vibetags.annotations.AIStrictTypes;
import java.math.BigDecimal;

/**
 * Demo class annotated with {@link AIStrictTypes}.
 * Prohibits loose typing (using Object, raw types, or Maps) where well-defined,
 * strongly-typed models must be used.
 */
@AIStrictTypes
public class PaymentDetails {
    
    private final String accountHolder;
    private final BigDecimal amount;
    
    public PaymentDetails(String accountHolder, BigDecimal amount) {
        this.accountHolder = accountHolder;
        this.amount = amount;
    }
    
    public String getAccountHolder() {
        return accountHolder;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
}
