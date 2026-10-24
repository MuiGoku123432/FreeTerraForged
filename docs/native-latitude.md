# Native latitude climate (experimental custom-branch feature)

Native latitude adds broad north–south climate patterns to new FreeTerraForged worlds. It is disabled by default and needs neither Natural Temperature nor TerraLatitude.

In the climate editor, enable **Native latitude climate**, set **Equator Z** and **Distance to polar climate** in blocks, leave **Repeat latitude bands** on for a repeating world, leave **Original FTF temperature bands** on to retain the original curve and warped boundaries, then choose independent temperature and moisture strengths. Start with **Temperature band influence = 1.0** to see the configured original pattern alone.

| Control | Meaning |
| --- | --- |
| Equator Z | Nominal warm-band center; original temperature warps make boundaries wander |
| Distance to polar climate | Nominal equator-to-pole spacing; the broad repeating cycle is twice this distance |
| Repeat latitude bands | On: warm → polar → warm, repeating north and south. Off: the polar target continues beyond the first poles |
| Original FTF temperature bands | On retains the original signed sine-power curve and both temperature Perlin warps; off selects the earlier smooth latitude model |
| Temperature band influence | 0 retains unconfigured temperature; 1 uses the configured pattern alone; intermediate values blend the two |
| Moisture latitude strength | 0 retains existing moisture; higher values blend broad wet/dry latitude bands with regional moisture |

The moisture pipeline remains **existing regional moisture → optional latitude blend → existing coastal boost and elevation drying**. Existing moisture settings keep their meanings. At moisture strength 1, its regional scale/falloff controls have no influence. In original temperature mode, **Falloff** and the temperature seed offset still shape the configured bands at strength 1; **Distance to polar climate** replaces temperature **Scale** for that contribution. Range/bias and terrain shaping still apply once. Temperature Scale continues to affect the unconfigured contribution at influences below 1. These are biome climate values, not Celsius, measured rainfall or live weather.

For example, equator Z **0** and polar distance **5,000** places nominal warm bands at Z=0, ±10,000, ±20,000 and nominal polar bands at ±5,000, ±15,000. Original temperature warps distort those boundaries and do not repeat identically from one cycle to the next. Both latitude temperature and latitude moisture follow the repeating pattern; regional and terrain effects still apply. Change the toggle in **Climate Settings** and use **Preview: Temperature**, **Moisture** or **Biome** to inspect the result.

## Saves and compatibility

Settings are carried by the existing FTF preset datapack. Keep that datapack with the save when moving it to a dedicated server. Presets without latitude settings retain legacy climate. A present latitude object must be complete and valid, even when disabled.

Only FTF-owned Overworld generation and its preview apply the native layer. Native-enabled profiles reject the known `naturaltemperature`, `terralatitude` and `fnf_altitude` transformers before creating their climate context. Native-off profiles retain existing mod behavior. This is not a claim of compatibility detection for every third-party mod.

An owned Overworld with an enabled `terralatitude-climate.json` schema-1 marker must retain TerraLatitude and Natural Temperature. Enabling native in such a save is rejected. No profile migration, retrogen or active-world editing is provided. Manually changing generation settings can create borders in newly explored chunks.

## Draft model and verification status

New presets use **`ftf:latitude_v3_original_repeating_draft`**, schema 1, with native climate still off. Turning repetition off selects **`ftf:latitude_v3_original_capped_draft`**. Both retain the original signed sine-power temperature shape and two Perlin warp stages, rescaled to the configured pole distance and shifted to the equator. This restores the style of the original generator; enabling it changes coordinates/output, so it is not an exact old-world migration.

Existing **`ftf:latitude_v1_draft`** (capped) and **`ftf:latitude_v2_repeating_draft`** (repeating) profiles keep their smooth temperature model. Decoding never upgrades saved profiles. In a **new-world preset**, enable **Original FTF temperature bands** to switch an older profile; it retains your strengths, so set temperature influence to 1.0 if you want the configured pattern alone. A value such as 0.65 still works, but mixes the old and configured patterns.

Initial editor values are equator 0, pole distance 50,000, temperature influence 1.0 and moisture influence 0.5. Moisture retains the same piecewise smoothstep latitude knots and the existing regional/coastal/elevation pipeline. Altitude cooling is unchanged. More physical cooling, actual coast-distance influence and directional rain shadows remain separate follow-up decisions.

This build is not generation-stable. The final model/default decision, comparative biome maps, client preview checks, dedicated-server save/reopen matrix and performance budget remain release gates. Do not assume that draft worlds have a supported migration to a later stable model.

Example optional object inside a preset's `climate` settings:

```json
"latitude": {
  "enabled": true,
  "schemaVersion": 1,
  "model": "ftf:latitude_v3_original_repeating_draft",
  "equatorZ": 0,
  "poleDistance": 50000,
  "temperatureStrength": 1.0,
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

For v3, the temperature target fields contain the actual warped original-band target; the fraction and moisture target remain unwarped geographic values. Regional and stage fields use 0–1 units; cell fields are signed −1–1; actual sampler fields are Minecraft's quantized climate values. Terrain filters, cache paths and biome policies can explain differences: a recomputed shaping value alone does not certify generated-world correctness. Compare equivalent coordinates and inspect stored generated biomes as part of release validation.

## Development checks

```sh
./gradlew :common:latitudeTest :fabric:build :neoforge:build
```

The standalone Java suite runs real common climate code with a test-only registry binding and a frozen pre-change `ClimateModule` regression oracle. It checks codecs, legacy/no-op output, independent strengths, range mapping, seed sequence, owner isolation, original geography and diagnostic bounds. It does not launch a game or establish actual biome distributions.

See the [specification](../.planning/features/native-latitude-climate/SPEC.md) and [implementation evidence](../.planning/features/native-latitude-climate/IMPLEMENTATION.md) for outstanding acceptance work.
