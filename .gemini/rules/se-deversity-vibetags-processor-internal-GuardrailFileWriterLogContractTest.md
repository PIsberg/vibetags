<!-- VIBETAGS-START -->
# Rules for GuardrailFileWriterLogContractTest

## Context & Focus
- **Focus**: The event names and reason= values asserted here are the writer's published contract, not test scaffolding: people grep build logs for write.skip and reason=cache-unchanged. Add a case when you add an event
- **Avoid**: Renaming or merging an asserted event to tidy the assertions; that is a breaking change for every consumer parsing a build log, and nothing else catches it
<!-- VIBETAGS-END -->
