# Rolling Highlands experimental build (RH1)

Branch: `feature/rolling-highlands`, based on `1_21_1-unstable-custom` at `c5076eb8130a28f10e625ed28d5a9d7aceeb7188`.

This delivers the playable experimental slice requested by the user. The earlier SPEC/ALGORITHMS documents describe the design target; the implementation and limits below supersede their proposed constants for RH1. Exploration and comparison tuning remain open.

## Trying it

Install the `1.0.0014-rh1` jar for the appropriate Minecraft 1.21.1 loader. Create a **new world**, open the FTF preset editor, and select **Rolling Highlands (Experimental)** under World → Continent Type. On Terrain, start with spacing 4096, mountain height 160, foothill relief 48, and dry valley width 256. Save the preset and create the world normally. A world height of 512 and sea level 63 provide suitable headroom.

Use fresh experimental worlds when changing generation settings or trying a later model revision. Existing worlds are not converted. Legacy UPLIFT and MULTI_IMPROVED presets retain their existing generation path and enum identities.

Supported control ranges:

| Control | Default | RH1 range | Meaning |
|---|---:|---:|---|
| Upland spacing | 4096 | 4096–8192 blocks | Regional relief and drainage spacing; independent of continent size |
| Mountain height | 160 | 32–256 blocks | Added major ridge amplitude |
| Foothill relief | 48 | 0–96 blocks | Added connector and lower-ridge amplitude |
| Dry valley width | 256 | 64–512 blocks | Dry floor width, excluding water and banks; modified by discharge and confinement |

Continent scale supports 1000–5000; choosing the mode clamps that slider into this range. At least 320 blocks above sea level are required. Invalid saved profiles and unsupported model versions fail validation rather than silently changing generation. The first version uses fixed wet-channel/depth parameters and no extra island overlay. The relevant editor pages explain these limits.

## Generation design

- A new continent implementation owns the regional relief, drainage and valley shaping. It reuses Advanced continent site placement, skipping and size variance, with bounded double-precision coastal warp and an explicit lattice owner. It does not enter the legacy random river generator.
- Every owner has a world-aligned bounded grid (at most 65,536 cells), bounded by clipped selection polygons expanded by the warp. Priority flood starts at actual ocean cells. Six-neighbor triangular connectivity matches lake interpolation. Contributing area selects channels and propagates them downstream.
- River reaches receive two bounded smoothing passes, then samples at most 16 blocks apart. An interval solver fits a non-increasing downstream water profile jointly across each tree. Junction cores are level; lakes share fixed spill surfaces. Ordinary/mountain slope bounds are 0.12/0.35, classified by physical coarse gradient. A four-block center bed and a 44-block water-profile cut allowance retain a 48-block planning-surface bed-cut limit.
- A local upper-profile allowance of eight blocks accommodates sub-grid outlet shoulders and junction cores. The preferred water surface remains the original ground/fill target minus two. This allowance changes the local carved bank cross-section; it does not emit the priority-filled grid as elevated dry terrain. Coastal relief uses a wider, scale-aware taper to avoid demanding steep river descents on small islands.
- The fixed routing floor is `upland + 40 * ridge + 24 * foothill`. User mountain and foothill amplitudes add above that floor, so reducing those controls does not lower surrounding land below the routing surface. Neighboring outlet drainage systems contribute a divide envelope to the final ridges. Low regional saddles retain passes.
- Quintic lateral profiles form wet beds, banks, dry floors and outer valley slopes. Contributing area changes channel size; confinement narrows dry valleys. Changing dry width does not move the water profile. Existing procedural erosion and cliff relaxation operate in block-consistent units; wet heights are protected and erosion fades near valleys.
- Explicit absolute water metadata reaches the surface builder, gasket and scree paths. Legacy water calculations retain their original expression. A single RH ceiling reserves height headroom. Settings are immutable, serialized with model version 1, and copied independently by the editor.
- The cache retains eight immutable owner plans, builds under one lock, and never recursively requests final terrain/chunk caches. Coarse and refined nodes, index entries and estimated retained memory have hard limits. Cache memory estimates are conservative accounting, not measured JVM object sizes.

## Validation and remaining work

See `VALIDATION.md` for commands and measured results. The automated checks cover legacy fingerprints, river solver feasibility, terrain/profile agreement, seed/owner determinism, settings persistence, chunk-boundary agreement, and water blocks in actual FULL server chunks.

Zoomed previews use the same drainage plans but omit fine erosion. Coordinate-sensitive RH preview sampling uses doubles. The client editor has compiled successfully; interactive visual UI testing and player exploration remain to be done.

The following are intentionally still open: ground-level aesthetic acceptance, quantitative traversal/relief coverage, broader coast-control and seed stress testing, long fluid simulations, release-level performance comparisons, and exact V3 MULTI_IMPROVED comparison. The exact V3 preset/build has not been identified, so the frozen current-branch legacy terrain fingerprints are regression evidence, not a V3 aesthetic comparison.

For the first exploration pass, use seeds `0`, `1`, `42`, `8675309`, and `-314159`. Record seed, coordinates, settings and screenshots for flat stretches, disconnected-looking ridges, overly deep valleys, excessive lakes, or awkward river junctions. Change one of the four controls at a time in a new world. This is an experimental starting point for that iteration, not completion of the release acceptance criteria.
