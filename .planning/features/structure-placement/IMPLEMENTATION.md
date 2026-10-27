# Structure placement correctness — implementation checkpoint

Date: 2026-10-27. Branch: `feature/structure-placement`.
Worktree: `/private/tmp/ftf-structure-placement`.
Base: `c5076eb8130a28f10e625ed28d5a9d7aceeb7188`, matching the installed custom baseline. The separate Rolling Highlands branch is not included.

**Status: correctness candidate, not release-ready.** Core sampling and fill regressions are reproduced and addressed. The first performance smoke comparison is substantially slower in wall time. Advanced placement profiles, natural-template air handling, root-specific blending and optional foundations remain unimplemented and disabled. Full visual, functional and placement-density acceptance remains outstanding.

## Implemented behavior

- FTF `getBaseHeight` delegates to Minecraft's density-column scan so the heightmap predicate, fluid state and accessor bounds are honored. The old scaled terrain height answered every heightmap type identically.
- Density-column queries obtain filtered terrain on a cold cache, without requesting generated world chunks. Interpolation outside a pinned chunk reads the coordinate-owning filtered chunk, including filter-region boundaries; it cannot use the adjacent region's padded copy as authoritative data.
- Existing jigsaw support/river checks and the cell structure rule read the same filtered terrain. Their thresholds, retries and ownership rules are unchanged.
- Filtered-cache generation retains the requested array through publication/eviction, propagates failed generation to waiting callers, and does not count replacement entries twice.
- FTF chunks with a referenced terrain-adapting structure use full configured-height noise filling. Structure-free/`none` cases retain the existing cap. The selection matches vanilla Beardifier's adaptation predicate; it conservatively includes starts whose pieces may ultimately contribute nothing. Non-FTF generators avoid the extra structure lookup.
- An explicitly enabled runtime probe compares canonical columns and full-height noise output. It runs inside the real loader/mixin environment, writes JSON, requests server shutdown and returns a failed process/task for failed or incomplete checks. Gradle deletes stale reports first. Normal gameplay does not activate it.

Terrain noise, erosion/filter algorithms, river paths, biome settings, world limits, structure sets and spawn-rate settings were not retuned. Correcting query inputs and interpolation ownership can change newly generated terrain/placements at affected coordinates. This is not a claim that new chunks are byte-identical to the old buggy implementation, or that seams with existing worlds have been validated. No instance files or existing user saves were edited.

## Reproductions and evidence

Evidence files are in [validation](../../research/structure-placement/validation/).

1. Before the density sampling changes, both cold-query equality and coordinate ownership tests failed. They passed after using the canonical filtered owner. A third test checks concurrent cold queries across two seeds, reversed query order and cache clears. This is cache-clear coverage, not a full concurrent eviction/reload/cancellation stress test.
2. The original fill cap omitted blocks created by structure density in a controlled high rigid-piece fixture over low terrain:

   | Terrain adjustment | Blocks differing from full-height fill |
   |---|---:|
   | `NONE` | 0 |
   | `BURY` | 3,328 |
   | `BEARD_THIN` | 537 |
   | `BEARD_BOX` | 277 |
   | `ENCAPSULATE` | 5,888 |

   See `fabric-fill-regression-before.json`. This is a synthetic regression, not a claimed reproduction of a specific naturally placed Giant/Additions structure. The fixture includes a rigid piece and junction; chunk cells are explicitly initialized as they would be before the NOISE stage. An earlier exploratory run omitted that initialization and bypassed the optimization; its result is not retained as evidence.
3. With the full-height fallback, version-2 probes passed 144 height checks and 983,040 block comparisons on Fabric and NeoForge 21.1.219, seed 42. Height checks compare both cold and warm calls against a full column for all six predicates, eight coordinates and three height accessors.
4. A further NeoForge 21.1.257 run passed with the installed Create 6.0.10, Create: Structures Arise 176.49.49 and Giant Natural Additions 2.0.0 jars, including bundled dependencies. See `target-runtime-manifest.json` and `neoforge-target-mods-seed42.json`. The newer test loader is required by the installed Create Structures artifact (minimum 21.1.229); the project's default loader version was not changed. This is a combined-mod runtime smoke test, not full-pack or functional-machine validation.
5. Harness version 3 expands coverage to empty accessor intersections above and below configured terrain, and three chunk positions for each of the five blending modes: 240 height checks and 2,949,120 block comparisons per run. The expanded runs passed on Fabric (seed 8675309) and NeoForge 21.1.257 with the three target mods (seed 42); both reported zero mismatches. See `fabric-expanded-seed8675309.json` and `neoforge-target-mods-expanded-seed42.json`. Both loader builds and all three JUnit tests passed; see `build-and-unit-results.json`.

The direct JUnit fixture uses a test-only Architectury registry binding. Only transformed server probes validate mixin execution. The synthetic structure managers intentionally do not validate real reference creation, NBT template population, entities or structure serialization.

## Performance concern — release gate remains open

One sequential, matched Fabric smoke pair used seed 42, default preset, radius 4, 81 FULL target chunks at continent center `(101033, 91995)`, Java 21, 4 GiB server heap and the existing profiler. Both runs touched 121 full chunks and 169 noise chunks. Baseline was a separate detached worktree at `c5076eb8`; its only harness change bound the server to loopback and an ephemeral port. No concurrent benchmark was deliberately run.

| Metric | Original baseline | Correctness candidate |
|---|---:|---:|
| Wall time | 5,621.5 ms | 9,494.6 ms |
| Target throughput | 14.41 chunks/s | 8.53 chunks/s |
| Cumulative structure-stage work | 353.0 ms | 5,113.4 ms |
| Filter regions computed | 25 | 90 |
| Cumulative measured thread work | 25,473.7 ms | 21,056.7 ms |

The candidate's throughput was approximately 59% of the baseline in this small run. Increased early filtered-region work and lower parallelism are consistent with the slowdown; attributing all of it to one call site requires profiling. Moving work from BIOMES into structure queries also makes individual stage timings misleading. The cap skipped the same number of sky cells in both runs, so this sample does not measure a structure-heavy full-height fallback workload.

This pair is too small for a release performance conclusion, and it does not pass the proposed 95% throughput gate. Preserve the correction while investigating canonical-query cost and scheduling. Do not restore raw/cache-dependent height answers or silently relax the gate. At least five paired runs, larger/structure-heavy workloads, memory accounting and full-pack measurements remain required before a performance-preserving release claim.

Raw reports: `fabric-baseline-performance-smoke.txt`, `fabric-candidate-performance-smoke.txt`.

## Reproduce

Run from this worktree with Java 21 and the project's dependencies available:

```sh
./gradlew :common:test :fabric:build :neoforge:build --offline --no-daemon
./gradlew :fabric:runBenchmarkServer -PftfStructureProbe=true -PftfBenchmarkSeed=8675309 --offline --no-daemon
./gradlew :neoforge:runBenchmarkServer -PftfStructureProbe=true -PftfBenchmarkSeed=42 --offline --no-daemon
./gradlew :fabric:runBenchmarkServer -PftfBenchmarkRadius=4 -PftfBenchmarkSeed=42 --offline --no-daemon
```

The benchmark tasks recreate **their own** `fabric/build/benchmark-run/world` or `neoforge/build/benchmark-run/world`. They are destructive to those disposable test worlds only. Probe JSON is written to `<loader>/build/benchmark-run/perf-reports/structure-probe.json`. Standard performance reports use `ftf-perf-latest.txt`. Do not point the diagnostic server at an existing user world: it clears caches, evaluates detached chunks and exits the process.

For the target-mod check, place copies of the three pinned jars in `neoforge/build/benchmark-run/mods` and use `-Pneoforge_version=21.1.257`. They are not committed or redistributed. Remove/move these test copies when running the project's default 21.1.219 absent-target-mod control, because Create Structures requires a newer loader. The initial 21.1.257 offline attempt lacked its userdev jar; a subsequent normal Gradle invocation fetched the dependency and completed.

## Remaining work and next gate

The SPEC acceptance checkboxes intentionally remain open: each covers more than these bounded regressions.

- Resolve/quantify the performance concern before promoting this candidate. Measure cold query sites and canonical region generation separately from full-height structure fill.
- Add final-stage terrain/fluid-envelope checks, actual eviction/reload/order tests, additional terrain modes/high-relief presets, seed strata, non-FTF controls and old/new chunk boundaries.
- Capture natural-start census and fixed viewpoints for the target IDs, plus vanilla village/trail ruin/monument/subterranean controls. Validate direct Create structure sets, machinery, entities, processors and save/restart.
- Implement exact-ID/versioned profile resolution only after the correctness gate is established. Profiles require explicit reviewed support, clearance, void and influence metadata; do not infer root support from giant canopy bounds.
- Address Create's airborne clearance and Giant Additions' natural-template air/root blending as separate opt-in stages. Preserve upstream cancellation and full piece/junction/reference state. Cover the 144-block oak's rotated reference extents.
- Optional foundations remain off until geometry, visual, river-preservation and valid-density gates pass.

Gortex diagnosis/impact/post-change calls returned `repo_not_tracked`; no graph-derived test selection or contract result is claimed. Native source review, unit tests and real-loader probes supply the evidence for this checkpoint.
