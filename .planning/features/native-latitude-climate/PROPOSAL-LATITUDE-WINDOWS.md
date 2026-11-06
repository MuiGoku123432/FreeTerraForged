# Proposal: latitude-defined climate windows

Status: **proposal / option for discussion.** Not accepted. Does not modify [SPEC.md](SPEC.md) or the shipped draft models (`ftf:latitude_v1_draft`, `ftf:latitude_v2_repeating_draft`). No code has been changed for this proposal.

Prepared 2026-10-22 from an investigation of the current custom-branch implementation.

## Motivation

The intended experience of native latitude climate is that the original FTF regional climate keeps mattering: latitude provides a planet-scale trend, while the original mod's regional bands, warps and patches still provide local variety. The current draft model does not deliver that at its defaults.

### How the current draft composes climate

`ClimateModule.apply` (`common/.../cell/climate/ClimateModule.java:148-152`) feeds raw regional noise through `LatitudeClimate.temperature/moisture`, then existing terrain shaping:

```
final = (1 - strength) * regional + strength * latitudeTarget     (LatitudeClimate.blend)
```

Defaults (`LatitudeSettings.DEFAULT`): temperature strength 0.75, moisture strength 0.5, pole distance 50,000 blocks.

Preserved unchanged: biome-region Voronoi/warp/IDs/edges, macro noise, continent/terrain, range/bias settings (also applied to the latitude target), altitude cooling, coastal boost, rain-shadow drying, seeds.

Reduced: regional temperature amplitude to 25 % and regional moisture amplitude to 50 % at defaults; 0 % at strength 1.

### How regional climate works today

- **Temperature** (`LegacyTemperature`): `sin(z / scale)^falloff`, mapped into min..max (+ offset bias), then two Perlin coordinate warps (amplitude 4×scale and 1×scale, in biome-cell units). It depends only on Z, so it is already a short-period, heavily warped latitude model. Defaults (scale 2, falloff 5, biome size 586) give a full warm–cold–warm period of ≈ 7,400 blocks.
- **Moisture**: simplex noise at ≈ 10 biome cells (≈ 5,900 blocks), clamp/stretch, falloff, range/bias, two warps. Non-directional patches.
- **Terrain shaping** (`modifyTemp`, `modifyMoisture`): altitude cooling; coastal moisture boost fading with elevation; elevation-based drying ("rain shadow" has no wind direction).
- Final `cell.temperature` / `cell.moisture` go through `CellSampler` into the multi-noise router; biome choice uses FTF's climate parameter bands.

### Observed consequence at defaults

Near the equator the latitude target is almost constant over typical exploration distances (target ≈ 0.97 at 5,000 blocks, ≈ 0.84 at 12,500 blocks from the equator). With temperature strength 0.75, the pre-cooling temperature near spawn is roughly 0.78–1.0 instead of the original 0.11–1.0. Cold biomes near the equator then appear essentially only via altitude cooling. Latitude bands are also straight east–west lines because the latitude field uses unwarped `originalZ`.

The two models are not conceptually opposed — both are Z-banded temperature at different scales. They conflict because a linear blend makes them compete for the same 0–1 output.

## Key observation: a fixed-width window equals the current blend

A window of constant width `W` sliding with latitude:

```
low  = (1 - W) * L
high = low + W
T    = low + W * R  =  (1 - W) * L + W * R
```

is algebraically the current blend with `W = 1 - strength` (ignoring clamp/float-order details). The default temperature strength 0.75 is a window only 0.25 wide. Therefore this proposal is only worth adopting if it changes at least one of the levers below.

## Proposal

At each geographic latitude fraction `u` (0 = equator, 1 = pole; repeating fold as in the v2 draft), the model defines a **window** `[lo(u), hi(u)]` within the climate envelope. The original regional noise, normalized to 0..1 *before* range mapping, chooses the position within that window:

```
T01 = lo(u) + (hi(u) - lo(u)) * R01
```

Then existing envelope/bias handling and existing terrain shaping run exactly once.

### Lever 1 — independent low/high curves expressed in biome bands

FTF's temperature parameter bands (`biome/Temperature.java`), converted to 0..1:

| Frozen | Cold | Temperate | Warm | Hot |
|---|---|---|---|---|
| < 0.275 | 0.275–0.425 | 0.425–0.6 | 0.6–0.775 | > 0.775 |

Humidity bands (`biome/Humidity.java`) in 0..1: 0.325 / 0.45 / 0.55 / 0.65 boundaries.

Define `lo(u)` and `hi(u)` as separate monotone/shape-preserving knot curves, meaning "which bands are possible at this latitude". Width then varies with latitude — something the blend cannot express.

Illustrative temperature windows (not tuned, not proposed defaults):

| u | Window | Bands reachable |
|---|---|---|
| 0 (equator) | 0.55–1.0 | temperate edge → hot |
| 0.35 (subtropics) | 0.45–0.95 | temperate → hot |
| 0.6 (midlatitude) | 0.30–0.80 | cold → hot (most variety) |
| 0.85 | 0.15–0.55 | frozen → temperate |
| 1 (pole) | 0.0–0.40 | frozen → cold |

Moisture could use the same structure with windows centered on the existing wet-equator / dry-subtropics / wetter-midlatitude / dry-pole knots.

### Lever 2 — regional distribution within the window

The default temperature falloff (5) concentrates regional values near the middle. Distribution of a pure sine over quintiles of 0..1 (warps ignored):

| Falloff | Quintile share (%) |
|---|---|
| 1 | 30 / 14 / 13 / 14 / 30 |
| 2 | 22 / 13 / 30 / 13 / 22 |
| 5 (default) | 14 / 10 / **52** / 10 / 14 |

With falloff 5, about half the world sits at the window midpoint and edges appear as narrow strips. The existing falloff slider therefore acquires a clear meaning — how evenly local variety fills the window — without any new control. Moisture's default falloff (1) already spreads widely.

### Lever 3 — full-envelope windows reproduce legacy

If `lo(u) = 0` and `hi(u) = 1` everywhere, the result should equal the existing regional climate. That gives a clean continuum from "legacy" to "strong latitude" and keeps every existing setting meaningful.

## Interactions

| Existing piece | Proposed role |
|---|---|
| Temperature/moisture min/max, offset bias | Outer envelope; windows are defined inside it |
| Distribution bias | Shapes `R01` within the window |
| Falloff | Spread of local variety within the window |
| Scale, seed offset, warps | Unchanged; control the local regional pattern |
| Altitude cooling | Once, after the window. Power curve cools hot equatorial windows less in absolute terms than polar ones |
| Coastal boost, rain-shadow drying | Once, after the moisture window |
| Biome regions, macro noise, edges | Unchanged |
| Equator Z, pole distance, repeating | Unchanged meaning; define `u` |

Scale relationship at defaults: one regional temperature period (≈ 7,400 blocks) against a 50,000-block equator→pole distance yields roughly seven local warm/cold oscillations per hemisphere, each confined to its latitude's window — the intended "global trend + local variety".

## Optional extension: meandering latitude

The latitude field currently uses unwarped geographic Z, producing straight band edges. Optionally displace geographic Z by an existing (or dedicated, seeded) large-scale warp so the planet-scale bands meander like the original mod's bands. The window composition already hides much of the straightness, so this may be unnecessary; it adds seed/determinism surface and should be a separate decision.

## Implementation considerations (not a plan)

- **New model identifier** required (e.g. `ftf:latitude_window_v1`), because the model ID fixes mathematical meaning. The existing models are drafts marked not generation-stable, so replacement before release is an alternative to coexistence; decide explicitly.
- **Raw regional value access:** the window needs `R01` before range mapping. Today `RangeValue.apply` is baked into the noise graph before the warps. Range mapping is value-only and commutes with coordinate warps, so a parallel graph without range mapping (or a restructured graph) is feasible. The disabled path must remain bit-identical to the current graph.
- **Cost:** two curve evaluations instead of one per axis; no new noise, no terrain query.
- **Curves:** use shape-preserving (monotone or knot-smoothstep) interpolation so `lo ≤ hi` holds everywhere and no overshoot occurs; validate `lo(u) ≤ hi(u)` and bounds in tests.
- **Preview/diagnostics:** `ClimateModule.diagnose` and the latitude command should report `u`, `lo`, `hi`, `R01`, windowed value and shaped value.
- **Settings/UI:** depends on the open questions below. Codec must keep "absent latitude object ⇒ legacy" semantics.

## Open questions

1. **Window authoring:** model-owned fixed `lo`/`hi` curves (possibly a few named presets such as Earth-like / Mild / Extreme) versus a simpler centre-plus-width UI (which, if width is constant, reduces to the current blend).
2. **Moisture scope:** apply windows to moisture in the first version, or temperature only initially?
3. **Meandering latitude:** warp geographic Z or keep straight bands?
4. **Envelope semantics:** keep existing min/max as the outer envelope, or let windows supersede them while latitude is enabled?
5. **Model lifecycle:** replace the draft models or ship alongside them?

## Suggested next step

Extend `.planning/research/native-latitude-climate/probe.py` to compute, per `u`, the share of area falling in each temperature and humidity band for candidate window curves, using actual falloff values and the preset's envelope. Use those numbers to choose curves before any spec change.
