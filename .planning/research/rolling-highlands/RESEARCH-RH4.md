# Rolling Highlands RH4 research: river planform (meanders, routing bias, confluences)

Date: 2026-10-28. Builds on RH3 (`1.0.0014-rh3`, model 3, commit `348d9a43`). Trigger: in-game review of world seed
−6240223350076425471 (Test-America-v3 preset, Streams Reflowing 2.15.2): main rivers read as straight runs with
angular, knife-edged junctions, while SR's small streams wind naturally. Plan: [PLAN-RH4.md](../../features/rolling-highlands/PLAN-RH4.md).

## 1. Diagnosis (user world, measured on the plan)

| Measure | Value | Source of the look |
|---|---|---|
| Plan grid step | **256 blocks** (default test seeds: 128) | Fixed 65,536-node budget; continent scale 5000 gives a ~34 km bounding box, so the step doubles |
| Grid nodes / land nodes / refined channel samples | 18,200 / 4,762 / 13,986 | — |
| Plan build time | 206–260 ms (default seeds at 128: 112–207 ms) | — |
| Sinuosity over 1 km, rivers > 4 km² | **1.17** (n = 587) | Hermite curves through D8 nodes; meander bulge ≤ 12% of a reach chord, only on flat reaches |
| Junction angle (tributary vs main stem, 64 blocks upstream) | mean **65°**, 20 of 75 below 30° | Tributaries can only join at grid nodes from one of 8 directions |
| Confluence point | knife-edged dry wedge | Each channel's banks come from its own centreline; the wet owner is the lowest bed; nothing rounds the inner corner |
| Small winding streams | not in FTF's water map | They are Streams Reflowing streams (block-resolution tracing, `meanderStrength=1.2`) |

Channel geometry today (`HighlandDrainage.build`): D8 routing on a regular lattice → channel nodes snapped ≤ 0.3 step
toward the local low and jittered ±0.15 step → tangent-continuous Hermite per reach → sine bulge `MEANDER·chord·noise`
→ 16-block refined samples. The rendered valley floor, banks and channel are all built from that one centreline.

## 2. How legacy FTF draws rivers (all other continent types)

`SimpleRiverGenerator` / `BaseRiverGenerator` (Multi, Single, Multi-Improved, Uplift):

- Main rivers: `riverCount` straight lines from 400 blocks..45% of the way out from the continent centre to the coast,
  at random angles.
- Forks: up to depth 3, every 10–25% (main) / 25% (fork) of the parent, length 44% of the parent, at
  **27–41°** (`River.FORK_ANGLE` 0.075–0.115 turns) alternating sides; rejected if within 250 blocks of another river.
- Shape: `RiverWarp` — a simplex lateral displacement tapered toward both ends, plus a sinusoidal wiggle of
  2–45 blocks whose frequency grows with river length; `GenWarp` domain warp of all river coordinates
  (perlin 25 blocks at 95-block scale + 5 blocks at 16).
- Terrain is carved to the lines (`UpliftRiverCarver`); water from the stepped continental water table. Rivers ignore
  relief and can cross ridges.

Conclusion: FTF's naturalness is noise on straight lines; its junctions are fixed acute angles. RH should keep its
drainage-first topology (rivers in real valleys) and add physically scaled planform on top — FTF's noise warp is a
cheap fallback, not a target.

## 3. Literature: meanders

- **Hydraulic geometry.** Bankfull width `w ∝ Q^0.5` (Leopold & Maddock 1953). RH already sizes channels by drainage
  area (`radius ∝ A^0.35`); meander scales follow from width.
- **Wavelength.** `λ ≈ 10–14 w` (Leopold & Wolman 1960; Williams 1986, mean ≈ 11 w). Radius of curvature
  `Rc ≈ 2–3 w` (median Rc/w ≈ 2.7, Leopold & Wolman 1960; migration peaks at Rc/w 2–3, Hickin & Nanson 1975).
- **Sine-generated curve** (Langbein & Leopold 1966): heading relative to the valley axis
  `θ(s) = ω·sin(2πs/M)` with arc length s and arc wavelength M. Sinuosity `P = 1/J₀(ω)`:

  | ω | 0.6 rad (34°) | 1.0 (57°) | 1.2 (69°) | 1.4 (80°) | 1.6 (92°) | ≈2.2 (126°) |
  |---|---|---|---|---|---|---|
  | P | 1.10 | 1.31 | 1.49 | 1.76 | 2.1 | loops touch (neck cutoff) |

- **Kinoshita curve** (Kinoshita 1961; Parker et al. 1983): `θ = ω sin φ + ω³ (J_s cos 3φ − J_f sin 3φ)` adds
  downstream skew (J_s) and flattening/fattening (J_f) — the asymmetric "lobe" shape of real bends. Small J (≈ 1/32–1/192).
- **Where rivers meander** (Schumm & Khan 1972; Schumm 1963): sinuosity is highest on low slopes with cohesive banks
  and wide floodplains; steep or confined reaches are straight or only gently sinuous. Classes: straight < 1.05,
  sinuous 1.05–1.5, meandering > 1.5. A natural network therefore *mixes* straight and meandering reaches — slope,
  confinement and a slowly varying "regime" control are enough to reproduce that.
- **Confinement.** The meander belt (≈ 2× amplitude) must fit the valley floor; where it doesn't, real rivers either
  stay straighter or the valley itself winds (incised/forced meanders). Floodplains widen with the belt.
- **Two axes.** Valley axis and channel are distinct: the floodplain and valley walls follow a smooth axis, the channel
  wanders inside it. This is the key structural idea for RH4.
- **Cutoffs and oxbows.** Neck cutoffs occur as ω approaches ≈ 2.2 rad; abandoned loops become crescent oxbow lakes /
  wetlands on the floodplain, usually one loop length (~λ/2) long, at or slightly above channel level.

## 4. Literature: routing direction bias

- D8 steepest-descent routing on a regular grid restricts flow to 0/45/90°; long channels follow lattice rows and
  diagonals and junctions inherit 45° multiples (Tarboton 1997 introduced D∞ largely for this reason; Freeman 1991 /
  Quinn 1991 multiple-flow-direction methods spread flow but are not single-thread river graphs).
- Procedural terrain from hydrology uses **irregular samples**: Génevaux et al. 2013 (Poisson-disc samples, river
  graph), Cordonnier et al. 2016 (Poisson-disc + Delaunay triangulation for stream-power uplift/erosion). Irregular
  graphs remove the axis bias; Fastscape-style regular grids keep it (Braun & Willett 2013).
- **Jittered lattice** (each node offset within its cell, 8 + optional knight-move neighbours) is the cheap
  intermediate: lattice indexing is kept, neighbour directions vary continuously (8 neighbours at ±0.45 jitter give
  roughly ±25° spread; adding knight moves adds 26.6°/63.4° directions).
- **Fields vs graph.** RH interpolates plan fields (incision, base level, lake depth) with C1 lattice kernels. Moving
  the *routing graph* off the lattice does not require moving the fields: compute on the graph, then rasterise node
  values back onto a regular field lattice (barycentric/natural-neighbour per lattice point, once per plan) and keep
  the existing smooth interpolation. This makes both the jittered lattice and a later Delaunay graph tractable.

## 5. Literature: confluences

- **Angles.** Humid networks: mean ≈ 72°; arid ≈ 45° (Seybold et al. 2017). Optimality/geometric argument (Howard
  1971): `cos θ ≈ S_main / S_trib` — gentle tributaries joining steep mains come in more parallel; steep tributaries
  join near-perpendicular.
- **Planform.** Tributaries commonly curve toward the main direction over the last few channel widths; the junction
  apex is a rounded tongue, not a point.
- **Morphology** (Best 1987, 1988; Mosley 1976): a scour hole at the junction whose depth grows with junction angle
  and discharge ratio (≈ 1–4× the approach depth in flumes; much less in natural sand-bed rivers), a flow separation
  zone and bar on the inner bank just downstream of the tributary mouth, and a tributary-mouth bar.

## 6. Quantitative targets for RH4

| Metric | RH3 (user world) | Target | Basis |
|---|---|---|---|
| Sinuosity, 1 km windows on rivers > 4 km² | 1.17 mean | mean 1.25–1.6; ≥ 20% of windows < 1.1 **and** ≥ 15% > 1.5 | §3 mixed regimes; user decision "balance with straighter sections" |
| Direction bias: 512-block chord directions within ±5° of a 45° axis | measure in M0 | ≤ 30% (uniform = 22.2%) | §4 |
| Junction angle mean / share < 25° | 65° / ~27% < 30° | 60–80° / ≤ 10% | §5 |
| Confluence tongue: dry width 8 blocks behind the tip | knife edge (measure in M0) | ≥ 6 blocks | §5 rounded apex |
| Channel cells outside own valley floor + banks | n/a | 0 | §3 confinement |
| Channel self/mutual crossings | n/a | 0 | — |
| Plan build (user continent) | 206–260 ms at 256 | ≤ 1 s | budget |
| Column sampling cost | ~2.2 µs | ≤ +25% | budget |
| All RH3 landscape/realism gates | pass | pass | — |

## 7. Recommended design (summary; details in PLAN-RH4)

1. **C1** separate grid and refinement budgets; keep the routing step ≤ 128 for continents up to scale 5000.
2. **C2a** jittered-lattice routing graph (all nodes, positions sampled where routed, optional knight moves),
   fields rasterised back to the lattice. **C2b** (Poisson-disc + Delaunay) only if the direction-bias target fails.
3. **B** valley axis = today's smoothed centreline; channel = axis + Kinoshita sine-generated meander with
   `M ≈ 11 w`, ω from slope, confinement and a low-frequency regime noise; amplitude clamped to the valley floor and
   to neighbouring channels; phase continuous along main stems; ω tapers to 0 at junctions, lake ends and mouths;
   occasional oxbow lakes where ω is high.
4. **D** tributary mouths curved toward the main stem to a Howard-style target angle (clamped 35–90°, humid bias).
5. **E** rounded confluence tongue (smooth union of channel distance fields), scour pool and inner-bank bar.

## References

Best 1987, 1988 (confluence dynamics/morphology) · Braun & Willett 2013 (Fastscape) · Cordonnier et al. 2016
(EG, tectonic uplift and fluvial erosion) · Freeman 1991; Quinn et al. 1991 (MFD) · Génevaux et al. 2013 (SIGGRAPH,
hydrology-based terrain) · Hickin & Nanson 1975 · Howard 1971 · Kinoshita 1961; Parker et al. 1983 · Langbein &
Leopold 1966 · Leopold & Maddock 1953 · Leopold & Wolman 1960 · Mosley 1976 · Schumm 1963; Schumm & Khan 1972 ·
Seybold et al. 2017 (PNAS) · Tarboton 1997 (D∞) · Williams 1986.
