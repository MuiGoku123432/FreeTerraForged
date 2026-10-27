# Rolling Highlands — groomed delivery scope

Date: 2026-10-27. Status: planned; implementation has not started.

User decision: **playable experimental option first, then tune through exploration**. This document stages the [full specification](SPEC.md); it does not declare its acceptance criteria passed or remove its eventual requirements. See [PLAN.md](PLAN.md) for implementation tasks and evidence gates.

## First delivery

Create a new world, select **Rolling Highlands (Experimental)** alongside UPLIFT and MULTI_IMPROVED, adjust four explicit controls, inspect its preview, save/reopen it, and explore recurring uplands, connected ridges/foothills, passes, and broad or confined river valleys. It must already have tributaries, coherent downstream water, and some useful local erosion. A terrain-only build with placeholder rivers is an internal milestone, not the first user delivery.

Start with one documented, validated reference preset and a bounded set of supported settings combinations. Prototype starting controls are 4096-block upland spacing, 160-block mountain height, 48-block foothill relief, and 256-block full dry valley-floor width. These are hypotheses to tune. Establish the reference continent settings, world height, sea level, channel settings and erosion settings during the initial baseline task; do not silently imply every existing preset works.

All four controls must be functional in the first delivery. Their usable ranges may initially be narrower than the proposed SPEC ranges. Validate those ranges and their interactions, display unsupported combinations clearly, and preserve superseded legacy settings when switching modes. Avoid an editor full of controls that secretly do nothing.

The option must be deterministic for supported inputs, independent of chunk order and cache state, and have bounded memory/work. Testing a fixed seed panel is evidence, not a seed allowlist. Unsolved domains must fail explicitly before publishing a partial plan; they cannot silently become ocean, lose rivers, or use legacy carving. Such failures remain defects to investigate, including during experimental exploration.

## Recommended architecture

Expose a new `ContinentType.ROLLING_HIGHLANDS` selection that dispatches a coordinated terrain/hydrology pipeline. Internally use a mode-owned regional sampler, immutable drainage plans, shared profiles and valley composition. Reuse Advanced continent/coast calculations only after validating their domain/outlet assumptions. A new terrain-category enum alone would not address river planning, water placement or the control contract.

Reuse existing noise primitives, terrain classifications, climate/biome integration and generation infrastructure. Give RH its own block-unit conversion, seed offsets, water metadata, profile solver, bounded plan cache and filter/ceiling ordering. Keep changes to shared components behind explicit mode branches. Do not repair unrelated legacy behavior while claiming legacy parity.

Use the candidate formulas in [ALGORITHMS.md](../../research/rolling-highlands/ALGORITHMS.md) as an initial implementation, not a frozen final visual design. The research probes verify isolated math and demonstrate failure modes; they do not validate Minecraft worlds.

## Must be present before the experimental handoff

- Recurring regional relief, connected mountain/foothill structure and passes; occasional open basins; both broad and confined valleys. Map and ground-level review across the fixed five-seed panel must show the intended pattern, with failures recorded.
- Terrain-aware tributary topology, real outlets, shared confluences, downhill profiles, bounded cuts, and coherent channel water after filters, surface generation and normal water updates. Implement the minimum lake/spill handling required by actual depressions.
- One authoritative RH water-height contract used by carving, surface water, sealing, wetlands and flow metadata. Correct Cell initialization/copy/reset and both runtime/preview callers.
- Basic erosion that preserves major forms and protects channels, with correct world units and preview sample spacing. It may need later visual tuning.
- Saved explicit settings/model identity; four usable controls; preview with the same plan; save/reopen and dedicated-server loading; precise coordinates before float truncation.
- Regression evidence for legacy modes, concurrency/cache/seam checks and measured resource use. Build both Fabric and NeoForge artifacts; perform generation/load smoke checks on both. Do deeper first exploration on the user's eventual target loader without making that choice block shared implementation.

## Defer until after first exploration

| Work | What is still required in the first build |
|---|---|
| Final aesthetic defaults, exact plain coverage, route prominence distributions and erosion calibration | Fixed routes/maps, visible recurring relief and a transparent defect list |
| Full proposed slider ranges and unusual height/continent combinations | A useful validated range for each control, explicit combination validation and headroom handling |
| Exact V3 superiority assessment | Capture current-checkout regression evidence; keep V3 identity pending |
| Advanced river meanders, detailed lake shore treatment, more elaborate slope drainage | Connected basic channels, minimum required lakes/spills, useful protected local detail |
| Adaptive/multiresolution drainage domains or generalized cross-domain routing | Validate the bounded candidate first; if shared ports are necessary even for the reference preset, implement them or reconsider the domain design before handoff |
| Exhaustive loader/settings/performance coverage and final editor polish | Both-loader smoke tests, hard resource limits, no deadlocks and practical measured generation performance |

Full tectonics, sediment conservation, new climate/biome/cave systems, existing-world conversion and global legacy fixes remain outside this feature.

## Delivery boundaries and decisions

1. **Internal proof:** actual FTF terrain → drainage → final blocks for a connected tributary system and ocean outlet, with diagnostic maps/profiles. Resolve ownership and profile feasibility first.
2. **Playable experiment:** complete the must-have list above and package reproducible worlds/presets/routes for exploration.
3. **Exploration iterations:** tune one cause at a time against the same seeds and routes; prioritize structural defects before aesthetics.
4. **Release candidate:** evaluate the full SPEC acceptance protocol, broaden supported combinations deliberately, and compare the captured V3 configuration.

User choice of an experimental delivery does not authorize silently weakening connected-river, determinism or legacy-isolation requirements. Numerical aesthetic/performance targets remain proposed until measured and explicitly accepted or revised with evidence.

Experimental algorithm changes can change terrain. Every output-affecting model change needs a saved model/version policy before distribution. Use fresh test worlds for changed generation; do not load an old experimental world under a new formula with the same identity and create seams. Unsupported old model versions must produce a clear error unless their implementation is retained.

Still needed later: the exact V3 seed/preset, generator jar or commit, and mod configuration. This blocks the V3 comparison, not the first implementation. There is no reliable calendar estimate yet: bounded outlet ownership and feasible water profiles are the two work items most likely to change the plan.
