<!-- VIBETAGS-START -->
# AI Guidelines for UserEntity

## Schema & Serialization Safety
- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.
- **Reason**: Maps to the users table replicated to the billing read-model; renaming a column or changing a type needs a backward-compatible Flyway migration first
<!-- VIBETAGS-END -->
