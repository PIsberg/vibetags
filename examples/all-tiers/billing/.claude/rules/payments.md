---
paths: ["**/*Gateway.java"]
---

<!-- VIBETAGS-START -->
# Rules for payments

## Deprecated — Migrate Callers

### com.example.alltiers.billing.PaymentGateway.charge(double)
- **Replaced by**: charge(Money)
- **Migration**: Switch callers to the Money overload; the double form loses minor units
- **Deadline**: v2.0 (2027-Q1)

## Legacy Compatibility Bridge

### com.example.alltiers.billing.PaymentGateway.toWireFormat(java.lang.String)
- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.
- **Reason**: Mirrors the acquirer's fixed-width wire format, spaces and all; tidying it broke settlement in 2024

## Strict Exception Handling

### com.example.alltiers.billing.PaymentGateway
- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions.
- **Reason**: A bare catch(Exception) here once swallowed a timeout and double-charged

## Strict Classpath Integrity

### com.example.alltiers.billing.PaymentGateway
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.
- **Reason**: Runs inside the PCI sandbox where reflection and custom classloaders are refused

## Feature Flag Gate

### com.example.alltiers.billing.PaymentGateway.useV2Gateway()
- **Flag**: 'billing.gateway.v2' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

## Sunset Element

### com.example.alltiers.billing.PaymentGateway.charge(double)
- **Status**: Strict Deprecation (No new references)
- **JIRA Ticket**: BILL-2201
- **Replacement**: java.lang.Object

## Temporary Workaround

### com.example.alltiers.billing.PaymentGateway.backOffWorkaround()
- **Expiration**: 2027-06-30
- **Reason**: Bridges the acquirer's rate-limit bug until their 4.2 release ships
- **Rule**: Hotfix or stub that must be removed before expiration.

## Mirrored — Keep In Sync

### com.example.alltiers.billing.PaymentGateway.TENANT_PREFIX
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: com.example.alltiers.billing.InvoiceController
- **Reason**: The gateway's tenant prefix and the invoice number format encode the same tenant id
- **Enforced by**: TaxRulesTest#tenantPrefixMatchesInvoiceFormat
<!-- VIBETAGS-END -->
