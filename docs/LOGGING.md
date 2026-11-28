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
  `module-gone`, `invalid-module-path`, `superseded`, `misread-source-set`, `unreadable`),
  `DeprecatedServicesTest` pins `platform.deprecated key= file= replacement=`, the WARN a build with a
  deprecated output opted in writes once per file (`replacement=none` where nothing replaces it;
  #1071), and `RuleFileLengthWarnerTest` pins `validation.rule-file-over-limit file= chars= limit=`, the WARN for
  a generated `.devin/rules/` or `.windsurf/rules/` file over Devin Desktop's per-file character cap,
  and `validation.rule-file-over-limit file= bytes= limit=` for an `.agents/rules/` file over
  Antigravity's byte cap (#850: the key names the unit, so a byte count is never logged as chars), with
  `validation.skip check=rule-file-length reason=no-markers` for a rule file VibeTags did not write,
  and `TestingRoutingLogContractTest` pins the `TESTING.md` routing decision:
  `testing.route sourceSet= routed= moved= kept=` (INFO, a routed test round: files routed,
  references moved to `TESTING.md`, safety references kept),
  `testing.skip reason=not-test-round sourceSet=` and
  `testing.skip reason=no-test-guardrails sourceSet=` (DEBUG), and
  `merge.testing.fallback service= module=` (DEBUG, the merge read a routed round's unrouted body
  because `TESTING.md` is gone). A project without `TESTING.md` logs none of them;
  renaming one of those events is a breaking change, not a cleanup. `SourceDigestEarlyExitEndToEndTest`
  pins `round.skip reason=sources-unchanged digest= rounds=` (DEBUG, the early exit ahead of the
  collection walk fired, #834); its sibling `round.skip reason=sources-appeared-after-skip digest=`
  is written when another processor generated sources after the first round was skipped.
  `PartialMergeDiagnosticsTest` pins the WARN events for a merged file that is missing a module's
  guardrails or points at rule files nobody will write: `sidecar.unreadable count= names=`,
  `mirror.skip file= reason=unusable-config detail=`,
  `merge.partial service= modulesBehind= reason=opted-in-after-last-compile`,
  `merge.partial modulesBehind= reason=granular-opted-out-after-last-compile`,
  `granular.missing entries= reason=sibling-file-absent` and
  `region.duplicate elements= regions= reason=element-claimed-twice`.
  `ModuleIdentityDiagnosticsTest` pins the WARN events for a module identity that costs
  guardrails: `module.detached module= moduleRoot= reactorRoot=`,
  `module.collapsed subprojects= root= reason=no-build-file-of-their-own`,
  `module.unidentified id= root= compilationRoot= existing=` and
  `sidecar.collision moduleId= path= other=`.
- When you fix a bug, add the DEBUG line that would have made it obvious in one read, and keep it.

Rationale and the longer argument: *Vibe Architecture*, Chapter 6b, "The Log Is a Feedback Loop".
