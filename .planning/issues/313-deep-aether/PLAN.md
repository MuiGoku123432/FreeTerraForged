# Implementation plan — issue #313

Status: ready for reproduction and diagnosis; candidate patch requires the evidence gate below.
Scope authority: [SPEC.md](SPEC.md). Evidence: [RESEARCH.md](RESEARCH.md).

## 1. Establish the baseline and failing integration case

Requirements: DA-01, DA-02, DA-03, DA-06.

- Start a dedicated fix branch from freshly fetched upstream `1.21.1`, not the older local fork branch. Recheck whether the implicated code has changed since `1d628bc138f24edf1a01118d702675bc5dac9495`.
- Use Java 21 and a disposable test instance. Keep user worlds untouched.
- Build a minimal NeoForge 1.21.1 stack around the reported FTF, Aether, Deep Aether, and TerraBlender versions, with their required dependencies. Record exact loader, effective AeroBlender version, jar hashes, and configuration. Avoid duplicate bundled/external dependencies.
- Compare three isolated runs with the same seed/config: FTF absent, released/unpatched FTF, and subsequently the candidate build. If current upstream no longer reproduces, identify the fixing revision before proposing another patch.
- At a reproducible Deep Aether biome, capture F3/registry identity, coordinates, dimension, and `/locate biome` results. Discover a naturally generated Deep Aether structure in the control and record its registry ID and location.
- Export transformed `MultiNoiseBiomeSource`/`BiomeSource` classes or use a debugger/test-only probe. Check that `ftf$bypassInlinePossibleBiomesCrash` actually executes on the runtime Aether source. Observe raw parameter keys versus appended supplier keys versus public `possibleBiomes()` results after initialization. Avoid premature observations before AeroBlender initialization completes.

**Evidence gate:** proceed with the preview-ownership patch only if the appended set contains a missing Deep Aether biome and the FTF bypass demonstrably removes it from the public result. If the hook is absent/inactive, or registration itself is missing, revise the diagnosis and plan. Do not claim success from reproducing only an artificial Java predicate.

Deliverable: a concise reproduction record with commands, manifest, seed, logs, and observed sets. Mark any unavailable setup explicitly.

## 2. Implement a preview-owned bypass

Requirements: DA-01, DA-04, DA-05.

Expected production edits, relative to repository root:

| File | Intended change |
|---|---|
| `common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/FTFMultiNoiseBiomeSource.java` | Add a narrowly named internal bridge operation for marking a newly constructed preview copy. Check all implementers and consumers first; prefer a small separate marker bridge if changing this interface would break integrations. |
| `common/src/main/java/etcodehome/freeterraforged/mixin/MixinMultiNoiseBiomeSource.java` | Add a unique instance flag, default false. Implement the marker operation. Let live/unmarked sources fall through without setting a return value. Preserve the existing null/inline guarded fallback for marked preview sources. Correct the misleading runtime/inline comment. |
| `common/src/main/java/etcodehome/freeterraforged/world/worldgen/biome/BiomePreviewResolver.java` | In `copyBiomeSource`, mark only the newly allocated multi-noise copy before returning it. Leave the branch returning the original non-multi-noise source untouched. Ensure marking precedes preview generator construction and integration initialization. |

- Keep the marker private to source ownership: no global singleton, dimension blacklist, mutable shared registry set, or per-query toggle.
- Cached preview copies retain their designation; runtime sources never receive it. Check any clone/reuse path that could transfer ownership unexpectedly.
- Do not change biome sampling, terrain, weights, structures, or exception handling beyond what is necessary for this bug.
- Use the repository's Gortex impact/signature/edit/check flow if indexing becomes available; otherwise retain the documented untracked-checkout fallback. No tracking/config changes are part of this issue.

Deliverable: a small patch which changes live enumeration behavior only where the old preview bypass was incorrectly active.

## 3. Verify behavior in transformed runtime code

Requirements: DA-01 through DA-06.

Prefer an existing mod integration harness if available; otherwise use a disposable launched instance/test-only diagnostic mod. Plain JUnit tests do not apply Mixins automatically. Do not add a production framework or a helper solely to test a boolean.

| Case | Required observation |
|---|---|
| Live inline source with a deliberately appended biome | Public enumeration retains the addition; fails before the patch and passes afterward. |
| Two sources sharing the same raw parameters, only one preview-owned | Preview handling is instance-specific; the live source remains unaffected. |
| FTF-created preview copy | Marked before first use and continues to enumerate safely. |
| Registered/right-side source | Existing behavior remains unchanged. |
| Non-multi-noise source returned by preview fallback | Original runtime object never gains preview ownership. |
| Preview cache reuse, seed changes, and reopening | Correct ownership persists without leaking into world generation. |
| Biolith/Lithostitched preview stack | Original casting failure does not return; expected modded preview output remains present. |
| Exact Aether/Deep Aether stack | Biome lookup succeeds and representative structures generate naturally in fresh chunks. |

Run relevant common tests and then `./gradlew build` using Java 21 for Fabric and NeoForge outputs. Run targeted loader smoke tests to detect missing optional injection targets or class transformation failures. Add maintainable regression tests to the project's supported harness where feasible, with a manual integration procedure for checks that cannot be automated there.

For structure verification, inspect generated structure starts/blocks, not only `/locate` output or `/place structure`. A restored biome set proves eligibility, not full placement correctness.

## 4. Review and prepare the contribution

Requirements: DA-01 through DA-06.

- Review the final diff for live-source mutation, initialization order, interface compatibility, and unexpected behavior on Fabric.
- Record every acceptance criterion as PASS, FAIL, or NOT RUN with supporting evidence. Any required NOT RUN means integration verification is incomplete.
- Draft the PR around the actual observed before/after behavior and exact target branch. Distinguish code inspection from executed tests.
- Mention the original preview workaround and why it remains scoped to preview-owned instances.
- Do not advertise an unstable fix unless a separate reproduction and implementation establish one.
- Keep these local planning documents out of the eventual upstream code PR unless repository conventions call for them.

## Risk and rollback

The main regression risk is reintroducing the original preview crash or marking a shared live source. The patch is reversible by reverting its focused commit; it should require no save migration. Existing generated chunks will not acquire missing structures automatically.

## Plan coverage review

All six requirements have explicit implementation or verification steps. The main unresolved dependency is runtime reproduction, which is the first task and an explicit condition on the proposed implementation. Static evidence supports a candidate, not a verified fix. This plan received an inline coverage review; no independent agent review or full GSD phase validation was performed.
