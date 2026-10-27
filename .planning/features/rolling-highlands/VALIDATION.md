# RH2 validation record

Date: 2026-10-27. Java 21, Minecraft 1.21.1, build `1.0.0014-rh2`, generation model 2. Servers bind to loopback.

## Automated

`./gradlew :common:test :fabric:build :neoforge:build --offline` passes. Highlights:

- `HighlandQualityTest` (new): five seeds (0, 1, 42, 8675309, −314159), fixed 3072-block window per seed chosen by
  rule (a coarse land sample of the origin continent halfway between coast and core), 4-block maps written to
  `common/build/rolling-highlands/quality-<seed>.png`, plus 12 one-block transects per window. Gates and results:

| Gate | RH1 baseline | RH2 (all five seeds) |
|---|---|---|
| Dry 1-block jumps > 3 blocks | up to 36 per window, worst 61 | 0 (worst step 2.3) |
| 4-block seams (> 12 per 4 blocks) | up to 2,674 | 0 |
| Adjacent wet water steps ≥ 2 | — | 0 |
| Wet cells with lower dry neighbour (perched) | up to 13 | 0 |
| Shore cliffs > 12 within 4 blocks | 1 (76 blocks) | 0 |
| Channel cells with current / currents upstream | stub | 100% / 0% |
| Positive weirdness / peak slice on valley floors | 100% / 13% | 0% / 0% |
| Weirdness slices low/mid/high/peak (legacy 60/33/6/1) | — | 57–61 / 32–36 / 6 / 1 |
| Erosion levels 1–4 (legacy 12/16/52/20), levels 5–6 | — | 11–12 / 18–21 / 49–55 / 16–19, 0 |
| Beaches (legacy ≈ 3%) | ~10% (+ 20% under 5 blocks) | 1.3–1.9% |
| Channel length exactly on grid directions | ~62% of reaches | 6.5–8.3% |
| Valley zone share of dry land | 94–95% | 7–68% |

- Plan builds: 364-plan bounds sweep (52 seeds × 7 scale/spacing pairs) and owner plans build without fallback;
  river-less fallback plan validated.
- Determinism (parallel/reversed order, rebuild, owner isolation), filtered chunk boundary agreement, settings
  persistence (model 2; model 1 rejected with guidance) and frozen legacy UPLIFT/MULTI_IMPROVED fingerprints pass.

## Actual Minecraft blocks

Seed 0, default RH2 preset via datapack (see RH1 reproduction steps; preset exported to
`common/build/rolling-highlands/preset.json`), radius 1 (9 FULL chunks), after features and a 40-tick wait.
Checks: water/ice (or a feature's solid block, never air) at the planned surface, solid bed, no source water
above the surface, flow-field byte present for every current, dry neighbours solid at the water surface.

| Loader | Site | Wet columns | Current columns | Flow missing | Spills | Failures |
|---|---|---:|---:|---:|---:|---:|
| Fabric | confluence + wetland (5343, 5789) | 1,103 | 1,022 | 0 | 0 | 0 |
| Fabric | lake outlet (6559, 6918) | 531 | 531 | 0 | 0 | 0 |
| NeoForge | confluence + wetland (5343, 5789) | 1,103 | 1,022 | 0 | 0 | 0 |

Throughput on these diagnostic runs: 5.2 / 5.1 chunks/s (Fabric), 4.7 chunks/s (NeoForge). Unfiltered column
sampling costs about 1.8 µs for RH vs 9.7 µs for UPLIFT on the same machine.

Found during validation and fixed: single-block surface steps used a flowing top block that drained when no
upstream source fed it (RH now keeps a source riffle). Boulder features in shallow wetland pools are accepted.

## Not covered

Ground-level aesthetic review, SR + FTF in-game smoke test (manual), long fluid simulations, V3 comparison.
