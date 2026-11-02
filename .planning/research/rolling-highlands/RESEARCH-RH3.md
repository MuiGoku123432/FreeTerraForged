# Rolling Highlands RH3 research: river/lake placement and mountain/valley form

Date: 2026-10-27. Research only; no code changed. Based on build `1.0.0014-rh2` (model 2).

Four parallel research tracks (procedural-terrain literature, quantitative geomorphology, erosion/landform
techniques, Minecraft prior art + Streams Reflowing) plus a whole-continent diagnosis of RH2. Every external
claim below carries its source URL. Items marked **[B]** are background knowledge not re-read this session;
**[D]** are values derived for 1 block = 1 m.

## 1. Diagnosis of RH2 (seed 0 and 42, origin continent, 16-block hillshade)

Measured with a temporary harness (not committed):

| Measure | Seed 0 | Seed 42 | Real-world reference |
|---|---|---|---|
| Lake share of land | 4.3% | 8.1% | 1.8–3.7% of land globally, concentrated in glaciated terrain; unglaciated fluvial uplands have very few natural lakes (§3.6) |
| Channel samples > 60 blocks below the *uncarved* visible land | 34.5% | 31.3% | rivers should sit at the lows of the rendered surface |
| Mean junction angle (2-branch junctions) | 26.5° | 28.8° | ~72° humid, ~45° arid (Seybold 2017) |
| Dry-land slope histogram (5° bins) | 22/21/17/15/10/6/4/3/1/1 % | similar | mean slope saturates near 30°; tail > 40° only at cliffs/headwalls |

Visual findings (`common/build/rolling-highlands/continent-*.png` when regenerated):

1. **Mountains are noise blobs.** Ridged-noise bumps and dimples cover the land uniformly; ridges are isolated arcs, not
   dendritic divides; valleys are invisible at continent scale; rivers are thin lines that do not sit in dominant valleys.
2. **Rivers ignore the rendered relief.** Routing uses the planning surface (`upland + 40·ridge + 24·foothill`), but the
   rendered land adds `160·ridge + 48·foothill`. A third of channel length is a gorge cut > 60 blocks into mountains the
   router never saw.
3. **Lakes cluster in the continent interior,** in low patches of the upland noise, as large filled noise pits.
4. **Junctions are too acute** (≈27°) and many rivers are short parallel coastal streams.
5. **Coasts** keep the straight Voronoi edges of the continent cells in places, and the ease-out rise produces a dark
   steep band on some shores.

All five match failure modes the literature describes (sections 2–4).

## 2. Root causes, stated by the literature

- **The terrain you route over is not the terrain you render.** Every hydrology-consistent method derives the final
  elevation from the river network. Génevaux et al. 2013 list exactly this failure ("flat rivers can cut through high
  mountains") —
  https://www.cs.purdue.edu/cgvlab/www/resources/papers/Genevaux-ACM_Trans_Graph-2013-Terrain_Generation_Using_Procedural_Models_Based_on_Hydrology.pdf
- **Smooth tilted surfaces give straight, parallel rivers.** "A slowly decreasing [uplift] gradient leads to a very straight
  erosion in the gradient direction, whereas a set of steps of constant values gives more random valleys" (Cordonnier
  2016, §6.4) —
  https://www.cs.purdue.edu/cgvlab/www/resources/papers/Cordonnier-Computer_Graphics_Forum-2016-Large_Scale_Terrain_Generation_from_Tectonic_Uplift_and_Fluvial_.pdf
  Without variable erodibility "all valleys strike perpendicular to the axis of the mountain" (Cordonnier 2018) —
  https://www.cs.purdue.edu/cgvlab/www/resources/papers/Cordonnier-IEEE_Transactions_on_Visualization_and_Computer_Graphics-2018-Sculpting_Mountains_Interactive_Terrain_Modeling_Based_on_Subsur.pdf
- **Filled noise pits are not lakes.** Dendry measured depression area 35.9% for multifractal and 24.5% for ridged noise
  vs 3.3% for a real Alps DEM —
  https://www.cs.purdue.edu/cgvlab/www/resources/papers/Gaillard-2019-Dendry_A_Procedural_Model_for_Dendritic_Patterns.pdf
  Stream-power landscapes do not create closed depressions (fastscapelib) —
  https://raw.githubusercontent.com/fastscape-lem/fastscapelib/main/include/fastscapelib/eroders/spl.hpp
  Real lakes come from specific processes (glacial basins, tectonic basins, oxbows, landslide/fan dams) —
  https://en.wikipedia.org/wiki/Lake
- **Ridged noise has no erosion structure:** "once you've seen one patch of land, you've basically seen it all"; real
  fractal shape comes from "smaller streams merging into larger streams" — https://github.com/dandrino/terrain-erosion-3-ways ;
  ridged noise "looks uniform and contextless" — https://www.decarpentier.nl/scape-procedural-extensions
- **Uniform valley slopes** follow from an area-independent cross-section; real channels obey S = k_s·A^(−θ), giving
  concave long profiles — https://lsdtopotools.github.io/LSDTT_documentation/LSDTT_chi_analysis.html

## 3. Quantitative targets (calibration and tests)

| Metric | Target | Source |
|---|---|---|
| Bifurcation ratio Rb / length ratio RL / Tokunaga c | 3–5 (≈4.5) / 2–2.5 / ≈2.3 | https://arxiv.org/html/2106.02991 |
| Hack exponent (L = C·A^h) | 0.55–0.6; L ≈ 1.3·A^0.6 km [B/D]: 10 km² → 5 km, 100 km² → 20 km | https://en.wikipedia.org/wiki/Hack%27s_law |
| Junction angle | ~72° humid, ~45° arid | https://doi.org/10.1002/2016GL072089 |
| Drainage density / valley spacing | 2–10 km/km² humid uplands [B]; spacing ≈ 1/Dd → 100–500 m [D] | https://en.wikipedia.org/wiki/Drainage_density ; https://www.nature.com/articles/nature08174 |
| Concavity θ | median 0.425 (IQR 0.225–0.575) | https://doi.org/10.1002/essoar.10505724.1 |
| Channel gradient by type | riffle-pool < 1–2%; plane-bed 1–5%; step-pool 5–20%; cascade > 10–15% | https://en.wikipedia.org/wiki/Channel_types |
| Width vs area | w ∝ A^0.3–0.5; ≈2–4 m @1 km², 5–9 @10, 12–25 @100, 30–60 @1000 km² [D] | https://doi.org/10.1029/2000WR900393 |
| Hydraulic geometry | w ∝ Q^0.5, d ∝ Q^0.4, v ∝ Q^0.1 | https://pubs.usgs.gov/pp/0252/report.pdf |
| Valley-floor width | ∝ A^(0.30 ± 0.06) | https://doi.org/10.5194/esurf-10-437-2022 |
| Meanders | wavelength 7–14·w; radius 2–3·w; belt 15–18·w; sinuosity ≥ 1.5 only on gentle wide floors | https://en.wikipedia.org/wiki/Meander ; https://pubs.usgs.gov/publication/pp282B |
| Hillslopes | convex crests, planar flanks; mean slope saturates ≈30° | https://doi.org/10.1029/1998WR900090 ; https://doi.org/10.1130/G23641A.1 |
| Lakes | 1.8–3.7% of land globally, high only in glaciated districts; size tail τ ≈ 2.13; small lakes ≥ 3× rarer than the power law; every lake has an outlet (except arid) | https://www.nature.com/articles/srep29633 ; https://www.nature.com/articles/ncomms13603 |
| Depression area | a few percent at most (Alps 3.3%) | Dendry PDF above |

Key placement rules (geomorphology track): rivers first, every cell drains to an outlet; valleys evenly spaced, ridges are
the dual network; tributaries join at grade (Playfair, https://en.wikipedia.org/wiki/Playfair%27s_law) at ~60–75°;
channel/valley size scales with drainage area; meanders, oxbows and backswamps only on gentle wide floors
(https://en.wikipedia.org/wiki/Backswamp); lake density depends on glacial history (cirque tarns, paternoster chains,
moraine lakes — https://en.wikipedia.org/wiki/Paternoster_lake , https://en.wikipedia.org/wiki/Cirque); knickpoints from one
base-level event share an elevation.

## 4. Techniques that fit the RH architecture

### 4.1 Elevation derived from drainage (stream power) — the structural fix
- Steady state of ∂z/∂t = U − K·A^m·S^n gives `z_i = z_receiver + Δx·(U/(K·A^m))^(1/n)`, m/n ≈ 0.4–0.5: ridges
  become divides, profiles become concave, tributaries meet at grade.
  - Tzathas et al. 2024 (analytic, time as a "maturity" slider, multigrid) — https://doi.org/10.1111/cgf.15033 ; code
    https://gitlab.inria.fr/landscapes/analytical-terrains
  - Braun & Willett implicit O(n) solver via fastscapelib (mountain example: K=2e−4, m=0.4, n=1) —
    https://fastscapelib.readthedocs.io/en/latest/examples/mountain_py.html
  - Schott et al. 2023 "uplift domain" authoring (paint uplift, relief emerges) —
    https://aparis69.github.io/public_html/projects/schott2023_Uplift.html ; shader
    https://raw.githubusercontent.com/H-Schott/StreamPowerErosion/HEAD/data/shaders/spe_shader.glsl
  - Cordonnier 2016 (Poisson/Delaunay graph, lake overflow via passes, 30° thermal cap modulated 6°–54°).
- Fit: one O(N) pass up the drainage tree per iteration; 10–30 route+solve iterations on ≤ 65,536 nodes is milliseconds.
  Existing upland/ridge/foothill noise becomes **uplift U(x)** and **erodibility K(x)** (banded/anisotropic) instead of
  height. Use plateau/step-like uplift near coasts rather than a smooth tilt.

### 4.2 Rendered land from the plan, not beside it
- Génevaux crest rule: crest height at a Voronoi vertex = max(adjacent channel z) + λ·d, λ ∈ [0, 0.25].
- Argudo et al. 2019 valley interpolation between river and ridge with floor width ∝ A^0.4 —
  https://github.com/oargudo/orometry-terrains (`synthesis/divtree_to_dem.py`).
- Per column: bicubic (C1) sample of the plan elevation plus its analytic gradient; extra relief goes into U before
  solving, never added after routing. Detail amplitude × smoothstep(height above nearest channel / local relief).

### 4.3 Hillslope detail that follows that drainage
- Rune Skovbo Johansen's erosion filter (gradient-aligned gully stripes in Worley cells, octaves branch from the
  accumulated gradient, fade toward ridges/creases) — https://blog.runevision.com/2026/03/fast-and-gorgeous-erosion-filter.html ;
  Phacelle noise https://blog.runevision.com/2026/01/phacelle-cheap-directional-noise.html ; Unity params
  https://github.com/lpmitchell/AdvancedTerrainErosion ; Minecraft port CATTO https://modrinth.com/mod/catto
  - Per column, ~16 splats/octave; 4–5 octaves from ~128–192 blocks down to ~8–12.
  - Use fade target `2·HAND/relief − 1` (height above nearest drainage / local relief) instead of altitude — the
    author notes altitude fade fails where a valley is higher than another peak.
  - Use doubles or integer cell hashing with the world seed (CATTO uses float, unseeded hashing).
- Cheaper complements: IQ derivative-damped fBm https://iquilezles.org/articles/morenoise/ ; Swiss/Jordan turbulence;
  Tectonic's slope-shifted ridge detail
  https://raw.githubusercontent.com/Apollounknowndev/tectonic/rewrite-squared/src/common/main/resources/resourcepacks/tectonic/data/tectonic/worldgen/density_function/mountain_ridges/shifteddetail.json
- Padded-chunk finishing pass: nonlinear hillslope diffusion (Roering) + talus cap, Jacobi-only (seam-free); avoid
  droplet erosion (order-dependent, truncated at tiles — TerraForged 0.3, World Machine tiled-build notes
  https://help.world-machine.com/topic/device-erosion/).

### 4.4 Depressions and lakes by process
- Priority-Flood+ε and flat resolution (convergent drainage on flats) — https://arxiv.org/abs/1511.04463 ,
  https://arxiv.org/abs/1511.04433
- Lindsay 2016 selective breach-vs-fill (breach if cut depth/length small, else lake) — https://doi.org/10.1002/hyp.10648 ;
  WhiteboxTools `breach_depressions.rs`.
- Basin graph / spill passes as a minimum spanning tree, w = max(w_receiver, z) —
  https://esurf.copernicus.org/articles/7/549/2019/
- Lake sources worth modelling explicitly: tectonic/structural basins (closed lows in U), glacial-style over-deepenings in
  upper main valleys and cirque tarns below crests (Cordonnier 2023 glacial —
  https://www.cs.purdue.edu/cgvlab/www/resources/papers/Cordonnier-ToG-2023-forming.pdf ), paternoster chains, oxbows on
  low-slope meanders (cutoff rule from Paris 2023 — https://github.com/aparis69/Meandering-rivers ).

### 4.5 Network geometry and channel type
- Grid bias: jitter nodes ±0.25–0.4 cell with true distances, randomised single receiver, MFD (exponent 1–1.3) for the
  area that sets width; Cordonnier 2016 preferred Poisson + Delaunay because "the randomized triangular graph generates
  more plausible results."
- Channel type by local slope (Rosgen / Montgomery–Buffington): steep → narrow V, near-straight, step-pools;
  moderate → plane-bed; gentle (< ~1–2%) → floodplain, meanders; very gentle near coast → braided/delta
  (Peytavie 2019 riverscapes —
  https://www.cs.purdue.edu/cgvlab/www/resources/papers/Peytavie-Computer_Graphics_Forum-2019-Procedural_Riverscapes.pdf ).
- Meanders from the sine-generated curve φ(s) = ω·sin(2πs/M) (Langbein & Leopold 1966 —
  https://pubs.usgs.gov/pp/0422h/report.pdf ), λ ≈ 7–12·w, bounded by the floor.
- Sub-128-block tributaries: Dendry hierarchy seeded from plan channels, built once per chunk —
  https://github.com/mgaillard/Noise
- Junctions: unequal orders near-perpendicular, equal orders acute (Howard 1971 via Dendry: cos α = S_main/S_trib);
  mouths in concave coastline segments, large mouths kept apart (Génevaux §4.1).

### 4.6 Minecraft prior art
- TerraForged 0.3 rivers follow a downhill cell graph with warped segments, lakes at sources, U/linear valley blend —
  https://raw.githubusercontent.com/TerraForged/TerraForged/0.3.x/src/main/java/com/terraforged/mod/worldgen/noise/continent/river/RiverGenerator.java
- TerraFirmaCraft: watershed partitions, drain-to-source growth, per-context valley profiles (BANKED, FLOODPLAIN, CANYON,
  TALUS, TERRACES…) — https://alcatrazescapee.com/rivers/ ,
  https://raw.githubusercontent.com/TerraFirmaCraft/TerraFirmaCraft/1.21.x/src/main/java/net/dries007/tfc/world/river/RiverBlendType.java
- Vintage Story Rivers: grow upstream from coast with a "don't go downhill" error budget —
  https://raw.githubusercontent.com/glutzer/Rivers-Mod/master/Rivers/src/Rivers/RiverRegion.cs
- Tectonic: underground rivers where mountains are too tall instead of 200-block ditches —
  https://raw.githubusercontent.com/Apollounknowndev/tectonic/HEAD/src/common/main/resources/resourcepacks/tectonic/data/tectonic/worldgen/density_function/underground_river/total.json
- Player complaints that RH must avoid: rivers all at one level (RTF #55), flow reversing on bends (FTF #51/#105),
  200-block ditches through mountains (FTF #265 https://github.com/ETcodehome/FreeTerraForged/issues/265), flat cut-out
  lakes, rivers starting full-width from nowhere, square channels deeper than wide (Tectonic #367).

## 5. Streams Reflowing (SR) contract

SR 2.15.2 reads FTF reflectively (from `javap` of the jar in the F&F Terra instance; SR is All-Rights-Reserved). Changelog:
https://api.modrinth.com/v2/project/streams-reflowing/version

- Entry points: `FTFRandomState.generatorContext()`, `GeneratorContext.lookup/levels`, `WorldLookup.applyCell(cell,x,z,false)`
  (unfiltered unless already cached), `Cell.terrain/height/riverWaterLevel/flowAngle`, `TerrainType.RIVER` (reference
  equality), `IFlowFieldHolder.freeterraforged$getFlowField()`, `ChunkFlowField.hasRivers/getRawGrid`. Renaming any of
  these silently disables the bridge. Gate: `reterraforgedCompat=true`.
- Water surface for RIVER cells: `max(riverWaterLevel, levels.water) × terrainScaleFactor`; must match the placed top
  water block within ±1 (SR's `FtfFlowAudit`).
- SR plans its own watersheds (`watershedSize`, default 768 blocks here) from terrain heights, carves its own streams and
  lakes at the `lakes` feature step (after FTF's surface pass), ends streams at `outletBiomes`
  (`#is_river`, `#is_ocean`, `#c:is_swamp`, `#c:is_wet`), and gives terrain-mod rivers a current.

RH implications:
1. **Wetland pools are invisible to SR** (terrain WETLAND, not RIVER): SR may carve streams or place lakes in them. Either
   mark pool cells RIVER, give wetlands `#c:is_swamp/#c:is_wet` biomes (outlets), or blacklist them for streams.
2. **Lakes with zero flow magnitude** fall back to SR's own orientation; RH only drifts near outlets. Give whole lakes a
   weak outlet-directed current if SR's fallback disagrees in game.
3. **Height consistency:** SR samples `applyCell(..., false)`; RH filtered heights differ from unfiltered outside protected
   zones — keep RH filter strength near channels at 0 (already) and check banks.
4. **Thread safety:** SR calls `applyCell` from its own threads ahead of chunk generation across whole watersheds — RH2's
   lock-free plan cache and deterministic plans already satisfy this.
5. Many of SR's own fixes (rivers starting full width, raised lake rims, lakes cut into hillsides) are the same visual
   problems RH must avoid; SR will build on RH's rivers, so RH's errors propagate into SR's streams.

## 6. Recommended RH3 changes (ranked)

1. **Stream-power plan (structural).** Replace "planning surface + independent mountain amplitude" with a steady-state
   (optionally transient, Tzathas) stream-power elevation on the continent plan: U from the existing noise families
   (step/plateau-like, no smooth coastal tilt), K from banded noise, m/n ≈ 0.45, talus cap modulated 30–45°, ocean as
   base level, 10–30 route/solve iterations, rescale to the mountain-height control. Ridges become divides; rivers sit
   in the lows of the land that is rendered. *Fixes the gorge and noise-blob problems at the root.*
2. **Render from the plan.** Bicubic plan elevation + gradient per column; valley floor ∝ A^0.3–0.4 and channel width ∝
   A^0.35–0.4 from hydraulic geometry; ridges from the plan, detail masked by height-above-channel. Remove the
   `160·ridge` post-routing term.
3. **Hillslope detail:** Rune's erosion filter per column, fed by the plan gradient and a HAND-based fade target; Roering
   diffusion + talus as the padded-chunk finishing pass.
4. **Lakes by process, few by default:** breach-vs-fill (Lindsay) on the final plan surface; explicit sources
   (structural basins in U, upper-valley over-deepenings, cirque tarns, oxbows); target ≈ 1–3% lake area
   (higher only behind a "lake district / glaciated" control); every lake has a pass outlet; lake nodes are no-erosion
   zones in the solver.
5. **Network realism:** jittered/irregular nodes, randomised receivers, Barnes flat resolution; junction angles toward
   60–75°; coastline mouth spacing; channel type and cross-section by slope class; meanders only on low-slope reaches via
   the sine-generated curve.
6. **Sub-grid tributaries:** Dendry-style hierarchy (64/32/16 blocks) seeded from plan channels, cached per chunk.
7. **SR hand-off:** decide wetland visibility (RIVER pools or wet biomes), lake currents, and add an SR-contract test that
   reads cells through `applyCell(…, false)` exactly as SR does.
8. **Realism metrics in the quality harness:** slope–area/χ concavity, Hack exponent, Horton/Tokunaga ratios,
   junction-angle histogram, lake area fraction and size distribution, depression fraction, slope histogram, valley
   spacing (spectral peak), plus the continent hillshade render.

Suggested order: 1 + 2 together (biggest change, model version 3), then 4 and 5, then 3, then 6–8. Items 7–8 can start
immediately because they are independent of the terrain model.
