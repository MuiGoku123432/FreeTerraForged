"""Independent algorithm experiments, not an FTF or NT implementation.

Requires the versions recorded in algorithm-requirements.txt. Writes research
JSON, CSV, and figures alongside this file. No game files are read or changed.
All synthetic parameters are illustrative, not calibrated climate defaults.
"""
from pathlib import Path
import csv
import json
import os
import platform
import tempfile

os.environ.setdefault("MPLCONFIGDIR", str(Path(tempfile.gettempdir()) / "ftf-climate-mpl"))
os.environ.setdefault("XDG_CACHE_HOME", str(Path(tempfile.gettempdir()) / "ftf-climate-cache"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import scipy
from scipy.interpolate import CubicSpline, PchipInterpolator
from scipy.ndimage import distance_transform_edt, gaussian_filter

ROOT = Path(__file__).resolve().parent
FIG = ROOT / "figures"


def annual_sun(latitude, orbit_samples=1440, tilt_degrees=23.44):
    """Circular orbit, 23.44 degree tilt; daily-average geometry integrated in time.

    Integrates the positive solar zenith cosine analytically over hour angle,
    then numerically over uniform orbital longitude (uniform time only for e=0).
    Returns Q/S0, not surface temperature. Handles polar night/day explicitly.
    """
    phi = np.asarray(latitude)[:, None]
    longitude = 2 * np.pi * (np.arange(orbit_samples) + 0.5) / orbit_samples
    delta = np.arcsin(np.sin(np.deg2rad(tilt_degrees)) * np.sin(longitude))
    a = np.sin(phi) * np.sin(delta)
    b = np.cos(phi) * np.cos(delta)
    ratio = np.divide(-a, b, out=np.zeros_like(a), where=np.abs(b) > 1e-14)
    hour = np.arccos(np.clip(ratio, -1, 1))
    daily = (a * hour + b * np.sin(hour)) / np.pi
    daily = np.where(np.abs(b) <= 1e-14, np.maximum(a, 0), daily)
    return np.mean(daily, axis=1)


def emit(fig, name):
    fig.savefig(FIG / (name + ".png"), dpi=160)
    plt.close(fig)


def transport(height, ocean, dx, initial=1.0, uplift=2.5):
    """Stylized discrete moisture budget: evaporation then bounded precipitation.

    Dimensionless water storage; horizontal units arbitrary. No real weather.
    """
    q = float(initial)
    stores, rain, evaporation = [], [], []
    for i, h in enumerate(height):
        e = (1 - q) * (1 - np.exp(-dx / 8)) if ocean[i] else 0.0
        available = q + e
        rise = max(float(h - height[i - 1]), 0.0) if i else 0.0
        p = available * (-np.expm1(-dx / 200 - uplift * rise))
        q = available - p
        stores.append(q)
        rain.append(p)
        evaporation.append(e)
    return np.array(stores), np.array(rain), np.array(evaporation)


def run():
    FIG.mkdir(exist_ok=True)
    plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
    results = {"environment": {"python": platform.python_version(), "numpy": np.__version__,
        "scipy": scipy.__version__, "matplotlib": matplotlib.__version__},
        "scope": "Synthetic algorithm experiments only; no FTF runtime, NT parity or biome coverage claims."}

    u = np.linspace(0, 1, 10001)
    sun_lat = np.linspace(0, np.pi / 2, 901)
    sun = annual_sun(sun_lat)
    sun_fine = annual_sun(sun_lat, 2880)
    zero_tilt_error = float(np.max(np.abs(annual_sun(sun_lat, tilt_degrees=0) - np.cos(sun_lat)/np.pi)))
    spherical_mean = float(np.trapezoid(sun*np.cos(sun_lat),sun_lat))
    assert zero_tilt_error < 1e-12 and abs(spherical_mean-.25) < 1e-6
    normalized_sun = (sun - sun[-1]) / (sun[0] - sun[-1])
    curves = {"linear": 1 - u, "cubic_smoothstep": 1 - u*u*(3 - 2*u),
        "quintic_smootherstep": 1 - u**3*(10 - 15*u + 6*u*u),
        "raised_cosine": (1 + np.cos(np.pi*u))/2,
        "normalized_annual_insolation": np.interp(u, sun_lat/(np.pi/2), normalized_sun)}
    results["curves"] = {}
    for name, y in curves.items():
        assert np.all(np.isfinite(y)) and y.min() >= -1e-12 and y.max() <= 1+1e-12
        assert np.max(np.diff(y)) < 1e-9
        hist = np.histogram(y, bins=np.linspace(0, 1, 6))[0]
        results["curves"][name] = {"temperature_bin_fraction": (hist / hist.sum()).tolist(),
            "cold_fraction_below_0_2": float(np.mean(y < .2)),
            "warm_fraction_above_0_8": float(np.mean(y > .8)),
            "maximum_slope_per_normalized_latitude": float(np.max(np.abs(np.gradient(y, u))))}
    results["insolation"] = {"equator_Q_over_S0": float(sun[0]), "pole_Q_over_S0": float(sun[-1]),
        "zero_tilt_max_error_from_cos_phi_over_pi":zero_tilt_error,
        "spherical_mean_Q_over_S0_expected_one_quarter":spherical_mean,
        "orbital_quadrature_max_change_Q_over_S0": float(np.max(np.abs(sun_fine-sun)))}
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.5), layout="constrained")
    for name, y in curves.items():
        axes[0].plot(u, y, label=name.replace('_', ' '))
    axes[0].set(xlabel="Equator-to-pole fraction", ylabel="Normalized target (not Celsius)", title="Candidate temperature shapes")
    axes[0].legend(fontsize=8)
    for i, (name, stats) in enumerate(results["curves"].items()):
        axes[1].bar(np.arange(5) + (i-2)*.15, stats["temperature_bin_fraction"], width=.15, label=name)
    axes[1].set(xticks=range(5), xticklabels=["0–.2", ".2–.4", ".4–.6", ".6–.8", ".8–1"],
        xlabel="Temperature bin", ylabel="Fraction of uniformly sampled latitude", title="Smoothness changes area allocation")
    fig.suptitle("Synthetic shapes: equal endpoints do not imply equal climate coverage")
    emit(fig, "temperature-curves")

    # A deliberately difficult but valid monotonic control sequence.
    knots = np.array([0, .2, .35, .65, .8, 1])
    values = np.array([1, .98, .3, .28, .02, 0])
    cubic = CubicSpline(knots, values, bc_type="natural")(u)
    pchip = PchipInterpolator(knots, values, extrapolate=False)(u)
    assert np.min(pchip) >= -1e-12 and np.max(pchip) <= 1+1e-12 and np.max(np.diff(pchip)) < 1e-12
    results["interpolation"] = {"natural_cubic_min": float(cubic.min()), "natural_cubic_max": float(cubic.max()),
        "natural_cubic_increasing_steps": int(np.sum(np.diff(cubic) > 1e-12)),
        "pchip_min": float(pchip.min()), "pchip_max": float(pchip.max()),
        "pchip_increasing_steps": int(np.sum(np.diff(pchip) > 1e-12))}
    moisture_values = np.array([.85, .65, .2, .7, .4, .15])
    moisture = PchipInterpolator(knots, moisture_values)(u)
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.2), layout="constrained")
    axes[0].plot(u, cubic, label="Natural cubic spline")
    axes[0].plot(u, pchip, label="PCHIP")
    axes[0].scatter(knots, values, c="black", zorder=5, label="Monotonic control values")
    axes[0].set(xlabel="Latitude fraction", ylabel="Target", title="Stress fixture: overshoot and reversal")
    axes[0].legend(fontsize=8)
    axes[1].plot(u, moisture, color="teal")
    axes[1].scatter(knots, moisture_values, c="black")
    axes[1].set(xlabel="Latitude fraction", ylabel="Moisture proxy", title="Independent moisture curve: illustrative only")
    emit(fig, "interpolation")

    # Analytic steady P2 energy balance: normalization cancels amplitude changes.
    p2 = (3*np.sin(np.pi*u/2)**2-1)/2
    ebm = []
    q0, albedo, a, b, s2 = 1361/4, .3, 210, 2, -.48
    for diffusion in (0, .3, .6, 1.2):
        t0 = ((1-albedo)*q0-a)/b
        t2 = (1-albedo)*q0*s2/(b+6*diffusion)
        t = t0+t2*p2
        norm = (t-t[-1])/(t[0]-t[-1])
        error = float(np.max(np.abs(norm-curves["raised_cosine"])))
        assert error < 1e-12
        ebm.append({"diffusion": diffusion, "equator_minus_pole_model_degrees": float(t[0]-t[-1]),
            "normalized_difference_from_raised_cosine": error})
    results["constant_albedo_P2_EBM"] = ebm

    # A shared coordinate warp and two bounded/unbounded ways to add variation.
    x, z = np.meshgrid(np.linspace(-1, 1, 401), np.linspace(-1.2, 1.2, 481))
    base_u = np.minimum(np.abs(z), 1)
    base = 1-base_u**2*(3-2*base_u)
    noise = .65*np.sin(2*np.pi*x)*np.cos(3*np.pi*z)+.35*np.sin(5*np.pi*x+2*np.pi*z)
    warped_u = np.minimum(np.abs(z + .12*np.sin(2*np.pi*x)*np.cos(np.pi*z)), 1)
    warped = 1-warped_u**2*(3-2*warped_u)
    additive = np.clip(base+.25*noise, 0, 1)
    bounded = base+.8*base*(1-base)*noise
    assert np.min(bounded) >= -1e-12 and np.max(bounded) <= 1+1e-12
    results["variation"] = {"base_exact_endpoints_fraction": float(np.mean((base==0)|(base==1))),
        "additive_clamp_exact_endpoints_fraction": float(np.mean((additive==0)|(additive==1))),
        "bounded_modulation_exact_endpoints_fraction": float(np.mean((bounded==0)|(bounded==1))),
        "warp_min_dzprime_dz_bound": float(1-.12*np.pi)}
    fig, axes = plt.subplots(1, 4, figsize=(13, 5), layout="constrained")
    for ax, field, title in zip(axes, (base, warped, additive, bounded),
            ("Base", "Coordinate warp", "Add then clamp", "Bounded modulation")):
        im=ax.imshow(field, origin="lower", extent=(-1,1,-1.2,1.2), cmap="coolwarm", vmin=0,vmax=1,aspect="auto")
        ax.set(title=title, xlabel="X / pole distance", ylabel="Z / pole distance")
    fig.colorbar(im, ax=axes, label="Temperature target before terrain")
    fig.suptitle("Synthetic sinusoidal variation; these are not FTF biome maps")
    emit(fig, "spatial-variation")

    # Nonlocal effects require a halo: compare full field and independently filtered tile.
    rng = np.random.default_rng(20261022)
    field = rng.random((257,257))
    radius, sigma = 12, 3
    full = gaussian_filter(field, sigma, radius=radius)
    tile = field[96:160,96:160]
    without = gaussian_filter(tile, sigma, radius=radius)
    with_halo = gaussian_filter(field[96-radius:160+radius,96-radius:160+radius], sigma, radius=radius)[radius:-radius,radius:-radius]
    halo_error = float(np.max(np.abs(full[96:160,96:160]-with_halo)))
    assert halo_error < 1e-14
    land = np.ones((257,257), dtype=bool)
    land[:,84] = False
    cap = 50
    global_distance = np.minimum(distance_transform_edt(land),cap)[96:160,96:160]
    # Explicit no-ocean fallback; EDT of an all-land finite tile is not a global answer.
    no_ocean_tile = np.full((64,64),cap,dtype=float)
    halo_distance = np.minimum(distance_transform_edt(land[46:210,46:210]),cap)[50:-50,50:-50]
    assert np.array_equal(global_distance,halo_distance)
    results["tile_boundaries"] = {"gaussian_without_halo_max_error": float(np.max(np.abs(full[96:160,96:160]-without))),
        "gaussian_with_halo_max_error": halo_error,
        "capped_ocean_distance_without_halo_max_error_cells": float(np.max(np.abs(global_distance-no_ocean_tile))),
        "capped_ocean_distance_with_halo_max_error_cells": float(np.max(np.abs(global_distance-halo_distance)))}

    # Symmetric terrain, wind reversal, and discrete conservation.
    position = np.linspace(-120,120,961)
    dx = float(position[1]-position[0])
    height = np.exp(-(position/16)**2)
    ocean = np.abs(position)>85
    storage, rain, evap = transport(height,ocean,dx)
    reversed_storage, reversed_rain, _ = transport(height[::-1],ocean[::-1],dx)
    reversed_rain = reversed_rain[::-1]
    residual = float(1+evap.sum()-rain.sum()-storage[-1])
    assert abs(residual)<1e-12 and np.min(storage)>=0 and np.min(rain)>=0
    assert np.max(np.abs(rain-reversed_rain[::-1]))<1e-12
    windward=(position>-35)&(position<-5)
    lee=(position>5)&(position<35)
    windward_ratio=float(rain[windward].mean()/rain[lee].mean())
    assert windward_ratio>1
    results["moisture_budget"] = {"mass_balance_residual":residual,
        "windward_to_lee_mean_rain_ratio":windward_ratio,
        "initial_storage":1.0,"final_storage":float(storage[-1]),
        "total_evaporation":float(evap.sum()),"total_rain":float(rain.sum()),
        "warning":"Toy budget without cloud delay, thermodynamics or observed calibration"}
    sensitivity = []
    for uplift in (.25,.75,1.5,2.5):
        q_test,p_test,e_test=transport(height,ocean,dx,uplift=uplift)
        balance=float(1+e_test.sum()-p_test.sum()-q_test[-1])
        assert abs(balance)<1e-12
        sensitivity.append({"uplift_coefficient":uplift,
            "windward_to_lee_mean_rain_ratio":float(p_test[windward].mean()/p_test[lee].mean()),
            "mass_balance_residual":balance})
    results["moisture_uplift_sensitivity"]=sensitivity
    fig, axes = plt.subplots(3,1,figsize=(10,7),sharex=True,layout="constrained")
    axes[0].fill_between(position,height,color="sienna",alpha=.55)
    axes[0].set(ylabel="Terrain",title="Synthetic ridge: equal elevation does not imply equal rainfall")
    axes[1].plot(position,rain/dx,label="Wind west → east")
    axes[1].plot(position,reversed_rain/dx,label="Wind east → west")
    axes[1].set(ylabel="Rain per distance unit"); axes[1].legend()
    axes[2].plot(position,storage,color="teal",label="Remaining atmospheric moisture, west → east")
    axes[2].set(xlabel="Arbitrary horizontal distance",ylabel="Moisture storage");axes[2].legend()
    emit(fig,"moisture-transport")

    with (ROOT/"algorithm-curves.csv").open("w",newline="") as stream:
        writer=csv.writer(stream, lineterminator="\n");writer.writerow(["latitude_fraction",*curves.keys()])
        for i in range(0,len(u),10):
            writer.writerow([u[i],*(y[i] for y in curves.values())])
    results["status"]="pass"
    (ROOT/"algorithm-results.json").write_text(json.dumps(results,indent=2,sort_keys=True)+"\n")
    print(json.dumps(results,indent=2,sort_keys=True))


if __name__ == "__main__":
    run()
