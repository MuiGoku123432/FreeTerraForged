# Rolling Highlands RH8 plan

Spec: [SPEC-RH8.md](SPEC-RH8.md). Research: [RESEARCH-RH8.md](../../research/rolling-highlands/RESEARCH-RH8.md).
Model 7 (optional `rangeRidges`, default 1). Branch `feature/rolling-highlands`. One commit per phase,
`feat(worldgen): RH8 <phase> ...`, full `:common:test` green; never amend or push unasked.

## 1. Decisions
D-a sub-crests at signed offsets (approach A); D-b `rangeRidges` 1-4, default 1 = model 7 exactly, ROCKIES 3;
D-c belt ~1.5-2× wider for 2+ ridges, relief cost gate relaxed to a measured value.

## 2. Order and effort
| # | Phase | Files | Size |
|---|---|---|---|
| 1 | **P0** golden hash + cross-section metric, ridges-1 baseline | `HighlandMountainProbe`, `HighlandRangeTest`, test resource | S |
| 2 | **R** signed frame, sub-crests, core, setting + UI | `HighlandLand`, `RollingHighlandsSettings`, `TerrainSettingsPage`, `en_us.json`, `HighlandSettingsTest` | M |
| 3 | **T** tune belt width / spacing / heights, re-baseline cost gates | `HighlandLand` constants, `HighlandRangeTest` | M (iteration-bound: full suite ~9 min) |
| 4 | **V** in-game both loaders, renders, docs, release, ROCKIES preset | docs, presets | S-M |

Reuse: no new classes expected. Sub-crest profile reuses the RH7 main-ridge profile, `oriented`, `HighlandMath.noise`
/ `smooth` / `smin`-style smooth max; `HighlandRangeRelief` and drainage consume the new shape unchanged. Graph
(Gortex) reuse verification unavailable: repo not tracked; reuse checked by local search.

Estimated production diff ~150-250 lines (mostly `HighlandLand`), tests ~150 lines. Main uncertainty is tuning time
(T) and whether the wider belt needs the relief fallback (SPEC §4).

## 3. Gates
As in SPEC-RH8 §3 per phase. Existing gates keep passing at ridges 1 (bit-identical heights); any gate moved at
ridges 3 gets a measured value and a reason in §4.

## 4. Baseline and as built
### P0 (ridges 1 = model 7)
Golden hash `ranges-model7.sha256` (1024 rendered columns of the user world, Range Height 400, tall frame:
height, terrain, water, river mask, range weight). Cross-section metric `HighlandMountainProbe.ridges`.

| Metric at 400, ridges 1 | Value |
|---|---|
| sections | 180 |
| ≥ 2 crests | 7% |
| mean crests | 1.07 |
| saddle depth / H | 0.40 |
| crest spacing / CV | 664 / 0.18 |
| highest crest in same third | 99% |
| G0 (unchanged) | hw/spacing 1.05, dissection 0.20, slope 36.5°, gentle 11%, crest width 28, convex 44% |

Full `:common:test` green (14 min 30 s).

### R + T (model 7, `rangeRidges` 1-4)
Built:
- **Belt geometry.** The range field is stretched across the strike by the belt factor `1 + 1.4 (r − 1) / 2`
  (r = 3: 2.4×), so a belt is a single range magnified across: belts are wider *and* further apart, the share of land
  in ranges stays about the same, and relief cost stays near the plan cost. One secant step corrects the first-order
  crest distance for r > 1 (without it the inner ridges came out wider and gentler than the outer ones).
- **Ridges.** Crests 1.4 single-range half widths apart, wandering ±0.2 spacing; RH7 main-ridge profile reaching
  0.75 half widths, narrower for lower or fading ridges (down to 40%) so their flanks stay steep; per-ridge presence
  (en echelon), height (60-100% of the locally tallest) and passes; joined by a power mean (p = 6).
- **Troughs.** Each ridge stands on its own foothills, which fall to 15% midway to the next ridge, so longitudinal
  valleys lie near the range foot. Without this the troughs sat at ~0.5 H, trough channels were capped at the
  uplift envelope and the inner flanks were not dissected.
- **Core** stays full across the belt between the outer crests and falls over the outer flanks like a single range.
- **Setting** `rangeRidges` (optional, 1-4, default 1, not written when 1), slider and tooltip, ROCKIES = 3.
- **Wetland fix** (found by the ridges-3 water gate, a latent defect): a wetland pool now also yields to a lower
  reach's *wetland* near its level, not only to its carved valley. Two adjacent pools of different reaches had
  stood 2-3 blocks apart (9 water steps, 30 perched columns in the ridges-3 quality world; 0 / 0 after). Ridges-1 golden
  hash and gates unchanged, but existing worlds can differ where two such pools overlapped.

Measured, user world at Range Height 400, tall frame:

| Metric | ridges 1 | ridges 3 | Gate (ridges 3) |
|---|---|---|---|
| sections ≥ 2 crests | 7% | 84% | ≥ 60% |
| mean crests | 1.07 | 2.09 | ≥ 1.8 |
| saddle depth / H | 0.40 | 0.53 | ≥ 0.25 |
| crest spacing / CV | 664 / 0.18 | 1083 / 0.37 | CV ≤ 0.45 |
| highest crest in same third | 99% | 52% | ≤ 70% |
| crossings / tarn spills / walls | 0 / 0 / 0 | 0 / 0 / 0 | 0 |
| tarns | 36 | 18 | ≥ 10 |
| relief / plan build | 538 / 586 ms | 668 / 612 ms | relief ≤ 1.3 × plan |
| heightmap | 18.6 µs/column | 16.8 µs/column | ≤ 1.15 × ridges 1 |
| quality world (RH 140) water gates | 0 | 0 | 0 |

Gates moved, with reasons:
- (Superseded by T2.) Belt factor 2.4× at 3 ridges instead of the ~1.5-2× in D-c: at 1.85× (spacing 0.85) the ridges overlapped at
  half height, troughs stayed high and only the outer flanks were dissected; at 2.1× (spacing 1.1, reach 0.6) the
  ridges were thin ribbons. The across-stretched field keeps range share and cost, which was D-c's concern.
- Spacing CV ≤ 0.45 instead of 0.35: absent ridges (en echelon ends) double the gap between their neighbours by design.
- G0 metrics (slope, dissection, crest width) are not gated at ridges 3: they sample the band core 0.2-0.8, which with
  a belt covers only the outer flank's foothills (median slope 19°), not the ridges.
- Relief cost gate relief ≤ 1.3 × plan (measured 1.09×); ridges 1 keeps relief ≤ plan.

Visual check (hillshades `ridges-1.png` / `ridges-3.png`, `-Dftf.render=true`): 2-3 ridges per belt with rivers in
the longitudinal valleys, staggered ends; inner ridges dissected but less than the outer flanks. In-game check is V.

### T2: one range with depth (user feedback 2026-11-03)
In game, R+T belts read as separate ranges with wide valleys between them, not one range with more depth.
Changed (ridges > 1 only; ridges-1 golden hash unchanged):
- Crest spacing 1.4 → 0.9 single-range half widths, so the belt factor is 1.45× / 1.9× / 2.35× for 2 / 3 / 4
  ridges (back within D-c's ~1.5-2× for 3 ridges).
- Shared base: each present ridge carries a single range's foothills around it (max over ridges), so the valleys
  between ridges lie high inside the range instead of near its foot; an absent outer ridge narrows the belt instead
  of leaving a foothill plateau with a scarp.
- Shorter ridge segments: presence noise wavelength 1.5 → 0.6 range lengths (band 0.35-0.55), so ridges stagger and
  their valleys drain out through the gaps.
- Secant step bounded: it only shortens the distance (to ≥ 30%) and fades out toward the belt edge. The unbounded step
  pulled in far points where the field turns over and printed straight creases and scarps outside the ranges.
- Test helper `tarnContainment` counts only a tarn's still water (inside its rim, not flowing): with ridges 3 a planned
  river crosses one cirque bowl, and its normal 1-block slope steps had been counted as tarn spills.

| Metric at 400, ridges 3 | R+T | T2 | Gate |
|---|---|---|---|
| sections ≥ 2 crests | 84% | 88% | ≥ 60% |
| mean crests | 2.09 | 2.18 | ≥ 1.8 |
| saddle depth / H | 0.53 | 0.51 | ≥ 0.25 |
| crest spacing / CV | 1083 / 0.37 | 934 / 0.37 | CV ≤ 0.45 |
| highest crest in same third | 52% | 57% | ≤ 70% |
| crossings / tarn spills | 0 / 0 | 0 / 0 | 0 |
| tarns | 18 | 43 | ≥ 10 |
| relief / plan build | 668 / 612 ms | 661 / 723 ms | relief ≤ 1.3 × plan |
| heightmap ridges 3 / 1 | 16.8 / 18.6 µs | 22.6 / 21.1 µs | ≤ 1.15× |
| quality world water gates | 0 | 0 | 0 |
