# Archived diagrams

Diagrams that were once in `docs/ARCHITECTURE.md` and have been superseded. They are kept
because a superseded diagram is still evidence of what the design looked like at the time, and
deleting it makes the history of the architecture harder to read than keeping it does.

Nothing here is regenerated. `docs/diagrams/generate.cjs` no longer lists these `.puml` files,
so editing one has no effect — if a diagram here becomes relevant again, move it back out of
`archive/` and re-add it to that list.

| Diagram | Retired | Superseded by | Why |
|---|---|---|---|
| `class-diagram.puml` / `.png` | 1.0.0-RC7 | [`codekarta/class-diagram.svg`](../codekarta/class-diagram.svg) and [`codekarta/annotations/class-diagram.svg`](../codekarta/annotations/class-diagram.svg) | It was hand-maintained and had drifted: it drew 8 of the 44 `@AI*` annotations and named 8 internal helper classes, of which there are now 17. Both halves are now parsed from source by [code-karta](https://github.com/PIsberg/codekarta), so the same drift cannot recur — a stale parsed diagram shows up as a diff, not as a wrong picture. |

The hand-drawn diagrams that remain live — `component-diagram`, `build-sequence`, `data-flow`,
`platform-output` — are *not* candidates for the same treatment. Each of them shows something a
parser cannot see: a developer, a build system, a data-flow decision, or the same annotation
rendered into five different output formats. Parsed diagrams replace hand-drawn ones only where
the hand-drawn one was describing structure that exists in the source.
