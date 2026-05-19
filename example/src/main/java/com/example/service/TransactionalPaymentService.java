package com.example.service;

import se.deversity.vibetags.annotations.AIStrictExceptions;

/**
 * Demo service class annotated with {@link AIStrictExceptions}.
 * Enforces precise, robust exception handling.
 * Prohibits catching or throwing generic Exceptions/Throwables.
 */
@AIStrictExceptions
public class TransactionalPaymentService {
    
    public void processTransaction(String accountId, double amount) throws IllegalArgumentException {
        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("Account ID must not be null or empty");
        }
        // Processing code here. AI must throw specific custom/standard exceptions.
    }
}
