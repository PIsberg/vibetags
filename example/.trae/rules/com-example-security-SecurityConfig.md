---
alwaysApply: false
globs: ["**/SecurityConfig.java"]
description: "AI rules for com.example.security.SecurityConfig"
---

<!-- VIBETAGS-START -->
# Rules for SecurityConfig

## Locked Status
- **Reason**: CRITICAL: Security configuration managed by DevOps team. Any changes require security review and approval ticket SEC-XXXX

### Rules for method getEncryptionAlgorithm
- **Reason**: Encryption algorithm tied to compliance requirements (PCI-DSS)

### Rules for method getKeyRotationHours
- **Reason**: Key rotation period mandated by company policy

### Rules for method getMaxLoginAttempts
- **Reason**: Max login attempts set by security team to prevent brute force

### Rules for method validateToken
- **Reason**: Token validation must match auth server exactly. Changes will break all client authentication

## Context & Focus
- **Focus**: This class is READ-ONLY for AI assistants. Do not suggest modifications.
- **Avoid**: Any changes to encryption algorithms, key sizes, or validation logic

## Core Functionality
- **Sensitivity**: Critical
- **Note**: This is a security manager. Any single-line change can compromise the entire project.
<!-- VIBETAGS-END -->
