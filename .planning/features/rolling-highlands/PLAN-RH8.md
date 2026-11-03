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
