# Roadmap: Structure placement

## Overview

Make Giant Natural Additions and Create: Structures Arise place correctly in FTF worlds (not floating, buried,
terrain-clearing or intersecting) without losing FTF terrain quality, valid structures or generation performance.

Source docs: [SPEC](../../features/structure-placement/SPEC.md) ·
[EDGE-COVERAGE](../../features/structure-placement/EDGE-COVERAGE.md) ·
[IMPLEMENTATION](../../features/structure-placement/IMPLEMENTATION.md) ·
[research](../../research/structure-placement/RESEARCH.md) (requirements SP-01…SP-12 in `requirements.json`).

Baselines (detached worktrees): pre-change `c5076eb8` (`ftf-structure-original`), perf control `744c2f20`
(`ftf-structure-perf-control`).

**Upstream:** candidate — general FTF correctness fix, likely upstreamable once performance and in-game acceptance hold.

## Phases

- [x] **Phase 1: Correct terrain sampling and fill bounds** - canonical heights, structure-aware fill (`c6630740`)
- [x] **Phase 2: Recover performance** - parallel region prep, reused erosion drainage segments (`00ac277d`, `3f47583d`)
- [ ] **Phase 3: Fit rigid structures to the ground** - ground fit + terrain blend, leave intentional set-ins alone
- [ ] **Phase 4: Acceptance** - full-modpack visual, functional and placement-density acceptance
- [ ] **Phase 5: Advanced placement profiles** - natural-template air, root-specific blending, optional foundations
- [ ] **Phase 6: Upstream PR**

## Phase Details

### Phase 1: Correct terrain sampling and fill bounds
**Status**: implemented on `feature/structure-placement`, integrated into `1_21_1-unstable-custom`. Correctness
candidate; see IMPLEMENTATION.md for measured results. **Upstream**: candidate.

### Phase 2: Recover performance
**Docs**: [PERFORMANCE](../../features/structure-placement/PERFORMANCE.md) ·
[PERFORMANCE-FOLLOWUP](../../features/structure-placement/PERFORMANCE-FOLLOWUP.md) · evidence under
`research/structure-placement/validation/`.
**Status**: integrated into `1_21_1-unstable-custom`. No reduction in erosion detail, sampling or structure checks
(locked decision). **Upstream**: candidate.

### Phase 3: Fit rigid structures to the ground
**Goal**: rigid templates sit on the ground and the terrain blends to them; structures deliberately set into the
ground are left alone.
**Depends on**: Phase 2
**Branch**: `fix/structure-ground-fit` (worktree `/private/tmp/ftf-structure-ground-fit`). This is an
upstream-shaped branch: 5 code-only commits on `upstream/1.21.1_unstable` (`efc7565b`) — rebased Phases 1–2
(`c6630740`, `00ac277d`, `3f47583d`) plus ground fit `33ff89e8` and set-in exception `8af97233`.
**Status**: both commits integrated into `1_21_1-unstable-custom` (`7d94ce42` = `33ff89e8`, `f46d33de` =
`8af97233`). No spec/verification doc yet — record one before closing.
**Plans**: TBD (verification + integration). **Upstream**: candidate.

### Phase 4: Acceptance
**Goal**: the SPEC acceptance protocol passes in the full modpack — fewer bad placements, no major loss of valid
structures, generation time within budget versus the perf control.
**Depends on**: Phase 3
**Plans**: TBD

### Phase 5: Advanced placement profiles
**Goal**: the proposed-but-disabled profiles from SPEC (natural-template air handling, root-specific blending,
optional foundations). Deferred until Phase 4 says they are needed.

### Phase 6: Upstream PR
**Goal**: PR against `upstream/1.21.1_unstable` with only structure-placement code commits.
**Depends on**: Phase 4
**Note**: `fix/structure-ground-fit` is already the right shape for this PR (rebase onto current upstream first).
