package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.mix;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.ridge;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.smooth;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;

/**
 * RH10 drowned coast: flooded river-valley bays (rias) as a transform of the coast edge value, a function of
 * position only so the drainage plan and the renderer share it (D-17).
 *
 * <p>Regions are jittered discs (one candidate per {@link #REGION_SIZE} cell, active with chance
 * {@link #REGION_CHANCE}); each has a weight 1 at its core falling to 0 at its rim. Inside a region the edge is
 * pulled down toward {@link #riaTarget} along a branching valley network (ridged noise, rigidly rotated per region so
 * channels run inland along the standalone edge gradient at the region centre, stretched by {@link #ANISO}), fading
 * out {@link #ARM_REACH} blocks inland of the coast point so arms shoal toward their heads. The transform only lowers
 * the edge and is monotone in it, so land never goes below sea level, skipped owners (edge 0) are untouched and the
 * coast is bit-for-bit the RH9 coast wherever the weight or the valley factor is 0. The land binding is inert until
 * {@link #bind}: an unbound instance is the identity.
 */
final class HighlandDrowned {
    /** Region cell size and chance, disc radius (fraction of the cell size, plus its jitter) and the share of it that is full weight. */
    static final double REGION_SIZE = 4000, REGION_CHANCE = .3, REGION_RADIUS = .4, REGION_RADIUS_JITTER = .2, REGION_CORE = .35;
    /** Valley network: stretch along the inland direction, trunk and branch wavelengths (blocks), branch weight, valley thresholds. */
    static final double ANISO = 2.5, TRUNK_SCALE = 2000, BRANCH_SCALE = 700, BRANCH_WEIGHT = .8, VALLEY_LOW = .45, VALLEY_HIGH = .85;
    /** Blocks inland of the coast point over which arms shoal, and how far below the beach point arm water sits (fraction of beach to shallow ocean). */
    static final double ARM_REACH = 2500, DROWN_MARGIN = .3;
    /** Half distance (blocks) of the central differences that orient a region. */
    private static final double GRADIENT_STEP = 256;
    private static final int MEMO = 4;

    /** Transformed edge value and the drowned-region weight (0..1) at the position. */
    record At(double edge, double drowned) {}
    private record Region(int i, int j, double cx, double cz, double radius, double gx, double gz, double ox, double oz) {}
    private static final class Memo { final Region[] slots = new Region[MEMO]; int next; }

    private final HighlandCoast coast;
    private final long seed;
    private final double coastEdge, riaTarget, reachEdge;
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);
    private volatile HighlandLand land;

    HighlandDrowned(GeneratorContext context, HighlandCoast coast) {
        var points = context.preset.world().controlPoints;
        this.coast = coast;
        this.seed = mix(context.seed.root() ^ 0x5248313044L);
        this.coastEdge = points.coast;
        this.riaTarget = points.beach - DROWN_MARGIN * (points.beach - points.shallowOcean);
        this.reachEdge = ARM_REACH * HighlandCoast.EDGE_PER_BLOCK;
    }

    /** Land source for the range-aware parts of later RH10 steps; set once, after the continent exists. */
    void bind(HighlandLand land) { this.land = land; }
    HighlandLand land() { return land; }

    /** Position-only region weight (0..1): the largest weight over the regions near the position. */
    double weight(double x, double z) {
        int gi = (int) Math.floor(x / REGION_SIZE), gj = (int) Math.floor(z / REGION_SIZE);
        double best = 0;
        for (int j = gj - 1; j <= gj + 1; j++) for (int i = gi - 1; i <= gi + 1; i++) {
            long h = hash(i, j);
            if (unit(h) >= REGION_CHANCE) continue;
            best = Math.max(best, discWeight(h, i, j, x, z));
        }
        return best;
    }

    /** The edge value at a raw position given the standalone (pre-drowning) edge there, and the region weight. */
    At at(double rawX, double rawZ, double edge) {
        if (land == null || edge <= riaTarget || edge >= coastEdge + reachEdge) return new At(edge, 0);
        int gi = (int) Math.floor(rawX / REGION_SIZE), gj = (int) Math.floor(rawZ / REGION_SIZE);
        double weight = 0, k = 0, fade = -1;
        for (int j = gj - 1; j <= gj + 1; j++) for (int i = gi - 1; i <= gi + 1; i++) {
            long h = hash(i, j);
            if (unit(h) >= REGION_CHANCE) continue;
            double w = discWeight(h, i, j, rawX, rawZ);
            if (w <= 0) continue;
            weight = Math.max(weight, w);
            if (fade < 0) fade = edge <= coastEdge ? 1 : 1 - smooth(0, reachEdge, edge - coastEdge);
            if (fade <= 0) continue;
            k = Math.max(k, w * fade * valley(region(h, i, j), rawX, rawZ));
        }
        if (k <= 0) return new At(edge, weight);
        return new At(edge + k * (riaTarget - edge), weight);
    }

    /** Valley factor 0..1 of one region at a position: a trunk network with branches, rotated rigidly about the region centre. */
    private static double valley(Region region, double x, double z) {
        double dx = x - region.cx, dz = z - region.cz;
        double along = dx * region.gx + dz * region.gz, across = -dx * region.gz + dz * region.gx;
        double u = across + region.ox, v = along / ANISO + region.oz;
        return smooth(VALLEY_LOW, VALLEY_HIGH, Math.max(ridge(0x5248313045L, u, v, TRUNK_SCALE, 2), BRANCH_WEIGHT * ridge(0x5248313046L, u, v, BRANCH_SCALE, 2)));
    }

    private double discWeight(long h, int i, int j, double x, double z) {
        double radius = radius(h), cx = centreX(h, i), cz = centreZ(h, j);
        return 1 - smooth(REGION_CORE * radius, radius, Math.hypot(x - cx, z - cz));
    }

    private Region region(long h, int i, int j) {
        var m = memo.get();
        for (var slot : m.slots) if (slot != null && slot.i == i && slot.j == j) return slot;
        double cx = centreX(h, i), cz = centreZ(h, j);
        double gx = coast.undrowned(cx + GRADIENT_STEP, cz).edge() - coast.undrowned(cx - GRADIENT_STEP, cz).edge();
        double gz = coast.undrowned(cx, cz + GRADIENT_STEP).edge() - coast.undrowned(cx, cz - GRADIENT_STEP).edge();
        double norm = Math.hypot(gx, gz);
        if (norm == 0) {
            double angle = 2 * Math.PI * unit(mix(h + 5));
            gx = Math.cos(angle); gz = Math.sin(angle);
        } else { gx /= norm; gz /= norm; }
        var region = new Region(i, j, cx, cz, radius(h), gx, gz, 1e4 * unit(mix(h + 6)), 1e4 * unit(mix(h + 7)));
        m.slots[m.next] = region;
        m.next = (m.next + 1) % MEMO;
        return region;
    }

    private long hash(int i, int j) { return mix(seed ^ (i * 0x9e3779b97f4a7c15L) ^ (j * 0x632be59bd9b4e019L)); }
    private static double centreX(long h, int i) { return (i + unit(mix(h + 1))) * REGION_SIZE; }
    private static double centreZ(long h, int j) { return (j + unit(mix(h + 2))) * REGION_SIZE; }
    private static double radius(long h) { return REGION_SIZE * (REGION_RADIUS + REGION_RADIUS_JITTER * unit(mix(h + 3))); }
    private static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }
}
