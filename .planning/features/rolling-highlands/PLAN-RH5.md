# Rolling Highlands RH5 — river hierarchy, water biomes, lakes and ranges

Date: 2026-10-29. Status: agreed; in implementation. Builds on RH4 (`1.0.0014-rh4`, model 4, `ee892191`).
Inputs: the user's RH4 server test ("vastly superior, not perfect") and its diagnosis on the server world
(seed −6240223350076425471, Test-America-v3-ROLLING), recorded in the 2026-10-29 session handoff.

## 1. Agreed decisions

From the RH4 diagnosis (handoff 2026-10-29):

- **Frozen water matches the land.** RH water uses `frozen_river` only where the land biome around it is itself
  snowy/freezing; otherwise `river`. Gated on `cell.rollingHighlands` in `TerrainBiomeRoleSelection`. (Rejected:
  never frozen; keep the vanilla temperature rule.)
- **Mountains:** a new RH range layer — long continuous ranges several thousand blocks long with foothills, that
  rivers route around — with Range Height and Range Length settings. (Rejected: only tuning the legacy Mountains
  sliders; the legacy worley-edge chains stay segmented.)
- **Base FTF untouched:** every change is gated on `cell.rollingHighlands` / `ROLLING_HIGHLANDS`.
- Still in force: the Streams Reflowing contract (`applyCell`, `Cell.terrain/height/riverWaterLevel/flowAngle`,
  `TerrainType.RIVER`, `IFlowFieldHolder`/`ChunkFlowField`, the water-surface formula) is not renamed; wetlands keep
  `WETLAND` with erosion .78; older models are rejected with "create a new world"; one commit per phase with the
  full `:common:test` suite; `feat(worldgen): ...`, never amend, never push unless asked.

River work, settled 2026-10-29:

- **Reduce overall river density.**
- **Keep the current forks and branching character.**
- **Keep Streams Reflowing lakes enabled** (`generateLakes=true`).
- **Spacing:** substantial rivers roughly **1–2 km apart** across comparable terrain, with natural variation.
- **Small rivers start as 2–4-block creeks** and widen gradually with drainage area; established rivers keep their
  RH4 appearance.
- **RH is complete without SR.** RH draws its own creeks; a pack preset with a lower River Count reduces overlap
  when SR supplies additional streams.
- Thinning the drawn network must not change stream capture (merges) or lake placement — they are decoupled from
  River Count.
- The Still Life biome tag audit and lake-cutoff reproduction are handled elsewhere (not part of RH5).

## 2. Diagnosis that drives the river changes

- The "dense parallel rivers" are mostly a **flat-routing starburst**: on flats and filled depressions every receiver
  has zero drop, so Priority-Flood routing falls back to the shortest path to the outlet; on uniform cost that is a
  radial fan of parallel channels that meet only at the outlet (user continent, south-east fan: 10+ channels 300–600
  blocks apart converging on one point).
- River Count (`channelScale`) moved three things at once: the drawn channel threshold, the stream-capture threshold
  and the dam-lake / depression-lake eligibility (lakes needed a drawn channel). Thinning rivers therefore also
  removed merges and lakes.
- Every drawn channel was at least 8 blocks wide (RH4 radius floor 4, head taper to 30% only over 160 blocks).

## 3. Phases

### D — River hierarchy and density (model 5 behaviour; ships with the model bump in M)
- **D1 flat routing friction.** Path cost per block on flats = 1 + 3·n² (n: smooth noise, 1400-block wavelength).
  Shortest paths through the medium coalesce along low-cost corridors, so flat drainage collects into branching
  trunks about a wavelength apart. Sloping ground is unchanged (steepest descent).
- **D2 hierarchy.** Areas as fractions of `uplandSpacing²`:
  - creek heads (drawn network starts) at 0.065 × (7 / River Count), clamped ×0.4–×3 (1.1 km² at River Count 7
    and spacing 4096; RH4 was 0.72 km²);
  - established river at 0.36 (6 km²): from there the RH4 width law (half width 16·q^0.35, 4–12) is unchanged;
  - creeks widen as a power of area from half width 1.4 at the head to the river law at 6 km² (≈3 wide at the head,
    ≈7 at 2.5 km², ≈11 at 3.3 km²); width noise gives 2–4-block creeks; the head taper keeps 70%;
  - creek sections: shallower bed (1.6 + texture), half-height banks, 30% of the river floor width, all blending
    continuously into the river section by log-area.
- **D3 decoupling.** A fixed structural area (0.043, the RH4 River Count 7 value) drives stream capture, dam-lake
  sites and lake eligibility. A lake's outlet is always drawn (as a creek if small), so no lake sits in a dry valley.
- Oxbows only on channels at least 8 blocks wide.
- One width function (`HighlandDrainage.halfWidth`) replaces the four copies (valley section, meander wavelength,
  mouth clearance, confluence scale).
- SR pack preset: River Count 4 (creek heads at ≈1.9 km²) leaves more dry side valleys for SR streams; SR lakes stay
  on.
- Metrics: channel spacing by drainage-area class (land area / channel length), creek width at heads, plus every
  RH4 planform/quality/realism gate.

### W — Water biomes
- Frozen rule above. The land biome is resolved first for every RH water patch; `frozen_river` only if that land
  biome is snowy/freezing (precipitation snow at the patch).
- Per 4×4 biome patch, choose the water role from whether the patch is mostly water (majority of its sampled
  columns), not from its corner cell — removes the speckled ice at banks, bars and confluence wedges.

### C — Climate from a smoothed landscape height
- For RH cells, `ClimateModule.modifyTemp/modifyMoisture` read a smoothed landscape height (the uncarved RH land
  low-passed over ~256 blocks, never including riverbeds or per-hill detail) instead of `cell.height`. Removes
  snowy-plains blobs on hilltops and warmer riverbeds. Gated on `cell.rollingHighlands`.

### H — Hollows: tarns and dished floors
- `floored()` lifted closed hollows to drainage level + 1.5 (dead-flat floors that SR filled with straight-edged
  lakes). Replace: hollows deeper than a tarn threshold become RH lakes (mountain tarns, with shore shaping);
  shallow ones get a gently dished floor that drains toward the base level (no flat basin).

### S — Lake shorelines
- Shorelines traced from the real ground contour (lake surface vs. rendered land), not from the planning grid:
  removes triangular lakes and straight shore edges.
- Lake ownership blended between neighbouring lakes instead of switching at grid mid-lines
  (`HighlandBasins.sample`).

### M — RH mountain-range layer (model 5)
- Long continuous ranges (several km) with foothills, from low-frequency warped ridge noise aligned along range
  axes, added to the RH land before drainage, so rivers route around ranges and cross them only at gaps.
- Settings `rangeHeight` and `rangeLength` (new `RollingHighlandsSettings` fields); model 5; model 4 presets are
  rejected with "create a new world".

### V — Validation, docs, release `rh5`
- Benchmarks on the server-world sites (A/B/C) with `grid.py` maps; ground-level checks of lake edges, river
  entries/exits, junctions; SR interaction (streams into RH valleys, SR lakes on).
- Docs (VALIDATION.md, user-facing notes), version `1.0.0015-rh5`, deploy to the Prism client and the server; update
  the user preset to model 5 (plus an SR variant with River Count 4).

## 4. As built (2026-10-29)

Commits: plan `e5eefabf`; D `78b7e968`; W `ea7fc487`; C `8ae49b3c`; H `f2863fbb` + `31ebdb04`; S `27972b8c`;
M `d8fa90aa`; validation fixes `5bce84c9`; release docs follow. Record: [VALIDATION.md](VALIDATION.md).

Differences from the plan above:
- **Model bump moved to D** (the first generation change), so every intermediate commit generates model 5.
- **C** also removes the mountain-chain switch from RH climate sampling: RH reclassifies mountain-chain valley floors
  as hills, which made the climate sample jump at every valley edge.
- **H tarns** come from the existing lake decision (lake chance) for off-network depressions at least 8 deep, at
  least 2 km apart; they have no drawn outlet. Other off-network hollows lose their fill level and get dished floors.
- **S** lowers the base-level floor beside lakes to the lake level, so shorelines can follow the real ground: they
  retreat up to 12 and advance up to 10 depth units from the planning field (no advance within 24-64 blocks of a
  channel). Steep lake sides can still show straight edges where the stream-power incision itself steps between
  128-block plan nodes.
- **M** ranges are off by default (`rangeHeight` 0) so existing calibration and gates stay as they were; the user's
  preset turns them on. Ranges are iso-lines of a strike-stretched noise (long, fairly straight, with occasional
  hairpin turns like folded ranges), switched on in stretches and notched by passes.
- Erosion filters may no longer dig dry valley ground below one block above its river.
