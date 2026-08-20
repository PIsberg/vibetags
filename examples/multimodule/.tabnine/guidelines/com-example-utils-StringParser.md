<!-- VIBETAGS-START -->
# AI Guidelines for StringParser

## Context & Focus
- **Focus**: Optimize for memory usage over CPU speed. Minimize object allocations and avoid creating intermediate string objects.
- **Avoid**: java.util.regex, String.split(), StringBuilder in loops
<!-- VIBETAGS-END -->
