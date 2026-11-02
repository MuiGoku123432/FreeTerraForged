# Rolling Highlands RH6 research: lakes, lake junctions, elevated streams, ranges

Date: 2026-10-29. Input: user feedback on RH5 (`1.0.0015-rh5`) from the server world (seed −6240223350076425471,
Test-America-v3-ROLLING, model 5), screenshots at (3948, 9840), (4675, 9884), (4689, 9704), (4819, 9817),
(4903, 10222), (4836, 10197), (4924, 10456), plus "mountains look poky, not like a range".

Method: the test fixture reproduces the server world at the same coordinates (heights within a block of the
screenshots), so cells, plans and fields were probed directly. RH4 numbers come from `ee892191` run with the
same probes. FULL-chunk benchmark worlds (RH4, RH5 and five RH5 variants with one change reverted) confirmed
block-level results. No product code was changed.

## 1. Lakes: shallow sheets on flats

| | RH4 | RH5 |
|---|---|---|
| lakes on the user continent | 25 | 39 |
| total lake area | 1.5 km² | 3.4 km² |
| typical mean depth (largest 10) | 3.2–4.9 | 4.4–5.0 (tarns 9–13) |
| share of lake area under 3 blocks deep | 35–58% | 29–54% (dam lakes 35–54%) |
| water share at screenshot sites 3/4/6 | 4 / 0 / 16% | 27 / 38 / 77% |

Water share at the screenshot sites when one RH5 change is reverted (176×176 blocks):

| site | as shipped | no flat friction (D1) | no dam lakes | no shoreline change (S) | no tarns |
|---|---|---|---|---|---|
| (4689, 9704) | 27.4% | 1.0% | 19.4% | 20.8% | 27.4% |
| (4819, 9817) | 38.1% | 0.0% | 20.5% | 30.4% | 38.1% |
| (4870, 10210) | 76.8% | 16.2% | 23.2% | 74.0% | 76.8% |

Mechanism:
1. **Dam lakes need a catchment that matches the flood.** `damLakes` accepts a site only if the flooded nodes stay
   inside the dam's own drainage subtree. RH4 drained flats as a radial fan of one-node-wide strips, so a flood on
   a flat always crossed into a neighbouring strip and was rejected. RH5's flat friction (D1) gathers a whole flat
   into one trunk catchment, so raising the water by the preset lake depth (10 × 0.6–1.4, min 4) floods the entire
   flat up to `MAX_LAKE_NODES` = 600 nodes (≈ 9.8 km² at a 128-block grid). Nothing in the acceptance test
   measures depth, confinement or shape.
2. **Lake 20 (the screenshots)** is one dam lake about 260 × 870 blocks, surface y 84.2, on a valley flat at
   y 85–88. Mean depth 4.4, 35% under 3 blocks.
3. **RH5 S adds area and depth noise.** Beside lakes the floor drops to the lake level and the shoreline may
   advance up to 10 depth units, so water fills whatever the unfloored ground (land − incision) leaves below the
   surface: +25–100% area per lake. The lake bed follows that unfloored ground, which exposes the stream-power
   trough under the lake (bed y 75 under a 84.2 surface while the planned depth is 1–5): a long dark trench through
   a shallow sheet (screenshot 4). The nearest channel there is 45 blocks away; the trench is not a river.

## 2. Banding where rivers meet lakes

Block cross-sections at lake 20 and at every inlet found on the continent:
1. **1-block step at every inlet.** Channels and the node water grid use the lake's spill (fill) level; the lake
   surface is spill − `FREEBOARD` (1). Backwater channels above lakes therefore sit exactly one block above the
   lake: lake 1 98.7 / river 99.7, lake 3 121.7 / 122.7, lake 20 84.2 / tributary 85.2.
2. **Shore-parallel tributary (screenshot 6).** A tributary at the spill level (85.2) runs about 300 blocks along
   lake 20's west shore, separated by a 4–5-block dry strip at y 85–86, and finally meets the lake sideways with a
   1-block drop. Its axis samples are just outside the planned lake (planned depth −1…−3), so they are never
   flagged as flooded; channels are only absorbed where the planned depth is above 0.
3. **Flat rims.** `floored` (water floor + 1.5) and `shapeLake`'s rim (ground held at water + 1 within 12 depth
   units) make a flat shelf 1–1.5 blocks above the water around every lake and along channels. Surface rules paint
   the 1-block step at its edge as stone/gravel: the stone stripes lining shores and channels in screenshots 5–7.
4. **Lake bed trench** (§1.3) reads as a band of deep water through the shallow lake.

## 3. Elevated streams and canal streams

| | RH4 | RH5 |
|---|---|---|
| channel columns with dry land > 2 blocks below the water within 48 blocks | 23.1% | 17.3% |
| … > 5 blocks below | 3.9% | 2.3% |
| land lifted > 2 blocks by the base-level floor | 50.4% | 47.8% |
| land lifted > 8 blocks | 23.1% | 21.6% |
| … of land > 200 blocks from any channel, lifted > 2 | 48.6% | 47.5% |
| dead-flat ground (9×9 samples at 4-block spacing on one level), screenshot sites | 27–52% | 3–47% |

Both pre-date RH5; RH5's narrower creeks make them more visible.

1. **The base-level floor is a global fill, not a riverside guard.** `floored()` lifts every column to
   `waterFloor + 1.5`, where `waterFloor` interpolates the per-node water level. For a land node that is
   `max(incised, water[parent])`, i.e. its own incised planning height. So the floor is the smooth 128-block
   interpolant of the incised planning surface, and every legacy-land dip below that interpolant is filled: half
   of all land, mostly far from water. Effects:
   - dead-flat plateaus and terraces (y 88 everywhere around screenshots 2 and 7);
   - square "pillow" shading at 128-block spacing, because the smoothstep-bilinear interpolant is flat at every
     node (visible on the range flanks and in every zoom render);
   - streams cut 1–2 blocks into those flats look like canals (screenshots 2, 7).
2. **Channels on valley sides.** Worst case (3004, 10452): a creek at water 146.5; the ground falls to 122–127
   within 60 blocks to the south-west, where the base level is 122. The plan routes on 128-block jittered nodes and
   draws a Hermite/meander line between them, sampling only node points; the line crosses ground the plan never
   sampled. The water profile is interpolated between nodes, so the creek holds a hillside level and the valley
   carving plus support cone (`water + 1 − 0.5·d`) cut a shelf for it along the slope.
3. RH5's flat friction routes flats by noise, not by the (floor-flattened) ground, which adds a few more of these
   canal reaches but is not the main cause.

## 4. Mountain ranges look "poky"

Range at (4704, 9444), crest traced for 7.7 km:
- The **range layer itself is smooth**: added height along the crest averages 78 (std 19), 0.13 peaks/km with
  > 15 blocks of prominence.
- The **rendered crest swings between 94 and 215** along the same line. Under the range cores the legacy land
  is 33% plateau-hills, 32% hills and 28% `mountains_2`. `mountains_2` is the FTF worley-edge mountain populator
  (≈ 540-block cells at horizontal scale 1.5, vertical scale 1.65 in this preset), which makes isolated pyramidal
  peaks with radial gullies.
- Legacy local relief (max−min on a 384-block ring) is 53 inside range cores and 76 outside, against a range
  that adds about 120 spread over ±900 blocks (≈ 0.13 blocks per block). The legacy slopes are 2–4 times steeper,
  so the eye reads legacy peaks lifted by a broad swell, not a ridge line.
- Cross-section: crest 145, but 300–500 blocks to one side the ground is at 53–59. Rivers route around the range,
  and stream-power incision cuts deep valleys along its foot.
- Only crest > 0.5 columns are reclassified `MOUNTAIN_CHAIN`, so stone/snow biomes appear on isolated tops.

What reads as a range (geomorphology, e.g. alpine and Appalachian-type fold ranges): a continuous crest line
whose height varies slowly (peaks and cols, few deep gaps); asymmetric flanks; spur ridges and valleys roughly
perpendicular to the strike; peaks elongated along the strike; foothills as lower parallel ridges.

## 5. Speckled ice in `bloomingnature:cold_river` (screenshot 1)

`cold_river` has base temperature 0.175, above the 0.15 cut-off used to classify it as non-freezing. Vanilla
freezes water by height-adjusted temperature: `t − (noise·8 + y − 80) · 0.05/40`, with noise ±1. The water there
is at y 101–107, so its temperature is 0.145 ± 0.01, on the freezing line, which gives speckle. Regions Unexplored
rivers (0.4–0.6) are unaffected.

## 6. Previously known, still open

- Cave carvers open pits beside rivers that the river gasket does not seal (site C, 4 columns, both loaders).
- One channel crossing at a coastal mouth with ranges on.
