<!-- VIBETAGS-START -->
# Amazon Q Rules for TransactionalPaymentService

## Strict Exception Handling
- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions.
- **Reason**: A bare catch(Exception) here once swallowed a TransactionRolledbackException and double-charged customers; only catch the specific types you handle
<!-- VIBETAGS-END -->
