# Rolling Highlands RH6 spec: honest ground, valley lakes, clean lake junctions, real ranges

Date: 2026-10-29. Status: decisions confirmed 2026-10-30, ready for planning. Builds on RH5 (`1.0.0015-rh5`, model 5, `7dfa204c`).
Research and measurements: [RESEARCH-RH6.md](../../research/rolling-highlands/RESEARCH-RH6.md).

## 1. Problems (user feedback on RH5, root causes in the research)

| # | What the user sees | Root cause |
|---|---|---|
| P1 | Big, shallow, sprawling lakes on flats | Dam lakes accepted on any flat once flat friction (D1) made it one catchment; no depth or confinement test; RH5 S lets water advance onto low unfloored ground |
| P2 | Banding where rivers meet lakes; a river running along a lake behind a thin strip | Lake surface = spill − 1 while channels use the spill level (1-block step at every inlet); shore-parallel channels never absorbed; flat 1–1.5-block rims with stone step edges; lake bed exposes the incision trough |
| P3 | Elevated / canal-like streams, dead-flat plateaus, grid "pillows" | Base-level floor lifts ~48% of all land to a smooth 128-block interpolant of the planning surface; channels drawn between coarse nodes sit on valley sides |
| P4 | Mountains are pokes, not a range | Range adds a smooth 120-block swell under legacy FTF peaks (`mountains_2` worley peaks, plateau-hills) that are 2–4× steeper; incision cuts the range foot; rock/snow only on isolated tops |
| P5 | Speckled ice in `bloomingnature:cold_river` | Freezing rule uses base temperature only; RH water at y 100+ sits on vanilla's height-adjusted freezing line |

Keep: river density, forks, creek widths, meanders, confluences (the user is happy with the rivers), the SR
contract, `cell.rollingHighlands` gating, base FTF untouched, "create a new world" for older models.

## 2. Decisions (confirmed 2026-10-30)

| # | Decision |
|---|---|
| D-a | Range Height 200 in `Test-America-v3-ROLLING` and `-SR` once R lands (slider range unchanged) |
| D-b | New "Ranges replace mountains" toggle, default on when Range Height > 0 |
| D-c | Accepted: natural dry hollows away from water stay; RH tarns only for deep hollows |
| D-d | Spec proposal: total lake area ≈ RH4 (≈ 1.5 km²), mean depth ≥ 6, ≤ 25% of area under 3 blocks |

Original proposals, for reference:

- **D-a Range height:** your preset uses 120. To dominate legacy relief (53–76 blocks locally), ranges need roughly
  180–240. Proposal: keep the slider range and set your preset to 200 after R lands.
- **D-b Legacy mountains outside ranges:** option to scale the legacy `mountains_*` terrain down where there is no
  range, so ranges become where the mountains live. Proposal: a "Ranges replace mountains" toggle (default on when
  Range Height > 0), or you lower the existing Mountains weight slider yourself.
- **D-c Natural hollows:** once the global floor is gone (F), natural legacy hollows away from water stay as they
  are. Some will hold Streams Reflowing lakes; these follow real contours instead of straight-cut floors. Proposal:
  accept that, with RH tarns only for deep hollows.
- **D-d Lake budget:** fewer, deeper valley lakes. Proposal: total lake area near RH4 (≈ 1.5 km² on your
  continent), mean depth ≥ 6, at most 25% of lake area under 3 blocks deep.

## 3. Phases (model 6; one commit per phase, full `:common:test` each)

### F: honest ground (P3; first, because L and R depend on it)
- **F1 Low-envelope planning nodes.** Each routing node samples the legacy land at 9 points (offsets of
  0, ±0.2 step), takes the lowest as its ground and moves its position there (within the existing jitter bound, so
  quads stay convex and links never cross). Routing, incision and the water grid then follow valley bottoms.
- **F2 Thalweg refinement.** Each 16-block valley-axis sample searches ±min(48, 0.3·step) across the axis for the
  lowest unfloored ground and pulls the axis toward it (smoothed along the axis, belt and clearance rules
  unchanged). The water profile is capped at that local low + bed allowance; the existing pass that stops water
  rising downstream keeps it monotone. Streams sit in the valley they appear to drain.
- **F3 Water-relative floor.** Replace `floor = waterFloor + 1.5` everywhere with the maximum of:
  - the sea-level floor;
  - the support cone of nearby channels (already computed in `carveValleys`);
  - the lake shore support (L4);
  - a pit fill only where ground within `FLOOR_REACH` (≈ 64 blocks) of water lies below that water's level.

  There is no global interpolant, so no pillows and no plateau fill far from water.
- **Gates:**
  - land lifted > 2 blocks ≤ 10% (now 48%);
  - stream columns with dry land > 2 blocks below the water within 48 blocks ≤ 5% (now 17%), > 5 blocks ≤ 0.5%
    (now 2.3%);
  - dead-flat share at the screenshot sites ≤ 15%;
  - a new grid-pillow metric: rendered slope minima at plan node positions no more frequent than elsewhere;
  - existing gates hold: sunken, below sea level, water steps, flow, crossings. The depressions gate is
    re-baselined against legacy FTF land and D-c.

### L: valley lakes and clean junctions (P1, P2)
- **L1 Dam-lake acceptance.** Besides "stays inside the basin", a candidate must have:
  - mean flooded depth ≥ max(5, 0.45·dam height);
  - ≤ 25% of flooded nodes shallower than 3;
  - confinement: ground rising ≥ the dam height within 3 nodes either side of the dam;
  - flooded extent ≤ 4 × the FTF lake `sizeMax` setting.

  Rejected sites try a lower dam (existing 1 / 0.7 / 0.5 ladder) before giving up.
- **L2 One lake level.** Lake surface and all channel water inside the lake and in its backwater share one level
  (spill − freeboard), written into the node water grid before the channel profile is built. No 1-block step at
  inlets; the rim still stands a block above the surface.
- **L3 Shore absorption.** A channel that comes within the lake's shore band (planned depth > −6) at or below the
  spill level ends there and enters the lake head-on. The parallel remainder is dropped and its flow is
  reassigned to the lake.
- **L4 Shore and bed shape.**
  - The bed follows the unfloored ground only down to planned depth + 2 (no trench).
  - Shoreline advance is capped at 2 depth units.
  - The flat 1–1.5-block rim is replaced by a beach slope: water + 0.5 rising at 1:4–1:6 into the land. No step
    edge, so no stone stripes.
- **Gates:**
  - lake count and area within ±30% of RH4 on your continent;
  - mean depth ≥ 6; shallow share ≤ 25%;
  - 0 inlets with a water step;
  - 0 channel reaches running parallel within 12 blocks of a lake for more than 32 blocks;
  - no rim ring of constant height (max share of shore columns at exactly water + 1 or water + 2 ≤ 30%).

### I: water ice by height (P5)
- Freezing is judged with vanilla's height-adjusted temperature at the RH water level y, including the worst-case
  noise: `base − (y ± 8 − 80)·0.00125`.
- A river candidate counts as open only if it stays ≥ 0.15 at +8, and as freezing only if it stays < 0.15 at −8.
  Ambiguous candidates are excluded from both lists. The land biome is judged the same way. If no candidate
  remains, fall back to `minecraft:river` / `minecraft:frozen_river`.
- **Gate:** no RH water patch whose biome freezes on some columns and not others (checked on a benchmark world with
  the modded biome set, or by a unit test over the height formula).

### R: real ranges (P4)
- **R1 Range owns its relief.** Inside the range core, legacy detail is damped toward its smooth shape (legacy
  minus its ~512-block low-pass, scaled by 1 − 0.75·core), and range relief takes over. With D-b, legacy
  `mountains_*` weight falls to a fraction outside ranges.
- **R2 Strike-aligned crest.** Crest detail uses ridged noise in strike coordinates, stretched about 4× along the
  strike. Peaks become elongated, joined by high cols; crest height varies ±15%; passes stay as rare deep notches.
- **R3 Spurs and asymmetry.**
  - Flank ridges perpendicular to the strike (ridged noise stretched across the strike), zero at the crest and the
    foot, strongest mid-flank.
  - One flank steeper than the other, switching slowly along the strike.
  - Foothill ridges parallel to the main ridge.
- **R4 Classification.** Mountain biome inputs (weirdness peak slice, low erosion, `MOUNTAIN_CHAIN`) along the
  whole main ridge above a height fraction, giving continuous rock/snow bands instead of isolated tops.
- **R5 Default height:** per D-a.
- **Gates (rendered surface, not the range field):**
  - along-crest height std / crest relief ≤ 0.25 (now 94–215 swing);
  - ≥ 80% of crest length within 25% of the local crest maximum;
  - peak aspect ratio along/across ≥ 2;
  - ≥ 50% of flank ridge directions within ±30° of strike-normal;
  - longest crest ≥ 4 km;
  - crossings 0, including the coastal mouth seen in RH5.

### V: validation and release
- Benchmarks at the screenshot sites and sites A/B/C on both loaders. Compare RH4, RH5 and RH6 maps (water share,
  dead-flat share, elevated share) and do ground-level checks at the same seven spots.
- Re-check Streams Reflowing lakes in natural hollows (D-c).
- Docs, `1.0.0016-rh6`, model 6, deploy to Prism and the server, preset updates (D-a, D-b).

## 4. Risks
- F changes the base of every other phase, so gates must be re-baselined after it. The depressions and sunken
  gates are the ones most likely to move.
- F1/F2 move channels: re-check meander belts, mouths, crossings and the planform metrics (sinuosity, junction
  angles, spacing).
- L3 drops channel length near lakes; the creek density metric may move slightly.
- R1's low-pass of legacy land costs extra samples per column; budget ≤ 10% generation time (measure with the
  benchmark chunks/s).

## 5. Out of scope
Cave-carver pits beside rivers (site C) and the Still Life biome work.
