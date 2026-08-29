---
paths: ["**/*Controller.java"]
---

<!-- VIBETAGS-START -->
# Rules for api-endpoints

## Locked Status

### com.example.alltiers.billing.InvoiceController.invoiceNumber(long)
- **Reason**: The invoice number format is on partner contracts; changing it breaks reconciliation

## Context & Focus

### com.example.alltiers.billing.InvoiceController
- **Focus**: The public invoicing surface; keep responses stable for partner integrations
- **Avoid**: Returning entities directly — always map to a response record

## Security Audit Requirements

### com.example.alltiers.billing.InvoiceController
When modifying this element, audit for:
- SQL Injection
- Broken Access Control

## PII / Privacy Guardrails

### com.example.alltiers.billing.InvoiceController.billingEmail
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Billing email identifies a natural person; never log it or echo it back

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.

### com.example.alltiers.billing.InvoiceController.renderInvoice(java.lang.String,java.lang.String)
- **Constraint**: Must complete in <20ms p99 — it is on the checkout path

### com.example.alltiers.billing.InvoiceController.tenantId
- **Constraint**: Read on every request; keep it a field, never a lookup

## Contract-Frozen Signature

### com.example.alltiers.billing.InvoiceController.renderInvoice(java.lang.String,java.lang.String)
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Serialized straight to the partner API; the parameter order is the wire format

## Public API Surface Protection

### com.example.alltiers.billing.InvoiceController
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Reason**: Three partner integrations are pinned to v1 of these endpoints

## Input Sanitization

### com.example.alltiers.billing.InvoiceController.renderInvoice(java.lang.String,java.lang.String)#customerNote
- **Target Filters**: XSS, SQL_INJECTION
- **Rule**: Run raw input strings through approved sanitizers.

## Secure Logging Masking

### com.example.alltiers.billing.InvoiceController.billingEmail
- **Policy**: HASH
- **Rule**: Never pass this raw variable to log appenders or stdout streams.
<!-- VIBETAGS-END -->
