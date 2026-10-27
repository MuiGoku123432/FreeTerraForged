# Structure placement compatibility — feature specification

Date: 2026-10-27.
Status: correctness implementation in progress on `feature/structure-placement`; advanced profiles remain proposed. See [IMPLEMENTATION.md](IMPLEMENTATION.md) for measured results and open gates.
Research: [RESEARCH.md](../../research/structure-placement/RESEARCH.md).
Evidence: [artifact-inventory.json](../../research/structure-placement/artifact-inventory.json), [evidence-manifest.json](../../research/structure-placement/evidence-manifest.json).

## 1. Goal and authority

Improve placement of Giant Natural Additions and Create: Structures Arise in FTF worlds while retaining FTF terrain generation, rivers, erosion, biomes, caves and functional structures. Demonstrate fewer unsupported, unintentionally buried, terrain-clearing or terrain-intersecting placements without hiding the problem through major loss of valid structures or generation performance.

**User constraints:** preserve FTF functionality and terrain quality; research/specify the previously discussed fixes. **Engineering proposals:** policy names, numeric budgets, release staging and implementation approaches below are recommendations, not user-selected defaults or measured results. The initial deliverable was this spec and its research. The user subsequently authorized implementation on an isolated branch/worktree; installation remains out of scope.

The repository has standalone feature specs and no numbered phase roadmap. This document uses the GSD requirement/edge-review structure without asserting a completed phase interview or changing project workflow state.

## 2. Background and scope

Confirmed concerns include the heightmap-agnostic height shortcut, raw/filtered sampling paths, rigid large templates, explicit air in some natural templates, and absolute airborne heights. The implementation has since reproduced cache inconsistencies and structure-aware fill truncation; see the implementation evidence. The full modpack's visible placement outcomes still require validation. See research F1–F7 for evidence/confidence. Existing Minecraft beardification is present; unrecognized mod structures are not inherently disabled.

**In scope:**

- Correct FTF base-height/base-column contracts and deterministic placement sampling.
- Validate and, where necessary, correct the existing fill-height optimization.
- A reusable, explicit policy mechanism for reviewed surface, natural and airborne structures.
- An inventory/classification of all 17 Giant Additions and 28 Create definitions in the pinned artifacts, with per-template eligibility for adaptation. Nether/underground/water roles must be recorded as such.
- Root/foundation support, occupied-volume clearance, template-air handling and coherent rigid placement.
- Optional bounded foundations and root-specific terrain blending, released separately from correctness fixes.
- Natural-generation, preservation, compatibility, persistence and performance validation on supported loaders.

**Out of scope:**

- Global terrain flattening, river rerouting, changes to erosion/noise/climate algorithms, biome distribution, cave frequency or world height — preserve FTF's terrain identity.
- Blanket support for every structure mod or automatic reinterpretation of arbitrary custom structure classes — unknown behavior remains upstream-owned.
- Retrogen or repair of existing structures/chunks — new starts are the target.
- Changing third-party spawn rates, biome restrictions, exclusion zones, spacing or user configuration to hide rejection.
- General underwater/underground relocation, terrain-matching conversions of rigid templates, or a new client editor — separate work if needed.
- Deploying to the user's Prism instances or changing their saves/configs during implementation without an installation request.

## 3. Requirements

### SP-01 — Correct height-query semantics

- **Current:** The bound generator returns a scaled 2D cell height for all heightmap types and ignores accessor bounds on that path.
- **Target:** In FTF generators, `getBaseHeight` returns the first free Y above the highest state matching the requested heightmap predicate within the configured/accessor intersection, or the normal minimum for an empty column. `getBaseColumn` and height helpers use the same canonical pre-structure density/fluid model. Preserve non-FTF behavior.
- **Acceptance:** AC-01 compares every supported heightmap type to a full-height reference column with canonical FTF inputs; zero mismatches. Cover ocean/seabed, dry land, negative Y, cave openings, configured min/max and empty columns. No universal `+1` assumption.

### SP-02 — Sampling stage is explicit and independent of cache warmth

- **Current:** A cached filtered cell and a raw heightmap evaluation can answer the same lookup depending on prior work; the inherited noise-column path also passes through FTF sampling hooks.
- **Target:** Placement and base-column evaluation obtain canonical filtered terrain independent of cache warmth and exploration order. Raw/filtered/stage values cannot share an indistinguishable cache entry. Provide a distinct conservative planned final-surface/fluid envelope where post-NOISE work changes placement support; do not redefine the base-height API as a final decorated-world query.
- **Acceptance:** AC-02 obtains identical results for cold, warm, evicted, pre-previewed and interleaved-world cases; reference columns and stage-aligned generated chunks agree. Querying terrain must never recursively request chunk generation or deadlock with filtering.

### SP-03 — Noise-fill optimization preserves all density effects

- **Current:** The downstream fill wrapper caps work using local terrain/sea height plus 16 without an explicit structure-influence bound.
- **Target:** Optimized fill is block-for-block equivalent to full configured-height fill, including structure terrain adaptations, junctions, interpolation and fluids. Use a conservative bound or full-height fallback whenever equivalence cannot be established.
- **Acceptance:** AC-03 compares NOISE outputs at every block for all adaptation modes, high structures spanning lower chunks, noise-cell edges, min/max Y and shifted structures. No arbitrary fixed margin is accepted as proof.

### SP-04 — Explicit profiles and deterministic precedence

- **Current:** Only selected vanilla/village identities receive FTF-specific placement handling.
- **Target:** World-owned, versioned profiles select exact registry IDs or explicitly authored tags, with per-template support/clearance/air/blending metadata. Exact-ID rules override tag rules; equal-priority conflicting rules are rejected during validation with the conflicting IDs. Disable/passthrough rules can opt a structure out. Absent optional mods are harmless; a present but incompatible template fingerprint leaves its advanced profile inactive with a diagnostic. No namespace/name/height-based automatic classification.
- **Acceptance:** AC-04 covers missing mods, empty selectors, overlapping tags, unknown IDs/versions, pack priority, invalid numeric values and changed template/processor identities. Unknown structures keep existing behavior after universal correctness fixes. One profile cannot execute in addition to an incompatible existing village/subterranean adaptation.

### SP-05 — Rigid surface placement has bounded support and clearance

- **Current:** Most target surface structures project a rigid start using one bounding-box-center height.
- **Target:** Evaluate the actual transformed support mask, intended buried regions, occupied blocks and entry clearances. Keep the original candidate if valid. Otherwise choose a deterministic feasible rigid height or bounded same-start-chunk candidate, then reject with a reason if no legal fit exists. Do not twist individual columns, stretch machinery, edit the template, or bypass biome/exclusion constraints.
- **Acceptance:** AC-05 proves support/embedding limits at every required contact and acceptable collision/entry clearance after all generation stages. Flat valid controls retain their original transform. Every selected candidate has coherent pieces, bounds, junctions, start position and references; no orphan blocks in adjacent chunks. All transformed occupied/influence chunks must remain inside supported reference/dependency coverage; same-start-chunk anchors alone do not establish this.

### SP-06 — Natural landmarks preserve terrain outside their declared local effect

- **Current:** Giant templates use rigid, generally beard_thin placement; canopy bounding boxes can greatly exceed roots. Some templates contain large explicit-air volumes.
- **Target:** Reviewed natural profiles distinguish root/stem support from canopy clearance, intentional voids and blending domains. Root placement uses an explicit anchor rather than assuming the canopy center. Replace/restrict inherited full-box blending only for enrolled profiles. Suppress unwanted explicit air only where the reviewed profile declares terrain preservation; retain declared intentional cavities and distinguish omitted coordinates from air.
- **Acceptance:** AC-06 verifies each enabled natural profile under all supported rotations/mirrors: grounded roots, no canopy-wide foundation, no undeclared terrain excavation, preserved intentional cavities, and no river obstruction. Ambiguous profiles remain observation-only rather than receiving a guessed automatic fix.

### SP-07 — Airborne structures clear terrain within world limits

- **Current:** Three identified Create airborne structures use absolute height ranges; separate mod-side rules may reject steep/mountain sites.
- **Target:** For explicitly enrolled airborne IDs, keep the original altitude if valid; otherwise lift the entire structure by the minimum legal amount satisfying occupied-footprint clearance. Honor maximum lift, actual occupied height, world bounds and dimension padding. Skip when no feasible altitude exists. Preserve Create's own disable/eligibility decisions.
- **Acceptance:** AC-07 includes high flat plateaus, narrow peaks between coarse samples, slopes, ocean, world ceiling and asymmetric templates. Every occupied footprint column clears the planned surface by the declared amount; no piece/entity exceeds padding or world limits. Original absolute Y is never added twice.

### SP-08 — Foundations are optional, local and budgeted

- **Current:** No generic reviewed per-template foundation budget exists; vanilla blending already changes local terrain.
- **Target:** Additional foundation work is off by default. An enrolled profile may enable bounded material support/local blending within explicit masks, depth and volume limits, accounting for inherited beard effects and template writes. Work must preserve protected wet channels, shorelines, caves outside declared embedding and neighboring structures. If the fit exceeds budget, reject or retain upstream behavior in observation mode; never silently exceed the limit.
- **Acceptance:** AC-08 diffs block states against the terrain-only reference and checks the union of all declared influence regions. Added supports obey material/depth/volume rules and do not leave exposed artificial platforms, fill channels, remove terrain outside declared masks or perform cross-chunk writes.

### SP-09 — Determinism and complete structure state survive lifecycle changes

- **Current:** No new policy state/serialization contract exists; moving a pool piece alone does not update its junction list.
- **Target:** Results depend only on world/epoch, seed, structure selection, pinned profile/template data and coordinates. Bounded extra choices use their own stable salt without extra draws from upstream RNG. Apply one coherent transform; persist any policy decisions required by later chunks. Do not re-run selection when loading saved starts. Reload/cache invalidation is epoch-owned; in-flight starts retain one immutable policy snapshot.
- **Acceptance:** AC-09 compares forward/reverse/random chunk order, worker counts, save/restart between piece chunks, reloads, cancellation/retry and two worlds/previews. Positions, junctions, references and final blocks agree; no shared template mutation or duplicate entities. Diagnostics on/off cannot change output.

### SP-10 — Mod and loader behavior composes correctly

- **Current:** FTF and Create inject into jigsaw generation; Create also has placement hooks and its own config-controlled rejection. FTF supports direct structure-set holders through a compiled-plan guard.
- **Target:** Respect the resolved mod/pack definitions and cancellations, preserve direct-holder compatibility, block entities, entities, liquid settings, processors, loot and machinery. No blanket early-return replacement that skips another mod's eligibility/placement work. Non-FTF dimensions and uninvolved structures retain behavior.
- **Acceptance:** AC-10 uses actual transformed runtime on Fabric and NeoForge where target artifacts exist, including one absent-target-mod run, target mods separately/together, and the effective full pack. Native starts, `/locate`, serialization and final blocks agree; Create functional fixtures operate. Nether/underground controls stay at intended depth and receive no surface policy.

### SP-11 — FTF quality and exploration density remain measurable constraints

- **Current:** No matched baseline reproduction or candidate/defect census exists for this report.
- **Target:** Preserve terrain algorithms/parameters and demonstrate the quality gates in section 6. Compare installed baseline, correctness reference and policy candidate separately. Improved defect rates cannot be credited if valid structure density collapses. Keep existing village, trail-ruin, monument and subterranean protections under regression coverage.
- **Acceptance:** AC-11 passes unchanged terrain-only model fixtures; every intentional correction is isolated and explained. AC-12 passes matched placement quality and valid-density gates. A runtime defect cannot be marked fixed from static data alone.

### SP-12 — Bounded cost and actionable diagnostics

- **Current:** General worldgen timing exists, but no profile-specific sampling/rejection census exists.
- **Target:** Bound candidates, sampled columns, occupied-template analysis, memory and concurrency; precompute immutable template metadata per epoch. Capture original/resolved heights, sampling stage, profile identity, attempted/accepted/rejected counts, budget exhaustion and upstream rejection separately. No timeout-dependent placement or unbounded search. Standard play has no per-block log spam.
- **Acceptance:** AC-13 measures throughput/latency/memory against both baselines and checks deterministic worst-case caps. AC-14 produces a reproducible per-structure report with manifest, seed, preset, effective datapacks/config, coordinates, final outcome and reason. Counters reconcile and do not alter RNG/output.

## 4. Proposed policy contract and starting budgets

These are **prototype values to validate**, not established safe defaults. Do not silently relax them to make tests pass. Record before/after evidence if changed.

| Field / decision | Proposed starting value or rule |
|---|---|
| Advanced policies | Observation-only initially; opt-in per world/profile after gates pass. Correctness fixes have their own release/test stage. |
| Profile identity | Schema version + ID + world policy epoch + effective template/pool/processor fingerprints. Runtime pack overrides are authoritative. |
| Surface/natural retries | Original plus at most 3 alternatives; alternate anchor X/Z remains in the original start chunk. No retries in initial observation/correctness stage. |
| Support mask | Authored contact cells or explicit mask; transform with actual template rotation, mirror and anchor offsets. Canopy columns are not automatically contacts. |
| Dry building embed / added support | Prototype maximum 2 blocks embed; 0 added support by default. Optional support up to 3 blocks/contact and 512 added blocks/start, within reviewed material rules. |
| Natural embed / root blend | Per-template; initial maximum 2-block intentional embedding. Optional blend confined to root mask plus at most 4 horizontal blocks; maximum 2-block local height change. No broad excavation. |
| Terrain preservation | Combined inherited blending, custom support and template writes must obey declared influence bounds. Protect wet channels from deterministic hydrology/fluid data, not only biome names. |
| Airborne clearance / lift | Prototype 16 clear blocks; upward-only adjustment of at most 256 blocks. Check full occupied top and dimension padding; reject infeasible positions. |
| Sampling | At most 32,768 unique terrain columns per attempt, 131,072 per start. Sparse prechecks may reject/refine but never certify full clearance without a conservative bound. |
| Template analysis | At most 1,000,000 stored block records/template in prototype; cache semantic/column summaries rather than rewalking blocks per candidate. Above cap: profile inactive with reason. |
| Policy metadata cache | Prototype 64 MiB per world/epoch with bounded concurrency; eviction may change performance, never output. Verify memory accounting before finalizing. |
| Budget exhaustion | Deterministic `budget_exceeded`; observation leaves upstream result, enforced profile rejects candidate. Report separately from geometric failure. |

All arithmetic uses checked/bounded integer coordinates at API boundaries. Compare exactly at thresholds and one block either side. Sampling masks are deduplicated in a defined coordinate order. No wall-clock time limit may change acceptance.

Ground-fit feasibility and airborne-lift formulas are in the research document. They constrain contact/clearance; they do not substitute for processor, fluid, cavity and final-world checks.

## 5. Delivery stages

1. **Baseline and diagnostics:** freeze installed artifact/effective-pack manifests; capture reported examples if available; add independent fixed fixtures, census and full-height/canonical-column reference paths. Classify all target structure IDs. No placement policy enabled.
2. **Correctness:** fix SP-01/02 and any reproduced SP-03 failure. Test all heightmap consumers, vanilla protections and both loaders. Quantify all changes before enabling profiles.
3. **Placement without additional foundations:** explicit reviewed building/root anchors and airborne clearance; coherent state; bounded site selection; policy observation versus enforcement comparison. Treat mod-side rejections as a separate cause.
4. **Natural template/blending compatibility:** reviewed root-domain adaptation and template-air handling. This is necessary before claiming giant-natural terrain preservation; support-point checks alone are insufficient.
5. **Optional limited foundations:** enable only for profiles that pass geometry, visual and river-preservation checks. Complete density/performance gates before changing defaults.

Each stage is independently reversible before deployment and has separate comparison results. Existing chunks/starts are not regenerated. Profile removal or upgrades must not reinterpret saved accepted starts; partially generated structures crossing save/load or an upgrade boundary are required fixtures. No seamless compatibility claim across arbitrary old/new chunk boundaries without testing.

## 6. Acceptance protocol

### Baselines and fixtures

- **B0:** installed FTF `custom-c5076eb8`, exact effective mods/preset/packs/config and original template identities.
- **B1:** canonical filtered query/full configured-height reference with the same FTF terrain model, upstream structures and advanced policies disabled.
- **B2:** optimized correctness candidate, advanced policies disabled; must match B1 at the relevant stages.
- **P:** B2 plus each explicitly enabled policy stage. Compare P versus B2 to isolate policies and versus B0 to report total user-visible impact.
- Freeze at least five seeds (proposed `0`, `1`, `-1`, `8675309`, `9223372036854775807`), all current stable terrain modes, default and one high-relief preset. Rolling Highlands, if concurrently available, is an additional experimental compatibility run, not a replacement baseline.
- Fixed strata: flat land, slopes, peaks, high plateau, coast/ocean, wet river/valley, cave/overhang, biome boundary, negative coordinates, chunk/filter-region boundary and world ceiling. Keep the preset files, generation order and camera poses with results.
- Controlled candidate fixtures: at least 20 attempts per enabled structure ID across rotations/strata, including known-valid flat controls. Natural-generation survey: at least 200 accepted target starts overall and at least 10 per enabled policy class across three seeds. Rare-ID coverage uses controlled fixtures and is reported separately. A fixed predeclared survey/expansion schedule prevents cherry-picking; failure to reach sample minimum means inconclusive, not pass.
- `/place structure` or forced candidates may isolate geometry, but cannot replace natural-start, structure-set, biome, exclusion-zone and reference tests.

### Pass/fail checklist

- [ ] **AC-01 — Height semantics:** zero discrepancies with canonical reference columns for all heightmap types and edge bounds; `firstOccupied = firstFree - 1` as required by the API. Scan results use the requested predicate and configured/accessor intersection.
- [ ] **AC-02 — Stage/cache consistency:** zero query/decision differences across cold/warm/evicted caches, order, preview use and concurrent worlds; explicit tests of pre-NOISE/NOISE/SURFACE/final-stage distinctions, including elevated river water. No recursive chunk loading or filtering deadlock.
- [ ] **AC-03 — Fill equivalence:** all NOISE block states equal full configured-height reference with NONE/BURY/BEARD_THIN/BEARD_BOX/ENCAPSULATE fixtures, junctions, boundaries and fluids; fallback cases also pass.
- [ ] **AC-04 — Profile resolution:** deterministic precedence and actionable validation errors; malformed/missing resources cannot silently apply a guessed policy. Unknown/disabled profiles pass through; no duplicate ownership. Empty masks and conflicting mask ownership are invalid.
- [ ] **AC-05 — Ground fit:** every required support contact meets declared embed/gap limits, every protected entry/collision region passes, and valid original controls retain transforms. Test singleton masks, holes, touching masks, exact limits, negative coordinates and all transformations. Include the 144-block giant oak at rotated negative extents and exact eight-/nine-chunk reference boundaries; validate full template completion in natural generation. Unsupported extents require legal re-anchoring or a diagnosed rejection, not a global dependency-radius increase. No independent movement of machinery columns or disconnected jigsaws.
- [ ] **AC-06 — Natural landmarks:** each enabled template has reviewed support/void/blend masks and pinned identity; root support is independent of canopy size; no undeclared air clearing, canopy platforms or blocked rivers. Include giant red mushroom's explicit air and intentional cavity cases.
- [ ] **AC-07 — Airborne fit:** dense/conservative footprint validation catches peaks missed by coarse samples; all occupied blocks satisfy clearance and upper/lower padding. Test required lift 0, max, max+1 and no feasible altitude; no double height offset. Third-party rejections remain effective.
- [ ] **AC-08 — Bounded terrain effects:** optional foundations default off; every changed terrain block is inside declared influence unions and budgets. Zero fluid-channel obstructions, terrain edits outside masks or writes outside the chunk currently being populated. Overlapping influence regions compose deterministically within aggregate limits rather than double-spending budgets.
- [ ] **AC-09 — Lifecycle/determinism:** 1 versus multiple workers, reversed/random order, repeat calls, cancellation/retry, save/reload and policy reload produce identical outcomes for the same epoch. Pending old starts finish with their saved decisions. Pieces, junctions, references, entities and diagnostic modes remain consistent.
- [ ] **AC-10 — Integration:** actual runtime tests pass on each supported loader/artifact combination. Include unkeyed Create structure sets, vanilla adaptations, Nether/underground/water pass-through, processors/fluids/loot/entities and functional Create machinery. Unsupported artifact combinations are explicitly marked untested.
- [ ] **AC-11 — FTF preservation:** terrain-only canonical cell/filter outputs and mode parameters remain unchanged. B1/B2 stage outputs match. P has zero unexplained terrain differences outside declared structure/decorative influence; inspect NOISE, SURFACE and final stages separately to avoid misattributing dependent decoration. Generated old chunks are unchanged. No retuning terrain to accommodate structures.
- [ ] **AC-12 — Placement quality/density:** proposed release gate is at least 50% fewer unintended placement defects per 100 accepted target starts than B0, no new defects in known-valid controls, and no reduction in valid target starts per matched explored area relative to B0. Report per-ID/per-stratum counts, confidence intervals and all rejections; aggregate gains cannot conceal loss of a structure class. If B0 has zero defects, preserve zero rather than dividing by zero. Inconclusive samples do not pass.
- [ ] **AC-13 — Performance/resources:** proposed gates: median fresh-chunk throughput at least 95% of B0 and p95 generation latency at most 110% of B0, on identical hardware/JVM/heap/workload; report B1/B2/P separately. Measure at least five paired runs after equal warm-up, alternate order, separate cold/warm paths and include structure-heavy regions plus worst supported masks. No unbounded cache growth/deadlocks; operation/memory caps verified. A budget miss requires optimization or an explicit documented scope decision, not a claim of no regression.
- [ ] **AC-14 — Diagnosis/reproduction:** counters reconcile attempted→upstream reject→policy reject/accept→placed/final outcome. Record per-ID reasons, effective inputs, sampling stage, original/resolved transforms, support/clearance extremes, influence bounds and hashes. Logging on/off is output-identical. Reports explicitly distinguish observed failures, inferred risks and untested cases.

Visual review accompanies the mechanical gates: fixed overhead and ground-level views must show grounded roots/buildings, preserved river courses and no artificial rectangular platforms or cutouts. A failed view gets a recorded coordinate/cause and regression fixture; attractive screenshots cannot override failed measurements.

## 7. Edge coverage and prohibitions

The canonical GSD edge probe is run against the 12 requirements. [EDGE-COVERAGE.md](EDGE-COVERAGE.md) maps every emitted category to explicit acceptance checks; this is a specification-coverage result, not evidence those checks have passed.

Project-specific negative requirements derived from the user's preservation goal:

| Must-NOT | Verification obligation |
|---|---|
| Make FTF globally flatter, change rivers/erosion/biomes, or introduce canopy-sized platforms to accommodate structures | AC-06/08/11 plus fixed-view review. |
| Claim success by removing problematic structures or silently increasing spawn rates | AC-12/14; valid density and rejection accounting. |
| Distort Create machinery, erase intended interiors or lift underground/Nether structures with a surface policy | AC-05/06/10. |
| Present static analysis or proposed thresholds as proven in-game quality/performance | AC-14 and release evidence review. |

The initial planning task implemented no tests. Current regression locations and their deliberately narrower coverage are recorded in IMPLEMENTATION.md; these checkboxes remain release gates, not claims inferred from partial tests.

## 8. Readiness and remaining uncertainty

The goal and preservation boundaries are ready for implementation planning. The exact reported world remains an optional reproduction input, while independent controlled fixtures can proceed immediately. Runtime evidence is still required for visible cache divergence, fill truncation, transformed mixin ordering, effective template-air clearing and numeric budgets.

Clarity assessment (author judgment, not a user interview): goal 0.95; boundaries 0.95; constraints 0.85; acceptance 0.90; weighted ambiguity 0.08. Numeric settings remain explicitly proposed. The user approved research/specification and the preservation priority; no additional preferences or test results are inferred.

Next implementation entry point: stage 1 baseline/diagnostics, followed by correctness fixes. Do not begin with global rejection or terrain reshaping.
