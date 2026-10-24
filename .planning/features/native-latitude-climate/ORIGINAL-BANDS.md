# Configurable original temperature bands

Decision: 2026-10-24. The user prefers the original FTF temperature shape and wants configuration rather than replacement with a smooth latitude overlay. Restore the original signed sine-power curve and both existing Perlin temperature warp stages as the configured source. Keep all saved v1/v2 models intact; select v3 for new profiles, native disabled by default.

## Behavior

- Original FTF temperature bands selects v3; turning it off selects smooth v1/v2. Repeat toggling stays within the chosen family. JSON/NBT retain all four exact model identities.
- Equator Z shifts the configured temperature field. Pole distance replaces its scale, stretching both curve and temperature warps. Existing Falloff and temperature seed offset remain effective at full influence.
- At influence 1, only the configured original-style field contributes. At 0, original unconfigured generation is exact. Intermediate influence blends them; 0.65 remains a choice rather than a required correction.
- Equator and pole locations are nominal. Temperature warps, biome warps, edge displacement and terrain can move boundaries. Repeating means the broad latitude cycle repeats; the Perlin distortion does not tile identically.
- The original temperature noise graph and raw climate APIs remain unchanged. No extra root seed draws, terrain queries, world migration or moisture corrections are introduced.
- Temperature targets retain double geographic precision before the legacy float sine lookup. Existing noise warps remain float-based, as in FTF; tiny pole distances can alias at distant coordinates. These settings are accepted but not a promise of attractive biome maps.
- Diagnostics report the actual warped temperature target used by generation, then its single range mapping, influence blend and terrain shaping. Geographic fraction/moisture remain unwarped.

## UI and testing

Climate Settings: enable Native latitude climate and Original FTF temperature bands; choose equator and pole distance; start temperature influence at 1.0. Use Temperature and Biome previews. Saved smooth profiles remain smooth until explicitly switched in a new-world preset.

Validation: `./gradlew :common:latitudeTest :fabric:build :neoforge:build --offline --console=plain` passed with **241,318 checks**. Both remapped jars contain the new source class/UI translations and exclude test fixtures. The integer Heightmap path also preserves odd block coordinates near Z=30,000,000.

NeoForge SHA-256: `ab0710ac4487647404fa80790abbffe7d109f9cb67afe62596bfd935056d4a79`. Fabric SHA-256: `72a0e802a76abaf6fc1e46e312baa59e8ddbc9fa6174c87cc9e12c915fdaafce`. Offline tests preserve the existing v1/v2 suite and compare the new source against the original production noise graph at equivalent coordinates. They cover shape/warp composition, falloff, equator translation, scale stretching, all model codecs, disabled/zero-strength identity, seed sequence, raw API semantics, independent moisture, range application and diagnostic/application equality at world limits.

## Follow-up realism work

Altitude cooling stays in place with its existing formula. A separate versioned cooling change should address the hottest values resisting the current power-based cooling and be tested against biome outcomes. Moisture keeps its independent native latitude influence and existing regional/coastal/elevation shaping. Actual ocean-distance influence is a possible next incremental step; physical moisture transport and directional rain shadows need a separate design and performance budget. The latitude-window proposal has not been adopted.

This build remains a draft. In-game comparative maps, save/reopen/server acceptance and measured performance are still required before declaring a stable generation model.
