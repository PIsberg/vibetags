<!-- VIBETAGS-START -->
# Rules for sendEmail

## Implementation Tasks
- **Instruction**: Implement email sending using JavaMail API or similar. Include HTML template support and attachment handling. Add retry logic for transient failures (max 3 retries with exponential backoff).

## PII / Privacy Guardrails
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Email address is PII under GDPR - never log the recipient address
<!-- VIBETAGS-END -->
