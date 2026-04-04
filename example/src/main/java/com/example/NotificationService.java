package com.example;

import se.deversity.vibetags.annotations.AIDraft;

/**
 * Service for sending notifications to users.
 */
@AIDraft(instructions = "Implement email sending via SMTP and push notifications via FCM. Ensure retry logic and rate limiting are applied.")
public class NotificationService {
    
    /**
     * Send a notification to a specific user.
     */
    public void sendNotification(String userId, String message) {
        // TODO: Implement this
    }
}
