# FTF structure placement — research

**Implementation follow-up:** [IMPLEMENTATION.md](../../features/structure-placement/IMPLEMENTATION.md) records later runtime reproductions, fixes, and the performance concern. Findings below retain their original research-time confidence.

Date: 2026-10-27. Status: static investigation and design research; no in-game reproduction or performance claim.
Specification: [SPEC.md](../../features/structure-placement/SPEC.md).
Evidence: [artifact inventory](artifact-inventory.json), [source/artifact manifest](evidence-manifest.json), [reproduction script](inspect_artifacts.py).

## 1. Question and conclusion

The user reports placement trouble with Giant Additions and Create structure mods and wants improvements that retain FTF functionality and terrain quality. Installed artifacts identify Giant Natural Additions 2.0.0 and Create: Structures Arise 176.49.49 in the Terra Dynamic Trees Test instance. This identifies available test inputs, not the exact world in which the reported failures occurred. No seed, coordinates, screenshots, preset, or full effective datapack stack was supplied.

There is more than one mechanism. Fixing height queries is warranted independently of new placement policies. Large templates also need deliberate support/clearance semantics; inherited Minecraft terrain blending and explicit template air can change the landscape even when FTF itself does not flatten anything. Airborne placement and mod-side rejection need separate treatment. A global slope filter or conversion to terrain-matching projection would be an unsafe general solution.

The current checkout was inspected at `0f2da95bcdfc855adb39f9f4161682cbba8ae160`; the installed FTF jar is `freeterraforged-1.0.0014-custom-c5076eb8.jar`. These are distinct baselines. Installed FTF bytecode independently confirms the same height-query shortcut, terrain-plus-16 fill bound, and filtered-cache-or-raw sampling branch described below. Record exact effective runtime inputs again before implementation tests.

Gortex reports this checkout untracked, including the attempted prior-work recall. No tracking or graph mutation was performed. Local source and installed artifacts were read directly. Existing standalone feature/research directories establish the document layout; no phase roadmap or STATE.md exists. The GSD spec template and edge probe inform the spec, but this is not a completed numbered GSD phase or a fabricated interview.

## 2. Evidence and confidence

Paths below are relative to the repository. `J` abbreviates `common/src/main/java/etcodehome/freeterraforged`.

| ID | Evidence | Finding and limit |
|---|---|---|
| F1 | `J/world/worldgen/runtime/TerraForgedChunkGenerator.java:366`, `getBaseHeight` | Bound-context path returns `levels.scale(cell.height)` without using requested heightmap predicate or generation-height bounds. Confirmed API correctness concern, especially fluids; exact land-height error must be measured, not assumed to be universally one block. |
| F2 | `J/world/worldgen/densityfunction/CellSampler.java`, `Cache2d.getAndUpdate`; `J/world/worldgen/cell/heightmap/WorldLookup.java`, `applyCell`; `FilteredChunkCache.java`, `generateRegion` | Cache hit may supply filtered cells; a cold lookup with `load=false` evaluates the raw heightmap. Thread-local cached values can retain the earlier result. Different sampling paths are confirmed; the size/frequency of visible placement divergence remains unmeasured. |
| F3 | `J/mixin/MixinNoiseChunk.java`, constructor hooks and `wrapNew` | Full chunk noise obtains filtered cells, whereas a single-column noise context can use only already-present filtered cells and fall back to CellSampler. Calling `super.getBaseHeight()` alone is not proof that sampling becomes deterministic. |
| F4 | `J/mixin/MixinNoiseBasedChunkGenerator.java:68`, `freeterraforged$wrapDoFill` | Fill ceiling is derived from `max(local cell height, sea level) + 16`, rounded to noise cells. It does not explicitly bound contributions from structures, junctions or neighboring density interpolation. `NoiseFillExtent.fullConfiguredHeight()` in the generator does not remove this later wrapper. Truncation is a credible hypothesis requiring a differential test. |
| F5 | `J/world/worldgen/runtime/WorldgenPlans.java:1106`; `J/mixin/MixinJigsawStructure.java:93` | Special policies cover trial chambers, ancient cities, trail ruins and village-tagged structures. Other structures normally retain Minecraft/mod placement. This is an integration gap for tailored adaptation, not evidence that unrecognized structures are disabled. |
| F6 | `J/world/worldgen/runtime/TerraForgedChunkGenerator.java:597`, `createStructures` / `isPlanned` | Current compatibility guard accepts direct/unkeyed structure sets when all their referenced structures belong to the compiled plan. Preserve this existing Create compatibility behavior. |
| F7 | `J/data/worldgen/preset/PresetStructureRuleData.java`; `J/mixin/MixinStructure.java` | The built-in cell-test registration is commented out. Runtime rules can reject starts if present, but this source does not establish a default blanket mountain blacklist. Effective packs/rules must be captured. |

### Minecraft 1.21.1 contract, verified from local mapped bytecode

The inspected Minecraft jar and SHA-256 are recorded in the manifest. `javap -c -p` was used; downloaded wiki descriptions or Bedrock documentation are not treated as Java Edition authority.

- `ChunkGenerator.getFirstFreeHeight` returns `getBaseHeight`; `getFirstOccupiedHeight` subtracts one. `NoiseBasedChunkGenerator` evaluates the supplied heightmap predicate against a density-generated column. A contract test must use first-free semantics, the accessor's bounds, fluid state, and the same FTF density/fluid machinery.
- `JigsawPlacement.addPieces` projects the initial piece using the center of its rotated bounding box, then applies the piece's ground-level delta. The canopy-box center is not necessarily a tree's root center. One projected point does not establish support over an entire structure.
- `NoiseChunk` adds the beardifier to final density. FTF has not simply omitted all vanilla terrain adaptation from its noise router.
- `Beardifier` evaluates rigid pieces using their bounding boxes and ground-level deltas, with a finite local kernel (radius constant 12). It does not discover trunks, roots or semantic building foundations. A canopy-sized bounding box can influence a much larger area than the actual roots. Kernel radius is not a guaranteed maximum foundation depth or a sufficient fill-ceiling formula.
- `PoolElementStructurePiece.move` moves its bounding box and position; its method body does not rewrite its junction list. Relocating an already assembled jigsaw structure requires coherent junction/stub/reference handling, not just moving every box.
- `ChunkGenerator.createReferences` searches starts within eight chunks in each horizontal direction. A transformed 144-block-wide template can extend into the ninth chunk in a negative direction (for example, local X=-143 at an origin on a chunk boundary). This is another possible source of clipped giant structures, including on non-FTF terrain; actual occupied extents, rotation and transformed runtime must be checked. Remaining inside the start chunk with the anchor alone does not prove every piece stays referenceable. A fix must validate/re-anchor within supported reference coverage, not globally enlarge chunk dependencies.
- `SinglePoolElement.getSettings` adds the structure-block ignore processor, configured processors and projection processors; its inspected default path does not impose a blanket air-ignore processor. This supports testing explicit-air clearing as a distinct mechanism.

Base-height queries describe pre-structure generation, not a promise of equality with every final heightmap after carvers, surface water, structures, vegetation and decoration. Tests must compare equivalent stages. Placement additionally needs FTF's final planned surface/fluid envelope where later stages alter the ground/water; it must not generate neighboring chunks recursively to discover it.

## 3. Installed mod data

The inventory script parses JSON and NBT read-only, records artifact hashes and emits dimensions/counts, without extracting or redistributing binary templates. It inventories jar defaults; runtime datapack overrides, tags, processors and config can supersede them.

| Artifact | Actual structure definitions | Initial height projection | Terrain adaptation | Pool elements |
|---|---:|---|---|---|
| Giant Natural Additions 2.0.0 NeoForge 1.21.1 | 17 | 16 WORLD_SURFACE_WG; 1 absolute-height Nether tree | 16 beard_thin; 1 beard_box | 17 rigid |
| Create: Structures Arise 176.49.49 NeoForge 1.21.1 | 28 | 19 WORLD_SURFACE_WG; 9 unprojected | 15 beard_thin; 5 beard_box; 8 none | 28 rigid |

One additional Create JSON in the structure directory lacks a structure `type`; it is excluded from the 28-definition count. Some stored templates are unused alternatives: resolve effective pool entries before treating a template as a live placement.

### Large natural structures need semantic masks

| Template | X × Y × Z | Explicit air entries | Implication |
|---|---|---:|---|
| `giant_oak_tree` | 144 × 124 × 100 | 0 | Whole canopy footprint is 14,400 columns; neither a nine-point slope check nor a requirement to support the whole rectangle is appropriate. |
| `giant_palm_tree` | 119 × 96 × 119 | 0 | Off-center/canted growth needs an authored root anchor and rotated support mask. |
| `giant_jungle_tree` | 122 × 115 × 102 | 0 | Full occupied-volume collision checks differ from support checks. |
| `giant_red_mushroom` | 106 × 107 × 113 | 388,056 | Explicit air can clear terrain over a large saved volume. Inspect intended cavities before filtering it. |
| `giant_cherry_bonsai2.0` | 92 × 82 × 115 | 29 | Even small air counts need a deliberate policy, not a blanket per-mod assumption. |

All inventoried pools explicitly ignore structure blocks, but do not explicitly ignore air through that processor list. Runtime processor/placement behavior still requires a real fixture. An explicit air entry is different from a coordinate omitted from sparse NBT; do not synthesize air for omitted coordinates.

The proposed model separates: (1) roots/foundation contacts requiring support, (2) occupied structure blocks requiring collision/clearance checks, (3) intentional interior voids, and (4) the bounded terrain-blending region. Metadata is template-specific and transformed with rotation/mirror/ground offset. A generic inference algorithm may suggest masks offline, but is not accepted as runtime authority merely because a block is a log or the lowest block in a column.

### Create structures have mixed intent

- `createairdrop`: absolute Y 100–200; `createminiskyvillage`: 200–201; `pillagersteampunkairship`: 160–190. These are the airborne candidates, and need explicit altitude-policy enrollment.
- `createmonsterroom` and `create_dungeon_base` are unprojected Overworld underground candidates. They must not be lifted to the surface by generic rules.
- `create_bastion`, `darkcastle`, `deadlordshouse` and `obsidiantemple` target Nether biomes. Their absolute elevations are not evidence of an FTF Overworld problem.
- Projected water/boat structures must be classified from effective definitions and templates, not grouped with dry-land buildings because they share a namespace or projection type.
- Create buildings need rigid connected machinery, intentional room air, fluids, block entities and entities retained. Terrain-matching every column would distort machines and is rejected.

### Mod-side rejection and hook composition

The installed config has airborne steep-terrain avoidance enabled, a maximum sampled height difference of 18, and radius 32. Installed `StructureDebugMixin` bytecode samples terrain through `getFirstFreeHeight(WORLD_SURFACE_WG)` and can reject generation. Thus improving clearance does not automatically bypass Create's own eligibility gate.

Primary upstream sources, pinned at `59fa1eb18a586fb314be9a232a7efd2253c869b5`:

- [StructureDebugMixin](https://github.com/SmartStreamLabs/Create--Structures-Arise/blob/59fa1eb18a586fb314be9a232a7efd2253c869b5/src/main/java/createstructuresarise/mixin/StructureDebugMixin.java)
- [CreateStructuresAriseServerConfig](https://github.com/SmartStreamLabs/Create--Structures-Arise/blob/59fa1eb18a586fb314be9a232a7efd2253c869b5/src/main/java/createstructuresarise/config/CreateStructuresAriseServerConfig.java)
- [PoolElementStructurePieceMixin](https://github.com/SmartStreamLabs/Create--Structures-Arise/blob/59fa1eb18a586fb314be9a232a7efd2253c869b5/src/main/java/createstructuresarise/mixin/PoolElementStructurePieceMixin.java)

The public branch imports Forge APIs and is not assumed byte-identical to the installed NeoForge artifact. Its nine-position terrain sample and early cancellation behavior corroborate the inspected binary. Its diagnostic selection hook also samples the shared start-height RNG before normal selection; installed bytecode has this call too. Do not introduce additional RNG draws for FTF diagnostics or assume a fresh random stream reproduces the modded baseline. Transformed runtime mixin order must be tested.

## 4. Recommended design

### A. Establish a deterministic height contract first

Implement an explicit canonical filtered terrain sampling path for placement/base-column queries, isolated from raw cells in caches. Reuse existing filters and regional cache infrastructure rather than writing a second erosion model. Audit filter-generation dependency cycles before allowing a cold query to populate it. Cache identity includes context/epoch, coordinates, sampling stage and height bounds/type where applicable; never publish a raw result as filtered.

Evaluate Minecraft's density-column path as the reference with canonical filtered inputs. Preserve FTF density, aquifers and fluid picker; do not replace it with vanilla Overworld terrain. Verify all heightmap types, empty columns, negative heights and bounds. A fast height shortcut is allowed only for proven equivalent cases, with exact fallback. A universal `+1` or `max(height, seaLevel)` patch is insufficient.

### B. Prove density-fill bounds

Compare optimized fill with a full configured-height reference in isolated test worlds. Fixtures must include high rigid foundations over low neighboring chunks, every terrain adaptation mode and junctions, padded/noise-cell boundaries and negative minY. If the optimization cannot conservatively bound contributions, use full height for the affected case. Do not remove the optimization globally without measuring cost. Any conditional optimization must produce exactly the reference's block states at NOISE.

### C. Explicit placement policies

World-owned policy data selects exact structure IDs or explicit tags with deterministic precedence; no name guessing or automatic opt-in for all jigsaw structures. Unknown structures keep upstream behavior after universal query fixes. Proposed profiles: `surface_building`, `natural_landmark`, `airborne`; underwater/underground/other dimensions remain pass-through unless separately supported. Existing village and subterranean adaptations continue to own their current structures.

Begin with observation-only policies, then site/height selection without added terraforming. Evaluate the original candidate first and retain it if valid. Optional small same-start-chunk retries use bounded independent deterministic choices and retain normal structure-set selection, biome restrictions, exclusion zones, references and upstream cancellation. Check every transformed occupied chunk and influence domain against the engine's supported reference/dependency coverage; reject/re-anchor templates that exceed it. No brute-force search across neighboring chunks or hidden spawn-rate changes.

For ground support, let `h_i` be the first free terrain height at support column i, `q_i` the template contact block's local Y, `e_i` permitted embed depth, and `f_i` permitted added support depth. A proposed origin Y must satisfy:

```text
max_i(h_i - q_i - e_i) <= originY <= min_i(h_i - q_i + f_i)
```

Choose the closest feasible integer height to the original placement, with a fixed tie rule. When the interval is empty, retry within limits or reject. For a conservative terrain-height interval, use its upper bound for collision/embed and lower bound for required support. This formula is only the contact constraint: actual occupied-volume collision, fluids, ceilings and processor effects must also pass.

### D. Airborne clearance without altering terrain

Preserve intended absolute altitude whenever it already clears the terrain. For each footprint column with occupied structure blocks, let `b_i` be its lowest occupied block's original absolute Y and `c` required clearance. A candidate lift is `max(0, max_i(h_i + c - b_i))`. Check topmost occupied blocks, dimension padding, maximum permitted lift and total vertical bounds. Avoid double-adding original absolute Y as an above-ground offset. Sparse sampling alone cannot prove clearance; refine or use a conservative terrain envelope.

Use coherent translation of the whole assembled structure or deterministic regeneration at the resolved anchor. Pieces, junctions, stub position, bounding boxes, references, entities and serialized state must agree. Respect Create's separate rejection decisions; config changes that broaden its eligibility are an explicit pack-author choice, not an FTF default.

### E. Optional local adaptation, separately gated

Only reviewed natural profiles may replace canopy-box beard influence with root-mask influence and preserve terrain against unwanted explicit template air. Preserve intentional mushroom interiors where declared; ambiguous templates stay observation-only. Only reviewed buildings may receive limited local support. An extra foundation layer cannot be called bounded if the inherited full-box beardifier still modifies terrain outside the declared domain; the combined effect must be tested.

Prefer deterministic structure-owned data evaluated per affected chunk at the appropriate stage. Do not perform cross-chunk writes, modify shared terrain cells, mutate registry templates, or install giant hidden support platforms. Reject land placements that require river obstruction, ocean filling or terrain changes beyond the selected budget. Persist adaptation data needed when pieces generate across saves/reloads.

## 5. Alternatives and tradeoffs

| Approach | Benefit | Cost / reason for decision |
|---|---|---|
| Correct queries only | Broad API improvement with no terrain retuning | Does not solve footprint geometry, explicit air or fixed-altitude sky structures. Required first stage. |
| Datapack-only patches | Simple per-ID projection/processors | Cannot repair FTF cache semantics or fill optimization; useful for authored profiles, not complete solution. |
| Global terrain flattening | Forces more structures to fit | Violates the user's terrain-quality goal. Rejected. |
| Global slope rejection | Easy to implement | Can silently remove exploration content, rejects large trees based on canopy and excludes legitimate airborne placements. Rejected as default. |
| Terrain-matching projection | Useful for selected paths/features | Warps rigid machines/buildings and natural templates. Not a generic conversion. |
| Explicit bounded policies | Preserves intended structure roles and permits per-template review | Requires profile maintenance, runtime fixtures and diagnostic reasons. Recommended. |

## 6. Verification research still required

Capture exact user-world preset/seed/coordinates if available; otherwise build independent reproducible fixtures and label them as such. Validate raw versus filtered, density-column versus NOISE, and final surface/fluid stages separately. Pure unit tests do not validate mixin order, structure references, template processors or natural spawning.

Run identical-seed separate worlds for installed baseline, corrected query/fill reference, and each policy stage. Include mod-free FTF, each target mod independently, both together, and the effective pack stack. Minecraft-default terrain is a diagnostic control, not the quality baseline for FTF. Include both supported loaders with available compatible artifacts; do not claim Fabric compatibility from a NeoForge-only runtime test.

Track candidates, upstream rejects, policy rejects, successful starts, generated pieces, final defects and valid structures per explored area. A lower defect percentage achieved by removing nearly all structures is not success. Freeze survey areas/seeds before comparing and count all failures. Benchmark without verbose diagnostics. Proposed numeric gates and staged deliverables are in the spec; none has passed yet.

Re-run artifact research with:

```sh
python3 .planning/research/structure-placement/inspect_artifacts.py /path/to/mods > /tmp/structure-inventory.json
```

No production code, installed jars, configs or worlds were changed by this research.
