# Draft PR plan: optional native latitude climate

Prepared 2026-10-22 from [RESEARCH.md](RESEARCH.md). This is a proposed implementation plan and draft description, not a record of completed feature work. No PR was opened by the research task.

**Current authority:** [custom-branch SPEC.md](../../features/native-latitude-climate/SPEC.md) now defines the feature contract. The user selected the custom branch for this work; use the specification if this earlier research plan differs.

The follow-up [algorithm study](ALGORITHMS.md) adds a selection gate: compare cubic smoothstep and raised cosine against actual FTF biome coverage before freezing `ftf:latitude_v1`. Use a constrained shape-preserving control curve only if configurable zones are needed. Moisture transport, ocean-distance fields and directional rain shadows remain separate prototypes requiring halo, conservation, terrain-dependency and performance checks. The [synthetic experiments](EXPERIMENTS.md) do not satisfy runtime acceptance criteria.

## Proposed PR title and opening description

**[Draft] Add optional native latitude temperature and moisture to FTF**

> Add an opt-in latitude layer to FTF presets. Players can place an equator, choose the distance to polar climate, and independently blend latitude temperature and moisture with the existing regional climate. Existing regional moisture, coastal boost and elevation drying are retained. The layer feeds FTF cell climate so generation and previews can use the same surface values.
>
> Missing or disabled settings preserve legacy generation. The new model is saved with the world preset and requires no Natural Temperature installation. Existing TerraLatitude worlds retain their current generator; converting those worlds is outside this PR.

Rewrite this description around the implemented behavior before publication. Do not use the proposed preview/compatibility claims as completed acceptance criteria.

## Branch and dependency scope

Author the specification and implement the feature on `1_21_1-unstable-custom`, preserving its existing custom integrations, as directed by the user after research. A later upstream submission can extract the feature into a focused branch without unrelated custom changes. Creating that upstream branch is not a prerequisite for current custom-branch work.

Recheck the source register against the custom branch head before coding. PR #301 was open and unmerged when inspected, although its helpers already exist in the custom checkout and must retain their semantics. Native composition inside FTF does not depend on upstream accepting that API. PR #321's biome replacement work is a separate concern, not a requirement for native latitude.

## First-PR contract

| Area | Deliverable |
| --- | --- |
| Activation | Explicit preset toggle; absent field decodes disabled |
| Model | New versioned `ftf:latitude_v1`; legacy algorithm unchanged |
| Geometry | Z-axis equator and positive pole distance; cold caps beyond poles |
| Temperature | Smooth geographic target blended with regional climate before one elevation correction |
| Humidity | Independent latitude moisture target/strength blended before the unchanged existing moisture shaping; zero strength is exact existing moisture |
| Settings | Serialized, copied, validated, captured per generator owner |
| Preview | Toggle and settings update existing preview; cell and sampled surface values compared |
| Scope | FTF-owned Overworld generation; explicit behavior for other owners/dimensions |
| Compatibility | Native mode detects unsupported double-engine combinations before generation |
| Diagnostics | Reproducible transects identify model, profile, seed, raw/blended/final climate and actual sampled result |
| Platforms | Fabric and NeoForge 1.21.1, client and dedicated server |

Deferred: NT calculator adapter, exact TerraLatitude migration, looping/circular modes, seasons, wind-based rain shadows, ocean-distance transport, rainfall-driven rivers, custom NT modes, and a generic third-party climate plugin framework.

## Implementation sequence

### 1. Capture the legacy baseline

Record the actual custom-branch revision and build environment. Add focused regression coverage for absent/disabled settings, representative seeds and presets, near-water/high-altitude cells, biome edges, and negative/large coordinates. Compare raw float bits and quantized climate where relevant. Preserve seed consumption so enabling the new model cannot unintentionally move unrelated terrain noise.

Separate expected intentional climate changes from unintended changes to continentalness, erosion, depth, weirdness, terrain identity, and height. Final biome-dependent blocks/features are expected to differ when the model is enabled.

### 2. Add settings and owner snapshots

Extend `ClimateSettings.CODEC` and `copy()` with an optional latitude settings object. Validate finite values, strength in [0,1], a strictly positive bounded pole distance, supported schema/model versions, and a sensible equator coordinate range. Choose defaults from experiments rather than treating the probe's 50,000-block example as a shipping decision.

Capture immutable settings in the generator's climate context. Audit the current `PresetManager.PM` reads in temperature/moisture shaping. A small preparatory owner-snapshot change may be appropriate, but it needs its own regression evidence. Do not freeze a mutable object reference and call it an immutable snapshot.

Round-trip through preset export/import, generated world data, shutdown/reopen, and dedicated-server loading. Verify server-owned generation does not read a client's live editor settings. Do not add a redundant world JSON file until the existing persistence path has been tested and shown insufficient.

### 3. Implement the pure model and composition

Keep the geographical calculation small and independent of world access. Use original geographic coordinates; keep existing warps for regional variation. Distinguish internal 0–1 values from signed Minecraft climate coordinates. Apply the temperature range mapping once to each unmapped input, blend, then apply the existing elevation shaping once.

Use an explicit legacy branch when disabled or both strengths are zero. Each zero-strength axis preserves its existing operations exactly even while the other axis changes. Add the latitude moisture blend before the existing moisture-shaping step, without replacing regional moisture noise or changing coastal/elevation formulas. Preserve the custom branch's `sampleRaw` semantics. Do not mutate previously published cells, add per-sample disk access, or query neighboring terrain for latitude.

### 4. Connect preview and user controls

Add the toggle, equator position, pole distance, and independent temperature/moisture strengths to the climate editor using existing widgets and translations. Explain that these are biome climate targets, describe the broad wet/dry latitude pattern, and retain existing moisture controls. Follow the specification's new-world editing/continuity contract. Keep model identifiers in diagnostics rather than making them the main player-facing control.

Verify preset copies and fingerprints include every field. New settings must invalidate preview and generation context caches as appropriate; an in-place setting change must not reuse cells computed under another profile. Compare the temperature map, preview tooltip, preview biome resolver, and actual runtime sampler at matching coordinates/policies.

### 5. Add ownership/coexistence checks

When native mode is enabled, detect known NT/TerraLatitude combinations that would also transform the same sampler. Provide an actionable error before generation unless a tested per-owner opt-out exists. The implementation must not rely solely on loader ordering or a higher Mixin priority.

Test vanilla/non-FTF worlds and dimensions separately. Preserve existing cave policies; verify their result instead of promising identical cave biomes under changed temperature. Verify downstream biome replacement remains downstream and is not accidentally applied twice.

### 6. Gather runtime evidence and publish the draft

Run the validation matrix below and attach small transect/coverage artifacts. A draft PR may expose unfinished work, but its description must identify exactly which rows have passed and which remain unchecked. Record build hashes, settings, seed, loader, mod list, sample coordinates, and actual-versus-expected deltas with each evidence set.

## Validation matrix

| Test | Required observation | Why it matters |
| --- | --- | --- |
| Missing/disabled settings | Legacy output identical at float/quantized boundaries and representative chunks | Existing worlds must not drift |
| Strength zero | Each untouched axis is exact; both-zero preserves all legacy climate | Avoid conversion/rounding changes in an apparent no-op |
| Equator and both poles | Symmetric unwarped geographic baseline, smooth transition, no NaN/overflow | Validate mathematical contract |
| Coordinate edges | Negative Z, offset equator, quart boundaries, large block coordinates | Catch coordinate units and precision errors |
| Regional/edge sampling | Correct original geography through both climate evaluations | Avoid applying latitude to warped regional coordinates |
| Range and altitude | Range settings applied once; cooling once; sea/ground gate retained | Catch doubled shaping and domain confusion |
| Other climate axes | No direct changes to the other four coordinates from the new model | Prevent unrelated biome-selection regressions |
| Save and reload | Same profile/output after restart and preset export/import | World continuity |
| Two contexts | Distinct seeds/profiles sampled interleaved and concurrently without leakage | Reject global mutable settings ownership |
| Preview parity | Cell/map/tooltip and actual surface sampler agree under documented policies | Avoid attractive but incorrect previews |
| Cave and ocean samples | Actual policy behavior recorded at surface, underground and seabed | Surface identity does not prove underground correctness |
| Loader/dependency matrix | Both loaders, NT absent/present, TL absent/present, native off/on | Optional installations must not trigger classloading or double transforms |
| Biome coverage | 2D climate/biome maps and north–south/east–west transects across several seeds | Smooth climate can still produce poor biome distributions |
| Full pack | Representative vanilla, Regions Unexplored/Lithostitched configurations and biome replacement rules | Biome catalogs and restrictions affect the result |
| Generation performance | Same machine/settings, warmup, repeated runs; chunks/sec, sampling latency, allocations, preview time | No speed claims based solely on arithmetic complexity |

The pure model should not add terrain lookups. Confirm that with instrumentation. Establish an agreed regression threshold from baseline variance before running performance acceptance; do not invent a universal percentage. Test generated/stored biome data as well as the live sampler, because a diagnostic formula matching itself proves little.

## NT adapter decision gate for a later PR

Reconsider the dependency option if the NT author offers a stable, versioned API that accepts world-owned settings and lets FTF suppress NT's own sampler transformation for the same owner. Clarify supported integration and distribution permissions. Then prototype one pinned version before promising an open-ended version range.

An optional adapter should have an absent-dependency classloading test, immutable seed/settings ownership, a paired T/H contract, reliable preview participation, explicit engine persistence, and parity fixtures for every claimed supported mode. Existing TerraLatitude profiles require their retained arithmetic and exact dependency behavior; an approximate native conversion must remain a different model.

## What remains before implementation

The user selected native temperature and independently controlled latitude moisture, while explicitly preserving the established moisture subsystem. Product choices still needing evidence include pole distance, both latitude strengths and the latitude target curves. Existing-world continuity does not imply automatic migration of external profiles. The canonical specification defines these boundaries.

The research probe passing is not acceptance of any matrix row that involves FTF, Java, Minecraft, world saves, loaders, or performance. Those remain unchecked until the implementation exists.
