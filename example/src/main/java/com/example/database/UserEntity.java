package com.example.database;

import se.deversity.vibetags.annotations.AISchemaSafe;

/**
 * Demo database entity class annotated with {@link AISchemaSafe}.
 * Guarantees schema and serialization safety.
 * Restricts changing data formats, fields, columns, or serialization formats.
 */
@AISchemaSafe(reason = "Maps to the users table replicated to the billing read-model; renaming a column or changing a type needs a backward-compatible Flyway migration first")
public class UserEntity {
    
    private final String userId;
    private final String emailAddress;
    
    public UserEntity(String userId, String emailAddress) {
        this.userId = userId;
        this.emailAddress = emailAddress;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public String getEmailAddress() {
        return emailAddress;
    }
}
