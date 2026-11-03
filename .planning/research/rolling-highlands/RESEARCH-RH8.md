# Rolling Highlands RH8 research: ranges with parallel ridges

Input: user feedback on RH7 (2026-11-02): ranges read as a single mountain line; wanted "a little more depth",
clarified as several parallel ridges across the range.

## 1. Current shape (code reading, model 7)

`HighlandLand.rangeFrame` / `range`, Range Height H, length L:
- **One crest per range.** The crest is the 0.5 iso-line of `rangeField` (noise stretched 2.5× along a slowly turning
  strike, lightly warped). Distance across is first order: `|n − .5| / |∇n|`, normalised by the half width
  `clamp(.14 L, 420, 1500)` (860 blocks at L 6144) times a ±25% flank asymmetry.
- **Cross-section.** `0.3 H` foothill apron over the whole half width (with `parallel` noise ripples at ±30% of the
  apron, wavelength 360) plus `0.7 H` main ridge falling almost linearly to 0.75 of the half width. One spine,
  skirts on both sides.
- **Downstream consumers of the shape:** `HighlandRangeRelief.build` (coarse mask from `rangeCore`, envelope from
  `range`), `RollingHighlandsContinent.dissection` (range minus carved), drainage planning (`surface.height`
  includes ranges; `rangeCore` boosts steepness ×(1 + 2·core)), incision fade `(1 − rangeCore)`, legacy damp and
  `cell.highlandRange` (biome bands and surfaces), `HighlandMountainProbe` (crest at core ≥ .97, mid-flank transect at
  0.5 half width).

So valleys between peaks exist (RH7 D/G), but there is never a second crest across the strike; the parallel ripples
are a few tens of blocks and get carved away.

## 2. What real multi-ridge ranges look like (qualitative targets)

- **Belts of sub-ranges.** Large ranges are usually several roughly parallel ridges separated by valleys that run
  along the strike (longitudinal valleys, intermontane parks/basins): e.g. Rocky Mountain front ranges, the Alps'
  parallel chains, the Andean cordilleras, the Appalachian Valley and Ridge in its extreme form.
- **En echelon.** Sub-ridges do not run the full length side by side; they start, end and overlap in steps, so the
  belt's cross-section changes along the strike (sometimes one ridge, sometimes three).
- **Unequal heights.** One ridge is usually highest (often not the middle one), and which one changes along the belt.
- **Trellis drainage.** Streams run along the longitudinal valleys and leave the belt through gaps (water gaps) in the
  outer ridges; transverse valleys still dissect each ridge's flanks.
- **Intermontane basins.** Where a longitudinal valley is closed at both ends it holds a basin or lake.

## 3. Options

| Option | Result | Cost / risk |
|---|---|---|
| A. Sub-crests at signed offsets across the existing crest line | Parallel ridges that bend with the range; per-ridge presence and height noise give en echelon and changing highest ridge | Small, local to `HighlandLand`; first-order distance error grows with the wider belt |
| B. Several iso-levels of the same field (n = .5 ± kδ) | Ridges bunch and spread naturally with the field gradient | Spacing explodes where the field is flat; hard to clamp; belts of neighbouring ranges merge |
| C. Second independent range field ("front range") | Extra ranges | Crosses the main range instead of running parallel; not a belt |

Chosen: **A** (user, 2026-11-02), as a setting (Range Ridges, default 1 = model 7 unchanged) with a belt widened
~1.5–2× for 2+ ridges and the relief cost gate relaxed to a measured value.

## 4. Expected interactions

- **Relief cost** scales with belt area (coarse mask from `rangeCore`): at 1.9× width, expect up to ~1.9× the
  current 538–545 ms relief build. Current gate `relief ≤ plan` (~586 ms) will fail; to be re-baselined.
- **Drainage planning** sees the uncarved shape, so longitudinal valleys between ridges become planned channels;
  closed troughs become pits that the planner fills (basins / lakes via `HighlandBasins`). Outer-ridge passes are
  needed for those rivers to exit; the existing pass noise applies per ridge.
- **Incision fade** `(1 − rangeCore)` must keep core ≈ 1 across the inner belt, so longitudinal valleys are carved by
  the relief solver, not by plan incision stacked on the relief (the RH7 M flat-trough defect).
- **Distance accuracy:** `|n − .5| / |∇n|` is exact only near the crest; at 1.5–2× the half width, ridge offsets may
  drift where the field curves. Measured by ridge-spacing variation in the probe.
- **Probe metrics** assume a single crest (`core ≥ .97`, mid-flank at 0.5 half width) and need a multi-ridge
  cross-section metric.
