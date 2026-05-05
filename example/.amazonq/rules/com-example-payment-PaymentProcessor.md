<!-- VIBETAGS-START -->
# Amazon Q Rules for PaymentProcessor

## Locked Status
- **Reason**: Tied to legacy database schema v2.3. Changes will break production payment processing. Contact the payments team before modifying.

## Implementation Tasks
- **Instruction**: Implement support for new crypto payments without breaking legacy flow.

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: HFT-level requirements: O(1) processing time expected. No database lookups in processing loop.
<!-- VIBETAGS-END -->
