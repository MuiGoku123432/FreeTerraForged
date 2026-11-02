# Rolling Highlands — formulas and algorithm design for the first build

Date: 2026-10-27. Inspected checkout: `c5076eb8130a28f10e625ed28d5a9d7aceeb7188`.
Status: **concrete prototype design, not a validated terrain generator**.

Related: [research](RESEARCH.md), [behavior specification](../../features/rolling-highlands/SPEC.md), [formula probe](formula_probe.py), [results](formula-results.json), [internal audit](INTERNAL-AUDIT.md).

This second research pass supplies the math missing from the first spec. Published methods, source-derived facts, original derivations and proposed tuning are distinguished below. It does not authorize weakening the behavioral requirements when a candidate formula fails.

## 1. Choices for the first build

| Problem | Candidate to implement | Confidence / remaining experiment |
|---|---|---|
| Repeated broad relief | Smooth multiscale upland/basin fields plus ridge and foothill components with separate amplitudes | Equations specified; spatial quality needs FTF maps |
| Finite planning | Continent lattice identity with a conservative world-space bound derived from existing selection/warp | Bound derived; actual coastline connectivity must be verified |
| Drainage | Deterministic Priority-Flood routing, accumulation and depression/spill records | Established methods; FTF domain/graph extraction unbuilt |
| Channel elevations | Two-pass interval feasibility on a downstream tree, then target projection | Original derivation; 1,000 exhaustive-oracle fixtures agree |
| Valley geometry | Piecewise quintic bed/bank/floor/outer transition | 27 constant-outer-terrain fixtures pass; junctions/real terrain unbuilt |
| Junctions | Shared elevation; short level wet-channel core represented in the profile constraints | Proposed construction; not tested in Minecraft |
| Erosion | Retain slope-oriented FTF filter, with an explicit RH units/spacing boundary and protected wet cores | Existing implementation; newly found scaling assumptions need isolation |

Start with linear channel-height interpolation and unsmoothed or conservatively simplified channel polylines. Fancy spatial warps, unconstrained cubic curves and stream-power evolution are not prerequisites for the first build.

## 2. Units, numerical conventions and seeds

Use doubles for planning positions/heights. Let `x,z` be original world block coordinates, `y` a vertical offset in blocks above FTF's own ocean water reference, and `T_h = levels.terrainScaleFactor`.

```text
normalizedHeight = levels.water + y / T_h
y = (normalizedHeight - levels.water) * T_h
```

`T_h = max(1, min(worldHeight, 256))` in the inspected pipeline; it is not generally worldHeight. Ocean reference comes from Levels, whose `waterY` normally equals configured seaLevel minus one. Do not invent a second sea-level convention. Match final voxel fill semantics to the existing surface system with an integration fixture.

Keep lengths in blocks, area in blocks² and slope in blocks/block. Normalize a ratio before raising it to a fractional exponent. Use finite validated parameters and strictly positive denominators. Use integer lattice coordinates for ownership and double local positions for geometry. At large world coordinates, the existing float Heightmap API can already lose adjacent block positions; converting that lost float back to double cannot recover them. See the internal audit for the required call-path boundary.

For all formulas below:

```text
sat(t) = min(1, max(0, t))
S(t) = 6u^5 - 15u^4 + 10u^3, where u = sat(t)
smooth(a,b,v) = S((v-a)/(b-a)), b > a
lerp(a,b,t) = (1-t)*a + t*b
```

The quintic polynomial has zero first and second endpoint derivatives; FTF exposes it as Interpolation.CURVE4 despite that enum name. This is the interpolator described by Ken Perlin in [GPU Gems chapter 5](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-5-implementing-improved-perlin-noise). Use it for lateral transitions; it is not a universal safe choice for river-height interpolation because its maximum derivative is 1.875.

Assign independent named seed offsets for regional noise, basin selection, ridge detail, foothills and planning tie-breaks. Construct them from the immutable root seed with `Seed.offset`; do not consume extra legacy `Seed.next()` draws. Version and record the chosen constants. FTF public noise factories generally produce 0–1 signals; define signed noise as `2*N-1`, not by assuming the output is already signed.

## 3. Candidate regional-relief equations

These are original engineering proposals, not equations copied from a geomorphology paper. Their constants are initial tuning, not proven default values.

Let `L = uplandSpacing`, `M = mountainHeight`, `F = foothillRelief`. All `N` below are independent, bounded 0–1 noise fields sampled through the same regional warp. Keep warp amplitude proportional to L; cap it explicitly rather than assuming a noise bound.

```text
p = (x,z) + 0.12*L * (2*Nwx(x,z;1.5L)-1, 2*Nwz(x,z;1.5L)-1)
u = (2*N1(p;L) + N2(p;0.55L)) / 3
b = smooth(0.62,0.80,1-u) * (0.35 + 0.65*smooth(0.35,0.65,N3(p;2L)))
U = (24 + 96*smooth(0.20,0.80,u)) * (1 - 0.75*b)
r0 = sat(perlinRidge(p;0.55L, octaves=3))
R = (1-b) * smooth(0.25,0.85,r0)
f0 = sat(0.65*N4(p;0.22L) + 0.35*perlinRidge(p;0.35L,octaves=2))
Q = (1-b) * (0.30 + 0.70*f0)
H_proposal = coastalBlend(ocean, U + M*R + F*Q)
```

`U` is broad elevation, `b` basin preference, `R` major-ridge contribution and `Q` rolling connectors. Noise scale here means world-space wavelength parameter; do not apply globalHorizontalScale again. Coastal blending is applied exactly once with the continent mask and existing ocean/coast fields. These positive relief components avoid raising peak amplitude by multiplying all surrounding land.

For the drainage proposal use fixed model coefficients `M_ref=160`, `F_ref=48`, independent of the two amplitude sliders. This intentionally fixes routing topology under amplitude edits. It is a controllability choice, not a physical tectonic simulation. Spacing still changes the graph. After routing, reserve the drainage corridor and shape shared ridges/divides around it; do not keep an enormous ridge through a river and rely on unrestricted incision to remove it.

### Drainage-aware ridge refinement

Noise supplies initial character, not proof of connected mountain systems. On the coarse drainage grid, label major basins by outlet/trunk membership. Interfaces between labels identify candidate divides. Extract connected interface polylines, retaining branch vertices. Use a common global ridge field on both sides of a divide; do not assign separate heights per watershed.

For a divide segment distance `d_r`, a candidate ridge envelope is:

```text
ridgeEnvelope = (1 - S(d_r / ridgeRadius)) * (0.65 + 0.35*N5(p;0.4L))
R_final = (1-b) * sat(0.35*R + 0.65*ridgeEnvelope)
```

`ridgeRadius` initially 0.12L, bounded by basin geometry. Add lower-order divides for subsidiary ridges using reduced coefficients. Select passes at stable saddle candidates in the planning field; apply a compact smooth amplitude reduction there. Record the pass center/radius as diagnostic output. Exact pass density and ridge weighting are visual iteration parameters, not additional public sliders in v1.

Near accepted river centerlines, attenuate added ridge/foothill contributions using a **fixed model reservation** `A_c = S(d_channel / reservationRadius)`. The reservation is independent of the valley-width slider so lateral width edits cannot reroute rivers or change their water profile. Begin with a 96-block reservation and context-dependent narrowing in mountain reaches; measure whether it removes too much connecting relief. Avoid a circular definition in which height depends on a river plan that itself samples that same height.

No claim is made that these formulas alone already satisfy the ridge/pass rubric. The first maps should compare noise-only ridges against drainage-divide refinement with erosion disabled.

## 4. A bounded planning domain derived from this mod

This bound follows from the inspected AdvancedContinentGenerator selection algorithm, not from assuming an ideal unrestricted Voronoi diagram.

Let `T = 4*continentScale`. The generator searches only the 3×3 lattice neighborhood around `floor(warpedPosition/T)`. If lattice cell `(i,j)` is selected, then:

```text
(i-1)*T <= warpedX < (i+2)*T
(j-1)*T <= warpedZ < (j+2)*T
```

Its DomainWarp maps each finite Perlin2 signal to [-0.5,0.5] and multiplies by `strength=round(0.33*T)`. Thus each raw-coordinate offset is bounded by `B=strength/2`, giving:

```text
rawX in [(i-1)*T-B, (i+2)*T+B]
rawZ in [(j-1)*T-B, (j+2)*T+B]
```

Add a numerical guard and interpolation/query halo. This is a conservative bound on points assigned to this lattice identity, **not a proof that its rectangle is coastline, that its land is connected, or that its boundary cannot touch another landmass**. Keep the lattice identity explicitly in the RH planner; the existing corrected continent-center coordinates are not interchangeable with `(i,j)`.

Algorithm:

1. Build the conservative box and sample owner, coast mask and canonical planning elevation at a world-aligned coarse spacing.
2. Restrict routing nodes to owned land plus explicitly sampled coast transitions. Locate actual ocean crossings; refine outlet segments, do not classify the box edge as sea.
3. Check every connected land component has a verified coast exit. Check ownership transitions for land bridges. If there are land bridges, the one-owner plan has failed its assumption: redesign ownership/shared ports before claiming unrestricted support. Do not add artificial ocean channels along cache boundaries.
4. Include segment influence beyond ownership in the query index. Derive a finite set of neighboring lattice IDs from query position, warp bound and maximum influence; query relevant immutable plans without recursively building neighbors during a plan build.

### Resolution budget

Choose the smallest world-aligned power-of-two spacing `Δ >= 64` satisfying the configured node cap. For a square span D, `(ceil(D/Δ)+2)^2` is a conservative allocation estimate allowing alignment. Also require `Δ <= L/8` for the prototype's regional-resolution criterion. A pair that cannot meet both is not supported by the simple uniform-grid prototype; reject it clearly rather than silently dropping drainage detail. Adaptive refinement is a later measured extension, not assumed capacity.

At the proposed 65,536-node cap, excluding extra influence halo:

| continentScale | Box span | Coarse Δ | Minimum L for eight samples |
|---:|---:|---:|---:|
| 1000 | 13,320 | 64 | 512 |
| 4000 | 53,280 | 256 | 2048 |
| 10000 | 133,200 | 1024 | 8192 |

The initial independent slider ranges therefore need **combination validation**; their Cartesian product is not automatically supportable. The default L=4096 fits the example C=4000 budget, but not C=10000 under this conservative uniform-grid design. Counts are calculations, not generation-time benchmarks. A tighter actual-domain representation may improve them.

## 5. Drainage, accumulation and basin handling

Use Priority-Flood on the finite valid domain with a total order `(routingHeight, stableNodeId)`. Store original proposal height separately. On first discovery assign a downstream parent already reached from an outlet; store a traversal rank so every parent has lower rank. Use deterministic fixed neighbor ordering. Reject edges that cross excluded water/ownership geometry incorrectly.

```text
A[v] = representedDryLandArea[v]
for v in reverse traversal order:
    if parent[v] exists: A[parent[v]] += A[v]
```

Cell area is Δ² on a uniform grid, with fractional coastal coverage if implemented. Keep this as contributing area, not simulated rainfall/discharge. Extract channels where `A >= A_channel`; include all downstream continuations. Initial `A_channel = 0.03*L²` is an art-direction proposal to test, constrained so it does not select every coarse node or no usable tributaries. The legacy river-count setting needs an explicit mapping to this density threshold or must be disabled for the new mode.

Compress degree-two chains into polylines; retain junctions, outlet/spill points and profile constraint samples. Store accumulated area and stable IDs. Simplification must preserve topology and stay in the feasible valley corridor; start with no smoothing. D8 diagonal bias is a known visual limitation to measure, not something a random post-warp safely fixes.

### Depression records and lakes

Record depression labels and the lowest saddle between adjacent basins, then build their merge hierarchy using sorted saddle elevations and disjoint sets. Use stable IDs to break equal-saddle ties. This is based on [Barnes et al., depression hierarchies (2020)](https://esurf.copernicus.org/articles/8/431/2020/), which explicitly represents basin relationships and spill elevations. The routing tree and depression hierarchy are different data structures.

For each depression intersecting a selected river:

- A small shallow obstruction may receive a bounded breach if the profile feasibility test permits it.
- A suitable wet basin becomes a level lake with an explicit spill/outflow; wet extent is based on the connected depression below the spill, not an arbitrary circular widening.
- Nested lakes are merged only when their spill relationships require it. A flat lake is allowed; a filled dry-land plateau is not.
- Excessive lake area/depth or an infeasible breach triggers a bounded route/basin revision and a diagnostic if unresolved. Do not randomly delete all troublesome rivers to pass graph checks.

[Fill–Spill–Merge (2021)](https://esurf.copernicus.org/articles/9/105/2021/) describes dynamic redistribution through those relationships. V1 adopts explicit spill connectivity, not its full runoff-volume simulation. It makes no water-budget or rainfall-conservation claim.

For the first prototype allow at most three recorded basin/route revision attempts per failed domain, each using a fixed alternative order. A failed prototype is an actionable fixture. Production support requires demonstrated robust resolution or a different construction; research does not prove that three attempts work for arbitrary seeds.

## 6. Channel profiles: an exact feasibility algorithm for the chosen graph

### 6.1 Inputs and constraints

Each node has one downstream parent `p(v)`, except ocean roots. Distributaries are out of scope. Contract each level lake, and each designated level junction core, to one shared-elevation supernode. Preserve original boundary constraint samples in its interval intersection.

For every node define water elevation `w_v`, target `t_v`, allowable interval `[l_v,u_v]`, edge length `ell_v`, and allowed elevation drop `[a_v,b_v]`:

```text
l_v <= w_v <= u_v
a_v <= w_v - w_parent <= b_v
a_v = minimumSlope * ell_v
b_v = maximumSlope * ell_v
```

Use minimumSlope=0 initially, permitting quantized flats and lakes. Propose maximumSlope=0.02 for unconfined reaches and 0.12 for selected confined reaches; these are tunable block/block caps, not hydraulic laws. Roots have `[0,0]` in ocean-relative units. Avoid a positive mandatory slope over every edge: it can force unrealistic elevations on long shallow rivers.

For a channel sample with original planned surface H, desired bed depth D, maximum cut C, maximum permitted bed fill F_b, bank minimum H_bank and freeboard f:

```text
bed = w - D
l = max(0, H - C + D)
u = min(H + F_b + D, H_bank - f, availableWaterHeadroom)
target = H - f
```

These bounds are an original construction translating the constraints. H_bank must come from the intended enclosing shore, not the center of the channel. Lakes use lake-specific basin/shore bounds. In ocean transition nodes, the exposed-bank constraint is relaxed explicitly. A negative or empty interval is not silently clamped into feasibility.

Determine profiles from the fixed corridor/planning surface; detailed mountain/foothill additions are suppressed at that corridor. Valley width is applied later. If a wider valley would require excessive cut/fill, report that parameter combination rather than changing the already solved river profile. Keep broad basin synthesis visible in diagnostics so it cannot hide an excessive total land change.

### 6.2 Two passes

Original derivation for a rooted tree/forest with interval node/edge constraints:

```text
I[v] = [l_v,u_v]
for child v in leaves-to-roots order:
    p = parent[v]
    I[p] = I[p] intersect [I[v].low - b_v, I[v].high - a_v]
    if any interval becomes empty: INFEASIBLE

choose each root's fixed ocean elevation
for v in roots-to-leaves order:
    J = I[v] intersect [w_parent + a_v, w_parent + b_v]
    w_v = clamp(target_v, J.low, J.high)
```

Why it works: an upstream subtree permits exactly an interval of elevations at its root. The parent values compatible with that interval form another interval. Different child subtrees share only their parent, so intersecting their permitted parent intervals is sufficient. Induction gives exact feasibility for this graph class. The second pass selects values within those propagated intervals. Cost is O(nodes+edges); use iterative arrays, not recursive Java calls on arbitrarily long rivers.

This is **not** a globally optimal least-squares or minimum-earthwork solver. Target projection is a deterministic feasible choice. If visible gradients are poor, add a bounded profile optimization that preserves all constraints; do not mislabel the initial method as optimal. For graphs with reconverging branches or uncontracted cycles, the tree proof does not apply.

### 6.3 Interpolation and dense constraints

On each accepted geometric segment use arc-length interpolation:

```text
w(s) = (1-s/ell)*w_upstream + (s/ell)*w_downstream
```

This preserves the node drop bound continuously on that straight segment. Maintain samples where ground/shore bounds change; checking only coarse endpoints does not constrain a ridge between them. Initial refinement spacing should be at most 16 blocks, tightened to one-block validation at published wet channels and risky junction/outlet segments. Bound the total samples separately from coarse nodes.

Do not use ordinary cubic interpolation for profiles: it may overshoot. [SciPy's PCHIP documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.interpolate.PchipInterpolator.html) describes shape-preserving monotone cubic interpolation as an option. Even monotone interpolation must separately satisfy local slope and terrain bounds; linear interpolation is the first-build choice. No SciPy dependency is proposed for the mod.

## 7. Widths and smooth valley cross-sections

Hydraulic geometry commonly relates channel dimensions to discharge with power functions, as described in [Leopold and Maddock, USGS Professional Paper 252](https://pubs.usgs.gov/publication/pp252). We borrow that functional form, using contributing area as an artistic proxy. The following coefficients/exponents are design choices, not measured FTF hydraulics.

Let `A_ref=L²`, `q=max(A/A_ref,0.01)`:

```text
wetHalfWidth = clamp(r_ref*q^0.35, r_min, r_max)
depth = clamp(d_ref*q^0.15, d_min, d_max)
confinement = smooth(24,120,planningReliefWithin512Blocks)
dryFullWidth = valleyWidth * clamp(q^0.20,0.5,1.5) * lerp(1,0.25,confinement)
```

Wet channel settings supply explicit radius/depth bounds; do not reuse valleyWidth as channel width. At a junction, smooth the approach to the shared downstream width rather than abruptly replacing child width at the final point. Confinement is derived from the canonical planning field to avoid rerouting/width changes when mountain amplitude changes. Measure whether its visual interpretation remains reasonable at slider extremes.

For a cross-section at longitudinal coordinate s, use water W, depth D, wet half-width r, bank run b, dry floor half-width f=dryFullWidth/2, outer transition run o, freeboard h=2, and floor crossfall c=2 blocks. Let d be absolute perpendicular distance to the channel:

```text
0 <= d <= r:
    V(d) = W - D*(1-S(d/r))
r < d <= r+b:
    V(d) = W + h*S((d-r)/b)
r+b < d <= r+b+f:
    V(d) = W + h + c*S((d-r-b)/f)
r+b+f < d <= r+b+f+o:
    t = (d-r-b-f)/o
    V(d) = lerp(W+h+c, H_land(x,z), S(t))
outside:
    V(d) = H_land(x,z)
```

The dry-floor width excludes wet channel and banks, matching the spec. The floor has gentle crossfall toward the river. Quintic transitions avoid hard derivative joins in the ideal constant-outer-terrain case. A broad outer fade does not ensure a gentle slope if H_land itself varies sharply: derivative includes both the transition term and the underlying land derivative. Choose outer extent from measured relief and desired side slope, then verify it. A useful lower estimate is `o >= 1.875*abs(H_outer-H_floor)/slopeBudget`; it is not a sufficient bound for arbitrary varying outer terrain.

Use the target cross-section in its planned corridor, subject to cut/fill feasibility. Taking `min(original,V)` everywhere is insufficient: a low original patch can leave a leak or dry/water discontinuity. Conversely, unconstrained replacement can create walls of fill. Validate the entire changed surface; revise basin/width/transition geometry when limits fail.

### Junction composition

Nearest-segment ownership alone can jump between unequal water profiles. Averaging or independently taking minimum/maximum water levels does not guarantee a downhill continuous junction.

First-build construction: define a small junction neighborhood based on **wet channel width**, not the full valley width. Within it, the connected wet-channel union has one water elevation W_j. Insert profile boundary nodes at its branch entries/exits and contract their equality constraints to a junction supernode before solving. Only the channel union is wet, not the whole neighborhood disk. A short level junction core is allowed by downstream non-ascent; it is not a continent-wide terrace. If neighboring cores overlap, merge their constraints deterministically and recheck feasibility.

Use compatible bed depth at the core, interpolate depth outward, and blend dry bank/floor targets with compact normalized weights over all incident segments. Keep the wet core exact. Bound every influence region and validate sampled centerlines after composition. If this creates too many pond-like junctions, refine the junction geometry during iteration; do not hide it by adding erosion noise.

## 8. Erosion, headroom and the final surface

The existing Phacelle-style Erosion is suitable for initial local detail, but the internal audit found that it and CliffRelaxation use worldHeight where Levels uses terrainScaleFactor. Do not globally fix that as an incidental feature change: legacy parity is a separate contract. For RH, provide an explicit filter unit scale and actual sample spacing or a mode-owned adapter, preserving the legacy path. A zoomed preview cannot claim block-accurate erosion if adjacent samples are treated as one block apart.

Candidate new-detail amplitude envelope, only if needed beyond the existing gate:

```text
erosionMask = slopeGate * (1-wetCoreMask) * (1-flatFloorMask) * crestProtection
delta = erosionMask * clamp(detailDelta, -localCutBudget, localRaiseHeadroom)
```

Use actual local slopes and continuous masks. Keep short gullies directional along the surface; the graph establishes the main drainage. Do not add a second independent global erosion layer before evaluating the current filter with corrected units.

Reserve water/bed headroom before profile solving. Apply terrain ceiling compression to dry relief once in the new composition, accounting for post-filter deltas. The current ceiling is gated by mountainVariety and can be applied before rivers and again after filters; its transform is not generally idempotent. It must not independently compress a wet bed away from an unchanged water profile. Any new filter kernels require recalculating the total support radius and checking the 12-block region halo, not merely reusing it by habit.

## 9. What the numerical checks establish

Run:

```sh
python3 .planning/research/rolling-highlands/formula_probe.py
```

- 1,000 small integer tree fixtures were compared with exhaustive enumeration: 692 feasible, 308 infeasible, zero disagreements. A conflicting shared-junction fixture was correctly rejected.
- Feasible profiles retained non-ascent under linear interpolation and monotone integer quantization in the synthetic checks.
- 27 cross-section combinations passed endpoint, continuity and outward-monotonicity checks with constant outer terrain. Center bed elevation stayed independent of dry-floor width.
- At worldHeight=1024 with terrainScaleFactor=256, dividing a requested 48-block offset by worldHeight instead of terrainScaleFactor produces only 12 actual blocks in the Levels representation.
- Neighboring integer coordinates near 30 million can collapse to the same float; doubles must enter before that conversion.
- The domain allocation table is generated from the derived formula, not from measured sampled coastlines.

These checks do not establish renderer appearance, actual FTF river feasibility, domain coast connectivity, full-resolution junction behavior, water updates, performance, or V3 superiority. They are enough to eliminate several avoidable implementation mistakes and give the first prototype testable algorithms.

## 10. Build order and stopping point for research

1. Implement the pure units/noise sampler and bounded owner-domain diagnostics; verify real outlets before solving rivers.
2. Implement routing/accumulation/depression records and the interval profile solver, with dense constraint checks and explicit failure records.
3. Generate diagnostic terrain from the regional and cross-section formulas, initially without erosion. Compare ridge/noise variants and plot incision, basin, profile and constraint fields.
4. Integrate water placement and shared junction geometry; then mode-owned filters, headroom and metadata.
5. Add persisted controls/UI and run the specified seed routes, control sweeps and V3 comparison when its fixture is available.

**Research is sufficient to start this bounded prototype.** Remaining high-value work is empirical: whether the field composition looks right, how often route constraints fail, whether conservative domains are too expensive, and whether water/filter integration preserves the solved profiles. More general literature searching is unlikely to settle those questions.

Full stream-power evolution remains a contingency. [Braun and Willett's 2013 method](https://gfzpublic.gfz.de/pubman/faces/ViewItemFullPage.jsp?itemId=item_1945954_3) and [Fastscape's StreamPowerChannel API](https://fastscape.readthedocs.io/en/latest/_api_generated/fastscape.processes.StreamPowerChannel.html) are concrete references if iteration shows constrained synthesis cannot produce convincing connected relief. This recommendation does not introduce that simulation or promise its runtime cost.
