# Upstream PR #321 integration

Historical integration/testing record. Native latitude has since been removed; the biome replacement and other custom integrations remain. See [current custom build](../../../docs/custom-build.md).

Integrated 2026-10-22 into `1_21_1-unstable-custom`, preserving native latitude and earlier custom changes.

- Latest fetched upstream `1.21.1_unstable`: `22c2801b4772dbde04044ec80551e5bb89c845a1`. The custom branch already contained it; zero upstream commits were missing.
- [PR #321 — Native Biome Replacement](https://github.com/ETcodehome/FreeTerraForged/pull/321) remains open/unmerged upstream. Imported its exact fetched head `122eb7611e772418743b8f990df59a8dc99d22c5` with a merge, retaining upstream authorship/history.
- Pre-integration custom head: `45feb5580b541cfb307cfc4a9453b70ae6b5c881`.
- Git merged all files without conflicts. Reviewed overlapping README, MixinChunkMap, BiomePreviewResolver and MinecraftWorldgenPlanCompiler changes. Native latitude's precise active-preview origins and external-profile guard remain; replacement config snapshots participate alongside preset fingerprints in preview identity.

The resulting pipeline uses the native latitude climate for biome selection, then applies the replacement plan. The preview retains replacement provenance, crosshatching and the updated legend. Existing moisture formulas and latitude's independent strengths remain intact. This is source-level integration evidence, not visual in-game verification.

Validation on the combined source:

- `./gradlew :common:latitudeTest :fabric:build :neoforge:build --offline --console=plain` — passed; 212,612 offline climate checks plus both full loader builds.
- Re-ran the eight isolated JUnit regression tests retained from the earlier PR review using `/private/tmp/ftf-pr321-rereview.init.gradle` and its external test source directory — all passed. They cover one-hop chains/swaps, removal fallback/provenance, candidate filtering, inactive rules, replacement of removal fallbacks exactly once, and hatch distribution across zooms/centers.
- `git diff --cached --check` — clean; no unresolved merge paths.

Local run logs: `/private/tmp/ftf-pr321-integration-build.log` and `/private/tmp/ftf-pr321-integration-regressions.log`. The temporary review fixtures are separate from the repository's normal test suite. No new game session, generated-world acceptance, or performance benchmark was run; previously recorded draft release gates remain pending.

Publication continues through the existing draft feature head `feature/native-latitude-climate-custom`, targeting the fork's custom branch. This does not submit or merge changes into the upstream project.
