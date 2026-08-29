---
paths: ["**/*Printer.java"]
---

<!-- VIBETAGS-START -->
# Rules for label-printing

## Exclusion Rule

### com.example.alltiers.shipping.LabelPrinter.carrierSessionToken
This element is strictly excluded from AI context. Do not reference it.
- **Reason**: Cached carrier handshake token; meaningless outside one process

## Observability Instrumentation

### com.example.alltiers.shipping.LabelPrinter.print(java.lang.String)
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: shipping.label.printed, shipping.label.failed. Traces: shipping.label. Note: The Shipping SLO dashboard alerts on these two counters

## Security-Critical Code

### com.example.alltiers.shipping.LabelPrinter
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Carrier credentials and label signing

## Access Restrictions

### com.example.alltiers.shipping.LabelPrinter.writeToPrinter(byte[])
- **Allowed Callers**: [com.example.alltiers.shipping.LabelPrinter]

## Banned APIs

### com.example.alltiers.shipping.LabelPrinter.print(java.lang.String)
- **Rule**: The following compile here but are prohibited at this element.
- **Forbidden**: java.util.Date, java.text.SimpleDateFormat
- **Use instead**: java.time
- **Reason**: SimpleDateFormat is not thread-safe and this runs on the printer pool

## Thread Affinity

### com.example.alltiers.shipping.LabelPrinter.writeToPrinter(byte[])
- **Rule**: Safe on exactly one thread. This is NOT thread-safety — never add locks to "fix" it; marshal the call instead.
- **Affinity**: BACKGROUND_ONLY (printer-pool)
- **Marshal via**: PrinterExecutor.submit
- **Symptom if violated**: The request thread blocks on the printer socket and the endpoint times out
<!-- VIBETAGS-END -->
