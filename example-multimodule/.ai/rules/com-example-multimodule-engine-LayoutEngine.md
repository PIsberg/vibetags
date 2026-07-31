<!-- VIBETAGS-START -->
# Rules for LayoutEngine

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Stateless; safe to share across render threads

## Polymorphic Extension Pattern
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.
<!-- VIBETAGS-END -->
