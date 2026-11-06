No problem @ETcodehome. I merged current `1.21.1_unstable` (up to `4dce6946`) into the branch; the conflicts were only in the test tooling.

**Merge changes:**
- The snow cover check uses the new runner flow: its own watchdog step, a `TestRecord`, and the name `presetValidation.snowCoverCompatibility.<loader>`. It runs after the new non-FTF world, mod compatibility shim and biome replacement checks.
- I registered that name in `LocalTestReportGenerator.BASE_TEST_TOKENS` and in `publish-verification.yml`.

**`runPresetTest` results** (macOS, Java 21, offline Gradle, report directory cleared first):
- `BUILD SUCCESSFUL`; both loaders exited 0.
- All 19 system tests passed on each loader, including the new locate tests and the snow cover check. The 20 unit tests passed too.
- `report.html` shows **✔ VERIFICATION PASSED**. The filters work, and the relief/raw thumbnails load.
- Fabric and NeoForge numbers were identical:
  - spawn span 105 / max step 5;
  - seam step 1 with 73984/73984 identical;
  - 256-block command parity;
  - preview parity over 144 samples with spawn at (1337, 67, -2048).

**Some notes on the report:**
1. **The header says "System Tests: 78 / 32 Passed".** Two things combine:
   - Each loader's `suite_summary` now also contains the 20 `unitTest.*` entries, so 40 unit-test entries are counted as system tests on top of the 38 real ones. The header also shows "Unit Tests: 20 / 20" separately.
   - `adaptiveBiomeLocate`, `biomeLocateLivePlan` and `terrainLocateLivePlan` aren't in `BASE_TEST_TOKENS`, so the denominator is 16 × 2 = 32.

   With the `>=` pass rule this doesn't fail, but the count is confusing. It could also hide a registered system test that never ran, since the unit-test entries keep the count high. `publish-verification.yml` still lists only the original 12 base tests, so the CI "missing expected test" check doesn't cover the newer ones either. Deriving the expected set in one shared place, or excluding `unitTest.*` from the system count, would help.
2. **Intermediate reports:** each loader's server also writes `report.html` when it finishes. After Fabric, the file briefly holds a Fabric-only report until NeoForge and `generatePresetTestReport` overwrite it. It's harmless, but it's confusing if opened mid-run.
3. **Old diagnostic files** (from reading the code, not observed, since I cleared the folder): suite summaries are deduplicated to the latest run per loader, but other `*_report.txt` files aren't, so repeated runs without a clean would pile up old diagnostics. `unit_tests_report.txt` also matches that filter, so it appears in the diagnostics list as well as the unit test table.

In-game testing is still in progress; I'll post those results separately.
