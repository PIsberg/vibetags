---
paths: ["**/AnnotationDefinitionsTest.java"]
---

<!-- VIBETAGS-START -->
# Rules for AnnotationDefinitionsTest

### Rules for method testAILockedCanBeUsedOnMethods
- **Reason**: Test reason

### Rules for method testAIContextCanBeUsedOnMethods
- **Focus**: Test focus
- **Avoid**: Test avoids

### Rules for method testAIIgnoreCanBeUsedOnMethods
This element is strictly excluded from AI context. Do not reference it.
- **Reason**: Test reason

### Rules for method testAIAuditCanBeUsedOnMethods
When modifying this element, audit for:
- SQL Injection
- Thread Safety

### Rules for method testAIDraftCanBeUsedOnMethods
- **Instruction**: Test instructions

### Rules for method testAIPrivacyCanBeUsedOnMethods
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Test reason

### Rules for method testAICoreCanBeUsedOnMethods
- **Sensitivity**: Critical
- **Note**: Test core logic

### Rules for method testAIPerformanceCanBeUsedOnMethods
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: O(1) required

### Rules for method testAIContractCanBeUsedOnMethods
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Test contract

### Rules for method testAITestDrivenCanBeUsedOnMethods
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 90%
- **Frameworks**: JUNIT_5, MOCKITO
<!-- VIBETAGS-END -->
