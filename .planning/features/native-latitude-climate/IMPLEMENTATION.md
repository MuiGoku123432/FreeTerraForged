# Native latitude implementation and evidence

Date: 2026-10-22. Baseline: custom `43244515`; specification commit `e76a565b`.
Status: integrated draft implementation; not release-ready or generation-stable.

## Implementation decisions

- Add `LatitudeSettings` under existing `ClimateSettings`; strict complete-profile decoding, immutable values, absent-default omission and copy integration. Explicit null is rejected by a custom optional MapCodec because JsonOps otherwise treats it as missing.
- Existing persistence carrier: `Preset.buildPatchedRegistries` registers `Preset.KEY`; `Datapacks.makePreset` exports the registry into the preset datapack; `PresetConfigScreen` stages the pack atomically before selecting it; `MixinRandomState.initialize` reads the preset registry. No second native settings sidecar is introduced.
- `LatitudeClimate` evaluates the cubic baseline and the specified piecewise smoothstep moisture curve, range-maps the targets, and blends independently. Existing regional graphs and seed draws remain unchanged. Existing temperature/moisture shaping runs after the blend.
- Freeze existing altitude/coastal/elevation strengths into `ClimateModule` fields; copy the preset when creating a generator context. No global preset-manager redesign. Legacy comparison uses the intended isolated owner preset.
- Retain old float API overloads; add precise geographic-Z paths from integer WorldLookup/tile inputs, through Heightmap/Climate, including edge displacement. Native-active preview tiles and biome lookups retain precise origins; disabled/both-zero previews retain legacy float coordinate arithmetic.
- Existing preview preset-copy/fingerprint/invalidation machinery is reused. Add five controls and translations. Invalid geographic inputs remain visible and block page navigation/apply. Correct the pre-existing coastal slider initial value separately.
- Runtime scope comes from the worldgen epoch: latitude is disabled on the copied context preset outside the Overworld. Known mod conflicts are checked before context construction; the saved TerraLatitude marker is checked before FTF Overworld epoch initialization.
- Compatibility evidence: TerraLatitude revision `04a30c116f239a9b50f7f1df318a6f63091e7422`, `WorldProfiles.java`, `ClimateProfile.java` and `src/main/resources/META-INF/neoforge.mods.toml`. Marker is world-root `terralatitude-climate.json`, schema integer 1 and boolean `enabled`; IDs are `terralatitude`, `naturaltemperature`, and earlier prototype `fnf_altitude`. The native guard recognizes ownership only; it does not import the external algorithm/profile.
- Add one operator-only bounded transect export. It observes actual sampler output independently of stage calculations and includes stored biomes only for already-loaded chunks. No diagnostics dashboard or arbitrary curve editor.

## Draft model contract

Use `ftf:latitude_v1_draft` rather than prematurely claiming the proposed stable `ftf:latitude_v1`. Schema is 1. The cubic/piecewise-smoothstep baseline and original draft defaults are implemented; NL-13's raised-cosine comparison on actual biome evidence remains pending. No conclusion about the best final gameplay distribution is claimed.

Geographic fraction and curve evaluation use double arithmetic, followed by a float target. Range shaping follows the existing float power/map/add/clamp operations for a unit-range target. Blend order is `(1-w)*regional + w*target` in float, with explicit 0/1 endpoints. Existing shaping and signed-cell conversion retain their float order. This documents the current draft, not a promise of stability before the final model gate.

## Validation performed

Environment: Java 21, Minecraft 1.21.1; Fabric loader 0.19.3 / API 0.116.17+1.21.1; NeoForge 21.1.219. Dependencies resolved from existing Gradle caches with `--offline`.

Command: `./gradlew :common:latitudeTest :fabric:build :neoforge:build --offline --console=plain`.

- **Passed:** standalone Java regression suite, 212,612 checks at this revision. Frozen baseline implementation is retained under test sources with provenance, not shipped in mod jars.
- **Passed:** missing/default/disabled/both-zero behavior and independent zero-axis cell float bits; seed draw identity; raw API identity; three settings fixtures, five seeds and the 3×3 strength grid.
- **Passed:** JSON/NBT round trips, copy independence, missing/null/partial objects, unsupported models, fractional integer fields, boolean/numeric type mismatches, invalid bounds and nonfinite strengths.
- **Passed:** curve bounds/temperature monotonicity, moisture knots/continuity, range mapping against existing noise operations, original coordinate 29,999,999 near an equator at 30,000,000, independent region-displacement behavior.
- **Passed:** concurrent module sampling after global/editor mutation, known-mod guard predicates, enabled/missing/disabled/unknown external markers, diagnostic zero/duplicate/order/cap/overflow cases and supplied-input bypass/double-shaping detection.
- **Passed:** Fabric and NeoForge full builds, remapping and access-widener validation. Fabric default-preset datagen ran under the actual loader. Existing deprecation warnings remain unrelated.
- **Passed:** source/diff review confirms no changed regional moisture graph, seed draw, coastal boost formula or elevation-drying formula. `git diff --check` is clean.

The suite uses a test-only `RegistryUtil` binding to exercise common code outside a loader. It does not test mixin execution or claim game/runtime parity. Assertion count includes repeated numeric cases; it is not a count of distinct behavioral tests.

## Remaining release gates

No SPEC acceptance checkbox is marked complete solely by compilation or these offline tests.

| Requirements | Draft implementation / evidence | Remaining evidence |
| --- | --- | --- |
| NL-01–05 | Profile, native curves, paired composition, zero-axis and baseline module regressions | Quantized actual sampler and stored biome-array baseline fixtures; full world coordinate/edge/cache paths |
| NL-06 | Immutable latitude/shape values and context copy; concurrent module tests | Interleaved actual preview/world contexts, cache eviction and cold/warm runtime paths |
| NL-07 | Reuse existing preset datapack codec/export/load authority; JSON/NBT tests | Actual create/export/reopen/server-transfer and failed-write fixture |
| NL-08 | Controls, validation, translations, fingerprint participation and precise active preview origin | Client visual inspection, rapid edit/cancel/reopen, actual preview/runtime comparison |
| NL-09–10 | Known-mod checks, external marker guard, epoch-based dimension scope | Both-loader dependency matrix; non-owned dimensions and existing external-save fixtures |
| NL-11 | Bounded operator export and independent sampler/stored-biome observations wired | Execute command in game; validate runtime export and deliberate sampler bypass/double composition |
| NL-12 | Constant-work scalar layer, unchanged terrain query sites and no per-sample I/O | Five paired generation/preview runs per loader, allocations and variance; no performance pass claimed |
| NL-13 | Explicit draft model/defaults, both loader builds | Matched cubic/cosine biome maps, joint T/H/land masks, travel-distance/default decision, stable version/golden artifacts, client/server matrix |

Use all numeric boundary combinations in inexpensive unit/composition checks. Use a documented representative subset for generated-world scenarios across both loaders, including the specified seeds/presets, both hemispheres, caps and independent strengths. Record the exact matrix and artifacts before checking release gates; do not substitute synthetic distributions for generated biomes.

## Draft PR routing

Implementation is authored on `1_21_1-unstable-custom`. Publish an additive feature head from these commits and target the fork's custom branch, preserving all prior custom changes. Do not publish to upstream or move the custom remote base forward to the feature head before PR review, which would erase the review diff. Unrelated `.planning/issues/` work is excluded.
