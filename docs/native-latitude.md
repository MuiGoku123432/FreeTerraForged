# Native latitude climate (experimental custom-branch feature)

Native latitude adds broad north–south climate patterns to new FreeTerraForged worlds. It is disabled by default and needs neither Natural Temperature nor TerraLatitude.

In the climate editor, enable **Native latitude climate**, set **Equator Z** and **Distance to polar climate** in blocks, leave **Repeat latitude bands** on for a repeating world, then choose independent temperature and moisture strengths.

| Control | Meaning |
| --- | --- |
| Equator Z | Warmest geographic latitude before regional and terrain effects |
| Distance to polar climate | Distance from an equator to a polar band; with repeating enabled, the next equator is twice this distance away |
| Repeat latitude bands | On: warm → polar → warm, repeating north and south. Off: the polar target continues beyond the first poles |
| Temperature latitude strength | 0 retains regional temperature; 1 uses the geographic target before existing cooling |
| Moisture latitude strength | 0 retains existing moisture; higher values blend broad wet/dry latitude bands with regional moisture |

The moisture pipeline remains **existing regional moisture → optional latitude blend → existing coastal boost and elevation drying**. Existing moisture settings keep their meanings. At strength 1 there is no regional contribution for that axis, so its regional scale/falloff controls have no influence; range/bias and terrain shaping still apply. These are biome climate values, not Celsius, measured rainfall or live weather.

For example, equator Z **0** and polar distance **5,000** gives warm bands at Z=0, ±10,000, ±20,000 and polar bands at ±5,000, ±15,000. Both latitude temperature and latitude moisture follow the repeating pattern; regional and terrain effects still apply. Change the toggle in **Climate Settings** and use **Preview: Temperature**, **Moisture** or **Biome** to inspect the result.

## Saves and compatibility

Settings are carried by the existing FTF preset datapack. Keep that datapack with the save when moving it to a dedicated server. Presets without latitude settings retain legacy climate. A present latitude object must be complete and valid, even when disabled.

Only FTF-owned Overworld generation and its preview apply the native layer. Native-enabled profiles reject the known `naturaltemperature`, `terralatitude` and `fnf_altitude` transformers before creating their climate context. Native-off profiles retain existing mod behavior. This is not a claim of compatibility detection for every third-party mod.

An owned Overworld with an enabled `terralatitude-climate.json` schema-1 marker must retain TerraLatitude and Natural Temperature. Enabling native in such a save is rejected. No profile migration, retrogen or active-world editing is provided. Manually changing generation settings can create borders in newly explored chunks.

## Draft model and verification status

New presets use **`ftf:latitude_v2_repeating_draft`**, schema 1. Existing **`ftf:latitude_v1_draft`** profiles keep their original nonrepeating polar caps; decoding never upgrades them. The **Repeat latitude bands** toggle explicitly selects between the two models for a new-world preset. It uses cubic smoothstep temperature and bounded piecewise smoothstep moisture knots. The initial editor values are equator 0, pole distance 50,000, temperature strength 0.75 and moisture strength 0.5. They are provisional tuning values.

This build is not generation-stable. The final model/default decision, comparative biome maps, client preview checks, dedicated-server save/reopen matrix and performance budget remain release gates. Do not assume that draft worlds have a supported migration to a later stable model.

Example optional object inside a preset's `climate` settings:

```json
"latitude": {
  "enabled": true,
  "schemaVersion": 1,
  "model": "ftf:latitude_v2_repeating_draft",
  "equatorZ": 0,
  "poleDistance": 50000,
  "temperatureStrength": 0.75,
  "moistureStrength": 0.5
}
```

## Operator diagnostics

Permission level 2 is required. Run in the FTF Overworld:

```text
/ftf climate transect <x> <z> <stepX> <stepZ> <count>
/ftf climate transect 0 -50000 0 1000 101
/ftf climate transect -50000 0 1000 0 101
```

Use count 1 for a point, 0 for an explicit empty export, and zero steps to retain duplicate points. The limit is 4,096 points; the entire coordinate range is validated before sampling. Start with small requests: diagnostics run on the server command thread and can be expensive even though the request is bounded.

Exports are UTF-8 JSON under the world's `debug/ftf-latitude/`. They contain version/model/profile/seed/dimension provenance, evaluator class SHA-256, requested and quart-aligned block coordinates, surface height, regional/latitude/blended/shaped values, final cell values, independently queried actual sampler coordinates, sampled biome and stored biome where a chunk is already loaded. No chunks are loaded or generated for the command; absent stored biome data is recorded as null. Existing terrain queries still perform the usual FTF terrain calculations.

Regional and stage fields use 0–1 units; cell fields are signed −1–1; actual sampler fields are Minecraft's quantized climate values. Terrain filters, cache paths and biome policies can explain differences: a recomputed shaping value alone does not certify generated-world correctness. Compare equivalent coordinates and inspect stored generated biomes as part of release validation.

## Development checks

```sh
./gradlew :common:latitudeTest :fabric:build :neoforge:build
```

The standalone Java suite runs real common climate code with a test-only registry binding and a frozen pre-change `ClimateModule` regression oracle. It checks codecs, legacy/no-op output, independent strengths, range mapping, seed sequence, owner isolation, original geography and diagnostic bounds. It does not launch a game or establish actual biome distributions.

See the [specification](../.planning/features/native-latitude-climate/SPEC.md) and [implementation evidence](../.planning/features/native-latitude-climate/IMPLEMENTATION.md) for outstanding acceptance work.
