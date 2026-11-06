"""Analytical research fixtures, not Java/worldgen validation. Python standard library only."""
import hashlib
import json
import math
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def envelope(value):
    # Current default temperature min/max/offset; distribution bias is zero.
    return min(1.0, max(0.1124, 0.1124 + 0.8876 * value + 0.002))


def cooling(value, height, strength=2.2):
    return value ** (2.0 ** (height * strength))


def moisture(value, height):
    boosted = value + (1.0 - value) * (0.2 * (1.0 - height))
    return boosted ** (2.0 ** (height * 1.8))


def main():
    height = 0.5
    # Match the two illustrative corrections at temperature 0.5, at this one height.
    # This is a controlled shape comparison, not a calibrated lapse-rate parameter.
    delta = 0.5 - cooling(0.5, height)
    cooling_rows = [
        {"input": t, "power": cooling(t, height), "subtract_then_clamp": max(0.0, t - delta)}
        for t in [0.0, 0.25, 0.5, 0.75, 0.9, 0.99, 1.0]
    ]
    windows = [(0, 0.55, 1), (0.35, 0.45, 0.95), (0.6, 0.3, 0.8), (0.85, 0.15, 0.55), (1, 0, 0.4)]
    mapped = [{"latitude_fraction": u, "window": [lo, hi], "mapped_temperature_bounds": [envelope(lo), envelope(hi)]}
              for u, lo, hi in windows]
    fractions = {}
    count = 20000
    for power in [1, 2, 5]:
        bins = [0] * 5
        for i in range(count):
            sine = math.sin(2.0 * math.pi * (i + 0.5) / count)
            r = (math.copysign(abs(sine) ** power, sine) + 1.0) * 0.5
            bins[min(4, int(r * 5))] += 1
        fractions[str(power)] = [n / count for n in bins]
    # Constant-width window == linear blend when expressed in the same coordinate space.
    window_blend_error = max(
        abs((s * l + ((s * l + 1 - s) - s * l) * r) - ((1 - s) * r + s * l))
        for s in [0, 0.4, 0.75, 1] for l in [0, 0.25, 1] for r in [0, 0.3, 1]
    )
    checks = {
        "maximum_temperature_is_fixed_under_current_cooling": all(cooling(1, h) == 1 for h in [0, 0.5, 1]),
        "candidate_cools_maximum_at_positive_height": cooling_rows[-1]["subtract_then_clamp"] < 1,
        "polar_window_reaches_temperate_after_default_envelope": mapped[-1]["mapped_temperature_bounds"][1] > 0.425,
        "equatorial_window_floor_is_warm_after_default_envelope": mapped[0]["mapped_temperature_bounds"][0] > 0.6,
        "all_wet_inputs_stay_fully_wet_after_current_drying": all(moisture(1, h) == 1 for h in [0, 0.5, 1]),
        "sea_level_dry_input_crosses_first_humidity_boundary": moisture(0.2, 0) > 0.325,
        "full_window_is_legacy_before_terrain": all(envelope(0 + (1 - 0) * (i / 1000)) == envelope(i / 1000) for i in range(1001)),
        "constant_width_equivalence": window_blend_error < 1e-14,
    }
    assert all(checks.values()), checks
    sources = [
        "common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/climate/ClimateModule.java",
        "common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/climate/TemperatureShaping.java",
        "common/src/main/java/etcodehome/freeterraforged/data/worldgen/preset/settings/ClimateSettings.java",
        "common/src/main/java/etcodehome/freeterraforged/world/worldgen/cell/heightmap/Levels.java",
    ]
    result = {
        "scope": "Independent double-precision mathematical fixtures. No FTF noise, terrain sampling, Java execution or biome generation.",
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "source_sha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sources},
        "probe_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "checks": checks,
        "cooling_comparison": {"normalized_height": height, "current_strength": 2.2, "matched_subtractive_delta": delta, "rows": cooling_rows},
        "default_envelope_windows": mapped,
        "pure_sine_quintile_fractions": {"samples": count, "warps": False, "fractions": fractions},
        "current_moisture": [{"regional": r, "normalized_height": h, "output": moisture(r, h)} for r in [0.2, 0.5, 0.8, 1] for h in [0, 0.5, 1]],
        "height_normalization_example": {"blocks_above_sea": 128, "sea_level": 64, "height_320": 128 / (320 - 64), "height_640": 128 / (640 - 64)},
    }
    (HERE / "realism-probe-results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"checks_passed": len(checks), "cooling": result["cooling_comparison"], "windows": mapped}, indent=2))


if __name__ == "__main__":
    main()
