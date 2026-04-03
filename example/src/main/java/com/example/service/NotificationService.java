package com.example.service;

import com.vibetags.annotations.AIDraft;
import com.vibetags.annotations.AIContext;

/**
 * User notification service.
 * 
 * This service handles email, SMS, and push notifications.
 * AI is encouraged to help implement the delivery methods.
 */
@AIContext(
    focus = "Implement notification delivery with retry logic and error handling",
    avoids = "Hard-coded credentials, synchronous blocking calls"
)
public class NotificationService {
    
    /**
     * Send an email notification.
     */
    @AIDraft(instructions = "Implement email sending using JavaMail API or similar. Include HTML template support and attachment handling. Add retry logic for transient failures (max 3 retries with exponential backoff).")
    public boolean sendEmail(String to, String subject, String body) {
        // @AIDraft: Implement this
        return false;
    }
    
    /**
     * Send an SMS notification.
     */
    @AIDraft(instructions = "Implement SMS sending via Twilio or AWS SNS. Include phone number validation. Handle rate limiting (max 10 SMS per minute per user).")
    public boolean sendSMS(String phoneNumber, String message) {
        // @AIDraft: Implement this
        return false;
    }
    
    /**
     * Send a push notification to mobile devices.
     */
    @AIDraft(instructions = "Implement push notification using Firebase Cloud Messaging. Support both Android and iOS. Include notification payload customization.")
    public boolean sendPushNotification(String deviceId, String title, String message) {
        // @AIDraft: Implement this
        return false;
    }
    
    /**
     * Queue notifications for later delivery.
     */
    @AIDraft(instructions = "Implement a notification queue using a BlockingQueue or similar structure. Support batch processing and priority levels (LOW, MEDIUM, HIGH, CRITICAL).")
    public void queueNotification(String userId, String type, String content, int priority) {
        // @AIDraft: Implement this
    }
    
    /**
     * Get notification delivery status.
     */
    @AIDraft(instructions = "Implement delivery status tracking. Return status: PENDING, SENT, DELIVERED, FAILED. Include timestamp and error message if failed.")
    public String getDeliveryStatus(String notificationId) {
        // @AIDraft: Implement this
        return "PENDING";
    }
}
