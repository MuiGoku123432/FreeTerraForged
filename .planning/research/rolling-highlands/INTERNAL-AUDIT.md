# Rolling Highlands — internal reuse and integration audit

Date: 2026-10-27. Checkout: `c5076eb8130a28f10e625ed28d5a9d7aceeb7188`.
Scope: additional source investigation supporting [ALGORITHMS.md](ALGORITHMS.md). No production fixes or runtime reproduction were performed.

## Reuse decisions

| Existing component | Decision | Reason / required boundary |
|---|---|---|
| `Noises.perlin`, `perlinRidge`, `simplex`, `warpPerlin` | Reuse underlying noise/factory primitives | Explicit scale and root-seed offsets; respect 0–1 output convention |
| `Interpolation.CURVE4` | Reuse for lateral smooth transitions | It calls quintic interpolation despite its name |
| Advanced continent shape/coast calculations | Reuse behind a new mode-owned adapter if domain checks pass | Preserve lattice identity, bounded warp and real coast outlets |
| Complete `Populators.makeMountains*` helpers | Do not directly use as the new amplitude contract | Existing scaling, optional fancy erosion and terrain metadata are intertwined |
| Existing mountain/terrain labels and climate parameter patterns | Reuse after explicit classification | Keep height generation separate from biome erosion/weirdness |
| Existing radial `SimpleRiverGenerator` roots/forks | Replace for RH | Does not derive paths/profiles from shared regional terrain |
| `UpliftRiverCarver` | Reference its zoning, not reuse its complete implementation | Hard-coded stepped water and terraces conflict with the new model |
| `Network` recursive carving | Adapt carefully or use an RH segment index | Children inherit parent-warped coordinates; independent post-warp breaks shared geometry |
| `RiverCache` ownership concept | Retain ownership principle; add bounded plan-specific storage | Entry capacity/expiry is not a proven byte bound for large graphs |
| `Erosion`, `CliffRelaxation` | Reuse with explicit RH unit/spacing inputs | Their current worldHeight assumptions differ from Levels storage in tall worlds |
| `TerrainCeiling` | Reuse its monotone compression concept with mode-aware placement | Current enablement and repeated application do not match the new contract |
| Raw/filtered runtime caches and preview orchestration | Retain infrastructure | Planner must be pure and avoid recursive cache dependencies |

## 1. Actual height units

Evidence:

- [Levels.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/heightmap/Levels.java): `unit`, both `scale` overloads, `waterY` and `water`.
- [WorldSettings.java](../../../common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/settings/WorldSettings.java): `Properties.terrainScaleFactor()` returns `min(worldHeight,256)`.
- [Erosion.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/filter/Erosion.java): constructor stores `levels.worldHeight`; slopes multiply by it and output deltas divide by it.
- [CliffRelaxation.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/filter/CliffRelaxation.java): block-labelled relief thresholds divide by worldHeight.

Observed source mismatch: normalized terrain height is converted to blocks by terrainScaleFactor, while the filters use worldHeight for several equivalent-looking conversions. For a 1024-high world the factors differ by four. This is sufficient to reject treating these filters as automatically calibrated to the new block-valued controls; it is not a runtime measurement of the full visual effect, since masks and caps interact.

Recommendation: add explicit unit inputs for RH and preserve legacy behavior. Do not globally change the existing filters while promising bit-identical UPLIFT/MULTI_IMPROVED output. Add tall-world and short-world numerical fixtures during implementation.

## 2. Mountain helpers hide more than a height multiplier

Evidence: [Populators.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/terrain/Populators.java), `makeMountains`, `makeMountainChain`, `makeMountains2`, `makeMountains3`, `makeFancy`; [TerrainPopulator.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/terrain/populator/TerrainPopulator.java), `make` and `apply`.

`makeMountains` derives its internal horizontal scale from `settings.horizontalScale`, creates ridged noise, modulates/warps it, optionally applies fancy erosion, and multiplies by a vertical coefficient. `TerrainPopulator.make` then also stores `settings.verticalScale` as heightScale; `apply` multiplies by that. In the nonlegacy mountain-chain caller, the supplied verticalScale already includes the mountain setting. A naive wrapper can therefore square a setting that the UI author expected to apply once.

The private `makeMountains` signature also accepts a `horizontalScale` argument which is not used in its shown scaleH calculation. Increasing the caller's argument is not proof that the underlying mountain wavelength changes.

`makeMountains3` explicitly terraces its shape. It is unsuitable as the default broad Rolling Highlands surface without deliberately disabling that step. `makeFancy` invokes a separate noise-module erosion, while the later cell filter is another erosion stage. Do not stack both unconsciously and attribute all results to one erosion knob.

Recommendation: compose new dimensionless noise fields with one explicit block amplitude; reuse terrain metadata patterns separately. Leave legacy helpers intact.

## 3. Noise range and warp contracts

Evidence: [Noises.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/module/Noises.java), [Perlin.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/module/Perlin.java), [PerlinRidge.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/module/PerlinRidge.java), [Perlin2.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/module/Perlin2.java), [NoiseUtil.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/NoiseUtil.java), [DomainWarp.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/domain/DomainWarp.java).

The public factories wrap several noise modules with explicit seeds. Perlin/Perlin2 use the clamping four-argument NoiseUtil.map on their sums. DomainWarp maps advertised input bounds to [-0.5,0.5] before multiplying by strength. Thus the inspected finite Perlin2 continent warp has maximum per-axis displacement strength/2, not strength. Generic Map itself does not clamp; do not extend this bound to arbitrary composed noise without checking its real range.

Seed.offset returns a fresh seed based on root+offset, whereas next advances mutable state. Use distinct named offsets for new fields and stable hashing for node tie-breaks. Do not alter legacy construction order.

## 4. Finite ownership is available; outlet correctness is separate

Evidence: [AdvancedContinentGenerator.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/continent/advanced/AdvancedContinentGenerator.java), `apply`, `createWarp`, `getCorrectedContinentCenter`.

The 3×3 candidate search yields the finite bound derived in ALGORITHMS section 4. It avoids an unbounded scan for a continent's extent. However the method stores corrected center coordinates in the Cell, not its original lattice indices. Expose the latter in an RH owner descriptor rather than inferring them from the corrected center.

At a genuine bisector the unmodified distance/coast mapping tends toward ocean, which is encouraging for continent-local planning. The limited candidate searches, coordinate warping, coast/control settings and optional islands still need explicit actual-boundary sampling. The bound does not prove that every land component is ocean-separated. Keep ownership and drainage validation distinct.

## 5. Preview spacing and filter support

Evidence:

- [PreviewGrid.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/heightmap/PreviewGrid.java), `generateZoomed`: terrain is sampled using zoom-scaled world positions, then optional filters consume the grid.
- [FilterGrid.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/filter/FilterGrid.java): exposes origin/size/border, but no world-space sample spacing.
- [RegionFilterGrid.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/filter/RegionFilterGrid.java): 64-block region plus 12-block halo, total 88×88 cells.
- Erosion/CliffRelaxation compute per-cell world positions from origin+index and assume adjacent samples are a block apart.

Consequently, a zoomed preview is not sufficient evidence for correctly scaled final erosion. RH should either filter at actual world-space resolution and downsample, or carry explicit sample spacing through its filter path. Preserve preview network/landform identity regardless of visual detail level.

Both relaxation stages have three neighbor passes. Erosion reads slopes (radius one), a radius-two minimum and radius-two blur for its gate, and a radius-three envelope. A conservative height dependency radius for these three stages is 3 + max(1+2+2,3) + 3 = 11 blocks. This makes the existing 12-block halo plausible for current height processing, but metadata/postprocessors and any new kernels require their own support audit. Do not claim seam freedom from this arithmetic alone.

## 6. Ceiling handling

Evidence: [TerrainCeiling.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/heightmap/TerrainCeiling.java), [Heightmap.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/heightmap/Heightmap.java), [TerrainFilters.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/filter/TerrainFilters.java).

The ceiling is enabled when mountainVariety > 0, independently of the proposed new mode. Heightmap can apply it before rivers and TerrainFilters can apply it again afterward. The compression mapping is monotone but not generally idempotent: a compressed value can still exceed compressionStart and be compressed again. A new water graph cannot assume one compression pass or that its bed remains unchanged afterward.

RH should own enablement/order and reserve profile headroom. Compare dry terrain before/after filtering and keep wet-channel bounds coherent. Legacy ordering remains unchanged.

## 7. Precision and metadata are part of the formula boundary

The inspected Heightmap methods accept float world positions. Java single-precision floats cannot distinguish every block near the world border. The numerical probe demonstrates aliasing near 30 million. A new double planner must receive original integer/double coordinates at its entry point; calling it after float truncation is too late. Add an RH-specific precise path or appropriately scoped overloads through runtime and preview callers, with legacy behavior retained.

Cell metadata also matters. Existing riverWaterLevel meanings differ across consumers (some treat it as a tag/positive flag, while actual height is recomputed from waterTable). Define an explicit valid flag and one normalized/relative water-height convention for RH. Update Cell copy/reset and owner query paths if new fields are introduced. Retain terrain classification, biome erosion/weirdness, riverMask, erosionMask, riverZone and flow data coherently; height-only correctness is not an end-to-end implementation.

## Implementation audit checklist

- New math uses terrainScaleFactor exactly once at the Cell boundary.
- New noise amplitudes and wavelengths each have one owner; superseded legacy scales do not reapply.
- Regional sampler is pure and cannot call final rivers, climate, RawChunkCache or FilteredChunkCache.
- Owner bound, actual coast exits, sampling cap and settings-combination validation are separate checks.
- Geometry refinement and segment-query halos include every valley/warp influence.
- Junctions and lakes supply shared water constraints before profile solving.
- Final water, beds, banks, sealing and normal water updates are checked after filters/features.
- Precise coordinates arrive before any float conversion; preview spacing is explicit.
- New plan bytes/in-flight builds are capped; owner close and cancellation release resources.
- Legacy settings, random draws, filter scales and ceiling order remain on their original path.
