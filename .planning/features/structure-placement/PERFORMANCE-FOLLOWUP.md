# Further recovery toward the original baseline

Date: 2026-11-01. Parent: `f91b17f7df729c2331424cbbd60d7da80f3785e4`.
Original comparison: `c5076eb8130a28f10e625ed28d5a9d7aceeb7188`.

## Investigation and implementation plan

**Decided:** preserve canonical terrain queries, heightmap/fluid/accessor semantics, filter formulas and ordering, and the full-height structure fallback. No reduction in erosion detail, terrain sampling or structure checks is permitted.

**Rejected:** a height-result cache. Temporary logging found 178 calls but 174 unique `(x,z,type,minY,height)` keys in the seed-42/radius-4 run. That duplication rate does not justify another cache.

A Java Flight Recorder sample of the timed region identified repeated terrain-noise work, including `noise.module.Erosion.getNoiseValue`, and the procedural erosion filter. The original benchmark's shutdown watchdog prevented the first recording from being flushed; a temporary explicit dump before shutdown produced the complete recording. All profiling instrumentation was removed before candidate measurements.

**Decided:** extend the existing per-instance, per-thread erosion-noise scratch to retain the nine drainage-segment endpoints for each octave's current lattice cell. The endpoints depend on lattice coordinates, scale, fixed instance seed and input noise, not the sample's position within the lattice cell. Preserve the original neighbor tie order, point-to-segment math, octave accumulation and floating-point operations. Publish validity only after a complete build; large integer coordinates that cannot be represented exactly by a float key must miss rather than alias. Storage remains bounded by the configured octave count: 40 additional floats (160 bytes) per octave per participating thread and noise instance, plus the existing 25-float scratch. This is a storage calculation, not a measured whole-process memory result. **Assumed:** noise inputs remain deterministic for the lifetime of a worldgen noise instance, as required by the existing noise caches.

**Decided:** evaluate the region erosion filter's independent Step 3 rows in four batches using the existing common-pool/completion-barrier pattern. Snapshot construction, neighborhood operations, final waterline correction and subsequent filters remain ordered. Each worker uses its own existing four-float noise scratch. Restrict this path to `RegionFilterGrid`; retain serial execution for common-pool callers and parallelism at most one. No new executor, library or shared scheduling abstraction is needed.

Local reuse inspection found no existing retained drainage-segment cache; the noise module already owns a thread-local 25-value scratch, and `FilteredChunkCache` already supplies the scheduling pattern. Gortex attempts returned `repo_not_tracked` or checkout-discovery errors, so graph reuse/impact verification remains incomplete.

## Acceptance and verification

- Match pre-change floating-point results across lattice boundaries, negative coordinates, seeds, octaves, scales, blend modes and tied neighbor heights.
- Prove repeated nearby samples avoid rebuilding invariant lattice data; verify invalidation, large-coordinate ownership, concurrency and recovery after an input exception.
- Compare every filtered-cell field against the serial pipeline, retaining existing coordinate/world ownership regressions.
- Run both loader builds and real-loader structure probes, including the three pinned target mods on NeoForge.
- Measure five alternating pairs against the original baseline in disposable worlds. Compare a larger second-seed workload as well. Report remaining gaps without relaxing the earlier release gate.

**Deferred:** full-pack contention, memory/GC budgets, high-relief preset coverage, naturally generated structure census, functional machinery and existing-world seams. These remain release acceptance work.

## Results

### 81 chunks, seed 42 — five pairs

| Pair | Order | Original | Candidate | Candidate throughput / original |
|---|---|---:|---:|---:|
| 1 | original then candidate | 5.620 s | 6.573 s | 85.5% |
| 2 | candidate then original | 5.304 s | 6.345 s | 83.6% |
| 3 | original then candidate | 8.043 s | 6.835 s | 117.7% |
| 4 | candidate then original | 15.575 s | 15.163 s | 102.7% |
| 5 | original then candidate | 6.789 s | 6.364 s | 106.7% |

The descriptive medians are 6.789 s original and 6.573 s candidate, but the ranges span 5.304–15.575 s and 6.345–15.163 s respectively. Observed CPU contention and run-to-run variation prevent a reliable performance ranking from this set. The first two pairs reached roughly 84–86% of original throughput; no samples were discarded.

### 289 chunks, seed 8675309 — three pairs

| Pair | Order | Original | Candidate | Candidate throughput / original |
|---|---|---:|---:|---:|
| 1 | original then candidate | 13.671 s | 16.427 s | 83.2% |
| 2 | candidate then original | 13.097 s | 15.942 s | 82.2% |
| 3 | original then candidate | 13.850 s | 17.097 s | 81.0% |

Median wall time was **13.671 s original versus 16.427 s candidate**, or **83.2% of original throughput** (the median of the individual paired ratios is 82.2%). The candidate range was 15.942–17.097 s. The earlier `f91b17f7` single smoke measurement on this workload was 19.239 s; that historical comparison suggests further recovery but is not a fresh replicated comparison against the previous optimization.

The proposed **95% original-throughput gate remains unmet** on the larger workload. Canonical placement queries still generate more filtered regions than the original height shortcut. Full-pack, high-relief and structure-heavy measurements remain open. Stage timings include waits and additional workers; they are not directly comparable CPU-cost measurements.

Raw reports, source hashes, profile summary and the original erosion oracle are in [performance-followup-2026-11-01](../../research/structure-placement/validation/performance-followup-2026-11-01/). All runs used Fabric, Java 21, a 4 GiB server heap, the default preset, fresh disposable worlds and the existing profiler. No build/test workload was intentionally run concurrently; the original worktree only added loopback binding and an ephemeral port to its benchmark harness.

### Correctness checks

The eight-test suite passed before the paired runs. The 17,328-sample golden digest was captured from the unchanged `f91b17f7` erosion implementation, then asserted after optimization. Additional coverage verifies invariant-work reuse, exception recovery, reordered/concurrent queries, large coordinates, old scratch-array sizing and exact serial/parallel filtered-cell equality. Final verification also passed:

- Fabric and NeoForge builds passed.
- All eight tests passed with normal parallelism, and all eight passed again with `JAVA_TOOL_OPTIONS=-Djava.util.concurrent.ForkJoinPool.common.parallelism=1`. The final normal build reused the passing test outputs from the unchanged source; the serial-fallback run explicitly reran the suite. Timestamps and individual test names are retained in `unit-results.json`.
- Fabric seed 8675309 and NeoForge seed 42 each passed 240 height checks and 2,949,120 block comparisons with zero mismatches.
- The NeoForge 21.1.257 probe loaded Create 6.0.10, Create: Structures Arise 176.49.49 and Giant Natural Additions 2.0.0 together. Pinned jar hashes are retained; no jars are redistributed.
- All sixteen comparison runs completed. The standard probes remain synthetic height/fill checks; natural placement, machinery and full-pack behavior remain unverified.

Reproduce builds/probes with the commands in [PERFORMANCE.md](PERFORMANCE.md). `checks.json` records the exact tasks, properties and serial-fallback environment. The three target jars belong only in the disposable NeoForge benchmark's `mods` directory.

Actual-diff review confirmed two focused production changes, reuse of existing caches/executors, no dependency or codec-format changes, and no terrain-setting changes. Gortex post-change verification remained unavailable, so no graph-derived test or contract coverage is claimed.

Measurement limitation: this is an active developer workstation. A process-name/CPU-only check during the paired runs found a pre-existing Java process (about 21 hours old) consuming substantial CPU, along with desktop/browser activity. It was left untouched. One original-baseline run was markedly slower than its neighbors; all samples are retained. Alternating order and medians limit, but do not eliminate, this noise. These measurements cannot establish an isolated-machine release performance guarantee.

## External research

The [C2ME project release notes](https://github.com/RelativityMC/C2ME-fabric/releases) identify density compilation and aquifer optimizations as active performance work. Their implementation was not imported. The existing FTF router and Minecraft 1.21.1 bytecode were inspected directly; local profiling selected invariant erosion work instead of changing aquifer behavior. No external optimizer or new dependency was installed.
