# Evidence for issue #284 / PR #323 rework

Date: 2026-10-27. Static investigation (source, bytecode and upstream/mod repositories). No game launch, patched build or in-world reproduction has been performed for the rework.

## Provenance

- Issue: https://github.com/ETcodehome/FreeTerraForged/issues/284 — "Snow and flowers/grass do not layer correctly". Reporter (khkjkhjkjij) uses VanillaBackport and No Man's Land; small ground flowers sit in gaps in the snow instead of being covered.
- PR: https://github.com/ETcodehome/FreeTerraForged/pull/323 (`fixSnowVegetation` → `1.21.1_unstable`). Review by ETcodehome on 2026-10-27: **changes requested** — "Needs either rework as detailed above, or closing."
- Upstream `1.21.1_unstable` at `6acf6b30972abfafe104ded6fd0edcedcceb9460`.
- Worktree: `/private/tmp/ftf-pr323`. Branch `fixSnowVegetation` was rebased cleanly onto that upstream head: local `bd4f2cb5`, remote `origin/fixSnowVegetation` still `0853d942`. **Not pushed**; publishing the rebase requires a force-push.
- Vanilla facts were read with `javap` from the Mojang-mapped 1.21.1 jar (`~/.gradle/caches/fabric-loom/1.21.1/neoforge/21.1.219/minecraft-merged-mojang.jar`).
- Gortex does not track this checkout; local source, history and GitHub APIs were used.

Upstream source links below are pinned to `6acf6b30`.

## 1. Reviewer position (verbatim summary)

Core concern: the PR modifies normal vanilla behavior by destroying placed vegetation during `TOP_LAYER_MODIFICATION`. Co-existence of snow and flowers should be left to snow-layering mods (Snow! Real Magic). If progressed, it must be an **opt-in preset toggle**, ideally implemented in FTF's `DecorateSnowFeature` or surface rules rather than in vanilla's `SnowAndFreezeFeature`.

Findings table: redundant `warmEnoughToRain` (High), `pos.below()` allocations (Medium), light queried during `FEATURES` (Medium), destructive replacement (Critical), worldgen/weather desync (High), modded flora / floating halves / SRM bypass (High), hardcoded third-party IDs (Low).

## 2. Confirmed facts

### E1 — Vanilla snow placement cannot coexist with plants

- `SnowAndFreezeFeature.place` reads the `MOTION_BLOCKING` heightmap per column and calls `Biome.shouldFreeze(level, below, false)` then `Biome.shouldSnow(level, pos)`. On success it writes default `Blocks.SNOW` (1 layer) and sets `SnowyDirtBlock.SNOWY=true` on the block below.
- `Biome.shouldSnow`: `warmEnoughToRain` → reject; then build-height bounds and `getBrightness(LightLayer.BLOCK) < 10`; then the block must be `isAir()` **or** `is(Blocks.SNOW)`, and `Blocks.SNOW.defaultBlockState().canSurvive(level, pos)`.
- Small plants are not motion-blocking, so the heightmap points at the plant's own cell. The air/snow check fails and vanilla leaves the plant unsnowed. **The single-layer gap around a plant is vanilla behavior.**
- `shouldFreeze` also calls `warmEnoughToRain`, so vanilla already evaluates it twice per column. `Biome.getTemperature(BlockPos)` is backed by a per-thread `Long2FloatLinkedOpenHashMap` cache.
- `SnowLayerBlock.canSurvive` itself calls `pos.below()` (vanilla allocation).

### E2 — Lighting is not computed when features run

`ChunkStatus` order in 1.21.1: `… SURFACE, CARVERS, FEATURES, INITIALIZE_LIGHT, LIGHT, SPAWN, FULL`. Block-light queries during `FEATURES` cannot reflect this chunk's emitters. Vanilla and SRM still perform the query; it is effectively inert in worldgen.

### E3 — FTF's smooth-layer pass is what makes the gap visible

[`DecorateSnowFeature`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/world/worldgen/feature/DecorateSnowFeature.java) runs only when the active `RandomState` is an `FTFRandomState` with a generator context (lines 54, 130–133), so it is FTF-scoped by construction.

For every column where the FTF cell height equals the `MOTION_BLOCKING_NO_LEAVES` surface and is at or above sea level (lines 81–83):

- **Smooth pass** (lines 110–125): reads the block at `surfaceY+1`. **Only `Blocks.SNOW` is modified.** `smoothSnow` (152–166) derives the layer count from the fractional part of the terrain height (`cell.height / levels.unit`): `level = round(frac × 8)`. Level 0 leaves vanilla's single layer; 1–7 gives `SNOW[LAYERS=level]`; 8 gives `SNOW_BLOCK`. `fixBaseBlock` (168–179) converts a `SpreadingSnowyDirtBlock` (grass/mycelium) below to dirt when a 2–7 layer `SNOW` is placed.
- Result: snow beside a plant can be up to 7 layers or a full snow block while the plant column remains at ground level. This is the pit visible in issue #284. Without the smooth pass, the world shows only vanilla's 1-layer gap.

Each column's level depends only on that column's cell height. Adjacent columns vary gradually, so the level the plant column *would* receive is a good estimate of the surrounding pit depth. No cross-chunk neighbor reads are needed.

### E4 — FTF's erosion pass already removes plants

Same file, lines 86–107. In cold columns (`getTemperature <= 0.25`) that satisfy `snowErosion`, both `surfaceY+2` and `surfaceY+1` are set to air when `!MOTION_BLOCKING.isOpaque().test(state) || state.is(SNOW)`. Bytecode confirms `MOTION_BLOCKING` = `blocksMotion() || !fluid.isEmpty()`.

Flowers and short grass fail that test, so **the existing "Natural Snow Decorator" already deletes small plants on eroded cold slopes.** This precedent shows FTF's top-layer pass already edits vegetation. It does not by itself justify a default-on change.

### E5 — Feature ordering puts FTF after vanilla snow

- [`PresetBiomeModifierData`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/PresetBiomeModifierData.java) line 167 registers `ADD_SNOW_PROCESSING` = `append(TOP_LAYER_MODIFICATION, DECORATE_SNOW)` whenever `naturalSnowDecorator || smoothLayerDecorator`.
- [`Order.APPEND`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/modifier/Order.java) adds after existing entries. [`MinecraftWorldgenPlanCompiler.compilePlacedFeatures`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/world/worldgen/runtime/MinecraftWorldgenPlanCompiler.java) (~line 930) builds per-biome step lists and runs vanilla `FeatureSorter.buildFeaturesPerStep`.
- Vanilla `freeze_top_layer` is the sole `TOP_LAYER_MODIFICATION` entry in Overworld biomes, so `decorate_snow` is ordered after it.
- This matters because vanilla `shouldSnow` accepts existing `Blocks.SNOW` and would reset it to 1 layer if it ran later.
- Consequence: by the time `DecorateSnowFeature` runs, vanilla (and SRM, E6) have already decided each column.

### E6 — Snow! Real Magic hooks the same call the PR hooked

Sources: SRM `1.21-neoforge` @ `390145df` and `1.21-fabric` @ `b541e3eb` (identical files).

- `SnowAndFreezeFeatureMixin`: `@WrapOperation` on the **same** `Biome.shouldSnow` invoke inside `SnowAndFreezeFeature.place`, with default priority. When the original returns false it calls `Hooks.placeFeatureExtra`, which snowlogs the plant at 1 layer and returns the original `false`.
- PR #323's mixin wraps the same call. MixinExtras chains both wrappers, and the order is not controlled by either mod:
  - FTF outermost: SRM runs first and snowlogs the plant, then FTF's tag check misses the SRM block. Benign.
  - SRM outermost: FTF returns `true`, SRM skips snowlogging, and vanilla overwrites the plant with `SNOW`. **SRM is defeated.**
  - The reviewer's "bypasses snow-layering mods" finding is therefore real in at least one ordering.
- What a snowlogged plant becomes: `snowrealmagic:snowy_plant`, `snowy_double_plant_lower`/`_upper`, `snow_extra_collision` or `snowrealmagic:snow`.
  - All are `SRMSnowLayerBlock extends SnowLayerBlock implements EntityBlock`, carrying block entity `snowrealmagic:snow`.
  - **`is(Blocks.SNOW)` is false**; they are in `#minecraft:snow` via `#snowrealmagic:snow`.
- Config defaults: `replaceWorldFeature=true`, `placeSnowOnBlockNaturally=true`, `placeSnowOnBlock=true`, `restoreOriginalBlocks=false`. So SRM snowlogs at worldgen by default.
- SRM converts `#snowrealmagic:plants` (includes `#minecraft:small_flowers`, `#minecraft:flowers`, …) and falls back to class checks including `FlowerBlock`/`PinkPetalsBlock`. It rejects block entities, fluids and leaves.
- SRM edits only 1-layer snow at worldgen and never adjusts existing layer states.

Implication for a decorator-based design: with SRM active, plants reaching `DecorateSnowFeature` are already SRM blocks. They are neither `Blocks.SNOW` nor members of a vanilla-plant tag, so FTF neither smooths nor removes them. **Compatibility falls out of ordering with no mod detection.**

Known residual: an SRM-snowlogged plant stays at 1 layer beside deep FTF snow. Adjusting SRM block-entity blocks is a separate compatibility feature.

### E7 — Which tags cover the reporter's mods

Sources: VanillaBackport @ `0de7c04c`, No Man's Land @ `d94a25c6`, vanilla 1.21.1 data via misode/mcmeta `1.21.1-data` @ `aacd2a45`.

- **Vanilla 1.21.1 `#minecraft:small_flowers`:** dandelion, poppy, blue_orchid, allium, azure_bluet, 4 tulips, oxeye_daisy, cornflower, lily_of_the_valley, wither_rose, torchflower.
- `pink_petals` is in `#minecraft:flowers` only. `#minecraft:flowers` also contains tall flowers, flowering azalea and leaves, cherry leaves, mangrove_propagule, chorus_flower and spore_blossom. **It is unsuitable as a cover source.**
- `minecraft:wildflowers` is not a 1.21.1 vanilla block (it arrives in 1.21.5 / snapshot 25w02a).
- **VanillaBackport registers its blocks in the `minecraft` namespace.** Its wildflowers are `minecraft:wildflowers` (a vanilla `PinkPetalsBlock`; no block entity, no fluid). They are in `#minecraft:flowers`, **not** `#minecraft:small_flowers`.
  - The PR's `vanillabackport:wildflowers` entry names a block that does not exist.
  - Its optional `minecraft:wildflowers` entry was the correct one; the reviewer's "pseudo-`minecraft:wildflowers`" label is inaccurate for VanillaBackport users.
- VB's bush, firefly_bush, leaf_litter and short_dry_grass are only in `#minecraft:replaceable_by_trees`. They are not flowers and are out of scope.
- **No Man's Land:** its datagen adds every `FlowerBlock` (including all `FlowerbedBlock`s and clover/ground ivy) to `#minecraft:small_flowers`. `#nomansland:flowerbeds` is a subset, so the PR's optional NML tag entry is redundant.
  - NML places flowerbeds and flowers in snowy_plains, snowy_taiga, grove, ice_spikes and similar biomes, which matches the report.
  - NML already ships SRM compatibility tags.
- **Convention tags:** neither NeoForge `1.21.1` (generated `data/c/tags/block`) nor Fabric convention-tags v2 2.12.0 provides `c:` flower, grass or plant block tags. `#minecraft:small_flowers` *is* the cross-mod convention on 1.21.1.

Conclusion: a tag of `#minecraft:small_flowers` + `short_grass` + `fern` + `pink_petals` + optional `minecraft:wildflowers` covers vanilla, all NML flowers/flowerbeds, and VB wildflowers, with **no third-party namespace**.

### E8 — Settings and persistence surface

- [`MiscellaneousSettings`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/settings/MiscellaneousSettings.java) has 13 required `fieldOf` codec fields and a 13-arg constructor plus `copy()`.
  - Adding a required field would break every saved user preset JSON and every existing world's generated preset pack.
  - `optionalFieldOf(name, default)` is already used in `ErosionFilterSettings` for this purpose.
  - RecordCodecBuilder allows 16 fields, so a 14th fits.
- [`Presets`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/settings/Presets.java) constructs `MiscellaneousSettings` at 6 call sites (lines ~62, 107, 152, 197, 242, 455).
- [`DecorateSnowFeature.Config`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/world/worldgen/feature/DecorateSnowFeature.java) (line 212) has required `erode`, `smooth`, `erode_config`. Existing worlds persist the `configured_feature` JSON in their generated preset datapack ([`Datapacks.makePreset`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/data/worldgen/Datapacks.java)), so a new config field must also be optional with a false default.
- Wiring sites:
  - [`PresetConfiguredFeatures`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/PresetConfiguredFeatures.java) line 130–131.
  - `PresetPlacedFeatures` line 98.
  - `PresetBiomeModifierData` line 166 (registration is gated on `naturalSnowDecorator || smoothLayerDecorator`).
- GUI: [`MiscellaneousPage`](https://github.com/ETcodehome/FreeTerraForged/blob/6acf6b30972abfafe104ded6fd0edcedcceb9460/common/src/main/java/etcodehome/freeterraforged/client/gui/screen/presetconfig/MiscellaneousPage.java) uses `PresetWidgets.createToggle(initial, key, callback)`. The tooltip key is `Tooltips.translationKey(key)`.
- Strings: `FTFTranslationKeys`, `FTFLanguageProvider` (labels ~line 74–79, tooltips ~237–242) and the committed `assets/freeterraforged/lang/en_us.json`.
- Tags: `FTFBlockTags` and `FTFBlockTagsProvider` (which feeds the generated preset pack), plus static `data/freeterraforged/tags/block/*.json`. `ERODIBLE` follows the dual pattern.

### E9 — Available verification hooks

- `PresetValidationRunner` (`common/.../test/`) is a headless server harness. It is enabled by `-Dfreeterraforged.test.preset=true` via the `presetTestServer` run configs in `fabric/build.gradle` and `neoforge/build.gradle`.
  - It round-trips presets through `Preset.DIRECT_CODEC`, datapack zips and stage reloads.
  - It is the natural place for codec back-compatibility assertions.
- Benchmark run config properties exist (`freeterraforged.benchmark.*`).
- There is no `src/test` JUnit source set in `common`.

## 3. Reassessment of review findings

| Finding | Verdict | Evidence | Effect on rework |
|---|---|---|---|
| Destructive replacement (Critical) | **Valid** | E1, PR writes `SNOW` over plant | Keep removal but make it opt-in, limited to FTF-created pits, documented as removal |
| SRM bypass / modded flora (High) | **Valid**, stronger than stated | E6 mixin-order race | Remove the mixin; act after `freeze_top_layer` inside `DecorateSnowFeature` |
| Worldgen/weather desync (High) | Valid, but pre-existing | E3: FTF smoothing already creates multi-layer snow that weather never produces | Document; no new mechanism |
| Floating top halves (High) | Low | Tag-gated; double plants excluded | Also reject any state with `DOUBLE_BLOCK_HALF`, a block entity or a fluid |
| Redundant `warmEnoughToRain` (High) | Overstated | E1: vanilla calls it twice, cached; SRM repeats it | Check climate only after a cheap block/tag match on a non-snow column |
| `pos.below()` allocations (Medium) | Overstated | E1: vanilla `canSurvive` path | Not material; reuse `MutableBlockPos` where we own the code |
| Light during FEATURES (Medium) | Correct but inert | E2: vanilla/SRM do the same | Omit the light check in FTF code; document why |
| Third-party IDs (Low) | Partly valid | E7 | Drop `vanillabackport:*` and `#nomansland:*`; keep optional `minecraft:wildflowers` with justification |

## 4. Options considered

| Option | Summary | Assessment |
|---|---|---|
| A. Keep PR mixin | Wrap `shouldSnow` | Rejected by the owner; SRM race (E6); runs regardless of FTF snow settings |
| **B. Opt-in cover inside `DecorateSnowFeature` smooth pass** | When enabled and the column's computed FTF level ≥ 2, replace a tagged small plant with that level of snow | **Recommended.** Matches the reviewer's suggested location. FTF-scoped by construction (E3). Runs after vanilla/SRM (E5/E6). Only acts where FTF itself deepened the snow beyond vanilla. Off by default. |
| C. Surface rules | Mentioned by the reviewer | Surface rules run at `SURFACE`, before vegetation is placed (`FEATURES`), so they cannot see the plants. Not viable for this problem. |
| D. Suppress vegetation placement in cold FTF columns | Prevent the plant rather than remove it | Changes vegetation density and every vegetation feature; much larger blast radius and mod impact |
| E. Close; point users to SRM | No FTF change | Valid fallback. Leaves the residual 1-layer SRM plant beside deep FTF snow. |
| F. Adjust SRM blocks' `LAYERS` in the smooth pass | Non-destructive deep cover with SRM | Interesting follow-up; requires SRM block-entity semantics and runtime validation; out of scope |

## 5. Open questions (to settle in spec or in-game)

1. Threshold: is level ≥ 2 the right trigger for "FTF-created pit", or should it be higher (e.g. ≥ 3) to avoid removing plants where the step is only 2 px above vanilla? This needs an in-game look.
2. Should `pink_petals` / wildflowers (flowerbeds, partial-block coverage) be in the default tag? They are in scope for the reporter, but the reviewer flagged flowerbeds specifically.
3. The UI dependency on `smoothLayerDecorator`: tooltip note only, or disable the toggle when smoothing is off? There is no existing dependent-widget pattern on `MiscellaneousPage`.
4. Whether to also set `SNOWY=true` on non-spreading `SnowyDirtBlock`s (podzol) beneath newly placed cover. This is required for visual parity with vanilla snow columns; see spec.
