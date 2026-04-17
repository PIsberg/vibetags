package com.example.security;

import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AICore;

/**
 * Security configuration manager.
 * 
 * This class handles critical security settings and must never be modified
 * without explicit approval from the security team.
 */
@AICore(sensitivity = "Critical", note = "This is a security manager. Any single-line change can compromise the entire project.")
@AILocked(reason = "CRITICAL: Security configuration managed by DevOps team. Any changes require security review and approval ticket SEC-XXXX")
@AIContext(
    focus = "This class is READ-ONLY for AI assistants. Do not suggest modifications.",
    avoids = "Any changes to encryption algorithms, key sizes, or validation logic"
)
public class SecurityConfig {
    
    private static final String ENCRYPTION_ALGORITHM = "AES-256-GCM";
    private static final int KEY_ROTATION_HOURS = 24;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    
    @AILocked(reason = "Encryption algorithm tied to compliance requirements (PCI-DSS)")
    public String getEncryptionAlgorithm() {
        return ENCRYPTION_ALGORITHM;
    }
    
    @AILocked(reason = "Key rotation period mandated by company policy")
    public int getKeyRotationHours() {
        return KEY_ROTATION_HOURS;
    }
    
    @AILocked(reason = "Max login attempts set by security team to prevent brute force")
    public int getMaxLoginAttempts() {
        return MAX_LOGIN_ATTEMPTS;
    }
    
    /**
     * Validate security token.
     * DO NOT modify validation logic - it matches the authentication server.
     */
    @AILocked(reason = "Token validation must match auth server exactly. Changes will break all client authentication")
    public boolean validateToken(String token) {
        // Complex validation logic - DO NOT TOUCH
        return token != null && token.length() > 0;
    }
}
