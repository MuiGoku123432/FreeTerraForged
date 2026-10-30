# Rolling Highlands RH7 research: mountain ranges that read as mountains

Input: user screenshots of RH6 ranges in game (around 4366, 9991 on the server seed, Range Height 200/240), with the
verdict "they look like giant sculpted terrain blobs, not mountain ranges". Seen in the screenshots:
- one broad convex mass per range: high plateau → rounded shoulder → continuous snow cap;
- slopes smooth at the 100-1000 block scale and noisy only at the few-block scale;
- no valleys separating peaks: several "peaks" belong to one elevated mass;
- rock/dirt exposures as large amorphous grey and brown patches that do not follow cliffs;
- streams on the range run in shallow scratches; tarns sit on the flat top.

## 1. Root causes in the RH6 code

Range height (`HighlandLand.range`, model 6), Range Height H, length L:
- **One profile.** `core` falls from 1 on the crest line to 0 at the half width `clamp(.14 L, 420, 1500)` (860
  blocks at L 6144). Height = `0.3 H · smooth(0, .6, core)` foothills + `0.7 H · smooth(.45, 1, core)²` main
  ridge. A smooth bell about 1.7 km wide: mean flank gradient ≈ 200 / 860 ≈ 13°, convex at the top.
- **Detail is decoration.** Crest varies ±15% (wavelength 700, stretched 4× along the strike); spurs are ridged noise
  of amplitude `0.3 H · flank` around a −0.35 offset (≈ ±40 blocks at H 200), wavelength 260. Nothing at 30-150
  blocks; nothing that makes valleys deeper than ~40 blocks.
- **Drainage avoids the range.** RH6 multiplied channel steepness by `1 + 2·core`, so incision into a range is a
  third of what it is in the uplands. Plan nodes are 128 blocks apart, so the drainage cannot draw valleys
  between peaks a few hundred blocks apart anyway. Streams on the range are drawn, but do not cut.
- **FTF erosion is local.** The Phacelle erosion filter (`cell/filter/Erosion.java`) and smoothing act on the cell
  grid at block scale; they add gullies a few blocks deep. They cannot dissect a 200-block bell.
- **Classification by height share.** `MOUNTAIN_CHAIN` and the peak weirdness/erosion slice cover everything above
  0.2-0.45 H near the crest, so the whole bell top gets a mountain biome (snowy slopes / peaks) regardless of slope.
  The grey/brown patches are that biome's surface noise (to be confirmed in G0), not slope-driven rock.

So: big smooth shape + small noise, no middle scales, no carving. Tuning sliders cannot fix it.

## 2. What real ranges look like (targets)

Fluvially dissected ranges (Rockies, Alps below the ice, Coast Mountains) share a few robust regularities:
- **Regular valley spacing.** Main transverse valleys leave a linear range at nearly fixed spacing: spacing ≈ half
  the range width (ratio of half-width to spacing ≈ 1.9-2.2, Hovius 1996 across many ranges; Talling et al. 1997).
  Ridges between them are the spurs. Ridge-valley spacing also appears in uniform landscapes at a characteristic
  wavelength (Perron, Kirchner & Dietrich 2009).
- **Dendritic hierarchy.** Valleys branch (Horton/Strahler orders 3-5 within a range); ridges are what is left
  between valleys, so ridges branch too, in the opposite direction.
- **Threshold hillslopes.** In actively uplifting ranges, hillslopes sit near a threshold angle of about 30-35°
  regardless of uplift rate; more uplift gives more relief through deeper and longer valleys, not steeper sides
  (Burbank et al. 1996, Montgomery & Brandon 2002). Slope distributions are peaked near that threshold, with cliffs
  above it on rock and gentler valley floors.
- **Sharp crests, concave lower slopes.** Divides are narrow (a few blocks wide at Minecraft scale), slopes straight
  to slightly concave down to the valley floor; convex shoulders exist but are narrow.
- **Glacial overprint above the snowline** (Rockies style): U-shaped main valleys, cirques at valley heads (bowls
  with tarns), arêtes between cirques, horns where three cirques meet. Optional, but this is what makes "Rockies".
- **Surface follows slope and height.** Cliff bands on steep rock, scree/talus below them, alpine meadow on gentle
  slopes above the treeline, snow on gentle high ground and in cirques, forest below the treeline.

Minecraft scale (1 block = 1 m): a Range Height of 200-240 gives ~250-350 m relief; with a 30° threshold and a half
width of ~860, valleys spaced ~400-450 blocks and ~100-200 blocks deep fit inside a range.

## 3. Techniques

| Technique | Gives | Cost / fit |
|---|---|---|
| Ridged multifractal (Musgrave) with octave weighting by the previous octave | sharp crests, branching ridges | cheap per column; ridges are noise-placed, not drainage-consistent; risk of "noise mountains" |
| Derivative-damped fBm ("erosion-like" fBm, Quilez) | smoother valleys, detail on slopes | cheap; mostly cosmetic |
| Phacelle / gully noise (Johansen 2025, already in FTF erosion filter) | gullies aligned with the slope at chosen scale | cheap; scale-limited, needs the large-scale shape to be right |
| Drainage-first terrain (Génevaux et al. 2013; Cordonnier et al. 2016 uplift + stream power) | valleys and ridges consistent by construction, dendritic, threshold slopes | what RH already does for the uplands at 128-block nodes; needs a finer grid inside ranges |
| Stream power with threshold hillslopes (Cordonnier 2016, Schott et al. 2023) | real dissection: elevation = channel elevation + hillslope at threshold angle from the nearest channel | per-column evaluation needs distance to the nearest channel; RH has a channel index (`HighlandPlanIndex`) |
| Glacial carving (U-valley profile, cirque bowls at heads above a snowline) | Rockies/Alps character | add-on to drainage-first: reshape valleys above a height line |

Best fit for RH: **drainage-first at range scale.** Treat a range as an uplift field; run the existing stream-power
solver on a finer node grid inside ranges (32-64 blocks), so channels are carved and spaced naturally; build the
rendered surface as channel elevation + a threshold-slope hillslope rising from the nearest channel, capped by the
uplift envelope (this produces sharp divides and spurs automatically). Then add mid-scale ridged detail only on the
hillslopes, Phacelle gullies at small scale, and a glacial reshaping above a snowline.

## 4. Constraints from the existing system
- Plans are built per continent (1-2 s). A 32-block grid over all ranges is 16× the nodes of the 128 grid in those
  areas; ranges cover ~10-20% of land, so +2-3× plan cost. A 64-block grid is +1.5×. Needs measurement.
- Range channels must join the main network (rivers cross ranges only at passes or gorges; RH6 crossing gate 0).
- Water-contract gates (water steps, dry jumps, shore cliffs, flow, containment in game) must hold on steep ground.
  Threshold slopes of 30-35° are ~0.6-0.7 blocks per block: no 2-block jumps, so `dryJumps` should hold.
- The heightmap must stay per-column and deterministic (no global passes at chunk time).
- Biome classification is per column from weirdness/erosion/terrain; slope is available (`cell.gradient`).

## 5. Open questions (for the spec decisions)
1. Glacial features (U valleys, cirques, arêtes) in RH7, or fluvial dissection first and glaciation later?
2. Taller ranges: raise the 240 cap (world height allows) or keep it and get drama from dissection?
3. Should the legacy FTF mountains stay at 20% between ranges, or go to 0 when ranges replace mountains?
4. Snow: a height-based snowline with slope (snow on gentle high ground, rock on steep) vs biome-driven snow.

## References
- Hovius, N. (1996). Regular spacing of drainage outlets from linear mountain belts. Basin Research 8.
- Talling, P. et al. (1997). Regular spacing of drainage outlets from linear fault blocks. Basin Research 9.
- Perron, J.T., Kirchner, J.W., Dietrich, W.E. (2009). Formation of evenly spaced ridges and valleys. Nature 460.
- Burbank, D. et al. (1996). Bedrock incision, rock uplift and threshold hillslopes in the NW Himalayas. Nature 379.
- Montgomery, D.R., Brandon, M.T. (2002). Topographic controls on erosion rates in tectonically active mountain
  ranges. EPSL 201.
- Musgrave, F.K. (1993). Methods for realistic landscape imaging (ridged multifractal).
- Génevaux, J.-D. et al. (2013). Terrain generation using procedural models based on hydrology. SIGGRAPH.
- Cordonnier, G. et al. (2016). Large scale terrain generation from tectonic uplift and fluvial erosion. CGF 35.
- Schott, H. et al. (2023). Large-scale terrain authoring through interactive erosion simulation. ACM TOG.
- Johansen, R.S. (2025). Phacelle noise / procedural gully erosion (FTF erosion filter source).
