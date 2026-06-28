package com.example.payment;

import se.deversity.vibetags.annotations.AIStrictTypes;
import java.math.BigDecimal;

/**
 * Demo class annotated with {@link AIStrictTypes}.
 * Prohibits loose typing (using Object, raw types, or Maps) where well-defined,
 * strongly-typed models must be used.
 */
@AIStrictTypes(reason = "Currency math broke in INC-4412 when a double leaked into amount; keep money as BigDecimal and never widen these fields to Object/Map")
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
