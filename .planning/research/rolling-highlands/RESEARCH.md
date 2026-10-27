# Rolling Highlands — research and design recommendation

Date: 2026-10-27. Source checkout: `c5076eb8130a28f10e625ed28d5a9d7aceeb7188`.
Status: literature and source investigation completed; synthetic failure-mode and formula probes completed; FTF implementation and world validation not performed.

Companion: [feature specification](../../features/rolling-highlands/SPEC.md).

Second research pass: [ALGORITHMS.md](ALGORITHMS.md) supplies candidate relief/cross-section equations, a derived finite planning bound, depression handling and a tested tree-profile feasibility algorithm. [INTERNAL-AUDIT.md](INTERNAL-AUDIT.md) records reuse decisions and newly identified unit, scaling, preview and ceiling constraints. [Formula results](formula-results.json) distinguish validated mathematical properties from untested FTF behavior. Read these alongside the initial architecture below before implementation.

## 1. Question and conclusion

Can FTF offer repeated connected mountain systems, rolling uplands, buildable valleys, and coherent descending rivers, with independent controls for upland spacing, mountain height, foothill relief, and valley width?

**Recommendation:** expose `ROLLING_HIGHLANDS` beside `UPLIFT` and `MULTI_IMPROVED`, backed by a dedicated regional terrain composition and hydrology policy. Reuse the improved continent/coast machinery where its boundaries permit bounded drainage planning. A new terrain category alone cannot coordinate the required scales. A continent enum entry alone does not change mountain placement or river carving.

Use a smooth regional terrain proposal, a bounded drainage plan, and a final terrain synthesis that respects both. Keep three separate concepts: visible elevation, drainage-routing state, and actual channel/water elevation. Retain procedural erosion as detail rather than asking it to discover the major drainage network.

This is an engineering inference from the sources below, not a claim that a cited algorithm has already been ported or proven suitable for FTF. The second pass now defines a bounded-domain candidate and constrained profile solver; their full FTF integration and valley synthesis still need a Java prototype before production implementation.

## 2. Evidence and confidence

| Evidence | What it establishes | What it does not establish |
|---|---|---|
| Current checkout source | Integration points, control coupling, current river/terrain behavior | The appearance or cause of specific defects in the user's V3 save |
| Primary papers and author experiments | Applicable methods and known limitations | Minecraft performance, voxel water behavior, or suitable default tuning |
| Included synthetic probe | Filling and simple monotone carving can violate visual requirements despite valid routing | Rolling Highlands quality, FTF compatibility, realistic drainage, or V3 improvement |
| User brief | Desired behavior and four controls | Exact numeric tuning, acceptable performance overhead, V3 identity |

Gortex recall/impact/remember calls returned `repo_not_tracked`; this checkout was inspected with native read tools under the documented untracked fallback. No graph analysis was available. Decisions are recorded here instead. No production source, preset, save, or existing planning artifact was modified.

## 3. Current FTF architecture

Paths below are relative to repository root; line locations refer to the inspected commit. `J` abbreviates `common/src/main/java/etcodehome/freeterraforged/`.

| Anchor | Observed behavior | Consequence for Rolling Highlands |
|---|---|---|
| `J/data/worldgen/preset/settings/ContinentType.java`, enum factories | MULTI_IMPROVED selects AdvancedContinentGenerator; UPLIFT selects UpliftContinentGenerator | Add an opt-in selection without replacing legacy factories |
| `J/world/worldgen/cell/heightmap/Heightmap.java:83`, `:143`, `:200` | Continent and region fields precede terrain; warped Worley mountain mask blends mountain chains with region terrain; rivers follow terrain | A coordinated mode must participate in both land composition and river planning |
| `Heightmap.java:152`; `J/world/worldgen/cell/terrain/Populators.java:418` | Region-border terrain is based on plains/steppe | Merely increasing mountains does not guarantee connecting foothills |
| `J/world/worldgen/cell/continent/uplift/UpliftContinentGenerator.java:116` | Water-table gradient derives from a broad Voronoi-center uplift profile with coastal remapping | Repeating inland systems need a separate regional field |
| `J/world/worldgen/cell/rivermap/ContinentalHydrology.java:96`, `:131` | Fifteen plateau/ramp steps; amplitude depends on continentScale | Existing uplift ties spacing to height and intentionally makes stepped elevation |
| `J/world/worldgen/cell/continent/advanced/AdvancedContinentGenerator.java`, `apply` | MULTI_IMPROVED sets continent fields, not uplift waterTable | Do not assume UPLIFT's regional steps explain V3 MULTI_IMPROVED terrain |
| `J/world/worldgen/cell/continent/simple/SimpleRiverGenerator.java:20`; `.../rivermap/river/BaseRiverGenerator.java:78` | Root rivers extend outward toward coast; forks use geometric angles, length and overlap tests | Existing connected topology does not guarantee terrain-aware downhill profiles |
| `J/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java:100` | Shared carver handles beds/banks/floors/fadeout, explicitly terraces transitions, uses continental water heights | Preserve useful zoning concepts; supply a new profile and smooth cross-sections |
| Same carver, `updateValleyMask` and zone radius calculations | valleySize controls influence mask, while actual floor/fade radii also derive from bankWidth | A new valley-width setting must affect geometry, not only erosion masking |
| `J/world/worldgen/cell/filter/Erosion.java`; `TerrainFilters.java` | Procedural Phacelle erosion with slope gates, crest/headroom protection, and pre/post relaxation | Good starting detail stage; not a drainage solver; check post-filter channel integrity |
| `J/mixin/MixinSurfaceSystem.java:181`; `J/world/worldgen/feature/RiverGasketFeature.java:147` | Water height is recomputed from the continental function | A new carver alone leaves inconsistent water placement/sealing |
| `.../rivermap/wetland/Wetland.java`; `.../feature/ScreeFeature.java` | Additional consumers of continental water offsets | Include wetlands and surface decoration in the water-level audit |
| `.../rivermap/river/Network.java`, `carve` | Recursive child carving inherits warped coordinates | A new graph's junction geometry must not be independently warped at each level |
| `.../rivermap/RiverCache.java` | Per-owner cache and owner-qualified thread-local shortcut; continent-center key | Preserve owner isolation; do not assume constructor capacity 32 is a hard memory cap |
| `.../heightmap/RawChunkCache.java`, `getRawChunk`; `PreviewGrid.java`, `generateZoomed` | Runtime and preview invoke terrain/rivers/climate via different cache/batch paths | Planner must be independent of request order, preview zoom and chunk cache recursion |
| `J/data/worldgen/preset/settings/TerrainSettings.java` | mountainVariety changes height/base/width together; horizontal mountain scale also affects mask in Heightmap | Existing controls cannot simply be relabelled as the four independent controls |
| `J/data/worldgen/preset/settings/Preset.java`, codec/copy; `J/client/gui/screen/presetconfig/WorldSettingsPage.java:66` | Explicit codec/copy paths and explicit dropdown list | Persist settings, deep-copy them, expose mode and controls, invalidate preview state |

Existing pipeline, simplified:

```text
continent fields -> region + mountain terrain -> continental offset
 -> river/wetland carving -> climate -> relaxation/erosion/relaxation
 -> downstream density/surface/water placement and features
```

There are multiple uses of the word erosion: biome climate parameters, mountain-shape noise, and the height-changing filter. A plan must identify which one it changes; raising a biome erosion parameter is not equivalent to eroding terrain.

## 4. Primary-source research

Accessed 2026-10-27. These are compact paraphrases; no source code was copied. Publication dates below come from the papers/publisher, not search-engine crawl dates.

### S1 — Hydrology-organized terrain

Génevaux et al., *Terrain Generation Using Procedural Models Based on Hydrology*, ACM TOG 32(4), 2013. [Author-hosted paper](https://perso.liris.cnrs.fr/eric.galin/Articles/2013-river-networks.pdf), [publisher record](https://doi.org/10.1145/2461912.2461996).

The method constructs river networks and terrain together, with controls over river and valley slopes. This supports making drainage a structural input rather than a late decorative cut. Its finite terrain model is not evidence of seamless arbitrary-order Minecraft generation. We adopt the organizational principle, not an unexamined full port.

### S2 — Depression routing and its limits

Barnes, Lehman and Mulla, *Priority-Flood: An Optimal Depression-Filling and Watershed-Labeling Algorithm for Digital Elevation Models*, Computers & Geosciences 62, 2014. [Author-hosted paper](https://rbarnes.org/sci/2014_depressions.pdf), DOI `10.1016/j.cageo.2013.04.024`.

Priority-Flood processes terrain from outlets using a priority queue, resolving depressions and supporting watershed/flow-direction calculation. Floating-point processing is bounded by O(n log n); filling can create flats. Its guarantee depends on the supplied domain and outlets. For this feature, filled elevations are routing metadata, not rendered land. A computation-tile edge is not inherently an ocean outlet. Flat tie-breaking must produce deterministic acyclic parent links.

### S3 — Coupled uplift and fluvial erosion

Cordonnier et al., *Large Scale Terrain Generation from Tectonic Uplift and Fluvial Erosion*, Computer Graphics Forum, 2016. [Author-lab paper](https://www.cs.purdue.edu/cgvlab/www/resources/papers/Cordonnier-Computer_Graphics_Forum-2016-Large_Scale_Terrain_Generation_from_Tectonic_Uplift_and_Fluvial_.pdf).

This work evolves elevations on a graph using uplift and stream-power erosion, including lake drainage, then reconstructs detailed terrain. It supports coupling large forms to drainage. The reported iterative workflow is not a chunk-generation latency guarantee. A full simulation would add tuning and runtime cost; reserve it as an alternative if the simpler constrained synthesis cannot satisfy the shape requirements.

### S4 — Boundary and junction failures in practical prototypes

Amit Patel, *Procedural elevation*, 2017. [Author's experiment](https://www.redblobgames.com/x/1725-procedural-elevation/).

The experiment demonstrates that independently assigning elevation from river mouths can mismatch ridges, while other assignments mismatch tributary junctions and produce unwanted canyon cuts. Mixing the results does not automatically fix either. For FTF, use shared junction elevations and a shared regional field, then validate cross-sections rather than trusting a top-down river map.

### S5 — Procedural erosion is a detail method

Grenier et al., *Real-time Terrain Enhancement with Controlled Procedural Patterns*, Computer Graphics Forum 43(1), 2024, first published November 2023. [Publisher](https://onlinelibrary.wiley.com/doi/full/10.1111/cgf.14992), [Eurographics paper](https://diglib.eg.org/bitstream/handle/10.1111/cgf14992/v43i1_05_cgf14992.pdf).

The paper adds multiscale, direction-sensitive erosion patterns to existing terrain. It explicitly distinguishes this from generating a globally coherent river network. This supports preserving broad forms and adding bounded slope detail afterward. The current FTF implementation is a Phacelle-based filter, not an implementation of this paper; the connection is conceptual.

### S6 — Bounded lazy river planning in an infinite world

Michael K. Davis III, *Plate-Local River Generation*, September 2026 preprint. [Version 1](https://arxiv.org/html/2610.00301v1).

The preprint uses bounded plate domains, coarse terrain samples, lazy network caches and monotone river profiles. Rivers do not cross plate boundaries; paths may terminate in local-minimum lakes, and boundary warp is constrained by a safety margin. These limitations prevent treating it as a drop-in solution to this brief. It is useful recent evidence for bounded planning, not independently verified proof of FTF suitability. Cross-boundary routing and outlet-connected basins remain explicit requirements here.

## 5. Options evaluated

The ratings are engineering judgments for this checkout, not published comparative benchmarks.

| Approach | Benefit | Main problem for this brief | Decision |
|---|---|---|---|
| Retune existing mountain/hill weights and erosion | Small change; useful comparison control | Does not coordinate regional basins, divides or downhill rivers; controls remain coupled | Keep only as a cheap comparison, not complete solution |
| Replace UPLIFT steps with smooth noise | Removes one shelf source; repeated relief is easy | Noise can create undrained pits; existing radial routes and water consumers remain | Insufficient alone |
| River-first network, terrain built around it | Junction topology explicit | Can produce uniform basin shapes or mismatched divides without shared elevation constraints | Useful ingredient |
| Regional terrain proposal + drainage plan + constrained terrain synthesis | Preserves regional art direction and provides connected rivers | Requires depression policy, boundary contract, profile constraints and cache budget | Recommended prototype |
| Iterative tectonic uplift/stream-power simulation | Strong process coupling | Larger implementation, cost and tuning burden; must solve bounded-world integration too | Defer unless recommended prototype fails |

## 6. Reproducible experiment

Run from repository root:

```sh
python3 .planning/research/rolling-highlands/drainage_probe.py
```

Files: [probe](drainage_probe.py), [machine-readable results](probe-results.json). Python standard library only. The experiment samples a synthetic 65×65 coastal terrain with repeated waves, ridges and a basin, using five fixed seeds. Vertical units are synthetic blocks; grid spacing is unspecified. It does not use FTF noise, Minecraft, V3, production erosion, or actual water blocks.

It compares a Priority-Flood-style routing surface against a deliberately naive carving alternative that propagates upstream minima down the same parent tree. Both yield acyclic, non-ascending routes to the synthetic ocean. The carving alternative does not constrain incision or require a strictly positive gradient.

| Seed | Raw local sinks | Land raised by fill | Largest fill / naive cut | Land cut more than 48 blocks |
|---|---:|---:|---:|---:|
| 0 | 17 | 14.66% | 115.46 | 1.41% |
| 1 | 14 | 15.57% | 90.06 | 1.56% |
| 42 | 22 | 14.61% | 114.51 | 1.44% |
| 8675309 | 23 | 15.47% | 54.60 | 0.45% |
| -314159 | 19 | 16.43% | 73.02 | 1.36% |

Every run had zero routing cycles, outlet reachability for every node, identical repeated results, and zero downstream rises after the naive carve. Roughly 15–16% of land parent edges in the filled representation were flat. The equality of maximum fill/cut in these fixtures follows from using the same depression tree; these are not independent algorithms with equal visual quality.

**Validated finding:** routing correctness alone is insufficient. Neither a filled visible surface nor unrestricted incision satisfies the intended terrain constraints. **Not validated:** the recommended synthesis, usable default values, multiseed exploration quality, performance, or freedom from chunk seams. Synthetic seeds do not count as the five FTF acceptance worlds.

## 7. Proposed architecture

### 7.1 Separate policy from terrain labels

Add an opt-in enum selection and versioned settings, with a shared regional model available to continent, terrain, and river stages. Keep existing TerrainType labels where meaningful so biome/structure consumers retain their normal roles. Only introduce new terrain classifications if existing categories cannot express the resulting terrain; classification is not the generation strategy.

Conceptual components, names not prescribed:

- `RollingHighlandsSettings`: immutable owner snapshot, model version, four controls.
- `RegionalReliefSampler`: pure seeded field for smooth uplands, basin preference, ridge skeleton and foothill relief; no climate, chunks, rivers or mutable globals.
- `DrainagePlan`: immutable node/edge/junction/outlet records, basin/spill metadata, influence bounds and spatial index.
- `RollingHighlandsTerrain`: synthesizes broad terrain and transitions using regional fields and plan.
- `RollingHighlandsRiverCarver`: channel/floor/bank geometry from shared profiles.
- A mode-aware water query: one water elevation/validity convention consumed by carvers, surfaces, wetlands, river sealing and diagnostics.

`Heightmap.applyTerrain` currently precedes river application. Access to regional plans must not recurse through `Heightmap.apply`, RawChunkCache, climate sampling or filtered terrain while constructing that plan. Extract a pure planning sampler; do not call the full heightmap from the river generator.

### 7.2 Regional layout and independent controls

Compose broad overlapping uplands, selected low basins, a branching ridge skeleton and lower foothill ridges in world-block coordinates. Keep spacing and amplitude as separate inputs. Modulate heights/spacing with stable seeded variation. Use smooth basin masks to reserve open land; do not make plains the default connector everywhere.

Route on a stable regional planning field. Apply mountain-height and foothill-relief amplitudes during detailed synthesis, maintaining designated corridors. This makes graph positions stable under amplitude-only edits; the prototype must demonstrate that this does not produce implausible relief at extreme settings. If it cannot, revise the control contract explicitly instead of silently rerouting the whole world when a height slider moves.

Broad hills and peak prominence must survive coastal fades and TerrainCeiling compression. All peak targets are limited by configured world height; extreme valid settings still need usable transitions and explicit headroom handling.

### 7.3 Bounded drainage and domain ownership — first prototype gate

Prefer a full bounded continent-owned plan, exploiting existing continent identities, **only if** a conservative geometric bound and actual ocean outlets can be established for supported warps/skipping/size variance. Existing coast searches are not themselves a proof that every selected planning boundary is ocean or a divide.

A grid's rectangular edge must never become an invented sea outlet. If land can cross an ownership boundary, use a shared, deterministic boundary connection with a common height, or revise the mode's domain construction to have genuine shared divides/ocean boundaries. Padding alone does not solve drainage dependencies. Do not force a wall, plateau, or ocean moat at a cache boundary.

Limit total nodes, segments, construction concurrency, retained bytes and refinement work. Existing continentScale reaches 10,000 in the UI and the generators use a four-times scale internally; fixed fine resolution over the whole bound can be expensive. Consider capped coarse resolution plus bounded tributary refinement. Measure detail loss at the cap. No unbounded neighbor recursion is acceptable.

### 7.4 Basin and longitudinal profile resolution

Use drainage preprocessing to identify routes, contributing area and depression spill structure. Resolve each selected wet depression as an outlet-connected basin/lake, or choose a bounded breach/reroute. A lake's water surface is level; an outgoing channel starts at its spill. V1 need not model intentionally closed salt basins.

Fit profiles over the complete graph, with one elevation per junction and ocean level as the terminal boundary. Require non-increasing downstream water height, bed below water, bounded incision, and transitions compatible with the broad land surface. Plateaus caused solely by filling stay in the planning representation. Do not simply lower every downstream node until it lies below an isolated deep pit.

If profile, incision, and headroom constraints conflict, revise the route/basin plan deterministically with a bounded retry policy. During development, surface infeasible cases as failures with seed/domain/settings. Never publish an arbitrary-height river or silently switch to a legacy carver.

### 7.5 Valley shape and local erosion

Use separate widths for wet channel, dry valley floor and bank/outer transition. Adapt the local widths and side slopes to contributing area and regional confinement. Basin reaches widen; mountain passages narrow. Channel widening downstream is independent from regional valley spacing. Use smooth spatial variation to avoid periodic uniform trenches.

Geometry at junctions must be resolved jointly, with common water level and sufficient bed overlap. Independent minimum-height carving and maximum-water tagging are not a complete overlap policy. Unrelated nearby rivers must not cross or flood over their separating divide.

Retain existing relaxation/erosion first. Protect wet channels, flat floors and crest envelopes; inspect the final filtered surface as well as the unfiltered plan. Flow-biased erosion detail is a follow-up only if the current slope-oriented filter cannot satisfy the local-drainage requirement. Avoid another global noise-amplitude increase.

### 7.6 Runtime, persistence and compatibility

Branch into the new model only for the new mode; preserve legacy seed draws and behavior. Use settings and seed owned by the active world/preview. Include all new fields in preset encoding, copy, saved-world authority and preview invalidation. A model version identifies the generation algorithm; changing defaults does not reinterpret saved explicit settings.

Publish immutable plans atomically. A context must not see another world's plan, and eviction must recompute identical data. Build plans without recursive calls into the same cache or waiting on tasks scheduled to a saturated executor. Release owner resources on close. Segment lookup needs a spatial index; scanning every segment per generated column is not the intended steady-state design.

## 8. Implementation sequence and decision gates

1. **Capture baselines:** identify V3 jar/preset/seed/mod list and freeze current-checkout legacy output fixtures. These are distinct baselines.
2. **Prototype domain and drainage contracts:** bounded geometry, genuine outlets, depressions/spills, junction profiles, deterministic cold/warm/parallel generation, incision limits. Exercise largest supported dimensions. Stop for redesign if no bounded solution.
3. **Prototype regional shape and control independence:** compose uplands/ridges/foothills/basins and inspect longitudinal/cross sections before detailed erosion.
4. **Integrate water consumers and filtering:** actual voxel water, shore sealing, wetland/lake boundaries, flow vectors, climate/terrain metadata, ceiling and filter invariants.
5. **Expose and persist the option:** codec, deep copies, UI, labels, tooltips, preview, save/reopen and dedicated servers.
6. **Validate the experience:** fixed-seed route suite, independent control sweeps, performance and legacy regressions; revise proposed numeric tuning before release freeze.

This is a sequencing recommendation, not a claim that a GSD numbered phase or implementation plan was created.

## 9. Outstanding evidence

- The V3 MULTI_IMPROVED world/preset and exact build are not available in the inspected checkout. Final comparative acceptance remains pending; research/specification can proceed.
- A finite owner bound and exact tree interval-feasibility algorithm are now derived in ALGORITHMS.md. Actual outlet coverage, robust feasible profiles across FTF seeds, and full-resolution valley/junction geometry still need the Java prototype.
- Numeric landscape and performance targets in the spec are proposed engineering starting points, not values established by papers, experiments, or user approval.
- Block-water updates, gasket behavior, cave exposure, third-party worldgen interactions, preview filters, and full-world seam tests remain unverified.
- No new external library is required by this recommendation. If implementation copies code rather than implementing published ideas independently, review that code's license and attribution separately.
