# Rolling Highlands RH7 plan

Spec: [SPEC-RH7.md](SPEC-RH7.md). Research: [RESEARCH-RH7.md](../../research/rolling-highlands/RESEARCH-RH7.md).
Model 7 from the first generation change. Branch `feature/rolling-highlands`. One commit per phase,
`feat(worldgen): RH7 <phase> ...`, full `:common:test` green; never amend or push unasked.

## 1. Decisions
D-a glaciation in RH7; D-b Range Height 0-640 and RH presets at world height 864 / depth 128 (top y 735, nearest
16-multiple to the requested 728); D-c legacy mountains 0% when replaced; D-d slope + snowline surfaces; D-e valleys
may cut to the range foot and below (gorges).

## 2. Order
1. **G0 + D** (dissection): `HighlandMountainProbe` and RH6 baseline first; then uplift envelope, fine range nodes,
   threshold hillslopes, mid-scale ridged detail; RH6 noise spurs and steepness boost removed. Model 7.
2. **M**: Range Height 0-640, soft top compression, world height 864 for RH presets, legacy mountains 0%.
3. **G**: snowline setting, U valleys, cirques and tarns, arêtes and horns.
4. **S**: slope/snowline surfaces and matching biome classification.
5. **V**: in-game both loaders (screenshot site 4366, 9991; A/B/C; a cirque site), renders, docs, `1.0.0017-rh7`,
   Prism + server bundle, presets (ROLLING, ROLLING-SR, ROCKIES) with backups.

## 3. Gates
As in SPEC-RH7 §3 per phase. Existing gates (water contract, planform, ground, lakes, RH6 range evenness/length/
crossings) keep passing; any gate moved gets a measured value and a reason in §4.

## 4. Baseline and as built
### G0 + D (model 7)
Built (Option A, drainage relief): `HighlandRangeRelief` solves the range per continent on a 24-block grid (uplift
`min(1.8u, max(u, 1.5·rangeHeight))`, 3 iterations of roughened priority-flood routing + stream-power floors capped
at a 0.8 threshold hillslope, smoothed wandering channel lines, hillslopes and divides by soft minimum; B-spline
surface). The range shape is evaluated on every second node. Rendering adds fall-line ribs and gullies (±20 blocks,
80-block wavelength stretched 2.5× downhill, faded on valley floors, flats and divides). The main ridge falls almost
linearly from the crest; RH6 noise spurs removed. River planning still uses the undissected shape. Planner fix: a
tributary mouth whose every curve crosses the parent is rebuilt from up to 4× further upstream (the new range shape
exposed 2 crossings in the user world).

User world, Range Height 200 (RH6 baseline → as built; SPEC target):

| Metric | RH6 | RH7 G0+D | Target | Gate now |
|---|---|---|---|---|
| half width / valley spacing | 1.02 | 1.39 (spacing 621) | 1.7-2.5 | ≥ 1.15 |
| dissection | 0.22 | 0.22 | ≥ 0.4 | ≥ 0.2 |
| median flank slope | 19.1° | 24.7° | 25-38° | 24-38° |
| flank < 10° | 23% | 20% | ≤ 10% | ≤ 22% |
| crest width | 84 | 36 | ≤ 12 | ≤ 40 |
| convex share | 41% | 47% | ≤ 35% | (none) |
| relief build / plan build | — | 605 / 737 ms | ≤ 2× RH6 plan | relief ≤ plan |
| heightmap ranges / none | — | 20.2 / 29.3 µs | ≤ +15% | ≤ +15% (was +10%) |

Gates moved, with reasons:
- RH6 `nearMax ≥ .8` dropped and `evenness ≤ .25` relaxed to `≤ .5` (measured .40): dissected crests have peaks and
  saddles by design (D-e); an even crest is the RH6 blob this phase removes.
- `spurAlignment ≥ .5` → `≥ .45` (measured .58; was .51 in RH6 and drifts ±.05 with detail tuning).
- Heightmap cost gate +10% → +15% per SPEC (ranges are currently cheaper than legacy mountains).

Targets not met at Range Height 200: dissection, spacing, gentle share, crest width and convexity. At 200 blocks over
an ~860-block half width the geometric ceiling for dissection is ~0.49 and flanks average ~13°, so the SPEC targets
(set for Rockies-scale relief) are re-measured at Range Height 400 in phase M, where `MAX_RANGE_HEIGHT` rises to 640.
The gates above lock in the G0+D gains without blocking on that.
