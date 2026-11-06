# Issue #284 / PR #323 — Opt-in snow cover for small plants in FTF snow pits

Status: implemented in worktree (uncommitted, 2026-10-27). `./gradlew build` and `runPresetTest` (Fabric and NeoForge, 13/13 including the new snow-cover check) pass. In-game verification (§7 steps 2–3) not yet done.
Date: 2026-10-27
Issue: https://github.com/ETcodehome/FreeTerraForged/issues/284
PR under rework: https://github.com/ETcodehome/FreeTerraForged/pull/323 (changes requested by ETcodehome)
Target: upstream `1.21.1_unstable` @ `6acf6b30972abfafe104ded6fd0edcedcceb9460`. Worktree `/private/tmp/ftf-pr323`, branch `fixSnowVegetation`, rebased locally to `bd4f2cb5` (not pushed).
Evidence: [RESEARCH.md](RESEARCH.md). References `E#` point to its sections.

## 1. User story

As a player using FreeTerraForged's smooth snow layers (optionally with flower mods such as No Man's Land or VanillaBackport), I can opt in so that small flowers and grass in cold biomes don't leave holes where FTF has built snow deeper than vanilla's single layer. When I leave the option off, plants and snow behave exactly as they do today. When Snow! Real Magic is installed, its snowlogged plants are left alone.

## 2. Authority and decisions

| Item | Source |
|---|---|
| Rework PR #323 rather than close it; rebase onto `1.21.1_unstable` | **User decision** (2026-10-27) |
| Opt-in preset toggle; implement in `DecorateSnowFeature`, not `SnowAndFreezeFeature` | **Repository owner review** (required for merge) |
| Ship `false` in all built-in presets. Propose enabling it in built-in presets later, as a separate follow-up PR backed by in-game before/after evidence. A missing field always decodes to `false` (legacy isolation, not a preference). | **User decision** (2026-10-27) |
| Remove the plant only where FTF's computed layer level is ≥ 2 | Proposal (E3). Tune after the in-game check (§7). |
| Default tag contents (§4.3) | Proposal (E7). User may trim. |
| UI: always-visible toggle whose tooltip states the smooth-layer dependency | Proposal (E8: no dependent-widget pattern exists) |

## 3. Requirements and acceptance

| ID | Requirement | Acceptance |
|---|---|---|
| SV-01 | **Opt-in setting.** Add `MiscellaneousSettings.snowCoversSmallPlants` (boolean). Serialize with `optionalFieldOf("snowCoversSmallPlants", false)`. Include it in the constructor and `copy()`. All built-in presets in `Presets` set `false`. | Codec round trip preserves `true`/`false`. A preset JSON lacking the field decodes to `false`. `copy()` is independent. All 6 `Presets` call sites compile with an explicit `false`. |
| SV-02 | **Legacy isolation.** Existing user presets, existing worlds' generated preset packs, and any world with the option off behave exactly as before. `DecorateSnowFeature.Config` gains `cover_plants` via `optionalFieldOf("cover_plants", false)`. | An existing world created with upstream `6acf6b30` loads without codec errors. A `decorate_snow` configured-feature JSON without `cover_plants` decodes. With the option off, chunks generated at a fixed seed/region match the upstream baseline block-for-block. |
| SV-03 | **Scope: FTF-created pits only.** Cover is attempted only inside the existing smooth pass (`config.smooth && config.coverPlants`), on columns already admitted by that pass (FTF cell height == `MOTION_BLOCKING_NO_LEAVES` surface, ≥ sea level). It applies only when the column's computed level from the existing `getLevel` math is ≥ `MIN_COVER_LEVEL = 2`. | Columns with computed level 0–1 keep their plant (vanilla look). Columns ≥ 2 receive cover. No effect in non-FTF generators or dimensions without an `FTFRandomState` (existing guard). No effect when `smoothLayerDecorator` is off, even if the option is on. |
| SV-04 | **Climate and support parity with vanilla.** At `p = surfaceY+1`: the biome at `p` must satisfy `coldEnoughToSnow(p)`, `p.y` must be inside build height, and `Blocks.SNOW.defaultBlockState().canSurvive(level, p)` must hold. The block-light check is intentionally omitted (E2) and the omission is commented in code. | Warm/temperate biomes: no change. Cold biomes over ice, packed ice or barrier (`snow_layer_cannot_survive_on`): no change. |
| SV-05 | **Eligible blocks only.** The state at `p` must be in `#freeterraforged:snow_coverable`, have no `DOUBLE_BLOCK_HALF` property, not be a `DoublePlantBlock`, have no block entity, and have an empty fluid state. | Tall grass, large fern, tall flowers, any half-block plant (including modded plants using `DOUBLE_BLOCK_HALF`), waterlogged states and block-entity plants are untouched even if tagged. Overriding the tag with an empty list (`replace: true`) disables all cover. |
| SV-06 | **Result parity with neighboring snow columns.** Place `getState(level)` (2–7 → `SNOW[LAYERS]`, 8 → `SNOW_BLOCK`). Then apply the existing `fixBaseBlock`. If the block below still has `SnowyDirtBlock.SNOWY`, set it `true` (matching what vanilla `freeze_top_layer` did for adjacent snow columns). | Covered columns are indistinguishable from neighboring smoothed snow columns at the same level: same layer state and same base-block result (grass/mycelium → dirt for 2–7, snowy podzol/grass under snow block). |
| SV-07 | **No change to vanilla features.** Delete `MixinSnowAndFreezeFeature`, its `freeterraforged-common.mixins.json` entry, and `SnowVegetation`. No FTF code wraps `Biome.shouldSnow`. | Mixin config lists no `SnowAndFreezeFeature` target. A vanilla-generator world is unaffected with FTF installed. |
| SV-08 | **Snow! Real Magic compatibility.** No mod detection. Because `decorate_snow` is appended after `freeze_top_layer` (E5), SRM has already snowlogged eligible plants. SRM blocks aren't in the tag and have block entities, so FTF never replaces them. | With SRM at default config and the option on: sampled snowy-plains/taiga plants remain `snowrealmagic:*` blocks with their stored plant. No FTF-induced removal is observed. |
| SV-09 | **Tag hygiene.** `#freeterraforged:snow_coverable` contains only vanilla-namespace entries (§4.3). Drop `vanillabackport:wildflowers` (nonexistent) and `#nomansland:flowerbeds` (redundant via `#minecraft:small_flowers`). Keep the static JSON and the `FTFBlockTagsProvider` entry consistent, as `ERODIBLE` does. | The bundled JSON and the generated preset-pack tag list identical entries. No non-`minecraft`/non-`freeterraforged` namespaces. NML flowers/flowerbeds and VB wildflowers resolve as members when those mods are present. |
| SV-10 | **Bounded cost.** The new branch runs only when the smooth-pass block is neither air nor `SNOW`. Check order: option flag → tag/property checks → level threshold → climate → `canSurvive`. Reuse the existing `MutableBlockPos`; no new per-column allocations in FTF code. | Code review confirms ordering. Optional: benchmark run config (`freeterraforged.benchmark.*`) with the option on vs off shows a chunk-generation time difference within run-to-run noise. |
| SV-11 | **Determinism.** The decision uses only that column's cell, block state and biome, with no neighbor or cross-chunk reads. | Same seed/settings produce identical results regardless of chunk generation order. |
| SV-12 | **Editor and text.** Add a toggle to `MiscellaneousPage` immediately after `smoothLayerDecorator`. Translation key `gui.button.snowCoversSmallPlants` plus tooltip in `FTFTranslationKeys`, `FTFLanguageProvider` and `en_us.json`. | The toggle appears, persists through save/reopen and preset copy, and its tooltip states the dependency and the removal (§4.4). |
| SV-13 | **Builds and harness.** | `./gradlew build` passes (common, Fabric, NeoForge, Java 21). `presetTestServer` passes on both loaders. The harness gains an assertion that decoding a `miscellaneous` object without the new key yields `false`. |

## 4. Design detail

### 4.1 Algorithm (inside the existing `config.smooth` block of `DecorateSnowFeature.place`)

```
pos = (worldX, surfaceY + 1, worldZ)
state = chunk.getBlockState(pos)          // existing
[existing air → surfaceY fallback unchanged]
if state.is(SNOW):                        // existing path unchanged
    smoothSnow(...)
else if config.coverPlants && isCoverable(state):
    level = getLevel(depth(cell, levels))  // reuse existing math, no duplication
    if level >= MIN_COVER_LEVEL
       && pos.y within build height
       && biomeAt(pos).coldEnoughToSnow(pos)
       && SNOW.defaultBlockState().canSurvive(level, pos):
        layer = getState(level)
        chunk.setBlockState(pos, layer, false)
        fixBaseBlock(chunk, pos, layer, level)
        markBelowSnowy(chunk, pos)         // SNOWY=true if property present
```

- Refactor `smoothSnow` so the level computation is shared, not copied. Behavior for existing `SNOW` columns must stay identical (SV-02).
- `isCoverable(state)`: tag membership, no `BlockStateProperties.DOUBLE_BLOCK_HALF`, not a `DoublePlantBlock`, `!hasBlockEntity()`, `getFluidState().isEmpty()`.
- `MIN_COVER_LEVEL` is a private constant with a comment explaining "level 1 equals vanilla's single layer; only deeper FTF snow creates a visible pit".

### 4.2 Settings and wiring

| Location | Change |
|---|---|
| `MiscellaneousSettings` | Field, optional codec entry (14th field), constructor, `copy()` |
| `Presets` (6 sites) | Pass `false` |
| `DecorateSnowFeature.Config` | `boolean coverPlants`, `optionalFieldOf("cover_plants", false)` |
| `PresetConfiguredFeatures` (~l.131) | Pass `miscellaneous.snowCoversSmallPlants` |
| `PresetPlacedFeatures` / `PresetBiomeModifierData` | No gating change (the feature already registers when smoothing is on; the option alone does nothing) |
| `MiscellaneousPage` | New `CycleButton<Boolean>` via `PresetWidgets.createToggle` |
| `FTFTranslationKeys`, `FTFLanguageProvider`, `en_us.json` | Label and tooltip |
| `FTFBlockTags` / `FTFBlockTagsProvider` / `snow_coverable.json` | Keep `SNOW_COVERABLE`; trim entries per §4.3 |
| `PresetValidationRunner` | Missing-field decode assertion (SV-13) |
| Remove | `MixinSnowAndFreezeFeature.java`, `SnowVegetation.java`, mixins.json entry |

### 4.3 Default `#freeterraforged:snow_coverable` (proposal)

```json
{
  "replace": false,
  "values": [
    { "id": "#minecraft:small_flowers", "required": false },
    "minecraft:short_grass",
    "minecraft:fern",
    "minecraft:pink_petals",
    { "id": "minecraft:wildflowers", "required": false }
  ]
}
```

`minecraft:wildflowers` is optional. It exists in vanilla from 1.21.5 and is registered under the `minecraft` namespace by VanillaBackport on 1.21.1 (E7). `#minecraft:small_flowers` covers all No Man's Land flowers and flowerbeds. `#minecraft:flowers` is intentionally not used (it includes leaves, tall flowers, propagules and other non-ground blocks).

### 4.4 Text (proposal)

- Label: "Snow Covers Small Plants"
- Tooltip: "Requires Smooth Layer Decorator. Where smoothed snow is deeper than a single layer, small flowers and grass are replaced by snow during generation (the plants are removed). Install a snow-layering mod such as Snow! Real Magic to keep plants inside snow instead."

## 5. Exclusions

- No change to vanilla `SnowAndFreezeFeature`, `Biome.shouldSnow`, weather snowfall or random ticks. Worldgen/weather divergence already exists for FTF's multi-layer snow (E3) and is documented, not solved.
- Enabling it in built-in presets is deferred to a follow-up PR. Supporting precedent: the default-on Natural Snow Decorator already removes small plants on eroded cold slopes (E4).
- Already-generated chunks are not modified.
- Adjusting SRM snowlogged blocks' layer count to match FTF depth (RESEARCH option F) is a follow-up.
- Tall/double plants, bushes, leaf litter, dry grass, saplings, mushrooms and modded multi-block plants are excluded.
- No surface-rule approach: surface rules run before vegetation exists (RESEARCH §4 C).
- No default-on behavior and no changes to existing erosion-pass semantics (E4).

## 6. Review traceability

| Reviewer finding | Resolution in this spec |
|---|---|
| Destructive replacement (Critical) | Opt-in (SV-01), FTF pits only (SV-03), removal stated in the tooltip (SV-12) |
| Opt-in preset toggle required | SV-01, SV-12 |
| Use `DecorateSnowFeature`, not `SnowAndFreezeFeature` | SV-07, §4.1 |
| SRM / modded flora | SV-07, SV-08 (ordering removes the mixin race), SV-05 (half-block/BE/fluid exclusions) |
| Weather desync | §5. Pre-existing for FTF smoothing; vanilla-depth columns (level ≤ 1) keep vanilla behavior. |
| Redundant `warmEnoughToRain` | SV-10 ordering. Climate is checked only for tagged plants in FTF-deepened columns. |
| `pos.below()` allocations | SV-10. No new allocations in FTF code; the remaining one is inside vanilla `canSurvive`. |
| Light during FEATURES | SV-04. Omitted with a code comment (inert at that stage). |
| Hardcoded third-party IDs | SV-09, §4.3 |

## 7. Verification plan

1. **Static:** build on both loaders, then `presetTestServer` on Fabric and NeoForge.
2. **Back-compat:** take a world and a saved preset created on upstream `6acf6b30`, load them with the patched jar, and confirm no codec errors and identical regeneration of fresh chunks with the option absent/off.
3. **Behavior (in-game, fresh worlds, fixed seed recorded):**
   - FTF + No Man's Land (+ VanillaBackport) with smoothing on: compare option off vs on at the same coordinates in snowy_plains/snowy_taiga/grove. Record coordinates and screenshots.
   - Confirm level-1 columns still show vanilla gaps. Tune `MIN_COVER_LEVEL` here if 2 looks wrong, and record the reason.
   - Option on, smoothing off: no change.
   - FTF + SRM defaults + option on: plants remain snowlogged (SV-08).
   - Vanilla-generator world with FTF installed: unaffected.
4. **Optional:** benchmark option on vs off (SV-10).

No claim of in-game validation in the PR until step 3 is done.

## 8. Delivery and communication

- Implement on the rebased `fixSnowVegetation` in `/private/tmp/ftf-pr323` as new commits on top of `bd4f2cb5`, or squash; decide at implementation.
- Publishing requires `git push --force-with-lease origin fixSnowVegetation`, which needs explicit user approval.
- Rewrite the PR description: the new approach, the opt-in default, the explicit "removes plants" statement, the SRM ordering rationale, the tag rationale (including VanillaBackport's `minecraft` namespace), and the verification actually performed.
- Reply to the review with the §6 traceability table. Frame the overstated performance and lighting findings neutrally, as "addressed by ordering/omission", rather than disputing them.

## 9. Readiness

The behavior, boundaries and persistence contract are clear enough to plan and implement. Remaining uncertainties are visual tuning (`MIN_COVER_LEVEL`, tag contents) and runtime confirmation of the SRM ordering. These are covered by §7 step 3.
