---
paths: ["**/ValidationRule.java"]
---

<!-- VIBETAGS-START -->
# Rules for ValidationRule

## Thread-Safety Guarantee
- **Strategy**: IMMUTABLE
- **Note**: Implementations must hold no state. ValidationRules keeps one instance per rule for the life of the JVM, and a Gradle daemon runs that instance against many unrelated compilations in sequence, so a field added here carries one project's elements into another project's diagnostics. Everything a check needs arrives as the ValidationContext and Element arguments.
<!-- VIBETAGS-END -->
