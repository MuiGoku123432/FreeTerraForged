# Rolling Highlands RH2 — productionization plan

Date: 2026-10-27. Branch: `feature/rolling-highlands` (RH1 = `3672fcae`).
Status: plan for review. Supersedes the T7/T8 sections of PLAN.md for the RH2 delivery.

RH1 was a test slice. RH2 makes Rolling Highlands a real, playable preset with the same downstream river
contracts as the legacy river system. FTF already works with SR as-is, so SR compatibility is kept by
**output parity**: RH must produce the same kinds of river cells, water blocks, biomes, flow-field data and
sealing that legacy FTF rivers produce. No SR-specific code is planned.

## 1. Baseline defects (measured on RH1, seeds 0 and 8675309, default preset)

| # | Defect | Root cause | Evidence |
|---|---|---|---|
| D1 | Vertical walls/seams across valleys | `RollingHighlandsContinent.apply` shapes terrain from the single nearest reach (`HighlandPlanIndex.nearest`); no composition between reaches with different water | 61-block wall in 1 block at (4542, 9147); 36 dry jumps >3 blocks on 12 transects; red seams in `build/rolling-highlands/diag-*.png` |
| D2 | Width steps along rivers | Confinement constant per reach (`HighlandPlanIndex:69`) | Valley width jumps at every coarse node |
| D3 | Whole landscape is valley | INFLUENCE 1536, outer run up to 1024 | 94–95% of dry land in a river zone |
| D4 | Ruler-straight rivers | 6-neighbour square grid without NE–SW diagonal, 128-block step, ±32-block smoothing | 62% of reaches at exactly 0/45/90°, 26 of 619 at 135° |
| D5 | Variant / peak biomes in wrong places | Weirdness `lerp(.15,.65,ridge)` always positive; erosion/weirdness/terrain from pre-carve ridge noise | 100% land positive weirdness; 27% peak slice; 13% of valley floors peak slice |
| D6 | Huge beach/flat coastal band | `height < 5 → BEACH`; wide coastal taper | ~20% of continent <5 blocks; ~10% BEACH |
| D7 | Very deep oceans | `properties.oceanDepth` (a lava-level offset, default 256) used as floor depth | Ocean floor ≈ Y −193 |
| D8 | Polygonal lakes, shore notches | Triangle-corner basin sampling returns null at triangle edges | Straight lake edges; one 76-block shore cliff |
| D9 | Raised river banks / perched water | +8 profile allowance; floor = water+2..4 even when land is lower | Possible water up to 6 above planning ground |
| D10 | Flow field is a stub | Constant magnitude 0.5, centreline direction only, no lakes/junctions/waterfalls | `RollingHighlandsContinent:108-109` |
| D11 | Runtime throws in worldgen | Basin merge / plan validation exceptions inside `apply`; failed plans not cached | `HighlandBasins:65`, `HighlandDrainage` checks |
| D12 | Global lock per sample | `synchronized plan()` on every cell | `RollingHighlandsContinent:41` |

## 2. Output-parity contract (legacy river system → RH must match)

These are what FTF consumers, and therefore SR, see from legacy rivers. RH2 must provide each:

| Output | Consumer | RH1 state | RH2 requirement |
|---|---|---|---|
| `TerrainType.RIVER` on wet channel cells | river biome role selection, jigsaw avoidance, locate | partial | channel + lake cells; banks never RIVER |
| `#is_river` biome on channels | vanilla/biome mods | via role selection | verified in FULL chunks |
| `WETLAND` terrain where flats meet water | wetland biomes, surface water | none | floodplain wetlands in broad low reaches (P6) |
| `riverZone` Riverbed/Banks/ValleyFloor/ValleyFadeout | surface mixin, gasket, scree | yes | continuous, consistent with composed geometry |
| `riverWaterLevel` + `hasRiverWater` | surface mixin, gasket, scree | yes | taken from the reach/lake that owns the wet cell |
| `riverMask` | structures (`CellTest`), filters | yes | continuous in [0,1] |
| `hasFlow` / `flowAngle` → `ChunkFlowField` | particles, boat currents, navigable waterfalls, client sync, NBT | stub | full model (P5) |
| Waterfall columns | surface mixin `multiBlockDrop` | averaging disabled for RH | RH-specific drop handling (P5/P6) |
| Gasket sealing | `RiverGasketFeature` | yes | only real channel/lake columns; bounded work |

## 3. Phases

Each phase ends with `./gradlew :common:test :fabric:build :neoforge:build --offline`, regenerated diag maps
and the phase gates below. Phases are committed separately.

### P0 — Permanent quality harness (first)
- Promote the RH1 diagnostics into `HighlandQualityTest`: 4-block maps for 5 seeds plus 1-block transects.
- Metrics with gates: dry 1-block jumps, seam pixels, % land in valley zone, reach angle histogram,
  weirdness sign/slice distribution, % beach, % <5 blocks, ocean floor depth, lake edge discontinuities,
  adjacent wet water steps ≥2, perched water, flow coverage on wet cells.
- Record RH1 numbers as the baseline; gates tighten in later phases.

### P1 — Continuous valley composition (D1, D2, D3, D9)
- Query all reaches within influence, not just the nearest. Compute each reach's section and combine with a
  smooth-min carve: `height = smin(land, section_1..n)`. Sections never rise above `land`, which removes berms.
- Wet ownership: the reach whose wet radius contains the point and whose water is lowest; ties resolved by
  stream order. Water level and flow come from the owner only.
- Interpolate confinement, discharge and width along reaches; blend them at junctions.
- Shrink influence: outer run scales with relief but is capped (target ≤ 512 blocks) and fades to the
  undisturbed land between valleys, so interfluves and ridges stay intact.
- Divide ridges apply to every adjacent pair of reaches, not only different outlets.
- Gates: 0 dry jumps > 3 blocks/block outside designated cliffs; valley zone ≤ 60% of land; no wet cell
  with a lower dry neighbour.

### P2 — Natural drainage routing (D4)
- Replace the 6-neighbour grid with D8 (diagonal cost √2) priority flood plus an epsilon gradient across
  flats, so flats drain toward outlets instead of in index order.
- Two-level planning: keep the coarse continent plan for topology; refine each reach on a local 16–32 block
  grid inside a corridor, re-routing on the visible planning surface so rivers follow valleys rather than
  cutting sub-grid ridges.
- Smooth centrelines with bounded Chaikin/Catmull-Rom, plus deterministic meander amplitude scaled by
  discharge and inverse slope (wider meanders on flats, near-straight in gorges).
- Taper channel heads: width ramps from 0 over the first reach.
- Gates: no reach-angle bin > 2× the mean; mean |curvature| > 0 on lowland reaches; profile solver still
  feasible across all sampled owners.

### P3 — Lakes and basins (D8, D11)
- Build a smooth lake field: bilinear `(spill − ground)` on the grid dilated by one cell, so shorelines are
  contours instead of triangle edges.
- Resolve adjacent basins with different levels during plan build (merge to the shared spill level).
  Never throw from per-cell sampling.
- Lake outlets connect to their river reach at the spill level; inlet reaches enter at lake level.
- Gates: no shoreline jumps > 3 blocks; no exceptions in a 10k-point probe per owner.

### P4 — Biome and terrain inputs (D5, D6, D7)
- Derive weirdness from the final relief, using the negative (normal) slices like legacy: valley floors and
  channels → VALLEY/LOW, slopes → MID/HIGH, ridgelines above surroundings → PEAK. Variant (positive) slices
  come only from a separate low-frequency variant noise, at a ratio comparable to legacy.
- Erosion from local slope/relief after carving (flat floors high erosion, ridges low), clamped to legacy
  ranges.
- Terrain type from the final surface: FLATS/HILLS/MOUNTAIN_CHAIN by relief, RIVER/WETLAND by water.
- BEACH only inside the beach control-point band at the coast. Narrow the coastal taper so the low coastal
  plain is a band, not a fifth of the continent.
- Ocean floor uses legacy-like depths, driven by continentEdge and the existing ocean control points.
  `properties.oceanDepth` keeps its lava-offset meaning.
- Gates: positive weirdness ≤ legacy share ±10 pts; peak slice on valley floors ≈ 0; beach ≤ 3% of land;
  ocean floor within the legacy depth range.

### P5 — Flow field and waterfalls (D10)
- Port the `UpliftRiverCarver.storeFlowDirection` model: downstream tangent from the smoothed centreline,
  inward turn at the banks, magnitude falling off across the channel.
- Magnitude scales with profile slope and discharge (fast in mountain reaches, slow on flats, calm in lakes).
- Junction blending: direction is a weighted blend of the branches across the level junction core.
- Lakes: low-magnitude drift toward the outlet; zero far from the outlet.
- Waterfalls/rapids: where the solved profile drops more than 1 block per block, mark rapids. The surface
  mixin builds proper RH waterfall columns so navigable waterfalls and particles behave like legacy.
- Gates: 100% of channel cells have flow; flow points downstream on every sampled reach (dot > 0); boat
  smoke test in FULL chunks.

### P6 — Water, surface and filters
- Floodplain wetlands: broad low reaches get WETLAND margins with legacy wetland water behaviour.
- Erosion/relaxation protection mask from the composed geometry; check chunk-edge agreement.
- Gasket only runs on chunks with RH water columns (performance).
- Extend `HighlandWorldValidation`: water columns, banks, waterfalls, flow-field bytes in FULL chunks, after
  ticks.

### P7 — Robustness and performance (D11, D12)
- Lock-free plan cache: `ConcurrentHashMap<Long, CompletableFuture<Plan>>` with bounded LRU eviction. No
  per-sample monitor.
- Plan-build failures: deterministic repair (relax the slope class, then widen the cut allowance within
  bounds). If still infeasible, publish a valid plan with that tree's channels removed and log once. Never
  throw from `apply`.
- Benchmark against UPLIFT on the same machine with the existing runner.

### P8 — Settings, editor and preview
- Bump the model version to 2. RH1 (v1) presets are rejected with a clear message.
- Preview render modes show the RH river network, lakes, flow and valley zones. Previewed river positions
  must match the generated ones.
- Update UI help text and translations.

### P9 — Validation and release check
- Full test suite, both loaders, in-game FULL-chunk validation at fixed coordinates for 5 seeds.
- Manual SR + FTF smoke test in a new RH world: rivers, lakes, junctions, waterfalls, boats, particles.
- Update IMPLEMENTATION.md / VALIDATION.md with before/after metrics and maps.

## 4. Decisions (user, 2026-10-27)

1. **RH1 world compatibility:** drop v1. Model version 2; v1 presets are rejected with a clear message.
2. **Wetlands:** included in RH2 (P6).
3. **Meanders:** subtle; mostly follow the valley shape.
4. Plan approved; build in the order below, one commit per phase.

## 5. Execution order

P0 → P1 → P4 → P3 → P2 → P5 → P6 → P7 → P8 → P9.
P1 and P4 fix most of the visible problems (seams and biomes) first. P2 changes topology, so it comes after
the harness can show regressions. P5 depends on the final centrelines from P2.
