# Rolling Highlands RH2

Branch: `feature/rolling-highlands`. Build `1.0.0014-rh2`, generation model 2. Plan, decisions and per-phase log:
[PLAN-RH2.md](PLAN-RH2.md). RH1 notes are kept in git history (`3672fcae`).

## Trying it

Create a **new world**, open the FTF preset editor and select **Rolling Highlands (Experimental)** under
World → Continent Type. Defaults: spacing 4096, mountain height 160, foothill relief 48, dry valley width 256;
world height 512 and sea level 63. Presets saved by RH1 (model 1) are rejected with a "create a new world" message
instead of regenerating differently. Legacy continent types are unchanged (frozen fingerprints still match).

## What changed from RH1

| Area | RH2 behaviour |
|---|---|
| Valleys | Every reach in range contributes a section; the result is a crease-softened minimum, so there are no walls where the nearest river changes. Channels own their wet cells (lowest bed wins) and every channel has a support cone (one block above its water at the bank, falling 1/block), so a higher river sits on a terrace instead of being cut away or spilling. |
| Rivers | D8 priority flood (no crossing diagonals, distance tie-breaks on flats), nodes snapped toward local lows plus bounded jitter, tangent-continuous Hermite reaches with a subtle meander on flat reaches, channel heads taper from a narrow shallow stream over 160 blocks. |
| Lakes | Only channel-connected fills at least 12 blocks deep; shallower fills are breached by the profile (≤ 36-block saddle cut). Shorelines follow the planning surface (bilinear field over the lake and a rising-ground shore region with a 384-block chamfer reach), with shoreline noise. Lakes record their outlet for currents. |
| Profiles | Deterministic feasibility repair: rapids where a fixed level cannot descend within the slope limit, backwater behind fixed levels; solver absorbs round-off. |
| Biomes | Weirdness stays on vanilla's normal side and comes from final prominence (suppressed in valleys and lowlands); erosion falls with prominence and avoids levels 5–6. Both are calibrated to legacy FTF shares at the same settings. Beaches and coast come from the shared climate pipeline plus legacy beach noise. Oceans use the legacy deep/shallow/coast populators. |
| Coast | Ease-out rise from the coastline (no wide flat sand band) while keeping the continental tilt that drains the interior. |
| Currents | Legacy model (downstream tangent, inward turn at banks, cross-channel falloff) with speed from the solved water slope; confluences blend; lakes are calm with drift toward the outlet. |
| Wetlands | Floodplain wetlands on broad, flat, low floors of larger rivers: hummocks and pools at the river level, WETLAND terrain, erosion-masked; pools fade wherever a lower valley could drain them. |
| Robustness | Lock-free plan cache (one build per continent, LRU 8, per-thread fast path). A continent whose plan cannot be built generates without rivers and logs once; per-sample code never throws. |
| Water placement | RH single-block surface steps keep source water (a small riffle) instead of a flowing block that can drain when no upstream source feeds it. The river gasket only visits RH water and bank columns. |

## Controls

Unchanged from RH1 (spacing 4096–8192, mountain height 32–256, foothill relief 0–96, valley width 64–512;
continent scale 1000–5000; ≥ 320 blocks above sea level). Valley width scales dry floors only; water profiles do
not move.

## Known limits

- Lake shores can be steep where visible mountains rise far above the drainage surface around the lake.
- Wetlands are deliberately rare (large, flat, low reaches only); tune `RollingHighlandsContinent` wetland factors
  if more are wanted.
- Aesthetic acceptance, the V3 comparison and long fluid simulations remain user/exploration items.
