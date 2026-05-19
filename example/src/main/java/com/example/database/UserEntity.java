package com.example.database;

import se.deversity.vibetags.annotations.AISchemaSafe;

/**
 * Demo database entity class annotated with {@link AISchemaSafe}.
 * Guarantees schema and serialization safety.
 * Restricts changing data formats, fields, columns, or serialization formats.
 */
@AISchemaSafe
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
