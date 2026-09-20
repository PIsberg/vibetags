---
paths: ["**/PaymentFixturesTest.java"]
---

<!-- VIBETAGS-START -->
# Rules for PaymentFixturesTest

## Context & Focus
- **Focus**: Build payments through PaymentFixtures, never with the PaymentRequest constructor: the fixture is what keeps minor units and currency consistent across the suite
- **Avoid**: Asserting on formatted amounts; assert on the minor-unit long
<!-- VIBETAGS-END -->
