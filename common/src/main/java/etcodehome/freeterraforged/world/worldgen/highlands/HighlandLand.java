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
    private final double spacing;
    private final ThreadLocal<Cell> scratch = ThreadLocal.withInitial(Cell::new);

    private HighlandLand(Heightmap.Land composed, Levels levels, long seed, double spacing) {
        this.spacing = spacing;
        this.region = composed.region();
        this.land = composed.land();
        this.frequency = composed.terrainFrequency();
        this.levels = levels;
        this.seed = seed;
    }

    public static HighlandLand create(GeneratorContext context) {
        long root = context.seed.root();
        return new HighlandLand(Heightmap.makeLand(context, new Seed(root)), context.levels,
            root + context.preset.terrain().general.terrainSeedOffset, context.preset.terrain().highlands().uplandSpacing());
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
        return Math.max(1, (cell.height - levels.water) * levels.terrainScaleFactor) + upland(x, z);
    }

    /**
     * Broad uplands and basins (blocks): warped two-octave noise at the upland spacing; basins sink to a quarter of the
     * upland height so lowland plains and lake basins occur between highland blocks.
     */
    double upland(double x, double z) {
        double px = x + .12 * spacing * (2 * HighlandMath.noise(seed + 1103, x, z, 1.5 * spacing) - 1);
        double pz = z + .12 * spacing * (2 * HighlandMath.noise(seed + 1877, x, z, 1.5 * spacing) - 1);
        double u = (2 * HighlandMath.noise(seed + 2741, px, pz, spacing) + HighlandMath.noise(seed + 3251, px, pz, .55 * spacing)) / 3;
        double basin = HighlandMath.smooth(.62, .8, 1 - u) * (.35 + .65 * HighlandMath.smooth(.35, .65, HighlandMath.noise(seed + 4271, px, pz, 2 * spacing)));
        return (UPLAND_BASE + UPLAND_RANGE * HighlandMath.smooth(.2, .8, u)) * (1 - .75 * basin);
    }
    static final double UPLAND_BASE = 16, UPLAND_RANGE = 80;

    /** Land height only, using a per-thread scratch cell. */
    public double height(double x, double z) {
        return apply(scratch.get().reset(), x, z);
    }
}
