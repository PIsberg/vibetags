package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.internal.content.annotations.*;

/**
 * A central registry providing access to stateless, thread-safe annotation formatters.
 */
public final class FormatterRegistry {
    private static final AILockedFormatter LOCKED = new AILockedFormatter();
    private static final AIContextFormatter CONTEXT = new AIContextFormatter();
    private static final AIDraftFormatter DRAFT = new AIDraftFormatter();
    private static final AIAuditFormatter AUDIT = new AIAuditFormatter();
    private static final AIIgnoreFormatter IGNORE = new AIIgnoreFormatter();
    private static final AIPrivacyFormatter PRIVACY = new AIPrivacyFormatter();
    private static final AICoreFormatter CORE = new AICoreFormatter();
    private static final AIPerformanceFormatter PERFORMANCE = new AIPerformanceFormatter();
    private static final AIContractFormatter CONTRACT = new AIContractFormatter();
    private static final AITestDrivenFormatter TEST_DRIVEN = new AITestDrivenFormatter();
    private static final AIThreadSafeFormatter THREAD_SAFE = new AIThreadSafeFormatter();
    private static final AIImmutableFormatter IMMUTABLE = new AIImmutableFormatter();
    private static final AIDeprecatedFormatter DEPRECATED = new AIDeprecatedFormatter();
    private static final AIObservabilityFormatter OBSERVABILITY = new AIObservabilityFormatter();
    private static final AIRegulationFormatter REGULATION = new AIRegulationFormatter();
    private static final AIParallelTestsFormatter PARALLEL_TESTS = new AIParallelTestsFormatter();
    private static final AILegacyBridgeFormatter LEGACY_BRIDGE = new AILegacyBridgeFormatter();
    private static final AIArchitectureFormatter ARCHITECTURE = new AIArchitectureFormatter();
    private static final AIPublicAPIFormatter PUBLIC_API = new AIPublicAPIFormatter();
    private static final AIStrictExceptionsFormatter STRICT_EXCEPTIONS = new AIStrictExceptionsFormatter();
    private static final AIStrictTypesFormatter STRICT_TYPES = new AIStrictTypesFormatter();
    private static final AIInternationalizedFormatter INTERNATIONALIZED = new AIInternationalizedFormatter();
    private static final AIStrictClasspathFormatter STRICT_CLASSPATH = new AIStrictClasspathFormatter();
    private static final AISchemaSafeFormatter SCHEMA_SAFE = new AISchemaSafeFormatter();
    private static final AIIdempotentFormatter IDEMPOTENT = new AIIdempotentFormatter();
    private static final AIFeatureFlagFormatter FEATURE_FLAG = new AIFeatureFlagFormatter();
    private static final AISecureFormatter SECURE = new AISecureFormatter();

    // New annotation formatters
    private static final AICallersOnlyFormatter CALLERS_ONLY = new AICallersOnlyFormatter();
    private static final AISandboxOnlyFormatter SANDBOX_ONLY = new AISandboxOnlyFormatter();
    private static final AIMemoryBudgetFormatter MEMORY_BUDGET = new AIMemoryBudgetFormatter();
    private static final AIPureFormatter PURE = new AIPureFormatter();
    private static final AIDomainModelFormatter DOMAIN_MODEL = new AIDomainModelFormatter();
    private static final AIExtensibleFormatter EXTENSIBLE = new AIExtensibleFormatter();
    private static final AIInputSanitizedFormatter INPUT_SANITIZED = new AIInputSanitizedFormatter();
    private static final AISecureLoggingFormatter SECURE_LOGGING = new AISecureLoggingFormatter();
    private static final AIExplainFormatter EXPLAIN = new AIExplainFormatter();
    private static final AIPrototypeFormatter PROTOTYPE = new AIPrototypeFormatter();
    private static final AISunsetFormatter SUNSET = new AISunsetFormatter();
    private static final AITemporaryFormatter TEMPORARY = new AITemporaryFormatter();

    private FormatterRegistry() {}

    public static AILockedFormatter locked() { return LOCKED; }
    public static AIContextFormatter context() { return CONTEXT; }
    public static AIDraftFormatter draft() { return DRAFT; }
    public static AIAuditFormatter audit() { return AUDIT; }
    public static AIIgnoreFormatter ignore() { return IGNORE; }
    public static AIPrivacyFormatter privacy() { return PRIVACY; }
    public static AICoreFormatter core() { return CORE; }
    public static AIPerformanceFormatter performance() { return PERFORMANCE; }
    public static AIContractFormatter contract() { return CONTRACT; }
    public static AITestDrivenFormatter testDriven() { return TEST_DRIVEN; }
    public static AIThreadSafeFormatter threadSafe() { return THREAD_SAFE; }
    public static AIImmutableFormatter immutable() { return IMMUTABLE; }
    public static AIDeprecatedFormatter deprecated() { return DEPRECATED; }
    public static AIObservabilityFormatter observability() { return OBSERVABILITY; }
    public static AIRegulationFormatter regulation() { return REGULATION; }
    public static AIParallelTestsFormatter parallelTests() { return PARALLEL_TESTS; }
    public static AILegacyBridgeFormatter legacyBridge() { return LEGACY_BRIDGE; }
    public static AIArchitectureFormatter architecture() { return ARCHITECTURE; }
    public static AIPublicAPIFormatter publicApi() { return PUBLIC_API; }
    public static AIStrictExceptionsFormatter strictExceptions() { return STRICT_EXCEPTIONS; }
    public static AIStrictTypesFormatter strictTypes() { return STRICT_TYPES; }
    public static AIInternationalizedFormatter internationalized() { return INTERNATIONALIZED; }
    public static AIStrictClasspathFormatter strictClasspath() { return STRICT_CLASSPATH; }
    public static AISchemaSafeFormatter schemaSafe() { return SCHEMA_SAFE; }
    public static AIIdempotentFormatter idempotent() { return IDEMPOTENT; }
    public static AIFeatureFlagFormatter featureFlag() { return FEATURE_FLAG; }
    public static AISecureFormatter secure() { return SECURE; }

    // Getters for new formatters
    public static AICallersOnlyFormatter callersOnly() { return CALLERS_ONLY; }
    public static AISandboxOnlyFormatter sandboxOnly() { return SANDBOX_ONLY; }
    public static AIMemoryBudgetFormatter memoryBudget() { return MEMORY_BUDGET; }
    public static AIPureFormatter pure() { return PURE; }
    public static AIDomainModelFormatter domainModel() { return DOMAIN_MODEL; }
    public static AIExtensibleFormatter extensible() { return EXTENSIBLE; }
    public static AIInputSanitizedFormatter inputSanitized() { return INPUT_SANITIZED; }
    public static AISecureLoggingFormatter secureLogging() { return SECURE_LOGGING; }
    public static AIExplainFormatter explain() { return EXPLAIN; }
    public static AIPrototypeFormatter prototype() { return PROTOTYPE; }
    public static AISunsetFormatter sunset() { return SUNSET; }
    public static AITemporaryFormatter temporary() { return TEMPORARY; }
}
