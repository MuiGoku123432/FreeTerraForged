---
phase: 09-rh9-real-coast
status: complete
completed: 2026-11-06
build: 1.0.0023-rh9
generation_model: 8
commits: [3d4f135c, 69c47e3f, 470c6928, 4f86823b, 7f739e24, f63e8866, 50e3c9e3]
integrated: 515b25b6 (1_21_1-unstable-custom)
upstream: fork-only
---

# Phase 9: RH9 a real coast — summary

RH coasts are no longer 100% beach. A position-only coast profile (`HighlandCoastProfile`) picks beach, bluff or sea
cliff in 300–3000-block stretches from a slow hardness noise, and warps the coastline up to 200 blocks (hard rock out
as points, soft rock in as coves). Regional climate leans the mix and the materials (PNW / California / Baja). Sparse
sea stacks stand off cliff stretches. New worlds only (model 8).

Source of record: [PLAN-RH9 §4](../../../../features/rolling-highlands/PLAN-RH9.md),
[IMPLEMENTATION item 9](../../../../features/rolling-highlands/IMPLEMENTATION.md),
[SPEC-RH9](../../../../features/rolling-highlands/SPEC-RH9.md).

| Plan | Commit | Delivered |
|---|---|---|
| P0 | `3d4f135c` | coast probe (`HighlandCoastTest.reportsCoast`) and model-7 baseline |
| C | `69c47e3f` | model 8, `HighlandCoastProfile`, coastline warp, coves/points; golden hash `ranges-model8.sha256` |
| S | `470c6928` | beach / bluff / cliff shore profiles; plan sees only the beach shift, channels cut gullies through scarps |
| M | `4f86823b` | per-climate cliff rock palettes, PNW gravel beaches (`highlandCoast`, `highlandCoastClimate` on the cell) |
| X | `7f739e24`, `50e3c9e3` | sea stacks off cliff coasts, in the coast's climate rock |
| V | `f63e8866` | gates checked, Review Focus tests (`coastalLakesKeepTheirBanks`, `narrowLandHasNoSeaPits`, `rockiesCoastMeetsGates`), release 1.0.0023-rh9 |

## Decisions made during execution

- Sinuosity measured as path/chord over 2 km windows on traced coastlines; a Cauchy-Crofton ratio was dropped because
  it read ≈ 1 regardless of the coast.
- V moved no constants: `MIN_CLIFF` 20 and an inland terrace cap were both tried and reverted (cliff height is
  land-limited, SPEC A-8).

## Known limits carried forward

- ROCKIES: a scarp top can follow a coastal hill down inland, leaving a 2–10 block dry dip (~7.8% of scarp transects;
  0.9% on the user world).
- Fjords, sounds, offshore islands, arches/sea caves, tidal flats: out of scope (SPEC-RH9 §5; BACKLOG option B).
