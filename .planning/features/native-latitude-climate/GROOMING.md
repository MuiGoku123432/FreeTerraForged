# Native latitude climate — grooming review

Reviewed 2026-10-22 against custom-branch commit `e76a565b` and the current [SPEC.md](SPEC.md).
Status: source-grounded assessment and proposed implementation breakdown. No production changes, runtime tests, or changes to canonical requirements are made by this review.

## Assessment

**Overall difficulty: 7/10.** The scalar latitude model is about 2/10; a working integrated draft is about 6/10; satisfying the complete persistence, compatibility, determinism and two-loader release contract is about 8/10. These are engineering judgments, not measured effort estimates. Confidence is moderate until the exact persistence and coordinate entry paths are traced.

The feature is feasible without rewriting moisture. `ClimateModule.apply` already has a clear insertion point between regional noise evaluation and `modifyMoisture`. Existing formulas and regional noise graphs can remain intact. The important work is keeping world and preview ownership, original coordinates, saved settings and legacy output consistent.

## Expected implementation changes

| Area | Change or addition | Difficulty / scope note |
| --- | --- | --- |
| Climate profile | Add an immutable latitude settings type and validated codec; integrate it with `ClimateSettings.CODEC` and `copy()` | 4/10; absent versus malformed profiles need distinct handling |
| Latitude evaluator | Add a small pure helper for geographic distance, temperature curve, moisture knots and independent blends | 2/10; no new noise generator, seeds or dependency |
| Climate composition | Insert each blend before its existing terrain shaping in `ClimateModule`; preserve zero-strength arithmetic and `sampleRaw` | 5/10; avoid signed/unsigned round trips and double shaping |
| Context ownership | Capture the three existing shaping strengths from constructor settings instead of consulting `PresetManager.PM` while sampling | 6/10; small code change with meaningful concurrency and compatibility consequences |
| Original geography | Carry original geographic Z with sufficient precision through the actual generation and preview entry paths | 7/10 uncertainty; current `Heightmap`, `Climate` and `ClimateModule` signatures use floats |
| Saved-world lifecycle | Trace and extend the existing preset save/load path; ensure restarts and server transfers use the saved profile | 7/10 uncertainty; adding a codec field alone does not prove world persistence |
| Editor and preview | Add five controls: toggle, equator, pole distance and two strengths, plus translations/validation and regeneration wiring | 5/10; reuse existing preview snapshot/fingerprint machinery |
| Compatibility | Add narrowly scoped checks for supported external-mod combinations and known external saved-profile markers | 6/10; exact loader IDs, marker format and earliest safe validation hook must be identified |
| Verification | Add baseline fixtures, focused Java tests, bounded diagnostics and actual client/server evidence | 8/10; this is a substantial part of the release effort |

Source anchors: `common/src/main/java/etcodehome/freeterraforged/` contains `data/worldgen/preset/settings/ClimateSettings.java`, `world/worldgen/cell/climate/{ClimateModule,Climate,TemperatureShaping}.java`, `world/worldgen/cell/heightmap/Heightmap.java`, and `client/gui/screen/presetconfig/{ClimateSettingsPage,PreviewRequestKeyFactory}.java`.

## Grooming changes recommended before implementation

1. **Separate draft readiness from release readiness.** A draft PR can contain the complete opt-in feature and explicitly pending runtime evidence. Model freeze, both loaders, dedicated-server persistence and measured performance remain release gates. Do not require every release artifact before opening a reviewable draft, and do not call the draft generation-stable.
2. **Make the owner-settings correction an isolated prerequisite commit.** Capture altitude cooling, coastal moisture boost and elevation drying values, keeping formulas and operation order unchanged. Compare legacy output with the correct preset selected. This fixes context leakage; it must not expand into a global preset-manager refactor.
3. **Resolve original-coordinate transport first.** Existing `originalZ` parameters are floats and cannot restore precision already lost upstream. Trace integer/block entry points and preserve a separate precise geographic Z through regional displacement. Keep existing regional float arithmetic unchanged. Do not convert the whole noise/terrain engine to doubles.
4. **Keep the baseline algorithm small.** Start implementation with cubic temperature and the specified piecewise smoothstep moisture curve. Retain the required raised-cosine comparison as an experiment before freeze; do not add a runtime strategy framework or player curve picker. No additional Hermite investigation is necessary unless biome evidence exposes a problem.
5. **Make verification tiers explicit.** Run all numeric boundaries and the full strength combination grid through inexpensive evaluator/composition tests. Use a documented representative subset for expensive generated-world tests, covering both loaders and key lifecycle scenarios. Avoid accidentally requiring every seed × preset × coordinate × strength combination to create full worlds. Any reduced runtime matrix must be recorded in the implementation plan before claiming acceptance.
6. **Keep diagnostics narrow.** Implement one bounded development/operator sampling/export path and reuse existing sampler access where possible. Keep independent actual-sampler and stored-biome checks, but avoid building a general climate dashboard or diagnostic framework.
7. **Specify behavior at full strength in the UI.** At strength 1 the regional contribution to that axis is zero; existing terrain shaping still applies. Temperature scale/falloff continue to affect the regional component, while geographic distance is controlled by pole distance. Explain this to prevent apparently ineffective existing controls from looking broken.
8. **Add explicit gameplay review points to default selection.** Evaluate travel distance to cold/warm zones, equatorial spawn-area variety and dry/wet biome access for the provisional 50,000-block pole distance and strengths 0.75/0.5. These values are not frozen. Moisture 0.5 changes generated moisture substantially even though its underlying system is preserved. Do not change it silently or equate preservation of formulas with unchanged enabled-mode biomes.
9. **Make compatibility evidence concrete.** Record exact supported mod versions/IDs, external profile marker and validation hook. A blanket promise to detect every climate modifier is not implementable. Keep the existing spec's narrow ownership and continuity guarantees; do not add an external-engine integration framework.

## Ordered implementation slices

| Slice | Deliverable and exit condition | Primary spec coverage |
| --- | --- | --- |
| 1. Integration audit and baseline | Locate world save/load authority, geographic entry points, preview invalidation and test runner. Capture representative legacy results before touching arithmetic. Record exact external profile detection contract. | NL-01, 06, 07, 09, 10 |
| 2. Owner settings | Snapshot existing shaping strengths; isolated context, interleaving and legacy fixtures agree. | NL-01, 06 |
| 3. Native model and pipeline | Profile codec/copy, pure evaluator, precise geography and additive composition for both axes; independent zero-strength identity and raw API checks pass. | NL-01–05 |
| 4. Player and world integration | Controls, translations, preview identity, persistence and early compatibility errors; representative new/reopened world and preview agree. | NL-07–10 |
| 5. Model decision and release evidence | Bounded diagnostics, comparative maps, fixed model/defaults, golden outputs, benchmarks and client/server loader matrix. | NL-11–13; AC-11–14 |

Both axes remain part of the feature; these slices are implementation order, not a temperature-only scope reduction. Keep all slices reviewable in one eventual draft PR, with ownership changes distinguishable from latitude behavior. Open the draft when the integrated work is concrete; keep incomplete release gates visible.

## Findings and uncertainties

- **Confirmed ownership issue:** `ClimateModule.modifyTemp` and `modifyMoisture` read the three shaping strengths from `PresetManager.PM`, despite the constructor receiving `ClimateSettings`.
- **Confirmed precision risk:** `Heightmap.applyClimate`, `Climate.apply` and `ClimateModule.apply` take float positions. Extreme coordinates and very short pole distances need tests through the real path. An isolated double-only evaluator test is insufficient.
- **Existing preview support can be reused:** `PreviewRequestKeyFactory.Input` copies the preset and fingerprints its encoded form. New latitude fields should participate through codec/copy support and existing invalidation callbacks; no replacement cache architecture is justified by this review.
- **Separate existing editor defect:** `ClimateSettingsPage` initializes the coastal-moisture slider from `climate.altitudeCoolingStrength`, although its callback writes `climate.coastalMoistureBoost`. Track a focused UI fix before tuning or collecting editor-based moisture evidence. This is not a change to the moisture formula, and it has not been fixed here.
- **Persistence remains a tracing task:** the inspected `TerraForgedChunkGenerator` codec serializes biome sources and noise settings, not an explicit latitude/preset field. Determine the existing preset's actual persistence carrier rather than inventing a second sidecar file by default.
- **Test infrastructure needs inventory:** `common/src/test` does not exist in this checkout. The ordinary repository file scan found no obvious conventional test suite. Existing scripts or excluded tooling may provide a harness; identify them before estimating setup work. Research Python probes cannot replace Java/game validation.

## Scope lock

Existing regional moisture → optional latitude blend → existing coastal/elevation shaping.

No replacement moisture generator, retuned moisture formulas, new terrain transport, weather simulation, extra dependency, general cache rewrite or whole-engine precision conversion. Zero moisture strength must retain existing moisture output exactly within the intended owner context. No source or runtime changes were made during grooming.
