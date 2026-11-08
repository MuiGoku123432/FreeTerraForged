# Rolling Highlands

Branch: `feature/rolling-highlands`. Build `1.0.0024-rh10`, generation model 9 (drowned coast: item 10; coasts: item 9). Plans and per-phase logs:
[PLAN-RH4.md](PLAN-RH4.md) (river planform), [PLAN-RH3.md](PLAN-RH3.md) (land, drainage, valleys). Research:
[RESEARCH-RH4.md](../../research/rolling-highlands/RESEARCH-RH4.md), [RESEARCH-RH3.md](../../research/rolling-highlands/RESEARCH-RH3.md).
Earlier models (RH1–RH3) are in git history.

## Trying it

Create a **new world**, open the FTF preset editor and select **Rolling Highlands (Experimental)** under
World → Continent Type. Presets from earlier models are rejected with a "create a new world" message.

- **Terrain page:** four RH controls (upland spacing, upland height, valley depth, valley width) followed by the full
  normal FTF terrain controls — they shape RH3's land exactly as for other continent types.
- **River page:** river count (drainage density), **Meanders** (0–200%, default 100), flow toggles, main-river bed
  depth / bank heights / bank width, lake chance and depth, wetland chance.
- World height ≥ 320 above sea level; continent scale 1000–5000.

## How it works

1. **Land** = the legacy FTF land composition (terrain regions, region borders, mountain chains with variety and
   fancy erosion, `Blender`), extracted unchanged as `Heightmap.makeLand`, plus a broad smooth upland uplift.
2. **Drainage plan** per continent on that land (128-block lattice for continents up to scale 5000, every node routed
   at a jittered position so flow is not tied to the 8 grid directions): priority flood to the coast, drainage area,
   stream capture of parallel channels, stream-power incision `z = min(land, z_receiver + run·k·A^−0.45)`,
   monotone water levels, depression lakes (keep or breach by FTF lake chance), and dammed valley lakes on gentle
   large rivers (FTF lake depth).
3. **Rendered surface** = land − smooth (Catmull-Rom) incision; valleys are where the drainage on this same land says.
4. **Channels** (RH4): each drainage line is the *valley axis*; the channel winds around it as a Kinoshita
   sine-generated meander (wavelength 10–14 channel widths, bend amplitude from slope, confinement, a slow
   straight/meandering regime and the Meanders setting, capped so loops never reach another channel or fold inside an
   axis bend). Some well-developed loops are cut off at the neck, leaving oxbow lakes (still water, silted wetland
   ends). Tributary mouths are rebuilt to meet the parent at Howard junction angles (35–90°, humid mean 72°) without
   crossing; confluences get a rounded apex, a scour pool and a submerged bar.
5. **River valleys** use FTF's `UpliftRiverCarver` profile (riverbed, terraced bank step, flat floor, terraced
   fadeout with talus and cliffs, gully/rivulet drainage, pinch/width/asymmetry noises) driven by the plan's water
   level, drainage area and the FTF main-river settings; bed and banks follow the channel, floor and fadeout follow the
   valley axis with the floor widened to hold the meander belt; channels compose continuously with confluence rapids.
6. **Lakes and wetlands:** lake shorelines follow the plan with shoreline noise and rims; floodplain wetlands on flat,
   large, low reaches carry WETLAND terrain and swamp-level erosion (swamp / mangrove biomes).
7. **Everything downstream is FTF's:** biomes from the native populator parameters (valley floors pulled to the low
   slice), legacy oceans, Phacelle erosion filter and cliff relaxation, surface water mixin, river gasket, scree,
   flow field (currents follow the legacy carver's flow model), Streams Reflowing bridge.
8. **Sea shores** (2026-11-04): past the shallow-ocean point the sea floor follows a shoreface (shelf depth × (d /
   48)^(2/3), d = blocks from the coastline estimated from the edge field's slope) instead of easing up to sea level by
   edge value, which had left one-block-deep flats hundreds of blocks wide (dirt floors with disk patchwork). After the
   climate pass, RH land drops the region-wide COAST tag (it became a beach only on WorldLookup paths) and low dry
   ground within a ragged ~6-block strip of the coastline becomes BEACH with coast continentalness and the low
   weirdness slice (beach biome; stony shore at erosion levels 0–2). Gate: `HighlandShoreTest`.
9. **Coasts** (RH9, model 8; [PLAN-RH9.md](PLAN-RH9.md)): a position-only coast profile (`HighlandCoastProfile`)
   picks beach (~55-60%), bluff (~25%) or sea cliff (~15-25%) in stretches of 300-3000 blocks from a slow hardness
   noise, and warps the coastline up to 200 blocks (hard rock out as points, soft rock in as coves; sinuosity 1.21).
   The regional climate leans it: cool/wet (PNW) coasts get more cliff, narrower beaches, dark stone/andesite/tuff
   faces, gravel patches and more sea stacks; warm/dry (Baja) coasts get wider beaches with dunes and red sandstone
   / terracotta faces; the middle (California) gets sandstone bluffs. Beaches are 15-40 blocks of dry sand with a
   berm; bluffs are 6-16 block scarps with an 8-20 block beach at the foot; sea cliffs are 15-40 blocks (limited by
   the land behind) straight into deep water (shoreface shortened under cliffs). The drainage plan sees only the beach
   shift, so channels cut gullies through the scarps. Sparse sea stacks (3-12 wide, 5-25 high) stand 10-60 blocks
   off cliff stretches. Gates: `HighlandCoastTest` (user world and ROCKIES), `HighlandShoreTest`.

10. **Drowned coast** (RH10, model 9; phase 11): the coast edge value (the same field the RH9 profile, the plan and the
   renderer read) is transformed inside jittered regions about 4-7 km across (`HighlandDrowned`, position-only, so plan,
   renderer and every lookup path agree), flooding coastal valleys while land never goes below sea level. About 20-30%
   of the coast is drowned (region chance .32, measured 20.8% mean over two seeds and two presets). The arm network is
   ridged noise rigidly rotated per region (the arms are not tied to the real drainage valleys). Each region is a **ria**
   or a **fjord**: a region is a fjord where the land has ranges, the regional climate at its centre is PNW and the range
   core is at least .25 (the user preset has no ranges, so only rias there); otherwise a ria.
   - **Rias**: flooded river-valley bays with a narrow entrance (a connected-contour band over the first 400 blocks, so a
     bay cannot close behind a bar), a basin up to about 1.8 km across behind it, calm shores (94.7% beach-dominant against
     55.1% on the open coast, beaches about half as wide) and marsh edges (WETLAND tag, read as swamp / mangrove swamp
     in warm zones, taiga and snowy biomes in cold ones).
   - **Fjords**: steepened coastal ramp, long straight arms (median about 1.1 km, up to 5 km) with side arms, deep water
     (median about 150 blocks against a 28-block shelf), PNW rock walls (99.3% of the shore is cliff-dominant) and
     occasional sound basins (about 3 of 23 fjord regions, widest 1-2 km). A river entering a fjord head keeps the
     river-mouth fade (a small flat), with no water step.
   - **Islands**: chains and broad blobs of ridge-top islands on the shelf seaward of a drowned region (93% under 1 km,
     94% chained, 1-3 km islands at 1.1 per 25 km2 pooled; fjord islands 7.6 times higher and 7 times steeper than bay
     islands; small islands have no creek or river) and sparse open-sea islands (about 2-3 per owner with ocean,
     0.3-3 km across). No upstream FTF archipelago code is used.
   - **Sheltered water** is a measured outcome: 17.7% (ria site) and 21.3% (ROCKIES fjord site) of drowned-scope sea at
     least 3 blocks deep and sheltered from fetch, against 0.2% at model 8; harbors 27.5 / 39.8 per 25 km2 of drowned
     sea; navigable share (6-block channels) 99.4% / 97.2%.
   - Outside the regions the coast is the RH9 coast bit for bit (the transform is the only difference; checked by
     ablation against the model-8 golden). Model 9 is permanent for a world that uses it: `MODEL_VERSION` 9, model-8
     presets are rejected with the usual "create a new world" message, and there is no setting.
   - Gates: `HighlandDrownedTest` (shares, ocean band, fjord and bay shape, shores, islands, sheltered water, navigability,
     determinism, continuity, cost), `HighlandCoastTest` and `HighlandShoreTest` (RH9 gates outside drowned regions),
     `singleRidgeRangesAreFrozen` (golden `ranges-model9.sha256`).

   Final values (8 km boxes, `USER_SEED`; drowned scope is sea within the regions):

   | Quantity | Model 8 user | Model 9 user | Model 8 ROCKIES | Model 9 ROCKIES | Gate |
   |----------|-------------|--------------|----------------|-----------------|------|
   | Drowned share of coast | 0% | 16.8-23.4% (two seeds x two presets, pooled) | 0% | (same four windows) | mean 20-30% (measured 20.8%), each 15-35% |
   | Islands in the 8 km box | 0 | 180 in 8 ria boxes | 0 | 22 in 3 fjord boxes (3 at tracer level) | chains, mix and relief gates |
   | Sheltered share, drowned scope | 0.2% | 17.7% (ria site) | 0.2% | 21.3% (fjord site) | at least 15% per site (25% agreed, lowered by user) |
   | Harbors per 25 km2 of drowned sea | 1.56 | 27.5 | 1.65 | 39.8 | at least 1 |
   | Navigable share | 100% | 99.4% | 99.9% | 97.2% | at least 95% |
   | Ocean band in drowned scope | 100% | 100% | 100% | 100% | at least 95% |
   | Open-sea islands per owner with ocean | 0 | 2.9 | 0 | 2.2 | 1-4 |
   | Warm coast transect ns/column | 10062 (RH9) | x0.921 | | | at most x1.15 |
   | Drowned 2 km box ns/column | | x0.688 | | | at most x1.25 |
   | Site owner plan build | 8.5 s | x0.889 | 3.7 s | | at most x1.10 |

## Known limits

- Hack exponent 0.85–0.94 (basins elongated toward the coast; real ≈ 0.6).
- Concavity of water surfaces 0.19–0.28 (dam backwater flattens long profiles).
- River mouths can sit 1–2 blocks above sea level for the last few blocks.
- On high coastal hills (ROCKIES) a bluff or cliff top can follow the hill down inland, leaving a dry dip of 2-10
  blocks behind the scarp (~7% of ROCKIES scarp transects; under 1% on the user world).
- Channels that start with a large drainage area begin abruptly (a square head against a valley wall).
- Where a channel stands on a terrace, its 0.5 support slope can show faint 16-block facets in hillshades.
- Faint grid-scale patterns and occasional straight axis-aligned lines remain on some uplands (pre-RH4).
- Vanilla carvers can still breach river beds and banks (base FTF behaves the same).
- Aesthetic acceptance and the Streams Reflowing in-game smoke test are manual steps.
- RH10 ria bays reach only the low end of the intended 1-5 km: median inner width about 1 km, widest 1.77 km (limited by the
  arm reach 2500 and the region disc). Bigger bays need a per-type reach for rias and a re-check of the fjord arms.
- RH10 ria bays read as lagoons behind a bar with narrow inlets rather than separate branching tributaries; the census
  cannot tell the two apart (visual review is a UAT item).
- RH10 arms come from noise, not from the real drainage valleys (the lowness variant was measured and rejected in 11-03),
  so arms need not follow a river valley.
- RH10 fjord country is sparse: typing happens at the region centre, so most FJORD regions never reach the coast (23 in a
  120 km scan). Typing at the region's coast point would change types only, not ria geometry or the tuned share.
- RH10 fjord walls are about 50 blocks high (ratio to inland 1.01); taller walls need a larger `FJORD_WALL` or ramp factor,
  which the monotone steepening caps near 4.
- RH10 sheltered water meets 15% per site, not the 25% first agreed: the only tuning that reached about 24-29% broke the
  closed-water (5.5% over 5%) and big-island (0.95 under 1 per 25 km2) gates. The user lowered the target.
- RH10 `REGION_CHANCE` .32 holds the drowned share at 20.8%; .33 flooded the pinned confluence junction of
  `HighlandConfluenceWaterTest`, so the margin is thin and the share and region tuning are coupled.
- RH10 drowned regions are tuned to the user world (seed `-6240223350076425471`); the 11-03 sites are seed specific
  (seed 1 and 42 differ), so gates that need a site stay on that seed.
- RH10 big islands (1-3 km) keep too little interior for creeks or rivers (0 of 14), because the plan marks nodes within a
  lattice step of drowned sea as sea.
- RH10 bays and island channels are not width-gated beyond the 6-block navigability definition; channels between strips of
  one chain can be narrow.
- RH10 sea cliffs on arm shores are not judged by the quality seam gate (seams count land-to-land pairs only).
