# Roadmap: Rolling Highlands

## Overview

A new experimental continent type with drainage-first terrain: recurring uplands, real mountain ranges, natural river
networks, lakes and coasts, built on FTF's legacy land. Each RH phase bumps the generation model when terrain changes
(new worlds only). Branch `feature/rolling-highlands`, merged periodically into `1_21_1-unstable-custom`.

Source docs: [SCOPE](../../features/rolling-highlands/SCOPE.md) · [SPEC](../../features/rolling-highlands/SPEC.md) ·
[IMPLEMENTATION](../../features/rolling-highlands/IMPLEMENTATION.md) · [BACKLOG](../../features/rolling-highlands/BACKLOG.md) ·
[research](../../research/rolling-highlands/).

**Upstream:** candidate. Not ready as-is (see BACKLOG "Upstream PR readiness"): discuss with maintainers first, then a
fresh branch from `upstream/1.21.1_unstable` with only RH code commits.

## Phases

- [x] **Phase 1: RH1 experimental slice** - playable Rolling Highlands preset, valleys, lakes, rivers (`3672fcae`)
- [x] **Phase 2: RH2 productionization** - river output parity with legacy FTF, model 2 (`bfcb0fd1`)
- [x] **Phase 3: RH3 FTF-faithful drainage-first terrain** - stream-power drainage, dam lakes, model 3 (`348d9a43`)
- [x] **Phase 4: RH4 natural river planform** - meanders, oxbows, junctions, model 4, release 1.0.0014-rh4 (`ee892191`)
- [x] **Phase 5: RH5 hierarchy, water biomes, lakes, ranges** - model 5, release 1.0.0015-rh5 (`7dfa204c`)
- [x] **Phase 6: RH6 honest ground, valley lakes, real ranges** - model 6, release 1.0.0016-rh6 (`10efe563`)
- [x] **Phase 7: RH7 dissected, glaciated ranges** - model 7, release 1.0.0017-rh7 (`2c625cb4`, tarn fix `0a6c0575`)
- [x] **Phase 8: RH8 parallel-ridge range belts** - `rangeRidges` setting, model 7 compatible (`9efd6617`)
- [x] **Phase 8.1: Raised-lake embankments (INSERTED)** - lakes resolve against real land (`21dda09f`)
- [ ] **Phase 9: RH9 a real coast** - beaches, bluffs, sea cliffs, coves, sea stacks; model 8
- [ ] **Phase 10: Upstream PR preparation** - RH-only branch on upstream unstable, CI-safe tests

## Phase Details

Phases 1–8 are complete; upstream status fork-only until Phase 10.

### Phase 1: RH1 experimental slice
**Goal**: a playable experimental preset with valleys, lakes and rivers. **Docs**: [SCOPE](../../features/rolling-highlands/SCOPE.md) · [PLAN](../../features/rolling-highlands/PLAN.md)

### Phase 2: RH2 productionization
**Goal**: river output parity with legacy FTF rivers. **Docs**: [PLAN-RH2](../../features/rolling-highlands/PLAN-RH2.md)

### Phase 3: RH3 FTF-faithful drainage-first terrain
**Goal**: legacy FTF land with stream-power drainage and dam lakes. **Docs**: [PLAN-RH3](../../features/rolling-highlands/PLAN-RH3.md) · [research](../../research/rolling-highlands/RESEARCH-RH3.md)

### Phase 4: RH4 natural river planform
**Goal**: meanders, oxbows, natural junctions and confluences. **Docs**: [PLAN-RH4](../../features/rolling-highlands/PLAN-RH4.md) · [research](../../research/rolling-highlands/RESEARCH-RH4.md)

### Phase 5: RH5 hierarchy, water biomes, lakes, ranges
**Goal**: river hierarchy, water biomes, tarns, first mountain ranges. **Docs**: [PLAN-RH5](../../features/rolling-highlands/PLAN-RH5.md)

### Phase 6: RH6 honest ground, valley lakes, real ranges
**Goal**: water-relative floors, confined valley lakes, ranges that own their relief. **Docs**: [SPEC-RH6](../../features/rolling-highlands/SPEC-RH6.md) · [PLAN-RH6](../../features/rolling-highlands/PLAN-RH6.md) · [VALIDATION](../../features/rolling-highlands/VALIDATION.md)

### Phase 7: RH7 dissected, glaciated ranges
**Goal**: drainage-carved ranges up to 640, glacial overprint, slope surfaces. **Docs**: [SPEC-RH7](../../features/rolling-highlands/SPEC-RH7.md) · [PLAN-RH7](../../features/rolling-highlands/PLAN-RH7.md)

### Phase 8: RH8 parallel-ridge range belts
**Goal**: ranges as belts of parallel ridges (`rangeRidges` 1–4). **Docs**: [SPEC-RH8](../../features/rolling-highlands/SPEC-RH8.md) · [PLAN-RH8](../../features/rolling-highlands/PLAN-RH8.md)

### Phase 8.1: Raised-lake embankments (INSERTED)
**Goal**: lakes sit in their real basins; no raised sheets or stone embankments (Test-America-v3-ROCKIES X≈10112).
**Docs**: [SPEC](../../issues/raised-lake-embankment/SPEC.md) · [PLAN](../../issues/raised-lake-embankment/PLAN.md) ·
[VERIFICATION](../../issues/raised-lake-embankment/VERIFICATION.md) · [research](../../research/raised-lake/README.md)
**Status**: code committed on `feature/rolling-highlands`; 22 targeted tests + 8 FULL checks passed; Fabric and
NeoForge candidates built. **Open:** cold-start performance, full-modpack visual acceptance. Not deployed.
**Upstream**: fork-only (part of RH).

### Phase 9: RH9 a real coast
**Goal**: RH coasts read as real coastlines: beaches, bluffs and sea cliffs chosen by a position-only coast profile,
coves and points, coastal materials, sea stacks. Generation model 8.
**Depends on**: Phase 8.1
**Docs**: [SPEC-RH9](../../features/rolling-highlands/SPEC-RH9.md) · [PLAN-RH9](../../features/rolling-highlands/PLAN-RH9.md)
**Success Criteria**: per SPEC-RH9; validated on both loaders and in-game before release.
**Plans** (PLAN-RH9 §2):
- [x] P0: coast probe and model-7 baseline (`3d4f135c`)
- [x] C: model 8, coast profile fields, coves/points (`69c47e3f`)
- [x] S: beaches, bluffs and sea cliffs (`470c6928`)
- [x] M: coastal rock and beach materials by climate (`4f86823b`)
- [x] X: sea stacks off cliff coasts (`7f739e24`, `50e3c9e3`)
- [x] V: tuning and release 1.0.0023-rh9 (`f63e8866`)
**Status**: all plans done, automated gates met, integrated (`515b25b6`). In-game UAT outstanding —
[09-VERIFICATION](phases/09-rh9-real-coast/09-VERIFICATION.md) (`human_needed`).
**Upstream**: fork-only (part of RH).

### Phase 10: Upstream PR preparation
**Goal**: an upstream-ready RH branch the maintainers have agreed to review.
**Depends on**: maintainer discussion; Phase 9 (or an agreed cut-off model).
**Success Criteria** (from BACKLOG "Upstream PR readiness"):
  1. Fresh branch from `upstream/1.21.1_unstable` with only RH code commits; no `.planning/`, no `mod_version` bump,
     no benchmark server-ip/port tweaks.
  2. JUnit dependency/`test {}` deduplicated against upstream; inline FQNs replaced by imports; internal phase-letter
     references removed from code.
  3. Flaky wall-clock assertion in `HighlandRangeTest.userRangesHaveEvenCrestsAndSpurs` fixed; slow RH tests gated so
     `:common:test` fits upstream's 15-minute CI job.
  4. Builds on all loaders and full common suite passes on the upstream base.
**Plans**: TBD

## Deferred (BACKLOG)
Islands and archipelagos · tarns keep the planned bowl in rendered ground ("option B").
