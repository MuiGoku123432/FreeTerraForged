# Raised-lake fix — implementation and verification

Date: 2026-11-04. Status: implementation retained; 22 targeted tests and eight FULL checks passed; Fabric and NeoForge candidates built. Cold-start performance and full-modpack visual acceptance remain open. No deployment.

## Cause and resulting behavior

The reported lake was a coarse, off-network hollow admitted as a lake even though the detailed valley did not
contain its planned water surface. Rendering then raised the bed and banks to enforce that outline. This created
the elevated sheet and tall stone embankment. The recorded candidate was not an artificial-dam candidate.

Ordinary lakes now resolve against the actual land, valley and oxbow surface before the owner plan is published.
A connected spill calculation establishes their footprint and level; the existing river-profile solver imposes
whole-body ceilings before equality pins. The final renderer only excavates beds. At immediate dry banks it may
seal at most two blocks; larger gaps lower and re-resolve the body. Candidate discovery and river geometry stay
fixed throughout resolution.

Fine membership samples every interior block plus a local exterior margin. Unresolved exterior/foreign-owner
space is open drainage. Temporary flood fields are discarded; packed wet row spans count against the existing
8 MiB index budget. Original candidate IDs survive removals, and oxbow levels follow their source channel nodes.
No dependency, preset option or save-format change was introduced.

## Reproduction evidence

Seed: `-6240223350076425471`. Preset: saved Test-America-v3 with ROCKIES and Rolling Highlands. The installed
server used `c92366ef`; this checkout has a newer mountain layout. The opt-in replay overlays only that historical
`HighlandLand` source, so the fix is tested at the original terrain rather than letting a changed layout hide it.

- Original candidate 4: planned water Y=117.3574; detailed ground roughly Y=84–95. Measured bed/bank raising
  reached 30.50/34.25 blocks. Maximum natural retention was below the existing four-block lake-depth policy.
- Fixed replay: all 1,200 positive samples in the old candidate footprint have no ordinary lake. Eight other
  bodies remain, with no overlapping membership. Retained body data is 389,576 bytes; full owner-plan wall time
  was 6,373 ms, measured externally around plan construction.
- Saved NeoForge FULL blocks: all 39 sampled former lake-surface columns lose the Y=117 sheet; ground is
  14–28 blocks lower. The lower-stream controls at (10004,11600) and (10008,11600) retain ground Y=85/84 and
  water Y=86 exactly. Of 159 sampled ground columns, 157 match the predicted integer surface; two have later
  surface/feature differences of one and four blocks. All 159 saved Fabric columns match NeoForge exactly
  after ignoring chunk-generation timestamps.

Evidence: [fixed replay](../../research/raised-lake/fixed-server/),
[saved-block comparison](../../research/raised-lake/full/neoforge-site/comparison.json).

## Automated checks

Focused gates passed: 16 lakes, 0.87 km², mean depth 8.0 blocks, 14% shallow area, no stepped inlets or
shore-parallel channels; 32,384 dry-bank edges checked with zero spills. The original quality thresholds remain.
The River Count 7/3 preservation contract and existing 30-second concurrent-plan timeout passed. Synthetic
controls cover a supported bowl, an open slope, low exterior terrain beyond a divide, an outlet only one block
wide, exact integer membership, carving-only beds and bounded bank sealing.

Evidence: [focused results](../../research/raised-lake/resolved-lake-gates.json).

The final normal-source build passed all five remaining tests: continent realism (seeds 0, 42 and 8675309),
both planner tests and both solver tests. Together with the repair and filtered-bank checks, **22 distinct targeted
tests pass**. This is incremental validation, not a claim that the entire repository test suite ran in one invocation.
The [test matrix](../../research/raised-lake/passing-test-matrix.json) identifies each case's evidence and code stage;
[final test/build results](../../research/raised-lake/candidate-build/results.json) preserve the last five results.

## Broader compatibility finding and repair

The first broad run passed 19 of 20 tests, including continent realism, ground-height gates and Streams flow
metadata. The landscape test detected 19 steep shore samples on seed 42 and eight water steps on seed 8675309.
The same test passes with the pre-fix lake implementation, confirming both findings are regressions.

The shore case lost the old dry-bank excavation along with the unsafe filling behavior. The repair adds a local
excavation-only shore that fades out over 32 blocks; it never expands water membership or relaxes the two-block
fill bound. The water steps occur where a tributary meets a reach one node before its downstream reach boundary.
The existing rapid rule reached only directly adjacent reaches. It now propagates through the locally connected
reach chain, with the same nonnegative distance penalty and gradient, so a bookkeeping boundary does not create
a different water level.

Evidence: [first broad run](../../research/raised-lake/final-checks/results.json),
[passing baseline](../../research/raised-lake/baseline-quality/test.xml),
[exact failing columns](../../research/raised-lake/quality-locations/),
[river-contact trace](../../research/raised-lake/quality-locations/channel-contacts.xml).
The targeted repair passed all **16** selected tests, including the unchanged five-seed landscape gate, the new
junction regression, lake/ground/count/determinism/Streams gates and every tested bank edge. Lake metrics remain
16 lakes / 0.87 km² / mean depth 8.0 / 14% shallow; all 32,384 dry-bank edges still contain water.
[Repair results](../../research/raised-lake/quality-repair/results.json).
The six FULL checks below were refreshed after the shore/confluence repair. Saved columns at the reported site
remain identical to the earlier fixed run and identical across loaders.

The additional seed-42 block run exposed two real spills after filtering, at ice-water columns (-1545,-1746)
and (-1546,-1745). The raw bank was valid, but later erosion/relaxation lowered it; those dry columns had no
channel-bank classification and were unprotected. The focused filtered-bank test reproduced the failure.
Immediate dry lake banks now use the existing `erosionMask` contract, preserving the already validated barrier
without adding fill or changing water membership. The filtered-bank regression, all fixture bank edges and the
existing filtered-region boundary test pass. The two additional FULL runs passed on this final protection change;
the six historical-layout rows below precede only that narrow filter-mask addition.
Evidence: [failing FULL run](../../research/raised-lake/full-final/neoforge-quality-shore-first/server.log),
[failing filtered regression](../../research/raised-lake/filter-boundary/before.xml),
[passing filtered regression](../../research/raised-lake/filter-boundary/after.xml).

## FULL Minecraft chunks

The first six disposable local runs use the saved preset and historical mountain layout. The final two use the
serialized quality-test preset and current mountain layout to exercise the discovered regressions. The
existing harness generates FULL chunks and waits 40 ticks before checking actual water/ice, bed and side
containment, missing flow data, and excess water. These runs include vanilla feature and cave processing.

| Loader/site | FULL chunks | Wet columns | Flow columns | Missing flow / spills / failures | Chunk generation |
|---|---:|---:|---:|---|---:|
| NeoForge reported site + lower stream | 1,089 | 4,393 | 4,393 | 0 / 0 / 0 | 27.91 s |
| Fabric reported site + lower stream | 1,089 | 4,393 | 4,393 | 0 / 0 / 0 | 29.06 s |
| NeoForge retained lake 0 | 2,401 | 77,281 | 25,203 | 0 / 0 / 0 | 65.60 s |
| Fabric retained lake 0 | 2,401 | 77,281 | 25,203 | 0 / 0 / 0 | 64.71 s |
| NeoForge retained lake 1 + outlet | 625 | 42,351 | 10,514 | 0 / 0 / 0 | 18.30 s |
| Fabric retained lake 1 + outlet | 625 | 42,351 | 10,514 | 0 / 0 / 0 | 18.98 s |
| NeoForge iced shore, seed 42 | 289 | 24,593 | 0 | 0 / 0 / 0 | 14.77 s |
| Fabric confluence, seed 8675309 | 169 | 7,103 | 7,103 | 0 / 0 / 0 | 9.71 s |

Total: **8,688 FULL chunks and 279,746 wet-column checks**, zero failures.
[Machine-readable results](../../research/raised-lake/full-final/validation-summary.json).
Logs and performance reports: [final FULL evidence](../../research/raised-lake/full-final/). The three areas
per loader share one disposable server; retained-lake timings are warm runs. Each server saved all dimensions
after `stop`; its lingering test JVM was then terminated. The six validator results completed before shutdown.
The two additional runs shut down automatically and both Gradle invocations completed successfully.

The first retained-lake run exposed two validator false positives: solid terracotta had replaced planned water
and already separated the adjacent water from a dry hole. The validator now checks side containment only where
the actual top block contains water or ice. Missing-water/solid, bed, flow and excess-water checks remain intact.
The corrected complete run passed. [Bounded block inspection](../../research/raised-lake/full/neoforge-lake0-first/diagnosis.md).

## Performance

Same historical mountain layout, saved preset, Java 21, NeoForge and 4 GiB heap; one baseline and one fixed
reported-site run, each generating 1,089 FULL chunks:

| Measurement | Before | Fixed |
|---|---:|---:|
| Fresh-world server startup | 11.138 s | 91.564 s |
| Target chunk generation after startup | 30.083 s | 27.912 s |

**Cold planning is materially slower**: startup adds about 80 seconds in the final run. The earlier fixed run took
99.046 seconds to start and 34.208 seconds for chunks; the final run took 91.564/27.912 seconds. Chunk generation
after startup is comparable across these runs; the cold-planning regression is consistently substantial. These are sequential single measurements with the user’s game also running, not
a controlled speed ratio. The startup cost must not be hidden by reporting only the chunk interval or partial
`Plan.buildNanos`; the latter omits final bank-check time. The externally timed owner replay was 6.37 seconds.
The existing concurrent-plan timeout and memory gates pass, but this is not a performance-neutral change.
The separate final quality-preset runs took 169.359 seconds (seed 42) and 176.526 seconds (seed 8675309) to start.
Those different-fixture measurements have no paired baseline and are not compared to the 11.138-second server replay.

[Raw comparison](../../research/raised-lake/full/performance-comparison.json). Further cold-planning optimization
is a follow-up before treating this build as a routine server upgrade.

## Review candidates

Both loader builds succeeded with version `1.0.0023-rh7-lakefix-candidate`, without a research init script:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :common:test \
  --tests '*HighlandQualityTest.continentRealism' \
  --tests '*HighlandPlannerTest' --tests '*TreeProfileSolverTest' \
  :fabric:build :neoforge:build \
  -Pmod_version=1.0.0023-rh7-lakefix-candidate --offline --console=plain
```

- [NeoForge candidate](../../../neoforge/build/libs/freeterraforged-1.0.0023-rh7-lakefix-candidate.jar)
- [Fabric candidate](../../../fabric/build/libs/freeterraforged-1.0.0023-rh7-lakefix-candidate-fabric-1.21.1.jar)
- [Artifact sizes, SHA-256 hashes and provenance](../../research/raised-lake/candidate-build/artifacts.json)
- [Successful build log](../../research/raised-lake/candidate-build/gradle.txt)

Both packaged jars contain the resolved-body and final bank-protection code. Normalized `javap` bytecode for
`HighlandLand` matches the normal current-source compile on both loaders; the common source jar also matches the
current terrain/resolver/controller sources. No historical-layout overlay or diagnostic test class is packaged.
The two temporary benchmark preset-selector files were removed from each disposable loader configuration.
The build reports an existing deprecated biome-noise API warning and Gradle deprecation warnings; neither fails it.

## Scope and remaining acceptance

The test worlds contain the loader and FreeTerraForged, not the complete remote modpack. The separate Streams
contract check covers exported flow metadata. An in-game visual review on the complete server pack has not been
performed. No remote server or saved user chunks were changed. Existing generated chunks require a separately
planned regeneration/repair to change; installing a generator fix only affects newly generated terrain.

Unrelated shoreline, biome and climate work already present in this checkout is preserved. Build artifacts from
this checkout include those working-tree changes. Historical test source overlays must be followed by a normal
compile before packaging; they are not production source changes.

The [issue-only review patch](../../research/raised-lake/implementation.patch) contains the five production files,
the quality-probe adjustment and three regression-test files. For `RollingHighlandsContinent`, its base is the
pre-issue working-tree snapshot containing the separate shoreline work; the other tracked files use HEAD.
The patch passes `git apply --reverse --check` against this working tree. The
[source manifest](../../research/raised-lake/implementation-sources.json) records hashes and those bases.

General cross-candidate merging is not added: the resolver retains the largest supported connected component per
candidate. Fixture and server-replay membership checks reject overlaps; those checks do not prove all possible
seeds. Bounded constraint reconciliation fails visibly when it does not converge.

Implementation choices narrowed from the initial plan: detailed resolution does not reroute meanders or discover
replacement candidates; it preserves geometry and updates the associated node profiles, contacts, outlets and
oxbow levels. It conservatively treats the bounded exterior as open instead of repeatedly expanding a candidate
window, and retains one connected component per candidate. These choices passed the recorded preservation gates;
they are not claims of a general multi-basin hydrology solver.
