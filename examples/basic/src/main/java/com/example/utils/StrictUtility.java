package com.example.utils;

import se.deversity.vibetags.annotations.AIStrictClasspath;

/**
 * Demo class annotated with {@link AIStrictClasspath}.
 * Enforces strict compile-time dependency and classpath constraints.
 * Prohibits dynamic class loading, custom classloaders, or runtime reflection hacks.
 */
@AIStrictClasspath(reason = "Runs inside the locked-down payment sandbox where the SecurityManager forbids reflection and custom classloaders; dynamic loading throws at runtime")
public class StrictUtility {

    public static String computeSecureHash(String input) {
        // AI must only use standard JDK classes and keep imports strictly on classpath.
        return String.valueOf(input.hashCode());
    }
}
