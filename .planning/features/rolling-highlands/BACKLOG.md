# Rolling Highlands — backlog

Deferred ideas and findings. Not scheduled; nothing here is a requirement until promoted to a plan.

## Islands and archipelagos (Deferred, 2026-11-02)

Current state (code reading, not in-game verified):

- RH disables the Islands page ("Extra island overlays are unavailable"), and `Heightmap.make` bypasses the upstream
  `ArchipelagoPopulator` / `IslandBlender` layer entirely for RH.
- `HighlandCoast` gives large, smoothly warped continents (Advanced continent cells); skipped cells give separate
  continents, not island chains.
- RH land never dips below sea level (`HighlandLand` floor of 1 block, `FLOOR_MARGIN` inland), so valleys cannot
  drown: no fjords, sounds or inlets.
- RH7 U valleys and cirques only form above the snowline; land eases up from the coast, so glaciation never meets
  the sea.

Options:

| Option | Result | Cost |
|---|---|---|
| A. Re-enable upstream archipelago overlay in RH | Open-ocean dome/volcanic islands, not fjords | Medium: block-unit vs legacy normalized heights; RH drainage/validation unaware of the islands |
| B. "Drowned glacial coast" phase (Alexander Archipelago / BC / Norway look) | Fjords, sounds, islands as drowned range crests | Large, RH6/RH7-sized phase |

Option B would need: sub-sea-level coastal floors flooded by ocean (breaks the "land above sea level" invariant;
touches drainage, lakes, sealing, validation); glacial troughs reaching and cutting below sea level; coastline
derived from relief instead of the independent continent edge; ocean vs lake vs river ownership and biomes for drowned
valleys; drainage planning for many small land domains (SPEC currently only promises valid empty plans for small
islands).

Keep both out of the first upstream PR; mention the disabled Islands page there as a known limitation.

## Tarns: rendered ground keeps the planned bowl (Deferred, 2026-11-02, "option B")

Option A (done) sets tarn water from the real ground on the footprint rim, so there are no grid-shaped rim walls,
but most planned cirque bowls do not exist in the rendered ground (test world: 3 of 36 tarns hold water, was 7 with
walls up to 80 blocks). Causes: water planned on the carved 24-block grid, while rendered ground adds the legacy/upland
foot (varies across the bowl), the B-spline of the carved grid (softens the 2-block lip), D4 dissection detail (up to
±20 blocks) and residual incision. B: near a cirque, render the exact bowl and lip (as ranges render their exact
shape) and suppress dissection detail there. A then keeps more tarns without changes.

## Upstream PR readiness (Assessed, 2026-11-02)

Not ready as-is. Branch is cut from `1_21_1-unstable-custom` and carries unrelated custom commits. RH-only commits
(`0f2da95b~1..ccabe808`, excluding `.planning/`) apply to `upstream/1.21.1_unstable` with 3 easy conflicts
(`gradle.properties`, `Climate.java`, `TerrainFilters.java`), compile on all loaders, and pass 63/64 common tests;
`LegacyTerrainTest` passes on the upstream base.

Before a PR: discuss with maintainers first; fresh branch from upstream with only RH commits; drop the
`mod_version` bump and benchmark server-ip/port tweaks; dedupe the JUnit dependency and `test {}` block upstream
already added; replace inline fully qualified names with imports and drop internal "RH5 C"-style references; fix the
flaky wall-clock assertion in `HighlandRangeTest.userRangesHaveEvenCrestsAndSpurs` and gate slow RH tests, since
upstream `runPresetTest` runs `:common:test` inside a 15-minute CI job (RH tests took ~9 minutes locally).
