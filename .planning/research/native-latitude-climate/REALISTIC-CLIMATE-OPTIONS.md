# Realistic native temperature and moisture options

Research date: 2026-10-23. Inspected code: `dd0b1edabbf5aec89ef41dacb409ecef3eca4de6`.
Status: research and recommendations for discussion. The installed build, accepted specification and saved models are unchanged.

## Recommendation

Develop a layered native climate model: repeating latitude establishes broad tendencies, existing FTF regional fields provide local variation, and terrain modifies the result. Evaluate temperature and moisture jointly. Latitude windows are a promising composition mechanism, but their presence alone does not establish realism.

The next comparison should test paired temperature/moisture windows against a weaker version of the current blends, alongside a separately evaluated height-based temperature subtraction. The largest later moisture improvement would be bounded ocean-exposure and windward/leeward terrain effects. Those need more infrastructure than a latitude curve and should remain a separate decision.

This preserves FTF's regional generators, biome-region shapes, configurable envelopes, terrain and preview integration. Greater realism may justify revising its height-only moisture approximations in a new model; preserving the overall design does not require retaining those formulas forever.

## What realistic means here

Aim for convincing long-term climate geography: warmer low latitudes, cooler high latitudes and mountains, broad circulation-related wet/dry tendencies, local variety, and eventually wetter windward terrain and drier sheltered interiors. Minecraft's temperature and humidity coordinates are biome-selection parameters, not Celsius, relative humidity or rainfall measurements. Keep any research-only physical/proxy quantities separate from the final game coordinates.

The user's repeating warm–polar–warm pattern remains a gameplay convention. Real-world latitude provides guidance within each cycle; an endlessly repeated plane is not an Earth simulation. A static world-generation climate also cannot reproduce seasonal monsoons, changing weather, snow accumulation or soil-water storage merely by changing these two biome coordinates.

## Scientific findings and their implications

### Broad latitude patterns are supported but not sufficient

NOAA describes pressure belts near the equator, subtropics, subpolar regions and poles, and explains how land and water break their uniformity. This supports broad wet/dry tendencies rather than identical climates along every latitude. The particular FTF knot values and band widths remain tuning choices. [NOAA circulation](https://www.noaa.gov/jetstream/global/global-atmospheric-circulations)

Temperature and moisture should share geographic placement but retain separate regional variation and strengths. A hot region must not automatically become either desert or rainforest. Their joint distribution matters more than each channel's histogram alone.

### Elevation cooling needs an explicit scale

NWS distinguishes environmental temperature gradients from the cooling of a rising air parcel; moist parcel lapse rates are not constant. A real atmospheric lapse rate cannot be copied directly into dimensionless biome coordinates. [NWS glossary](https://forecast.weather.gov/glossary.php?word=lapse+rate)

For FTF, compare `T_out = clamp(T_base - k * max(0, Y_surface - Y_reference), 0, 1)`, with k explicitly measured in climate units per block. This is a lapse-inspired game approximation. It needs a chosen reference level, height scale and biome calibration; it is not a physical temperature simulation. A fixed block scale would make the same elevation correction independent of an otherwise unused world-height ceiling. A relative-height scale is also defensible for compressed worlds, but has different semantics and must be explicit.

### Mountains affect precipitation by exposure as well as height

CHELSA combines background climate with wind fields, windward/leeward exposure, valley effects and bias correction. It is evidence for relevant geographic predictors, not a ready-to-use generator for an arbitrary Minecraft world. [Karger et al. 2017](https://www.nature.com/articles/sdata2017122)

Smith–Barstad's orographic model includes airflow response, transported condensed water and evaporation, using Fourier methods. It shows why placing all precipitation immediately on an uphill slope is incomplete and scale-dependent. Adopting that model would require atmospheric inputs and substantial handling of regional boundaries. [Smith and Barstad 2004](https://training.weather.gov/wdtd/courses/woc/winter/physiogeographic/cool-season-orographic/story_content/external_files/Smith%20and%20Barstad%202004.pdf)

The useful first approximation is a bounded exposure/barrier effect informed by available moisture. A full precipitation solver is not justified for the next iteration.

### Near the ocean does not always mean wet

NASA's Andes account describes wet windward slopes, dry leeward regions and a cold coastal current associated with aridity. Consequently, distance to ocean alone cannot reproduce these patterns. [NASA Bolivian Andes](https://science.nasa.gov/earth/earth-observatory/bolivian-andes-8830/)

Treat ocean proximity as potential moisture supply, not a universal wetness bonus. An initial distance-only approximation is possible, but an upwind ocean-exposure measure is more useful for distinguishing geography. Ocean currents and seasonal circulation would still be absent.

### Rainfall and ecological wetness are different quantities

FAO's reference evapotranspiration method uses temperature, radiation, humidity and wind. Its input requirements show why a temperature-only dryness multiplier is not a physical evaporation calculation. [FAO Paper 56 chapter 2](https://www.fao.org/4/x0490e/x0490e06.htm)

Keep the current regional moisture field as a calibrated biome-suitability signal for the near-term model. If later introducing a rainfall/supply proxy, define a separate conversion into biome humidity, potentially including a bounded evaporative-demand proxy. Do not label the result measured precipitation, relative humidity or soil moisture. Cold low-precipitation areas should be evaluated jointly with temperature; forcing them through the same ecological interpretation as hot deserts is inappropriate. Wetlands additionally involve drainage and water tables, which a latitude window cannot determine.

## Findings in the current code

Paths below are under `common/src/main/java/etcodehome/freeterraforged/` at the inspected commit.

| Component | Current behavior | Consequence |
| --- | --- | --- |
| `world/worldgen/noise/module/LegacyTemperature.java` and `cell/climate/ClimateModule.java` | Periodic Z-based source with falloff, range/bias and coordinate warps | Preserve this regional field as a source of local structure; the full warped field is not Z-only |
| `world/worldgen/cell/climate/LatitudeClimate.java` | Linear blend of regional and latitude targets | Strength 0.75 leaves 25% of pre-shaping regional temperature amplitude at fixed latitude; moisture strength 0.5 leaves 50% |
| `world/worldgen/cell/climate/TemperatureShaping.java` | `T^(2^(height*strength))` | T=1 remains exactly 1 at every elevation; values near 1 cool only slightly |
| `world/worldgen/cell/heightmap/Levels.java` | Inland elevation divided by world height minus sea level | The same surface height cools differently when the configured world-height ceiling changes |
| `ClimateModule.modifyMoisture` | Low-elevation boost, then elevation power drying | No ocean-distance or prevailing-wind input; equal regional moisture/elevation gives equal output on either side of a ridge |
| `world/worldgen/cell/Cell.java` and continent generators | `continentDistance` comes from continent-cell geometry | It is not an established distance in blocks to the final ocean shoreline |
| `world/worldgen/cell/heightmap/Heightmap.java` | Terrain/rivers are evaluated before climate, with an existing terrain-only entry point | A possible starting point for a later coarse geographic sampler; ownership, erosion stage and recursion still need an audit |

The current moisture power curve also fixes M=1 at 1. Elevation drying therefore does not uniformly dry every high location. These are mathematical characteristics of the current formulas, not evidence of particular in-game biome percentages.

## Candidate comparison

| Candidate | Benefit | Limitation | Relative implementation scope |
| --- | --- | --- | --- |
| Weaker current blends | More existing regional variation; already integrated | Latitude and regional amplitude still compete | Small; indispensable comparison baseline |
| Variable latitude windows for both axes | Independently shape local temperature/moisture ranges by latitude | Windows need calibration after mapping and terrain shaping; no ocean or wind knowledge | Moderate; raw pre-envelope values and exact control semantics |
| Reposition/rescale original temperature bands | Close to inherited temperature structure | Enlarges climate features; does not supply a corresponding moisture model | Moderate; an optional comparator |
| Additive temperature anomaly, with clipping | Direct control of regional anomaly amplitude | Endpoint clipping concentrates values and flattens variation | Moderate; compare only with saturation measurements |
| Height-based temperature subtraction | Every positive starting temperature can cool; explicit height interpretation | Lower-bound saturation and block-scale calibration | Moderate; new saved behavior and snow/biome validation |
| Ocean distance | Better coastal/inland distinction than elevation alone | Isotropic; cannot distinguish wet windward and dry coastal regions | Substantial; authoritative ocean mask and neighborhood sampling |
| Upwind ocean exposure plus mountain barriers | Stronger geographic explanation for moisture patterns | Assumed winds, finite reach and undersampled terrain can distort results | Substantial; coarse field, bounded support and shared preview/runtime cache |
| Moisture transport or full atmospheric solver | More explicit processes and budgets | Boundary conditions, calibration, cost and false precision | Large; defer |

Energy-balance models can include radiation, albedo feedback and meridional heat transport. They are useful research references, but their extra physical parameters do not automatically improve a normalized game curve. The earlier algorithm study already demonstrates a restricted normalization case where additional parameters cancel. [Climlab EBM documentation](https://climlab.readthedocs.io/en/latest/api/climlab.model.ebm.html), [existing analysis](ALGORITHMS.md)

## How a window candidate should work

For each axis, retain its own regional source, falloff, seed and coordinate warps. Apply distribution bias to that source, then map its position into a latitude-dependent window, then apply the existing min/max and offset rules once. Follow with the selected terrain effects. Do not apply distribution bias a second time after the window.

Retain independent strengths. One proposed interpretation is to expand each target window toward the unrestricted [0,1] window as strength decreases: `lo_effective = strength*lo`, `hi_effective = 1-strength + strength*hi`. At zero strength, bypass the candidate path and execute the legacy arithmetic exactly. At full strength, regional variation remains wherever the window has positive width. This differs from the current full-strength blend and needs a new saved model and accurate tooltips.

Use shared knots and the same interpolation weight for both window endpoints. Ordered endpoints at every knot then stay ordered between knots. Separate unconstrained interpolators do not provide that guarantee. For repeating bands, endpoint derivatives should be zero to avoid a slope discontinuity where the pattern reverses.

Windows should describe broad allowed climate tendencies, not force particular biome IDs. Keep biome selection and replacement policies downstream. Existing range/bias settings can move the windows across biome thresholds, so authoring tables must show mapped values as well as raw window coordinates.

## Reproducible mathematical checks

Run `python3 .planning/research/native-latitude-climate/realism_probe.py`. The adjacent [results](realism-probe-results.json) record source hashes, the inspected commit and the probe hash. These are independent Python double-precision calculations, not execution of Java world generation or sampling of actual biome maps.

Eight analytical checks passed. The most useful findings are:

- The proposal's equatorial window 0.55–1 maps to approximately **0.60258–1** with the actual default temperature envelope. Its polar window 0–0.40 maps to **0.1144–0.46944**, reaching the temperate climate band before altitude effects. Its illustrative biome-band labels therefore cannot be treated as guarantees.
- At normalized height 0.5 and current cooling strength 2.2, a temperature of **0.99 becomes about 0.97869**, while **1 stays 1**. A subtractive comparator matched at input 0.5 instead gives about 0.71632 and 0.72632. This illustrates different curve behavior; the matching correction is not a proposed default or scientific calibration. Subtraction also saturates low values at zero, which needs evaluation.
- At sea level, current regional moisture **0.2 becomes 0.36** with the default boost, without any ocean-distance input. This crosses the first humidity-band boundary at 0.325.
- A height 128 blocks above sea level 64 gives normalized elevation **0.5** for world height 320 but **0.2222** for world height 640. The same landscape can therefore get different cooling because of the preset ceiling.
- Pure signed-power sine samples reproduce the qualitative concentration toward central quintiles at falloff 5. The fixture omits warps, terrain and biome selection, so it must not be called world-area coverage.

No mathematical fixture here ranks which map will look best. That requires paired actual FTF samples.

## Native integration requirements

Keep all candidate state immutable and owned by the world/preset context. The same profile and sampling contract must feed diagnostics, preview and generation. Preserve legacy and existing v1/v2 saved profiles; introduce new semantics under a new identifier. Do not reuse the old altitude or moisture slider numbers with silently changed meanings.

For future terrain-dependent moisture, use deterministic, globally aligned coarse samples with a bounded neighborhood and appropriate surrounding samples at tile boundaries. Cache by seed, dimension, relevant terrain settings and climate model. Chunk discovery order and cache eviction must not affect values. Do not query full neighboring climate cells from inside climate evaluation; that can create recursive generation dependencies. Decide explicitly whether the geographic field uses pre-erosion or filtered terrain, and use the same stage in preview and runtime.

A repeating latitude fraction loses direction: it does not identify which side of an equator/pole the query occupies. A later prevailing-wind model needs the signed phase as well as the folded fraction, with smooth behavior at cycle boundaries. Otherwise repeated temperature bands can coexist with discontinuous rain-shadow directions.

Do not stack a new directional rain-shadow effect on top of old elevation drying by default. In a new model, either replace the old approximation or retain it as an explicit additional adjustment with demonstrated purpose. The same applies to combining actual ocean exposure with the existing low-elevation moisture boost.

Terrain temperature, selected-biome temperature and snow placement are related but distinct. Validate real snow behavior, including installed biome/weather mods; a cold climate coordinate alone is not proof of a specific snow outcome.

## Recommended next experiment

Use the same terrain seeds and presets for: legacy/no latitude; current default blends; weaker paired blends; temperature windows plus current moisture blend; and paired windows. Compare the cooling variants separately so a latitude change is not credited for an altitude correction.

Include short and long cycles (for example 5,000 and 50,000 blocks), shifted equators, repeated poles, dry/wet regional settings, lowlands and mountains. Report joint temperature/humidity occupancy, selected biome coverage, connected patch sizes, east–west and north–south travel distances, endpoint saturation and altitude response. Show maps at local exploration scale as well as across complete cycles.

For a later geography experiment, include an ocean beside flat land, an inland plain at the same height, a ridge with mirrored winds, multiple ridges, narrow islands and valleys. Compare outputs across tile boundaries and under reordered queries. Benchmark preview latency and generation cost against the unchanged baseline before choosing a sampling resolution.

Keep the already working implementation available. The immediate decision is whether windows improve paired climate variety and whether the cooling model should change. Actual ocean and directional mountain effects would be a separately scoped extension of the existing moisture system, not a prerequisite for testing those decisions.
