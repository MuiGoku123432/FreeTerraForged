#!/usr/bin/env python3
"""Original research checks; not an FTF implementation or world-quality test.

Run from any directory. Uses only Python's standard library. Exhaustive small
integer fixtures independently check the proposed tree interval algorithm.
"""
import itertools
import json
import math
import random
import struct
from pathlib import Path


def solve(parent, lower, upper, drops, target):
    """One root, parent index < child index; edge drops are (min,max).

    Return a feasible profile, not a globally optimal least-squares fit.
    Real implementation needs explicit topological ordering and finite checks.
    """
    lo, hi = list(lower), list(upper)
    for i in reversed(range(1, len(parent))):
        if lo[i] > hi[i]:
            return None
        p = parent[i]
        a, b = drops[i]
        lo[p] = max(lo[p], lo[i] - b)
        hi[p] = min(hi[p], hi[i] - a)
    if lo[0] > hi[0]:
        return None
    water = [max(lo[0], min(hi[0], target[0]))]
    for i in range(1, len(parent)):
        p = parent[i]
        a, b = drops[i]
        low = max(lo[i], water[p] + a)
        high = min(hi[i], water[p] + b)
        if low > high:
            return None
        water.append(max(low, min(high, target[i])))
    return water


def valid(w, parent, lo, hi, drops):
    return all(lo[i] <= w[i] <= hi[i] for i in range(len(w))) and all(
        drops[i][0] <= w[i] - w[parent[i]] <= drops[i][1]
        for i in range(1, len(w))
    )


def brute(parent, lo, hi, drops):
    for w in itertools.product(*(range(a, b + 1) for a, b in zip(lo, hi))):
        if valid(w, parent, lo, hi, drops):
            return w
    return None


def solver_checks():
    rng = random.Random(20261027)
    counts = {"fixtures": 1000, "feasible": 0, "infeasible": 0, "disagreements": 0}
    for trial in range(counts["fixtures"]):
        n = rng.randint(2, 6)
        parent = [-1] + [rng.randrange(i) for i in range(1, n)]
        lo, hi, drops = [0], [0], [(0, 0)]
        witness = [0]
        for i in range(1, n):
            if trial % 2 == 0:
                value = rng.randint(witness[parent[i]], 5)
                witness.append(value)
                lo.append(rng.randint(0, value))
                hi.append(rng.randint(value, 5))
                diff = value - witness[parent[i]]
                drops.append((rng.randint(0, diff), rng.randint(diff, 5)))
            else:
                a, b = sorted((rng.randint(0, 5), rng.randint(0, 5)))
                lo.append(a)
                hi.append(b)
                a, b = sorted((rng.randint(0, 3), rng.randint(0, 3)))
                drops.append((a, b))
        target = [rng.randint(0, 5) for _ in range(n)]
        got = solve(parent, lo, hi, drops, target)
        oracle = brute(parent, lo, hi, drops)
        assert (got is None) == (oracle is None), (trial, got, oracle)
        if got is None:
            counts["infeasible"] += 1
        else:
            assert valid(got, parent, lo, hi, drops)
            counts["feasible"] += 1
            # Integer projection models monotone water quantization; no voxel
            # filling, lateral overlap or FTF surface convention is tested here.
            for i in range(1, n):
                p = parent[i]
                samples = [got[i] + (got[p] - got[i]) * t / 100 for t in range(101)]
                assert all(a >= b for a, b in zip(samples, samples[1:]))
                quantized = [math.floor(v) for v in samples]
                assert all(a >= b for a, b in zip(quantized, quantized[1:]))
    # Locally plausible branch bounds conflict at their shared downstream node.
    assert solve([-1, 0, 1, 1], [0, 0, 5, 0], [0, 5, 5, 1],
                 [(0, 0), (0, 5), (0, 1), (0, 1)], [0, 1, 5, 1]) is None
    counts["shared_junction_conflict_rejected"] = True
    return counts


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t*t*t*(t*(6*t - 15) + 10)


def section(d, water, depth, radius, bank, dry_half, fade, land):
    d = abs(d)
    freeboard, crossfall = 2.0, 2.0
    if d <= radius:
        return water - depth * (1 - smooth(d / radius))
    if d <= radius + bank:
        return water + freeboard * smooth((d - radius) / bank)
    if d <= radius + bank + dry_half:
        return water + freeboard + crossfall * smooth((d - radius - bank) / dry_half)
    floor_edge = water + freeboard + crossfall
    t = (d - radius - bank - dry_half) / fade
    return floor_edge + (land - floor_edge) * smooth(t)


def section_checks():
    count = 0
    for full_width in (64, 256, 1024):
        for depth in (2, 5, 12):
            for land in (84, 120, 260):
                water, radius, bank, fade = 80.0, 12.0, 24.0, 256.0
                half = full_width / 2
                args = (water, depth, radius, bank, half, fade, land)
                bounds = (0, radius, radius+bank, radius+bank+half,
                          radius+bank+half+fade)
                expected = (water-depth, water, water+2, water+4, land)
                for x, y in zip(bounds, expected):
                    assert abs(section(x, *args)-y) < 1e-8
                    assert abs(section(x-1e-5, *args)-section(x+1e-5, *args)) < 1e-5
                heights = [section(t*bounds[-1]/1000, *args) for t in range(1001)]
                assert all(a <= b + 1e-10 for a, b in zip(heights, heights[1:]))
                assert heights[0] == water-depth
                count += 1
    return {"constant_bank_fixtures": count, "endpoint_and_continuity_checks": "passed",
            "outward_monotonicity_checks": "passed", "center_bed_independent_of_floor_width": True,
            "limitation": "No spatially varying outer terrain, junction blending, filters, or voxel water tested"}


def numeric_checks():
    rows = []
    for world_height in (128, 256, 512, 1024):
        scale = min(world_height, 256)
        rows.append({"world_height": world_height, "terrain_scale": scale,
                     "normalized_delta_for_48_blocks": 48/scale,
                     "actual_blocks_if_divided_by_world_height": 48/world_height*scale})
    f32 = lambda v: struct.unpack('f', struct.pack('f', v))[0]
    return {"height_units": rows,
            "far_x": [f32(29999998), f32(29999999)],
            "adjacent_coordinates_can_alias_at_float_api": f32(29999999) == f32(30000000),
            "quintic_midpoint_derivative": 1.875,
            "warning": "Quintic longitudinal easing increases maximum slope to 1.875 times the segment average"}


def domain_budget():
    rows = []
    for scale in (1000, 4000, 10000):
        tectonic = 4*scale
        # Round matches positive Java Math.round for these exact fixture values.
        warp = math.floor(tectonic*.33 + .5)/2
        span = 3*tectonic+2*warp
        spacing = 1
        # Aligning a world grid can add one more interval than span / spacing.
        while (math.ceil(span/spacing)+2)**2 > 65536:
            spacing *= 2
        rows.append({"continent_scale": scale, "conservative_span_blocks": span,
                     "coarse_spacing_power_of_two": spacing,
                     "upper_grid_nodes": (math.ceil(span/spacing)+2)**2,
                     "minimum_upland_spacing_for_8_samples": spacing*8})
    return rows


def main():
    result = {"scope": "Mathematical/synthetic checks only; no FTF generation executed",
              "tree_solver": solver_checks(), "cross_sections": section_checks(),
              "numerics": numeric_checks(), "domain_budget": domain_budget()}
    Path(__file__).with_name('formula-results.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
