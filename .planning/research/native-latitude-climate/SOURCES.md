# Source register and evidence limits

Inspected 2026-10-22. Commit links are immutable evidence anchors; project/loader pages are live documentation and should be rechecked before implementation. Local source links are relative to this research directory. Findings are summarized in [RESEARCH.md](RESEARCH.md).

Additional primary references for the deeper numerical and climate-model study are in [ALGORITHM-SOURCES.md](ALGORITHM-SOURCES.md), with independent results documented in [EXPERIMENTS.md](EXPERIMENTS.md).

## FTF source

All FTF files below were read from the local custom checkout at `43244515ebf3d3aa9867aba97d8ec360faeabdae`. [Pinned repository tree](https://github.com/MuiGoku123432/FreeTerraForged/tree/43244515ebf3d3aa9867aba97d8ec360faeabdae). The recorded upstream revision is `22c2801b4772dbde04044ec80551e5bb89c845a1`. These are different branches; custom APIs are not automatically upstream APIs.

| ID | Source | Evidence |
| --- | --- | --- |
| F1 | [LegacyTemperature.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/noise/module/LegacyTemperature.java) | Z-axis sine/power legacy temperature; existing codec |
| F2 | [ClimateModule.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/climate/ClimateModule.java) | Regional graphs, cell output, raw API, global shaping settings, unused original coordinate arguments |
| F3 | [Climate.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/climate/Climate.java) and [TemperatureShaping.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/climate/TemperatureShaping.java) | Edge resampling and explicit float elevation helper |
| F4 | [CellSampler.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/densityfunction/CellSampler.java), [PresetNoiseRouterData.java](../../../common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/PresetNoiseRouterData.java) | Cell-based temperature/moisture density functions and cache ownership |
| F5 | [RenderMode.java](../../../common/src/main/java/etcodehome/freeterraforged/client/gui/screen/presetconfig/RenderMode.java), [PreviewDetails.java](../../../common/src/main/java/etcodehome/freeterraforged/client/gui/screen/presetconfig/PreviewDetails.java), [BiomePreviewResolver.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/BiomePreviewResolver.java) | Preview climate consumers |
| F6 | [ClimateSettings.java](../../../common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/settings/ClimateSettings.java), [Preset.java](../../../common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/settings/Preset.java), [PreviewRequestKeyFactory.java](../../../common/src/main/java/etcodehome/freeterraforged/client/gui/screen/presetconfig/PreviewRequestKeyFactory.java) | Codec, copies, preset registration and preview identity |
| F7 | [FTF license](../../../LICENSE) | MIT license in inspected checkout |
| F8 | [MixinClimateSampler.java](../../../common/src/main/java/etcodehome/freeterraforged/mixin/MixinClimateSampler.java), [UndergroundBiomeClimatePolicy.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeClimatePolicy.java), [WorldgenPlans.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans.java) | Runtime query ownership, cave depth protection and ordered sampler decorators |
| F9 | [ClimateSettingsPage.java](../../../common/src/main/java/etcodehome/freeterraforged/client/gui/screen/presetconfig/ClimateSettingsPage.java), [GeneratorContext.java](../../../common/src/main/java/etcodehome/freeterraforged/world/worldgen/GeneratorContext.java) | Existing controls and generator context integration sites |

[FTF PR #301](https://github.com/ETcodehome/FreeTerraForged/pull/301), inspected open/unmerged, provides the raw-climate helpers at `2228f6c57f7457a4a0d516f9e5983627c3087ffd`. Its author's supplied-input checks are not runtime evidence from this research. [PR #321](https://github.com/ETcodehome/FreeTerraForged/pull/321) and [its predecessor #291](https://github.com/ETcodehome/FreeTerraForged/pull/291) motivated the discussion but address biome replacement, not implementation of latitude climate.

## TerraLatitude

Pinned revision: `04a30c116f239a9b50f7f1df318a6f63091e7422`. The project is the user's existing addon. Its code and documents provide evidence about that adapter, not a guarantee about every NT version.

| ID | Source | Evidence |
| --- | --- | --- |
| T1 | [README](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/README.md), [climate design](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/docs/CLIMATE-DESIGN.md) | Supported build, order bug, world profile, preview limitation, reported runtime versus offline evidence |
| T2 | [NaturalClimateAdapter](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/NaturalClimateAdapter.java), [NtSettings](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/NtSettings.java) | Paired calculator calls, settings capture, mode constraints |
| T3 | [ClimateComposition](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/ClimateComposition.java), [WorldProfiles](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/WorldProfiles.java) | Composition order, no-op paths, saved ownership |
| T4 | [IntegratedSamplerMixin](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/mixin/IntegratedSamplerMixin.java), [temperature redirects](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/mixin/NtTemperatureSettingsMixin.java), [humidity redirects](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/src/main/java/dev/fnf/terralatitude/mixin/NtHumiditySettingsMixin.java) | Injector order and version-sensitive static configuration access |
| T5 | [License](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/LICENSE) | MIT license; does not grant rights to NT itself |
| T6 | [Altitude arithmetic evidence](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/docs/evidence/altitude-arithmetic-2026-10-14.json) | Synthetic float/double comparison; not measured biome distribution |
| T7 | [Integration specification](https://github.com/MuiGoku123432/TerraLatitude/blob/04a30c116f239a9b50f7f1df318a6f63091e7422/docs/INTEGRATION-SPEC.md) | Future integration intent, not completed behavior |

Also inspected: [FreeTerraForgedxNaturalTemperature README](https://github.com/Chunchunmaru3D/FreeTerraForgedxNaturalTemperature/blob/master/README.md). It describes a narrowly pinned compatibility combination. This live branch is background only and is not used as proof of compatibility with the current custom checkout.

## NT and loader primary sources

| ID | Source | Evidence and limits |
| --- | --- | --- |
| N1 | [Natural Temperature official project](https://www.curseforge.com/minecraft/mc-mods/natural-temperature) | Author-described settings, scope, release listings and algorithm caveats; live page, not a reproduced runtime test |
| N2 | [NT repository](https://github.com/Caesius-Leo/Natural-Temperature-Mod/tree/11389193b1c502881fed9d2e6702c0c0fc77b84d), [custom license](https://github.com/Caesius-Leo/Natural-Temperature-Mod/blob/11389193b1c502881fed9d2e6702c0c0fc77b84d/License) | Inspected public tree and use restrictions; no claim that an API cannot be provided privately or later |
| L1 | [NeoForge 1.21.1 mod files](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/#dependency-configurations) | Optional dependency type validates compatibility when installed |
| L2 | [Fabric metadata specification](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec) | Distinction between depends, recommends, suggests and breaks |

## Work performed and not performed

Performed: read-only local code tracing; pinned GitHub source/document review; official web documentation/license review; comparison of architecture and compatibility tradeoffs; independent mathematical probe; research document validation.

Not performed: production code changes, new mod builds, dependency binary inspection/decompilation, game startup, terrain/biome generation, save migration, performance measurement, exact NT parity testing, or PR publication. Earlier builds/reviews in this session do not validate this proposed feature.

Gortex MCP was attempted but returned `repo_not_tracked` for this checkout. Local source inspection and these repository documents supplied the fallback evidence and durable notes; no graph was registered or changed.
