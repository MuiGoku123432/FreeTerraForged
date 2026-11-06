Thanks for the thorough review @ETcodehome. You were right about the core problem, so I've reworked the PR rather than closing it. I rebased onto current `1.21.1_unstable` and pushed `d5f10c40`.

**Summary of the rework:** the `SnowAndFreezeFeature` mixin is removed. The behavior is now an opt-in preset toggle, **Snow Covers Small Plants**, implemented inside `DecorateSnowFeature`'s smooth pass and off by default in every preset. It only acts where FTF's own smoothing has made the snow at least 2 layers deep, so vanilla-depth columns keep vanilla's behavior.

How each finding was handled:

| Finding | Resolution |
| :--- | :--- |
| Destructive vegetation replacement | Off by default. When enabled, it only affects small tagged plants in columns where the smoothed snow is at least 2 layers deep, which is the pit case from #284. The tooltip states that plants are removed and points to Snow! Real Magic for keeping them. |
| Use `DecorateSnowFeature` rather than `SnowAndFreezeFeature` | Done; no FTF code touches vanilla's feature or `Biome.shouldSnow` anymore. |
| Mod compatibility / Snow! Real Magic | SRM wraps the same `shouldSnow` call the old mixin did, so the old approach could pre-empt it depending on mixin order. `decorate_snow` now runs after `freeze_top_layer`, so plants SRM has snowlogged are already SRM blocks, which aren't tagged and carry block entities, and FTF skips them. Any state with `DOUBLE_BLOCK_HALF`, a `DoublePlantBlock`, a block entity or a fluid is never replaced. |
| Worldgen vs weather desync | Weather and random ticks are unchanged. The difference only applies where FTF's smoothing already produces snow deeper than weather does. |
| Redundant `warmEnoughToRain` | The climate check now only runs for tagged plants in columns already selected for 2+ layers of smoothing, after the cheaper checks. |
| `pos.below()` allocations | No new allocations in FTF code; the existing `MutableBlockPos` is reused. |
| Block light during `FEATURES` | Dropped, with a comment explaining that lighting isn't computed at that stage. |
| Hardcoded third-party IDs | The tag is now vanilla-namespace only: short grass, fern, pink petals, `#minecraft:small_flowers`, optional `minecraft:wildflowers`. No Man's Land puts its flowers and flowerbeds in `#minecraft:small_flowers`. VanillaBackport registers wildflowers as `minecraft:wildflowers`, which is also the 1.21.5 vanilla ID, so the optional entry stays. 1.21.1 has no `c:` flower block tags on either loader. |
| Opt-in preset toggle | `snowCoversSmallPlants` is optional and defaults to `false`, as is the new `cover_plants` feature config field. Existing presets and worlds' generated preset packs decode unchanged. `runPresetTest` has a new check covering this. |

Builds and `runPresetTest` (13/13) pass on Fabric and NeoForge. I haven't tested in-game yet. I'll post results for option off vs on, smoothing off, SRM installed, and loading an existing world before asking for another look.
