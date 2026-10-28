# Rolling Highlands RH3

Branch: `feature/rolling-highlands`. Build `1.0.0014-rh3`, generation model 3. Plan, decisions and per-phase log:
[PLAN-RH3.md](PLAN-RH3.md). Research: [RESEARCH-RH3.md](../../research/rolling-highlands/RESEARCH-RH3.md).
Earlier models (RH1, RH2) are in git history.

## Trying it

Create a **new world**, open the FTF preset editor and select **Rolling Highlands (Experimental)** under
World → Continent Type. Presets from models 1–2 are rejected with a "create a new world" message.

- **Terrain page:** four RH controls (upland spacing, upland height, valley depth, valley width) followed by the full
  normal FTF terrain controls — they shape RH3's land exactly as for other continent types.
- **River page:** river count (drainage density), flow toggles, main-river bed depth / bank heights / bank width,
  lake chance and depth, wetland chance.
- World height ≥ 320 above sea level; continent scale 1000–5000.

## How it works

1. **Land** = the legacy FTF land composition (terrain regions, region borders, mountain chains with variety and
   fancy erosion, `Blender`), extracted unchanged as `Heightmap.makeLand`, plus a broad smooth upland uplift.
2. **Drainage plan** per continent on that land (128-block grid): priority flood to the coast, drainage area,
   stream capture of parallel channels, stream-power incision `z = min(land, z_receiver + run·k·A^−0.45)`,
   monotone water levels, depression lakes (keep or breach by FTF lake chance), and dammed valley lakes on gentle
   large rivers (FTF lake depth).
3. **Rendered surface** = land − smooth (Catmull-Rom) incision; valleys are where the drainage on this same land says.
4. **River valleys** use FTF's `UpliftRiverCarver` profile (riverbed, terraced bank step, flat floor, terraced
   fadeout with talus and cliffs, gully/rivulet drainage, pinch/width/asymmetry noises) driven by the plan's water
   level, drainage area and the FTF main-river settings; channels compose continuously with confluence rapids.
5. **Lakes and wetlands:** lake shorelines follow the plan with shoreline noise and rims; floodplain wetlands on flat,
   large, low reaches carry WETLAND terrain and swamp-level erosion (swamp / mangrove biomes).
6. **Everything downstream is FTF's:** biomes from the native populator parameters (valley floors pulled to the low
   slice), legacy oceans, Phacelle erosion filter and cliff relaxation, surface water mixin, river gasket, scree,
   flow field (currents follow the legacy carver's flow model), Streams Reflowing bridge.

## Known limits

- Hack exponent 0.85–0.94 (basins elongated toward the coast; real ≈ 0.6).
- Concavity of water surfaces 0.19–0.28 (dam backwater flattens long profiles).
- River mouths can sit 1–2 blocks above sea level for the last few blocks.
- Aesthetic acceptance and the Streams Reflowing in-game smoke test are manual steps.
