# RH1 validation record

Date: 2026-10-27. Java 21, Minecraft 1.21.1, development server launches on loopback only. Experimental build `1.0.0014-rh1`.

## Automated tests

`./gradlew :common:test :fabric:build :neoforge:build --offline`

22 JUnit test cases pass. The individual cases include larger internal sweeps:

- 1,000 small tree-profile problems compared with a brute-force oracle; malformed graphs and nonfinite inputs rejected.
- 364 owner plans: 52 seeds (`0..49`, `8675309`, `-314159`) across seven continent-scale/upland-spacing pairs: `(1000,4096)`, `(1000,8192)`, `(3000,4096)`, `(4000,4096)`, `(4000,8192)`, `(5000,4096)`, `(5000,8192)`. Profiles solve and spatial-index budgets hold. This is sampled coverage, not a proof for every seed/control combination.
- Five seeds across three neighboring continent owners: real ocean roots, acyclic downstream topology, monotone profiles and bounded node counts.
- Five fixed 16,384-block terrain windows; every refined channel sample agrees with its generated water height. Diagnostic maps are generated under `common/build/rolling-highlands/seed-*.png`.
- 2,000 far/negative coordinate samples check coast ownership bounds; noise precision has a separate adjacent-coordinate check.
- Parallel/reversed request order, separate seeds and rebuilt owners preserve deterministic sampled results. Padded filtered chunks agree at their shared boundary.
- Mountain/foothill/valley-width variations, including minimum amplitude, preserve sampled channel water profiles. Valley cross-sections are continuous and monotone through their boundaries.
- Preset persistence, independent copies, invalid model/range/nonfinite/fractional rejection, water metadata copying/reset, and unchanged legacy enum ordinals.
- Frozen current-unstable UPLIFT/MULTI_IMPROVED terrain fingerprints for three seeds each remain identical. This does not establish equivalence to the unidentified V3 preset.

Both distributable jars contain the RH implementation and English editor translations and exclude test fixtures. `git diff --check` passes. Existing compiler deprecation warnings remain.

## Actual Minecraft blocks

Seed 0, default RH profile, exact center `(-1920, 11136)`, radius 1:

| Loader | FULL chunks | Wet columns checked | Failures |
|---|---:|---:|---:|
| Fabric | 9 | 903 | 0 |
| NeoForge | 9 | 903 | 0 |

Validation runs after surface generation/features and a 40-server-tick wait. It checks water or ice at the planned surface, a solid nonfluid bed, and no water above the planned surface. It does not prove long-duration fluid stability or check every dry bank column. Both servers shut down cleanly.

The small junction runs reported 5.33 chunks/s (Fabric) and 5.92 chunks/s (NeoForge). These are diagnostic runs on this machine, not release performance thresholds or a comparison with V3. The profiler excludes the validation wait. Generated reports live under each loader's `build/benchmark-run/perf-reports/`.

A second Fabric smoke at the lake-area junction `(6528,7040)` passed: nine FULL chunks, 1,310 wet columns, zero failures, clean shutdown. The final combined test/build/smoke command completed successfully; all 22 JUnit cases passed after the final terrain-influence seam fix.

## Reproduction

The tests export the default RH preset to `common/build/rolling-highlands/preset.json`. Make an override datapack with `pack.mcmeta` containing `{"pack":{"pack_format":48,"description":"Rolling Highlands experimental validation"}}` and that preset at `data/freeterraforged/freeterraforged/worldgen/preset/preset.json`. Pass its directory as `PACK` below; the benchmark task copies it into its disposable generated world.

```sh
./gradlew :fabric:runBenchmarkServer --offline \
  -PftfBenchmarkSeed=0 -PftfBenchmarkRadius=1 \
  -PftfBenchmarkX=-1920 -PftfBenchmarkZ=11136 \
  -PftfBenchmarkExact=true -PftfValidateHighlands=true \
  -PftfBenchmarkPresetPack="$PACK"
```

Replace `fabric` with `neoforge` for the second loader. Run the server checks sequentially because they share loopback port 25585. These tasks recreate their own `build/benchmark-run/world`; they are not commands for a player's world.

## Scope of the evidence

T0–T5 and the automated/build portion of T6 are implemented for the experimental delivery. Interactive client editor testing, ordinary player exploration, aesthetic acceptance, full performance envelopes, extended fluid simulation, and the exact V3 comparison remain open. T7 tuning and T8 release hardening are not claimed complete. See IMPLEMENTATION.md for the narrowed spacing range and the local bank/coastal transition refinements made during validation.
