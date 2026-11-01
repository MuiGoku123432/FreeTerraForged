# Canonical terrain query performance investigation

Date: 2026-11-01. Control: `744c2f208cd17a33685d498691c71fd42cad56ec`.
Candidate: the raw-region preparation change accompanying this report.

## Cause and change

A fresh seed-42/radius-4 profile reproduced the correctness candidate's slowdown: 9,428.8 ms for 81 target chunks, 4,753.4 ms attributed to structures, and 90 filtered regions. Temporary cache-miss stack sampling identified 55 region requests through aquifer preliminary-surface sampling, 10 direct column queries and 8 subterranean floor-range queries; 17 were other/unclassified callers. These counts describe one run, not a general workload distribution. The temporary logging was removed before timing the optimization.

Canonical queries need the filtered terrain that chunk generation uses. In this sample they request 90 regions instead of the older shortcut's 25. Most cold-region preparation time was spent serially evaluating raw chunks for the 88-by-88 filter grid. Returning raw heights or bypassing aquifer queries would undermine the correctness changes.

The change prepares the same 6-by-6 raw-chunk rectangle in four batches on Java's existing common pool. It waits for every batch before obtaining the caller's thread-local grid, then runs the existing grid copy, biome-envelope population, erosion and other filters, and cache publication. It changes neither the samples nor their filtering order. Calls already running on the common pool, or with common-pool parallelism at most one, retain serial preparation. No new executor, dependency or terrain setting is introduced.

An experimental `ForkJoinTask.join()` wait was rejected after a disposable-server deadlock: a Minecraft worker helped unrelated work from its own pool while waiting for raw preparation, re-entered its own unfinished filtered region, and waited for itself. The final implementation uses `CompletableFuture.allOf(...).join()` over explicitly submitted independent batches. It avoids that fork/join task-help path and waits for all submitted batches even if one fails. The common-pool caller fallback avoids recursively submitting and waiting on that same pool. Raw preparation does not request generated world chunks or filtered regions.

## Measurement method

Five sequential paired runs compare the unmodified correctness candidate (`744c2f20`) with the optimization. Each run starts a new JVM and disposable world, uses Fabric, Java 21, the default FTF preset, seed 42, radius 4, 81 target FULL chunks, a 4 GiB heap and the existing profiler. Run order alternates control/candidate and candidate/control. Benchmark commands and fresh raw reports are retained with the results. No test/build workload was intentionally run concurrently. This is a developer workstation measurement, not an isolated laboratory or full modpack benchmark.

Wall time is the primary metric. Raw preparation now happens on additional workers, so the existing profiler's per-stage/cumulative work totals are not directly comparable CPU measurements. Extra raw-cache hits include the preparation pass followed by grid extraction; they do not prove less terrain computation. Cache sizes and filter extents are unchanged.

## Results

| Pair | Order | Control wall | Candidate wall | Time reduction |
|---|---|---:|---:|---:|
| 1 | control then candidate | 10,307.7 ms | 7,648.5 ms | 25.8% |
| 2 | candidate then control | 9,920.5 ms | 7,608.3 ms | 23.3% |
| 3 | control then candidate | 10,357.1 ms | 7,468.0 ms | 27.9% |
| 4 | candidate then control | 9,871.4 ms | 7,469.8 ms | 24.3% |
| 5 | control then candidate | 10,092.6 ms | 7,164.8 ms | 29.0% |

Median wall time fell from **10,092.6 to 7,469.8 ms (26.0% lower)**. Median throughput increased from **8.03 to 10.84 chunks/s (35.0% higher)**. Every pair improved; all ten runs computed 90 filtered regions. These results compare against the correctness implementation, not the older inaccurate-height shortcut.

Raw reports and the machine-readable summary: [performance-2026-11-01](../../research/structure-placement/validation/performance-2026-11-01/).

A separate single pair used seed 8675309, radius 8 and 289 target chunks. Wall time decreased from **20,917.2 to 19,238.6 ms (8.0%)**, with throughput increasing from **13.82 to 15.02 chunks/s**. This larger sample exercised cache turnover: the control computed 206 filtered regions and the candidate 210, so its work counts were not identical. Cache scheduling/eviction can affect recomputation. This is a single-pair smoke result, not a second replicated performance claim.

Command: `./gradlew :fabric:runBenchmarkServer -PftfBenchmarkRadius=8 -PftfBenchmarkSeed=8675309 --offline --no-daemon`.

## Correctness validation

- Both Fabric and NeoForge builds passed.
- All five JUnit tests passed normally and again with `JAVA_TOOL_OPTIONS=-Djava.util.concurrent.ForkJoinPool.common.parallelism=1`. The new equivalence test compares every public cell field against serial grid/filter evaluation across two seeds, positive and negative regions, all 16 chunks and padding, for both ordinary callers and common-pool callers. The second new test exercises contending cold queries from a separate fork/join pool with bounded waits. Existing density ownership and concurrent-world regressions still pass.
- The Fabric runtime probe (seed 8675309) passed 240 height checks and 2,949,120 block comparisons with zero mismatches.
- The NeoForge 21.1.257 runtime probe (seed 42) passed the same checks with Create 6.0.10, Create: Structures Arise 176.49.49 and Giant Natural Additions 2.0.0 loaded together. Their pinned jar hashes are in `target-mods.json`; no jars are redistributed.
- Twelve final-version benchmark servers (ten paired small runs plus the larger pair) completed without a stall. This is bounded scheduling coverage, not proof against every possible modpack interaction.

Commands/results and probe JSON are alongside the raw timing reports. Reproduce the builds and Fabric probe with:

```sh
./gradlew :common:test :fabric:build :neoforge:build :fabric:runBenchmarkServer -PftfStructureProbe=true -PftfBenchmarkSeed=8675309 --offline --no-daemon
JAVA_TOOL_OPTIONS=-Djava.util.concurrent.ForkJoinPool.common.parallelism=1 ./gradlew :common:test --rerun-tasks --offline --no-daemon
./gradlew :neoforge:runBenchmarkServer -Pneoforge_version=21.1.257 -PftfStructureProbe=true -PftfBenchmarkSeed=42 --offline --no-daemon
```

The NeoForge run requires copies of the three pinned jars in its disposable `build/benchmark-run/mods` directory. Builds used the project's default NeoForge version; only the combined-mod runtime check selected 21.1.257. The runtime checks remain synthetic placement/fill probes, not natural-start, machinery or full-pack validation.

Gortex remained unavailable for this untracked checkout, including the post-change query. No graph-derived verification is claimed. Local diff review, the unit suite and real-loader probes provide the validation evidence.

## Limits

This optimization addresses the scheduling cost of canonical region preparation. It does not remove the additional samples required by correct height/aquifer queries or the full-height fallback for terrain-adapting structures. The old `c5076eb8` single-run result (14.41 chunks/s) remains historical evidence, not a fresh paired control for this optimization. The proposed 95% release-throughput gate against that older baseline remains open.

Shared-pool contention, allocation/GC and peak memory, larger modpacks, high-relief presets, structure-heavy full-height filling, natural placement density and visual/functional acceptance need separate coverage. Exact field equality at the tested coordinates is evidence of preserved filtering, not proof of unchanged output at every coordinate or compatibility with existing world boundaries. The PR remains a draft.
