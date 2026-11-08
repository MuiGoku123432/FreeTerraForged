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
 *
 * <p>Each region is typed once, at its anchor (memoized with its orientation): FJORD where the land has ranges, the
 * regional climate at the region centre is PNW and a range core stands within {@link #REGION_SIZE}/2 of the centre
 * (D-01), otherwise RIA (D-03). Typing reads {@link HighlandCoastProfile#climateClassAt} (raw climate), never the
 * shore, so the shore may read {@link #fjord} without recursion.
 */
final class HighlandDrowned {
    /** Region cell size and chance, disc radius (fraction of the cell size, plus its jitter) and the share of it that is full weight. */
    static final double REGION_SIZE = 5500, REGION_CHANCE = .3, REGION_RADIUS = .4, REGION_RADIUS_JITTER = .2, REGION_CORE = .8;
    /** Valley network: stretch along the inland direction, trunk and branch wavelengths (blocks), branch weight, valley thresholds. */
    static final double ANISO = 2.5, TRUNK_SCALE = 2000, BRANCH_SCALE = 700, BRANCH_WEIGHT = .8, VALLEY_LOW = .45, VALLEY_HIGH = .85;
    /** Blocks inland of the coast point over which arms shoal, and how far below the beach point arm water sits (fraction of beach to shallow ocean). */
    static final double ARM_REACH = 2500, DROWN_MARGIN = .3;
    /** Range core (0..1) a region needs within half a region of its centre to be a fjord region. */
    static final double FJORD_RANGE_CORE = .25;
    /** Half distance (blocks) of the central differences that orient a region. */
    private static final double GRADIENT_STEP = 256;
    /**
     * Region seed salt. REGION_SIZE, REGION_CHANCE, REGION_CORE and this salt were tuned together (11-02 Task 2, a sweep over
     * sizes 3000-6000, cores .35/.6/.8 and salts 0-900) so that the drowned share is 20-30% of the RH9 coastline in both
     * calibration boxes (user world, ROCKIES) and the RH9 coast gates, which measure outside the regions, keep a
     * representative non-drowned coast (including the PNW bay shore of the user box).
     */
    private static final long SEED_SALT = 257;
    private static final int MEMO = 4;

    /** Transformed edge value and the drowned-region weight (0..1) at the position. */
    record At(double edge, double drowned) {}
    /** A region anchor: centre, disc radius, inland direction (unit), noise offsets and its type (fjord, else ria). */
    record Region(int i, int j, double cx, double cz, double radius, double gx, double gz, double ox, double oz, boolean fjord) {}
    /**
     * Per-thread memos: the orientation of recently used regions, and the active discs of the 3x3 cells around the last
     * cell looked up (consecutive samples almost always share a cell, so the neighbour hashing is done once).
     */
    private static final class Memo {
        final Region[] slots = new Region[MEMO];
        int next;
        int gi, gj, count;
        boolean valid;
        final long[] h = new long[9];
        final int[] ci = new int[9], cj = new int[9];
        final double[] cx = new double[9], cz = new double[9], radius = new double[9];
    }

    private final HighlandCoast coast;
    private final long seed;
    private final double coastEdge, riaTarget, reachEdge;
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);
    private volatile HighlandLand land;

    HighlandDrowned(GeneratorContext context, HighlandCoast coast) {
        var points = context.preset.world().controlPoints;
        this.coast = coast;
        this.seed = mix(context.seed.root() ^ 0x5248313044L ^ SEED_SALT);
        this.coastEdge = points.coast;
        this.riaTarget = points.beach - DROWN_MARGIN * (points.beach - points.shallowOcean);
        this.reachEdge = ARM_REACH * HighlandCoast.EDGE_PER_BLOCK;
    }

    /** Land source for the range-aware parts of later RH10 steps; set once, after the continent exists. */
    void bind(HighlandLand land) { this.land = land; }
    HighlandLand land() { return land; }

    /** Active discs of the 3x3 cells around (gi, gj), memoized per thread for the last cell. */
    private Memo discs(int gi, int gj) {
        var m = memo.get();
        if (m.valid && m.gi == gi && m.gj == gj) return m;
        m.count = 0;
        for (int j = gj - 1; j <= gj + 1; j++) for (int i = gi - 1; i <= gi + 1; i++) {
            long h = hash(i, j);
            if (unit(h) >= REGION_CHANCE) continue;
            int n = m.count++;
            m.h[n] = h; m.ci[n] = i; m.cj[n] = j;
            m.cx[n] = centreX(h, i); m.cz[n] = centreZ(h, j); m.radius[n] = radius(h);
        }
        m.gi = gi; m.gj = gj; m.valid = true;
        return m;
    }

    /** Position-only region weight (0..1): the largest weight over the regions near the position. */
    double weight(double x, double z) {
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) best = Math.max(best, discWeight(m.cx[n], m.cz[n], m.radius[n], x, z));
        return best;
    }

    /**
     * Position-only fjord weight (0..1): the largest weight over the FJORD regions near the position; 0 when unbound or
     * when the land has no ranges.
     */
    double fjord(double x, double z) {
        var land = this.land;
        if (land == null || land.rangeHeight() <= 0) return 0;
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], x, z);
            if (w > best && region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).fjord) best = w;
        }
        return best;
    }

    /**
     * The region with the largest weight at the position (among FJORD regions only when {@code fjordOnly}); null when
     * there is none.
     */
    Region dominant(double x, double z, boolean fjordOnly) {
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        Region best = null;
        double bestWeight = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], x, z);
            if (w <= bestWeight) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            if (fjordOnly && !region.fjord) continue;
            best = region; bestWeight = w;
        }
        return best;
    }

    /** True where the transform can change an edge value: above the arm target and inside the inland reach. */
    boolean inBand(double edge) { return land != null && edge > riaTarget && edge < coastEdge + reachEdge; }

    /** The edge value at a raw position given the standalone (pre-drowning) edge there, and the region weight. */
    At at(double rawX, double rawZ, double edge) {
        if (!inBand(edge)) return new At(edge, 0);
        var m = discs((int) Math.floor(rawX / REGION_SIZE), (int) Math.floor(rawZ / REGION_SIZE));
        double weight = 0, k = 0, fade = -1;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], rawX, rawZ);
            if (w <= 0) continue;
            weight = Math.max(weight, w);
            if (fade < 0) fade = edge <= coastEdge ? 1 : 1 - smooth(0, reachEdge, edge - coastEdge);
            if (w * fade <= k) continue; // the valley factor is at most 1: this region cannot raise the maximum
            k = Math.max(k, w * fade * valley(region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]), rawX, rawZ));
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

    /** Disc weight at a position: 1 inside the core, falling smoothly to 0 at the rim (squared distances until needed; hypot is slow). */
    private static double discWeight(double cx, double cz, double radius, double x, double z) {
        double dx = x - cx, dz = z - cz, d2 = dx * dx + dz * dz, core = REGION_CORE * radius;
        if (d2 >= radius * radius) return 0;
        return d2 <= core * core ? 1 : 1 - smooth(core, radius, Math.sqrt(d2));
    }

    private Region region(long h, int i, int j, double cx, double cz) {
        var m = memo.get();
        for (var slot : m.slots) if (slot != null && slot.i == i && slot.j == j) return slot;
        double gx = coast.undrowned(cx + GRADIENT_STEP, cz).edge() - coast.undrowned(cx - GRADIENT_STEP, cz).edge();
        double gz = coast.undrowned(cx, cz + GRADIENT_STEP).edge() - coast.undrowned(cx, cz - GRADIENT_STEP).edge();
        double norm = Math.hypot(gx, gz);
        if (norm == 0) {
            double angle = 2 * Math.PI * unit(mix(h + 5));
            gx = Math.cos(angle); gz = Math.sin(angle);
        } else { gx /= norm; gz /= norm; }
        var region = new Region(i, j, cx, cz, radius(h), gx, gz, 1e4 * unit(mix(h + 6)), 1e4 * unit(mix(h + 7)), fjordAnchor(cx, cz));
        m.slots[m.next] = region;
        m.next = (m.next + 1) % MEMO;
        return region;
    }

    /** D-01 typing: ranges in the land, PNW climate at the centre, and a range core at the centre or one of four probes half a region away. */
    private boolean fjordAnchor(double cx, double cz) {
        var land = this.land;
        if (land == null || land.rangeHeight() <= 0 || coast.profile().climateClassAt(cx, cz) != HighlandCoastProfile.PNW) return false;
        double half = REGION_SIZE / 2, core = land.rangeCore(cx, cz);
        core = Math.max(core, Math.max(land.rangeCore(cx + half, cz), land.rangeCore(cx - half, cz)));
        core = Math.max(core, Math.max(land.rangeCore(cx, cz + half), land.rangeCore(cx, cz - half)));
        return core >= FJORD_RANGE_CORE;
    }

    private long hash(int i, int j) { return mix(seed ^ (i * 0x9e3779b97f4a7c15L) ^ (j * 0x632be59bd9b4e019L)); }
    private static double centreX(long h, int i) { return (i + unit(mix(h + 1))) * REGION_SIZE; }
    private static double centreZ(long h, int j) { return (j + unit(mix(h + 2))) * REGION_SIZE; }
    private static double radius(long h) { return REGION_SIZE * (REGION_RADIUS + REGION_RADIUS_JITTER * unit(mix(h + 3))); }
    private static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }
}
