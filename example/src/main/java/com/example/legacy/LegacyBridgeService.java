package com.example.legacy;

import se.deversity.vibetags.annotations.AILegacyBridge;

/**
 * Demo class annotated with {@link AILegacyBridge}.
 * Protects compatibility/legacy bridges from unnecessary modernization or refactoring.
 * AI is instructed to only modify internal business logic as explicitly requested.
 */
@AILegacyBridge(reason = "Mirrors a quirk in the upstream mainframe wire format (KEY=…;VAL=… with no escaping); 'modernizing' it broke the EBCDIC gateway in 2023")
public class LegacyBridgeService {

    public String adaptLegacyCall(String key, String value) {
        // AI must preserve this legacy formatting quirk to prevent upstream breakage.
        return "KEY=" + key + ";VAL=" + value;
    }
}
