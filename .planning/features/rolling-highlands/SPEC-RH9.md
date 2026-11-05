# Rolling Highlands RH9 spec: a real coast (beaches, bluffs, sea cliffs)

Model 8 (bump; new worlds only). Status: draft for review, 2026-11-05.

## 1. Problem
In-game (2026-11-05, F&F world near 10109, 79, 18236) the RH coast is a straight line of land ramping out of the
sea with a 1-3 block sand strip on it. Causes in the code:
- `HighlandDrainage.landBlend` multiplies the inland surface by an ease-out curve that is 0 at the coastline, so
  every shore is a smooth ramp: no bluff, scarp or headland can form.
- `RollingHighlandsContinent.shore` tags BEACH only within ~6 blocks (`BEACH_WIDTH`, ragged ×0.5-1.5) of the
  coastline and ≤ 3 blocks above sea level (`BEACH_HEIGHT`). The ground under it is still the land ramp.
- `HighlandCoast.sample` warps the continent edge once at `tectonicScale × .225` (the legacy high-frequency coast
  modulation was removed on purpose), so coastlines run straight for kilometres: no coves or points.
- Seaward, the shoreface deepens to shelf depth over `SHOREFACE_RUN` = 48 blocks. This is fine for beaches but too
  gentle under a cliff.

Goal: a coast like the American West. It should be mostly walkable beach, with bluffs and sea cliffs common enough
that you meet them regularly. Its character follows climate: Pacific Northwest where cool and wet, California in
the middle, Baja where warm and dry.

## 2. Decisions
Confirmed by the user, 2026-11-05:
- **D-a Three climate characters, all wanted.**
  - **PNW:** dark rock headlands, sea stacks, pocket beaches in coves, forest to the cliff edge.
  - **California:** long sand beaches backed by bluffs and marine terraces.
  - **Baja:** wide bright beaches with dunes, tall tan or red cliffs, rocky points.
- **D-b Mix:** balanced but more beach than cliff, with cliffs not uncommon.
- **D-c Beaches much wider than today.** They are not a 2-block strip.
- **D-d New worlds only:** `MODEL_VERSION` 7 → 8. Model-7 presets are rejected as unsupported, as with earlier
  bumps. The user resets the F&F world.
- **D-e Default:** the new coast is simply how model 8 RH coasts generate. There is no on/off setting. ROCKIES and
  the shipped FTF RH presets move to model 8.

Assumed (builder's call unless the user objects):
- **A-1 Coast types and target shares** are measured along the coastline at sea level:

  | Type | Share | Shape |
  |---|---|---|
  | Beach | ~55% | sand or gravel from the waterline to a berm, dry beach 15-40 blocks wide, low dunes behind it in warm/dry climates |
  | Bluff | ~25% | beach 8-20 wide at the foot of a 6-16 block scarp, flat terrace on top that merges into the land |
  | Sea cliff | ~20% | 15-40 block cliff straight into the sea, no beach, deep water at the foot, occasional sea stacks |

- **A-2 Stretches, not flicker.** The type comes from slow noise along the coast. A stretch runs 300-3000 blocks,
  with transitions over ~40-80 blocks: a beach rises into a bluff, and a bluff steepens into a cliff.
- **A-3 Climate leans the mix rather than replacing it.** Cool/wet coasts take about +10 points of cliff share
  with darker rock and sand/gravel beaches. Warm/dry coasts take wider beaches, dunes and tan/red cliff rock. The
  middle stays California. Climate is read from the cell's temperature and moisture, which `shore` already sees
  after the climate pass. Inland biomes are not overridden: Baja vegetation appears only where the climate is
  already arid.
- **A-4 Headlands and coves share one cause.** A single slow "hardness" field drives both the cliff type and a new
  mid-scale coastline warp. Hard rock juts out as points and headlands; soft stretches recede into coves and bays
  with beaches, as on real coasts. Coves and points are a few hundred blocks across.
- **A-5 River mouths are always low.** Within ~64 blocks of a *river* reaching the sea (drainage area at or
  above the river law's `riverArea`), the profile fades to beach, so rivers never meet a cliff or step down to
  the sea. *Creeks* do not fade the type, which would turn the stretches into flicker. Instead each creek cuts a
  narrow gully (channel half width + ~3 blocks, walls steep) down through a bluff or cliff to sea level, so it
  reaches the sea with no step.
- **A-6 Beaches move the land back; they are not a max.** (Builder check, 2026-11-05.) Today most ground 15-40
  blocks inland already stands above berm height, so a raise-only profile cannot make a wide beach. The coastal
  band therefore re-maps the land's rise. `landBlend` takes the distance inland *minus the beach width W* (0 on the
  beach itself), and the profile supplies the beach surface there. Bluff and cliff profiles are maxes over that
  shifted land. W, H and the type are functions of position only (hardness field, along-coast noise, raw climate),
  so `planningHeight`, the lake windows and the `floored` ramp use the same shifted blend. The drainage plan is
  made on the surface that gets rendered.
- **A-7 Climate shapes the ground through raw climate.** Height is computed before the climate pass, and that pass
  depends on height. The coast geometry therefore reads the *raw regional* temperature and moisture: the
  pre-altitude sample from the existing raw climate helpers, at the column (or the plan node). This adds one extra
  climate sample per coastal column, covered by the V cost gate. Fallback if the cost gate fails: climate sets
  only materials and the BEACH tag, and the cliff share and beach widths come from noise alone.
- **A-8 Bluffs and cliffs never stand higher than the land behind them.** The terrace reaches at most ~64 blocks
  inland, at a slight seaward tilt. The effective height is `min(H, land height at the terrace's inland end)`.
  Ground therefore never falls inland behind a scarp (no hollows above or below sea level, nothing new for the
  lake resolution). On low coastal plains, bluffs are simply lower and cliffs become bluffs. The type shares are
  measured on what results.

## 3. Phases (one commit per phase, full `:common:test` each)

### P0: measurement
- `HighlandCoastProbe` (test side, like `HighlandQualityProbe`): sample the coastline of the user world at a fixed
  spacing. Per sample, record:
  - the type from the profile (none at P0);
  - dry-beach width (sand/gravel columns from the waterline inland);
  - the height reached 8 / 32 blocks inland;
  - the seabed depth 8 blocks offshore;
  - the sinuosity of the coastline (path length / chord over 2 km windows).
- Baseline recorded at model 7: beach width median and p10, sinuosity, and coast cost (heightmap time).

### C: coastline shape
- The mid-scale warp added in `HighlandCoast.sample` (A-4) uses the hardness field. `warpBound` (and so `bounds`)
  grows by its amplitude, so the drainage plan, the land mask and the region bounds stay exact.
- The drainage plan picks it up unchanged (it already reads `coast.sample`).
- Gates: sinuosity ≥ 1.15 over 2 km windows (target, tuned in T); RH water gates 0; validation green.

### S: shore profile
- The coast type and parameters (beach width W, scarp height H, dunes) come from the hardness field, the
  along-coast noise and climate (A-1 to A-3).
- Land within the coastal band is shaped by type, with distance from the coastline taken from `shoreDistance`:
  - **Beach:** the shoreface rises to a 2-3 block berm at the waterline, then the dry beach (with dunes in
    warm/dry climates). The land's own rise begins behind the beach, offset inland by W, so forest no longer
    reaches the water.
  - **Bluff:** beach to the foot, a steep scarp to H, then a terrace that holds H until the land rises past it.
  - **Cliff:** ground at the coastline stands at H and drops to the seabed within a few blocks. Seaward, the floor
    reaches ~6-12 blocks of depth within ~16 blocks: a steeper shoreface than beaches get.
- On beaches the land's rise is shifted inland by W (A-6). Bluffs and cliffs are maxes over the shifted land,
  capped by A-8. A-5 fades the profile at rivers and cuts gullies for creeks. The water floor and channel logic
  keep working on the shaped surface.
- `shore` tags BEACH across the whole dry beach width of beach and bluff types (W from the profile, not
  `BEACH_WIDTH`). Cliff faces and wave-cut platforms take a stony shore treatment.
- Gates: dry-beach width median ≥ 18 and p10 ≥ 8 on beach stretches; type shares within ±8 points of A-1 at the
  user world; cliff stretches have height ≥ 15 at 4 blocks inland and depth ≥ 5 at 8 blocks offshore; no land
  below sea level sealed behind a cliff (no ocean-fill pits); river mouths 0 steps; RH water gates 0.

### M: materials and climate
- **Cliff and scarp faces:** reuse the RH7 surface-cover path (`HighlandSurface` CLIFF) with a coast weight on the
  cell, so steep coastal ground is bare rock by climate: dark stone (PNW), sandstone tones (California),
  red/tan sandstone or terracotta tones (Baja).
- **Beach material:** sand by default, sand and gravel mix for cool/wet coasts, snowy beach where vanilla climate
  says so.
- Gates: probe reports the share of cliff faces that are rock (≥ 90%) and the share of beach-type columns tagged
  BEACH (≥ 90%).

### X: sea stacks
- Sparse rock columns 3-12 blocks wide that rise 5-25 blocks above water, 10-60 blocks offshore, only on cliff
  stretches (more on cool/wet ones). They are ocean-side ground only and do not touch drainage.
- Gate: none beyond the RH water gates. A count per 10 km of cliff coast is recorded.

### V: validation and release
- Tuning against the P0 metrics at the user world and ROCKIES.
- In-game on both loaders: a beach site, a bluff site, a cliff site with stacks, a river mouth through a cliff
  stretch, and one site in each climate character.
- Overview render of a coast before/after. Ground-level screenshots for the user.
- Docs, model 8, next `-rh9` version, ROCKIES and the FTF RH presets on model 8, deploy to Prism and stage the
  server bundle.
- Cost gate: heightmap time ≤ model 7 + 15%.

## 4. Risks
- **`shoreDistance` is first order** (edge value / edge gradient). With the new warp it may be off by tens of
  blocks where the edge field curves, which would warp beach widths and cliff placement. Fallback: a second-order
  (secant) step, as RH8 did for crest distance.
- **Cliffs lift the land the drainage plan planned on a ramp.** Channels stay low through A-5. Ground behind a
  cliff that falls below the terrace must not make enclosed sub-sea-level pits.
- **Model-8 test churn:** golden hashes and baselines tied to model 7 are re-recorded (documented in PLAN-RH9).
- **Biome classification:** wider BEACH tags may push vanilla beach biomes onto ground the climate would make
  desert or forest. This is acceptable (beaches are beaches) but recorded.

## 5. Out of scope
Drowned glacial coast (fjords, sounds, islands; BACKLOG option B), arches and sea caves, tidal flats and
estuaries beyond A-5, offshore islands, non-RH continent coasts, and changing biome placement inland.
