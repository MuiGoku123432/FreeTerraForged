"""Independent scalar-model research; no FTF/NT/Minecraft code or dependencies.

Run: python3 .planning/research/native-latitude-climate/probe.py
Prints deterministic JSON. This checks Python double arithmetic only.
"""

import json
import math
import struct


def latitude_temperature(z, equator, pole_distance, repeating=False):
    if not all(math.isfinite(v) for v in (z, equator, pole_distance)):
        raise ValueError("coordinates and distance must be finite")
    if pole_distance <= 0:
        raise ValueError("pole distance must be positive")
    distance = abs(z - equator)
    if repeating:
        phase = (z - equator) % (2 * pole_distance)
        distance = min(phase, 2 * pole_distance - phase)
    u = min(distance / pole_distance, 1.0)
    return 1.0 - u * u * (3.0 - 2.0 * u)


def blend(regional, target, strength):
    if strength == 0:
        return regional
    return regional + strength * (target - regional)


def run():
    counts = {}

    def check(group, condition):
        counts[group] = counts.get(group, 0) + 1
        if not condition:
            raise AssertionError(group)

    for distance in (1.0, 256.0, 50_000.0, 1_000_000.0):
        for equator in (-1_000_000.0, 0.0, 123_456.0):
            previous = 1.0
            for i in range(1001):
                offset = distance * i / 1000
                north = latitude_temperature(equator + offset, equator, distance)
                south = latitude_temperature(equator - offset, equator, distance)
                check("bounded", 0.0 <= north <= 1.0)
                check("hemisphere_symmetry", abs(north - south) < 1e-10)
                check("monotonic_to_pole", north <= previous + 1e-12)
                previous = north
            check("warm_equator", latitude_temperature(equator, equator, distance) == 1)
            check("cold_pole", latitude_temperature(equator + distance, equator, distance) == 0)
            check("cold_cap", latitude_temperature(equator + 3 * distance, equator, distance) == 0)
            for repeating in (False, True):
                for z in (-30_000_000.0, -256.0, -1.0, 0.0, 1.0, 256.0, 30_000_000.0):
                    value = latitude_temperature(z, equator, distance, repeating)
                    check("large_coordinates_finite_bounded", math.isfinite(value) and 0 <= value <= 1)
                # Smoothstep derivative has maximum magnitude 1.5 / distance.
                epsilon = max(1e-4, distance * 1e-6)
                for multiple in (-4, -2, -1, 0, 1, 2, 4):
                    boundary = equator + multiple * distance
                    left = latitude_temperature(boundary - epsilon, equator, distance, repeating)
                    right = latitude_temperature(boundary + epsilon, equator, distance, repeating)
                    check("boundary_continuity_bound", abs(right - left) <= 3 * epsilon / distance + 1e-9)
            for fraction in (-3.9, -2.1, -1.3, -0.7, 0.3, 1.7, 3.2):
                z = equator + distance * fraction
                a = latitude_temperature(z, equator, distance, True)
                b = latitude_temperature(z + 2 * distance, equator, distance, True)
                check("repeating_period", abs(a - b) < 1e-9)

    for regional in (-0.0, 0.0, 0.001, 0.25, 0.5, 0.999, 1.0):
        for target in (0.0, 0.2, 0.8, 1.0):
            check("zero_strength_bit_identity", struct.pack("!d", blend(regional, target, 0)) == struct.pack("!d", regional))
            for strength in (0.0, 0.01, 0.5, 0.99, 1.0):
                result = blend(regional, target, strength)
                check("convex_blend_no_overshoot", min(regional, target) - 1e-15 <= result <= max(regional, target) + 1e-15)

    for args in ((0, 0, 0), (0, 0, -1), (float("nan"), 0, 100), (0, 0, float("inf"))):
        try:
            latitude_temperature(*args)
        except ValueError:
            check("invalid_input_rejected", True)
        else:
            check("invalid_input_rejected", False)

    return {
        "status": "pass",
        "model": "independent smoothstep proposal; not production ftf:latitude_v1",
        "arithmetic": "Python binary64; no Java float or Minecraft quantization parity claim",
        "checks": counts,
        "total_checks": sum(counts.values()),
        "example": {
            "equator_z": 0,
            "pole_distance_blocks": 50000,
            "note": "Illustrative distance, not a selected shipping default",
            "samples": [
                {"z": z, "temperature_01_before_range_and_elevation": latitude_temperature(z, 0, 50000)}
                for z in (-75000, -50000, -25000, 0, 25000, 50000, 75000)
            ],
        },
        "exclusions": ["FTF runtime", "NT parity", "biome coverage", "saved worlds", "loader compatibility", "performance"],
    }


if __name__ == "__main__":
    print(json.dumps(run(), indent=2, sort_keys=True))
