# RH4 validation record

## Automated (`./gradlew :common:test`)

All RH3 landscape and realism gates pass unchanged. `HighlandPlanformTest` adds, for seeds 0, 42, 8675309 (default
preset) and the user's Test-America-v3 preset:

| | sinuosity | < 1.1 / > 1.5 | anisotropy | junction mean / < 25° | tip width (< 5) | crossings | oxbows | outside belt |
|---|---|---|---|---|---|---|---|---|
| seed 0 | 1.31 | 8% / 14% | 5.9% | 72.0° / 0% | 13 (0%) | 0 | 0 | 0 |
| seed 42 | 1.22 | 28% / 5% | 5.8% | 70.4° / 0% | 12 (0%) | 0 | 0 | 0 |
| seed 8675309 | 1.23 | 19% / 0% | 1.7% | 74.9° / 0% | 12 (0%) | 0 | 0 | 0 |
| user preset | 1.34 | 16% / 21% | 5.5% | 71.9° / 0% | 13 (0%) | 0 | 6 | 0 |

RH3 → RH4 (user world): sinuosity 1.17 → 1.34, axis direction bias 30.3% → ~26%, junction angles under 25°
~27% → 0%, sharp confluence tips 27% → 0%, crossings 4 → 0. Gates: sinuosity 1.15–1.65, anisotropy ≤ 8%,
junction mean 60–80° with ≤ 10% under 25°, 0 crossings, tip median ≥ 8 with ≤ 5% under 5, no channel outside its
belt, oxbows present. `HighlandDeterminismTest.meanderStrengthControlsChannelLength` covers the Meanders setting;
`HighlandSettingsTest` covers model 4 and the rejection of models 1–3. Plan build ≤ 0.8 s on the user continent;
column sampling +22% over the R2 baseline (user world, 262k samples).

## Actual Minecraft blocks (RH4)

FULL chunks (radius 8, 289 chunks) after features and a 40-tick wait, `-PftfValidateHighlands=true`:

| Loader | World / site | Wet columns | Current columns | Flow missing | Spills | Failures | Throughput |
|---|---|---:|---:|---:|---:|---:|---:|
| Fabric | default seed 0, confluence (−6236, 3859) | 32,639 | 21,737 | 0 | 0 | 0 | 23.2 chunks/s |
| NeoForge | default seed 0, confluence (−6236, 3859) | 32,639 | 21,737 | 0 | 0 | 0 | 23.9 chunks/s |
| Fabric | default seed 0, oxbow (−5526, 8884) | 3,825 | 3,172 | 0 | 0 | 0 | 27.1 chunks/s |
| NeoForge | default seed 0, oxbow (−5526, 8884) | 3,825 | 3,172 | 0 | 0 | 0 | 28.1 chunks/s |
| Fabric | user export, river nearest (4551, 9377) → (4391, 9569) | 10,422 | 10,422 | 0 | 0 | 0 | 33.0 chunks/s |
| NeoForge | user export, river nearest (4551, 9377) → (4391, 9569) | 10,422 | 10,422 | 0 | 0 | 0 | 30.5 chunks/s |
| Fabric | user export, river nearest (5300, 13000) → (5172, 12840) | 6,980 | 6,777 | 0 | 7 | 7 | — |
| NeoForge | user export, river nearest (5300, 13000) → (5172, 12840) | 6,980 | 6,777 | 0 | 7 | 7 | — |

Both loaders agree exactly. The 7 spills at the second user site are vanilla carvers: the dry neighbours are planned
at y 72 but the world surface is at 70 with re-grassed cave openings below (air from y 69 down to 58/54 at
(5309–5310, 12828)), the same class as the RH3 user-world "bed not sealed" failures. `-PftfNearestRiver=true`
centres the run on the nearest planned river channel (used because the user export's layout is not the test
fixture's); spill reports now name the neighbour's terrain, zone and planned height.

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
| Inland dry land below sea level | — | 0 |
| Samples > 3 blocks below all water within 24 blocks | — | 105–1,518 (gate ≤ 3,000) |

### Continent realism (`HighlandQualityTest.continentRealism`, seeds 0, 42, 8675309)

| Metric | Target (RESEARCH-RH3 §3) | RH2 | RH3 |
|---|---|---|---|
| Lake area of land | 1–3% (gate 0.5–4%) | 4.3 / 8.1 / 6.1% | 0.6 / 1.0 / 1.7% |
| Closed depressions | few % (legacy FTF land: 20–24%; gate ≤ 6%) | 1.7–2.1% | 2.5–2.9% |
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

## User world check (2026-10-28)

Seed −6240223350076425471 with the user's exported Test-America-v3 noise settings (sea level 62, y −128..768;
surface rule swapped for the default one because the pack references modded blocks), Fabric, 7×7 FULL chunks at
(3888, 9377): water-topped columns 11,591 before the base-level floor, 1,372 after (1,369 planned river columns).
99 river columns fail the "bed sealed" check both before and after: vanilla carvers cut under sea-level rivers and
fill with water below sea level (base FTF does the same).

## Not covered

Ground-level aesthetic review, Streams Reflowing in-game smoke test, long fluid simulations.
