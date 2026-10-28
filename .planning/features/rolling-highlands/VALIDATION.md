# RH3 validation record

Date: 2026-10-28. Java 21, Minecraft 1.21.1, build `1.0.0014-rh3`, generation model 3.

## Automated

`./gradlew :common:test :fabric:build :neoforge:build --offline` passes. Frozen legacy UPLIFT/MULTI_IMPROVED terrain
fingerprints are unchanged after extracting `Heightmap.makeLand`.

### Landscape gates (`HighlandQualityTest.landscapeQualityGates`, seeds 0, 1, 42, 8675309, −314159)

| Gate | RH2 | RH3 |
|---|---|---|
| RH-induced dry 1-block jumps > 3 | 0 | 0 |
| 4-block seams | 0 | 0 |
| Adjacent wet water steps ≥ 2 | 0 | 0 |
| Perched water (wet next to lower dry, excl. ocean) | 0 | 0–2 (river mouths) |
| Channel cells with current / upstream currents | 100% / 0% | 100% / ≤ 0.1% |
| Positive weirdness / peak slice on floors | 0 / 0 | 0 / 0 |
| Weirdness slices low/mid/high/peak (legacy 60/33/6/1) | 57–61/32–36/6/1 | 64–66/28–29/5–6/1 |
| Beaches | 1.3–1.9% | 1.8–2.7% |
| Grid-aligned channel length | 6.5–8.3% | 4.5–5.9% |

### Continent realism (`HighlandQualityTest.continentRealism`, seeds 0, 42, 8675309)

| Metric | Target (RESEARCH-RH3 §3) | RH2 | RH3 |
|---|---|---|---|
| Lake area of land | 1–3% (gate 0.5–4%) | 4.3 / 8.1 / 6.1% | 1.3 / 2.5 / 1.6% |
| Closed depressions | few % (legacy FTF land: 20–24%; gate ≤ 12%) | 1.7–2.1% | 8.5–8.8% |
| Channel length cut > 60 below uncarved land | low (gate ≤ 25%) | 30–35% (gorges through unseen mountains) | 7–11% (planned valleys) |
| Junction angle | ~72° humid (gate 50–95°) | 41–46° | 69–86° |
| Concavity (water surface) | 0.3–0.6 (gate 0.15–0.65) | 0.07–0.12 | 0.19–0.28 |
| Bifurcation ratio | 3–5 | 3.8–5.4 | 3.9–5.3 |
| Hack exponent | ≈ 0.6 | 0.71–0.85 | 0.85–0.94 (not gated) |

Streams Reflowing contract (`HighlandStreamsContractTest`): 0 hidden water, 0 phantom river cells, 0 surface
mismatches, 0 zero-magnitude currents on seeds 0 and 42.

## Actual Minecraft blocks

Seed 0, default RH3 preset via datapack, radius 1 (9 FULL chunks), after features and a 40-tick wait:

| Loader | Site | Wet columns | Current columns | Flow missing | Spills | Failures | Throughput |
|---|---|---:|---:|---:|---:|---:|---:|
| Fabric | confluence (10891, 13166) | 1,435 | 1,435 | 0 | 0 | 0 | 5.2 chunks/s |
| Fabric | dam-lake outlet (6205, 6166) | 1,496 | 1,496 | 0 | 0 | 0 | 4.0 chunks/s |
| NeoForge | confluence (10891, 13166) | 1,435 | 1,435 | 0 | 0 | 0 | 4.5 chunks/s |

Column sampling with climate: ~2.2 µs for RH3 vs ~9.7 µs for UPLIFT on this machine.

## Not covered

Ground-level aesthetic review, Streams Reflowing in-game smoke test, long fluid simulations.
