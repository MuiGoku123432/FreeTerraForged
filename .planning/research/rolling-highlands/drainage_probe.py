#!/usr/bin/env python3
"""Synthetic drainage experiment, NOT FTF terrain or a production implementation.

Run: python3 .planning/research/rolling-highlands/drainage_probe.py
Writes results beside this script. Standard library only. Original small research
implementation informed by Barnes et al. (2014), cited in RESEARCH.md.
Tests whether routing correctness alone implies acceptable terrain modification.
"""
import hashlib
import heapq
import json
import math
import random
from pathlib import Path

N = 65
SEEDS = (0, 1, 42, 8675309, -314159)


def neighbors(i):
    z, x = divmod(i, N)
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if (dx or dz) and 0 <= x + dx < N and 0 <= z + dz < N:
                yield (z + dz) * N + x + dx


def terrain(seed):
    rng = random.Random(seed)
    phases = [rng.random() * math.tau for _ in range(4)]
    result = []
    for z in range(N):
        for x in range(N):
            u, v = (x - 32) / 32, (z - 32) / 32
            coast = max(0, min(1, (1 - max(abs(u), abs(v))) * 4))
            regional = 135 + 65 * math.sin(7*u + phases[0]) * math.cos(6*v + phases[1])
            ridge = 45 * (1 - abs(math.sin(13*u + 4*v + phases[2])))
            local = 10 * math.sin(24*u + phases[3]) * math.cos(21*v)
            basin = 90 * math.exp(-((u - .2)**2 + (v + .1)**2) / .035)
            result.append(coast * max(1, regional + ridge + local - basin))
    return result


def route(raw):
    # Only actual synthetic sea cells are outlets. A computation rectangle's
    # boundary must not automatically be an outlet in an FTF implementation.
    filled = list(raw)
    parent = [-2] * len(raw)
    queue = []
    for i, h in enumerate(raw):
        if h == 0:
            parent[i] = -1
            heapq.heappush(queue, (h, i))
    order = []
    while queue:
        h, i = heapq.heappop(queue)
        order.append(i)
        for j in neighbors(i):
            if parent[j] != -2:
                continue
            parent[j] = i
            filled[j] = max(raw[j], h)
            heapq.heappush(queue, (filled[j], j))
    return filled, parent, order


def experiment(seed):
    raw = terrain(seed)
    filled, parent, order = route(raw)
    rank = {i: k for k, i in enumerate(order)}
    land = [i for i, h in enumerate(raw) if h > 0]
    edges = [i for i in land if parent[i] >= 0]
    assert len(order) == len(raw)
    assert all(rank[parent[i]] < rank[i] for i in edges)
    assert all(filled[parent[i]] <= filled[i] for i in edges)
    # Acyclic rank + every non-ocean node having a parent implies ocean reachability.
    assert all(parent[i] >= 0 for i in land)
    assert route(raw) == (filled, parent, order)
    # Naive carving alternative: propagate upstream minima down the same tree.
    # This proves non-ascent, but can cut deeply; it is intentionally NOT the
    # recommended final river profile or a lake/spill solver.
    carved = list(raw)
    for i in reversed(order):
        p = parent[i]
        if p >= 0:
            carved[p] = min(carved[p], carved[i])
    assert all(carved[parent[i]] <= carved[i] for i in edges)
    assert all(0 <= carved[i] <= raw[i] for i in range(len(raw)))
    raised = [filled[i] - raw[i] for i in land]
    cuts = [raw[i] - carved[i] for i in land]
    sinks = sum(all(raw[j] >= raw[i] for j in neighbors(i)) for i in land)
    return {
        "seed": seed, "land_nodes": len(land), "raw_local_sinks": sinks,
        "all_nodes_reach_ocean": True, "routing_cycles": 0,
        "repeat_identical": True,
        "filled_land_fraction": sum(v > 1e-9 for v in raised) / len(land),
        "filled_flat_parent_edge_fraction": sum(abs(filled[i] - filled[parent[i]]) < 1e-9 for i in edges) / len(edges),
        "max_fill_blocks": max(raised), "max_naive_cut_blocks": max(cuts),
        "naive_cut_over_48_blocks_fraction": sum(v > 48 for v in cuts) / len(land),
        "naive_cut_downstream_rises": 0,
        "routing_sha256": hashlib.sha256(json.dumps(parent).encode()).hexdigest(),
    }


def main():
    result = {
        "scope": "Synthetic 65x65 coastal terrain; no FTF code, water placement, erosion, or V3 baseline executed",
        "units": "synthetic vertical blocks; grid spacing deliberately unspecified",
        "seeds": [experiment(seed) for seed in SEEDS],
        "conclusions": [
            "Acyclic ocean-connected routing does not guarantee shelf-free visible terrain.",
            "Non-ascending carved profiles alone do not bound incision depth.",
            "Production needs separate visible relief, routing state, and constrained river profiles.",
        ],
    }
    path = Path(__file__).with_name("probe-results.json")
    path.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
