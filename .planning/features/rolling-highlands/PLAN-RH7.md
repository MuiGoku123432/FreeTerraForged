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
(filled per phase)
