package com.example.service;

import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;

import java.math.BigDecimal;

/**
 * Showcase class demonstrating the 12 new VibeTags annotations added in v0.9.8.
 * This class serves as a comprehensive developer reference and is processed
 * at compile-time to generate custom AI guardrail configuration files.
 */
public final class NewAnnotationsShowcase {

    private NewAnnotationsShowcase() {}

    /**
     * 1. Protection & Access Control
     * Restricts sandbox helper utilities strictly to mock, dev, or test environments.
     * Prevents the AI from importing or referencing sandbox utilities in production pathways.
     */
    @AISandboxOnly
    public static class SandboxTestHelper {
        public static void setupLocalMockDatabase() {
            System.out.println("Local mock database configured for unit/integration testing.");
        }
    }

    /**
     * 2. Protection & Access Control
     * Restricts method invocation strictly to authorized caller packages or classes.
     * Prevents AI code generation tools from introducing illegal architectural bypasses.
     */
    @AICallersOnly({"com.example.service.PricingService", "com.example.payment.PaymentProcessor"})
    public static void executeSecureDatabaseWipe() {
        System.out.println("Secure database wipe executed by authorized caller.");
    }

    /**
     * 3. Behavioral Constraints & Pure Functions
     * Enforces strict time/space complexity and heap allocation policy.
     * Denies heap allocations, autoboxing, or object instantiation inside high-performance critical sections.
     *
     * AIPure declares that a method is a side-effect-free, deterministic mathematical function.
     */
    @AIMemoryBudget(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION)
    @AIPure
    public static int calculateFastFibonacci(int n) {
        if (n <= 1) return n;
        int prev = 0, curr = 1;
        for (int i = 2; i <= n; i++) {
            int next = prev + curr;
            prev = curr;
            curr = next;
        }
        return curr;
    }

    /**
     * 4. Design Patterns & Structures
     * Enforces Domain-Driven Design (DDD) boundaries by preventing external or framework imports.
     * The compiler will scan and block any imports from Spring, JPA/Hibernate, Jackson, etc.
     */
    @AIDomainModel(allow = {"java.math.BigDecimal"})
    public static class ImmutableProductPrice {
        private final BigDecimal amount;
        private final String currency;

        public ImmutableProductPrice(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }
    }

    /**
     * 5. Design Patterns & Structures
     * Signals that an interface or class must be extended using polymorphic designs (Open-Closed Principle).
     * Prompts the AI to introduce strategy patterns rather than accumulating massive conditional/switch statements.
     */
    @AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)
    public interface TaxCalculatorStrategy {
        BigDecimal calculateTax(BigDecimal amount);
    }

    /**
     * 6. Security & Privacy
     * Enforces sanitization pipelines on input parameters before they reach queries, HTML renderers, or files.
     */
    public static void executeDatabaseQuery(
            @AIInputSanitized({AIInputSanitized.SanitizerType.SQL_INJECTION}) String sqlRawInput) {
        System.out.println("Executing sanitized query: " + sqlRawInput);
    }

    /**
     * 7. Security & Privacy
     * Protects sensitive variables from being logged directly or leaked in console outputs.
     */
    public static void registerUserSession(
            String username,
            @AISecureLogging(AISecureLogging.MaskingPolicy.HASH) String passwordRaw,
            @AISecureLogging(AISecureLogging.MaskingPolicy.MASK_CREDIT_CARD) String creditCardNumber) {
        System.out.println("User registered: " + username);
    }

    /**
     * 8. Development Workflow
     * Enforces step-by-step mathematical/architectural Chain-of-Thought (CoT) explanations of any modifications.
     */
    @AIExplain(AIExplain.ComplexityLevel.HIGH)
    public static double runComplexMatrixMath(double[][] a, double[][] b) {
        // AI must write dynamic mathematical justifications when modifying this code.
        return a[0][0] * b[0][0];
    }

    /**
     * 9. Development Workflow
     * Declares a rapid framework prototype spike.
     * Relaxes standard quality rules (such as i18n, coverage) but prevents it from leaking into stable production code.
     */
    @AIPrototype
    public static class DraftKafkaIntegrationSpike {
        public static void testConnection() {
            System.out.println("Kafka sandbox prototype connection success.");
        }
    }

    /**
     * 10. Temporal & Maintenance
     * Hard stop deprecation guardrail. AI models are strictly prohibited from adding new references to this element.
     */
    @AISunset(replacement = PricingService.class, jira = "DEBT-742")
    public static double deprecatedLegacyCalculatePrice(double basePrice, double discountRate) {
        return basePrice * (1.0 - discountRate);
    }

    /**
     * 11. Temporal & Maintenance
     * Hard stop for hotfixes, temporary stubs, or quick hacks.
     * Warns/fails compilation once system date exceeds the expiration date (ISO format YYYY-MM-DD).
     */
    @AITemporary(expiresOn = "2028-12-31", reason = "Hotfix workaround until upstream payment provider updates their API.")
    public static void temporaryUpstreamBypass() {
        System.out.println("Bypassing broken gateway version 2.41.");
    }
}
