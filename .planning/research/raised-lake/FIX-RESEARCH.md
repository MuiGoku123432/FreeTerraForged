# Raised lakes — research after the rejected fix

Date: 2026-11-04. Scope: diagnosis and historical prototypes. Implementation has resumed; current results are in PLAN.md.
Implementation plan: [PLAN.md](../../issues/raised-lake-embankment/PLAN.md).

Latest evidence supersedes two earlier uncertainties: candidate instrumentation confirms the reported basin is a
kept off-network hollow, and one-block terrain sampling confirms it retains at most 1.37335 blocks naturally.
The connected-basin prototype preserved 17 rendered fixture lakes, but failed inlet continuity; its one repair
failed profile feasibility. See the plan's execution record and `integration-results.json`. That prototype was restored before the user authorized another implementation. The current fix is retained and
its focused gates pass; it has not been deployed. See the current plan and `resolved-lake-gates.json`.

## Findings that change the plan

### 1. The two-block rejection test detects a widespread dependency on fill

`BasinAuditProbe` evaluated the original, unchanged plans. It does not reject candidates or retry planning.
It samples at 8-block spacing, computes the existing pre-lake terrain including oxbows, then calls the original
lake shaper. It also evaluates the final rejected patch's bank/water predicates without changing any plan.

| Original plan | Candidates | Recorded outlets | Fail proposed bank predicate | Fail proposed water-overwrite predicate | Existing bank raise >16 blocks |
|---|---:|---:|---:|---:|---:|
| Repository Test-America-v3 fixture, current layout | 22 | 12 | **21** | **0** | 12 |
| Saved server Rockies preset, c92366ef mountain layout | 13 | 8 | **12** | **0** | 6 |

Evidence: [fixture audit](fixture-audit/basin-audit.csv), [server audit](server-audit/basin-audit.csv).
These are candidate counts; the existing rendered-lake metric excludes tiny/non-lake water and reports fewer.
No outlet recorded is not sufficient evidence of a basin's admission branch or intended closed/open classification.

This refines the previous explanation: fixing the unrelated-water comparison was necessary, but is not enough.
The remaining bank predicate already rejects nearly every original candidate, before the cascading replans.
The old lake-count gate demonstrates a regression in abundance, **not** that all those lakes are correctly contained.
Some have 20–55-block bank additions. A correct solution must rebuild supported water bodies, not preserve every
old outline or equate a two-block cap with a complete containment algorithm.

### 2. The reported site does not contain a substantial natural lake at the sampled resolution

`spill_probe.py` ran a minimax escape calculation on the retained 4-block pre-lake terrain grid. The domain is
X=9700–10500, Z=11000–12000; its edges are open escapes, never walls. Four-neighbor connectivity was used.

| Position | Ground Y | Lowest escape barrier Y | Retained depth |
|---|---:|---:|---:|
| 10112,11500 | 88.1769 | 88.1769 | 0 |
| 10112,11600 | 90.5153 | 90.5153 | 0 |
| 10120,11408 | 86.6903 | 86.6903 | 0 |

Across all 1,200 samples in the positive-depth planned footprint, the deepest local pocket is only **1.2831 blocks**
deep, at (10132,11596), with spill Y=90.1918. The planned water was Y=117.3574.
Evidence: [spill-experiment.json](spill-experiment.json).

Conclusion: omitting this lake is the expected result unless finer sampling reveals a materially different basin.
Merely lowering the same rectangular footprint would still invent a water body on draining terrain. This is not
proof at block resolution: the retained CSV excludes the oxbow stage, the domain is local and samples are 4 blocks
apart. The five detailed original sites have no oxbow. Final acceptance still requires one-block boundary checks.

### 3. Water levels are already part of routing before the late validator runs

Current `HighlandDrainage.build` order:

1. Coarse terrain, routing, stream-power incision, depression keep/breach and dam candidates.
2. `absorbShores` modifies parent links; `hollows` admits off-network basins.
3. `HighlandBasins` makes interpolated membership/depth and surface levels.
4. Flooded coarse nodes receive basin water; axes and `HighlandMeanders` consume membership and water.
5. Two profile passes enforce downstream ordering and slope bounds; outlets and oxbow levels are then recorded.

`RollingHighlandsContinent` subsequently evaluates different, detailed ground, range dissection, support floors,
valley carving and oxbows before applying the lake. Its support floor itself depends on channel water levels.
Thus a new lake solver cannot simply evaluate "final ground" before any river calculation, nor trust a lake's
own artificial backwater support as independent evidence of containment.

The previous retry reran candidate selection too. Rejecting a dam changes the spacing opportunities for later
dam candidates, while breaching changes downstream terrain. It was a moving target, not a stable list to validate.
Stable candidate identities and a fixed candidate set are needed during reconciliation.

### 4. Existing useful code was missed in the failed approach

- **`TreeProfileSolver.solve`** already solves bounded downhill profiles on a parent-before-child forest. It can
  fix samples to an accepted lake level using equal lower/upper bounds and detect impossible joins explicitly.
  It has exhaustive-oracle tests, but is currently **not called by production drainage**. Integration is proposed,
  not assumed to be safe merely because the solver is tested.
- **`HighlandRangeRelief.fillWindow`** already implements local priority flood, and `tarnBounds`/`tarnWater`
  demonstrate per-body bounds and a single water level. Its private heap and cirque assumptions should not be
  turned into a general framework for this fix. Use the same algorithm locally in `HighlandBasins` with existing
  Java collections, retaining the separate cirque-tarn behavior.
- **`HighlandBasins.sample`**, `nodeSurface`, `absorbed`, and **`HighlandMeanders`** all need to consume the same
  finalized membership. Changing only the renderer leaves routing tied to the old lake.
- **`groundBeforeTarns`** contains the exact land/valley/oxbow sequence to factor locally for the provisional
  terrain evaluation. Do not reconstruct an approximation in production as the research probes do.

Local repository searches supplied this reuse review because Gortex does not track this checkout. No Gortex
verification is claimed. The prior EBEN lookup found no matching project note; a fresh repo-path search did not
find one either. Source fingerprints are retained in `research-source-sha256.json`.

## Algorithm research and choice

Priority-Flood computes escape elevations by processing a terrain grid from its boundary inward. Here its
output should be used as a **water-level constraint**, not copied into solid terrain. The local prototype computes
the equivalent minimum possible maximum terrain height along an escape path. See
[Barnes, Lehman & Mulla, Priority-Flood](https://rbarnes.org/sci/2014_depressions.pdf).

Connected depressions and spill points matter: a low cell elsewhere in a bounding box is not automatically part
of the lake. The Fill–Spill–Merge work explicitly models depression connections and spill/merge behavior. Its full
runoff/volume simulation is unnecessary for this procedural generator; use connected-component and spill reasoning
without adding the library or a regional depression hierarchy.
[Barnes, Callaghan & Wickert, 2021](https://esurf.copernicus.org/articles/9/105/2021/esurf-9-105-2021.html).

| Option | Assessment |
|---|---|
| Cap banks or remove bed fill alone | Necessary output safeguards, insufficient to contain water. |
| Reject any candidate with a low shore sample | Measured to reject 21/22 fixture candidates; cannot ship. |
| Lower water to the minimum of the entire bounding box | Wrong when that low point is outside the connected basin; ignores real spill paths and river outlets. |
| Preserve every planned lake by broad terrain uplift | Recreates the defect at a larger scale. |
| **Resolve connected bodies on detailed terrain, then constrain their river profiles** | Recommended. Can keep, shrink, lower, split or remove a candidate based on actual connectivity. Requires staged integration and preservation gates. |

## Important contract conflicts

`HighlandDeterminismTest.riverCountThinsCreeksButKeepsLakes` assumes exactly the same lakes with different river
counts. `valleyWidthKeepsTheChannelProfile` assumes unchanged profiles when valley width changes. If those settings
change the ground that contains a lake, final-terrain validity may require that lake or its adjacent profile to
change. Do not silently delete these tests or preserve their guarantees by retaining invalid water.

The plan keeps these tests initially. If evidence proves an unavoidable conflict, propose narrowly revised
guarantees for affected basins/reaches, with unrelated topology and profiles preserved. That is a visible contract
decision before implementation expands, not an automatic test relaxation.

## What this research does and does not establish

Completed: two original-plan audits, a sampled spill experiment, source integration/reuse review, primary-source
algorithm review. Both Java diagnostic runs passed. No production source or server world changed.

Not established: how many lakes the connected-body resolver will preserve, its final storage format or production
cost, the complete original admission provenance of server lake 4, or one-block/fluid correctness. The plan begins
with a bounded prototype and requires these answers before touching production routing. No claim that a final
resolver exists or that its acceptance gates already pass.
