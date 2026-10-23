# Algorithm sources

Accessed 2026-10-22. These primary papers, author explanations, official scientific guidance and project documentation support [ALGORITHMS.md](ALGORITHMS.md). Software documentation is live; experiment package versions are recorded separately. No source's real-world validation transfers automatically to a Minecraft adaptation.

| Source | What was used | Applicability limit |
| --- | --- | --- |
| [Beck et al. (2018), Present and future Köppen–Geiger climate classification maps](https://www.nature.com/articles/sdata2018214), DOI 10.1038/sdata.2018.214 | Classification uses temperature/precipitation and seasonal criteria | A classifier and dataset, not a procedural field generator |
| [Ken Perlin, Implementing Improved Perlin Noise](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-5-implementing-improved-perlin-noise) | Coherent noise design and interpolation derivative continuity | Noise smoothness does not establish useful biome coverage |
| [SciPy PchipInterpolator](https://docs.scipy.org/doc/scipy/reference/generated/scipy.interpolate.PchipInterpolator.html), referencing Fritsch–Butland (1984), DOI 10.1137/0905021 | Shape-preserving monotone interpolation and continuity guarantees | Endpoint handling and world extrapolation still need a contract; original paper not separately read |
| [Climlab insolation API](https://climlab.readthedocs.io/en/latest/api/climlab.solar.insolation.html) | Daily/annual radiation, orbital inputs, Berger references | Our experiment independently implements a circular-orbit simplification; it does not run Climlab |
| [Climlab EBM API](https://climlab.readthedocs.io/en/latest/api/climlab.model.ebm.html) | Zonal radiation/diffusion energy-balance equation | Our P2 cancellation result applies only to the explicitly restricted linear, constant-albedo case |
| [GPU Gems 3, Generating Complex Procedural Terrains Using the GPU](https://developer.nvidia.com/gpugems/gpugems3/part-i-geometry/chapter-1-generating-complex-procedural-terrains-using-gpu) | Octave composition and domain warping | Rendering/terrain technique; climate scales and CPU costs must be chosen/tested separately |
| [NOAA, Global Atmospheric Circulations](https://www.noaa.gov/jetstream/global/global-atmospheric-circulations) | Broad circulation and pressure belts | Motivation for stylized wet/dry zones, not a quantitative annual precipitation curve |
| [Felzenszwalb and Huttenlocher (2012), Distance Transforms of Sampled Functions](https://cs.brown.edu/people/pfelzens/papers/dt-final.pdf), DOI 10.4086/toc.2012.v008a019 | Linear-time distance transforms on grids | Grid-mask exactness does not mean continuous-coastline exactness; separate implementation licensing applies |
| [SciPy distance_transform_edt](https://docs.scipy.org/doc/scipy/reference/generated/scipy.ndimage.distance_transform_edt.html) | Exact distance-to-background reference experiment | Requires explicit ocean/background semantics and spatial sampling |
| [Karger et al. (2017), Climatologies at high resolution for the earth's land surface areas](https://www.nature.com/articles/sdata2017122), DOI 10.1038/sdata.2017.122 | CHELSA precipitation downscaling with wind/topographic predictors | Uses background reanalysis and correction; not a self-contained arbitrary-world climate simulator |
| [Smith and Barstad (2004), A Linear Theory of Orographic Precipitation](https://training.weather.gov/wdtd/courses/woc/winter/physiogeographic/cool-season-orographic/story_content/external_files/Smith%20and%20Barstad%202004.pdf), Journal of the Atmospheric Sciences 61, 1377–1391 | Airflow response, condensed-water transport, conversion/fallout and Fourier treatment | Linear-model assumptions, specified atmospheric inputs; no implementation or validation of this model here |
| [NOAA Storm Prediction Center, Environmental Temperature Lapse Rates](https://origin-west-www-spc.woc.noaa.gov/exper/soundings/help/lapse.html) | Atmospheric lapse-rate terminology | Parcel/environmental rates cannot be inserted into dimensionless biome coordinates without a mapping |
| [FAO Irrigation and Drainage Paper 56, Chapter 2: Penman–Monteith](https://www.fao.org/4/x0490e/x0490e06.htm) | Meteorological inputs to reference evapotranspiration | Not a temperature-only dryness formula; no PET solver implemented |
| [SciPy gaussian_filter](https://docs.scipy.org/doc/scipy/reference/generated/scipy.ndimage.gaussian_filter.html) | Kernel radius and boundary-extension semantics | A finite array's edge treatment is not a valid substitute for neighboring world data |

## Original analysis versus sourced claims

The latitude-to-planar-coverage comparison, P2-normalization derivation, bounded modulation inequality, warp-fold condition, finite-fetch contraction reasoning, FTF integration recommendations and synthetic test results are analysis developed for this research. They are not claims that the cited sources designed or validated this FTF feature.

The previous [source register](SOURCES.md) anchors FTF/TerraLatitude code inspection and the NT dependency/license comparison. NT internals are not a source for the independent algorithms here. No third-party implementation source was pasted into the production repository. The experimental script invokes installed research libraries and uses independently written mathematical fixtures.

## Evidence quality

Scientific references explain models and their assumptions. Official software docs explain numerical routines. The executable fixtures check selected mathematical and boundary properties. None establishes in-game performance, visual quality, Java bitwise behavior, saved-world compatibility, mod interoperability or actual biome percentages. Those need the draft PR's integration tests and game runs.
