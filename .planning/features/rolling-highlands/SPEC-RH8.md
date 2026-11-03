# Rolling Highlands RH8 spec: ranges with parallel ridges

Research: [RESEARCH-RH8.md](../../research/rolling-highlands/RESEARCH-RH8.md). Model 7 (no bump; see D-b).

## 1. Problem
RH7 ranges are dissected and glaciated, but each range is one crest line with a foothill apron: a single mountain
line. Goal: ranges that are belts of several roughly parallel ridges with longitudinal valleys between them,
staggered (en echelon) along the strike, with the highest ridge changing along the belt.

## 2. Decisions (confirmed 2026-11-02)
- **D-a Approach A:** sub-crests placed at signed offsets across the existing crest line (one field, ridges bend
  with the range). Rejected: iso-level bands (spacing uncontrollable where the field is flat) and an independent
  second range field (crosses instead of running parallel).
- **D-b Setting, no model bump.** New optional field `rangeRidges` (1-4, default 1). 1 reproduces model 7 exactly,
  so existing worlds and presets are unchanged. The ROCKIES starting profile uses 3. Slider on the Terrain page next
  to Range Height / Range Length.
- **D-c Wider belts.** With 2+ ridges the belt widens ~1.5-2× (to be fixed in tuning). The relief cost gate is
  relaxed to a measured value, recorded with its reason in PLAN-RH8 §4. As built: 1.7× / 2.4× / 3.1× for 2 / 3 / 4
  ridges, with the range field stretched by the same factor so range share and cost hold (PLAN-RH8 §4).

## 3. Phases (one commit per phase, full `:common:test` each)

### P0: measurement
- Golden fixture: hash of rendered heights (including range relief) on a fixed grid for the user continent at Range
  Height 400, ridges 1 (the model-7 baseline the D-b gate compares against).
- `HighlandMountainProbe` cross-section metric: along the strike at belt-core samples, a 4-block transect across the
  belt; count crests with prominence ≥ 0.15 H (above the higher of the saddles on either side). Report share of
  sections with ≥ 2 crests, mean crest count, ridge spacing and its coefficient of variation, saddle depth below the
  lower neighbouring crest (longitudinal valleys), and which crest is highest (index distribution).
- Baseline at ridges 1 recorded first; relief build and heightmap cost at ridges 1.

### R: ridge belt and setting
- **R1 Signed frame.** `RangeFrame` carries the signed across distance (blocks) and the belt half width; ridges 1
  keeps today's half width and code path.
- **R2 Sub-crests.** For r ridges, crest k at offset `(k − (r − 1)/2) · spacing + wander_k`, wander from slow noise
  along the strike (≤ ~0.25 spacing) so ridges drift and overlap en echelon. Each ridge: own presence noise (fades in
  and out over several km; the belt's main presence still switches whole stretches), own crest-height noise; a slow
  noise picks which ridge is highest (others 60-90% of it). Per-ridge profile as the RH7 main ridge with a half
  width so neighbours overlap at roughly a third of their height; combined by smooth maximum, plus the foothill
  apron across the whole belt. Passes per ridge (existing pass noise, per-ridge seed).
- **R3 Core.** `rangeCore` stays ≈ 1 across the inner belt and fades at the belt edge, so legacy damp, biome bands,
  surfaces and the incision fade treat the whole belt as range.
- **R4 Setting.** `RollingHighlandsSettings.rangeRidges` (optional codec field, 1-4, default 1; `validate`;
  `withRangeRidges`), slider and tooltip (en_us), ROCKIES = 3.
- Gates: ridges 1 matches the P0 golden hash exactly; codec: missing field → 1, 0 and 5 rejected, 4 accepted;
  existing RH tests green at ridges 1.

### T: tuning and gates at ridges 3 (Range Height 400, tall frame)
- Belt width factor and spacing tuned against the P0 metric. Targets: ≥ 2 crests in ≥ 60% of belt sections; mean
  saddle depth between neighbouring crests ≥ 0.25 H; spacing CV ≤ 0.35 (as built ≤ 0.45, PLAN-RH8 §4); highest-crest index not constant (no index
  > 70% of sections).
- Kept at ridges 3: water gates 0 (steps, dry jumps, shore cliffs, below sea), crossings 0, longest crest ≥ 4 km,
  median flank slope 30-38°, cirques ≥ 30 per 10 km, tarns 0 spills.
- Recorded (no gate): longitudinal river share inside belts, intermontane lake count, water gaps per belt.
- Cost: relief build and heightmap time at ridges 3 measured; gate set to measured + 15%, with the ridges-1 gates
  unchanged.

### V: validation and release
- In-game on both loaders: a ROCKIES world at 3 ridges, belt cross-section site and an intermontane-lake site (0
  water failures); ridges 1 world at the RH7 screenshot site (4366, 9991) unchanged.
- Overview renders ridges 1 vs 3; ground-level screenshots for the user.
- Docs, next `-rh8` version, deploy to Prism and stage the server bundle; ROCKIES preset (backup) updated to 3.

## 4. Risks
- Relief cost ~1.5-2× at the wider belt (D-c). Fallback: coarser range shape sampling between ridges.
- First-order distance error far from the crest line: ridges drift where the field curves. Fallback: clamp the belt
  where `|∇n|` changes fast along the across direction.
- Closed longitudinal valleys: many small basins/lakes. Fallback: bias outer-ridge passes toward trough low points.
- Neighbouring belts merging where the field folds back; measured, not gated.
- Exact ridges-1 reproduction constrains refactoring of `range` / `rangeFrame`.

## 5. Out of scope
Branching / non-parallel sub-ranges, fold-and-thrust geology, plateau belts (Altiplano), base FTF mountains,
islands (BACKLOG).
