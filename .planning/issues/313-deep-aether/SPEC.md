# Issue #313 — Preserve live Aether biome enumeration

Status: groomed; implementation is conditional on runtime reproduction.
Date: 2026-10-21
Issue: https://github.com/ETcodehome/FreeTerraForged/issues/313
Target: upstream `1.21.1` (stable), inspected at `1d628bc138f24edf1a01118d702675bc5dac9495`.

## User story

As a player using FreeTerraForged with Aether and Deep Aether, I want Deep Aether biomes that generate in the Aether to remain discoverable and eligible for their natural structures, so installing FTF does not silently disable another dimension's content.

## Reported failure

The reporter uses FTF 1.0.0, Deep Aether 1.21.1-1.1.5.1, Aether 1.21.1-1.5.10, and TerraBlender 4.1.0.8. They report immediate `/locate biome` failure even while standing in `deep_aether:aerlavender_fields`, missing Deep Aether structures, and successful lookup/generation in a new world without FTF. These are reporter observations, not an independently reproduced result.

## Requirements and acceptance criteria

| ID | Requirement | Observable acceptance |
|---|---|---|
| DA-01 | Live biome sources retain the authoritative biome set supplied by the normal Minecraft/mod integration path. An inline parameter list alone does not authorize preview behavior. | After Aether initialization, its `possibleBiomes()` includes Deep Aether additions confirmed present in the control run. A synthetic live inline source also retains a deliberately appended biome absent from its original list. |
| DA-02 | Biome lookup works for Deep Aether biomes actually present in the test world. | At a recorded position in `deep_aether:aerlavender_fields`, `/locate biome deep_aether:aerlavender_fields` succeeds. Record dimension, seed, position, result, and full mod versions. |
| DA-03 | Deep Aether structures remain eligible and generate naturally. | With matched seed/config and fresh chunks, a representative structure known to generate in the FTF-disabled control can be located and its generated start/blocks verified in the patched world. Record the structure's actual registry ID. `/place structure` alone does not satisfy this criterion. |
| DA-04 | Existing FTF preview crash protection remains effective. | Open, refresh, change seed/preset, and reopen previews in base FTF and the Biolith/Lithostitched combination associated with the original workaround. No original casting crash; expected modded preview biomes remain available. |
| DA-05 | Preview ownership never leaks into runtime sources. | Only independently created FTF preview sources receive preview behavior; sharing, caching, and non-multi-noise fallback paths do not mark a live source. Live lookup remains correct before and after preview use. |
| DA-06 | The common-code change remains compatible with supported loaders and ordinary worlds. | Relevant automated tests and Fabric/NeoForge builds pass. Smoke-test ordinary Overworld, Nether, End, and an unmodified inline source; no new mixin errors. |

## Scope

- Diagnose the stable-branch preview bypass and preserve runtime biome enumeration.
- Prefer a generic distinction between FTF preview-owned sources and live sources.
- Preserve the preview workaround until its original failure has been tested.
- Add behavior-focused regression coverage and a reproducible mod integration test record.
- Validate with new worlds or fresh chunks; restoring structures in already generated chunks is outside scope.

## Exclusions

- No Aether-specific biome whitelist, changes to region registration, biome weights, terrain, or structure placement rules.
- No rewriting the biome planner or solving issue #310 in this patch.
- No automatic unstable port: current unstable no longer contains the implicated mixin or interface and uses a different preview architecture.
- No upstream issue comments or PR submission as part of this planning task.

## Constraints and open questions

- The exact reported release jars and effective mixin transformation must be checked. The optional injection uses `require = 0`; finding its source code does not prove it executes in every mod stack.
- The old crash is documented in Git history but has not been independently reproduced here.
- The reporter did not provide a seed, coordinates, complete dependency manifest, or an AeroBlender version. Deep Aether's inspected build declares an included AeroBlender dependency; record the actual resolved runtime version.
- A narrow patch should restore the normal mod-provided enumeration, not approximate it by unioning arbitrary registry biomes.
- Do not treat successful compilation or a pure predicate unit test as proof of mixin integration or structure generation.

## Readiness

The desired behavior and boundaries are clear enough to plan. Source evidence supports a specific failure mechanism and candidate fix. The first implementation milestone is a failing runtime reproduction; the solution is not yet verified.

See [RESEARCH.md](RESEARCH.md) for evidence and [PLAN.md](PLAN.md) for implementation and validation steps.
