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

### M (model 7)
Built: Range Height 0-640 (codec, slider, tooltip); soft compression in the last 32 blocks below the world top
(asymptote 4 below it); choosing Rolling Highlands in the World page raises a lower world to top y 735 / depth 128;
legacy mountain weight and chain strength 0 under ranges (D-c). Range relief at heights above 200 scales the channel
gradient with Range Height (steady state S ∝ U), and the plan's stream-power incision fades out toward the range
core (it was computed on the uncarved range and stacked on the relief, leaving flat-floored troughs at 400). Planner:
a tributary whose own path crosses its parent above the mouth (the parent meandered over it) starts its mouth beyond
the crossing (1 crossing in the user world after D-c).

Gates: codec 640 accepted / 641 rejected; at 640 in the tall frame the highest column is y 591 (top 735, no column
above top - 1); RH400 morphology locked in (below). Plan cost gate now best of five builds (parallel-suite noise).

Range Height 400, tall frame (SPEC targets are judged here, user decision):

| Metric | RH7 at 200 | RH7 at 400 | Target |
|---|---|---|---|
| half width / valley spacing | 1.36 | 0.91 (spacing 947) | 1.7-2.5 |
| dissection | 0.23 | 0.15 | ≥ 0.4 |
| median flank slope | 25.4° | 30.4° | 25-38° (met) |
| flank < 10° | 19% | 14% | ≤ 10% |
| crest width | 32 | 36 | ≤ 12 |
| convex share | 46% | 44% | ≤ 35% |
| crest / foot | 204 / 76 | 263 / 71 | — |

At 400 the slope target is met; spacing, dissection, crest width and convexity are not. Valleys space out at 400
(steeper channels need more area to stay below the threshold slope) and relief reaches only ~190 of the 400 (the
threshold hillslope caps how far divides rise above channels). Follow-up before G: denser channel heads and a
height-scaled hillslope at 400, then re-measure.

### M tuning at Range Height 400
For h = Range Height / 200 (≥ 1): channel steepness × h² (the range stands h times taller over the same width;
real active-range steepness indices are well above the 200 value), threshold hillslope × √h (cap 1.2), routing
roughness × h at wavelength / h. Range Height ≤ 200 is unchanged. Swept on the 400 probe (hillslope exponent 0-0.7,
channel exponent 0.5-2.5, channel-head area ×1/8-×1, roughness): channel-head area had no measurable effect on
spacing; channel steepness drives relief; finer, stronger roughness tightens spacing a little.

| Metric at 400 | M commit | tuned | aim | SPEC target |
|---|---|---|---|---|
| crest / foot | 263 / 71 | 402 / 72 | relief ≥ 300 (met) | — |
| valley spacing | 947 | 840 | < 700 (not met) | hw/sp 1.7-2.5 |
| dissection | 0.15 | 0.21 | ≥ 0.25 (not met) | ≥ 0.4 |
| median slope | 30.4° | 37.7° | — | 25-38° (met) |
| flank < 10° | 14% | 11% | — | ≤ 10% (close) |
| crest width | 36 | 28 | — | ≤ 12 |
| convex | 44% | 44% | — | ≤ 35% |

640 in the tall frame now reaches the compression band (highest column y 732, top 735). Remaining gaps: the
dissection metric (max within a 300-block window) is bounded by hillslope × 150 / Range Height, about 0.3 at 400 with
these slopes, so ≥ 0.4 needs slopes over 45°; valley spacing is set by how the routing organises basins, not by the
channel-head area. Both are left to G (U valleys and cirques deepen and widen valley heads) and re-measured there.
Gates at 400 now: slope 30-38°, gentle ≤ 13%, crest width ≤ 36, dissection ≥ 0.19, relief ≥ 300.

### G results (glacial overprint)
- **Snowline setting** `snowline` (percent of Range Height above the range foot, 20-100, default 60; 100 = off),
  optional in the codec, slider on the terrain page.
- **G1 U valleys:** channel nodes fed from above the snowline carry a glacier to 25% of Range Height below it
  (weight fades over that reach). Their cones rise from a U floor: half width 16·√(area/10⁴) (≤ 120 blocks), a
  parabolic floor rising 25% of the half width, then the threshold walls; floors are overdeepened by 35% of it.
- **G2 cirques:** at the node where each glacial valley first reaches 6·10⁴ blocks² of drainage, a horseshoe bowl
  centred upslope (radius 40% of Range Height, 60-200 blocks): gentle floor, headwall at gradient 1.6, open
  downvalley. **Tarns deferred:** cirque floors are not closed, so no dry pits; tarns need the lake model to see the
  relief (planned with S or a later pass).
- **G3 arêtes/horns:** divide smoothing between glacial sources is cut by 80%; horns emerge where cirques meet.
- Measured at 400 (default snowline vs off): 164 cirques on the continent, 51 per 10 km of measured crest (gate ≥ 30;
  off = 0); crest 376 vs 402 (glacial lowering), slope 36.4° vs 37.7°, spacing 828 vs 840, dissection 0.20 vs 0.21.
  The probe metrics barely move: the G0 metrics sample flanks, not valley floors. U/V ratio is not measured
  (needs a cross-section probe); visual check on the overview render shows scalloped amphitheatres along the crest.
- Relief cost 560 ms vs plan 593 ms (gate relief ≤ plan; little headroom left).

### S results (surfaces by slope and snowline)
- Cell carries `highlandRange` (range weight), `highlandSnowline` and `highlandTreeline` (snowline = range foot +
  Snowline% of Range Height; treeline 25% of Range Height below it).
- **Biomes:** the RH6 crest classification (uncarved range height) is replaced by bands on the final carved height:
  above the treeline toward vanilla's mountain-slope slice (MOUNTAIN_CHAIN), above the snowline toward its peak
  slice; valleys cut below the treeline keep the land's classification.
- **Surfaces** (`HighlandSurface.choose`, applied in `ScreeFeature` on RH range columns, slope from cell heights ±2
  blocks): cliff rock ≥ 45° (≥ 50° below the treeline), scree 33-45° above the treeline (gravel, andesite,
  cobblestone, tuff), snow block on gentler ground above the snowline, grass meadow between treeline and snowline,
  biome surface below. Edges jittered ±6 blocks.
- Measured at 400 (range columns, 16-block sampling): cliff 40% (mean 52°), scree 22% (40°), snow 4% (23°),
  meadow 8% (22°), biome 26%; mountain biome columns above the treeline 100%; no snow above 45°.
- Relief cost: priority flood now uses the Barnes pit queue and the hillslope Dijkstra skips re-evaluating a node
  from its own source (relief 545 ms vs plan 588 ms).
- Needs the in-game check (V): the surface path runs only with the scree decorator on.

### G2 tarns (follow-up to G)
- Cirque floors are overdeepened by 12 blocks and closed downvalley by a rock lip rising 2 blocks above the head
  floor. Each cirque gets a local priority flood (window 1.5 × cirque radius); a closed depression at least 3 deep
  at its centre becomes a tarn at its spill level less 1.
- Columns: the absolute water level is resolved once per tarn at its centre (land under the range + level), so the
  surface is flat. Inside the footprint (bilinear node weight > 0.15) ground below the water floods, bed ≥ 1 (up to 5
  toward the middle) below; at the footprint edge, low ground is raised to one block above the water. Shorelines
  follow the ground's contour.
- At 400: 36 tarns from 162 cirques (22%), 0 spills (every water column's 8 neighbours are water at the same level or
  dry at least at the water surface); relief 538 ms vs plan 586 ms. Gate: ≥ 10 tarns, 0 spills, none without glaciation.
