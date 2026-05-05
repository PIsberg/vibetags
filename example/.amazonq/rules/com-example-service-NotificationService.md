<!-- VIBETAGS-START -->
# Amazon Q Rules for NotificationService

## Context & Focus
- **Focus**: Implement notification delivery with retry logic and error handling
- **Avoid**: Hard-coded credentials, synchronous blocking calls

### Rules for method sendEmail
- **Instruction**: Implement email sending using JavaMail API or similar. Include HTML template support and attachment handling. Add retry logic for transient failures (max 3 retries with exponential backoff).

### Rules for method sendSMS
- **Instruction**: Implement SMS sending via Twilio or AWS SNS. Include phone number validation. Handle rate limiting (max 10 SMS per minute per user).

### Rules for method sendPushNotification
- **Instruction**: Implement push notification using Firebase Cloud Messaging. Support both Android and iOS. Include notification payload customization.

### Rules for method queueNotification
- **Instruction**: Implement a notification queue using a BlockingQueue or similar structure. Support batch processing and priority levels (LOW, MEDIUM, HIGH, CRITICAL).

### Rules for method getDeliveryStatus
- **Instruction**: Implement delivery status tracking. Return status: PENDING, SENT, DELIVERED, FAILED. Include timestamp and error message if failed.

### Rules for method sendEmail
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Email address is PII under GDPR - never log the recipient address

### Rules for method sendSMS
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Phone number is PII - never log the destination number
<!-- VIBETAGS-END -->
