# Rolling Highlands — staged implementation plan

Date: 2026-10-27. Status: ready to start implementation; no task below has been executed.

Delivery authority: [SCOPE.md](SCOPE.md), including the user's choice of a playable experimental option first. Requirements: [SPEC.md](SPEC.md). Algorithm candidates: [ALGORITHMS.md](../../research/rolling-highlands/ALGORITHMS.md). Integration constraints: [INTERNAL-AUDIT.md](../../research/rolling-highlands/INTERNAL-AUDIT.md).

This is a feature-local plan using GSD planning guidance. There is no numbered roadmap phase here; no formal GSD executor/checker run is claimed. Scope and dependency review was performed inline. Work is sequenced around an early end-to-end proof before expanding support.

Paths below are relative to `common/src/main/java/etcodehome/freeterraforged/` unless stated otherwise. New class names are suggestions; responsibilities and tests are the contract. Keep unrelated checkout changes intact.

## Sequence

```mermaid
flowchart LR
    A[T0 Baselines and harness] --> B[T1 Regional ownership and sampler]
    B --> C[T2 Connected drainage and profiles]
    C --> D[T3 End-to-end generated river proof]
    D --> E[T4 Landscape composition and erosion]
    E --> F[T5 Four controls and persistence]
    F --> G[T6 Experimental validation and artifacts]
    G --> H[T7 Explore and tune]
    H --> I[T8 Release coverage]
```

T0–T3 form the internal vertical slice. Do not expand into full settings coverage before its actual terrain, water and outlet checks pass. T4–T6 complete the first playable delivery. Every task should leave compiling code and focused evidence; commit implementation in reviewable units when implementation begins, not as one large final change.

## T0 — Freeze baselines and establish a repeatable harness

**Depends on:** nothing. **Covers:** RH-01, RH-09, RH-11. **Risk:** low; infrastructure is reversible.

**Touch:** feature evidence directory, new `common/src/test/java/...` fixtures/harness, `common/build.gradle` only as needed for test support; inspect existing diagnostic commands before adding another.

- Record source commit, Java/toolchain, loader/mod versions and exact exported presets. Save current-checkout UPLIFT and MULTI_IMPROVED sampled heights/metadata and selected final chunk/water outputs before changing generation. They are regression fixtures, not the missing V3 comparator.
- Choose and record one reference RH preset: continent settings, height/sea level, channel settings, erosion, and the four candidate defaults. Check resolution/headroom feasibility first. Establish narrower first-build control bounds from evidence, not arbitrary UI limits.
- Add a focused Java test setup for pure planner/math code. `common/build.gradle` currently declares no test framework. Either add the minimal test dependency/platform setup or a deterministic assertion harness with a documented Gradle task; do not count an empty `test` task as verification.
- Define outputs for terrain/divide/channel maps, downstream profiles, interval infeasibility, plan bytes/build times and final-block checks. Persist seeds, coordinates, settings, model identity and result hashes with every run.

**Verify/exit:** unchanged legacy fixtures reproduce on the current source; the harness intentionally fails on a corrupt profile; a reference preset manifest exists. Missing V3 details are recorded as pending rather than replaced with guessed defaults.

## T1 — Pure regional sampler and finite domain ownership

**Depends on:** T0. **Covers:** RH-02, RH-07, RH-09. **Risk:** high; domain design determines the rest of the pipeline.

**Touch:** new RH sampler/domain classes near `world/worldgen/cell/continent/`; narrow adapter to `continent/advanced/AdvancedContinentGenerator.java`; precise-coordinate entry points in `heightmap/Heightmap.java` and its runtime/preview callers as necessary.

- Implement regional uplands/basins, ridge proposal and foothill fields in block units with named seed offsets. Keep planning amplitudes fixed so the height/relief controls do not change graph topology. Apply final user amplitudes separately.
- Carry the original continent lattice identity. Implement the derived finite raw-coordinate bounds and coarse-grid allocation cap. Validate `gridSpacing <= uplandSpacing / 8`; include halo and real allocation dimensions in the cap.
- Sample actual coast/ownership boundaries and connected land components. Establish real ocean terminals. Include full channel/floor/bank/fade influence in lookup bounds; a sample's owning continent alone is not sufficient near overlapping influences.
- Keep the sampler free of final terrain, climate and chunk-cache calls. Feed original integer/double coordinates before any legacy float conversion.

**Verify/exit:** bounded allocation, deterministic fields, positive/negative/far-coordinate fixtures and mapped outlet/land-component evidence across five fixed seeds and boundary cases. No rectangle edge is treated as sea. If the coast adapter cannot provide valid domains, resolve shared ports or revise the domain design here; do not paper over it in the carver.

## T2 — Drainage, basin decisions and feasible water profiles

**Depends on:** T1. **Covers:** RH-03–07, RH-09. **Risk:** high; the formula probe does not prove feasible real-world profiles.

**Touch:** new RH plan builder, basin resolver, tree-profile solver, segment index and bounded owner-scoped cache near `world/worldgen/cell/rivermap/`.

- Implement stable priority-flood parent ranks, area accumulation and downstream-closed stream selection. Retain real outlet identity and stable branch/junction IDs. Extract consistent divide/ridge geometry and saddle/pass candidates from the same plan.
- Resolve depressions with bounded breach/revision or explicit level lakes with connected spills. Do not emit the filled routing surface as visible terrain. Limit retries, nodes, refinement samples and in-flight builds; report the failing seed/domain/constraints on exhaustion.
- Contract level lake/junction cores, solve the exact tree interval constraints, then expand profiles. Use arc-length linear profiles initially. Refine against dense actual terrain constraints before publication; reject crossings, incompatible overlapping channels and invalid junction geometry.
- Publish immutable complete plans. Include seed, settings/model and owner identity in lifecycle/key design; cache eviction may change cost, never terrain. Avoid holding chunk-cache locks while planning or waiting for plan builds.

**Verify/exit:** port the independent exhaustive solver fixtures to Java; add conflicting confluences, lake/spill, narrow pass, all-ocean and impossible-cut fixtures. Actual reference-preset plans have nonempty rivers, acyclic downstream paths, real outlets, consistent junction levels, bounded cuts and finite resource use. Height/relief edits preserve the graph; width edits preserve profiles. Passing graph checks alone does not complete the task: validate refined lateral composition feasibility too.

## T3 — Complete the first generated-block river path

**Depends on:** T2. **Covers:** RH-01, RH-04–06, RH-08–10. **Risk:** high; this crosses existing water consumers.

**Touch:** `data/worldgen/preset/settings/{ContinentType,TerrainSettings,Preset}.java`, `heightmap/Heightmap.java`, `cell/Cell.java`, new RH valley composer, `mixin/MixinSurfaceSystem.java`, `world/worldgen/feature/RiverGasketFeature.java`, plus all water/flow consumers identified in the audit. Initial settings may be loaded through a development preset before the editor is finished.

- Add opt-in mode dispatch and minimal versioned settings plumbing without changing legacy construction or random draw order. Establish one explicit valid-water flag and one water-height unit; implement Cell initialization/copy/reset.
- Compose wet bed, banks, dry floor and outer transition using the profile contract. Enforce cut/fill limits; simple `min(original, carved)` is insufficient. Use shared junction geometry and account for overlapping influence regions.
- Update RH surface water, gasket/sealing, wetland and flow consumers to use the same profile. Keep the legacy path intact. Separate terrain-classification/climate metadata from height synthesis.
- Make runtime and preview query the same immutable plan. Establish the RH ceiling/filter ordering now, including short-world headroom, before adding erosion detail.

**Verify/exit:** generate and inspect actual chunks containing a tributary junction, lake/spill where applicable, confined reach, broad valley and ocean outlet. Test chunk/domain seams, beds and integer water levels after surface/features and normal water updates. No downstream water rises or disconnected confluences. Check legacy fixtures. This is the first end-to-end proof; proceed only after it passes.

## T4 — Shape the intended landscape and protect it through erosion

**Depends on:** T3. **Covers:** RH-02, RH-03, RH-06, RH-07, RH-10. **Risk:** medium/high; aesthetic and profile constraints interact.

**Touch:** RH regional/ridge/valley composition; `cell/filter/{TerrainFilters,Erosion,CliffRelaxation,FilterGrid}.java` or RH-specific adapters; `heightmap/{TerrainCeiling,PreviewGrid}.java` where needed.

- Turn divide/saddle proposals into connected, varied ridges and passes. Preserve broad uplands and occasional dry basins. Keep noise detail subordinate to regional and mountain structure.
- Couple lateral valley confinement to terrain/area while retaining the independent reference dry-floor width. Recheck profiles against the final composed terrain; decorative ridge changes must not invalidate reserved channels.
- Use explicit terrainScaleFactor and sample spacing in RH filters. Protect channels/lake levels and keep filter dependency support inside the sampled halo. Apply the mode's ceiling policy coherently once rather than inheriting double compression.
- Add restrained local erosion/gully detail. Compare erosion-off and erosion-on maps/profiles. Keep legacy filter units/order unchanged.

**Verify/exit:** five-seed maps and fixed-route views show recurring relief, connected foothills/ridges, passes, open basins and both valley types. Record provisional SPEC metrics without treating proposed aesthetic percentages as already approved thresholds. Re-run final water/seam checks after filters; preview matches regional structure and channel positions at corresponding coordinates.

## T5 — Finish the playable option and four-control workflow

**Depends on:** T4; minimal codec already landed in T3. **Covers:** RH-01, RH-07, RH-08, RH-10. **Risk:** medium; saved generation identity is costly to change.

**Touch:** settings/copy/codecs from T3; `client/gui/screen/presetconfig/{WorldSettingsPage,TerrainSettingsPage}.java`; `common/src/main/resources/assets/freeterraforged/lang/en_us.json`; preset validation/export paths.

- Expose Rolling Highlands (Experimental) and the four controls with units. Validate supported combinations and headroom before generation; import validation must match the editor. Preserve legacy settings when switching; disable superseded scaling and terrain controls with clear explanations.
- Require complete explicit profiles when present, handle absent settings according to SPEC, and reject unknown versions/nonfinite values. Export explicit defaults for new RH worlds. Verify every copy/save/import/world-authoritative path.
- Invalidate preview on relevant edits without leaking/cross-sharing plans. Display limitations of zoomed erosion detail accurately.
- Record the experimental model identity and fresh-world iteration policy before distributing the first jar. A formula/default change must not silently reinterpret an already-created world.

**Verify/exit:** create/export/import/copy/edit/save/reopen loop, malformed input tests, dedicated-server load, and independent control sweeps at supported bounds. Width changes leave the graph/profile identical; mountain/foothill controls leave layout identical and visibly change their intended amplitudes.

## T6 — Package and validate the first playable experiment

**Depends on:** T5. **Covers:** RH-01–11 within the experimental scope. **Risk:** medium; validation can reopen T1–T5.

**Touch:** focused tests, diagnostic/evidence scripts, experimental preset and handoff notes. Fix findings in their owning implementation task.

- Run fixed seeds `0, 1, 42, 8675309, -314159` with reproducible sampling sites/routes chosen by the SPEC protocol. Record map/route selection failures rather than moving silently to attractive locations. Inspect generated blocks at structural problem sites, not just overview renders.
- Compare serial/reverse/random chunk orders, concurrent builds, eviction/rebuild and interleaved world/preview owners. Test cancellation/resource release, finite caps, negative/far coordinates, narrow-world headroom and supported setting boundaries.
- Run focused Java tests, compile/build Fabric and NeoForge and perform both-loader world-generation/load smoke tests. Establish runnable test commands in T0; expected build entry points are `./gradlew :common:test :fabric:build :neoforge:build` after test setup. Run with Java 21. An unavailable dependency/runtime is a reported verification gap, never a pass.
- Measure cold plan latency, retained bytes and warm generation throughput against the captured current-checkout baseline. Hard caps and absence of deadlocks are required; proposed SPEC performance ratios are evaluation targets until measured. Record any missed target and its practical exploration impact before handoff.
- Deliver jars, exact preset, seed/coordinate routes, model/source identity, screenshots/profiles, known issues and fresh-world instructions. Recheck legacy output before packaging.

**Verify/exit:** all first-delivery must-haves in SCOPE are evidenced. Structural water, ownership, determinism, legacy or crash failures block handoff; aesthetic tuning issues may remain clearly listed. Label the result experimental and do not assert V3 superiority.

## T7 — Exploration-led tuning

**Depends on:** T6 and user exploration feedback. **Covers:** RH-02, RH-03, RH-06, RH-07, RH-11.

Collect seed, coordinates, direction of travel and the unwanted behavior. Classify it as spacing/connectivity, amplitude/foothills, valley/confinement, water integrity or erosion. Fix structural defects first. Change one relevant family of parameters at a time and compare the same maps/routes across the seed panel. Preserve good sites as regression examples, but do not replace the fixed panel with favorites. Record each model change and use fresh worlds where output changes. Repeat until the exploration pattern is satisfactory.

## T8 — Broaden coverage and prepare a release candidate

**Depends on:** T7; exact V3 fixture required for its comparison. **Covers:** full RH-01–11 / AC-01–12.

Expand supported combinations deliberately; introduce adaptive planning/shared ports only when justified by measured failures or desired coverage. Complete the full loader/settings/biome/cave/structure interaction and resource matrix. Evaluate all full-SPEC aesthetic and performance targets, resolve failures or explicitly revise proposed thresholds with evidence. Capture the exact V3 world configuration and compare the same protocol before making improvement claims. Freeze release defaults/model support policy and publish the final acceptance report.

## Coverage and plan review

| Requirement | Main tasks | Evidence before first handoff | Later completion |
|---|---|---|---|
| RH-01 / AC-01 | T0, T3, T5, T6 | Mode selection and legacy terrain/final-water fixtures | Expanded regression matrix |
| RH-02–03 / AC-03–04 | T1, T2, T4, T6 | Five-seed structure/routes, valley/buildability views | Quantitative tuning and V3 comparisons |
| RH-04–05 / AC-05–06 | T2, T3, T6 | Graph, profiles, junctions, real outlets and final blocks | Broader settings/loader coverage |
| RH-06 / AC-07 | T2–4, T6 | Bounded cuts and erosion-off/on channel/form checks | Calibrated visual/numeric targets |
| RH-07 / AC-08 | T1, T2, T4–6 | Four functional controls and structural invariance | Wider ranges where supported |
| RH-08 / AC-02, AC-09 | T3, T5, T6 | Codec, UI, copy/export, reopen and server load | Release defaults/version policy |
| RH-09 / AC-10–11 | T1–3, T6 | Determinism, concurrency, hard bounds and timings | Full stress/performance matrix |
| RH-10 / AC-09 | T3–6 | Shared preview plan and spacing-aware filtering | Broader zoom/setting coverage |
| RH-11 / AC-03–04, AC-12 | T0, T6–8 | Reproducible multiseed provisional evidence | Exact V3 comparison and release decision |

Inline review outcomes: all requirements have owners; experimental and release gates are distinct; the domain/outlet and profile feasibility risks precede editor expansion; actual water consumers and final blocks are included; four controls are not postponed out of the playable build; there is no implicit approval of proposed numeric targets. No implementation tests were run for this documentation-only planning step.

The next implementation action is **T0**, followed by T1–T3 as a connected proof. Further broad literature research is not a prerequisite. Research should resume only for a concrete failed assumption that implementation evidence exposes.
