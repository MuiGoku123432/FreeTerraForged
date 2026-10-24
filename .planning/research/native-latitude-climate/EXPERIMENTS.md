# Reproducible algorithm experiments

Run date: 2026-10-22. Entry point: [algorithm_experiments.py](algorithm_experiments.py). These experiments inform [the algorithm comparison](ALGORITHMS.md); they do not implement an FTF feature.

## Environment and outputs

The executed environment was Python 3.14.7 with NumPy 2.5.4, SciPy 1.18.1 and Matplotlib 3.11.2 in a temporary virtual environment. [algorithm-requirements.txt](algorithm-requirements.txt) pins the three direct research dependencies. The JSON also records the executing versions. This is not a complete cross-platform dependency lock or a guarantee of binary-identical plots.

From the repository root, create a research environment and run:

```sh
python3 -m venv /tmp/ftf-climate-research-env
/tmp/ftf-climate-research-env/bin/python -m pip install -r .planning/research/native-latitude-climate/algorithm-requirements.txt
/tmp/ftf-climate-research-env/bin/python .planning/research/native-latitude-climate/algorithm_experiments.py
```

Use a Python version supported by the pinned packages. The script writes its own result/figure files beside itself. Matplotlib and font caches default to temporary directories. It reads no worlds, dependencies or FTF production source.

Outputs:

- [algorithm-results.json](algorithm-results.json): metrics and environment.
- [algorithm-curves.csv](algorithm-curves.csv): sampled scalar curves, one row every 0.001 normalized latitude.
- [Temperature curves](figures/temperature-curves.png): profiles and equal-width temperature-bin occupancy.
- [Interpolation](figures/interpolation.png): natural cubic overshoot versus PCHIP; an illustrative moisture profile.
- [Spatial variation](figures/spatial-variation.png): coordinate warp, additive clamping and bounded modulation.
- [Moisture transport](figures/moisture-transport.png): ridge, opposite wind directions and remaining atmospheric moisture.

## Fixtures and conclusions

| Fixture | Check/measurement | Result and interpretation |
| --- | --- | --- |
| Five temperature shapes | Boundedness, monotonicity, slope and planar bin allocation | Passed; smoother endpoint derivatives do not produce equal coverage |
| Annual insolation | Circular orbit, 23.44° tilt, daily geometry; 1,440 vs 2,880 orbital samples | Refinement error below 7.54e-8 in Q/S0; not validation against an observed climate |
| Insolation cross-checks | Zero-tilt cos(phi)/pi identity and spherical mean approximately 1/4 | Assertions passed; independent checks beyond array finiteness |
| Monotonic control points | Natural cubic versus shape-preserving interpolation | Natural cubic exceeds both bounds and reverses locally; PCHIP preserves order to tolerance |
| Linear P2 EBM | Four diffusion coefficients and endpoint normalization | Normalized profiles match raised cosine within 3.34e-16; solver complexity can cancel out in normalization |
| Spatial variation | Endpoint saturation for the same analytic noise fixture | Additive clamping increases endpoint occupancy; bounded modulation avoids that added saturation |
| Gaussian tile | Compare full-field result to isolated tile and haloed tile | Error about 0.11134 without halo; zero in this fixture with full kernel support |
| Capped coast distance | Ocean lies outside output tile but within influence radius | Up to 38-cell error without surrounding mask; zero with the specified halo |
| Moisture budget | Nonnegative state/rain, conservation, wind reversal | Assertions passed; strong uplift still creates a roughly 95.6 windward/lee ratio |
| Uplift sensitivity | Repeat budget with four uplift coefficients | Saved in JSON; changing one parameter changes contrast despite conserved water |

The interpolation stress sequence is deliberately awkward. The moisture coefficients, wind, ocean mask, hill shape and units are artificial. The figures are **not** FTF terrain, biome maps, measured climate, or recommended preset defaults. The remaining-water field differs from deposited precipitation, which matters if a later implementation maps either into biome humidity.

## Reproducibility checks and limits

The script uses a fixed seed (`20261022`) for the tile fixture and fixed analytic fields elsewhere. It asserts selected invariants and fails on violation. The saved JSON is reproducible in the recorded environment. Numerical libraries, Python builds, platforms or plotting fonts may change last bits or image bytes; that is not the persisted world-generation contract.

Tests cover only the fixtures described. They do not test an infinite terrain domain, streaming world caches, every possible spline preset, actual FTF noise, Java float/quantization behavior, runtime injection order, NT parity, biome selection, seasons, or generation performance. A zero halo error here proves agreement for this finite-support array operation and fixture, not all future nonlocal algorithms.

The older [probe.py](probe.py) and [probe-results.json](probe-results.json) remain the independent standard-library checks for the initial scalar proposal. Their 36,664 assertions should not be added to an invented single count for this newer suite; the newer script uses vector assertions and reports results by experiment.
