# Native latitude specification: edge audit

Generated 2026-10-22 using the installed GSD edge-probe engine and authored behavior-shape classifications. This is a standalone specification audit, not a completed numbered GSD phase.

Coverage: **46/46 applicable cases specified; 0 unresolved taxonomy cases.** These are requirement resolutions, not passed implementation tests. User-selected temperature plus independent latitude moisture is covered; existing moisture formulas are preserved. Candidate/default selection remains an explicit gate in [SPEC.md](SPEC.md).

Inputs: [requirements](edge-requirements.json), [resolutions](edge-resolutions.json). Engine result: [edge-coverage.json](edge-coverage.json).

| Requirement | Category | Status | Specified acceptance obligation |
| --- | --- | --- | --- |
| NL-01 | boundary | Specified; test pending | AC-01/02: missing/disabled/both-zero preserve all climate; each zero-strength axis preserves its own output; both strengths accept [0,1]. |
| NL-01 | precision | Specified; test pending | AC-01: no-op paths preserve raw float bits and quantized climate, not approximate equality. |
| NL-01 | idempotency | Specified; test pending | AC-01/06: repeated queries and toggle off/on never compound latitude or alter legacy seed consumption. |
| NL-01 | concurrency | Specified; test pending | AC-01/06: equivalence uses isolated owner settings; interleaving contexts must reproduce those isolated results. |
| NL-02 | boundary | Specified; test pending | AC-02: inclusive equator/distance limits and both independent strength limits, plus immediate invalid neighbors. |
| NL-02 | empty | Specified; test pending | AC-02: absent whole object means legacy; null, incomplete or malformed present objects fail. |
| NL-02 | encoding | Specified; test pending | Data contract: schema integers and exact resource identifiers; no locale/case folding or fuzzy model matching. |
| NL-02 | precision | Specified; test pending | AC-02/03: integer block distances/positions, finite strength, wide geographic arithmetic; reject nonfinite or overflowing inputs. |
| NL-02 | idempotency | Specified; test pending | AC-02/07: encode/decode and copying preserve the complete effective profile. |
| NL-02 | concurrency | Specified; test pending | AC-02/06: deep copies and immutable snapshots prevent concurrent editor changes altering validation or active owners. |
| NL-03 | boundary | Specified; test pending | AC-03: temperature endpoints/caps and all moisture knots/interval boundaries; both hemispheres, offsets and negative/large coordinates. |
| NL-03 | precision | Specified; test pending | AC-03: actual block/quart/float path tested; 1e-6 geographic tolerance and frozen persisted golden outputs are distinct contracts. |
| NL-04 | boundary | Specified; test pending | AC-04/05: each zero/full axis strength, ground gate, collapsed ranges, and zero cooling/coastal/drying controls retain defined behavior. |
| NL-04 | precision | Specified; test pending | AC-04/13: each axis uses its own range-mapped inputs; frozen blend precision/order; existing temperature and moisture shaping runs once. |
| NL-04 | idempotency | Specified; test pending | AC-04/06: edge retries recompute raw inputs; latitude and existing temperature/moisture shaping never compound prior results. |
| NL-04 | concurrency | Specified; test pending | AC-04/06: owner snapshot supplies shaping inputs; no mutable global reads may change active native results. |
| NL-05 | boundary | Specified; test pending | AC-05: moisture strengths 0 and 1, temperature-only/moisture-only/both modes, ground threshold and existing moisture control extremes. |
| NL-05 | precision | Specified; test pending | AC-01/05: zero moisture strength preserves existing moisture float bits and quantized values, with no extra signed/unsigned conversions. |
| NL-05 | idempotency | Specified; test pending | AC-05: repeated evaluation retains existing regional moisture source/seed and applies existing coastal/elevation operations once. |
| NL-05 | concurrency | Specified; test pending | AC-05/06: unchanged moisture operations use the intended owner settings; latitude does not replace the moisture subsystem. |
| NL-06 | idempotency | Specified; test pending | AC-06: cold/warm cache, eviction, repeated and permuted queries return the same values. |
| NL-06 | concurrency | Specified; test pending | AC-06: two contexts with different seeds/settings sampled interleaved and concurrently match serial controls. |
| NL-07 | idempotency | Specified; test pending | AC-07: repeated save/reopen/export/import preserves explicit values and chosen model, even when defaults change. |
| NL-07 | concurrency | Specified; test pending | AC-07: failed required persistence prevents generation; immutable world snapshot is independent of later client edits. |
| NL-08 | idempotency | Specified; test pending | AC-08: repeated toggles and reopen preserve edits and correct profile identity. |
| NL-08 | concurrency | Specified; test pending | AC-08: cancelled/superseded preview jobs cannot publish over the latest accepted request. |
| NL-09 | idempotency | Specified; test pending | AC-09: a supported owner receives one transformation; repeated compatibility validation cannot stack engines. |
| NL-09 | concurrency | Specified; test pending | AC-09: resolve exclusive ownership before generation; no injection-priority race or global mod disabling. |
| NL-10 | idempotency | Specified; test pending | AC-10: reopening never converts an engine, rewrites stored biome data, or replaces saved profiles. |
| NL-10 | concurrency | Specified; test pending | AC-10/07: active worlds keep their captured profile; missing external dependencies cannot race into native fallback. |
| NL-11 | boundary | Specified; test pending | Evidence protocol: accept 0 through 4096 requested samples; reject larger requests before sampling. |
| NL-11 | adjacency | Specified; test pending | AC-11: retain duplicate coordinates as separate requested records, including points on caps/quart boundaries. |
| NL-11 | empty | Specified; test pending | AC-11: zero-point transect emits an explicit empty record set. |
| NL-11 | encoding | Specified; test pending | Evidence protocol: UTF-8 machine-readable records, exact resource IDs and numeric coordinate fields. |
| NL-11 | ordering | Specified; test pending | AC-11: output follows requested order, including duplicates, irrespective of worker completion order. |
| NL-11 | precision | Specified; test pending | AC-11/03/13: report block/quart positions and stage-specific numeric values; actual sampler comparison uses frozen quantization. |
| NL-11 | concurrency | Specified; test pending | AC-11/06: each export identifies one immutable model/profile; capture actual samples under the same owner, not global editor state. |
| NL-12 | boundary | Specified; test pending | AC-12: zero new terrain lookups or per-sample I/O; five or more paired runs; 95% throughput/110% preview budgets; over-5% baseline variation is inconclusive. |
| NL-12 | precision | Specified; test pending | AC-12: retain raw benchmark measurements and calculate medians/ratios without rounding a failure into a pass. |
| NL-12 | idempotency | Specified; test pending | AC-12/06: repeated sampling allocates no accumulating shared state and preserves query counts/output. |
| NL-12 | concurrency | Specified; test pending | AC-12: control background load and use owned state; noisy benchmarks cannot be certified as a pass. |
| NL-13 | adjacency | Specified; test pending | Evidence protocol: if candidate evidence is tied and extra complexity has no justification, retain cubic and record why. |
| NL-13 | empty | Specified; test pending | AC-13: both temperature candidates and a nonempty matched dataset including latitude-moisture results are required; absent evidence fails. |
| NL-13 | ordering | Specified; test pending | Evidence protocol: comparison rows use stable preset/seed/coordinate/candidate ordering and identical input sets. |
| NL-13 | idempotency | Specified; test pending | AC-13: freeze both target curves and golden fixtures; subsequent changes to either follow explicit model-version rules. |
| NL-13 | concurrency | Specified; test pending | AC-13/06: candidate evaluation uses isolated immutable contexts; model freeze precedes any generation-stable build. |

## Reproduction

With the same GSD installation, run from the repository root:

```sh
node /Users/cfanch06/.codex/gsd-core/bin/lib/edge-probe.cjs .planning/features/native-latitude-climate/edge-requirements.json .planning/features/native-latitude-climate/edge-resolutions.json
```

The engine classifies applicable categories and validates/merges authored resolutions. It does not prove that the prose is correct or that a game implementation passes. If requirements change, regenerate the authored inputs and inspect the resolutions before rerunning.
