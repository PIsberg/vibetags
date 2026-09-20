<!-- VIBETAGS-START -->
# Rules for MultiModuleYamlValidityTest

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: At least one fixture here gives a single module two sidecars sharing one region id, that is, an annotated main source set and an annotated test source set
- **Breaks if changed**: Every reactor fixture annotates main sources only. That is exactly how the duplicate top-level key defect survived: one body per region, so no region ever carried two scaffolds, and all six YAML outputs shipped unparseable
<!-- VIBETAGS-END -->
