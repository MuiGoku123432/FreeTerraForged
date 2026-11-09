# Rolling Highlands range layout shears with distance from the world origin

Status: confirmed by probe; not fixed (deferred).
Date: 2026-11-06
Severity: medium. Range layout is not translation-invariant, and the defect affects every world with ranges, at any ridge count.
Probe: `common/src/test/java/etcodehome/freeterraforged/world/worldgen/highlands/HighlandRangeFieldProbe.java`
(`./gradlew :common:probeTest --tests '*HighlandRangeFieldProbe'`, writes `common/build/range-field-probe.txt`, runs in about 15 s).

## 1. Problem

`HighlandLand.rangeField` rotates world coordinates by a strike angle that changes with position:

```java
double strike = Math.PI * HighlandMath.noise(seed + 6091, x, z, 5 * l);
double along = (wx * cos + wz * sin) / RANGE_ELONGATION, across = (-wx * sin + wz * cos) / (...);
```

The derivative of `along` and `across` picks up a term proportional to distance from the origin times the gradient
of `strike`. Far from (0,0), that term dominates. The field's effective scale shrinks, the 2.5x elongation along
the strike is lost, and crest direction stops following the strike. The comment on `oriented()` in the same file
warns about exactly this ("rotating world coordinates by a varying angle would shear the noise far from the
origin"), but `rangeField` doesn't use the fixed-direction blend that `oriented()` uses.

## 2. Evidence (ROCKIES settings, raw range field, 16 km windows, seeds 0 and 12345, +x and diagonal)

| Distance | Coverage (core > 0) | Crest spacing | Alignment to strike (1 = along, ~0.64 = random) |
|---|---|---|---|
| 0 | 33–39% | 4.6–7.6 km | 0.87–0.90 |
| 10k | 17–38% | 4.1–9.1 km | 0.62–0.97 |
| 20k | 30–63% | 3.1–8.1 km | 0.53–0.91 |
| 40k | 29–60% | 2.4–6.8 km | 0.54–0.87 |
| 80k | 41–78% | 1.2–2.5 km | 0.47–0.72 |

The trend holds for both 1 and 3 ridges. With 3 ridges the belt is about 3.3 km wide, so at about 2 km crest
spacing the belts merge into one continuous sheet of mountains. Each window holds only a few ranges, so single
rows are noisy. The curvature metric in the probe output gave no clean signal.

## 3. Related tuning (user wish, 2026-11-05)

The user wants RH ranges, especially multi-ridge ones, to be less linear, narrower, less common and more winding.
Near spawn the current field does what it was designed to do. Ranges are straight (alignment about 0.9), cover a
third or more of the land, and 3-ridge belts are about 3.3 km wide. Constants that control this, all in
`HighlandLand`:

- Frequency: presence band `smooth(.38, .58, …)`; `RIDGE_RANGE_SPREAD = .5` (belts widen 1.9x, while ranges move
  apart only 1.45x).
- Width: `rangeHalfWidth = clamp(.14 * l, 420, 1500)`, `RIDGE_SPACING = .9`, and the core is full between the
  outer crests.
- Straightness: `RANGE_WARP = .1`, `RANGE_ELONGATION = 2.5`, strike wavelength `5 * l`, `RIDGE_WANDER = .2`.

## 4. Intended fix order

1. Make `rangeField` translation-invariant. Options: the ORIENTATIONS blend `oriented()` uses, or a domain warp in
   place of the position-dependent rotation. Acceptance: probe metrics at 40k and 80k match those at 0, within
   seed noise.
2. Then tune the constants above toward the user's wish, using the probe to measure.

## 5. Constraints

- Both steps change generation for single-ridge worlds too, so they need a model version bump. The settings
  comment says 1 ridge "keeps model-7 worlds unchanged".
- Existing worlds, such as the F&F server, would get seams where new chunks meet old ones.
- More warp makes the first-order distance estimate in `rangeFrame` less accurate, so belt width will vary more.
  Check it with the probe.
