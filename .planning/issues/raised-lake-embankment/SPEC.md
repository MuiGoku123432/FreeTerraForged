# Raised lake embankments from disagreement between planned and rendered terrain

Status: implementation retained; 22 targeted regression tests passed; eight FULL checks passed (8,688 chunks). Fabric and NeoForge candidates built. Cold-start performance and full-modpack visual acceptance remain open; not deployed.
Date: 2026-11-04
Severity: high visual/world-generation defect; existing server chunks are affected.
Evidence and reproduction: [research](../../research/raised-lake/README.md), [cross-sections](../../research/raised-lake/profiles.svg), [measurements](../../research/raised-lake/measurements.json).
Implementation: [PLAN.md](PLAN.md), supported by [follow-up research](../../research/raised-lake/FIX-RESEARCH.md). Current results: [VERIFICATION.md](VERIFICATION.md).

## 1. Problem and intended outcome

At approximately X=10112, Z=11500–11700 in the user's Test-America-v3-ROCKIES world, a long lake stands above the
valley on steep stone embankments. A separate stream runs along the lower valley floor. This is real generated
terrain, confirmed by reading saved server block states; it is not an inference from shaders or distant LODs.

As a player, I should find lakes occupying basins in the surrounding terrain. Lake generation must not manufacture
a long elevated platform and retaining walls to preserve a water level that the final terrain cannot contain.

## 2. Reproduction identity

| Item | Verified value |
|---|---|
| World | Remote `mcserver`, `/home/cfanch06/server/fnf/world`, overworld |
| Seed | `-6240223350076425471`, read from actual `level.dat` and corroborated by `server.properties` |
| Saved preset | World datapack `freeterraforged-preset.zip`, `data/freeterraforged/freeterraforged/worldgen/preset/preset.json`; retained as `server-preset.json` |
| Installed server jar | `freeterraforged-1.0.0023-custom-c92366ef-biomerules.jar` |
| Highlands | model 7; spacing 6144; upland height 120; valley depth 120; valley width 448; meanders 80; range height 400; range length 6144; replace mountains true; snowline 60; ridges 3 |
| Lake | Basin ID 4 in continent owner `(0,0)`; planned water Y=117.3574 |
| Plan resolution | 128 blocks; four flooded lattice nodes at X=10112 and Z=11392,11520,11648,11776 |
| Source screenshots | `2026-11-04_11.11.10.png` (camera 10153,158,11366); `2026-11-04_11.11.31.png` (camera 10128,161,11762) |

The current checkout at `9efd6617` plus pre-existing local changes has a different mountain layout from the
installed server. Running the same seed and saved preset on that layout produces no lake at this site. This
did **not** establish a fix: the unsafe lake-shaping operations were still present in the baseline. Diagnosis
used the server revision's `HighlandLand` in an isolated test overlay, retaining the unchanged inland lake/valley
logic, and compared its output with saved server blocks. See the research for the overlay's precise limits.

## 3. Measured evidence

All heights below are world Y, not height above sea level. Model values are continuous; saved block heights are integers.

| Position X,Z | Before lake shaping | After lake shaping | Height added | Server ground / water |
|---|---:|---:|---:|---|
| 10080,11500 — west bank | 87.3557 | 117.7061 | **30.3504** | 117 / no lake water |
| 10112,11500 — lake bed | 88.1769 | 102.3891 | **14.2122** | 102 / 117 |
| 10112,11600 — lake bed | 90.5153 | 106.8030 | **16.2877** | 106 / 117 |
| 10104,11356 — maximum sampled dry-bank raise | 84.3346 | 118.5860 | **34.2514** | Not included in the three server transects |
| 10120,11408 — maximum sampled wet-bed raise | 86.6903 | 117.1875 | **30.4972** | Not included in the three server transects |

- Probe: 50,451 cells on a 4-block grid, X=9700–10500, Z=11000–12000. Maxima are sampled maxima, not exhaustive block-level extrema.
- Server: 159 columns, X=10000–10208 every 4 blocks at Z=11500,11600,11700, read from region `r.19.22.mca`.
- Ground matches `floor(predictedHeight)` in **137/159** columns. Ten saved columns are one block lower and twelve
  are three blocks lower. The cell probe excludes later block generation, cave and feature passes; it does not
  establish which later pass accounts for each difference.
- All **39** sampled columns containing the lake's surface water have their highest water block at Y=117.
- The five detailed diagnostic positions have no oxbow and no cirque tarn. This is the ordinary `HighlandBasins`
  / `shapeLake` path. Subsequent admission instrumentation confirms a kept off-network hollow: all four flooded
  nodes have `kept=true`, `structural=false`, `drawn=false`; no channel outlet is recorded. A fix only in `damLakes`
  would not address this case. See `server-terrain/contracts.txt` in the research artifacts.

## 4. Confirmed cause in the pre-fix code

The planner and the renderer disagree about the terrain that must contain the lake, and the final lake pass
resolves that disagreement by adding terrain without a bound on the amount of fill.

1. `HighlandDrainage` evaluates and accepts basins on a coarse, jittered planning grid. `HighlandBasins` derives
   their water levels and an interpolated depth field from that surface. Here, four flooded nodes in one grid
   column produce the elongated, nearly straight-sided footprint.
2. `RollingHighlandsContinent.groundBeforeTarns` evaluates detailed land, dissection and incision, then runs
   `floored` and **`carveValleys` before `shapeLake`**. The actual surface can be lower than the planned basin,
   and neighboring low rivers can lower it further. For example, at (10112,11500), raw ground is 99.3521 and
   valley carving lowers it to 88.1769. At (10112,11600), raw ground is already 90.5288 although the planned lake
   bed is about 108.8030. Range dissection is only 0.0190 there, so it is not the primary cause at that location.
3. `shapeLake` adjusts shoreline depth from **`water - raw`**, where `raw` precedes valley carving, rather than
   validating containment on the supplied post-valley `height`. It also limits adjustment relative to the planned
   depth. It therefore cannot reliably reconcile either the detailed-land mismatch or the later valley cut.
4. The wet branch returns **`water - Math.min(bed, 16)`**, replacing the incoming terrain height. It can raise
   the bed dramatically; there is no fill-budget or basin-validity check.
5. The dry branch uses **`Math.max(shaped, lerp(shaped, beach, ...))`** to raise the rim toward the lake surface.
   There is no cap on this raise either. The narrow support band turns the level mismatch into stone walls.

This is a basin-validity and stage-composition defect. Broadening or texturing the shoreline would retain the
invalid elevated lake. Removing its rim alone would leave uncontained water. Limiting bed depth alone would not
repair the basin's water level, footprint or neighboring river relationship.

Relevant existing code:

- `HighlandDrainage`: `planningHeight`, depression selection, `hollows`, `damLakes`, `lakeShape`, `confined`.
- `HighlandBasins`: basin water/depth/shore fields, `sample`, recorded outlets.
- `RollingHighlandsContinent`: `groundBeforeTarns`, `carveValleys`, `shapeLake`.
- `HighlandLakeProbe` / `HighlandLakeTest`: existing lake metrics and acceptance gate.

## 5. Required behavior and acceptance criteria

The user authorized implementation on 2026-11-04. The following defines the fix contract; it is not a claim that
these checks pass today.

| ID | Requirement | Acceptance |
|---|---|---|
| RL-01 | Evaluate ordinary lake validity against the terrain that will surround and support it, including overlapping river-valley carving. | The reproduced site cannot retain the Y=117 lake over the Y≈84–95 valley through artificial bank/bed fill. Trace pre-lake and post-lake surfaces, not just final water depth. |
| RL-02 | Resolve an invalid basin coherently. Prefer lowering/recomputing its water and footprint when a contained basin remains; otherwise omit it. | One stable water surface per surviving lake; no per-column water-level clamps, floating sheets, exposed side water or dry slices through the lake. A rejected lake leaves the underlying terrain and valid river drainage intact. |
| RL-03 | Bound artificial fill used solely to support ordinary lake water. | Proposed tolerance: at most **2 blocks** above the pre-lake terrain for residual rim sealing, and no positive bed fill solely to keep a lake within its 16-block depth limit. Any intentional natural dam must be explicitly identified, localized and separately tested; it cannot exempt an entire lake perimeter. |
| RL-04 | Preserve hydrological relationships when lake levels or membership change. | Valid inlet/outlet joins retain matching levels and downstream non-ascent; the neighboring lower stream is neither buried nor raised to the invalid lake. A closed basin may remain without a channel outlet only if actual terrain contains it. |
| RL-05 | Preserve valid lakes and deterministic generation. | Existing correctly contained lakes retain their behavior outside the necessary affected basin/reaches. Same seed/preset yields the same basin decision and surface across cache rebuilds and chunk-generation orders. No recursive plan-build dependency or chunk-order-based water decision. |
| RL-06 | Add regression coverage that observes the missing property. | Extend existing lake diagnostics to record post-valley/pre-lake terrain, bed fill and rim fill per lake. A frozen reproduction of this defect fails before the fix. Cover ordinary basins as well as cirque tarns; tests of `shapeTarn` alone do not cover this issue. |
| RL-07 | Verify final blocks as well as cell heights. | Fresh generated chunks at the reproduced site or a preserved equivalent fixture meet RL-01–04 after surface/features and ordinary nearby fluid updates. Review cross-sections and in-game views from both supplied camera positions, including the lower stream. |

The 2-block sealing tolerance is a proposed engineering bound, not an existing user preference. The essential
requirement is to eliminate large terrain additions that manufacture containment. If a different small tolerance
is justified during implementation, record its rationale and keep this exact defect covered.

## 6. Implementation direction and verification

- Reuse existing basin data and lake diagnostics. Keep the fix local to the existing hydrology pipeline; no new
  dependency, configurable framework or global terrain-flattening layer is justified by this issue.
- Resolve basin water/footprint validity once per basin using a consistent terrain evaluation. Avoid changing water
  independently at each sampled column. Account for associated river levels before publishing the final plan.
- A basin validation step cannot call the public terrain path while that same plan is being built: that path asks
  for the plan again. Use an explicit already-available intermediate state and bounded sampling if required.
- Add a focused failing regression first. Preserve the old-layout fixture or a smaller causal fixture so future
  mountain-layout changes cannot make the failure disappear merely by moving the lake.
- Run affected lake/river tests, deterministic cache checks, then the existing FULL-chunk water-containment checks
  on both supported loaders. Broaden testing only for actual cross-pipeline changes.
- At diagnosis, existing tests checked depth, shallow-water share, inlet steps, short-distance shore-parallel channels and flat
  shelves. They did not bound pre/post lake terrain raise; the fix adds that coverage. The nearby low stream here is about 100–125 blocks
  from the diagnostic lake/bank points, well outside the existing 12-block parallel-channel metric.

## 7. Scope and existing worlds

In scope: ordinary lake placement/containment, its interaction with rendered terrain and neighboring river valleys,
and the regression gap demonstrated above. Preserve the existing fix that lowers cirque tarn water to its real rim.

Out of scope: distant striped mountain rendering, biome appearance, unrelated pending terrain/climate changes,
and a general rewrite of drainage or mountain generation.

A generator fix affects newly generated terrain. It does not reshape the saved server lake. Do not delete or
regenerate server chunks as part of diagnosis or the code fix. Any repair of existing chunks is a separate task
requiring a concrete area and preservation plan. Do not deploy the current mountain-layout changes as a workaround;
moving this lake does not establish that invalid elevated lakes are prevented elsewhere.

## 8. Verification status

The implemented resolver removes the original raised lake in the historical-layout replay. The eight latest Fabric
and NeoForge FULL-chunk checks pass: 8,688 chunks and 279,746 wet-column checks, including two retained lakes,
outlets, the lower stream and newly discovered shore/confluence regressions. All 22 distinct targeted regression
tests pass, and both loader candidates build. Exact validation stages and packaging are recorded in
[VERIFICATION.md](VERIFICATION.md).

The measured cold-start increase is material (about 92 seconds versus 11 seconds in the paired fresh-world harness).
The block-validation portion of RL-07 passes; its full-modpack in-game visual acceptance remains open.
No deployment or saved-server terrain repair was performed.

## 9. First implementation attempt — rejected, 2026-11-04

The attempted change validated each basin against post-valley terrain before publishing its plan, excluded
unsupported candidates from subsequent drainage construction, prevented lake-bed fill, and capped bank fill
at two blocks. It rebuilt drainage for excluded candidates so their old water levels and absorbed routes would
not survive. No runtime dependency or server change was introduced.

`HighlandLakeTest.lakesAreValleyLakesWithCleanJunctions` failed: the fixture went to **0 lakes**, zero area and
zero mean depth, violating its existing 15–27 lake and 0.65–1.6 km² gates. Inspection identified two overly strict
conditions: unrelated nearby channel water was mistaken for water overwritten by the lake, and a capped bank
was rejected even when still above the water. One repair corrected those conditions; the gate still reported
zero lakes. The all-or-nothing rejection approach does not meet RL-05. The lake-count test was not weakened.

All production edits from this attempt were restored to their exact pre-attempt contents, preserving unrelated
local edits. The rejected patch is retained only as research evidence:
[rejected-fix-attempt.patch](../../research/raised-lake/rejected-fix-attempt.patch).
The frozen measured bed/bank regression cases were retained as an opt-in research test and have since been
promoted into the normal [HighlandLakeContainmentTest.java](../../../common/src/test/java/etcodehome/freeterraforged/world/worldgen/highlands/HighlandLakeContainmentTest.java).
After restoration, the existing lake gate passes again, and both new defect regressions fail as expected
against the unfixed code. Results: [restored-baseline-regressions.json](../../research/raised-lake/restored-baseline-regressions.json).

**Next implementation direction:** reconcile unsupported basins' water levels and footprints with the rendered
ground before final channel/backwater construction. Rejection alone is insufficient to preserve the existing
lake population. This requires more work on RL-02/RL-04, rather than further tuning the rejection threshold or
loosening the lake-preservation test. The existing authorization covers fixing the defect; this entry records a
failed bounded attempt, not a request for deployment or permission to change the server.

## 10. Follow-up research and plan

The user requested research and a plan after that failed attempt. The unchanged-plan audit found bank-predicate
failures in 21/22 fixture candidates and 12/13 server-layout candidates, with no water-overwrite-predicate failures
at the audit's 8-block samples. Large terrain fill is widespread; the failure is not solely the two corrected
comparison bugs or the later candidate-replacement cascade.

A local 4-block minimax flood experiment found only 1.2831 blocks of natural retention anywhere in the reported
lake's planned footprint, and open drainage at its sampled central points. Omission is therefore the expected
outcome for this site, subject to finer validation. Other lakes need connected footprint/level resolution, not
an all-or-nothing cap test. The plan preserves the existing abundance gates while a prototype establishes what
supported lakes can survive; it makes no promise that every old outline is valid.

`PLAN.md` supersedes the simplistic notion of resolving lakes before **all** river work: provisional river-valley
geometry is needed to evaluate containment, and resolved bodies must then constrain final profiles. The existing
`TreeProfileSolver` is reusable for that step. River Count/Valley Width invariance tests are explicit compatibility
checks, not tests to delete silently if reconciliation needs to change an affected lake.
