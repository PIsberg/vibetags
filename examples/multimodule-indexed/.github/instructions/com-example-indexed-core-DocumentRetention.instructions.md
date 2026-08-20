---
applyTo: "**/DocumentRetention.java"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for DocumentRetention

## Context & Focus
- **Focus**: Retention windows and the legal basis for them
- **Avoid**: Do not shorten a window to make a test pass; the windows are set by the standards named below

### Rules for field cachedExpiryEpochDay
This element is strictly excluded from AI context. Do not reference it.

### Rules for field ownerEmail
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Owner email identifies a natural person; never log it or put it in a suggestion

### Rules for method expiryFromNow
- **Replaced by**: expiryEpochDay(long, int)
- **Migration**: Pass the creation day explicitly instead of relying on the system clock
- **Deadline**: 2027-01-01

## Regulatory Compliance
- **Standard**: GDPR
- **Clause**: Art. 5(1)(e)
- **Description**: Storage limitation: documents are kept no longer than the stated purpose requires

## Internationalization Mandate
- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources.

### Rules for field ownerEmail
- **Policy**: HASH
- **Rule**: Never pass this raw variable to log appenders or stdout streams.

### Rules for method expiryEpochDay
- **Complexity Level**: HIGH
- **Rule**: Any logic modification requires updating a walkthrough/markdown file with structured architectural rationale.

### Rules for method expiryFromNow
- **Status**: Strict Deprecation (No new references)
- **JIRA Ticket**: DOC-4471
- **Replacement**: java.lang.Object

### Rules for method legacyRetentionDays
- **Expiration**: 2026-12-31
- **Reason**: Bridges the pre-2026 records that stored a retention class instead of a day count
- **Rule**: Hotfix or stub that must be removed before expiration.
<!-- VIBETAGS-END -->
