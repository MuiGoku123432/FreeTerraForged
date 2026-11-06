# Roadmap: Upstream fixes

## Overview

Small fixes and compat work aimed at upstream FreeTerraForged, one branch and one PR per issue. Each is also carried
in `1_21_1-unstable-custom` (see [docs/custom-build.md](../../../docs/custom-build.md)) while the PR is open. Phases
here are independent; numbering is order of intake, not dependency.

Already done upstream: #292 locate terrain (merged), #290 rewrapped structure sets (merged), #273 height-limit
warning (merged), #272 wetland water level (merged). Closed without merge: #291 Biome Replacer compat, #271 Streams
Reflowing compat.

## Phases

- [ ] **Phase 1: #284 snow gaps around plants** - PR #323 open (reworked after review)
- [ ] **Phase 2: #282 beach/ocean control-point previews** - PR #322 open
- [ ] **Phase 3: Climate integration API** - PR #301 open
- [ ] **Phase 4: #313 Deep Aether biome enumeration** - groomed; needs runtime reproduction

## Phase Details

### Phase 1: #284 snow gaps around plants
**Branch**: `fixSnowVegetation` (worktree `$TMPDIR/opencode/ftf-pr323`, prunable) → `upstream/1.21.1_unstable`.
**Docs**: [SPEC](../../issues/284-snow-vegetation/SPEC.md) · [RESEARCH](../../issues/284-snow-vegetation/RESEARCH.md) ·
[PR description](../../issues/284-snow-vegetation/PR-DESCRIPTION.md) · replies
([1](../../issues/284-snow-vegetation/REVIEW-REPLY.md), [2](../../issues/284-snow-vegetation/REPLY-2-MERGE-AND-REPORT.md)).
**Status**: opt-in "Snow Covers Small Plants" inside `DecorateSnowFeature`; `runPresetTest` passes on both loaders;
upstream unstable merged in. **Open:** in-game verification (SPEC §7 steps 2–3), maintainer re-review.
**Upstream**: PR #323 open.

### Phase 2: #282 beach/ocean control-point previews
**Branch**: `fixBeachControlPointsPreview`. In custom build as `3b308c63`. No planning doc yet.
**Upstream**: PR #322 open.

### Phase 3: Climate integration API
**Branch**: `climateIntegrationApi` (`2228f6c5`). Optional climate helpers for integration mods; in custom build.
**Upstream**: PR #301 open.

### Phase 4: #313 Deep Aether biome enumeration
**Goal**: preserve live Aether biome enumeration (suspect mixin bypass).
**Docs**: [SPEC](../../issues/313-deep-aether/SPEC.md) · [RESEARCH](../../issues/313-deep-aether/RESEARCH.md) ·
[PLAN](../../issues/313-deep-aether/PLAN.md).
**Status**: static investigation only; implementation is conditional on a runtime reproduction. Target: fresh branch
from `upstream/1.21.1` (stable).
**Upstream**: candidate.
