# Raised-lake diagnosis — 2026-11-04

Issue: [SPEC.md](../../issues/raised-lake-embankment/SPEC.md).
Follow-up: [FIX-RESEARCH.md](FIX-RESEARCH.md) and [implementation plan](../../issues/raised-lake-embankment/PLAN.md).

Latest result: implementation resumed with explicit authorization. Whole-body river constraints, exact local
terrain resolution, compact storage and bounded bank sealing now pass the focused gates: **16 measured lakes,
0.87 km², mean depth 8.0, shallow share 14%, zero inlet steps**, and **32,384 dry-bank edges with zero spills**.
River Count preservation and concurrent/cache-rebuilt generation also pass. Evidence: `resolved-lake-gates.json`.
Historical-layout replay and the eight latest FULL-chunk runs passed: 8,688 chunks, 279,746 wet-column checks,
zero failures. All 22 distinct targeted regression tests pass across the incremental validation stages; both loader
candidates built successfully. Cold startup rose from about 11 to 92 seconds in the paired harness runs.
See [current verification](../../issues/raised-lake-embankment/VERIFICATION.md) for exact stages, artifacts and remaining
performance/full-modpack visual acceptance. No deployment has occurred.
The earlier `resolver-integration-prototype.patch` remains historical evidence, not the current implementation.

The broad compatibility pass subsequently found two genuine regressions (steep shores on seed 42 and a
three-reach confluence step on seed 8675309). `baseline-quality/` confirms the old implementation passes those
metrics. The targeted repair passes all 16 selected tests without changing the gates; results are in
`quality-repair/`. `full-final/` contains the refreshed block checks after that repair; `full/` preserves the
earlier runs and the performance baseline. A final seed-42 FULL run found a post-filter bank spill; `filter-boundary/`
records the failing/passing regression and `full-final/` its successful block recheck. `candidate-build/` contains the
normal-source build log, last five tests and artifact hashes. `passing-test-matrix.json` deduplicates all 22 tests.
`implementation.patch` isolates the nine issue files from unrelated working-tree changes; `implementation-sources.json`
records its source hashes and bases.

The reported lake was reproduced and its predicted surface checked against actual saved server blocks. The lake
pass adds up to 30.50 blocks beneath sampled wet cells and 34.25 blocks at sampled dry banks. The defect comes from
retaining a coarse planned basin after the detailed land and neighboring valley carving have left lower terrain,
then rebuilding the bed and rim to hold the planned water.

## Evidence

- [profiles.svg](profiles.svg): two cross-sections showing the pre-lake surface, lake-shaped surface, live ground and water.
- [measurements.json](measurements.json): comparison counts and maximum-raise sample rows.
- [server-columns.csv](server-columns.csv): 159 actual terrain/water columns from three transects. `-999` means no
  matching block; water below ground can be cave water. The terrain whitelist excludes vegetation and structures.
- [server-build/summary.txt](server-build/summary.txt), [details.txt](server-build/details.txt),
  `server-build/samples.csv.gz`: reproduction with the server revision's mountain layout. Coordinates and all stage
  heights are retained. `water` is the planned basin water, including in its dry shore influence region; `wet` is
  the final cell's water flag. A dry shore sample is not itself lake water.
- `rockies/` and `fixture/`: negative controls on the current source layout and the older no-range test fixture.
  Neither produces this lake. The saved server preset equals the client's Rockies preset semantically.
- [server-preset.json](server-preset.json): exact saved worldgen preset, SHA-256
  `818f4c2e68881e86c5e3230321228aa3f3243fde3502d760a0fc5e478f3b8725`.

User screenshots are in the Prism instance's `minecraft/screenshots/`:
`2026-11-04_11.11.10.png` and `2026-11-04_11.11.31.png`. They were supplied in the conversation; no screenshot or
world-region copy is required for the numerical reproduction.

## Build identity and limits

Working tree HEAD: `9efd661758dbbadff71ccfe304e48c64411faf73`, with pre-existing local changes. Server jar:
`freeterraforged-1.0.0023-custom-c92366ef-biomerules.jar`. The server jar's `HighlandLand.class` SHA-256 is
`a7438e050589ac2d63154be7290586e10426f66d49acb30eb3173bfb8a73e52d`, identical to that class in the local disabled
copy of the deployed jar. The original chunk-generation jar was not recorded in the chunks, so installed-jar
identity alone is not proof of chunk provenance; the measured terrain match is the independent evidence.

The diagnostic overlays **only `HighlandLand` source from c92366ef** in the test classpath. At diagnosis time, the inland lake,
basin and valley methods had no behavioral changes between that revision and the investigated working tree.
The current fix changes those methods; the frozen baseline evidence below refers to the earlier diagnosis.
This is not a boot of the complete old modpack. It reproduces the relevant terrain calculation, and 137/159 saved
ground columns match its floored height exactly. The remaining 22 are 1 or 3 blocks lower. Their later-generation
cause was not individually traced. Lake water is Y=117 in all 39 sampled lake-water columns.

The probe invokes existing private methods reflectively to record their outputs without adding production debug
hooks. It reports terrain after `carveValleys` as `pre_lake`. It does not separately replay the intervening oxbow
pass; the five detailed positions, including both measured maxima, have no oxbow. Cirque tarns are also absent at
those positions. Later candidate instrumentation (`server-terrain/contracts.txt`) confirms basin 4 is a kept
off-network hollow, rather than a dam. `BasinTerrainProbe` also includes the oxbow stage in its later terrain exports.

Gortex does not track this checkout. Graph reuse verification was unavailable; explicitly permitted local searches
found and reused `WorldgenFixture`, `HighlandPlanformProbe`'s seed, the existing plan accessors and generation methods.
No additional dependency or production abstraction was added. No matching EBEN project note was found.

## Re-run the cell diagnostic

From the repository root, with Java 21 and the existing Gradle cache:

```sh
mkdir -p /private/tmp/ftf-lake-server-source
git show c92366ef:common/src/main/java/etcodehome/freeterraforged/world/worldgen/highlands/HighlandLand.java > /private/tmp/ftf-lake-server-source/HighlandLand.java
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
LAKE_SERVER_SOURCE=/private/tmp/ftf-lake-server-source \
LAKE_PRESET="$PWD/.planning/research/raised-lake/server-preset.json" \
LAKE_OUTPUT="$PWD/.planning/research/raised-lake/fixed-server-replay" \
LAKE_EXPECT_REMOVED=1 \
./gradlew :common:test --tests '*LakeEmbankmentProbe.diagnose' \
  -I .planning/research/raised-lake/probe.init.gradle --offline --console=plain
```

`analyze.py` summarizes the frozen **pre-fix** `server-build` evidence. Do not overwrite that directory with a
fixed replay; the current replay belongs in a separate output directory.

The init script opts the research source into the test source set only for this invocation. Do not run the general
test suite with the server overlay. Afterward, remove only its generated test classes (`HighlandLand.class`, `HighlandLand$*.class` and
`LakeEmbankmentProbe.class` under `common/build/classes/java/test/.../highlands/`) so later tests cannot accidentally
inherit the old layout. No main-source or main-class replacement is performed.

To compare the current layout, omit `LAKE_SERVER_SOURCE` after clearing those overlay test classes. Output CSVs
are gzip-compressed in the retained evidence; `analyze.py` reads either plain CSV or its compressed counterpart.

## Server evidence procedure

The server seed was read from the `seed` long in `level.dat`; the preset was extracted from the world's datapack.
The read-only [read_server_columns.py](read_server_columns.py) runs via SSH stdin and emits only terrain/water
heights, terrain/water block names and chunk timestamps for the bounded transects. No world files were modified.
An initial whole-region transfer was rejected by automatic approval review; it was not performed. The approved
bounded extraction supplied all required server evidence without copying a region or exposing entity data.

## Re-run FULL block validation

`server-layout.init.gradle` compiles the historical mountain layout as an opt-in source replacement and selects
the saved preset through the dedicated-server bootstrap in the disposable `build/benchmark-run` config. Example:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
LAKE_SERVER_LAND=/private/tmp/ftf-lake-server-source/HighlandLand.java \
./gradlew :neoforge:runBenchmarkServer \
  -I .planning/research/raised-lake/server-layout.init.gradle \
  -PftfBenchmarkSeed=-6240223350076425471 \
  -PftfBenchmarkX=10112 -PftfBenchmarkZ=11584 -PftfBenchmarkRadius=16 \
  -PftfBenchmarkExact=true -PftfValidateHighlands=true -PftfNearestRiver=false \
  --offline --console=plain
```

Use `:fabric:runBenchmarkServer` for Fabric. Retained-lake centers/radii are `(3664,4096)/24` and
`(9616,4880)/12`. `LAKE_MULTI_RUN=1` keeps this disposable server open with console input enabled; after each
completion, issue `ftf benchmark <radius> <x> <z>`. Copy each performance report before starting the next area.
Use `save-all flush` before reading saved columns. Stop the server after the last validation; if its JVM remains
alive after all dimensions are saved, terminate that test session. Do not run two harnesses on port 25585.

The optional `LAKE_PRESET_FILE` selects a different fixture. `quality-preset.json` is serialized directly from
`HighlandQualityProbe.preset()`; its repaired locations use the **current** HighlandLand source, seeds 42 and
8675309, respectively. No complete datapack exporter is called from the common-only test process.

After all overlays, run a normal compile without any init script before packaging. Remove only the generated
`default-preset-source.txt` and `raised-lake-preset.json` files created by this script in each disposable run config.
Do not leave a historical test source class on the normal test classpath.

## Runs actually completed

1. Current layout, client Rockies preset: diagnostic passed; no lake in the sampled box.
2. Current layout, repository no-range fixture: diagnostic passed; no lake in the sampled box.
3. Server-layout overlay, saved server preset: diagnostic passed; lake 4 and raised bed/rim reproduced.
4. Same overlay with added node/outlet/reach tracing: diagnostic passed; ordinary basin path confirmed.
5. Server terrain-column extraction and local comparison: completed; measurements above.

The initial Gradle init script used an incorrect project lookup and failed before compilation; its isolated script
was corrected, after which the diagnostic runs passed. Later production prototypes and their focused checks are
recorded in the plan. The current production implementation and completed checks are listed in VERIFICATION.md;
the complete remote modpack and in-game visual acceptance have not been exercised.

## Follow-up planning experiments

- `BasinAuditProbe` passed on the unchanged fixture and old server layout. Outputs are
  `fixture-audit/basin-audit.csv` and `server-audit/basin-audit.csv`; 8-block sampling, original plans only.
  Run with the same opt-in init script and environment as above, changing the test filter to `*BasinAuditProbe`
  and using a separate output directory. The research class does not modify any plan.
- `python3 .planning/research/raised-lake/spill_probe.py` passed its open-slope, enclosed-bowl and lowered-saddle
  analytic controls and writes `spill-experiment.json` from the retained server CSV. No production flood solver
  or terrain change is introduced by this Python experiment.
- Cleanup after the audit removes `BasinAuditProbe.class` and the old-layout overlay classes listed above.
  `HighlandLakeContainmentTest` has since been promoted into normal test sources; do not delete that test class.

## Connected-basin experiment and integrated prototype

`BasinTerrainProbe` exports all candidate windows (including exact valley and oxbow stages). Its optional
`LAKE_PRESET` defaults to the repository fixture. Set `LAKE_IDS=4,11` and `LAKE_SAMPLE_STEP=1` to reproduce the
one-block server experiment. Otherwise spacing is four blocks and all candidates are exported. Run it with the
same opt-in Gradle init script, changing the filter to `*BasinTerrainProbe` and using a fresh `LAKE_OUTPUT` directory.
The old server mountain layout still requires the isolated `HighlandLand` overlay.

`python3 .planning/research/raised-lake/resolve_prototype.py <output-directory>` then computes connected bodies
using the existing `spill_probe.escape_levels` implementation. The reported terrain variants are actual pre-lake
ground, `min(raw, pre-lake)` to avoid crediting channel support, and an experimental hydraulic variant. The latter
credits same-level wet channels as barriers; it is an exploratory bound, not a production containment algorithm.
Only components intersecting the original positive-depth footprint and at least four blocks deep are counted.
No generated output is changed by either probe.

- `fixture-terrain/` and `server-terrain/`: metadata, admission/profile contracts, timing, and four-block results.
- `server-fine/`: metadata, timing, and one-block results for reported lake 4 and control 11.
- `contract-instrumentation.patch`: read-only stdout instrumentation of an isolated `HighlandDrainage.java` test
  source copy. Apply in the temporary overlay directory, not to production. It records dam nodes, original ground,
  crest, and each flooded node's keep/structural/drawn flags. Coordinates in DAM records are grid indexes; candidate
  records contain block coordinates.
- `resolver-integration-prototype.patch`: attempted three-file production integration plus its four focused tests,
  based on source snapshots that include the user's unrelated shoreline work. It must not be applied as a finished fix.
- `integration-first.xml`, `integration-repair.xml`, `integration-results.json`: actual failed integration checks.

Large reproducible binary grids from this run were moved to `/private/tmp/ftf-lake-terrain-fields/{fixture,server}`;
the fine-grid export is `/private/tmp/ftf-lake-server-fine`. They are scratch data, not required repository inputs.
To analyze scratch fields alongside retained metadata, copy `terrain.json` into the corresponding scratch directory,
or regenerate with `BasinTerrainProbe` into a new output directory. All retained summaries remain in this directory.
After opt-in runs remove `BasinTerrainProbe.class` as well as old-layout classes. When using admission instrumentation,
also remove exactly `HighlandDrainage.class` and `HighlandDrainage$*.class` from **test** class output, so it cannot
shadow production on later runs. Do not delete similarly named test classes.
