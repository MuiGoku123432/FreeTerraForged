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
- Only if direction anisotropy > 8% after R5 (R1 baseline 6.9–18.1%; R2 4.0–7.4%; R3 3.1–6.7%).
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
windows; direction anisotropy ≤ 8% (total-variation distance of 512-block chord directions from uniform; sampling
noise ≈ 4% at n ≈ 700 — replaces the axis-share direction bias, which misses bias toward other directions and is now
reported only); junction mean 60–80°, < 25° ≤ 10%; tongue ≥ 6 blocks; 0 channels outside their belt/floor; 0 crossings
(gated from R4: remaining crossings are tributary-mouth hooks); tip width median ≥ 8 with ≤ 5% < 5; plan build ≤ 1 s on
the user continent; sampling ≤ +25%; all RH3 gates.

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
- R1 done: `MAX_GRID_NODES` 131,072 (routing/field lattice) and `MAX_REFINED_NODES` 262,144 (channel samples). The
  user continent now routes at 128 (71,446 grid / 19,051 land nodes, build 539 ms; default seeds unchanged at 128,
  205–265 ms). User metrics: sinuosity 1.18, junction 79°, tip width 14, but direction bias rises 30.3% → 38.1% —
  four lattice steps per 512-block chord expose the D8 axes more than two smoothed 256 steps did (R2 target).
- R2 done: every lattice node routed and sampled at a jittered position (±0.24 step per axis, quads stay convex);
  receivers by steepest real-distance descent with a link-crossing check (replaces SNAP/JITTER); incision, base
  level and lake depth/reach resampled onto the field lattice by barycentric interpolation over the jittered
  triangles (without the lake resample, node-offset lake depths drew straight-edged wedges beside backwater lakes).
  New metric `anisotropy` (total-variation distance of chord directions from uniform).

  | | dir. bias R1 → R2 | anisotropy R1 → R2 | sinuosity | junction | <25° | build |
  |---|---|---|---|---|---|---|
  | seed 0 | 28.6 → 26.1% | 9.1 → 6.6% | 1.27 | 93° | 0% | 161 ms |
  | seed 42 | 27.6 → 25.4% | 6.9 → 7.4% | 1.19 | 80° | 7% | 180 ms |
  | seed 8675309 | 30.7 → 20.6% | 10.0 → 4.0% | 1.18 | 81° | 0% | 137 ms |
  | user | 38.1 → 28.0% | 18.1 → 6.4% | 1.21 | 72° | 7% | 313 ms |

  O3 knight moves (16 neighbours) evaluated: 45°-axis bias falls further (18.5–24.6%) but chords pile onto the
  knight axes instead (44–47% within ±5° of 26.6/63.4° vs ~40% with 8 neighbours, 22% uniform), anisotropy is
  not better (5.4–8.4%), junctions get sharper (user 57°, 24% < 25°) and sinuosity drops. Not adopted
  (`KNIGHT_MOVES = false`). Residual bias is expected to fall further once R3 meanders the channel; R6 decides.
  Lakes 0.73–0.85%, depressions 2.2–2.4%, all gates pass. The blocky grid-scale pattern visible on upland hillshades
  predates R2 (present at R1/128 and RH3/256) — tracked as a follow-up, not part of the routing change.
- R3 done (`HighlandMeanders`): every valley axis (the R2 centreline, 16-block samples) is split into stems (main
  chains); along each stem the channel follows a Kinoshita heading integrated in channel arc length (2-block steps),
  applied as lateral offsets in the axis frame (normals of the axis smoothed over 320 blocks). Plan data: `Reach` gains
  its axis range, `Plan.axis` (axis samples with the local meander belt) and `Plan.oxbows`; the water level is
  interpolated along the axis and the existing monotone/rapid passes run on the channel samples. `HighlandDrainage`
  takes a meander strength (1 = default; the R7 slider feeds it).
  - ω = strength · 1.55 · regime (2.4 km noise, 0.08–1) · (0.1 + 0.9 · flat) · (1 − 0.75 · confined) · taper, capped by
    the belt limit min(160, 0.6 · axis bend radius, neighbour clearance); flat fades between water slopes 0.6% and 3%
    (loops on steeper reaches put neighbouring loops of one channel blocks apart in level → terraces); wavelength
    10–14 widths × sinuosity; bend-to-bend irregularity ±45% in amplitude and length, but each bend's ω never exceeds
    what fits the cap at its actual length (a clamped loop flattens into parallel limbs) and never 1.9; Kinoshita skew
    ±0.04 (larger values fold loops at high ω). Taper to 0 at stem ends, lakes, sea and tributaries with ≥ 20% of the
    flow. Neighbour clearance: half the gap between axes less both half widths and 10 blocks; a tributary's lowest
    400 blocks are instead held clear of the parent's already-built channel.
  - Valley section (`FtfValley`, two lines): bed and bank by channel distance; floor and fadeout by axis distance, the
    floor widened by the belt (smoothed upper bound: moving max then two box means over 96 blocks) inside the valley's
    own extent (outer edge kept, side slope at most twice as steep); the bank rises from the water to the valley
    surface there (cut banks where a bend meets the side). Belt widening fades within 24–120 blocks of any higher
    channel (a floodplain never undercuts a tributary above it). Wetland pools fade where a lower reach (a downstream
    loop) carves near their level — continuous in level difference and ground — which fixed perched pools on seed 0.
  - Oxbows (O2): 30% of loops (at full strength) whose apex reaches 2.5 widths and whose channel is 1.5× its axis span
    are cut at the neck (10% of apex offset) by a near-straight Hermite shortcut; the loop beyond 40% becomes the oxbow:
    still water (0.35 widths half width, 2.5 deep, no flow, `RIVER`) beyond 60% of the apex offset, silted `WETLAND`
    ends and an 8-block wetland margin that never drops below the floor's own channel water − 0.5. Water = lowest
    channel water beside it. Open water keeps bank + 3 blocks from the channel segments; silted ends that reach the
    bank are trimmed. The belt includes the oxbow (always on the floor).

  | | sinuosity | < 1.1 | > 1.5 | anisotropy | junction | < 25° | tip (sharp < 5) | crossings | oxbows | build |
  |---|---|---|---|---|---|---|---|---|---|---|
  | seed 0 | 1.31 | 10% | 15% | 3.1% | 93° | 0% | 8 (11%) | 0 | 0 | 355 ms |
  | seed 42 | 1.21 | 28% | 2% | 6.7% | 79° | 7% | 8 (29%) | 0 | 0 | 351 ms |
  | seed 8675309 | 1.21 | 17% | 0% | 3.4% | 79° | 0% | 9 (15%) | 2 | 0 | 321 ms |
  | user | 1.33 | 17% | 20% | 4.8% | 75° | 10% | 7 (24%) | 2 | 5 | 599 ms |

  All windows: mean 1.29, 18.7% < 1.1, 14.1% > 1.5 (targets 20% / 15% — default revisited with the R7 slider).
  Gates added: 0 channel nodes outside their belt, sinuosity 1.15–1.65, anisotropy ≤ 8%, oxbows present. Remaining
  crossings are tributary mouths meeting a meandering parent within 48–63 blocks of the junction (R4 rebuilds the
  mouth geometry; the crossing gate moves there). Sampling cost (user world, 262k samples): R2 21.2 → R3 23.4 µs
  (+10%) after squared-distance and near-channel shortcuts. Quality gates: perched back to the 3 pre-existing coastal
  mouth cells per seed; sunken 0/684/1247/330/334 (R2 2/696/1377/134/220); lakes and depressions unchanged.
  Pre-existing, not R3 (confirmed against R2 renders): river mouths 1–2 blocks above sea-level shore cells; channels
  that start with large drainage area end in a square head against a wall; streams on narrow spurs between two lower
  valleys; two near-parallel rivers converging into a lake junction. Polyline support cones can show 16-block facets
  where a channel stands on a terrace (mitigated by the slope fade; a smooth channel distance field is a follow-up).
- R4 done (`HighlandMeanders.buildMouth`): channel positions are emitted first, then a tributary's last stretch
  (6 parent widths, 96–256 blocks, at most 85% of a short stem) is rebuilt as a Hermite from its own channel into a
  straight final run (2 parent widths, 24–64 blocks) that meets a parent channel node at θ: Howard
  cos θ = S_parent / S_trib clamped to 35–90°, pulled 55–85% toward the humid mean 72° by noise, measured against the
  parent's arrival over its last 48 blocks (the same scale as the metric). Candidate junction nodes within 3 parent
  widths downstream / 1.5 upstream of the routed junction (doubled at a penalty) are scored by clearance shortfall from
  the parent channel, crossings of any non-incident parent segment or of another tributary's mouth on the same parent
  (+1000 each), looping (> 150° turning, +1000) and turning beyond 90° (0.2 per degree); both arrival sides are tried,
  junctions within 1.5 × (both widths) of an existing junction and a direct (chord) arrival cost +200 / +100. The old
  junction blend (tributary dragged onto the nearest parent node over 48–256 blocks) is gone.

  | | junction R3 → R4 | < 25° | tip (sharp < 5) | crossings R3 → R4 | build |
  |---|---|---|---|---|---|
  | seed 0 | 93.4 → 73.3° | 0% | 8 (17%) | 0 → 0 | 415 ms |
  | seed 42 | 79.0 → 70.5° | 7 → 0% | 6 (16%) | 0 → 0 | 448 ms |
  | seed 8675309 | 79.3 → 74.3° | 0% | 8 (16%) | 2 → 0 | 365 ms |
  | user | 75.0 → 72.3° | 10 → 0% | 7 (20%) | 2 → 0 | 691 ms |

  Gates added: junction mean 60–80°, ≤ 10% under 25°, 0 crossings. Sinuosity/anisotropy/oxbows unchanged; quality
  gates pass (sunken 0/766/1253/238/315). Tip sharpness (R5 target ≤ 5% < 5) is unchanged by design. Noted for the
  grid-pattern follow-up: faint straight axis-aligned lines in some 1:1 hillshades (user world near 4680, 9860).
- R5 done: `HighlandPlanIndex` lists confluences (refined nodes with two upstream channels, outside lakes) with the
  parent flow direction, the tributary's side, both drainage areas and the angle between the upstream branches (48
  blocks); `carveValleys` applies, within 3 parent widths (24–96 blocks) of each, fading over the outer half:
  - rounded tongue: the wet test uses a polynomial smooth union (fillet 1.2 × the smaller bed radius) of the wet fields
    of the two nearest channels touching the junction; cells inside the fillet hold shallow water of the nearer channel
    (bed 1–3 blocks below it, damped flow along it);
  - scour pool: bed depth × (1 + up to 0.8), scaled by junction angle (30–90°) and discharge ratio, within 1.5 parent
    widths of the junction (Best 1988);
  - bar: an ellipse 1.5 parent widths downstream of the mouth on the tributary side (1.2 × 0.3 widths) lifts the bed
    toward one block below the water (submerged; no new dry land or water steps).

  | | tip width median R4 → R5 | sharp < 5 R4 → R5 |
  |---|---|---|
  | seed 0 | 8 → 13 | 17% → 0% |
  | seed 42 | 6 → 11 | 16% → 0% |
  | seed 8675309 | 8 → 12 | 16% → 0% |
  | user | 7 → 13 | 20% → 0% |

  Gate added: tip median ≥ 8 and ≤ 5% under 5. Junction angles, crossings, sinuosity unchanged; all quality/realism
  gates pass. Sampling cost (user world): R2 21.2 → R4 25.4 → R5 25.8 µs (+22%, within the +25% budget). FULL-chunk
  confluence validation on both loaders is part of R8.
- R6 skipped: direction anisotropy after R5 is 1.7–5.9% (trigger > 8%), so the jittered lattice stays.
- R7 done: `RollingHighlandsSettings` model 4 with `meanders` (0–200, default 100; optional in the codec so a model-4
  preset without it reads as 100). Models 1–3 are rejected with "create a new world". The Meanders slider is on the
  River page (Rolling Highlands section) with en_us label and tooltip; the continent passes meanders / 100 to the
  drainage. Strength ≤ 1 scales the bend amplitude; above 1 more of the network meanders (regime and flatness factors
  raised to 1/strength) and the strongest bends grow up to the 1.9 ceiling. The belt cap now solves for the bend
  amplitude whose loop at its own arc wavelength fits the belt (previously the cap used the target's wavelength,
  which made 200% straighter than 100%). The validation grid-step check mirrors the R1 budget (131 072 nodes).
  Channel length / axis length on seed 0: 1.004 (0%), 1.008 (50%), 1.014 (100%), 1.021 (150%), 1.031 (200%) — seed 0's
  network is mostly steep; the user world winds more (sinuosity 1.34, 6 oxbows). Test: meanders 0 → no oxbows and
  ratio < 1.01, increasing with strength, 200% crossing-free and inside the belt. All windows at 100%: mean 1.29,
  18.1% < 1.1, 15.2% > 1.5 (targets 20% / 15%); default kept at 100.
- R8 done: FULL-chunk water validation on Fabric and NeoForge (default seed 0 at a large confluence and an oxbow; the
  user's exported world at two river sites found with the new `-PftfNearestRiver=true` benchmark option), results
  identical across loaders; the only failures (7 spills at one user site) are vanilla carver breaches beside the
  channel (see VALIDATION.md). IMPLEMENTATION.md and VALIDATION.md updated for RH4; version `1.0.0014-rh4`; NeoForge
  jar deployed to the Prism instance (rh3 jar disabled) and the user's `Test-America-v3-ROLLING.json` converted to
  model 4 (backup `.rh3-backup`). Existing model-3 worlds are rejected with "create a new world" (RH3 decision).
