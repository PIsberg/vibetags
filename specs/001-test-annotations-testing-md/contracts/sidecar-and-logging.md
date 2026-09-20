# Contract: sidecar keys and log events

Internal contracts, but shared across independently compiled modules and across processor
versions, so they are contracts all the same.

## Sidecar

New key namespace: `~tfull~<service>`.

| Rule | Reason |
|---|---|
| Written only by a routed round, only for services where `routesTestGuardrails` is true | keeps every other sidecar byte-identical to today |
| Holds the rendering the round would have produced without `TESTING.md` | it is the fallback, so it must equal the pre-feature body; a test compares the two byte for byte |
| Never read while `testing` is opted in | the routed body is the truth then |
| Read in place of `<service>` when `testing` is not opted in | deleting `TESTING.md` plus a main-only build loses nothing |
| A sidecar without it merges exactly as today | sidecars written by older versions, and by non-routed rounds |
| An older processor reading a newer sidecar ignores it | same behaviour as `~idx~`, `~gran~`, `~granname~`; pinned by a test that loads a sidecar carrying an unknown `~x~` key |

Transient state `testingOptedIn` is set by `ModuleSidecar.readAll(root)` using the same opt-in
predicate `ServiceRegistry` uses (not a bare `Files.exists`), and is never saved.
`mergeFor(String, List<ModuleSidecar>, boolean)` keeps its signature.

## Log events

Format per `docs/LOGGING.md`: `domain.event key=value`.

| Event | Level | Emitted when | Keys |
|---|---|---|---|
| `testing.route` | INFO | routed round | `sourceSet`, `routed` (services), `moved` (non-safety references), `kept` (safety references) |
| `testing.skip` | DEBUG | `testing` opted in, round not routed | `reason=not-test-round` or `reason=no-test-guardrails`, `sourceSet` |
| `merge.testing.fallback` | DEBUG | merge substituted `~tfull~` | `service`, `module` |

- Nothing is logged when `testing` is not opted in.
- A mixed main-and-test round is indistinguishable from a main round at this point, so it logs
  `reason=not-test-round`; the limit is documented, not detected.
- `testing.route` and both `testing.skip` reasons get contract tests, which makes their names and
  keys frozen.
