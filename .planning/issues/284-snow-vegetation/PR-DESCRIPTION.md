> [!NOTE]
> Heads up: AI was used in the process of coding this. It's been built and run through the headless preset validation on both loaders (details below), but it hasn't been checked in-game yet.

Addresses #284, for `1.21.1_unstable`. Reworked after review: the vanilla `SnowAndFreezeFeature` mixin is gone, and the behavior is now an **opt-in preset setting inside `DecorateSnowFeature`, off by default**.

## Cause

Vanilla leaves small plants unsnowed: `Biome.shouldSnow` only accepts air or existing snow, so the single-layer gap around a flower is normal vanilla behavior.

The gap becomes a visible pit because of FTF's Smooth Layer Decorator. It raises snow next to the plant to as many as 7 layers or a full snow block, but it only touches `Blocks.SNOW`, so the plant column stays at ground level.

## Change

A new Miscellaneous setting, **Snow Covers Small Plants** (`snowCoversSmallPlants`), defaults to `false` everywhere. When it's enabled, the existing smooth-layer pass also handles columns topped by a small plant:

- The column must already qualify for smoothing, i.e. FTF terrain height equals the surface and it's at or above sea level.
- It only acts where that column's computed snow depth is **2 layers or more**. Depth 0–1 matches vanilla, so the plant is kept.
- The plant must be in `#freeterraforged:snow_coverable`, have no `DOUBLE_BLOCK_HALF` property, not be a `DoublePlantBlock`, and have no block entity or fluid.
- It applies vanilla's snow conditions: `coldEnoughToSnow`, build height, and `SNOW.canSurvive`. Block light isn't checked because lighting isn't computed until after the features stage.
- The plant is replaced with the same snow state the smoothing gives its neighbours. The ground below is handled the same way, including `SNOWY=true` where the property remains.

Because `decorate_snow` is appended after `freeze_top_layer`, vanilla and any snow-layering mod have already handled each column before this runs. For example, Snow! Real Magic snowlogs plants into its own `SnowLayerBlock` subclasses with block entities. Those aren't in the tag, so FTF leaves them alone. There's no mod detection and nothing wraps `Biome.shouldSnow`.

## Compatibility

- `snowCoversSmallPlants` and `DecorateSnowFeature.Config.cover_plants` are both `optionalFieldOf(..., false)`. Saved presets and the generated preset packs in existing worlds decode unchanged and behave as before.
- All built-in presets set it to `false`. Nothing changes unless a player enables it, and it has no effect while Smooth Layer Decorator is off.
- Non-FTF generators are unaffected; `DecorateSnowFeature` already exits without an `FTFRandomState`.

## Tag

`#freeterraforged:snow_coverable` contains `minecraft:short_grass`, `minecraft:fern`, `minecraft:pink_petals`, `#minecraft:small_flowers` and optional `minecraft:wildflowers`. There are no third-party namespaces.

- No Man's Land puts all of its flowers and flowerbeds in `#minecraft:small_flowers`.
- VanillaBackport registers its wildflowers as `minecraft:wildflowers`. That's also the vanilla ID from 1.21.5, hence the optional entry.
- Neither loader has `c:` flower block tags on 1.21.1.

A datapack can narrow the tag, or empty it to disable the behavior.

## Verification

- `./gradlew build` passes for common, Fabric and NeoForge on Java 21.
- `./gradlew runPresetTest` passes 13/13 on both Fabric and NeoForge, including a new check. It covers:
  - presets and `decorate_snow` configs without the new field decode as disabled;
  - enabled values survive the codec round trip and `copy()`;
  - the bundled tag includes poppy, short grass and pink petals, and excludes tall grass, snow and saplings.
- **No in-game testing yet.** Still to do: fresh worlds with the option off vs on, smoothing off with the option on, Snow! Real Magic installed, a vanilla-generator world, and loading a world created on current `1.21.1_unstable`. I'll update this PR with results.

## Notes

- This **removes** eligible plants when enabled; it doesn't keep them inside the snow. The tooltip says so and points to Snow! Real Magic for keeping plants.
- Weather snowfall, random ticks and existing chunks are unchanged.
- With Snow! Real Magic, its snowlogged plants stay at 1 layer beside deeper FTF snow. Matching that depth would be a separate change.
