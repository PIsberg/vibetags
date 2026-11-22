# The new-code quality gate, run once on all code

- **Date:** 2026-11-22.
- **Scope:** `vibetags/src/main/java` (the annotation processor) at commit ae15384e, which is `main`
  at 9dce2b47 plus the three commits of PR #1152, the one that adds the gate. The CLI, KSP and
  annotations modules are not measured; the gate measures only the processor's source root.
- **Status:** point-in-time analysis, not current reference. The gate in
  [`tools/quality-gate`](../../tools/quality-gate/README.md) is the reference; when this file and
  the gate disagree, the gate wins.
- **Question:** what would the new-code quality gate say if every line of the processor were new
  code?

## Method

The gate compares a change against a base. With Git's empty tree as the base every line is new,
and with an empty PMD report as the base's complexity every method over a threshold is new. Same
scripts, same thresholds, nothing changed for the run:

```bash
cd vibetags && mvn test -Pe2e                                   # 4,447 tests; writes jacoco.xml
mvn -q pmd:pmd@complexity pmd:cpd@duplication && cd ..
echo '<pmd version="7"></pmd>' > /tmp/empty-pmd.xml
python3 tools/quality-gate/quality_gate.py \
  --base 4b825dc642cb6eb9a060e54bf8d69288fbee4904 \
  --jacoco vibetags/target/site/jacoco/jacoco.xml \
  --cpd vibetags/target/cpd-new-code/cpd.xml \
  --pmd-head vibetags/target/pmd-complexity/pmd.xml \
  --pmd-base /tmp/empty-pmd.xml
```

The coverage report has to come from the full suite run immediately before. The first attempt read
a report the pre-commit self-check had just rewritten (it runs `mvn clean test` with one test
class) and measured 6.4%. JaCoCo matches coverage to classes by checksum, so a report from a
partial run reads every other line as missed.

## Result

| Condition | Threshold | All code | Result |
|---|---|---|---|
| Coverage | >= 80% | **95.6%** (11,685 of 12,228 executable lines) | pass |
| Duplication | <= 3% | **0.0%** (0 of 31,026 lines) | pass |
| Complexity | no method over CognitiveComplexity 15 or NcssCount 60 | **89 findings in 82 methods** (81 CognitiveComplexity, 8 NcssCount) | fail |
| Reliability and security | no finding | enforced on all code by PMD, SpotBugs, Error Prone and NullAway | build |
| Dependency risk | none added | `dependency-review.yml` | workflow |

Duplication at 0% is expected rather than measured luck: `cpd-check` already fails the build on any
clone of 100 tokens or more, which is the same threshold this report uses.

None of the 89 findings fails a pull request. The gate fails only a method that is new over a
threshold or that a change made worse, so these are the existing debt it holds still, not a red
build.

## Methods over the complexity thresholds

Sorted by cognitive complexity (Sonar's java:S3776 measure; threshold 15). NCSS is the
non-comment statement count (threshold 60). Names are relative to `se.deversity.vibetags.processor`.

| Method | Cognitive complexity | NCSS |
|---|---|---|
| `internal.ModuleSidecar#load(Path)` | 86 | 106 |
| `internal.validation.CoreRules#all()` | 59 |  |
| `internal.GuardrailFileWriter#writeWithMarkers(Path, String, String, String, String, String, boolean, String[], BasicFileAttributes)` | 48 | 80 |
| `internal.ModuleSidecar#mergeFor(String, List<ModuleSidecar>, boolean)` | 47 |  |
| `internal.ReactorRootDetector#stripped(String, boolean)` | 47 |  |
| `internal.ModuleSidecar#dropSupersededRegions(List<ModuleSidecar>, List<Path>, List<Long>, boolean, Logger)` | 45 |  |
| `AIGuardrailProcessor#mergeAcrossModules(Map<String, String>, Map<String, Path>, List<ModuleSidecar>, Logger)` | 44 |  |
| `internal.TomlValueSpans#scan(String)` | 43 | 66 |
| `AIGuardrailProcessor#generateFiles()` | 40 | 106 |
| `internal.TomlValueSpans#mergeTable(String, String, String, String)` | 40 | 61 |
| `internal.content.JsonLineArraysMerge#parse(String)` | 34 |  |
| `internal.MethodBodyGuardrailScanner.BodyScanner#determinedByTheRound(TreePath, Tree)` | 33 |  |
| `internal.ModuleSidecar#mergeGranularUnits(List<ModuleSidecar>, Function<ModuleSidecar, Map<String, GranularContribution>>, boolean, String)` | 31 |  |
| `internal.validation.DuplicateYamlKeyRule#find(String, Set<String>, String, String)` | 31 |  |
| `internal.Json#isJsonNumber(String)` | 26 |  |
| `internal.MethodBodyGuardrailScanner.BodyScanner#spelledOut(Tree)` | 26 |  |
| `internal.ModuleSidecar#readAll(Path, boolean, Logger, Loader, ModuleSidecar)` | 26 |  |
| `internal.RoundSources#resolve()` | 26 |  |
| `internal.content.RoleConfig#load(Path)` | 26 |  |
| `AIGuardrailProcessor#warnAboutSiblingRuleFilesThatAreMissing(Set<String>, Map<String, Path>, List<ModuleSidecar>)` | 25 |  |
| `internal.GuardrailContentBuilder#build()` | 25 |  |
| `internal.content.annotations.AIKeepInSyncFormatter#render(TaggedElement, StringBuilder, Platform)` | 25 |  |
| `AIGuardrailProcessor#applyTransitiveRules()` | 23 |  |
| `internal.ElementNaming#typeString(TypeMirror)` | 23 |  |
| `internal.GranularRulesWriter#plan(Map<TaggedElement, GranularBody>, RoleConfig, String, List<String>)` | 23 |  |
| `internal.ModuleSidecar#hoistScopedIndex(List<Entry<String, String>>)` | 23 |  |
| `internal.PartialRoundDetector#missingRecordedElements(Path, Set<String>, String, Path, String, Set<Path>, Set<String>)` | 23 |  |
| `internal.SourceDigest#of(String, Map<String, String>, String, boolean, Path, Path, Collection<Path>, String)` | 23 |  |
| `internal.content.MarkdownSectionMerge#foldSection(Container, Container)` | 23 |  |
| `internal.content.RoleConfig#globToRegex(String)` | 23 |  |
| `internal.content.annotations.AIObservabilityFormatter#render(TaggedElement, StringBuilder, Platform)` | 22 | 76 |
| `AIGuardrailProcessor#checkFiles()` | 22 |  |
| `internal.MethodBodyGuardrailScanner#countValuesWithoutTrees(RoundEnvironment, RoundSources, Elements)` | 22 |  |
| `internal.StubSourceNames#withoutComments(String)` | 22 |  |
| `internal.GenerationLock#acquireFile(Path, String, Logger, Duration)` | 21 |  |
| `internal.validation.LockedOverrideRule#visitTypes(ValidationContext, Element, TypeElement, TypeMirror, ExecutableElement, Elements, Types)` | 21 |  |
| `internal.GuardrailFileWriter#writeOnce(Path, String, String, boolean)` | 20 |  |
| `internal.ModuleRootResolver#fromRound(RoundSources)` | 20 |  |
| `internal.ModuleSidecar#applyRootIndexModeTo(Path, List<ModuleSidecar>)` | 20 |  |
| `internal.ModuleSidecar.SummaryScan#finishValue()` | 20 |  |
| `internal.SourcePositionResolver#delegateOf(ProcessingEnvironment)` | 20 |  |
| `internal.GranularRulesWriter#stemsWithPrefix(Map<String, Path>, Set<String>, Collection<String>)` | 19 |  |
| `internal.TransitiveManifestReader#resolve(Filer, Set<String>)` | 19 |  |
| `internal.content.JsonLineArraysMerge#merge(List<Entry<String, String>>)` | 19 |  |
| `internal.content.annotations.AIGeneratedFormatter#render(TaggedElement, StringBuilder, Platform)` | 19 |  |
| `internal.content.platforms.GranularIndexSection#appendMarkdownIndex(StringBuilder, Platform, RenderingContext)` | 19 |  |
| `AIGuardrailProcessor#warnIfSubprojectsShareTheRootIdentity(Path)` | 18 |  |
| `internal.JsonValueSpans#merge(String, List<SharedKey>, Map<String, String>)` | 18 |  |
| `internal.MethodBodyGuardrailScanner.BodyScanner#visitAnnotation(AnnotationTree, Void)` | 18 |  |
| `internal.MirrorConfig#read(Path)` | 18 |  |
| `internal.ModuleRootResolver#withRecordedOwner(ModuleIdentity, RoundSources, Path)` | 18 |  |
| `internal.PartialRoundDetector#unreadSourcesNamingTheAnnotations()` | 18 |  |
| `internal.content.ClaudeSectionMerge#parse(String)` | 18 |  |
| `internal.content.annotations.AIBannedApiFormatter#render(TaggedElement, StringBuilder, Platform)` | 18 |  |
| `internal.ElementNaming#stripAnnotations(String)` | 17 |  |
| `internal.HandAuthoredYamlKeyWarner#warn(Messager, Logger, Path, Map<String, Path>)` | 17 |  |
| `internal.Json.Parser#readString()` | 17 |  |
| `internal.ProcessorCoexistence#check(ProcessingEnvironment, RoundEnvironment, ClassLoader, ClassLoader, Logger)` | 17 |  |
| `internal.content.ClaudeSectionMerge#merge(List<String>)` | 17 |  |
| `internal.content.MarkdownSectionMerge#parse(String)` | 17 |  |
| `internal.content.annotations.AIImmutableFormatter#render(TaggedElement, StringBuilder, Platform)` | 17 |  |
| `internal.content.annotations.AILoadBearingFormatter#render(TaggedElement, StringBuilder, Platform)` | 17 |  |
| `internal.content.annotations.AIThreadAffinityFormatter#render(TaggedElement, StringBuilder, Platform)` | 17 |  |
| `internal.content.platforms.GranularIndexSection#appendXmlIndex(StringBuilder, Platform, RenderingContext)` | 17 |  |
| `internal.content.annotations.AIDeprecatedFormatter#render(TaggedElement, StringBuilder, Platform)` | 16 | 65 |
| `internal.GranularRulesWriter#write(Map<TaggedElement, GranularBody>, Map<String, Path>, Set<String>, RoleConfig, String, List<String>, Map<String, GranularContribution>)` | 16 |  |
| `internal.GuardrailFileWriter#legacyXmlBlockEnd(String)` | 16 |  |
| `internal.JsonValueSpans.Scanner#skipValue()` | 16 |  |
| `internal.ModuleOutputWriter#write(Path, Path, Map<String, Path>, Set<String>, AnnotationCollector, Result, String, String, RoleConfig, GuardrailFileWriter, Messager, List<ModuleSidecar>, String, String)` | 16 |  |
| `internal.RuleFileLengthWarner#measure(Messager, Logger, Path, String, Path)` | 16 |  |
| `internal.WriteCache#flush()` | 16 |  |
| `internal.content.annotations.AIIgnoreFormatter#render(TaggedElement, StringBuilder, Platform)` | 16 |  |
| `internal.content.annotations.AITestDrivenFormatter#render(TaggedElement, StringBuilder, Platform)` | 16 |  |
| `AIGuardrailProcessor#warnAboutPartialMerges(Set<String>, Map<String, Path>, List<ModuleSidecar>)` | 15 |  |
| `internal.FinalRoundElements#hasError(TypeMirror)` | 15 |  |
| `internal.GuardrailFileWriter#frontMatterEnd(String)` | 15 |  |
| `internal.MethodBodyGuardrailScanner#spelledOutInTheRound(Object, Element, Set<? extends Element>, RoundSources)` | 15 |  |
| `internal.TransitiveManifestReader#candidatesFromImports(Collection<ImportedName>)` | 15 |  |
| `internal.content.MarkdownSectionMerge#merge(List<String>)` | 15 |  |
| `internal.content.YamlMergeShape#mergeSourceSets(List<String>)` | 15 |  |
| `internal.content.platforms.ClaudeTestDrivenSection#render(Collection<TaggedElement>, StringBuilder)` | 15 |  |
| `internal.content.platforms.ClaudeRenderer#render(GuardrailModel, Platform, RenderingContext)` |  | 64 |

## Executable lines no test runs, by file

543 lines in 60 files. Paths are relative to `vibetags/src/main/java/se/deversity/vibetags/processor/`.

| File | Uncovered lines |
|---|---|
| `AIGuardrailProcessor.java` | 58 |
| `internal/MethodBodyGuardrailScanner.java` | 43 |
| `internal/GuardrailFileWriter.java` | 36 |
| `internal/ModuleSidecar.java` | 33 |
| `internal/PartialRoundDetector.java` | 32 |
| `internal/ElementNaming.java` | 29 |
| `internal/GenerationLock.java` | 23 |
| `internal/TransitiveManifestReader.java` | 19 |
| `internal/FinalRoundElements.java` | 17 |
| `internal/RoundSources.java` | 17 |
| `internal/TomlValueSpans.java` | 16 |
| `internal/content/AnnotationDescriptors.java` | 16 |
| `internal/RuleFileLengthWarner.java` | 12 |
| `internal/SourcePositionResolver.java` | 12 |
| `internal/GranularRulesWriter.java` | 10 |
| `internal/SourceDigest.java` | 10 |
| `internal/StubSourceNames.java` | 10 |
| `internal/content/MarkdownSectionMerge.java` | 10 |
| `internal/LazyFileAppender.java` | 9 |
| `internal/ReplayableDiagnostic.java` | 9 |
| `internal/content/JsonLineArraysMerge.java` | 9 |
| `internal/JsonValueSpans.java` | 8 |
| `internal/ProcessorVersion.java` | 8 |
| `VibeTagsLogger.java` | 7 |
| `internal/content/platforms/GranularIndexSection.java` | 7 |
| `internal/MirrorConfig.java` | 6 |
| `internal/ModuleRootResolver.java` | 5 |
| `internal/validation/ArchitectureRule.java` | 5 |
| `internal/ProcessorCoexistence.java` | 4 |
| `internal/ReactorRootDetector.java` | 4 |
| `internal/SingleLineAnnotation.java` | 4 |
| `internal/content/GranularPairing.java` | 4 |
| `internal/content/TomlInstructionsMerge.java` | 4 |
| `internal/validation/LockedOverrideRule.java` | 4 |
| `internal/AtomicFileReplacer.java` | 3 |
| `internal/WriteCache.java` | 3 |
| `internal/content/RoleConfig.java` | 3 |
| `internal/content/YamlMergeShape.java` | 3 |
| `internal/validation/DuplicateYamlKeyRule.java` | 3 |
| `internal/AnnotationCollector.java` | 2 |
| `internal/MirrorWriter.java` | 2 |
| `internal/ServiceRegistry.java` | 2 |
| `internal/content/GranularBody.java` | 2 |
| `internal/content/annotations/AIIgnoreFormatter.java` | 2 |
| `internal/validation/ValidationContext.java` | 2 |
| `model/TaggedElement.java` | 2 |
| `internal/DestructiveRewriteWarner.java` | 1 |
| `internal/GuardrailContentBuilder.java` | 1 |
| `internal/GuardrailModelBuilder.java` | 1 |
| `internal/TransitiveManifest.java` | 1 |
| `internal/UseSiteAnnotationValues.java` | 1 |
| `internal/content/GranularContribution.java` | 1 |
| `internal/content/GranularSections.java` | 1 |
| `internal/content/LocksReportMerge.java` | 1 |
| `internal/content/PlatformRendererRegistry.java` | 1 |
| `internal/content/annotations/AIThreadAffinityFormatter.java` | 1 |
| `internal/content/platforms/ClaudeSkillRenderer.java` | 1 |
| `internal/content/platforms/ClaudeTestDrivenSection.java` | 1 |
| `internal/content/platforms/GuardrailInstructionBlock.java` | 1 |
| `internal/content/platforms/JunieRenderer.java` | 1 |

## Reading it

- `ModuleSidecar#load(Path)` is the most complex method in the processor by a wide margin, at
  cognitive complexity 86 and 106 statements, about twice `generateFiles()`'s 40.
- Seven methods score 44 or more. Four of them are in `ModuleSidecar` and `AIGuardrailProcessor`,
  the two classes the 2026-11-22 refactors started on.
- Coverage is high enough that the gate's 80% on new code is not the binding constraint;
  complexity is.
