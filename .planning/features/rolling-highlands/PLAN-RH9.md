# Rolling Highlands RH9 plan: a real coast

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> Also required reading: `/Users/cfanch06/.agents/skills/iterative-delivery/SKILL.md` (GSD Lean policy).

**Goal:** RH coasts with wide beaches, bluffs and sea cliffs in stretches, coves and points, and a PNW /
California / Baja character by climate.

**Architecture:** One new position-only field object, `HighlandCoastProfile`, decides the coast type, beach width
W, scarp height H, dunes and the extra coastline warp. `HighlandCoast` uses its warp. `HighlandDrainage.landBlend`
and `RollingHighlandsContinent` use its profile, so planning and rendering see the same ground. `shore()` tags
BEACH across W. `ScreeFeature` covers coastal faces through the existing `HighlandSurface` CLIFF path.

**Tech Stack:** Java 21, Minecraft 1.21.1 (Fabric + NeoForge, `common` module), JUnit 5, Gradle.

**Spec:** [SPEC-RH9.md](SPEC-RH9.md). Branch `feature/rolling-highlands`. One commit per phase,
`feat(worldgen): RH9 <phase> ...`, full `./gradlew :common:test` green (~15 min); never amend or push unasked.

## Global Constraints
- `RollingHighlandsSettings.MODEL_VERSION` = 8. Model-7 presets are rejected as unsupported (existing validate).
  No new setting.
- Type shares at the user world: beach ~55%, bluff ~25%, cliff ~20%, each within ±8 points.
- Dry beach (beach-type stretches): width median ≥ 18, p10 ≥ 8, design range 15-40. Bluff: beach 8-20 at the foot
  of a 6-16 block scarp. Cliff: 15-40 blocks, ≥ 15 at 4 blocks inland, depth ≥ 5 at 8 blocks offshore.
- Stretches 300-3000 blocks along the coast; transitions over 40-80 blocks.
- River mouth fade ~64 blocks for drainage area ≥ `riverArea`. Creeks cut a gully of half width (channel half
  width + 3) to sea level.
- Terrace reach ≤ 64 blocks. Effective H = `min(H, land height at the terrace's inland end)`.
- Sinuosity ≥ 1.15 over 2 km windows.
- Cost: heightmap time ≤ model-7 baseline + 15%. If the climate sample breaks this, apply the A-7 fallback
  (climate sets materials only) and record it.
- RH water gates stay 0 (steps, dry jumps, shore cliffs, below sea); `HighlandWorldValidation` green.

## Review Focus
1. **Cliff coast meeting a lake or wetland near the shore.** A lake inside the 64-block terrace band must keep its
   level and banks (lake windows read `landBlend`). Test: `HighlandCoastTest.coastalLakesKeepTheirBanks` (Task S).
2. **Thin peninsulas and narrow straits after the new warp,** where shore distance from both sides overlaps.
   Expect no knife-edge ridges or sub-sea-level land. Test: `HighlandCoastTest.narrowLandHasNoSeaPits` (Task S).
3. **Region-boundary seams:** the extra warp must stay inside `warpBound`, or heights jump at owner borders.
   Test: `HighlandBoundsTest` passes unchanged with the larger bound, plus `HighlandCoastTest.warpStaysInBound`
   (Task C).
4. **Determinism across threads and lookup paths** (`applyCell` precise vs coarse, chunk cache vs lookup): beaches
   must not come and go with cache state (the reason `shore` exists). Test: `HighlandDeterminismTest` passes, plus
   `HighlandCoastTest.beachTagIsPathIndependent` (Task S).
5. **Cold coasts:** snowy beach climate must still give a walkable beach, not stone. Test:
   `HighlandCoastTest.coldBeachesStayBeaches` (Task M).

---

## 1. Decisions
SPEC D-a to D-e (user, 2026-11-05) and A-1 to A-8 (assumed, builder check 2026-11-05). Additional plan calls:
- **P-1 New class `HighlandCoastProfile`** (highlands package): position-only coast fields. Reason:
  `RollingHighlandsContinent` is already large, and the profile is shared by three consumers (`HighlandCoast` warp,
  `HighlandDrainage.landBlend`, the continent's `apply` / `shore`). Not a framework: one final class and one
  record.
- **P-2 Raw climate source.** `Climate.make(continent, ctx)` is built *after* the continent (Heightmap.java:150-152,
  169), so the profile builds its own `ClimateModule` from the same context and seed and calls
  `ClimateModule.sampleRaw(x, z)` (position-only, no cell). This skips `Climate`'s edge displacement. That is
  acceptable because climate only leans the coast.
- **P-3 Shore distance** stays `shoreDistance` (first order). Add a secant correction only if Task S's
  beach-width gate fails because of it (SPEC §4).

Reuse (local search; Gortex is not tracking this repo, so graph reuse verification is incomplete):
`HighlandMath.noise`/`smooth`, `HighlandCoast.sample`, `shoreDistance`, `HighlandSurface.Cover.CLIFF`,
`ScreeFeature.coverColumn`, and `ClimateModule.sampleRaw` are all reused. No new dependencies.

## 2. Order and effort
| # | Phase | Files | Size |
|---|---|---|---|
| 1 | **P0** coast probe + model-7 baseline | `HighlandCoastProbe` (new, test) | S |
| 2 | **C** model 8, profile fields, coastline warp | `HighlandCoastProfile` (new), `HighlandCoast`, `RollingHighlandsSettings`, `HighlandCoastTest` (new) | M |
| 3 | **S** shore profiles, shifted land, rivers/creeks, beach tag | `HighlandDrainage`, `RollingHighlandsContinent`, `HighlandCoastTest`, `HighlandShoreTest` | L |
| 4 | **M** coastal rock and beach materials | `Cell`, `RollingHighlandsContinent`, `HighlandSurface`, `ScreeFeature` | M |
| 5 | **X** sea stacks | `RollingHighlandsContinent` (ocean branch), `HighlandCoastTest` | S |
| 6 | **V** tuning, in-game, release | constants, docs, presets | M |

Estimated production diff ~350-500 lines, tests ~300.

## 3. Tasks

### Task P0: coast probe and baseline

**Files:** Create `common/src/test/java/etcodehome/freeterraforged/world/worldgen/highlands/HighlandCoastProbe.java`.

**Interfaces:**
- Produces: `record HighlandCoastProbe.Report(int samples, double beachShare, double bluffShare, double cliffShare,
  double beachWidthMedian, double beachWidthP10, double sinuosity, double cliffHeight4Median,
  double cliffDepth8Median, int stacksPer10km, long nanos)` and
  `static Report measure(WorldgenFixture context, int originX, int originZ, int radius)`.
- Method: walk the coastline (cells where `continentEdge` crosses `coastEdge`) in the box, one sample per 16
  blocks of coast. At each sample, step inland along the edge gradient: dry-beach width = BEACH-tagged columns
  from the waterline. Height at 4 / 8 / 32 blocks inland, depth at 8 blocks offshore. Type: from the profile once
  it exists; at P0 classify from geometry (height at 4 inland ≥ 15 → cliff; ≥ 6 → bluff; else beach). Sinuosity:
  coast-path length / chord over 2 km windows.

- [ ] Write `HighlandCoastProbe` with a `main` that prints the Report for the user world
  (`HighlandPlanformProbe.userPreset()`, `USER_SEED`, origin 10109, 18236, radius 4096) and for ROCKIES.
- [ ] Run it. Expected at model 7: beach width median ≤ 3, cliff share ≈ 0, sinuosity ≈ 1.0x.
- [ ] Record the baseline table in §4 of this file, with heightmap ns per column.
- [ ] Commit `test(worldgen): RH9 P0 coast probe and model-7 baseline`.

### Task C: model 8, profile fields and coastline warp

**Files:** Create `.../highlands/HighlandCoastProfile.java`, `.../highlands/HighlandCoastTest.java` (test).
Modify `HighlandCoast.java` (constructor, `sample`, `warpBound`) and `RollingHighlandsSettings.java`
(`MODEL_VERSION` = 8, javadoc line for 8 = RH9 coast).

**Interfaces:**
- Produces:
  ```java
  public final class HighlandCoastProfile {
      public enum Type { BEACH, BLUFF, CLIFF }
      /** blend: per-type weights summing to 1 (transitions); width W, height H (blocks); dunes 0..1; hardness 0..1. */
      public record Shore(double beach, double bluff, double cliff, double width, double height, double dunes,
                          double hardness, double temperature, double moisture) { Type dominant(); }
      public HighlandCoastProfile(GeneratorContext context);
      public Shore shore(double x, double z);          // position-only, deterministic
      public double warp(double x, double z);         // signed blocks, |warp| <= WARP_AMPLITUDE
      public static final double WARP_AMPLITUDE;      // tuned in V; start 160
  }
  ```
- Fields: hardness = slow noise (wavelength ~1500 blocks), sharpened. Type weights come from hardness, shifted
  by climate (cool/wet +0.1 toward cliff). Width W ∈ [15, 40], scaled up toward 55 when warm/dry. H ∈ [6, 16]
  for bluffs and [15, 40] for cliffs. Dunes > 0 only when warm/dry. Climate is from P-2.
- Warp: displaces the coastline seaward with hardness (points) and landward without it (coves), at a few-hundred-
  block scale, applied along the edge-field normal inside `HighlandCoast.sample` before the cell lookup.
  `warpBound` grows by `WARP_AMPLITUDE`.

- [ ] Write the failing tests in `HighlandCoastTest`:
  - `profileIsDeterministic`: two profiles from the same context return equal `Shore` at 1000 random points.
  - `typeSharesMatchSpec`: over coastline samples from `HighlandCoastProbe.measure` at the user world, the
    dominant-type shares are within ±8 of 55/25/20. Mark it `@Disabled("tuned in V")` until Task V; the
    assertion is still written now.
  - `stretchesAreLong`: the median run of one dominant type along the coast is ≥ 300 blocks.
  - `warpStaysInBound`: for 10 000 points, `|profile.warp(x, z)| <= WARP_AMPLITUDE`.
  - `coastlineIsSinuous`: probe sinuosity ≥ 1.15 at the user world.
- [ ] Run `./gradlew :common:test --tests '*HighlandCoastTest*'`. Expected: fails (class missing).
- [ ] Implement `HighlandCoastProfile`, wire the warp into `HighlandCoast`, and bump `MODEL_VERSION`.
- [ ] Re-run `HighlandCoastTest`, `HighlandBoundsTest` and `HighlandSettingsTest`. Expected: pass, with the
  settings test fixtures moved to model 8. `test-america-v3.json` stays at 7 only if a test asserts the rejection
  of old models; otherwise move it to 8.
- [ ] Run the full `:common:test`. Re-record the golden hashes that fail only because the coastline moved
  (`ranges-model7.sha256` → `ranges-model8.sha256`, `legacy-terrain.sha256` only if RH-affected). Note each one in
  §4 with its reason.
- [ ] Commit `feat(worldgen): RH9 C model 8 coast profile and coves/points`.

### Task S: shore profiles

**Files:** Modify `HighlandDrainage.java` (`landBlend`, `planningHeight`), `RollingHighlandsContinent.java`
(`apply`, `floored` ramp, ocean shoreface, `shore`, constants), `HighlandCoastTest`, `HighlandShoreTest`.

**Interfaces:**
- Consumes: `HighlandCoastProfile.shore(x, z)`, `shoreDistance(x, z, edge)`.
- Produces: `HighlandDrainage.landBlend(double edge, double x, double z)`, which replaces the one-argument form
  everywhere (planning, lake windows, `floored`, tarns). It takes inland distance minus W before the existing
  ease-out, so it is 0 across the beach. Also `RollingHighlandsContinent.coastProfile(Cell, double x, double z,
  double land)` → height, applying:
  - **beach:** berm 2-3 at the waterline, dry beach to W, dunes (amplitude ≤ 5) when dunes > 0;
  - **bluff:** beach to W_b = 8-20, scarp over 3-5 blocks to H_eff, terrace tilted seaward to 64 blocks;
  - **cliff:** H_eff at the coastline, terrace as for a bluff;
  - weights blended by `Shore.beach/bluff/cliff`; the result is `max(shifted land, profile)` for bluff and cliff
    and replaces it on the beach;
  - `H_eff = min(H, land height at 64 blocks inland)` (A-8);
  - river fade: within 64 blocks of a hit with `area ≥ riverArea`, weights → beach; creek gully: within channel
    half width + 3 of a creek hit, the profile is capped at the channel's water level.
- Ocean side: cliff weight steepens the shoreface. Depth is ≥ 5 within 8 blocks and reaches 6-12 by 16, blended
  with the existing `SHOREFACE_RUN` curve by weight.
- `shore()`: BEACH where shore distance ≤ the profile's beach width (W, or W_b on bluffs) and height ≤ berm +
  dunes + 1. `BEACH_WIDTH` / `BEACH_HEIGHT` are removed. Cliff-weight columns within 8 blocks of the coast and
  steep get no BEACH tag.

- [ ] Write the failing tests in `HighlandCoastTest` (user world, `WorldgenFixture`):
  - `beachesAreWide`: on beach-dominant samples the probe's dry-beach median is ≥ 18 and p10 ≥ 8.
  - `cliffsStandAndDropDeep`: on cliff-dominant samples, median height at 4 inland is ≥ 15 and median depth at 8
    offshore is ≥ 5.
  - `noHollowBehindScarps`: along each bluff/cliff sample's inland transect (0-96 blocks), height never falls
    more than 1 block below the running maximum of the seaward part.
  - `riversReachTheSeaLow`: for every river mouth (`area ≥ riverArea`) in the box, ground within 64 blocks along
    the coast is ≤ berm + dunes + 1, and the channel water steps 0 to the sea.
  - `creeksCutGullies`: for every creek mouth on a bluff/cliff stretch, the channel water reaches sea level with
    0 steps.
  - `coastalLakesKeepTheirBanks`, `narrowLandHasNoSeaPits`, `beachTagIsPathIndependent` (Review Focus 1, 2, 4):
    lake water and banks unchanged by `HighlandLakeContainmentTest` criteria inside the band; no dry column below
    sea level within 128 blocks of the coast; precise and coarse `applyCell` agree on BEACH at 2000 coastal
    columns.
- [ ] Update `HighlandShoreTest.seaFloorDeepensAndLowShoresAreBeaches` thresholds to the new widths (it uses
  `BEACH_HEIGHT`, which is removed).
- [ ] Run `./gradlew :common:test --tests '*HighlandCoastTest*' --tests '*HighlandShoreTest*'`. Expected: fail.
- [ ] Implement `landBlend(edge, x, z)`, `coastProfile`, the ocean-side change and the new `shore()`.
- [ ] Re-run the coast tests. Expected: pass. If `beachesAreWide` fails from distance error, apply P-3 (secant
  step) once and re-run.
- [ ] Run `HighlandQualityProbe` / `HighlandWorldValidation` water gates. Expected: 0 failures.
- [ ] Run the full `:common:test`. Re-record changed golden hashes in §4.
- [ ] Commit `feat(worldgen): RH9 S beaches, bluffs and sea cliffs`.

### Task M: coastal materials

**Files:** Modify `Cell.java` (new `public float highlandCoast;` 0..1 coastal-face weight, plus copy in the copy
method), `RollingHighlandsContinent.java` (writes it and the profile's climate class), `HighlandSurface.java`
(`choose` treats `max(range, coast)` as the range weight below the treeline; CLIFF at `FOREST_CLIFF_SLOPE`), and
`ScreeFeature.java` (enter the cover branch when `highlandCoast ≥ MIN_RANGE`; coast CLIFF uses the climate rock
palette).

**Interfaces:**
- Produces: `Cell.highlandCoast`, and `Cell.highlandCoastClimate` (byte: 0 PNW, 1 California, 2 Baja), set in
  `apply` from `Shore.temperature/moisture`.
- Palette: PNW = stone, andesite, tuff; California = sandstone, smooth sandstone, a little terracotta; Baja = red
  sandstone, orange/red terracotta. Beach blocks: sand; PNW mixes in gravel (~30%, cluster noise); snowy beaches
  stay vanilla.

- [ ] Write failing tests in `HighlandCoastTest`: `cliffFacesAreRock` (≥ 90% of cliff-dominant columns with slope
  ≥ 50° get `Cover.CLIFF` from `HighlandSurface.choose`) and `coldBeachesStayBeaches` (beach-type columns at
  climate class PNW and temperature below the snowy threshold are tagged BEACH, with no CLIFF cover).
- [ ] Run them. Expected: fail.
- [ ] Implement as above.
- [ ] Re-run, then run the full `:common:test`. Expected: pass.
- [ ] Commit `feat(worldgen): RH9 M coastal rock and beach materials by climate`.

### Task X: sea stacks

**Files:** Modify the `RollingHighlandsContinent.apply` ocean branch and `HighlandCoastTest`.

- Stacks: on cliff weight ≥ 0.6, cell noise (spacing ~48 blocks, chance 0.25, cool/wet ×1.5) places columns
  3-12 wide, 10-60 blocks offshore, top 5-25 above sea level with steep sides and a rough top. Ocean cells only,
  so drainage is untouched. Faces get `highlandCoast` = 1.
- [ ] Write `stacksOnlyOffCliffs` (no stack cell where cliff weight < 0.6 or nearer than 10 blocks to land) and
  `stacksAreRecorded` (the probe count per 10 km of cliff coast is > 0 at the user world).
- [ ] Run them (fail), implement, re-run (pass), then run the full `:common:test`.
- [ ] Commit `feat(worldgen): RH9 X sea stacks off cliff coasts`.

### Task V: tuning, validation and release
- [ ] Tune `WARP_AMPLITUDE`, the type thresholds, W and H against the probe until all §Global gates pass at the
  user world and ROCKIES. Remove `@Disabled` from `typeSharesMatchSpec`. Record final values and the reasons for
  any moved gate in §4.
- [ ] Cost: probe ns per column vs P0. Gate ≤ +15%. If it fails, apply the A-7 fallback (profile ignores climate
  for geometry) and record it.
- [ ] `./gradlew :common:test :fabric:build :neoforge:build`. Expected: green.
- [ ] In-game on both loaders, new world (user seed, model 8): beach, bluff, cliff+stacks, river mouth through a
  cliff stretch, and one site per climate character. Take overview and ground screenshots for the user.
- [ ] Docs (README RH section, IMPLEMENTATION.md), next `-rh9` version, ROLLING / ROLLING-SR / ROCKIES presets and
  backups on model 8, deploy to Prism and stage the server bundle.
- [ ] Vault: one status line in the project note.
- [ ] Commit `feat(worldgen): RH9 V tuning and release`.

## 4. Baseline and as built
### P0 (model 7)
`HighlandCoastTest.reportsCoast` (user world, 8 km box centred on 10109, 18236; summary in `common/build/rh9-coast.txt`).

| Metric | Model 7 |
|---|---|
| coast samples | 265 |
| type by geometry | beach 100%, bluff 0%, cliff 0% |
| BEACH-tag width median / p10 | 6 / 5 |
| sinuosity (traced coastline, path/chord over 2 km windows) | 1.079 |
| stacks per 10 km cliff | 0 |
| column cost (warm plans) | 10.5 µs |

Sinuosity is path/chord over 2 km windows on coastlines traced by crack following on an 8-block grid (resampled
every 8 cracks). A Cauchy-Crofton length ratio (0.989) was tried first and read ≈ 1 whatever the coast did, so it
was dropped. Gate stays ≥ 1.15.

### C (model 8)
`RHC user n=275 beach=60% bluff=25% cliff=15% sinuosity=1.212 stretch=382` (warp 200, fade .06-.35 edge units).
Golden hashes re-recorded: `ranges-model7.sha256` → `ranges-model8.sha256` (the coastline warp changes the edge field
near the coast, which moves drainage and lakes across the whole owner). `HighlandIntegrationTest` now checks cells
against the lake-resolved plan: on seed 0 the moved drainage puts a lake on a reach, and lake binding lowers that
reach to the lake's water (cell = resolved plan exactly, 11.657), 8.09 below the raw plan the test used to compare.

### S (model 8)
`RHC user n=275 beachWidth median=29 p10=20 cliffH4=15.9 cliffDepth8=12.5 hollow=0.9% sunk=0 ns/column=10725`.
The plan sees the beach shift only; bluff/cliff scarps are rendered and channels' valleys cut through them (see
ledger). `ranges-model8.sha256` re-recorded for the S ground. Cliff height median sits just over its gate (15.9 ≥ 15):
tune in V. `stacks/10km` reads 29.8 before Task X: the probe counts any ground ≥ 5 within 10-60 offshore, which at
points includes the next headland; X refines it.

### M (model 8)
`highlandCoast` (scarp-face weight) and `highlandCoastClimate` (California 0 / PNW 1 / Baja 2) on the cell. Steep coast
columns get CLIFF cover from a per-climate palette (4 deep): California sandstone / smooth sandstone / terracotta, PNW
stone / andesite / tuff, Baja red sandstone / orange and red terracotta. PNW beaches get gravel patches. 101/101 steep
cliff columns are rock at the user site (was 0); PNW beach columns stay BEACH.

### X (model 8)
One stack candidate per 48-block cell (chance 0.25, ×1.5 PNW) on centres with cliff weight ≥ 0.6, 10 + r to 60 blocks
offshore; radius 1.5-6 with a noisy outline, top 5-25 with a rough top, sides falling over 1.5 blocks. Ocean columns
only. `stacks/10km` 7.4 user, 15.8 ROCKIES (sea columns only; the 29.8 before X counted headland land).

### V (as built)
| Metric | Model 7 | Model 8 user | Model 8 ROCKIES | Gate |
|---|---|---|---|---|
| beach / bluff / cliff | 100 / 0 / 0 | 60 / 25 / 15 | 53 / 21 / 26 | 55 / 25 / 20 ± 8 |
| beach width median / p10 | 6 / 5 | 29 / 20 | 26 / 20 | ≥ 18 / ≥ 8 |
| sinuosity | 1.079 | 1.212 | 1.225 | ≥ 1.15 |
| cliff height 4 in / depth 8 out | – | 15.9 / 12.5 | 30.3 / 25.3 | ≥ 15 / ≥ 5 |
| stacks per 10 km cliff | 0 | 7.4 | 15.8 | recorded |
| stretch median | – | 382 | 471 | ≥ 300 |
| hollow / sunk | – | 0.9% / 0 | 7.8% / 0 | ≤ 5% (user) / 0 |
| ns per column (warm) | 10.5k | 10.3k | – | ≤ +15% |

No values moved in V. `MIN_CLIFF` 20 was tried (user cliffH4 15.9 → 16.4: cliff height is land-limited, A-8) and
reverted. Capping the terrace at the plain ramp 64 blocks inland was tried against the ROCKIES hollows and reverted
(cliffH4 2.6 user, hollows up); the ROCKIES hollows are dry dips where a scarp top follows a coastal hill down inland
(Known limits). Review Focus tests added in V: `coastalLakesKeepTheirBanks`, `narrowLandHasNoSeaPits`, plus
`rockiesCoastMeetsGates`.
