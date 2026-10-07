<!-- VIBETAGS-START -->
# Rules for TomlValueSpans

## Context & Focus
- **Focus**: The span body is annotation text, dependency JARs' included, spliced into a PR-Agent config the user owns: keep it Escape.tomlMultiline-encoded and marker-defused
- **Avoid**: Splicing raw text: a dependency could close the string and add review settings, or end the span early so the value grows a copy of itself on every build
<!-- VIBETAGS-END -->
