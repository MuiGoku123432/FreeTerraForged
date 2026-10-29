package etcodehome.freeterraforged.world.worldgen.highlands;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

/**
 * Rolling Highlands' surface: the legacy FTF land composition (terrain regions, region borders, mountain chains,
 * Blender; honours every Terrain-page setting) raised by a broad, smooth upland uplift. The uplift plays the role
 * of the UPLIFT continents' water table (interior highlands, lowland basins) without its steps. No continent shape is
 * applied here; the RH coast and coastal rise are applied by the caller. Built from a fresh seed so every
 * construction is identical.
 */
public final class HighlandLand {
    private final CellPopulator region, land;
    private final float frequency;
    private final Levels levels;
    private final long seed;
    private final double spacing, uplandRange, rangeHeight, rangeLength;
    private final ThreadLocal<Cell> scratch = ThreadLocal.withInitial(Cell::new);

    private HighlandLand(Heightmap.Land composed, Levels levels, long seed, double spacing, double uplandRange, double rangeHeight, double rangeLength) {
        this.spacing = spacing;
        this.uplandRange = uplandRange;
        this.rangeHeight = rangeHeight;
        this.rangeLength = rangeLength;
        this.region = composed.region();
        this.land = composed.land();
        this.frequency = composed.terrainFrequency();
        this.levels = levels;
        this.seed = seed;
    }

    public static HighlandLand create(GeneratorContext context) {
        long root = context.seed.root();
        var settings = context.preset.terrain().highlands();
        return new HighlandLand(Heightmap.makeLand(context, new Seed(root)), context.levels,
            root + context.preset.terrain().general.terrainSeedOffset, settings.uplandSpacing(), settings.uplandHeight(),
            settings.rangeHeight(), settings.rangeLength());
    }

    long seed() { return seed; }

    /**
     * Applies the legacy region and land populators to the cell (terrain type, erosion, weirdness, region fields)
     * and returns the land height in blocks above sea level, at least one block (land never dips below the sea
     * inside a continent; the coastal rise handles the shore).
     */
    public double apply(Cell cell, double x, double z) {
        region.apply(cell, (float) x, (float) z);
        land.apply(cell, (float) (x * frequency), (float) (z * frequency));
        double height = Math.max(1, (cell.height - levels.water) * levels.terrainScaleFactor) + upland(x, z);
        if (rangeHeight <= 0) return height;
        double core = rangeCore(x, z);
        if (core <= 0) return height;
        double h = range(x, z, core);
        // Range crests read as mountains for biomes: weirdness toward vanilla's peak slice (normal side) and erosion
        // toward its lowest levels, in proportion to how close to the crest the column is.
        double crest = HighlandMath.smooth(RANGE_CREST_LOW, RANGE_CREST_HIGH, core) * HighlandMath.smooth(.25 * rangeHeight, .6 * rangeHeight, h);
        if (crest > 0) {
            cell.weirdness = (float) HighlandMath.lerp(cell.weirdness, HighlandMath.lerp(RANGE_SLOPE_WEIRDNESS, RANGE_PEAK_WEIRDNESS, crest), Math.min(1, 2 * crest));
            cell.erosion = (float) HighlandMath.lerp(cell.erosion, HighlandMath.lerp(RANGE_SLOPE_EROSION, RANGE_PEAK_EROSION, crest), Math.min(1, 2 * crest));
            if (crest > .5) cell.terrain = etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.MOUNTAIN_CHAIN;
        }
        return height + h;
    }

    /**
     * RH5 mountain ranges: long continuous ridges along the iso-line of a warped low-frequency noise (iso-lines of a
     * smooth field are long, winding, unbroken curves), switched on for whole stretches by a slower presence field and
     * notched by passes. Returns the range's core weight (1 on the crest line, 0 beyond the foothills).
     */
    double rangeCore(double x, double z) {
        double l = rangeLength, e = 8;
        double n = rangeField(x, z);
        double gx = (rangeField(x + e, z) - rangeField(x - e, z)) / (2 * e);
        double gz = (rangeField(x, z + e) - rangeField(x, z - e)) / (2 * e);
        double gradient = Math.hypot(gx, gz);
        if (gradient < 1e-9) return 0;
        // Approximate distance (blocks) to the crest line n = 0.5, in units of the range half width.
        double t = Math.abs(n - .5) / gradient / rangeHalfWidth();
        if (t >= 1) return 0;
        double presence = HighlandMath.smooth(.38, .58, HighlandMath.noise(seed + 6151, x, z, 2.2 * l));
        return presence * (1 - HighlandMath.smooth(0, 1, t));
    }
    /**
     * The field whose 0.5 iso-line is the range crest: noise stretched RANGE_ELONGATION times along a slowly turning
     * strike direction (ranges run long and fairly straight, bending over tens of kilometres), lightly warped.
     */
    private double rangeField(double x, double z) {
        double l = rangeLength;
        double strike = Math.PI * HighlandMath.noise(seed + 6091, x, z, 5 * l);
        double cos = Math.cos(strike), sin = Math.sin(strike);
        double wx = x + RANGE_WARP * l * (2 * HighlandMath.noise(seed + 6101, x, z, 1.3 * l) - 1);
        double wz = z + RANGE_WARP * l * (2 * HighlandMath.noise(seed + 6113, x, z, 1.3 * l) - 1);
        double along = (wx * cos + wz * sin) / RANGE_ELONGATION, across = -wx * sin + wz * cos;
        return HighlandMath.noise(seed + 6131, along, across, .7 * l);
    }
    static final double RANGE_ELONGATION = 2.5, RANGE_WARP = .1;
    /** Half width (blocks) of a range including its foothills. */
    double rangeHalfWidth() { return HighlandMath.clamp(.14 * rangeLength, 420, 1500); }

    /**
     * Range height (blocks) from its core weight: a narrow main ridge with ridged-noise peaks and saddles, broad
     * foothills, and occasional passes where the crest drops to the foothill level.
     */
    double range(double x, double z, double core) {
        double ridge = HighlandMath.ridge(seed + 6173, x, z, 700, 3);
        double pass = .35 + .65 * HighlandMath.smooth(.22, .48, HighlandMath.noise(seed + 6197, x, z, .55 * rangeLength));
        double main = HighlandMath.smooth(.45, 1, core);
        double foothills = HighlandMath.smooth(0, .6, core);
        return rangeHeight * (RANGE_FOOTHILLS * foothills * (.75 + .25 * ridge) + (1 - RANGE_FOOTHILLS) * main * main * pass * (.55 + .45 * ridge));
    }
    /** Share of the range height carried by the foothills; the rest is the main ridge. */
    static final double RANGE_FOOTHILLS = .3;
    static final double RANGE_CREST_LOW = .45, RANGE_CREST_HIGH = .9;
    static final double RANGE_SLOPE_WEIRDNESS = -.45, RANGE_PEAK_WEIRDNESS = -.66, RANGE_SLOPE_EROSION = -.45, RANGE_PEAK_EROSION = -.8;

    /**
     * Broad uplands and basins (blocks): warped two-octave noise at the upland spacing; basins sink to a quarter of the
     * upland height so lowland plains and lake basins occur between highland blocks.
     */
    double upland(double x, double z) {
        double px = x + .12 * spacing * (2 * HighlandMath.noise(seed + 1103, x, z, 1.5 * spacing) - 1);
        double pz = z + .12 * spacing * (2 * HighlandMath.noise(seed + 1877, x, z, 1.5 * spacing) - 1);
        double u = (2 * HighlandMath.noise(seed + 2741, px, pz, spacing) + HighlandMath.noise(seed + 3251, px, pz, .55 * spacing)) / 3;
        double basin = HighlandMath.smooth(.62, .8, 1 - u) * (.35 + .65 * HighlandMath.smooth(.35, .65, HighlandMath.noise(seed + 4271, px, pz, 2 * spacing)));
        return (UPLAND_BASE * Math.min(1, uplandRange / 80) + uplandRange * HighlandMath.smooth(.2, .8, u)) * (1 - .75 * basin);
    }
    static final double UPLAND_BASE = 16;

    /** Land height only, using a per-thread scratch cell. */
    public double height(double x, double z) {
        return apply(scratch.get().reset(), x, z);
    }
}
