# Rolling Highlands RH4 — natural river planform

Date: 2026-10-28. Status: plan for review. Builds on RH3 (`1.0.0014-rh3`, model 3, `348d9a43`).
Research: [RESEARCH-RH4.md](../../research/rolling-highlands/RESEARCH-RH4.md).

## 1. Goal

Main rivers that wind like real rivers — meandering on flat floodplains, straighter where steep or confined — that
join at natural angles with rounded confluences, independent of the plan grid. Keep everything RH3 got right:
drainage-first topology on FTF's land, stream-power valleys, base-level floor, lakes/wetlands, Streams Reflowing
contract, all quality and realism gates.

Scope: **C1 + C2a + B + D + E**, with **C2b conditional** (only if the direction-bias target fails after R5).

## 2. Architecture (model 4)

```
legacy land L ─► routing graph  (C2a: jittered lattice, positions sampled where routed; C2b: Poisson-disc+Delaunay)
                  │  Priority-Flood+ε, stream capture, stream power, lakes, dams   (unchanged algorithms)
                  ▼
               graph node values ─► rasterised onto the field lattice (incision, base level, lake depth)
                  │                                   (existing C1 interpolation kernels keep working)
                  ▼
               reaches: VALLEY AXIS  = smoothed centreline (today's Hermite, tangent-continuous)
                        CHANNEL LINE = axis + Kinoshita meander (M ≈ 11 w; ω from slope, confinement, regime)
                        tributary mouths curved to a target junction angle (D)
                  ▼
per column:  floor/fadeout/terraces/wetlands  ← distance to VALLEY AXIS (floor widened to the meander belt)
             riverbed/banks/water/current     ← distance to CHANNEL LINE
             confluence: smooth union of channel distances (rounded tongue) + scour pool + inner-bank bar (E)
             oxbow crescents on high-ω floodplain reaches (still water, plan lakes)
```

Why two lines: the valley stays a smooth, wide landform (what RH3 already shapes well), while the river wanders inside
it. Meanders never cut valley walls, and valley-scale terrain does not change when meander parameters change.

## 3. Phases

One commit per phase; each runs the full `:common:test` suite, the RH4 metrics and a hillshade of the user's seed
(−6240223350076425471, Test-America-v3) plus the five test seeds.

### R0 — Metrics and baseline (no terrain change)
- Probe/test metrics (in `HighlandRealismProbe`, reported in `continentRealism`):
  - sinuosity on 1 km windows along rivers > 4 km² (mean, share < 1.1, share > 1.5);
  - direction bias: share of 512-block chord directions within ±5° of a 45° axis;
  - junction angle histogram, share < 25°;
  - confluence tongue width 8 blocks behind the dry tip (rendered grid);
  - channel cells outside their own reach's floor + bank zone; channel crossings;
  - plan build time and grid/refined node counts.
- Promote the user-preset renderer (`TmpUserPresetTest` in /tmp) into a test-scoped tool that can render any preset +
  seed + centre (hillshade, water classes), using a checked-in copy of the Test-America-v3 terrain/river settings.
- Record RH3 baselines in §6. No gates yet beyond the existing ones.

### R1 — C1: grid resolution independent of continent size
- Split `MAX_NODES` into `MAX_GRID_NODES` (routing/field lattice) and `MAX_REFINED_NODES` (channel samples).
- Choose the routing step as the finest of {64, 128, 256…} that fits the grid budget; set the budget so continent
  scale 5000 stays at 128 (user continent needs ~72.8k nodes at 128 → budget 131,072).
- Raise the refined budget for longer meandering channels (R3 increases samples by ~1.3–2×).
- Measure build time and transient memory on the user continent and the default seeds; target ≤ 1 s, record.
- Expected: user world gets RH3's 128-step look; no gate changes.

### R2 — C2a: jittered-lattice routing graph
- Every lattice node gets a deterministic position offset (|offset| ≤ 0.45 step per axis); ground is sampled at that
  position; routing distances, flat resolution and stream-power run lengths use real node-to-node distances.
- Neighbourhood: 8 neighbours with the existing diagonal no-crossing rule; evaluate adding knight moves (16
  neighbours) with a segment-crossing check — keep only if it measurably reduces direction bias.
- Remove the post-routing channel SNAP/JITTER (superseded); keep snapping to local lows only if R0 metrics favour it.
- Fields: incision, base level (water floor) and lake depth are computed per graph node, then rasterised to the
  regular field lattice (barycentric from the 2 triangles of each jittered cell quad) once per plan. Existing
  Catmull-Rom / smoothstep-bilinear sampling is unchanged.
- Basins/dam-lake flood fills stay lattice-indexed (same topology, real positions).
- Gate: direction bias reported; junction angle and all RH3 gates hold.

### R3 — B: valley axis + meandering channel
- Reach geometry stores two polylines: `axis` (today's smoothed Hermite, from R2 nodes) and `channel`.
- Channel = axis offset by integrating a Kinoshita heading `θ(s) = ω sin φ + ω³(J_s cos 3φ − J_f sin 3φ)` along
  the axis, with:
  - arc wavelength `M = k·w`, k ∈ [10, 14] from low-frequency noise; w = bankfull width from drainage area;
  - phase φ continuous along each main stem from the outlet upward; tributaries start their own phase;
  - `ω = ω_max · flat(slope) · regime(x, z) · confine(floor) · taper(ends)`:
    - `flat`: 1 on gentle reaches → 0 on steep ones (straight mountain reaches);
    - `regime`: slowly varying noise (wavelength ≈ 2–4 km) that switches between straight-ish and strongly
      meandering stretches → the user's "balance with straighter sections";
    - `confine`: ω reduced until the belt fits the valley floor (and the floor is widened up to a limit where the
      valley allows it);
    - `taper`: 0 at junctions, lake inlets/outlets, dam sites, the coast mouth, and channel heads;
  - ω_max ≈ 1.5 rad (P ≈ 1.9) so loops never touch; J_s, J_f from noise (skewed, varied bends);
  - amplitude also clamped against neighbouring reaches (distance query at build time) → no crossings.
- Water level: profile computed along channel length (gentler slope, realistic); the existing reverse-min,
  MAX_WATER_SLOPE and confluence-rapid passes run on channel samples.
- Valley sampling (`carveValleys`): floor/fadeout/terraces/wetland weights use distance to the axis; bed/bank/wet
  ownership/current use distance to the channel; the bank-step zone becomes "max of channel bank and floor".
- Oxbows (occasional): on reaches with ω near ω_max and a wide floor, place a crescent at a loop just upstream of a
  simulated neck cutoff, as a still-water plan lake at channel level (+0–1), partly silted (wetland edge). Rate set by
  noise so they stay occasional. SR sees them as still water.
- Sampling: refined pieces ≤ min(16, M/8) blocks.
- Gates: sinuosity targets, channel-outside-floor = 0, crossings = 0, all RH3 gates.

### R4 — D: natural tributary mouths
- Target junction angle from Howard: `cos θ = S_main / S_trib`, clamped to [35°, 90°], blended toward 72° by noise.
- Rebuild the tributary's last segment(s) (up to max(2 grid steps, 6 w_main)) as a Hermite whose end tangent is the
  main stem's downstream direction rotated by θ; optionally slide the junction point downstream along the main
  channel (≤ 3 w_main) so the curve does not cross the main channel.
- Meander taper (R3) keeps the mouth reach straight enough for the curve.
- Gates: junction mean 60–80°, share < 25° ≤ 10%, no water steps at junctions.

### R5 — E: confluence form
- Rounded tongue: near a junction, wet ownership and bank distance use a smooth union of the two channel distance
  fields (radius ≈ 0.5–1 × tributary width), so the dry apex is rounded.
- Scour pool: bed lowered by (0.5–1.5) × bed depth over ~1–2 w around the junction, scaled by junction angle and
  discharge ratio (Best 1988); water level unchanged.
- Inner-bank bar: a shallow sand/gravel bar (bed raised to water − 1 … water) in the separation zone just downstream
  of the tributary mouth on the tributary side.
- Gates: tongue width ≥ 6 blocks; FULL-chunk validation at confluences (both loaders).

### R6 — C2b (conditional): irregular routing graph
- Only if direction bias > 26% after R5 (RH3 baseline 27.6–30.7%, uniform 22.2%).
- Poisson-disc nodes + Delaunay neighbours; Priority-Flood and stream power on the graph; fields rasterised as in R2.

### R7 — Settings and model 4
- `RollingHighlandsSettings` model 4 (RH3 worlds rejected with "create a new world").
- River page (RH branch): **Meanders** slider 0–200% (default 100; scales ω_max and regime share) — see open
  question O1.
- Lang keys, codec tests, determinism tests (meander/axis independence: valley width/meanders must not move the
  drainage plan).

### R8 — Validation, docs, deploy
- Full build both loaders; FULL-chunk validation (Fabric + NeoForge) at a meander, a confluence and an oxbow on the
  default preset and on the user's exported noise settings (sea level 62, y −128..768).
- Hillshade before/after for the user seed; update IMPLEMENTATION.md / VALIDATION.md; bump to `1.0.0014-rh4`;
  deploy to F&F Server - Terra (rh3 jar disabled, preset → model 4 with backup). SR in-game smoke test stays manual.

## 4. Targets

See RESEARCH-RH4 §6. Summary: sinuosity mean 1.25–1.6 with ≥ 20% straight (< 1.1) and ≥ 15% meandering (> 1.5)
windows; direction bias ≤ 30%; junction mean 60–80°, < 25° ≤ 10%; tongue ≥ 6 blocks; 0 channels outside their floor;
0 crossings; tip width median ≥ 8 with ≤ 5% < 5; direction bias ≤ 26%; plan build ≤ 1 s on the user continent; sampling ≤ +25%; all RH3 gates.

## 5. Decisions

User, 2026-10-28:
- Do B + C1 + C2 + D + E as one rework; foundation first (C1, C2a), then planform (B), junctions (D), confluence form (E).
- C2b (Delaunay) conditional on the direction-bias metric.
- Meanders: a balance of lazy loops and moderate bends **with straighter sections**; occasional oxbows acceptable.
- Write research and plan before code.

Design (proposed, for review):
- Two-line reach geometry (valley axis + channel); valley terrain never depends on meander parameters.
- Graph-for-routing, lattice-for-fields (rasterise) so C2a/C2b do not disturb RH3's smooth field interpolation.
- No SR-specific code; SR keeps reading cells (`terrain`, `height`, `riverWaterLevel`, flow field).

## 6. Open questions

Resolved 2026-10-28 (user): **O1** Meanders slider 0–200% on the River page — yes. **O2** oxbows with still water in
the centre and wetland margins — yes. **O3** knight-move neighbours — evaluate in R2, adopt only with a clear gain.

## 7. Risks

- Meanders vs base-level floor and lake rims: the channel can sit off the axis where the floor is higher; the floor
  must use the channel's water where wet, the axis water elsewhere (verify with `sunken` / `belowSea` gates).
- Longer channels raise refined-sample and index budgets → per-sample query cost; measure in R3.
- Tapering ω at every lake/dam/junction may leave too many straight stubs; tune with the sinuosity distribution.
- Rasterising jittered-node fields can soften incision near channels; compare incision and dry-jump gates in R2.

## 8. Progress log

- 2026-10-28: research and plan written (baselines from the user world: grid step 256, sinuosity 1.17, junction mean
  65° with ~27% below 30°, plan build 206–260 ms).
- R0 done: `HighlandPlanformProbe` / `HighlandPlanformTest` (sinuosity, direction bias, junction angle, tip width 2
  blocks behind the dry confluence tip, channel crossings, plan cost; hillshades `planform-user*.png`), user preset
  fixture `test-america-v3.json`. Baseline (RH3):

  | | grid | build | sinuosity | <1.1 / >1.5 | dir. bias | junction | <25° | tip width / <5 | crossings |
  |---|---|---|---|---|---|---|---|---|---|
  | seed 0 | 128 | 181 ms | 1.21 | 23% / 7% | 28.6% | 92° | 3% | 10 / 0% | 2 |
  | seed 42 | 128 | 202 ms | 1.31 | 5% / 11% | 27.6% | 85° | 11% | 11 / 20% | 0 |
  | seed 8675309 | 128 | 215 ms | 1.24 | 20% / 8% | 30.7% | 72° | 22% | 10 / 19% | 2 |
  | user | 256 | 186 ms | 1.17 | 53% / 7% | 30.3% | 65° | 22% | 9 / 27% | 4 |

  Crossings are tributary Hermite curves hooking back over the main stem near junctions (visible as hooks in the
  render) — R4 removes them. Tip-width target revised to ≥ 5 blocks 2 behind the tip (knife edge ≈ 3).
