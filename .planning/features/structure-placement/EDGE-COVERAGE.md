# Structure placement — edge coverage

Date: 2026-10-27. Companion to [SPEC.md](SPEC.md).

The canonical GSD edge probe emitted 55 applicable edges for the [12 requirements](../../research/structure-placement/requirements.json). All 55 are addressed below as explicit specification obligations; zero were dismissed. **No runtime checks have passed merely because an edge is specified.** These rows supplement the referenced acceptance criteria.

Machine-readable report: [edge-coverage.json](../../research/structure-placement/edge-coverage.json).

| Requirement | Category | Status | Explicit verification obligation |
|---|---|---|---|
| SP-01 | boundary | Specified; untested | AC-01: test minY, maxY-1, empty column and accessor/config intersections; first-free may equal maxY. |
| SP-01 | precision | Specified; untested | AC-01: integer result equals reference predicate scan, including negative/fractional raw heights; no truncation or blanket +1 shortcut. |
| SP-02 | idempotency | Specified; untested | AC-02: repeated cold/warm/evicted queries yield identical heights and stages. |
| SP-02 | concurrency | Specified; untested | AC-02/09: interleave worlds/filter population and queries; no cycles, raw-cell contamination or partial publication. |
| SP-03 | boundary | Specified; untested | AC-03: exercise exact cell-aligned ceiling, one block above/below, min/max and high neighbor contributions. |
| SP-03 | precision | Specified; untested | AC-03: conservative rounded bounds equal full-height output; checked coordinate arithmetic never wraps. |
| SP-03 | idempotency | Specified; untested | AC-03/09: repeating generation/reference comparisons does not change sampled density or decisions. |
| SP-03 | concurrency | Specified; untested | AC-03/09: reordered workers produce identical complete NOISE chunks; fallback has no shared mutable state. |
| SP-04 | adjacency | Specified; untested | AC-04: exact-ID beats tag; same-priority conflicting tag rules are invalid, even when masks touch. |
| SP-04 | empty | Specified; untested | AC-04: absent optional mod/empty selector is inactive; an enabled empty support mask is invalid; unknown explicit profile version fails validation. |
| SP-04 | encoding | Specified; untested | AC-04: identifiers use Minecraft ResourceLocation parsing and explicit canonical namespace:path equality, not display names, case folding or substring matching. Invalid resource syntax is rejected. |
| SP-04 | ordering | Specified; untested | AC-04: resource-pack precedence then explicit selector priority; equal-priority conflict errors do not depend on registry iteration order. |
| SP-04 | idempotency | Specified; untested | AC-04/09: reloading unchanged effective data yields the same profile resolution/fingerprint. |
| SP-04 | concurrency | Specified; untested | AC-04/09: in-flight starts retain immutable epoch; successful reload publishes a complete new epoch atomically. |
| SP-05 | boundary | Specified; untested | AC-05: support gap/embed exactly at limit passes, one block beyond fails; legal zero-shift original is retained. |
| SP-05 | adjacency | Specified; untested | AC-05/08: deduplicate touching support cells; intersecting incompatible support/entry masks are validation errors, not double transforms. |
| SP-05 | empty | Specified; untested | AC-04/05: empty/zero-piece starts retain upstream failure; no fabricated fallback structure. Singleton support mask is valid if declared. |
| SP-05 | ordering | Specified; untested | AC-05/09: original first, fixed alternative order, closest feasible integer Y with lower-Y tie break; stable mask order. |
| SP-05 | precision | Specified; untested | AC-05: all fit-interval bounds are inclusive integer bounds; ceil lower/floor upper for conservative fractional envelopes, checked transforms at negative coordinates. |
| SP-06 | boundary | Specified; untested | AC-06/08: root blend/embedding stops exactly at declared mask/depth; unsupported canopy edges do not count as root failures. |
| SP-06 | adjacency | Specified; untested | AC-06: touching root and intentional-void masks need explicit compatible ownership; conflicts reject profile validation. |
| SP-06 | empty | Specified; untested | AC-06: missing/ambiguous root or void metadata keeps profile observation-only; sparse omitted template coordinates are never filled with air. |
| SP-06 | ordering | Specified; untested | AC-06/09: effective processor order is preserved except explicit profile-owned transformations; rotate masks with the selected template, not before resolving variants. |
| SP-06 | precision | Specified; untested | AC-06: integer transformed masks match actual template origin/ground delta at all rotations, including negative world positions. |
| SP-07 | boundary | Specified; untested | AC-07: lift 0/max/max+1, ceiling/padding touch, impossible interval; exact minimum clearance passes. |
| SP-07 | adjacency | Specified; untested | AC-07: touching footprint columns share sampled terrain; vertically separate occupied components retain their individual lowest/topmost bounds. |
| SP-07 | empty | Specified; untested | AC-07: no occupied blocks/empty generation stub cannot be accepted as an airborne success; retain upstream empty outcome. |
| SP-07 | ordering | Specified; untested | AC-07/09: original altitude first, minimum upward lift next; component order cannot alter max clearance or final transform. |
| SP-07 | precision | Specified; untested | AC-07: compute checked integer lift from first-free surfaces; do not double-add sampled absolute altitude; upward round conservative estimates. |
| SP-08 | boundary | Specified; untested | AC-08: support depth/volume exactly at cap passes, one extra block fails; zero budget means no added support. |
| SP-08 | adjacency | Specified; untested | AC-08: overlapping influence regions use deterministic ownership and aggregate budgets, not duplicate block writes or stacked height changes. |
| SP-08 | empty | Specified; untested | AC-08: no foundation profile/budget means no extra terraforming; no material rule means optional foundation profile invalid. |
| SP-08 | ordering | Specified; untested | AC-08/09: equivalent structure inputs produce identical combined influence independent of chunk scheduling; conflicting edits resolved deterministically or rejected. |
| SP-08 | precision | Specified; untested | AC-08: count unique changed blocks, use checked volume/depth arithmetic, and never wrap a large mask into a smaller budget. |
| SP-08 | idempotency | Specified; untested | AC-08/09: repeated population/reload does not stack extra foundation blocks or further lower terrain. |
| SP-08 | concurrency | Specified; untested | AC-08: per-current-chunk writes only; immutable cross-chunk decision data prevents races and partial shared-template edits. |
| SP-09 | adjacency | Specified; untested | AC-09: same structure start identity across adjoining chunks resolves to one persisted decision, not one per chunk. |
| SP-09 | empty | Specified; untested | AC-09: no accepted start means no saved transform; old starts without policy metadata retain their upstream saved state and do not acquire an inferred policy. |
| SP-09 | ordering | Specified; untested | AC-09: reverse/random order and interleaved worlds yield the same pieces, junctions, references and entities. |
| SP-09 | idempotency | Specified; untested | AC-09: repeated load/retry never rerolls accepted placement or duplicates entities. |
| SP-09 | concurrency | Specified; untested | AC-09: one epoch per start; failure/cancellation never publishes partial decisions or leaves owner caches bound to another world. |
| SP-10 | adjacency | Specified; untested | AC-10: multiple mixins/profiles targeting the same start execute each owned concern once; upstream rejection wins. |
| SP-10 | empty | Specified; untested | AC-10: target mods absent is a clean pass-through; upstream empty stub is not resurrected; empty direct-holder sets retain existing guard behavior. |
| SP-10 | ordering | Specified; untested | AC-10: validate actual transformed mixin order and processor sequence; no profile bypasses upstream eligibility via an early return. |
| SP-10 | idempotency | Specified; untested | AC-10/09: saved pieces preserve machine block entities/loot/entity counts after repeat loads. |
| SP-10 | concurrency | Specified; untested | AC-10/09: mod hook context remains owner/thread scoped across multiple worlds and concurrent generation. |
| SP-11 | boundary | Specified; untested | AC-12/13: sample minima and budget thresholds are explicit; exact target passes, insufficient samples are inconclusive. |
| SP-11 | adjacency | Specified; untested | AC-11: terrain comparisons use unioned influence bounds and stage labels; adjoining chunks cannot hide unexplained edits at shared borders. |
| SP-11 | empty | Specified; untested | AC-12: zero baseline defects preserves zero; zero baseline valid starts is reported separately, never used as an improvement ratio. Missing fixtures are inconclusive. |
| SP-11 | ordering | Specified; untested | AC-11/12: freeze survey/seed order before comparisons and alternate paired run order; do not cherry-pick successful examples. |
| SP-11 | precision | Specified; untested | AC-12: report integer numerator/denominator, per-ID counts and confidence intervals; avoid rounded percentages masking a failed threshold. |
| SP-12 | boundary | Specified; untested | AC-13/14: cap candidates/columns/template entries/memory; exactly-at-limit completes, next work item takes deterministic budget-exceeded path. |
| SP-12 | precision | Specified; untested | AC-13/14: counters use sufficient width/checked accumulation; throughput is total completed chunks divided by measured elapsed time, with stated units. |
| SP-12 | idempotency | Specified; untested | AC-14: logging toggles and repeated report reads do not sample RNG or change generation; counters distinguish retries from unique starts. |
| SP-12 | concurrency | Specified; untested | AC-13/14: bounded in-flight metadata work, owner-scoped aggregation, atomic report snapshots and no timeout-dependent acceptance. |
