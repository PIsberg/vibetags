# Logging

The full logging law for VibeTags. `CLAUDE.md` carries the one-line version; this is the
contract behind it. VibeTags runs inside javac, so its output is somebody else's build log.
Two audiences, two budgets.

- **`messager` (NOTE/WARNING) is the user-facing channel.** It goes to the compiler output every
  consumer sees. Add a line there only when a developer must act or would ask why a file changed.
- **`log` (SLF4J to `vibetags.log`) is the diagnostic channel.** `INFO` stays scarce: version,
  root, per-service status, the outcome of a run. `DEBUG` is where the narrative lives and it is
  free to be generous, because it is off unless `-Avibetags.log.level=DEBUG` asks for it.

Write events, not positions:

- `domain.event key=value key=value`, one event per line, lower-case dotted names
  (`write.skip`, `write.commit`, `round.write`). A grep for `write.skip` should answer
  "why was nothing written?" without a debugger.
- Log the branch taken and the values that decided it: `write.skip file=CLAUDE.md
  reason=cache-unchanged bytes=2481`, never `entering writeFileIfChanged`.
- `reason=` is mandatory on any `.skip` event. A skip with no reason is the log line people
  actually need and the one that is always missing.
- Guard with `log.isDebugEnabled()` in hot paths (the writer and the cache run per file, per
  build) so a disabled level formats nothing.
- `ERROR` means the build is affected. Generation failures that are downgraded to a warning are
  `WARN` at most.
- **A log event asserted in a test is a contract.** `GuardrailFileWriterLogContractTest` pins the
  writer's skip reasons and `ModuleSidecarLogContractTest` the sidecar reader's
  (`sidecar.skip` / `sidecar.prune`, with `stale-format`, `malformed`, `future-version`,
  `module-gone`, `invalid-module-path`, `superseded`, `unreadable`); renaming one of those events
  is a breaking change, not a cleanup.
- When you fix a bug, add the DEBUG line that would have made it obvious in one read, and keep it.

Rationale and the longer argument: *Vibe Architecture*, Chapter 6b, "The Log Is a Feedback Loop".
