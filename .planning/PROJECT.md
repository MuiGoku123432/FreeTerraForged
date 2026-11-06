# Project: FreeTerraForged — custom fork

## What this is

A personal fork (`origin` = MuiGoku123432/FreeTerraForged) of FreeTerraForged (`upstream` = ETcodehome/FreeTerraForged),
built and played as a custom integration build for the F&F modpack. Work lands in the fork first. Most of it is meant
to go upstream **eventually**, but not straight away: a feature is proven in the fork (in-game, on both loaders) before
an upstream PR is cut. Some work is fork-only by design.

## Branch model

| Branch | Role |
|---|---|
| `upstream/1.21.1` | Upstream stable. Base for stable-targeted fixes (e.g. #313). |
| `upstream/1.21.1_unstable` | Upstream development. Base for most upstream PRs. |
| `1_21_1-unstable-custom` | **Fork integration branch.** What gets built and installed. Feature branches merge into it. **Canonical home of GSD state.** |
| `feature/*`, `fix/*` | One branch per piece of work, usually in its own worktree. |
| `<name>` (no prefix) | Upstream PR branches cut fresh from an upstream base (`fixSnowVegetation`, `fixBeachControlPointsPreview`, …). |
| `archive/*` | Discontinued work kept for recovery. |

## Planning conventions

- `.planning/` is committed. Feature docs (`features/<name>/`, `issues/<n>-<slug>/`, `research/<name>/`) live with
  the code on their feature branch and reach `1_21_1-unstable-custom` when that branch is merged in.
- GSD state (`PROJECT.md`, `workstreams/*/ROADMAP.md`, `workstreams/*/STATE.md`) is edited **only on
  `1_21_1-unstable-custom`** (or a short-lived `planning/*` branch fast-forwarded into it). Feature branches never
  touch `workstreams/`, so integration merges never conflict on state.
- A session working in a feature worktree sets `GSD_WORKSTREAM=<workstream>` (or passes `--ws <workstream>`) and
  records progress in the feature's own docs; the roadmap/state rollup is done on the integration branch.
- Upstream PRs never carry `.planning/`: cut them with `/gsd-pr-branch` (or a fresh branch from the upstream base
  with only the feature's code commits).
- Every phase records an **Upstream** status: `fork-only` · `candidate` (intended, not ready) · `PR #N open` ·
  `merged` · `declined`.

## Workstreams

| Workstream | Branch(es) | Upstream | Status |
|---|---|---|---|
| [rolling-highlands](workstreams/rolling-highlands/ROADMAP.md) | `feature/rolling-highlands` | candidate (maintainer discussion first) | RH9 coast in progress |
| [structure-placement](workstreams/structure-placement/ROADMAP.md) | `feature/structure-placement`, `fix/structure-ground-fit` | candidate | ground-fit not yet integrated |
| [upstream-fixes](workstreams/upstream-fixes/ROADMAP.md) | one branch per issue | per issue | PRs #322, #323, #301 open |

**Archived:** native latitude climate — removed at the user's request; code on `archive/native-latitude-c6f98b83`,
history in `features/native-latitude-climate/` and [docs/custom-build.md](../docs/custom-build.md). The
latitude-windows proposal there was never accepted.

## Worktrees

Worktrees are disposable; the branch is what matters. Work worktrees map to a workstream; **baseline** worktrees are
detached A/B controls referenced from verification notes and are not tracked as work.

| Path | Ref | Workstream | Kind |
|---|---|---|---|
| `freeTerraForged-Custom` (main checkout) | `feature/rolling-highlands` | rolling-highlands | work |
| `freeTerraForged-Custom.gsd` | `planning/gsd` → `1_21_1-unstable-custom` | — | planning / integration |
| `/private/tmp/ftf-structure-performance` | `feature/structure-placement` | structure-placement | work |
| `/private/tmp/ftf-structure-ground-fit` | `fix/structure-ground-fit` | structure-placement | work |
| `/private/tmp/ftf-structure-original` | `c5076eb8` detached | structure-placement | baseline (pre-change) |
| `/private/tmp/ftf-structure-perf-control` | `744c2f20` detached | structure-placement | baseline (perf control) |
| `/private/tmp/ftf-grade-rh-test` | `6b63400b` detached | rolling-highlands | baseline |
| `$TMPDIR/opencode/ftf-pr323` | `fixSnowVegetation` | upstream-fixes | work (prunable) |

`/private/tmp` worktrees can vanish on reboot; recreate from the branch/commit above.

## Constraints

- Preserve FTF terrain quality and functionality; no reduction in erosion detail, terrain sampling or structure checks
  to win performance.
- Generation-model bumps mean new worlds only; never silently change existing saves.
- Validate on both Fabric and NeoForge before calling anything done; in-game checks are recorded separately from
  automated ones.
- Upstream CI runs `:common:test` inside a 15-minute job — slow tests must be gated before a PR.
