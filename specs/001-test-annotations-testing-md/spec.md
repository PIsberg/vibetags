# Feature Specification: Route Test-Code Guardrails to TESTING.md

**Feature Branch**: `001-test-annotations-testing-md`

**Created**: 2026-09-19

**Status**: Draft

**Input**: User description: "I would like to change so that annotations used in tests ends up in TESTING.md instead when that file is present"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Test-code guardrails land in TESTING.md (Priority: P1)

A project owner annotates test code (fixtures, test helpers, test classes) with `@AI*` guardrails.
Today those guardrails are merged into the same always-loaded instruction files (`CLAUDE.md`,
`AGENTS.md` and the other aggregates) as the guardrails for production code, so every agent
session pays for test-only rules even when the task never touches a test. The owner creates a
`TESTING.md` file. From the next build on, guardrails that come from test code are written to
`TESTING.md`, and the other instruction files carry production-code guardrails only.

**Why this priority**: This is the feature. It separates what an agent must know to change
production code from what it must know to change tests, and it shrinks the files that are loaded
on every session.

**Independent Test**: In a project with one annotated production class and one annotated test
class, create an empty `TESTING.md` and an empty `CLAUDE.md`, build, and confirm the test class
appears only in `TESTING.md` and the production class only in `CLAUDE.md`.

**Acceptance Scenarios**:

1. **Given** a project with `TESTING.md` and `CLAUDE.md` present and annotated elements in both
   main and test code, **When** the project is built including its tests, **Then** `TESTING.md`
   lists every non-safety guardrail from test code, and `CLAUDE.md` lists every guardrail from
   main code and no non-safety guardrail from test code.
2. **Given** the same project, **When** an annotation is added to a test class and the project is
   rebuilt, **Then** the new guardrail appears in `TESTING.md` and no other instruction file
   changes.
3. **Given** the same project, **When** the last annotation is removed from test code and the
   project is rebuilt, **Then** the generated region of `TESTING.md` no longer lists it, and the
   file itself is kept.
4. **Given** `TESTING.md` contains hand-written text, **When** the project is built, **Then** the
   hand-written text is unchanged and the generated content sits inside its own marked region.

---

### User Story 2 - No TESTING.md, no change (Priority: P1)

A project owner who has not created `TESTING.md` upgrades VibeTags. Nothing about their generated
files changes: test-code guardrails keep going where they go today, and no `TESTING.md` appears.

**Why this priority**: File presence is the only opt-in in VibeTags. An upgrade that rewrites
every consumer's instruction files, or creates a file nobody asked for, is a regression for every
existing user.

**Independent Test**: Build an existing fixture project with and without the feature and compare
every generated file byte for byte; confirm no `TESTING.md` exists afterwards.

**Acceptance Scenarios**:

1. **Given** a project without `TESTING.md`, **When** it is built, **Then** every generated file is
   byte-identical to what the previous version produced and `TESTING.md` is not created.
2. **Given** a project that had `TESTING.md` and whose owner deletes it, **When** it is rebuilt,
   **Then** test-code guardrails return to the other instruction files and `TESTING.md` is not
   recreated.

---

### User Story 3 - Agents can find the test guardrails (Priority: P2)

An agent is asked to change a test. `TESTING.md` is not a file any AI tool loads by itself, so the
always-loaded instruction files tell the agent that test-code guardrails live in `TESTING.md` and
must be read before test code is modified.

**Why this priority**: Without a pointer, moving guardrails to `TESTING.md` removes them from the
agent's view, which turns a context saving into a loss of protection. It ranks below the routing
itself because it has no meaning until the routing exists.

**Independent Test**: Build the User Story 1 fixture and confirm each always-loaded instruction
file contains one reference to `TESTING.md`, and that the reference is absent when `TESTING.md` is
absent or has no test guardrails to hold.

**Acceptance Scenarios**:

1. **Given** `TESTING.md` is present and test code carries guardrails, **When** the project is
   built, **Then** each opted-in always-loaded instruction file contains a single pointer naming
   `TESTING.md` as the place to look before changing test code.
2. **Given** `TESTING.md` is absent, **When** the project is built, **Then** no instruction file
   mentions `TESTING.md`.

---

### User Story 4 - Safety guardrails on test code stay visible (Priority: P2)

A test fixture holds recorded personal data and carries `@AIPrivacy`; a contract-test file carries
`@AILocked`. Those guardrails must reach an agent that never opens `TESTING.md`, so they do not
move.

**Why this priority**: The six safety annotations (`@AILocked`, `@AICore`, `@AIPrivacy`,
`@AIIgnore`, `@AIAudit`, `@AISecure`) are the ones VibeTags already keeps inline when everything
else collapses to an index, because a missed safety rule costs more than the context it saves.

**Independent Test**: Annotate one test class with a safety annotation and one with a non-safety
annotation, build with `TESTING.md` present, and check where each one lands.

**Acceptance Scenarios**:

1. **Given** `TESTING.md` is present and a test class carries a safety annotation, **When** the
   project is built, **Then** that guardrail appears in the always-loaded instruction files exactly
   as it would without `TESTING.md`, and does not appear in `TESTING.md`.
2. **Given** the same project and a second test class carrying only a non-safety annotation,
   **When** the project is built, **Then** the second class appears in `TESTING.md` only.
3. **Given** one test class carrying both a safety and a non-safety annotation, **When** the
   project is built, **Then** the safety guardrail stays always-loaded and the non-safety guardrail
   goes to `TESTING.md`.

---

### Edge Cases

- **Build without tests**: a build that compiles main code only (or skips test compilation) must
  leave the generated region of `TESTING.md` as the last full build wrote it, not empty it. This is
  the same rule that already stops a partial build from erasing guardrails.
- **Tests-only build**: a build that compiles only test code must not remove main-code guardrails
  from the other instruction files.
- **`TESTING.md` present, no annotated test code**: the file is treated like any other opted-in
  file with nothing to report; it is not deleted and its hand-written content is untouched.
- **Guardrail that spans main and test code**: a role or context rule matched by both main and
  test elements is split, the main elements stay where they are and the test elements go to
  `TESTING.md`.
- **Several test source sets**: unit tests, integration tests and similar test source sets all
  count as test code.
- **Multi-module builds**: test-code guardrails from every module reach `TESTING.md` under the
  same merging rules the other aggregated files follow; a module built alone must not erase
  another module's entries.
- **Files that do a job rather than instruct**: ignore lists and review-tool configuration keep
  their test-code entries, because an agent never reads them as guidance and removing an entry
  changes tool behaviour. An `@AIIgnore` on a test file still excludes that file.
- **Per-element scoped rule files**: rule files that load by file path are already scoped to the
  test file they describe and are not affected.
- **Check mode**: a stale `TESTING.md` is reported as drift the same way any other generated file
  is.
- **Non-Java sources**: test code written in Kotlin or Groovy is recognised as test code on the
  same basis as Java test code.
- **Untrusted text**: annotation text written into `TESTING.md` is encoded exactly as it is for
  the existing instruction files.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST treat the presence of `TESTING.md` at the project's output root as
  the only opt-in for this feature, and MUST NOT create `TESTING.md` when it is absent.
- **FR-002**: When `TESTING.md` is absent, every generated file MUST be byte-identical to the
  output of the previous release for the same input.
- **FR-003**: When `TESTING.md` is present, the system MUST write every non-safety guardrail that
  originates from an element in a test source set into `TESTING.md`.
- **FR-004**: When `TESTING.md` is present, the system MUST omit those guardrails from every
  other aggregated instruction file. Safety guardrails are excluded from the move by FR-010.
- **FR-005**: The system MUST classify an element as test code by the source set it is compiled
  in (`test`, `integrationTest` and other test source sets), not by its class name and not by
  which annotation it carries.
- **FR-006**: The system MUST keep generated content in `TESTING.md` inside a marked region and
  MUST preserve all hand-written content outside that region.
- **FR-007**: A build that did not compile the test sources MUST NOT remove or empty test-origin
  content in `TESTING.md`, and a build that compiled only test sources MUST NOT remove main-origin
  content from any other file.
- **FR-008**: When `TESTING.md` is present and holds at least one guardrail, each opted-in
  always-loaded instruction file MUST contain a pointer directing agents to `TESTING.md` before
  they modify test code: exactly one per file in a single-module project, and exactly one per
  contributing module's section in a multi-module project. The pointer MUST be absent otherwise.
  (Amended during planning, 2026-09-19: was "exactly one per file"; see research R6.)
- **FR-009**: Files that configure tool behaviour rather than instruct an agent (ignore lists,
  review-tool configuration) and per-element scoped rule files MUST be unaffected by this feature.
- **FR-010**: The six safety annotations (`@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`,
  `@AIAudit`, `@AISecure`) on test code MUST stay in the always-loaded instruction files exactly
  as they are rendered today, whether or not `TESTING.md` is present, and MUST NOT be duplicated
  into `TESTING.md`.
- **FR-011**: In multi-module builds, `TESTING.md` MUST aggregate test-origin guardrails from all
  modules under the same rules as the existing aggregated files, including that a module built in
  isolation does not erase other modules' entries.
- **FR-012**: Deleting `TESTING.md` MUST return test-origin guardrails to the other instruction
  files on the next full build, with no leftover pointer.
- **FR-013**: Creating, deleting or changing `TESTING.md` eligibility MUST cause regeneration on
  the next build, so a cached "nothing changed" result can never leave guardrails in the wrong
  file.
- **FR-014**: Check mode MUST report a stale or missing-region `TESTING.md` the same way it
  reports any other generated file.
- **FR-015**: The system MUST log the routing decision (test-origin guardrails routed to
  `TESTING.md`, or not routed and why) following the project's existing logging conventions.
- **FR-016**: User documentation (platform list, processor behaviour, changelog) MUST describe the
  new file, its opt-in, and the effect on the other files, in the same change.

### Key Entities

- **Test-origin guardrail**: a guardrail whose annotated element is compiled in a test source set.
  Attributes: the annotation, the element path, the module and the source set it came from.
- **TESTING.md**: an optional, user-created instruction file at the output root. Holds a generated
  region for test-origin guardrails plus any hand-written testing guidance.
- **Always-loaded instruction file**: an existing aggregated file that an AI tool loads on every
  session (`CLAUDE.md`, `AGENTS.md` and equivalents). Loses test-origin entries and gains one
  pointer when `TESTING.md` is present.
- **Pointer**: a single generated line in an always-loaded file that names `TESTING.md`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With `TESTING.md` present, 0 non-safety test-origin guardrails appear in
  always-loaded instruction files, 100% of them appear in `TESTING.md`, and 100% of safety
  guardrails on test code remain in the always-loaded files.
- **SC-002**: With `TESTING.md` absent, 0 bytes differ in any generated file compared with the
  previous release across all existing example and fixture projects.
- **SC-003**: For a project where non-safety guardrails on test code are N% of all guardrails, the generated region of
  each always-loaded file shrinks by roughly N%, less one pointer line.
- **SC-004**: Running the same full build twice in a row changes 0 files on the second run.
- **SC-005**: Across the three build shapes (main only, tests only, main then tests), 0 guardrails
  are lost from any file.
- **SC-006**: An owner opts in with 1 action (creating the file) and opts out with 1 action
  (deleting it); no build configuration change is needed for either.
- **SC-007**: 0 bytes of hand-written content in `TESTING.md` are lost across any number of
  builds.

## Assumptions

- "Annotations used in tests" means annotations placed on elements in test source sets. It does
  not mean annotations that talk about testing (such as `@AITestDriven` on a production class);
  those describe production code and stay where they are.
- `TESTING.md` lives at the same output root as the other generated files, one per project, not
  one per module.
- `TESTING.md` uses the same Markdown marker convention as the existing Markdown aggregates, and
  the content inside it follows the layout of an existing Markdown aggregate rather than a new
  format.
- No AI tool loads `TESTING.md` automatically, which is why User Story 3 exists. If a tool adopts
  the file natively later, the pointer remains harmless.
- Guardrails inherited from dependency JARs are never test-origin, since published artifacts do
  not ship test code, so transitive guardrails are out of scope.
- Which source-set names count as test code beyond `test` and `integrationTest` is settled during
  planning; the default is any source set whose name identifies it as tests.
- This is a new output file, so delivery follows the project's existing procedure for adding a
  platform or output file.
