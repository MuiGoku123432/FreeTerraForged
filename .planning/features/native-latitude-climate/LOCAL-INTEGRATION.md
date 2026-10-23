# Combined custom build for F&F Terra

## Retained and restored work

The integration starts from `3a3592ed` (native latitude plus upstream PR #321).
The existing custom baseline `43244515`, climate API `2228f6c5`, Biome Replacer
compatibility `583a375d`, and parallel terrain locate command `7a41443d` remain
ancestors. Upstream unstable `22c2801b` and PR #321 head `122eb761` remain included.

The additional local branches were merged without conflicts:

- `3b308c63`: finite beach biome sampling when ocean/beach control points coincide.
- `0853d942`: worldgen snow coverage for tagged small plants, scoped to FTF generation.

The wetland branch `5ffa8490` was already included. Inspection of the original
checkout's uncommitted climate API changes found its raw sampling methods already
present, but `TemperatureShaping.applyNormalized` was missing. That double-precision
helper is restored verbatim, preserving the older installed integration's arithmetic.
Native climate continues using its float helper and owner-specific shaping strengths.
The original checkout and its legacy patch-build scripts/evidence are untouched;
its old `1.0.0007-climate-api.1` version suffix is not applied to this newer full build.

## Validation

- `./gradlew :common:latitudeTest :fabric:build :neoforge:build --offline --console=plain`
  passed on the combined sources, including 212,612 latitude checks.
- Eleven external JUnit regression cases passed: eight PR #321 replacement/preview
  cases and three beach-control-point cases (the prior JUnit 4 fixture adapted to JUnit 5).
- 20,000 bit-exact comparisons of `applyNormalized` passed against the previously
  installed `freeterraforged-1.0.0010-fnf-all-local.1.jar` using isolated class loaders.
- The prior snow probe passed 34 assertions against the current compiled helper
  and bundled tag. A separate direct mixin-handler probe could not run with the
  plain Minecraft test classpath because `NoiseBasedChunkGenerator` is final there;
  loader-applied access widening and in-game mixin validation remain unverified.
- Both loader jars build successfully. This is not a completed in-game acceptance run.

External checks and logs are under `/private/tmp/ftf-all-custom-*` and
`/private/tmp/ftf-custom-snow-check`; no external fixture is packaged in the mod.

## Test deployment

Target: Prism Launcher **F&F Server - Terra**, Minecraft 1.21.1 / NeoForge 21.1.257.
Install the remapped NeoForge jar, retain a checksum-verified backup of the old FTF
jar, and reversibly disable TerraLatitude and Natural Temperature for native testing.
The instance's other mods, configs and saves remain untouched. A deployment receipt
beside the instance backup records exact paths, checksums, source commit and rollback.

Create a **new FTF test world**, enable **Native latitude climate** in the climate
editor, and inspect the temperature/moisture and biome preview layers. Native latitude
is off by default. Existing TerraLatitude-profile worlds require their original
TerraLatitude/Natural Temperature setup; this deployment does not migrate them.
