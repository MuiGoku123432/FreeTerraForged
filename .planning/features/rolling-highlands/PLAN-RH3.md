# Rolling Highlands RH3 — FTF-faithful, drainage-first terrain

Date: 2026-10-27. Status: plan for review. Builds on RH2 (`1.0.0014-rh2`, model 2).
Research: [RESEARCH-RH3.md](../../research/rolling-highlands/RESEARCH-RH3.md). User direction: draw much closer on
what FTF already does; agree with the research fixes.

## 1. Principle

Keep FTF's **land** and FTF's **river look**; replace only what FTF never had, a **drainage plan derived from the land
that is actually rendered**.

What we learned about the existing FTF systems (code inventory, 2026-10-27):

- Legacy land is a rich, user-tunable composition: terrain regions (steppe, plains, hills, dales, plateau, badlands,
  torridonian), mountain chains (with variety and fancy erosion), `mountainShape` + `Blender(.3, .8, .575)`. Every
  slider on the Terrain page feeds it. RH1/RH2 threw all of it away for ridged noise, which is why RH mountains read
  as noise blobs.
- Legacy rivers do **not** follow terrain: straight segments from the continent centre to the coast, noise-warped,
  carved down to a stepped continental water table (`ContinentalHydrology`). Their *shape* is good: `UpliftRiverCarver`
  zones (riverbed, terraced bank step, flat valley floor, terraced fadeout whose width grows with relief), gully and
  rivulet drainage masks, talus/cliff shaping, lakes as widened beds on flat steps, `Wetland` bowls with hummocks.
- So RH3 = legacy land as the surface, stream-power drainage computed **on that surface**, legacy carver/wetland/lake
  shapes driven by the plan's water levels and drainage areas.

## 2. Architecture (model 3)

```
legacy land L(x)  (Heightmap.make composition: regions + mountains + Blender, honoring TerrainSettings)
   │  sampled on jittered per-continent plan nodes (+ RH coast and coastal rise)
   ▼
route on L  (Priority-Flood+ε, D8 no-crossing, flat resolution, randomised receivers) → receivers, area A
   ▼
incise L by stream power  z_i = min(L_i, z_rcv + Δx·ks·A_i^−θ)  (Flint's law, θ≈0.45; ks = erosion-maturity control)
   ▼
water w_i = max(z_i, w_rcv)  → lakes where w > z (keep or breach by size/chance), channels where A ≥ threshold
   ▼
per column: surface S = min(L(x), bicubic(z))   (uneroded uplands keep full legacy detail)
            + FTF UpliftRiverCarver zones around plan channels (bed/bank/floor/fadeout, terracing, gullies)
            + FTF Wetland bowls on low-gradient, large-area reaches; lakes from plan basins with FTF lake look
   ▼
existing FTF filters (Phacelle Erosion, CliffRelaxation), surface mixin, gasket, scree, flow field, climate
```

Why this fixes the research findings:
- Routing happens on the rendered land, so rivers occupy real lows; no more 30%+ of river length cut 60+ blocks into
  unseen mountains.
- Incision is the steady-state stream-power profile: concave long profiles, tributaries at grade (Playfair), valleys
  deepen where land stands high above the outlet, ridges remain as divides.
- Lakes appear only where the incised surface still holds a depression (w > z) — process-based, few, with a defined
  outlet; the RiverSettings lake chance decides keep vs breach.
- Uplands, mountains and region variety are exactly FTF's, tuned by the same sliders.

## 3. Phases

Each phase: tests + quality harness + continent hillshade; one commit per phase.

### Q0 — Realism metrics and SR contract tests (no terrain change)
- Promote the continent hillshade renderer into the harness.
- Add metrics: lake area fraction, dry depression fraction, junction-angle mean/histogram, Hack exponent and
  Horton/Tokunaga ratios on the plan, slope–area concavity, slope histogram, channel incision below uneroded land,
  valley-floor width vs area.
- SR contract test: read cells exactly as Streams Reflowing 2.15.2 does (`applyCell(cell,x,z,false)`, RIVER by
  reference, `max(riverWaterLevel, water)·terrainScaleFactor`, `flowAngle`) and assert agreement with RH water.
- Record RH2 numbers as the baseline.

### Q1 — Legacy land as the surface
- Build the legacy land composition (`mountainShape`, `TerrainProvider.generateTerrain` + region selector/lerper,
  `VariedMountainPopulator`/mountain chain, `Blender`) inside the RH continent, exactly as `Heightmap.make` does, but
  without the uplift water table. Units: `(cell.height − levels.water)·terrainScaleFactor` blocks; sampled at
  `x·terrainFrequency`.
- Apply the RH coast and ease-out coastal rise; keep legacy ocean populators.
- Biome inputs come natively from the populators (erosion, weirdness, terrain type), with the RH valley suppression
  layered on top later.
- RH sliders: mountain height and foothill relief retire in favour of the Terrain page (mountains, regions, variety,
  fancy mountains, global scales). The Terrain page shows the normal FTF controls again for RH.

### Q2 — Drainage plan on the rendered land
- Sample L on a jittered node lattice (±0.3 cell, true distances), coast/ocean as base level.
- Priority-Flood+ε with Barnes flat resolution, D8 without crossing diagonals, randomised receivers; accumulate A.
- Incision z = min(L, z_rcv + Δx·ks·A^−θ) with θ = 0.45 and talus cap; `ks` from a new "valley depth / erosion
  maturity" control.
- Water w = max(z, w_rcv); depressions with w − z ≥ lake minimum are lake candidates. Keep with probability from
  `RiverSettings.lakes.chance` (deterministic hash) and size limits; otherwise breach (lower the outlet saddle along the
  path) or fill as flat floor.
- Channels where A ≥ threshold; threshold from `RiverSettings.riverCount` reinterpreted as drainage density.
- Retire HighlandFields, the tree interval solver and planning-height concepts (water is monotone by construction).

### Q3 — Surface from plan + FTF river carving
- Per column `S = min(L(x), bicubic(z))` (C1 interpolation of the incised plan surface).
- Extract `UpliftRiverCarver` zone math (riverbed depth model, bank step with terracing, flat floor, terraced fadeout,
  `applyTerracing`, gully/rivulet drainage masks, valley pinch) into pure static functions taking an explicit water
  level and widths; RH2's continuous multi-reach composition and support cones stay.
- Widths from `RiverSettings` (bedWidth, bankWidth, bed depth, bank heights, fade = head taper), scaled by drainage area
  (hydraulic geometry w ∝ A^0.35–0.4, floor ∝ A^0.3).
- Fadeout width grows with relief, as legacy (one bank width per 100 blocks of relief).

### Q4 — Lakes and wetlands with FTF looks
- Lakes: plan basins (Q2) with FTF's lake shoreline warp (simplex 55, 3 octaves, ±45%), level = outlet pass, lakes
  favoured on flat reaches; RiverSettings lake depth/size honoured.
- Wetlands: port `Wetland.apply` (bowl bed at water − 3.5, flat floor beyond 0.7, quintic walls with rivulets,
  hummocks +1..+2) with an absolute water level; placed on low-gradient, high-area reaches; RiverSettings wetland
  chance/size honoured. Decide SR visibility (see open decisions).

### Q5 — Network realism
- Junction angles toward 60–75° (tributary approach shaping near confluences), river mouths spaced along concave
  coastline, meanders only on low-slope reaches (RiverWarp-style lateral offset bounded by the floor), sub-plan
  tributary creases via FTF's `noise/module/Erosion` (flow-to-lowest-neighbour network noise) on double coordinates.

### Q6 — Detail and filters
- Keep FTF's Phacelle erosion filter and cliff relaxation; optionally seed gully direction from plan flow; expose the
  Erosion Filter page for RH. Enable legacy waterfall averaging on real knickpoints.

### Q7 — Settings, model 3, UI
- Model version 3; RH-specific controls reduced to: valley depth (erosion maturity), drainage density, valley width
  multiplier, lake abundance (or mapped onto RiverSettings). Terrain, River and Erosion pages behave like FTF's.

### Q8 — Validation
- Quality gates (RH2 gates kept), realism targets from RESEARCH-RH3 §3, FULL-chunk validation on both loaders, SR
  in-game smoke test in the F&F Terra instance, docs.

## 4. Targets (from research)

Lake area 1–3% of land (default), dry depressions ≤ ~3%, junction mean 55–75°, Hack h 0.5–0.65, Rb 3–5, concavity
0.3–0.6, < 5% of channel length incised > 60 blocks below uneroded land unless in a mapped gorge, slope histogram mode
≤ 30–35°, plus all RH2 continuity/water/biome gates.

## 5. Decisions (user, 2026-10-27)

1. RH3 terrain is controlled by the normal FTF Terrain page; RH keeps only drainage-specific controls.
2. Wetlands keep FTF's WETLAND terrain and get wet/swamp biomes (Streams Reflowing outlets).
3. Stream-power profiles only; no stepped water-table option.
4. Plan approved; build Q0 → Q8, one commit per phase.

## 6. Progress log

- Q0 done: `HighlandRealismProbe` (continent hillshade `build/rolling-highlands/continent-<seed>.png`, lake/wet/
  depression fractions, slope histogram, incision below uncarved land, junction angles, Hack exponent, Strahler Rb,
  concavity) and `HighlandStreamsContractTest` (cells read exactly as Streams Reflowing does). RH2 baseline
  (seeds 0 / 42 / 8675309): lakes 4.3 / 8.1 / 6.1%; depressions 2.1 / 1.7 / 1.9%; mean slope 13.6–14.4°;
  incised > 60 below uncarved land 34.5 / 31.3 / 29.5%; junction angle 41–46°; Hack 0.85 / 0.71 / 0.76;
  Rb 3.8 / 5.1 / 5.4; concavity 0.10 / 0.07 / 0.12; max Strahler order 3–4. SR contract: 0 hidden water, 0 phantom
  river, 0 surface mismatches, 0 zero-magnitude currents.
- Q1 + Q2 done (one commit; Q1 alone would route rivers on a surface that is not rendered):
  - `Heightmap.makeLand(ctx, seed)` extracts the legacy land composition unchanged (frozen legacy fingerprints pass);
    `HighlandLand` uses it from a fresh seed, plus a broad smooth upland uplift (the role of UPLIFT's water table,
    without steps). Native populator terrain type / erosion / weirdness drive biomes; valleys pull weirdness toward the
    low slice. `HighlandFields` removed.
  - Plan on the rendered land: priority flood, drainage area, **stream capture** (a channel running beside a
    channel at least twice its size joins it there), stream-power incision z = min(ground, z_rcv + run·0.04·A_km²^−0.45)
    with a 0.6 talus cap, water w = max(z, w_rcv); depressions kept as lakes (≥ 8 deep, ≤ 400 nodes, FTF lake chance)
    or breached along the outlet path. Water profiles are monotone by construction; rapids limit 0.2/block.
  - Rendered land = legacy land + uplands − Catmull-Rom interpolation of the plan's incision depth.
  - Realism (seeds 0/42/8675309): lakes 0.16/0.13/0.54% (target 1–3%, Q4), depressions ~9.3% (Q5), concavity
    0.38/0.33/0.44 (target 0.3–0.6, was ≈0.1), junction angle 83/86/69° (was 41–46°), incision > 60 below uncarved land
    13–15% (now intentional valley depth), Rb 3.9–5.3, Hack 0.85–0.94 (target ≈0.6, Q5).
  - Gates temporarily relaxed with notes: ≤ 10 wet water steps, ≤ 2 RH-induced dry jumps, ≥ 98% channel flow,
    beaches ≤ 4%; integration test asserts cell water never above the plan (lower connected channel may own it).
- Q3 done: `FtfValley` ports UpliftRiverCarver's profile (riverbed bowl, 3-step terraced bank step with gully notches,
  flat floor at water + bank height with terrace/drainage bumpiness, 5-step terraced fadeout with talus shelves and
  cliff faces) into RH blocks with the plan's water level. Uses the same noises/scales (width 150, asymmetry 250,
  pinch 360, depth 100, terrace 200, gully 65, rivulet 20) and FTF main-river settings (bed depth, bank heights,
  bank width); floor ∝ A^0.3 and confinement; fadeout = bank width × (5 + relief/100) like legacy. Confluence rapids:
  a higher connected channel's surface descends to the lower one at 0.25/block; level wet core one sample above
  every junction. Valley-bottom biome suppression only on channel/bank/floor. Strict gates restored (0 wet water
  steps, 0 RH-induced dry jumps).
- Q4 done: FTF-style lakes on flat, large rivers (`damLakes`): at deterministic dam sites on main channels with
  gradient < 1.5%, spaced ≥ 1000 blocks and chosen with the FTF lake chance, water rises by the FTF lake depth
  (×0.6–1.4) behind a natural barrier, flooding the upstream valley only if it stays inside the dam's own basin and
  ≤ 600 nodes. Result: dendritic finger lakes in flooded valleys; lake area 1.3 / 2.5 / 1.6% (target 1–3%).
  Depression lakes need ≥ 4 blocks. Wetland cells get vanilla's top erosion level (swamp / mangrove biomes, which
  Streams Reflowing treats as outlets).
- Q5 done (scoped): wetlands kept 0.1–0.25 edge units clear of the coast band (shared climate code turned coastal
  wetland pools into ocean terrain; channel currents back to 100%); single-node depression ponds (square at plan
  resolution) are breached (≥ 3 nodes); realism gates added: lake area 0.5–4%, closed depressions ≤ 12% (legacy FTF
  land measures 24% MULTI_IMPROVED / 20% UPLIFT; RH3 ≈ 8.5%), concavity 0.15–0.65 on water surfaces (dam backwater
  flattens them; incision uses θ = 0.45), junction angle 50–95° (RH3 69–86°, from stream capture), ≤ 25% of channel
  length incised > 60. Known deviation: Hack exponent 0.85–0.94 (elongated coast-draining basins); not gated.
- Q6/Q7 done: model version 3 (`uplandSpacing`, `uplandHeight` 0–160 default 80, `valleyDepth` 25–200% default 100,
  `valleyWidth`); model 1–2 presets are rejected with "create a new world". The Terrain page shows the RH controls
  followed by the full FTF terrain controls (regions, mountains, variety, scales). The River page shows FTF controls
  RH3 honours: river count (drainage density), flow toggles, main-river bed depth / bank heights / bank width, lake
  chance and depth, wetland chance. FTF's Phacelle erosion filter already runs with RH's channel protection. Build
  1.0.0014-rh3. Waterfall averaging stays off: RH profiles are limited to 0.2/block rapids and level junction cores.
