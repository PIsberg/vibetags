<!-- The diff shows what changed. This template asks for the three things a diff cannot
     show: why, how it was verified, and where it came from. Delete what does not apply,
     but delete it deliberately. -->

## Why

<!-- The failure this change prevents, or the capability it adds. One paragraph. -->

## Verification

<!-- What actually ran, with results. "Skipped" and "not run" are distinct from "passed";
     say which is which. For a bug fix, name the test that failed before the fix. -->

- [ ] `mvn test` (fast tier) from `vibetags/`
- [ ] `mvn test -Pe2e` when the change touches the processor, a renderer, or a writer
- [ ] `mvn clean compile -Pself-annotate` and committed the regenerated guardrail files,
      when the change touches annotations on this repo's own sources
- [ ] `pre-commit run --all-files` after `git add`
- [ ] Docs that this change makes wrong are updated in this same PR

## Provenance and prompt lineage

<!-- Who authored this: human, agent, or pair. For agent-assisted work, link the session
     and record the load-bearing intent: the instruction that shaped the change, not the
     whole conversation. A regression later should be bisectable to the instruction that
     caused it. -->

- Author:
- Session:
- Load-bearing intent:
