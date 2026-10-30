# Rolling Highlands RH7 spec: dissected mountain ranges

Research: [RESEARCH-RH7.md](../../research/rolling-highlands/RESEARCH-RH7.md). Model 7.

## 1. Problem
RH6 ranges read as "giant sculpted blobs": each range is one smooth ~1.7 km bell with ±40-block decoration, no
valleys between peaks, drainage deliberately shallow on ranges, and mountain biome surfaces covering the whole top
as amorphous rock/snow patches. Goal: Rockies-like ranges. Long chains of sharp crests separated by dendritic valleys,
threshold hillslopes, glacial valleys and cirques above a snowline, and surfaces that follow slope and height.

## 2. Decisions (confirmed 2026-10-30)
- **D-a Glaciation in RH7:** U-shaped main valleys, cirques with tarns at valley heads, arêtes and horns above a
  snowline.
- **D-b Taller ranges, taller worlds.** Range Height setting 0-640 (raised from 240; can go higher later). Rolling
  Highlands presets default to a world top near y 728: world height 864 with depth 128 (top y 735; heights must be
  multiples of 16). Crests approaching the world top are compressed softly within the last 32 blocks below it (no
  flat-topped clipping); the tooltip says so. User presets keep their Range Height until changed.
- **D-e Deep valleys.** Range valleys may cut down to the range foot and below it into gorges; dissection is not
  capped at a share of the range height.
- **D-c Legacy mountains 0%** when ranges replace mountains (RH6 kept 20%) and the legacy chain mask is off.
- **D-d Range surfaces by slope and snowline:** cliff rock on steep ground, scree/talus below cliffs, snow on gentle
  high ground and in cirques, alpine meadow above the treeline, forest below. Only on RH range columns.

## 3. Phases (one commit per phase, full `:common:test` each)

### G0: measurement (with phase D)
`HighlandMountainProbe` on the user continent, ranges 200 and 400, rendered surface:
- **valley spacing / half width** along each crest (transverse valleys = local minima of height along a line parallel
  to the crest at mid-flank): target ratio of half width to spacing 1.7-2.5;
- **dissection depth**: mean (envelope − surface) on range flanks, where the envelope is the 300-block max filter;
  target ≥ 0.4 of range relief (deeper is fine);
- **hillslope angle distribution** on range flanks: median 25-38°, ≤ 10% under 10° (excluding valley floors);
- **crest sharpness**: width of the band within 5 blocks of the local crest height; target median ≤ 12 blocks;
- **convexity**: share of flank columns with positive curvature at 64-block scale; target ≤ 35%;
- **surface**: share of range columns with snow/rock that disagrees with the slope/snowline rule (after S).
RH6 baseline recorded first. Renders of the screenshot site (4366, 9991) at 4 and 16 blocks/pixel, plus a
cross-section strip.

### D: dissection (drainage-first ranges)
- **D1 Uplift, not height.** The range becomes an uplift envelope U(x, z) (RH6 bell, half width as RH6, height up to
  the Range Height). It is not added directly to the surface.
- **D2 Fine drainage in ranges.** Plan nodes inside range cores are refined to 64 blocks (32 if the cost gate allows)
  and solved with the same stream-power model; the RH6 steepness boost is removed, and the incision draws from the
  uplift (channels in a range cut down toward its foot). Range channels join the main network at the range foot; main
  rivers cross ranges only at passes or as gorges (crossing gate stays 0).
- **D3 Threshold hillslopes.** Range surface = min(U, channel elevation + hillslope), with the hillslope rising from
  the nearest channel at a threshold gradient (≈ 0.6-0.7, slightly concave near the channel), from the plan index
  distance. Divides and spurs form where hillslopes from two channels meet.
- **D4 Mid and small scale.** Ridged detail (30-150 blocks) weighted onto hillslopes and crests only; FTF Phacelle
  gullies stay at small scale. The RH6 noise spurs and crest-variation noise are removed.
- Gates: G0 targets for spacing, dissection depth, hillslope angle, crest sharpness, convexity; RH6 range gates
  (crest evenness, longest crest ≥ 4 km, crossings 0); water-contract gates; plan cost ≤ 2× RH6 per continent;
  heightmap time ≤ +15%.

### G: glacial (D-a)
- A **snowline** height (preset setting, default a fraction of Range Height above the range foot, so ranges always
  carry ice).
- **G1 U valleys.** Range valleys whose floor is above the snowline, or whose head is, are widened to a parabolic
  cross-section (width grows with drainage area), floors flattened gently downstream; profiles blend to V below.
- **G2 Cirques.** Channel heads above the snowline get a bowl: steep headwall, overdeepened floor, rock lip; a tarn
  where the floor is closed (existing lake model, depth ≥ 3).
- **G3 Arêtes and horns.** Where cirque headwalls meet, the divide stays knife-edged (no smoothing across it); where
  three or more meet, a horn peak.
- Gates: cirque count per range length, tarn share, U/V ratio (valley width at half depth / depth) above vs below the
  snowline, water gates (tarns contained in game).

### M: mountain-only legacy and height (D-b, D-c)
- Range Height setting 0-640 (codec, UI, tooltip); soft compression in the last 32 blocks below the world top.
- RH presets (default RH profile, user presets) get world height 864 / depth 128; check the vertical frame, noise
  router, aquifers, caves and structure placement still work at that height on both loaders.
- With ranges replacing mountains: legacy mountain weight 0 and chain strength 0.
- Gates: settings codec tests (640 accepted, 641 rejected); no column above the world top minus 1; continuity.

### S: surfaces by slope and snowline (D-d)
- On RH range columns, surface layers chosen by slope (gradient) and height relative to the snowline and treeline:
  cliff rock (steep), scree/talus (below cliffs, moderate slope), snow (gentle, above the snowline; cirque floors),
  alpine meadow (gentle, between treeline and snowline), normal biome surface below the treeline.
- Biome classification (`MOUNTAIN_CHAIN`, weirdness/erosion) follows the same bands, so the mountain biome is only on
  high ground, not the whole range.
- Gates: probe agreement with the rule ≥ 95%; no snow on slopes > 45°; rock patches correlate with slope.

### V: validation and release
- In-game benchmarks on both loaders at the screenshot site and A/B/C plus one cirque site (0 water failures).
- Side-by-side renders RH6 vs RH7; ground-level screenshots for the user.
- Docs, `1.0.0017-rh7`, model 7; deploy to Prism and stage the server bundle; update the ROLLING, ROLLING-SR and
  ROCKIES presets (backups).

## 4. Risks
- Plan cost of the finer range grid. Fallback: 64-block nodes only within the range core, coarse elsewhere.
- Threshold hillslopes next to drawn water: the water-relative floor (RH6 F3) and shore rules must keep holding.
- Main rivers crossing ranges: gorges may need a dedicated rule (deep, narrow, steep walls) instead of passes.
- Height up to 548 exceeds the world top on many presets (crest = foot + H): soft compression handles it, but the
  look depends on world height.
- Surfaces touch biome/surface-rule code shared with base FTF: everything gated on `cell.rollingHighlands`.

## 5. Out of scope
Rock strata/geology, avalanche chutes, glaciers as ice blocks (snow only), base FTF mountains.
