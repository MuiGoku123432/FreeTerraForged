---
phase: 09-rh9-real-coast
verified: 2026-11-06
status: human_needed
score: 8/8 automated gates met (as recorded in PLAN-RH9 §4 V; not re-run for this record)
---

# Phase 9: RH9 a real coast — verification

**Phase Goal:** RH coasts read as real coastlines: beaches, bluffs and sea cliffs chosen by a position-only coast
profile, coves and points, coastal materials, sea stacks. Generation model 8.
**Status:** human_needed — automated gates pass; in-game aesthetic acceptance is a manual step.

## Automated gates (`HighlandCoastTest`, `HighlandShoreTest`; model 8)

| Gate | Target | User world | ROCKIES | Result |
|---|---|---|---|---|
| beach / bluff / cliff share | 55 / 25 / 20 ± 8 | 60 / 25 / 15 | 53 / 21 / 26 | ✓ |
| beach width median / p10 | ≥ 18 / ≥ 8 | 29 / 20 | 26 / 20 | ✓ |
| sinuosity | ≥ 1.15 | 1.212 | 1.225 | ✓ |
| cliff height 4 in / depth 8 out | ≥ 15 / ≥ 5 | 15.9 / 12.5 | 30.3 / 25.3 | ✓ (user height just over gate) |
| stretch median | ≥ 300 | 382 | 471 | ✓ |
| hollow / sunk | ≤ 5% user / 0 | 0.9% / 0 | 7.8% / 0 | ✓ (hollow gate is user-world only; ROCKIES is a known limit) |
| column cost (warm) | ≤ +15% vs 10.5 µs | 10.3 µs | – | ✓ |
| stacks per 10 km cliff | recorded | 7.4 | 15.8 | recorded |

Regression: golden hash re-recorded as `ranges-model8.sha256`; `HighlandIntegrationTest` compares against the
lake-resolved plan.

## Human verification required

1. **Coast aesthetics in-game** — new model-8 world on the user preset (around 10109, 18236) and ROCKIES; fly a few
   km of coast. Expect a visible mix of sandy beaches, bluffs with a beach at the foot and sea cliffs into deep water,
   coves and points, no sea pits behind cliffs. *Why human:* gates measure shares and dimensions, not look.
2. **Climate materials** — PNW cliffs dark stone/andesite/tuff with gravel patches; Baja red sandstone/terracotta
   with dunes; California sandstone bluffs.
3. **Sea stacks** — sparse, off cliff stretches only, in the coast's rock, not floating or attached to headlands.
4. **River mouths and creeks** cut cleanly through bluffs/cliffs (gullies), no walls across channel mouths.
5. **Streams Reflowing smoke test** on a model-8 world (standing manual step from IMPLEMENTATION).
6. **Both loaders** — load the 1.0.0023-rh9 build on Fabric and NeoForge.

Run with `/gsd-verify-work 9 --ws rolling-highlands`.
