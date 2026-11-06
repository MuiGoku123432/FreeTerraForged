# Rolling Highlands

Branch: `feature/rolling-highlands`. Build `1.0.0023-rh9`, generation model 8 (coasts: item 9). Plans and per-phase logs:
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
