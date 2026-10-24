# Custom integration build

The `1_21_1-unstable-custom` checkout retains these integrations:

- Upstream unstable through `22c2801b` and native biome replacement PR #321 through `122eb761`, including preview hatching and replacement legend details.
- Custom parallel `/ftf locate terrain <terrain> [radius]` command (`7a41443d`).
- Climate integration API (`2228f6c5`) and restored double-precision `TemperatureShaping.applyNormalized` helper (`8a29a2b5`).
- Biome Replacer compatibility (`583a375d`) and wetland changes (`5ffa8490`).
- Matching beach/ocean control-point fix (`3b308c63`) and snow vegetation fix (`0853d942`).
- Climate shaping settings bound to their owning context (`590bc4c7`) and correct initialization of the coastal moisture slider (`edc27096`). These fixes retain the original cooling and moisture formulas.

## Discontinued latitude experiment

At the user's request, the native latitude experiment is removed: its settings, UI, generators, external-mod guards, diagnostic command and feature-only tests are gone. The regular temperature/moisture editor and the existing altitude cooling, lowland moisture boost and elevation drying remain.

The removal reverses feature commits `c6f98b83`, `dd0b1eda` and `45feb558` without resetting the branch or removing the other integrations. The prior implementation is recoverable on local archive branch `archive/native-latitude-c6f98b83`. Remaining research/specification files are historical, not active development plans.

This is not a migration of worlds generated with native latitude. Such worlds can have climate/biome borders in newly generated chunks under the restored generator. Use a new world for comparison testing. No existing saves, presets or unrelated mod configurations are changed by this cleanup.

## Validation

- Clean offline Fabric and NeoForge builds passed.
- Eleven external JUnit cases passed for native biome replacement, preview hatching and beach control points, using `/private/tmp/ftf-all-custom-tests.init.gradle`.
- 20,000 bit-exact double climate API comparisons passed against the previously installed `dd0b1eda` jar.
- Packaged artifacts retain the terrain locate command, biome replacement, snow helper and climate API; no native latitude classes or UI translations remain.
- The terrain locate command source is identical to the custom baseline. All retained integration commits remain ancestors.
- NeoForge SHA-256: `27daa13dc764f2f1242416ed3cb6c2794bb0a5f17780818eb9b41c058525a7a2`.
- Fabric SHA-256: `7ed6a53b136325a0fe2214320a775e63e61e8aa35604e60f3e7a0548530b768f`.

These checks do not replace in-game acceptance. Build log: `/private/tmp/ftf-drop-latitude-build.log`.
