# Custom integration build

The `1_21_1-unstable-custom` checkout retains these integrations:

- Upstream unstable through `bff48ad1` (mod version `1.0.0014`) and native biome replacement PR #321 through `122eb761`, including preview hatching and replacement legend details.
- Custom parallel `/ftf locate terrain <terrain> [radius]` command (`7a41443d`).
- Climate integration API (`2228f6c5`) and restored double-precision `TemperatureShaping.applyNormalized` helper (`8a29a2b5`).
- Biome Replacer compatibility (`583a375d`) and wetland changes (`5ffa8490`).
- Matching beach/ocean control-point fix (`3b308c63`) and snow vegetation fix (`0853d942`).
- Climate shaping settings bound to their owning context (`590bc4c7`) and correct initialization of the coastal moisture slider (`edc27096`). These fixes retain the original cooling and moisture formulas.

## Discontinued latitude experiment

At the user's request, the native latitude experiment is removed: its settings, UI, generators, external-mod guards, diagnostic command and feature-only tests are gone. The regular temperature/moisture editor and the existing altitude cooling, lowland moisture boost and elevation drying remain.

The removal reverses feature commits `c6f98b83`, `dd0b1eda` and `45feb558` without resetting the branch or removing the other integrations. The prior implementation is recoverable on local archive branch `archive/native-latitude-c6f98b83`. Remaining research/specification files are historical, not active development plans.

This is not a migration of worlds generated with native latitude. Such worlds can have climate/biome borders in newly generated chunks under the restored generator. Use a new world for comparison testing. No existing saves, presets or unrelated mod configurations are changed by this cleanup.

## Upstream update: 2026-10-24

Merged [PR #325](https://github.com/ETcodehome/FreeTerraForged/pull/325) and [PR #326](https://github.com/ETcodehome/FreeTerraForged/pull/326), advancing the upstream baseline from `22c2801b` to `bff48ad1`.

- Replaced tile generation/caching with raw and filtered chunk caches, regional filter grids, and the new preview grid.
- Added surface-rule/strata optimizations, sky-density skipping, performance tracking, and dedicated-server benchmark tasks.
- Added upstream `/ftf perf` and `/ftf benchmark` alongside the retained `/ftf locate terrain` and `/ftf heightmap` commands.
- Included upstream fixes for river-cache ownership, cave/surface envelope determinism, surface rules, NeoForge world-preset registration, datapack format metadata, and accidental Windows-only launch arguments.
- Resolved four source conflicts by retaining PR #321 replacement preview data and the custom climate API while adopting upstream's preview grid and terrain-only climate sampling optimization.
- Native latitude remains removed. Original temperature/moisture formulas and the independent owner/slider fixes remain.

This is a substantial upstream generation change. Validation below is an integration smoke test, not a guarantee of identical terrain across old and new versions or of every modpack combination.

## Validation

- Clean compilation followed by successful offline Fabric and NeoForge builds.
- Fourteen external JUnit cases passed: the eleven replacement/preview/beach checks plus frozen-baseline climate parity, terrain-only climate bypass, and command registration in both orders.
- Climate parity covers 1,200 positions across four seeds, retained regional raw samples, owner isolation, and seed sequence.
- 20,000 bit-exact double climate API comparisons passed against the previously installed `e8a78484` jar.
- Packaged artifacts retain terrain locate, biome replacement, snow helper and climate API, include the new caches/profiling classes, and exclude native latitude and test fixtures.
- The terrain locate command, double climate helper and snow mixin sources are unchanged from the prior custom build.
- Both NeoForge and Fabric dedicated-server smoke tests generated 9/9 requested FULL chunks and shut down cleanly; no runtime errors were reported in either smoke log.
- NeoForge SHA-256: `1bee61bef338f24cca7f99421f48b0a01ec7aa06d6d17b639f746521f26d9af1`.
- Fabric SHA-256: `a59ce2390db100da217545c7370e50850383f19bf7a9d48a19bb375982a0a6df`.

External test fixtures and Gradle init scripts are under `/private/tmp/ftf-upstream-merge-tests*`; build/smoke logs are `/private/tmp/ftf-upstream-bff48ad1-build.log`, `/private/tmp/ftf-upstream-neoforge-smoke.log`, and `/private/tmp/ftf-upstream-fabric-smoke.log`. Benchmark worlds are isolated under each loader's `build/benchmark-run` and bind only to loopback. Full-pack client preview and gameplay acceptance remain to be checked.
