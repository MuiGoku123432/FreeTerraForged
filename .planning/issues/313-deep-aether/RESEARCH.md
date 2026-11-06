# Evidence for issue #313

Date: 2026-10-21. Static investigation only; no game launch, patched build, or end-to-end reproduction has been performed.

## Repository provenance

- Working checkout: `1.21.1`, clean before planning, HEAD `c5d322f5e44539ca6d3a6059c5de440af9abcfe6`.
- Current upstream stable: `1d628bc138f24edf1a01118d702675bc5dac9495`.
- The suspect mixin was read directly from that upstream commit and contains the same bypass.
- Local `BiomePreviewResolver.java` and `FTFMultiNoiseBiomeSource.java` match upstream stable blobs `394897986486acea7319524ec9ba9b923cce70cf` and `148a56a008c0aff6d8d21fcdb4820799e504bcfa` respectively.
- Gortex returned `repo_not_tracked` for this checkout. Investigation used local source/history and GitHub APIs; no graph-backed verification was possible.
- The GSD phase initializer reported no roadmap/planning phase. These are standalone issue-planning documents, not a completed GSD phase or agent-reviewed plan.

## Confirmed source facts

### E1 — FTF uses representation as a proxy for preview ownership

[`MixinMultiNoiseBiomeSource.java`, lines 129–156](https://github.com/ETcodehome/FreeTerraForged/blob/1d628bc138f24edf1a01118d702675bc5dac9495/common/src/main/java/etcodehome/freeterraforged/mixin/MixinMultiNoiseBiomeSource.java#L129): the cancellable HEAD injection checks only that `parameters.left()` exists. It collects holders from the raw parameter list and sets that as the method return value. There is no preview-instance, dimension, or lifecycle ownership check. The comment assumes runtime sources use the other representation.

This is a concrete scope defect in the guard. Whether it is the executed cause in the reporter's exact installation still requires runtime proof.

### E2 — Aether is a counterexample to that assumption

[Aether dimension definition](https://github.com/The-Aether-Team/The-Aether/blob/1.21.1-develop/src/generated/resources/data/aether/dimension/the_aether.json) uses `minecraft:multi_noise` with an explicit `biomes` array. It is a real dimension, not an FTF preview. The inspected array contains Aether's base biomes.

Version caveat: this is the official 1.21.1 development branch, not a binary inspection of the reporter's release jar.

### E3 — AeroBlender appends biome registrations separately

[`LevelUtilsMixin.initializeAetherBiomes`](https://github.com/RazorDevs/Aeroblender/blob/1.21.0-1.0/src/main/java/io/github/razordevs/aeroblender/mixin/LevelUtilsMixin.java) initializes Aether's regional parameter extension, gathers regional biome holders, and calls `IExtendedBiomeSource.appendDeferredBiomesList`.

[`TerraBlender MixinBiomeSource.appendDeferredBiomesList`](https://github.com/Glitchfiend/TerraBlender/blob/1.21.1/Common/src/main/java/terrablender/mixin/MixinBiomeSource.java) reads the existing possible-biome supplier, appends the additions, then replaces the supplier with a deduplicated set. The authoritative set therefore need not equal the raw inline parameter entries.

Version caveat: these are official branch sources. Verify the installed AeroBlender and TerraBlender artifacts during reproduction, especially because the issue names TerraBlender 4.1.0.8.

### E4 — Preview protection was intentional

[Commit `97fd254b`](https://github.com/ETcodehome/FreeTerraForged/commit/97fd254b70662417aec87d61f52f316722b2a653), “Working Litho previews With Biolith,” introduced the broader bypass. Its comment describes a Biolith-related ClassCastException during biome lookup.

[Commit `d993d930`](https://github.com/ETcodehome/FreeTerraForged/commit/d993d930fa787e643df7a36355e5d7b1d7a541b6), “Properly fixed,” restricted it to inline lists. Neither inspected diff provides a reproducible crash fixture. Removing the guard outright is not yet supported by regression evidence.

### E5 — A narrow preview ownership boundary exists

[`BiomePreviewResolver.copyBiomeSource`](https://github.com/ETcodehome/FreeTerraForged/blob/1d628bc138f24edf1a01118d702675bc5dac9495/common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/BiomePreviewResolver.java#L295) creates a new inline multi-noise source using a copied parameter list. `create` then constructs the preview generator and initializes integrations. This provides a place to mark the new source before it is used.

The non-multi-noise fallback returns the original source, so marking must occur inside the branch that actually constructs a copy. A blanket mark at the caller would risk modifying runtime state.

[`FTFMultiNoiseBiomeSource`](https://github.com/ETcodehome/FreeTerraForged/blob/1d628bc138f24edf1a01118d702675bc5dac9495/common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/FTFMultiNoiseBiomeSource.java) is an existing bridge into the mixin. Its current sole method unwraps parameters.

### E6 — The reported dependency combination is plausible

[Deep Aether's 1.21.1 build properties](https://github.com/RazorDevs/Deep-Aether/blob/1.21.1-1.1/gradle.properties) identify version 1.1.5.1 and Aether 1.21.1-1.5.10, matching the report, and declare included AeroBlender 1.21.1-1.0.0-neoforge and TerraBlender 1.21.1-4.1.0.3. The latter differs from the reporter's explicitly installed TerraBlender 4.1.0.8, so the effective dependency resolution matters.

### E7 — This is specifically a stable-branch candidate

The upstream unstable tree inspected on this date has neither `MixinMultiNoiseBiomeSource.java` nor `FTFMultiNoiseBiomeSource.java`. Its preview resolver uses runtime plan compilation and request-owned source abstractions. A stable patch cannot simply be cherry-picked into those missing classes.

## Causal inference

If the FTF injection executes on the live Aether source, E1 returns the original inline set and bypasses the separately expanded supplier in E3. E2 shows why Aether qualifies for the faulty guard. That predicts biomes which can be sampled/generated yet are absent from enumeration, consistent with the reporter's lookup and structure symptoms.

Confidence: high in the source-level mismatch; medium in the exact end-to-end diagnosis until execution is observed. Do not report the bug as independently reproduced.

## Candidate solution

Make the bypass opt-in for FTF-created preview copies, using per-instance state rather than `Either.left` alone. Default every source to normal runtime enumeration. Mark a newly constructed preview copy before creating its generator or invoking integration initialization, and retain the inline/null checks within the preview-only branch.

Expected production footprint: three existing files (mixin, bridge interface, preview resolver). Test/instrumentation files depend on the available mod-launch harness. No new Aether dependency is necessary for production code.

Alternative approaches:

| Approach | Assessment |
|---|---|
| Remove the injection | Smaller, but may reintroduce the documented preview crash; only prefer if tests establish that the workaround is obsolete. |
| Union the raw list with extra registry entries | Risks admitting unrelated biomes and bypassing other mods' removals; does not restore ownership of enumeration. |
| Special-case Aether/Deep Aether | Narrowly hides the representation/ownership mistake and leaves other inline dimensions exposed. |
| Use a thread-local “preview active” flag | Less reliable than instance ownership because sources are cached and preview work spans lifecycle stages and worker threads. Existing `PreviewBiomeQueryContext` is a per-sample handoff, not source ownership. |

## Evidence still required

1. A reproducible original failure with exact jars, seed, coordinates, loader, and dependency manifest.
2. A transformed-class export or debugger observation showing the optional injection is installed and runs on the live Aether source. `require = 0` can conceal a missing target; do not interpret source presence as proof of execution.
3. Before/after comparison of raw inline keys, the authoritative appended set, and the public method return at the correct initialization phase.
4. A passing candidate-patch run for biome lookup and natural structure starts.
5. A passing reproduction of the Biolith/Lithostitched preview scenario the guard originally protected.

If item 2 fails or the appended set already lacks Deep Aether entries, investigate the actual active path before changing this guard. The candidate is not an unconditional implementation mandate.
