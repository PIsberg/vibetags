# VibeTags Enforcing-Mode Example

Guardrails are advisory by design. This example shows the opt-in exception: for the three
families whose promise the processor can prove from the javac element model, enforcement turns a
shape drift into a compile error.

| Family | Annotation | What is frozen |
|---|---|---|
| `locked` | `@AILocked` | The element's structural signature |
| `contract` | `@AIContract` | Method name, parameter types, return type, checked exceptions |
| `publicapi` | `@AIPublicAPI` | A type's supertypes plus its public and protected member signatures |

Bodies, comments, formatting and private members are invisible to the check on purpose: an
enforcement that fires when someone reformats a locked file gets switched off.

## The workflow

The build always compiles with `-Avibetags.enforce=locked,contract,publicapi`, checked against the
committed [`.vibetags-baseline`](.vibetags-baseline):

```bash
mvn clean compile          # green: the 3 guarded elements match the baseline
```

Change a guarded shape, for example the `long consumedUnits` parameter of
`TariffEngine.computeTariff` to `int`, and the same command fails:

```
[ERROR] VibeTags: @AILocked violation - com.example.enforcing.TariffEngine.computeTariff(java.lang.String,long)
        was approved in .vibetags-baseline but this compilation has no such guarded element.
[ERROR]   If the change is intended, run once with -Avibetags.baseline.update=true and commit the
          new baseline, so the change is reviewed rather than assumed.
```

Both directions are checked, and the second is the one that matters: a changed signature abandons
its approved baseline entry rather than editing it, so renames, deletions and removed annotations
are violations too. When the change is intended:

```bash
mvn compile -Dvibetags.baseline.update=true    # re-record the shapes
git diff .vibetags-baseline                    # the PR now shows WHAT changed, in full
```

The baseline stores full signatures rather than hashes for exactly that diff.

## What this example does not show

- Naming a family the processor cannot prove (`callersonly`, `strictclasspath`, `threadsafe`,
  `testdriven`) produces a `[WARNING]` explaining the boundary, not silent non-enforcement.
- Switching enforcement on before any baseline exists warns and checks nothing, rather than
  failing every build on day one.

Both behaviours are pinned by `EnforcingModeEndToEndTest` in the library. The full semantics:
[docs/PROCESSOR.md](../../docs/PROCESSOR.md#enforcing-mode--avibetagsenforce).

## CI

CI builds this example, verifies the committed files regenerate byte for byte, then drifts the
locked signature with sed and asserts the build goes red with the violation above. The failure
mode is gated, not claimed.
