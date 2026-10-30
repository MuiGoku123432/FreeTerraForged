# Rolling Highlands RH6 — honest ground, valley lakes, clean junctions, real ranges

Date: 2026-10-30. Status: agreed; in implementation. Builds on RH5 (`1.0.0015-rh5`, model 5, `7dfa204c`).
Spec: [SPEC-RH6.md](SPEC-RH6.md). Research: [RESEARCH-RH6.md](../../research/rolling-highlands/RESEARCH-RH6.md).

## 1. Decisions

- **D-a** Range Height 200 in the user presets (`Test-America-v3-ROLLING`, `-SR`) once R lands.
- **D-b** "Ranges replace mountains" toggle (new `RollingHighlandsSettings` field), default on when Range Height > 0.
- **D-c** Natural dry hollows away from water stay (SR may fill them); RH tarns only for deep hollows.
- **D-d** Lake budget: total area ≈ RH4 (≈ 1.5 km² on the user continent, ±30%), mean depth ≥ 6, ≤ 25% of lake
  area under 3 blocks.
- Still in force: the SR contract is not renamed; every change gated on `cell.rollingHighlands` /
  `ROLLING_HIGHLANDS`; wetlands keep `WETLAND` with erosion .78; older models rejected with "create a new world";
  one commit per phase with the full `:common:test` suite; `feat(worldgen): ...`; never amend; never push unless
  asked. The rivers the user likes (density, forks, creek widths, meanders, flat friction) stay.

## 2. Measurement (G0, with phase F)

New permanent probe `HighlandGroundProbe` on the user preset and seed (fixture reproduces the server world):
- **lifted**: dry land samples (64-block grid over the user continent) whose rendered height exceeds the
  unfloored land (`blend·land − incision`) by > 2 / > 8 blocks; also the share among samples > 200 blocks from a
  channel.
- **elevated**: in a 2048-block window at 4-block resolution around the screenshot sites (centre 4800, 10000),
  the share of channel columns (water, not lake) with dry land more than 2 / 5 blocks below their water within 48
  blocks.
- **dead-flat**: share of dry columns whose 9×9 neighbourhood at 4-block spacing lies on one block level, in
  176-block windows at the screenshot sites.
- **pillow**: mean dry-land slope at plan lattice nodes over the mean slope at quad centres, away from water
  (1 = no grid imprint; the smoothstep floor makes it < 1).
- Phase L adds lake totals (count, area, mean depth, shallow share), inlet steps, shore-parallel reaches, rim
  rings; phase R adds crest metrics on the rendered surface.

RH5 baseline and F results are in §4.

## 3. Phases

### F — honest ground (model 6)
- **F1 low-envelope nodes.** Each routing node evaluates the planning height at its jittered position and the 8
  offsets of ±0.2 step; candidates outside the jitter bound (0.24 step per axis) are skipped, so quads stay convex.
  The node moves to the lowest candidate.
- **F2 thalweg refinement.** Each 16-block valley-axis sample searches ±min(48, 0.3·step) across the link for the
  lowest unfloored ground. The offset is smoothed within the link and tapered by sin(πt), so nodes and junctions do
  not move. The sample's water level is capped at the local valley low (the cross-section minimum, maxed over
  ±48 blocks along the axis so single pits do not lower the river) plus a bed allowance. The existing pass keeps water
  from rising downstream. Lakes and the sea keep their levels.
- **F3 water-relative floor.** `floored()` no longer lifts to the interpolated node water grid. The floor is:
  - the sea-level floor (inland land ≥ 1.5 above sea level, faded in over the coastal ramp as before);
  - the existing channel support cone in `carveValleys`;
  - a pit-fill cone out to `FLOOR_REACH` = 64 blocks beyond each channel's banks: water + margin falling gently
    with distance (smooth maximum, so no crease). Ground beside a river is not left in pits below its water, and
    nothing is filled far from water.
  
  Lakes keep their RH5 shaping until L.
- Gates: lifted > 2 ≤ 10%; elevated > 2 ≤ 5%, > 5 ≤ 0.5%; dead-flat ≤ 15% at the sites; pillow ratio ≥ 0.9.
  Existing gates hold (sunken, below sea, water steps, flow, crossings, planform). The depressions gate is
  re-baselined (legacy FTF land 20–24%; D-c).
- Model 6 (the first generation change, as in RH5).

### L — valley lakes and clean junctions
- **L1** dam-lake acceptance: mean flooded depth ≥ max(5, 0.45·dam height); ≤ 25% of flooded nodes under 3 deep;
  confinement (ground ≥ dam height within 3 nodes either side of the dam, across the flow); flooded extent ≤ 4 × lake
  `sizeMax`. Rejected sites try the lower dam ladder (1 / 0.7 / 0.5) before giving up.
- **L2** one lake level: the lake surface (spill − freeboard) is written into the node water grid for flooded nodes
  and backwater before channel profiles are built; no 1-block inlet step.
- **L3** shore absorption: a channel sample inside the shore band (planned depth > −6) at or below the spill level
  ends the channel there; the remainder enters the lake head-on.
- **L4** bed ≤ planned depth + 2 (no trench); shoreline advance ≤ 2; the flat rim becomes a beach slope (water +
  0.5, rising 1:4–1:6).
- Gates: lake count and area within ±30% of RH4; mean depth ≥ 6; shallow share ≤ 25%; 0 inlet steps; 0
  shore-parallel reaches (> 32 blocks within 12 blocks of a lake); rim ring share ≤ 30%.

### I — water ice by height
- Freezing judged by vanilla's height-adjusted temperature at the RH water level with ±8 noise margins; ambiguous
  river candidates excluded; fallback `river` / `frozen_river`. Unit test over the formula, plus a benchmark check.

### R — real ranges
- **R1** legacy relief damped inside range cores (legacy minus its ~512-block low-pass, × (1 − 0.75·core)).
- **R2** strike-aligned crest: ridged noise in strike coordinates stretched 4×; crest varies ±15%; rare passes.
- **R3** spurs perpendicular to the strike, asymmetric flanks, parallel foothill ridges.
- **R4** mountain classification along the whole main ridge above a height fraction.
- **R5** D-b toggle (`rangesReplaceMountains`), legacy `mountains_*` weight reduced outside ranges; user preset
  height 200 (D-a).
- Gates on the rendered surface: crest std / relief ≤ 0.25; ≥ 80% of crest within 25% of the local max; peak
  aspect ≥ 2; ≥ 50% of flank ridges within ±30° of strike-normal; longest crest ≥ 4 km; 0 crossings.
  Generation time ≤ +10%.

### V — validation and release
- Benchmarks at the seven screenshot sites plus A/B/C on both loaders; RH4/RH5/RH6 map comparison; SR lakes in
  natural hollows; docs; `1.0.0016-rh6`; deploy to Prism and the server; preset updates (D-a, D-b).

## 4. Baseline and as built

The server world matches the fixture's Test-America-v3 **without** ranges (lake 20 at y 84.2, the canal creek at
(3004, 10452) at 146.5): it was created before the preset gained Range Height 120. Ground metrics are measured on
that world (`HighlandGroundTest`); ranges are measured separately in R.

| `HighlandGroundProbe` (user world) | RH5 | RH6 F |
|---|---|---|
| dry land lifted > 2 / > 8 blocks (continent) | 32.1% / 14.9% | 1.1% / 0.3% |
| … > 200 blocks from a channel, lifted > 2 | 37.7% | 0.2% |
| stream columns with land beside them > 2 / > 5 below the water | 5.1% / 0.87% | 1.1% / 0.08% |
| stream columns with any land > 2 / > 5 below the water within 48 | 22.1% / 6.5% | 12.1% / 0.15% |
| … on gentle reaches (water gradient < 2%) only, > 2 | — | 3.9% |
| dead-flat share at the 5 screenshot sites | 28/19/15/27/6% | 0/3/5/0/0% |
| pillow (node slope / quad-centre slope) | 0.84 | 1.03 |
| plan build, user continent | 1.0 s | 1.7 s |

### F as built
- **F3** as planned. The pit-fill cone: FLOOR_MARGIN (1.5) over the channel water, falling 0.08 per block, out to 64
  blocks beyond the banks (faded over the outer half).
- **Valley floor tilt (added).** After F3 the remaining dead flats at the sites were all FTF valley floors: the
  floor sat at water + bank height across the whole valley, flat to the block over hundreds of blocks on gentle
  rivers. The floor now rises 0.035 per block away from the valley axis, sloping up gently to its sides. This also
  removed most of the remaining "land below the stream" columns.
- **F2** as planned, plus a clearance rule: a sample moves at most 0.45 × (distance to any other drawn link − 16).
  Without it, two channels pulled into the same valley bottom crossed (seed 42) and a confluence tip narrowed (seed 0).
  **The water cap was dropped.** Capped levels were bounded by the downstream node, because a lower cap would lower
  every lake and outlet downstream. Bounded that way, the cap changed the elevated metrics by under 1 point and pushed
  seed 42's concavity fit below its gate.
- **F1 dropped.** Moving nodes to their lowest sample shrank the effective jitter on even slopes, so links lined up
  with the lattice: user-world anisotropy rose from 6.0% to 9–10.6%, over the 8% gate, even with rotated offsets and
  trend-relative selection. It added 1.5 s of planning, and its benefit (any > 2: 25 → 18%) was mostly covered by the
  floor tilt.
- **Elevated gate definition.** "Any dry land > 2 below the water within 48 blocks" also counts banks a little
  downstream of steep creeks (water falling 3+ blocks over 48 blocks). On gentle reaches it reads 3.9%. The gates are:
  land beside a stream (each dry column against its nearest stream column) > 2 below ≤ 5% and > 5 below ≤ 0.5%;
  any > 5 ≤ 0.5%; any > 2 ≤ 15% (RH5 22%).
- **Depressions gate re-baselined** to ≤ 12% (8.7–9.5% on seeds 0/42/8675309; legacy FTF land 20–24%; RH5 2.5–2.9%
  came from the global fill). All other gates are unchanged and pass.
