# Native latitude climate — custom branch specification

Date: 2026-10-22
Status: draft feature implemented; see [implementation evidence](IMPLEMENTATION.md). Runtime acceptance and stable-model freeze remain pending.
Target: `MuiGoku123432/FreeTerraForged`, `1_21_1-unstable-custom`.
Inspected baseline: `43244515ebf3d3aa9867aba97d8ec360faeabdae`.

This is the canonical specification for the native approach. It takes precedence over earlier research proposals and branch suggestions. [Architecture research](../../research/native-latitude-climate/RESEARCH.md), [algorithm study](../../research/native-latitude-climate/ALGORITHMS.md), and [experimental evidence](../../research/native-latitude-climate/EXPERIMENTS.md) explain the rationale. Research success does not establish implementation success.

User refinement (2026-10-22): latitude should return to warm climate beyond each polar band and repeat indefinitely. New presets use the repeating draft model; saved v1 profiles retain polar caps. The explicit **Repeat latitude bands** toggle selects the behavior for a new-world preset.

User refinement (2026-10-24): retain the original FTF temperature shape and warped boundaries, adding explicit equator/pole configuration. New v3 profiles implement this; saved v1/v2 profiles retain their existing output. See [original-band implementation](ORIGINAL-BANDS.md). This supersedes the earlier prohibition on reusing temperature warps; no new moisture subsystem or cooling formula is included.

## Goal

Players creating an FTF world can enable native latitude climate, place an equator, choose the distance to polar climate, and independently blend latitude temperature and moisture targets with FTF's existing regional climate. The preview and generated world use the same owned surface-climate profile. The feature requires neither Natural Temperature nor TerraLatitude, is disabled by default, and preserves existing worlds' generator choice.

The user selected **temperature and independently controlled latitude moisture**, then clarified that the goal is to make latitude native, not rewrite existing moisture. The feature therefore adds optional latitude targets to the existing pipeline. Preserve the regional moisture generator, its controls and seed sequence, coastal boost and elevation drying. No replacement moisture subsystem, new physical humidity model, ocean transport or directional rain-shadow simulation is included.

## Background and current constraints

FTF already generates warped Z-oriented periodic temperature in `LegacyTemperature`. `ClimateModule` writes temperature and moisture to cells used by the density router and preview. The missing capability is an explicit, saved, opt-in equator/pole model integrated with those shared values.

The custom branch contains the raw regional climate helpers proposed in PR #301 and other custom integrations. Those are part of this baseline and must remain intact. Native implementation is not conditional on upstream merging either #301 or biome-replacement PR #321.

Altitude and moisture shaping currently consult mutable `PresetManager.PM` settings despite climate construction receiving a preset. New active contexts require owner snapshots. Fixing that ownership must be isolated and validated; the spec does not promise to preserve accidental cross-context leakage. Legacy equivalence is measured against an isolated baseline context using that context's intended preset.

## Requirements

### NL-01 — Explicit activation and exact legacy behavior

- **Current:** There is no native latitude toggle or profile.
- **Target:** Missing latitude settings and `enabled=false` use the existing climate path. Each axis at zero latitude strength preserves that axis's legacy output even if the other axis is enabled. Both strengths zero preserve the complete legacy climate path. Installation of another mod never changes the selected model automatically.
- **Acceptance:** AC-01 compares cell float bits, quantized climate coordinates and generated biome arrays against the baseline for missing, disabled and both-zero cases. Temperature-only and moisture-only cases preserve the untouched axis exactly. The existing seed sequence and unrelated terrain remain unchanged.

### NL-02 — Validated, versioned profile

- **Current:** `ClimateSettings` has a codec and copy operation but no geographic profile.
- **Target:** The profile has schema/model identity, enabled state, equator Z, pole distance and separate temperature/moisture strengths, following the data contract below. Invalid values or unsupported versions produce a useful error before preview/world generation; they do not silently select legacy/default values.
- **Acceptance:** AC-02 covers valid boundaries, malformed/partial objects, nonfinite values, unknown model/schema, round trips and deep-copy independence. An absent object remains backward compatible.

### NL-03 — Geographic temperature behavior

- **Current:** Temperature scale does not directly specify an equator and pole distance.
- **Target:** The native target uses original geographic Z in blocks. Its baseline is symmetric about the equator, warmest there, monotone toward both poles, and returns to an equator at twice the pole distance, repeating in either direction before regional blending (saved nonrepeating profiles keep polar caps) and terrain shaping. For v1/v2 this remains an unwarped smooth target. For v3 the baseline uses the original signed sine-power curve and its two existing Perlin warp stages. Equator/poles are nominal locations; final warped targets need not be symmetric or monotone at a fixed X. No additional warp stages are introduced.
- **Acceptance:** AC-03 checks endpoints, both hemispheres, shifted equators, positions around repeating equators/poles and legacy caps, negative coordinates, block/quart conversion and large-coordinate fixtures. Translation of equator and position by the same amount preserves the geographic target within the specified arithmetic tolerance.

### NL-04 — One defined composition pipeline

- **Current:** Regional temperature and moisture are range-shaped and then adjusted by existing terrain operations; cells store signed values.
- **Target:** Blend each range-shaped raw regional axis with its independently range-shaped latitude target in 0–1 units using that axis's strength. Then run the existing temperature cooling or existing moisture shaping exactly once, respectively. Preserve raw API semantics and the four other climate inputs. Repeated edge-region evaluations recompute from raw input rather than reshaping prior output.
- **Acceptance:** AC-04 verifies both axes against independent supplied-input fixtures, confirms one existing terrain-shaping pass per axis, preserves ocean/ground behavior, and checks `sampleRaw` remains pre-latitude/pre-terrain. No extra terrain query is introduced.

### NL-05 — Add latitude moisture without replacing existing moisture

- **Current:** FTF shapes regional moisture using its existing elevation-dependent controls; biome providers consume the combined climate target.
- **Target:** Add a bounded latitude moisture target with its own strength. Reuse the existing regional moisture noise, range/bias controls, coastal boost and elevation-drying calculation without replacing or retuning them. Moisture strength zero preserves existing moisture exactly. Temperature strength never doubles as moisture strength. Latitude does not directly change continentalness, erosion, depth, weirdness, terrain height or terrain identities. Existing cave/provider/replacement rules retain their roles.
- **Acceptance:** AC-05 verifies independent axis controls, zero-moisture-strength identity, retained regional noise/seed behavior and single application of unchanged moisture-shaping formulas. Compare the four unaffected climate inputs at equivalent stages and check downstream policies. Changed T/H may legitimately change biomes, surfaces, features, structures and cave-biome choices.

### NL-06 — Immutable owner settings and deterministic sampling

- **Current:** Some shaping reads global mutable settings; preview and runtime have distinct owners and caches.
- **Target:** Each generator/preview context captures its own complete immutable climate inputs. Editing another preset or generating another world cannot change published results. Results depend on saved seed/profile/position, not query order, thread interleaving, cache warmth or exploration order.
- **Acceptance:** AC-06 interleaves and concurrently samples two distinct contexts, mutates editor copies, repeats cold/warm cache queries, and compares results. Existing cells are not retroactively modified. Owner changes invalidate/rebuild affected state.

### NL-07 — Persistent world authority

- **Current:** Presets can be encoded and exported, but no latitude fields exist in the world path.
- **Target:** The complete selected profile is saved through FTF's world preset path, loaded before climate work, and included in preset copy/fingerprint identity. Saved explicit values override new-world defaults. Invalid or unwritable required state prevents creation/generation rather than allowing a partially initialized world.
- **Acceptance:** AC-07 exports/imports a preset, creates and reopens a world, transfers the save to a dedicated server, and changes unrelated global defaults. Identical saved inputs produce identical sampled output. A failed persistence operation does not start chunk generation under fallback settings.

### NL-08 — Usable controls and accurate preview

- **Current:** The climate editor and preview expose native regional controls and cell values.
- **Target:** Add the controls described below. Every effective edit creates a new preview identity; stale jobs cannot replace a newer result. Preview maps, detail values and surface biome selection use the profile that will be saved. Invalid settings stay visible with a useful message and cannot be applied.
- **Acceptance:** AC-08 changes each control, switches seed/preset, closes/reopens the editor, and compares matching surface preview/runtime samples and eligible biome results. Cancellation and rapid repeated edits never publish results for the wrong profile.

### NL-09 — Scope and external-engine ownership

- **Current:** NT/TerraLatitude may independently modify samplers; not every sampler belongs to FTF.
- **Target:** Native effects apply to FTF-owned Overworld generation and its matching preview. Non-FTF generators and other dimensions retain their existing behavior. Native-enabled profiles reject known NT/TerraLatitude combinations that cannot establish exclusive transformation ownership before climate generation. No global mod disabling or mixin-priority race is acceptable.
- **Acceptance:** AC-09 exercises native off/on with dependency combinations, vanilla/non-FTF worlds and unrelated dimensions. Supported combinations transform once; unsupported active combinations fail with a remedy. Disabled native mode adds no blanket incompatibility restriction. A zero strength does not waive conflict validation for an explicitly enabled profile.

### NL-10 — Existing-world continuity

- **Current:** Worlds may have legacy FTF or saved TerraLatitude profiles with different mathematics.
- **Target:** No automatic conversion, retrogen, or profile reinterpretation. The UI configures new worlds/preset copies; it does not live-edit an active world's climate. Existing native worlds retain their model/version. Existing TerraLatitude worlds retain their dependencies/model; enabling native alongside a detected enabled TerraLatitude profile requires an explicit migration feature outside this release and is rejected here.
- **Acceptance:** AC-10 reopens legacy, native and TerraLatitude fixtures without silently creating/replacing the selected profile. Missing dependencies for an external profile do not cause a switch to native. Already stored chunk biome data is not rewritten. Documentation explains that manual generation-setting edits can create borders in future chunks.

### NL-11 — Independently useful diagnostics

- **Current:** Synthetic research probes do not observe the running game's actual sampler.
- **Target:** Provide a development/operator diagnostic path for point samples and reproducible north–south/east–west transects. Include build/model/profile identity, seed, dimension, block/quart coordinates, surface height, regional input, latitude target, blend, final cell climate and independently queried actual sampled climate. Document expected policy differences.
- **Acceptance:** AC-11 compares independent observations, exports a stable machine-readable record, and detects a deliberately bypassed/doubled composition in a negative fixture. Zero-point requests return an explicit empty result; duplicate coordinates are retained and records follow request order. No unbounded generation is hidden behind a diagnostic query.

### NL-12 — Bounded work and measurable runtime cost

- **Current:** No runtime cost of native latitude has been measured.
- **Target:** Geographic evaluation requires constant work, no neighboring terrain lookup, no disk/network access and no per-sample mutable shared state. Preserve the existing terrain-query count. Meet the benchmark budget below on each supported loader before declaring release readiness.
- **Acceptance:** AC-12 instruments query/allocation behavior and records paired generation/preview benchmarks with baseline variance. A noisy or incomplete measurement is inconclusive, not a pass.

### NL-13 — Model selection and release evidence

- **Current:** Research compares scalar curves and synthetic fields; no candidate has been validated against FTF biomes.
- **Target:** Compare the restored original-band v3 model with saved smooth v1/v2 temperature using identical baseline presets, seeds, coordinates and biome catalogs. Validate the paired latitude moisture curve independently and in combination with the unchanged regional/terrain moisture pipeline. Freeze one versioned model, both curve definitions, arithmetic contract and explicit defaults with a decision record and golden fixtures before any build is presented as generation-stable. Validate Fabric and NeoForge 1.21.1 in client and dedicated-server configurations.
- **Acceptance:** AC-13 includes comparative maps, temperature/biome coverage and diagnostics; AC-14 includes successful builds and runtime matrix evidence. An algorithm/default change after release uses explicit migration/version rules instead of silently changing saved output.

## Data and user-facing contract

The logical profile is an optional `latitude` object under the existing climate settings. Exact Java types/file placement are implementation planning decisions; the meanings and validation below are required.

| Field | Contract | Initial draft value |
| --- | --- | --- |
| `enabled` | Boolean; absent entire profile means disabled | `false` |
| `schemaVersion` | Integer; initially 1; independent from model identity | `1` |
| `model` | Exact supported identifier; saved v1/v2 retain their output | `ftf:latitude_v3_original_repeating_draft`; stable freeze still pending |
| `equatorZ` | Integer block Z, inclusive −30,000,000 to +30,000,000 | `0` |
| `poleDistance` | Integer blocks, inclusive 1 to 30,000,000 | `50000` |
| `temperatureStrength` | Finite number in [0,1] | `1.0` |
| `moistureStrength` | Independently stored finite number in [0,1]; zero retains existing moisture exactly | `0.5` |

The initial distance/strengths are **draft tuning values**, not a claim of physically accurate or ideal gameplay defaults. NL-13 must explicitly retain or revise them based on evidence before freezing the model. They are always serialized into a new world's profile; later default changes affect only new profiles. Do not infer missing enabled-profile fields from mutable installation defaults.

For backward compatibility, an absent `latitude` object decodes to legacy behavior. A present object must contain a complete supported profile. Null, malformed fields and unsupported model/schema values are errors even if that object's enabled flag is false. Version identifiers use exact resource-ID comparison; locale/case folding or fuzzy matching is not supported. No user-authored arbitrary model name or curve is accepted in v1.

Controls: **Native latitude climate** toggle; **Original FTF temperature bands** toggle (v3 on, saved smooth models off); **Repeat latitude bands** toggle (on for fresh profiles, retained from saved model when editing); **Equator Z** integer input; **Distance to polar climate** integer input; separate **Temperature band influence** and **Moisture latitude strength** controls. Show block units and validate before preview/export/create. Disabled fields retain their edited values when toggled off and back on. Explain the north–south temperature gradient, broad wet/dry latitude regions and the retained existing regional/terrain effects. Label zero moisture strength “Existing moisture.” Use biome temperature/moisture terminology, not Celsius, measured rainfall or live weather.

New-world creation and preset export must serialize the same effective values shown in the accepted preview. An arbitrary curve editor, raw model-ID picker, seasonal control and replacement moisture editor are excluded. Existing moisture settings remain available with their current meanings.

## Mathematical behavior and version freeze

For v3 temperature, convert X and equator-relative Z to radians with `pi / poleDistance`, offset the sine phase by `pi/2`, and apply the original detail-then-broad Perlin domain warps (normalized scale/strength 1 then 4, octaves 1 then 2). Reuse the original temperature seeds without additional root seed draws. Evaluate the original signed sine-power curve with the existing Falloff, then the existing range/bias and terrain pipeline. In capped v3, clamp the warped equator-relative phase to [-pi, pi] before evaluation. In repeating v3, reduce the final angle in double precision before the legacy sine-table lookup. Temperature Scale controls only the unconfigured contribution; pole distance controls the configured contribution. Biome and edge displacements remain part of the original-style temperature boundaries. Full warp maps do not repeat identically each cycle.

The following folded fraction still governs moisture in every model and smooth temperature in v1/v2. v3 nominal unwarped temperature follows the original curve; its actual diagnostic target additionally includes warping.

At original geographic Z `z`, let `d=abs(z-equatorZ)` and `D=poleDistance`. For the repeating draft model, compute the block-space phase `p=d % (2D)`, then `u=min(p,2D-p)/D`. For saved nonrepeating v1 profiles, retain `u=min(1,d/D)`. The repeating fraction reaches equators at every even multiple of D and poles at every odd multiple, symmetrically in both directions. Block-space reduction occurs before division to preserve short-period precision near world limits. Use sufficiently wide arithmetic for subtraction/absolute value and normalized geography. Evaluate the candidate `L(u)`:

- Baseline candidate: cubic `1-3u²+2u³`.
- Comparison candidate: raised cosine `(1+cos(pi*u))/2`.

Both have `L(0)=1`, `L(1)=0`, bounded output and zero endpoint slope. Symmetry/monotonicity tests apply to this geographic target, not to temperature after regional noise, range bias or terrain correction. Constant-range presets may deliberately remove latitude variation.

Let `R` be an axis's raw regional value after its existing range/bias operations. Transform that axis's latitude target using its existing effective min/max, distribution bias and offset bias **once**, into `Lr`. For nonzero axis strength w, blend `(1-w)*R+w*Lr` using a frozen evaluation order; for zero strength retain the original axis operations without additional signed/unsigned round trips. Temperature then uses existing elevation cooling/ground gating once. Moisture then uses existing `modifyMoisture` semantics once, including coastal boost and elevation drying. The existing regional scale, falloff and seed offsets continue to control R; they do not redefine geographic pole distance.

This insertion is the only new moisture transformation: **existing regional moisture → optional latitude blend → existing moisture shaping**. Do not blend into already terrain-shaped moisture and then apply the existing shaping again. No changes to the regional moisture source, coastal/elevation formulas, existing defaults or parameter meanings are authorized by this feature; only minimal owner-safe wiring/helpers needed to add the latitude stage are in scope.

The moisture target shares the same unwarped geographic u but has its own curve and strength. The proposed baseline uses bounded piecewise cubic smoothstep interpolation between these model-owned points:

| Equator-to-pole fraction u | Moisture target in 0–1 units |
| ---: | ---: |
| 0.00 | 0.85 |
| 0.20 | 0.65 |
| 0.35 | 0.20 |
| 0.65 | 0.70 |
| 0.85 | 0.40 |
| 1.00 | 0.15 |

These are draft latitude-layer values from the research fixture, not new regional-moisture defaults or physical rainfall estimates. Within an interval, interpolate adjacent target values using `s(t)=3t²-2t³`; this gives bounded segments with zero knot slopes and no overshoot. A slope-limited shape-preserving Hermite alternative may be evaluated at NL-13, but cannot silently replace a frozen model. The wet equatorial, dry subtropical, wetter midlatitude and drier polar pattern is symmetric in geography; existing noise and terrain can break symmetry in final moisture. For repeating profiles, moisture retraces its wet/dry bands after the pole and returns to its equatorial target at 2D; for nonrepeating v1 profiles retain the polar target beyond D. Then perform the usual blend and shaping. Knot editing is not part of the v1 UI.

The final model decision must fix operation order, intermediate precision, transcendental implementation if any, coordinate conversion and quantization behavior. It must preserve current legacy arithmetic for legacy mode. The existing public raw API continues to return raw regional values. Diagnostics distinguish raw geographic L from range-shaped Lr and final climate.

v1 consumes no new regional noise seeds and adds no geographic warp. Existing regional coordinate warps remain. The v1/v2 temperature and all moisture targets use original geography even when FTF retries climate at an edge-displaced regional coordinate. v3 temperature deliberately retains edge and biome displacement while preserving the double-precision geographic phase. Trace original coordinate precision into the evaluator: converting an already rounded float to double does not recover block precision. AC-03 must exercise the supported coordinate path, not only a double-only test helper.

## World lifecycle and compatibility matrix

| Situation | Required outcome |
| --- | --- |
| Old FTF preset has no latitude object | Legacy generation; opening alone does not activate latitude |
| New preset has disabled profile | Legacy output; editor values preserved for later new-world use |
| Enabled native profile, NT/TL absent | Native behavior on owned Overworld/preview |
| Enabled native profile, external climate transformer present | Reject before climate work unless exclusive ownership has been independently proven for that exact supported combination |
| Native disabled, external transformer installed | Preserve existing mod integration behavior; no new blanket rejection |
| Saved enabled TerraLatitude profile exists, addon absent | Detect the relevant saved-profile marker and report the missing/unsupported external setup; never silently adopt native |
| Saved native model is unknown or corrupted | Useful load failure before generation; no fallback |
| Vanilla/non-FTF world or unrelated dimension | No latitude transform and no feature-specific restriction from an inactive profile |
| Editor changes after world context creation | Affect only new preview contexts; the world keeps its immutable saved inputs |

Recognizing an external profile marker is not a promise to interpret or migrate its full schema. If safe profile detection requires an adapter-specific rule, make that rule explicit and test it. Unknown third-party climate transformers cannot all be detected automatically; document the exact tested combinations and avoid universal compatibility claims.

## Acceptance criteria

All checkboxes below remain unchecked until implementation evidence exists. Test fixtures must be checked against actual behavior, not just a duplicate implementation of the same formula.

- [ ] **AC-01 — Legacy:** Missing, disabled and both-zero profiles match isolated baseline float bits, quantized targets and stored biome arrays on fixed presets/seeds, including water, ground, mountains and biome edges. Temperature-only and moisture-only configurations preserve the zero-strength axis exactly. Terrain/regional seed consumption is unchanged.
- [ ] **AC-02 — Profile:** Bounds and their immediate invalid neighbors, NaN/infinity where the parser can represent them, null/partial objects, unknown versions, serialization and independent copies behave exactly as specified. UI and data loading enforce the same rules.
- [ ] **AC-03 — Geography:** Endpoint, reflection, translation, repeating period and seam continuity, saved-v1 cap, negative-coordinate, block/quart-boundary and near-limit tests pass through the actual coordinate path. Temperature is monotone to each pole; moisture follows the specified wet/dry intervals, stays between adjacent knot values and is continuous at every knot. Geography fixture tolerance is at most 1e-6 in 0–1 target units; persisted outputs follow frozen golden fixtures, not this loose tolerance.
- [ ] **AC-04 — Composition:** Each axis's range settings apply once per input and its existing terrain shaping applies once after blending. Surface height rather than player Y drives cooling; below/at-ground behavior is preserved; edge resampling does not compound shaping. Raw APIs retain their exact documented meaning.
- [ ] **AC-05 — Moisture preservation and independence:** Moisture strength 0 reproduces existing moisture bit-for-bit at every temperature strength. Temperature strength 0 similarly preserves temperature when moisture changes. Existing regional moisture/seed/range/scale/falloff controls and coastal/elevation formulas retain their meanings and operations; no second moisture correction is introduced. The four other climate inputs and terrain height/identity match the owner baseline at corresponding stages; existing cave/provider/replacement order is retained.
- [ ] **AC-06 — Ownership:** Interleaved/concurrent contexts with differing seeds and settings match separate serial runs; editor mutation, cache eviction and query order do not change a world's output. Repeated operations do not apply latitude twice.
- [ ] **AC-07 — Persistence:** World creation, restart, preset export/import and dedicated-server transfer retain identical explicit profile and climate fixtures. Unsupported/corrupt state and failed required writes do not start fallback generation.
- [ ] **AC-08 — Preview:** Every profile field affects cache identity; rapid edit/cancel/reopen tests never publish stale results. Matching preview/runtime surface cell values and quantized T/H agree exactly under equivalent sampling/policies. Biome comparisons use identical catalogs, provider state and applicable downstream rules; documented preview-policy differences are tested separately.
- [ ] **AC-09 — Compatibility:** The lifecycle matrix passes for both loaders and their supported external-mod combinations. Unsupported active combinations fail before new terrain/biome climate work; native-off and non-owned paths retain baseline behavior.
- [ ] **AC-10 — Existing worlds:** Legacy/native/external-profile fixtures retain their selected engine. No automatic conversion, stored-biome rewrite or live climate-setting change occurs. External dependencies disappearing never select a new native model.
- [ ] **AC-11 — Diagnostics:** Point/transect outputs include required provenance and independently sampled results; intentional bypass/double-application fixtures produce detectable differences. Empty and duplicate requests, deterministic order, bounded sample count and operator access are covered.
- [ ] **AC-12 — Cost:** No new terrain query, per-sample I/O or shared mutable accumulator occurs. Paired benchmarks meet the budget and evidence protocol below; noisy/incomplete runs are marked inconclusive.
- [ ] **AC-13 — Model freeze:** A decision record compares both temperature candidates, validates the added moisture curve against the unchanged moisture pipeline, and fixes both curves, arithmetic, defaults, profile version and golden fixtures. Coverage artifacts include joint T/H distributions, sampling window, land mask, seed/preset/catalog and actual biome outcomes. Every candidate/provisional build is labeled unstable until this gate passes.
- [ ] **AC-14 — Runtime matrix:** Fabric and NeoForge builds pass, and new/reopened worlds plus preview and dedicated-server scenarios are exercised. Record failures honestly; a build or mathematical probe alone does not satisfy runtime acceptance.

### Runtime evidence protocol

Use at least seeds `0`, `1`, `-1`, `2147483648` and `9223372036854775807`, with the default preset plus altered temperature/moisture ranges and a high-altitude preset. Include equator offsets 0 and a nonzero negative value; the Cartesian product of temperature/moisture strengths {0, 0.5, 1}; moisture knots and points immediately to either side; multiple repeating equators/poles and legacy caps; quart boundaries; and original-coordinate extremes. Synthetic range-collapse cases (`min=max`) and zero temperature cooling, zero coastal boost and zero elevation drying distinguish independent controls from normal examples.

For candidate comparison, record north–south/east–west transects and a two-dimensional sample window spanning both poles. Report temperature histograms, occupied joint T/H space, actual biome counts, connected-region/travel-distance observations and land-only versus all-cell coverage. No percentage claim may omit the window, sample spacing, mask or biome catalog. The comparison informs the decision; it does not silently add a requirement to reproduce real Earth biome percentages.

For preview equality use identical original coordinates, surface-height convention and provider snapshot. For runtime terrain, also inspect stored generated biome data so a diagnostic formula cannot certify itself.

Diagnostic export uses UTF-8 machine-readable records with exact resource identifiers and numeric coordinate fields. Preserve input order and duplicates; an empty transect returns an explicit empty record set. Reject requests over 4,096 points before sampling and expose the effective sample count. This bounds diagnostic work without requiring a particular command name or export UI.

The candidate comparison always contains both named curves evaluated on the same nonempty dataset, with stable ordering by preset, seed, coordinate and candidate. If both satisfy the criteria and the evidence does not justify extra complexity, retain the cubic baseline and record that reason. A missing candidate, empty evidence set or unspecified tie decision does not satisfy AC-13.

### Initial benchmark budget

Proposed engineering budget for this draft: at least five paired runs per loader/build configuration after warmup, same machine/JVM/preset/seed/region and controlled background load. Native-on median chunk-generation throughput must be at least 95% of native-off, and median end-to-end preview time no more than 110% of native-off. Record raw results, sampling latency and allocations. Repeat/investigate if baseline variation exceeds 5%; do not call a noisy result a pass. These are project acceptance targets, not predictions or universal climate-algorithm limits. They may be revised explicitly before implementation planning; do not change them after measuring merely to claim success.

## Boundaries

**In scope:**

- A native opt-in latitude layer for temperature and moisture, with independent strengths and a saved profile on the custom branch.
- A bounded latitude moisture target inserted into the existing moisture pipeline; preservation of existing regional moisture and terrain-shaping behavior.
- Existing preset editor/export, shared cell climate, runtime/preview ownership and persistence integration.
- Focused legacy, arithmetic, concurrency, compatibility and runtime tests.
- Candidate comparison, diagnostics, model freeze and a reviewable eventual draft PR.

**Out of scope:**

- Replacing, retuning or redesigning the existing moisture subsystem — the user explicitly limited the work to native latitude functionality.
- New ocean-distance or directional rain-shadow algorithms — require nonlocal terrain ownership and seam/performance evidence.
- Seasonal/weather/player-temperature simulation or physical Celsius/PET conversion — different modeled quantities.
- NT calculator dependency, source copying or full NT mode parity — native independence is the selected approach.
- Exact TerraLatitude migration, retrogen and active-world climate editing — different persistence/continuity scope.
- Radial/rotated latitude, user-authored curves, additional octave noise or additional boundary warp stages remain out of scope. Repetition and reuse of original temperature warps are now included; model-owned latitude moisture knots remain in scope.
- River/erosion redesign, global preset-manager refactoring, unrelated custom-branch cleanup — no dependency established.
- Immediate upstream PR submission or merge — this task authors and stores the specification.

## Product prohibitions

These are explicit intent constraints; ordinary numerical/cache correctness is covered by requirements and edge checks.

| Must-NOT | Requirement | Verification |
| --- | --- | --- |
| Expand native latitude into a replacement or retuning of the existing regional moisture, coastal-boost or elevation-drying subsystem | NL-04, NL-05 | Focused diff review plus zero-strength and existing-formula regression fixtures |
| Present dimensionless biome temperature as measured Celsius, live weather, or a validated physical climate simulation | NL-08, NL-13 | Wording/UI review; synthetic figures labeled correctly |
| Advertise exact TerraLatitude/NT world continuity or silently convert a save to make native mode appear compatible | NL-09, NL-10 | World fixtures plus documentation review |
| Represent compilation, synthetic math or self-comparison diagnostics as completed in-game verification | NL-11, NL-13 | Evidence review distinguishes supplied-input, actual sampler and stored-biome checks |

These are specified review obligations, not claims that checks are already wired or passed. No fabricated test paths or verification results are attached.

## Edge coverage

[EDGE-COVERAGE.md](EDGE-COVERAGE.md) records the applicable boundary, representation, ordering, repetition and concurrency cases raised by the installed GSD edge-probe engine. Each resolution points to this specification's acceptance obligations. “Specified” means the requirement addresses the case; it does not mean the corresponding implementation test has passed. Authored inputs and engine output are retained beside that audit for reproducibility.

## Decision and readiness record

| Topic | Status | Basis |
| --- | --- | --- |
| Native approach, custom branch | User-directed | “spec this native approach in our custom branch” |
| Saved-world continuity, preview ownership, native independence | Specified | Prior research recommendation and existing code constraints |
| Temperature and independent latitude moisture | User-selected | Answer to the scope question; both axes included |
| Preserve existing moisture subsystem | User-directed | “focus on just making the latitude stuff native”; only the optional latitude blend is added |
| Curves/arithmetic and distance/strength defaults | Explicit implementation gate | NL-13; research cannot settle actual joint biome quality |
| Benchmark budgets and numeric configuration bounds | Proposed engineering constraints | Concrete reviewable limits; not user-selected tuning |
| Standalone specification | Established repository format | No PROJECT/ROADMAP/STATE lifecycle files exist; no numbered phase invented |

Qualitative readiness: goals, user-selected scope, compatibility constraints and test obligations are sufficiently concrete for implementation planning. The NL-13 gate and draft engineering constraints remain visible; do not describe this as runtime-verified. The GSD requirement/edge formats were adapted to this repository's standalone documents; a numbered spec-phase workflow was not run.

## Branch and next deliverable

Keep this specification, its research and subsequent custom implementation on `1_21_1-unstable-custom`. Preserve all existing custom work. An eventual upstream submission can extract the feature into a focused branch later; do not base current work on upstream at the cost of the custom changes.

Next deliverable is an implementation plan that maps NL-01…NL-13 and AC-01…AC-14 to concrete changes/tests, closes the curve/default freeze through comparative evidence, and identifies runtime fixtures and evidence ownership. It must keep latitude moisture additive to the established pipeline and must not treat deferred transport/rain-shadow research as implicit permission to redesign moisture.
