Feature: Core guardrail flows
  The behaviours a consumer relies on before reading any documentation: opting a platform in
  by creating its file, keeping hand-written content safe across regenerations, and opting a
  platform out by deleting its file. These scenarios are executed by CoreFlowsBddTest, which
  fails the build if a scenario here has no binding, so this file cannot rot into fiction.

  Scenario: File presence is the only opt-in
    Given a project whose only AI config file is CLAUDE.md
    When the annotated sources are compiled
    Then CLAUDE.md contains a generated guardrail block
    And no other platform file has been created

  Scenario: Hand-authored content survives regeneration
    Given a compiled project with hand-authored notes outside the markers in CLAUDE.md
    When the annotated sources are compiled again
    Then the hand-authored notes are still present
    And the generated block between the markers is still there

  Scenario: Deleting a generated file opts the platform out permanently
    Given a compiled project opted into CLAUDE.md
    When CLAUDE.md is deleted and the sources are compiled again
    Then CLAUDE.md has not been recreated
