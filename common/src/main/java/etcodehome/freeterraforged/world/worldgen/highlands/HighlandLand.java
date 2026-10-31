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
    private final double spacing, uplandRange, rangeHeight, rangeLength, snowline;
    private final ThreadLocal<Cell> scratch = ThreadLocal.withInitial(Cell::new);

    private HighlandLand(Heightmap.Land composed, Levels levels, long seed, double spacing, double uplandRange, double rangeHeight, double rangeLength, double snowline) {
        this.snowline = snowline;
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
        var terrain = context.preset.terrain();
        float chains = 1;
        if (settings.replacesMountains()) {
            // Ranges are where the mountains live (RH7 D-c): no legacy mountain terrain and no legacy mountain chains.
            terrain = terrain.copy();
            terrain.mountains.weight *= etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings.REPLACED_MOUNTAIN_WEIGHT;
            chains = REPLACED_CHAIN_STRENGTH;
        }
        return new HighlandLand(Heightmap.makeLand(context, new Seed(root), terrain, chains), context.levels,
            root + context.preset.terrain().general.terrainSeedOffset, settings.uplandSpacing(), settings.uplandHeight(),
            settings.rangeHeight(), settings.rangeLength(), settings.snowline() / 100.0);
    }

    long seed() { return seed; }
    double rangeHeight() { return rangeHeight; }
    /** RH7 G: snowline height above the range foot (blocks); at or above the Range Height there is no glaciation. */
    double snowlineHeight() { return snowline * rangeHeight; }

    /**
     * Applies the legacy region and land populators to the cell (terrain type, erosion, weirdness, region fields)
     * and returns the land height in blocks above sea level, at least one block (land never dips below the sea
     * inside a continent; the coastal rise handles the shore).
     */
    public double apply(Cell cell, double x, double z) {
        double legacy = legacy(cell, x, z);
        double base = upland(x, z);
        if (rangeHeight <= 0) return legacy + base;
        var frame = rangeFrame(x, z);
        if (frame == null) return legacy + base;
        double core = frame.core();
        // RH6 R1: the range owns its relief. Inside it, legacy land above RANGE_LEGACY_CAP (legacy peaks and mountain
        // massifs) is compressed by up to RANGE_DAMP, so legacy peaks no longer poke through the range as isolated
        // pyramids and the range, not the legacy land, sets the crest height. Lower legacy detail stays.
        if (legacy > RANGE_LEGACY_CAP) legacy = RANGE_LEGACY_CAP + (legacy - RANGE_LEGACY_CAP) * (1 - RANGE_DAMP * core);
        double h = range(x, z, frame);
        // Range crests read as mountains for biomes (RH6 R4: along the whole main ridge above a height fraction, so rock
        // and snow form continuous bands): weirdness toward vanilla's peak slice (normal side) and erosion toward its
        // lowest levels, in proportion to how close to the crest the column is.
        double crest = HighlandMath.smooth(RANGE_CREST_LOW, RANGE_CREST_HIGH, core) * HighlandMath.smooth(RANGE_CLASS_LOW * rangeHeight, RANGE_CLASS_HIGH * rangeHeight, h);
        if (crest > 0) {
            cell.weirdness = (float) HighlandMath.lerp(cell.weirdness, HighlandMath.lerp(RANGE_SLOPE_WEIRDNESS, RANGE_PEAK_WEIRDNESS, crest), Math.min(1, 2 * crest));
            cell.erosion = (float) HighlandMath.lerp(cell.erosion, HighlandMath.lerp(RANGE_SLOPE_EROSION, RANGE_PEAK_EROSION, crest), Math.min(1, 2 * crest));
            if (crest > .4) cell.terrain = etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.MOUNTAIN_CHAIN;
        }
        return legacy + base + h;
    }

    /** Legacy FTF land height (blocks above sea level, at least 1) and its terrain fields written to {@code cell}. */
    private double legacy(Cell cell, double x, double z) {
        region.apply(cell, (float) x, (float) z);
        land.apply(cell, (float) (x * frequency), (float) (z * frequency));
        return Math.max(1, (cell.height - levels.water) * levels.terrainScaleFactor);
    }

    /** Range core weight (1 on the crest line, 0 beyond the foothills), crest-line strike angle and this flank's width factor. */
    record RangeFrame(double core, double strike, double width, double distance, double presence) {}

    /**
     * RH5 mountain ranges: long continuous ridges along the iso-line of a warped low-frequency noise (iso-lines of a
     * smooth field are long, winding, unbroken curves), switched on for whole stretches by a slower presence field and
     * notched by passes. RH6 R3: one flank is wider (gentler) than the other, switching slowly along the range.
     */
    RangeFrame rangeFrame(double x, double z) {
        double l = rangeLength, e = 8;
        double n = rangeField(x, z);
        double gx = (rangeField(x + e, z) - rangeField(x - e, z)) / (2 * e);
        double gz = (rangeField(x, z + e) - rangeField(x, z - e)) / (2 * e);
        double gradient = Math.hypot(gx, gz);
        if (gradient < 1e-9) return null;
        // Which flank (smoothed across the crest over a few percent of the half width, so the flank width, and the
        // height through it, do not step at the crest line).
        double side = Math.max(-1, Math.min(1, (n - .5) / gradient / (RANGE_SIDE_BLEND * rangeHalfWidth())));
        side *= 1.5 - .5 * side * side;
        double asymmetry = RANGE_ASYMMETRY * (2 * HighlandMath.noise(seed + 6211, x, z, 1.5 * l) - 1);
        // Approximate distance (blocks) to the crest line n = 0.5, in units of this flank's half width.
        double width = 1 + asymmetry * side;
        double t = Math.abs(n - .5) / gradient / (rangeHalfWidth() * width);
        if (t >= 1) return null;
        double presence = HighlandMath.smooth(.38, .58, HighlandMath.noise(seed + 6151, x, z, 2.2 * l));
        double core = presence * (1 - HighlandMath.smooth(0, 1, t));
        return core <= 0 ? null : new RangeFrame(core, Math.atan2(gz, gx) + Math.PI / 2, width, t * rangeHalfWidth() * width, presence);
    }

    /** The range's core weight at a position (0 outside ranges). */
    double rangeCore(double x, double z) {
        if (rangeHeight <= 0) return 0;
        var frame = rangeFrame(x, z);
        return frame == null ? 0 : frame.core();
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
     * Range height (blocks): a main ridge whose crest height varies slowly along the strike (RH6 R2: elongated peaks
     * joined by high cols, occasional deep passes), broad foothills with low ridges parallel to the main ridge, and spur
     * ridges running down the flanks perpendicular to the strike (RH6 R3), strongest mid-flank.
     */
    double range(double x, double z, RangeFrame frame) {
        double core = frame.core(), strike = frame.strike();
        // Passes are rare deep notches (the low tail of a smooth noise).
        double pass = .35 + .65 * HighlandMath.smooth(RANGE_PASS_LOW, RANGE_PASS_HIGH, HighlandMath.noise(seed + 6197, x, z, .55 * rangeLength));
        // RH7 D: the main ridge falls almost linearly from the crest line (a sharp divide, straight upper flanks) instead
        // of RH6's rounded bell top; foothills as RH6.
        double t = frame.distance() / (rangeHalfWidth() * frame.width());
        double main = HighlandMath.smooth(.45, 1, frame.presence()) * Math.pow(Math.max(0, 1 - t / RANGE_MAIN_REACH), RANGE_MAIN_SHAPE);
        double foothills = HighlandMath.smooth(0, .6, core);
        double crest = 1 - RANGE_CREST_VARIATION + 2 * RANGE_CREST_VARIATION * oriented(seed + 6173, x, z, strike, RANGE_CREST_STRETCH, RANGE_CREST_WAVELENGTH, 2);
        double parallel = oriented(seed + 6241, x, z, strike, RANGE_CREST_STRETCH, RANGE_FOOTHILL_WAVELENGTH, 2);
        return rangeHeight * Math.max(0, RANGE_FOOTHILLS * foothills * (.7 + .3 * parallel) + (1 - RANGE_FOOTHILLS) * main * pass * crest);
    }
    /** RH7 D: the main ridge reaches this share of the half width and falls with this exponent (1 = straight). */
    static final double RANGE_MAIN_REACH = .75, RANGE_MAIN_SHAPE = 1.3;

    /**
     * RH7 D4: mid-scale range detail in [-1, 1]: smooth noise stretched along the local fall line {@code fall} (the
     * caller's carved-surface gradient direction), so hillslopes carry ribs and gullies running downhill.
     */
    double rangeDetail(double x, double z, double fall) {
        // Symmetric in the fall direction (θ and θ + π give the same detail), so it does not step where the gradient
        // flips across ridges and valley floors.
        double a = oriented(seed + 7501, x, z, fall, RANGE_DETAIL_STRETCH, RANGE_DETAIL_WAVELENGTH, 3);
        double b = oriented(seed + 7501, x, z, fall + Math.PI, RANGE_DETAIL_STRETCH, RANGE_DETAIL_WAVELENGTH, 3);
        return 1.4 * (a + b - 1);
    }
    static final double RANGE_DETAIL_STRETCH = 2.5, RANGE_DETAIL_WAVELENGTH = 80;

    /** Width (share of the frame) over which the two flank sides blend at the crest. */
    static final double RANGE_SIDE_BLEND = .08;

    /**
     * Ridged noise stretched {@code stretch} times along direction {@code angle}, evaluated in rigidly rotated
     * coordinates for the two nearest of ORIENTATIONS fixed directions and blended (rotating world coordinates by a
     * varying angle would shear the noise far from the origin).
     */
    private static double oriented(long seed, double x, double z, double angle, double stretch, double wavelength, int octaves) {
        // Directions over the full turn: the strike angle is continuous modulo 2π, and rotated coordinates for θ and θ + π
        // differ (they are mirrored), so folding the angle into [0, π) would jump where the strike crosses 0.
        double a = (angle % TAU + TAU) % TAU / (TAU / ORIENTATIONS);
        int k0 = (int) Math.floor(a) % ORIENTATIONS, k1 = (k0 + 1) % ORIENTATIONS;
        double w = HighlandMath.smooth(a - Math.floor(a));
        return HighlandMath.lerp(rotated(seed, x, z, k0, stretch, wavelength, octaves), rotated(seed, x, z, k1, stretch, wavelength, octaves), w);
    }
    private static double rotated(long seed, double x, double z, int k, double stretch, double wavelength, int octaves) {
        double angle = k * TAU / ORIENTATIONS, cos = Math.cos(angle), sin = Math.sin(angle);
        double along = x * cos + z * sin, across = -x * sin + z * cos;
        // RH7 D: smooth (not ridged) fBm. Ridged noise has creases along its ridge lines, which the steeper RH7 flanks
        // turn into visible straight seams along the strike.
        double sum = 0, weight = 1, weights = 0;
        for (int i = 0; i < octaves; i++) {
            sum += weight * HighlandMath.noise(seed + 977L * k + i * 7919L, along / stretch, across, wavelength);
            weights += weight; weight *= .5; wavelength *= .5;
        }
        return sum / weights;
    }
    static final int ORIENTATIONS = 16;
    private static final double TAU = 2 * Math.PI;
    /** Legacy mountain-chain mask strength when ranges replace mountains (RH7 D-c: none). */
    static final float REPLACED_CHAIN_STRENGTH = 0F;
    /** Share of the range height carried by the foothills; the rest is the main ridge. */
    static final double RANGE_FOOTHILLS = .3;
    /** RH6 R1: share of legacy height above RANGE_LEGACY_CAP blocks removed on the crest line. */
    static final double RANGE_DAMP = .85, RANGE_LEGACY_CAP = 24;
    /** Pass noise band: below RANGE_PASS_LOW the crest drops to a pass, above RANGE_PASS_HIGH it is unbroken. */
    static final double RANGE_PASS_LOW = .1, RANGE_PASS_HIGH = .25;
    /** RH6 R2: crest height variation (±share), crest feature stretch along the strike and wavelength across it. */
    static final double RANGE_CREST_VARIATION = .15, RANGE_CREST_STRETCH = 4, RANGE_CREST_WAVELENGTH = 700, RANGE_FOOTHILL_WAVELENGTH = 360;
    /** RH6 R3: flank asymmetry (± share of the half width). */
    static final double RANGE_ASYMMETRY = .25;
    /** RH6 R4: mountain classification fades in between these shares of the range height. */
    static final double RANGE_CLASS_LOW = .2, RANGE_CLASS_HIGH = .45;
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
