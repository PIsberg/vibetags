---
name: consultation-loop
description: Pre-push adversarial interrogation of the current branch's diff by a fresh reviewer with no memory of writing it - five scoped questions (done? production-ready? scales? secure? top risks?), answers must cite a line or admit "cannot tell from here". Advisory only, never a gate. Use when the user says "consult", "consultation loop", "are we done", "pre-push review", "interrogate this diff", or before opening a PR on substantial work.
---

# Consultation loop

A voluntary pre-push interrogation of the branch diff. The reviewer must be a fresh context
that did not produce the change: spawn a subagent (or a fresh `claude -p` session) and give
it only the diff and the questions below. Never answer these questions from the session that
wrote the code; a session reviewing its own work confirms its own assumptions.

This loop is advisory. It produces ranked findings for the author to weigh, not a verdict.
The blocking review is CI's job (tests, self-check, the Inquisitor); this one exists to
catch what you can still fix cheaply, before the PR exists.

## Procedure

1. Compute the diff scope: `git diff origin/main...HEAD` (after `git fetch origin main`).
2. Spawn a fresh reviewer with the framing: "You did not write this code. Assume the author
   took shortcuts. Answer each question for this diff only. Every claim cites a file:line
   from the diff; if the diff cannot answer, say 'cannot tell from here' and name what
   evidence would answer it. Do not reassure."
3. Ask the five questions, one at a time, each with its scope clause:

   1. **Are we done?** Enumerate what the task promised (the issue, the spec, the PR body
      draft) as a list, and mark each item present or absent in the diff. Done-ness is a
      list, not a feeling.
   2. **Is it production ready?** Error handling on the failure paths the diff introduces;
      logging of the decisions it takes (`domain.event key=value`, `reason=` on skips);
      behavior when its inputs are absent or malformed. Fail closed and loud.
   3. **Does it scale?** Algorithmic findings visible in the diff itself (per-element work
      inside per-file loops, unbounded accumulation, repeated I/O). Demand the distinction
      between "this is quadratic, see line N" and "cannot tell from here; needs a
      measurement" - for the latter, `load-tests/` is the harness.
   4. **Is it secure?** What new untrusted input does this diff accept, and where does that
      input cross into generated files, shell commands, or paths? For this repo the classic
      surface is annotation string values reaching generated guardrail files; `Escape.java`
      and `MarkerInjectionTest` are the precedents to check against.
   5. **Top three change requests**, ranked by risk reduced, each citing a line. No line,
      no vote.

4. Bring the answers back to the author verbatim. The author decides; unresolved
   disagreement between author and reviewer is usually a missing rule, which belongs in
   `CLAUDE.md` or an annotation, not in the argument.

## Rules

- Advisory only. Never wire this loop into CI as a gate and never treat its silence as
  approval; its value is exactly that it is cheap, fresh and opinionated.
- The reviewer gets read access, nothing that mutates.
- Findings the author rejects are recorded in the PR body with one line of reasoning, so
  the rejection is visible in review rather than silent.
