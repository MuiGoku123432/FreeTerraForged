package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.mix;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.noise;
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
 * out {@link #ARM_REACH} blocks inland of the coast point so arms shoal toward their heads. Apart from the island chains
 * (11-06, on the shelf seaward of the coast point, away from owner borders) the transform only lowers
 * the edge (it is monotone in it in fjord regions and in ria regions away from the mouth and basin terms), so land never goes below sea
 * level, skipped owners (edge 0) are untouched and the coast is bit-for-bit the RH9 coast wherever the weight or the
 * valley factor is 0 and no island stands. The ria mouth band and basin shift (11-05) make a ria's result non-monotone in the edge near the
 * mouth: a sill, shallower at the entrance than inside, which is what the bay shape needs and changes nothing about
 * which columns are land. The land binding is inert until {@link #bind}: an unbound instance is the identity.
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
    /**
     * Fjord regions (D-05, D-06), tuned in 11-04 Task 2 against the pooled arm census of the fjord boxes (see the test's
     * FJORD_BOXES): ramp steepening factor and the blocks inland it acts over; the valley network is stretched
     * {@code FJORD_ANISO} along the inland direction (trunk scale as for rias), trunks (low thresholds, wide) and side arms
     * (branch ridges, higher thresholds, narrow) have their own thresholds; arms fade out over {@code FJORD_REACH} blocks
     * inland and {@code LONG_CHANCE} of fjord regions fade over {@code LONG_FACTOR} times that (the occasional long arm).
     */
    static final double FJORD_STEEP = 4, FJORD_WALL = 500, FJORD_ANISO = 5, FJORD_TRUNK_LOW = .6, FJORD_TRUNK_HIGH = .95,
        FJORD_VALLEY_LOW = .58, FJORD_VALLEY_HIGH = .95, FJORD_BRANCH_WEIGHT = 1, FJORD_BRANCH_SCALE = 1100,
        FJORD_REACH = 6000, LONG_CHANCE = .15, LONG_FACTOR = 2.5;
    /**
     * Fjord depth (D-06, D-17): the flooded part of the edge, its distance below the coast point, is stretched by
     * {@code 1 + (FJORD_DEPTH - 1) f}, so arm water falls below the shallow-ocean point where the legacy ocean populators
     * give deep floors (the shoreface alone never goes deeper than the shelf). The flooded set is unchanged (the stretch
     * keeps the sign of {@code edge - coast}); the edge never falls below {@link #depthFloor}.
     */
    static final double FJORD_DEPTH = 2;
    /**
     * Sounds (D-07): {@code SOUND_CHANCE} of fjord regions get a sound basin of radius {@code SOUND_RADIUS} blocks around the
     * region's coast point, where the valley thresholds drop by up to {@code SOUND_DROP}: arms widen into 1-3 km of open water
     * and only the crests of the valley noise survive as islands.
     */
    static final double SOUND_CHANCE = .3, SOUND_RADIUS = 1500, SOUND_DROP = .5;
    /**
     * Ria bays (D-13, D-14, tuned in 11-05 against the bay census of the RIA_BOXES and a ROCKIES ria box). Mouth: over
     * {@code MOUTH_LEN} blocks inland of the standalone coastline the valley factor is multiplied by {@code 1 - MOUTH_NARROW (1 -
     * band)}, where {@code band} keeps only the width {@code BAND_LO..BAND_HI} (|2n - 1| of the first trunk octave) about that
     * octave's contour. The contour is one connected line and the ridge value on it is at least 2/3, so the entrance narrows to
     * a channel but never closes (shifting the valley thresholds up instead closed the mouth wherever the ridge crest sagged,
     * leaving lagoons behind a bar: 99.6% of the flooded area in a ROCKIES box). Basin: over the middle of the arm reach (from
     * {@code WIDEN_FROM} to half of it full, falling to 0 at {@code WIDEN_TO}) the thresholds drop by up to {@code BAY_WIDEN}, so
     * the water spreads into a basin. Ria arms keep full strength out to {@code BAY_PLATEAU} of the reach and shoal over the
     * rest (fjords shoal from the coast point as before); without the plateau the arm fade kills the valley by about 1 km inland.
     */
    static final double MOUTH_LEN = 400, MOUTH_NARROW = 1, BAND_LO = 0, BAND_HI = .03, BAY_WIDEN = .4, WIDEN_FROM = .15, WIDEN_TO = .9, BAY_PLATEAU = .6;
    /**
     * Island chains (D-08, D-09, D-11): on the shelf seaward of the coast point of a region, flooded ridge tops are the land the
     * drowning left standing. Blocks seaward of the coast point where islands may stand ({@code ISLAND_GAP} to the shelf edge, with
     * {@code ISLAND_SOFT} blocks of ramp at the near end, so a channel of at least {@code ISLAND_GAP} blocks stays between them and
     * the mainland), and how high the top of an island stands above the coast point (blocks of the coast ramp; fjord regions stand
     * {@code ISLAND_PEAK_FJORD}, then the fjord steepening applies on top). Islands are the union of broken chains and a few broad
     * blobs: chains follow ridged noise elongated along the coast ({@code CHAIN_ANISO}, wavelength {@code CHAIN_SCALE}, kept above
     * {@code CHAIN_AT}..{@code CHAIN_HIGH}) cut by a gap noise (wavelength {@code GAP_SCALE}, off below {@code GAP_AT}); blobs are
     * low-frequency noise ({@code BLOB_SCALE}) above {@code BLOB_AT}. An island weight s raises the edge to
     * {@code edge + s (coast + peak - edge)}: continuous, shoaling toward the island, land only where s is near 1.
     */
    static final double ISLAND_GAP = 100, ISLAND_SOFT = 150, ISLAND_PEAK = 300, ISLAND_PEAK_FJORD = 750,
        CHAIN_ANISO = 3, CHAIN_SCALE = 1200, CHAIN_AT = .4, CHAIN_HIGH = .7, GAP_SCALE = 700, GAP_AT = .15, BLOB_SCALE = 2400, BLOB_AT = .4;
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
    /** A region anchor: centre, disc radius, inland direction (unit), noise offsets, type (fjord, else ria), arm reach and target, sound basin centre (NaN: none). */
    record Region(int i, int j, double cx, double cz, double radius, double gx, double gz, double ox, double oz, boolean fjord, double reach, double target, double soundX, double soundZ) {}
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
    private final double coastEdge, riaTarget, fjordTarget, reachEdge, wallEdge, longReachEdge, depthFloor, shelfEdge;
    /** Lowest edge value the transform can change: the ria target, or the depth floor (below the fjord target) when the land has ranges. */
    private volatile double lowEdge;
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);
    private volatile HighlandLand land;

    HighlandDrowned(GeneratorContext context, HighlandCoast coast) {
        var points = context.preset.world().controlPoints;
        this.coast = coast;
        this.seed = mix(context.seed.root() ^ 0x5248313044L ^ SEED_SALT);
        this.coastEdge = points.coast;
        this.riaTarget = points.beach - DROWN_MARGIN * (points.beach - points.shallowOcean);
        this.fjordTarget = points.shallowOcean - DROWN_MARGIN * (points.shallowOcean - points.deepOcean);
        this.reachEdge = ARM_REACH * HighlandCoast.EDGE_PER_BLOCK;
        this.wallEdge = FJORD_WALL * HighlandCoast.EDGE_PER_BLOCK;
        this.longReachEdge = FJORD_REACH * LONG_FACTOR * HighlandCoast.EDGE_PER_BLOCK;
        this.depthFloor = Math.max(points.deepOcean, .01);
        this.shelfEdge = points.deepOcean;
        this.lowEdge = riaTarget;
    }

    /** Land source for the range-aware parts of later RH10 steps; set once, after the continent exists. */
    void bind(HighlandLand land) {
        this.lowEdge = land != null && land.rangeHeight() > 0 ? Math.min(riaTarget, depthFloor) : riaTarget;
        this.land = land;
    }
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
     * Position-only ria weight (0..1): the largest weight over the RIA (non-fjord) regions near the position; 0 when unbound.
     * Used by the shore profile and the marsh tagging (D-16), which want the bay character, not the fjord one.
     */
    double ria(double x, double z) {
        var land = this.land;
        if (land == null) return 0;
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], x, z);
            if (w > best && !region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).fjord) best = w;
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

    /** True where the transform can change an edge value: above the (lowest) arm target and inside the longest inland reach. */
    boolean inBand(double edge) {
        var land = this.land;
        return land != null && edge > Math.min(lowEdge, shelfEdge) && edge < coastEdge + (land.rangeHeight() > 0 ? Math.max(reachEdge, longReachEdge) : reachEdge);
    }

    /**
     * D-06 ramp steepening in fjord regions: the land side ramp {@code u = edge - coast} is remapped to
     * {@code u + c f u (1 - u / wall)^2} (c = FJORD_STEEP - 1, f the fjord weight) for u inside the wall band, so the edge rises
     * about FJORD_STEEP times faster at the water and joins the identity at the band's inland end with slope 1. The slope of
     * {@code 1 + c f (1 - t)(1 - 3t)} is at least {@code 1 - c f / 3}, so with c f at most 3 the remap is monotone in the
     * edge; it only raises land that is already land.
     */
    double steepen(double edge, double fjordWeight) {
        double u = edge - coastEdge;
        if (u <= 0 || u >= wallEdge || fjordWeight <= 0) return edge;
        double r = 1 - u / wallEdge;
        return edge + (FJORD_STEEP - 1) * fjordWeight * u * r * r;
    }

    /**
     * The edge value at a raw position given the standalone (pre-drowning) edge there, and the region weight: steepened by
     * the fjord weight, then lowered toward each overlapping region's target by weight times arm fade times valley (the
     * smallest result wins, which is continuous where regions of different type overlap).
     */
    At at(double rawX, double rawZ, double edge) {
        if (!inBand(edge)) return new At(edge, 0);
        var m = discs((int) Math.floor(rawX / REGION_SIZE), (int) Math.floor(rawZ / REGION_SIZE));
        double weight = 0, fjord = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], rawX, rawZ);
            if (w <= 0) continue;
            weight = Math.max(weight, w);
            if (w > fjord && region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).fjord) fjord = w;
        }
        if (weight <= 0) return new At(edge, 0);
        double raised = raise(m, rawX, rawZ, edge);
        double steep = steepen(raised, fjord), lowering = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], rawX, rawZ);
            if (w <= 0) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            double fade = raised <= coastEdge ? 1 : 1 - smooth(region.fjord ? 0 : BAY_PLATEAU * region.reach, region.reach, raised - coastEdge);
            if (w * fade <= 0) continue;
            double k = w * fade * valley(region, rawX, rawZ, bayWiden(region, raised - coastEdge), bayNarrow(region, raised - coastEdge));
            lowering = Math.min(lowering, k * (region.target - steep));
        }
        double result = steep + lowering;
        if (fjord > 0 && result < coastEdge) result = Math.max(Math.min(result, depthFloor), coastEdge - (1 + (FJORD_DEPTH - 1) * fjord) * (coastEdge - result));
        return new At(result, weight);
    }

    /**
     * The standalone edge with the island chains of the regions around raised in (see {@link #ISLAND_PEAK}): the largest result
     * over the regions; the edge itself outside the shelf window or where no island stands.
     */
    private double raise(Memo m, double x, double z, double edge) {
        double window = smooth(shelfEdge, shelfEdge + ISLAND_SOFT * HighlandCoast.EDGE_PER_BLOCK, edge)
            * (1 - smooth(coastEdge - (ISLAND_GAP + ISLAND_SOFT) * HighlandCoast.EDGE_PER_BLOCK, coastEdge - ISLAND_GAP * HighlandCoast.EDGE_PER_BLOCK, edge));
        if (window <= 0) return edge;
        double best = edge;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], x, z);
            if (w <= 0) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            double s = w * window * island(region, x, z);
            if (s <= 0) continue;
            double peak = (region.fjord ? ISLAND_PEAK_FJORD : ISLAND_PEAK) * HighlandCoast.EDGE_PER_BLOCK;
            best = Math.max(best, edge + s * (coastEdge + peak - edge));
        }
        return best;
    }

    /** Island weight 0..1 of one region at a position: broken chains along the coast (rotated rigidly about the region centre) and broad blobs. */
    private static double island(Region region, double x, double z) {
        double dx = x - region.cx, dz = z - region.cz;
        double along = dx * region.gx + dz * region.gz, across = -dx * region.gz + dz * region.gx;
        double chain = smooth(CHAIN_AT, CHAIN_HIGH, ridge(0x5248313048L, across / CHAIN_ANISO + region.ox, along + region.oz, CHAIN_SCALE, 1))
            * smooth(GAP_AT, GAP_AT + .15, noise(0x5248313049L, x, z, GAP_SCALE));
        double blob = smooth(BLOB_AT, BLOB_AT + .1, noise(0x524831304AL, x, z, BLOB_SCALE));
        return Math.max(chain, blob);
    }

    /** Island weight (0..1) at a position: the footprint of the island chains, 0 where the edge is not raised (used by the identity gate). */
    double island(double x, double z) {
        var land = this.land;
        if (land == null) return 0;
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], x, z);
            if (w > 0) best = Math.max(best, w * island(region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]), x, z));
        }
        return best;
    }

    /**
     * Valley factor 0..1 of one region at a position: a trunk network with branches, rotated rigidly about the region
     * centre. Ria regions use one pair of thresholds on the stronger of trunk and branch; fjord regions stretch the network
     * further and give trunks and side arms their own thresholds, so trunks stay wider than side arms.
     */
    private static double valley(Region region, double x, double z, double shift, double narrow) {
        double dx = x - region.cx, dz = z - region.cz;
        double along = dx * region.gx + dz * region.gz, across = -dx * region.gz + dz * region.gx;
        if (region.fjord) {
            double u = across + region.ox, v = along / FJORD_ANISO + region.oz, drop = 0;
            if (!Double.isNaN(region.soundX)) { // a sound basin: the thresholds drop toward its centre
                double sx = x - region.soundX, sz = z - region.soundZ;
                drop = SOUND_DROP * (1 - smooth(.5 * SOUND_RADIUS, SOUND_RADIUS, Math.sqrt(sx * sx + sz * sz)));
            }
            return Math.max(smooth(FJORD_TRUNK_LOW - drop, FJORD_TRUNK_HIGH - drop, ridge(0x5248313045L, u, v, TRUNK_SCALE, 2)),
                smooth(FJORD_VALLEY_LOW - drop, FJORD_VALLEY_HIGH - drop, FJORD_BRANCH_WEIGHT * ridge(0x5248313046L, u, v, FJORD_BRANCH_SCALE, 2)));
        }
        double u = across + region.ox, v = along / ANISO + region.oz;
        double valley = smooth(VALLEY_LOW + shift, VALLEY_HIGH + shift, Math.max(ridge(0x5248313045L, u, v, TRUNK_SCALE, 2), BRANCH_WEIGHT * ridge(0x5248313046L, u, v, BRANCH_SCALE, 2)));
        if (narrow <= 0 || valley <= 0) return valley;
        // Mouth: keep only a band about the first trunk octave's contour (|2n - 1| small), which is one connected line, so the entrance narrows without ever closing.
        double band = 1 - smooth(BAND_LO, BAND_HI, Math.abs(2 * noise(0x5248313045L, u, v, TRUNK_SCALE) - 1));
        return valley * (1 - narrow * (1 - band));
    }

    /** Threshold shift of a ria region at inland coordinate {@code u} (edge units above the coast point): negative widens the basin; 0 in fjord regions. */
    private static double bayWiden(Region region, double u) {
        if (region.fjord) return 0;
        return -BAY_WIDEN * smooth(WIDEN_FROM * region.reach, .5 * region.reach, u) * (1 - smooth(.5 * region.reach, WIDEN_TO * region.reach, u));
    }

    /** Mouth narrowing weight of a ria region at {@code u}: MOUTH_NARROW at and seaward of the coast point, 0 from MOUTH_LEN blocks inland; 0 in fjord regions. */
    private static double bayNarrow(Region region, double u) {
        return region.fjord ? 0 : MOUTH_NARROW * (1 - smooth(0, MOUTH_LEN * HighlandCoast.EDGE_PER_BLOCK, u));
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
        boolean fjord = fjordAnchor(cx, cz);
        double reach = !fjord ? reachEdge : (unit(mix(h + 8)) < LONG_CHANCE ? longReachEdge : FJORD_REACH * HighlandCoast.EDGE_PER_BLOCK);
        double[] sound = fjord && unit(mix(h + 9)) < SOUND_CHANCE ? coastPoint(cx, cz, gx, gz) : null;
        var region = new Region(i, j, cx, cz, radius(h), gx, gz, 1e4 * unit(mix(h + 6)), 1e4 * unit(mix(h + 7)), fjord, reach, fjord ? fjordTarget : riaTarget,
            sound == null ? Double.NaN : sound[0], sound == null ? Double.NaN : sound[1]);
        m.slots[m.next] = region;
        m.next = (m.next + 1) % MEMO;
        return region;
    }

    /**
     * The coast point of a region: from its centre along the inland direction (or against it) to where the standalone edge
     * crosses the coast point, found by 128-block steps up to {@link #COAST_SEARCH} blocks and bisection; null when the
     * centre is in a skipped owner or no crossing lies in reach.
     */
    private double[] coastPoint(double cx, double cz, double gx, double gz) {
        double e0 = coast.undrowned(cx, cz).edge();
        if (!(e0 > 0)) return null;
        boolean land = e0 > coastEdge;
        double sign = land ? -1 : 1, lo = 0;
        for (double d = 128; d <= COAST_SEARCH; d += 128) {
            double e = coast.undrowned(cx + sign * d * gx, cz + sign * d * gz).edge();
            if (!(e > 0) || (e > coastEdge) != land || e == coastEdge) {
                double hi = d;
                for (int k = 0; k < 10; k++) {
                    double mid = (lo + hi) / 2, em = coast.undrowned(cx + sign * mid * gx, cz + sign * mid * gz).edge();
                    if (em > 0 && (em > coastEdge) == land && em != coastEdge) lo = mid; else hi = mid;
                }
                return new double[]{cx + sign * hi * gx, cz + sign * hi * gz};
            }
            lo = d;
        }
        return null;
    }
    private static final double COAST_SEARCH = 6000;

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
