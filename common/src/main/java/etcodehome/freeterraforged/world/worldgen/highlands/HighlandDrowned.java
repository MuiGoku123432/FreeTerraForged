package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.mix;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.noise;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.ridge;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.smooth;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

/**
 * RH10 drowned coast: flooded river-valley bays (rias) as a transform of the coast edge value, a function of
 * position only so the drainage plan and the renderer share it (D-17).
 *
 * <p>Regions are jittered discs (one candidate per {@link #REGION_SIZE} cell, active with chance
 * {@link #REGION_CHANCE}); each has a weight 1 at its core falling to 0 at its rim. Inside a region the edge is
 * pulled down toward {@link #riaTarget} along the coastal valleys of the column's owner (D-GC1): the valley axes of its plan0, the
 * RH9 planner's plan on a coast whose drowned layer is never bound ({@link HighlandArms}, built once per owner, see {@link #arms}),
 * each with a half width from its drainage area, fading out inland of the coast point so arms shoal toward their heads. Apart from the island chains
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
    static final double REGION_SIZE = 5500, REGION_CHANCE = .32, REGION_RADIUS = .4, REGION_RADIUS_JITTER = .2, REGION_CORE = .8;
    /** Valley network: stretch along the inland direction, trunk and branch wavelengths (blocks), branch weight, valley thresholds. */
    static final double ANISO = 2.5, TRUNK_SCALE = 2000, BRANCH_SCALE = 700, BRANCH_WEIGHT = .8, VALLEY_LOW = .45, VALLEY_HIGH = .85;
    /** Blocks inland of the coast point over which arms shoal, and how far below the beach point arm water sits (fraction of beach to shallow ocean). */
    static final double ARM_REACH = 2500, DROWN_MARGIN = .3;
    /**
     * Arms along plan0 valleys (D-GC1): arm half width is {@code HW_REF (area / 1 km^2)^HW_EXP} blocks of drainage area, at least
     * {@code HW_MIN} and at most {@code MAX_HALF_WIDTH} (the widest any later tuning may use; also how far a region disc is grown for
     * the arm data filter), {@code HW_REF} per region type; the valley profile is full inside {@code ARM_CORE} of the half width and
     * falls to 0 at it; the lowering fades in over the {@code ARM_SEA_BAND} blocks seaward of the old coastline.
     */
    static final double MAX_HALF_WIDTH = 1200, ARM_SEA_BAND = 150, HW_REF = 90, FJORD_HW_REF = 60, HW_EXP = .35, HW_MIN = 24, ARM_CORE = .55, FJORD_ARM_CORE = .7;
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
    /**
     * Open-sea islands (D-10): a few RH-native islands offshore, independent of the regions, built from the same edge field. One
     * jittered cell per {@code OPEN_SPACING} blocks is active with chance {@code OPEN_CHANCE}; its island centre is the first of
     * {@code OPEN_TRIALS} hashed points whose standalone edge is in the open-sea band (at or below the shallow-ocean point, at or above
     * {@link #islandMinEdge}). Radius is log-uniform from {@code OPEN_MIN_RADIUS} to {@code OPEN_MAX_RADIUS} blocks with an outline
     * noise of relative amplitude {@code OPEN_OUTLINE}; the top stands {@code OPEN_PEAK} blocks of coast ramp above the coast point
     * for the largest island and {@code sqrt(r / OPEN_MAX_RADIUS)} of that for a smaller one; the flank is the outer
     * {@code OPEN_FLANK} of the radius. The weight is zero at and below {@link #islandMinEdge} ({@code OPEN_GUARD} times the deep-ocean point,
     * above 0) and rises over the next {@code OPEN_GUARD_FADE} of it, so an island never reaches a neighbouring owner or a region border,
     * and it keeps the same coast gap as the island chains.
     */
    static final double OPEN_SPACING = 6000, OPEN_CHANCE = .5, OPEN_MIN_RADIUS = 380, OPEN_MAX_RADIUS = 1000, OPEN_OUTLINE = .2, OPEN_PEAK = 450,
        OPEN_FLANK = .3, OPEN_GUARD = .5, OPEN_GUARD_FADE = .2;
    private static final int OPEN_TRIALS = 24;
    /** Half distance (blocks) of the central differences that orient a region. */
    private static final double GRADIENT_STEP = 256;
    /**
     * Region seed salt. REGION_SIZE, REGION_CHANCE, REGION_CORE and this salt were tuned together (11-02 Task 2, a sweep over
     * sizes 3000-6000, cores .35/.6/.8 and salts 0-900) so that the drowned share is 20-30% of the RH9 coastline in both
     * calibration boxes (user world, ROCKIES) and the RH9 coast gates, which measure outside the regions, keep a
     * representative non-drowned coast (including the PNW bay shore of the user box). 11-07 raised REGION_CHANCE from .3 to .32 (more
     * regions only: every earlier region keeps its place) because the mean over two seeds and two presets, measured on 64 km windows,
     * was 19.4% (19.8% on 128 km windows; D-02 asks 20-30%); it is 20.8% now. .33 (21.3%) turned the pinned junction of
     * HighlandConfluenceWaterTest (seed 8675309) into drowned coast.
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
        /** The open-sea island candidates of the 3x3 cells around (oi, oj): centre, radius and the hash that shapes them. */
        int oi, oj, ocount;
        boolean ovalid;
        final double[] ox = new double[9], oz = new double[9], oradius = new double[9];
        final long[] oh = new long[9];
    }

    private final HighlandCoast coast;
    private final long seed;
    private final double coastEdge, riaTarget, fjordTarget, reachEdge, wallEdge, longReachEdge, depthFloor, shelfEdge, islandMinEdge, openBandEdge;
    /** Lowest edge value the transform can change: the ria target, or the depth floor (below the fjord target) when the land has ranges. */
    private volatile double lowEdge;
    private final long openSeed;
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);
    private volatile HighlandLand land;
    /** The base planner (plan0, never bound to a drowned layer) and the arm reach (edge units) of the bound land; arms of an owner, built once. */
    private volatile HighlandDrainage base;
    private volatile double armReach;
    /** Arm data of the {@code MAX_ARM_OWNERS} most recently used owners (completed entries only are evicted, so a build in flight is never lost). */
    static final int MAX_ARM_OWNERS = 16;
    private final java.util.concurrent.ConcurrentHashMap<Long, ArmEntry> armCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong armClock = new java.util.concurrent.atomic.AtomicLong();
    /** Bumped by every clear of the cache, so a per-thread memo from before it is never served. */
    private volatile int armGeneration;
    private final ThreadLocal<LastArms> lastArms = new ThreadLocal<>();
    /**
     * Set on a thread while it builds plan0 for the arm data; entering the arm lookup then is a programming error (D-GC1: plan0
     * samples only the inert base coast). Test access: the one-way gate sets it directly.
     */
    static final ThreadLocal<Boolean> BUILDING_PLAN0 = new ThreadLocal<>();
    private static final class ArmEntry {
        final java.util.concurrent.CompletableFuture<HighlandArms> future = new java.util.concurrent.CompletableFuture<>();
        volatile long lastUse;
    }
    private record LastArms(int generation, long key, HighlandArms arms) {}
    /** The one-way guard tripped: never a data condition, so it is never cached as a failed build. */
    private static final class Plan0Read extends IllegalStateException {
        Plan0Read() { super("RH10 plan0 must not read the drowned coast"); }
    }

    HighlandDrowned(GeneratorContext context, HighlandCoast coast) {
        var points = context.preset.world().controlPoints;
        this.coast = coast;
        this.seed = mix(context.seed.root() ^ 0x5248313044L ^ SEED_SALT);
        this.openSeed = mix(context.seed.root() ^ 0x524831304BL);
        this.coastEdge = points.coast;
        this.riaTarget = points.beach - DROWN_MARGIN * (points.beach - points.shallowOcean);
        this.fjordTarget = points.shallowOcean - DROWN_MARGIN * (points.shallowOcean - points.deepOcean);
        this.reachEdge = ARM_REACH * HighlandCoast.EDGE_PER_BLOCK;
        this.wallEdge = FJORD_WALL * HighlandCoast.EDGE_PER_BLOCK;
        this.longReachEdge = FJORD_REACH * LONG_FACTOR * HighlandCoast.EDGE_PER_BLOCK;
        this.depthFloor = Math.max(points.deepOcean, .01);
        this.shelfEdge = points.deepOcean;
        this.islandMinEdge = OPEN_GUARD * points.deepOcean;
        this.openBandEdge = points.shallowOcean;
        this.lowEdge = riaTarget;
        this.armReach = reachEdge;
    }

    /**
     * Land source for the range-aware parts of later RH10 steps and the base planner whose plan0 gives the arms; set once, after
     * the continent exists. A null land is inert (the RH9 coast); a null base means no arms (island raises only).
     */
    void bind(HighlandLand land) { bind(land, null); } // the untracked DrownedShapeProbe (user-owned) still calls this form
    void bind(HighlandLand land, HighlandDrainage base) {
        boolean ranges = land != null && land.rangeHeight() > 0;
        this.lowEdge = ranges ? Math.min(riaTarget, depthFloor) : riaTarget;
        this.armReach = ranges ? Math.max(reachEdge, longReachEdge) : reachEdge;
        forgetArms();
        this.base = base;
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
        return land != null && edge > Math.min(lowEdge, Math.min(shelfEdge, islandMinEdge)) && edge < coastEdge + (land.rangeHeight() > 0 ? Math.max(reachEdge, longReachEdge) : reachEdge);
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
     * The edge value at a raw position given the standalone (pre-drowning) edge there, the owner of the position and the region
     * weight: steepened by the fjord weight, then lowered toward each overlapping region's target by weight times the valley
     * factor of the owner's plan0 arms (the smallest result wins, which is continuous where regions of different type overlap).
     */
    At at(double rawX, double rawZ, double edge, int ownerX, int ownerZ) {
        if (!inBand(edge)) return new At(edge, 0);
        double open = openRaise(rawX, rawZ, edge);
        var m = discs((int) Math.floor(rawX / REGION_SIZE), (int) Math.floor(rawZ / REGION_SIZE));
        double weight = 0, fjord = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], rawX, rawZ);
            if (w <= 0) continue;
            weight = Math.max(weight, w);
            if (w > fjord && region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).fjord) fjord = w;
        }
        if (weight <= 0) return new At(open, 0);
        double sea = smooth(coastEdge - ARM_SEA_BAND * HighlandCoast.EDGE_PER_BLOCK, coastEdge, edge);
        HighlandArms arms = HighlandArms.EMPTY;
        if (sea > 0 && edge < coastEdge + armReach) {
            arms = arms(ownerX, ownerZ);
            m = discs((int) Math.floor(rawX / REGION_SIZE), (int) Math.floor(rawZ / REGION_SIZE)); // building the arms read other discs and replaced the memoized cell
        }
        double raised = raise(m, rawX, rawZ, open);
        double steep = steepen(raised, fjord), lowering = 0;
        var hits = arms.query(rawX, rawZ);
        if (hits.count > 0) for (int n = 0; n < m.count; n++) {
            double w = discWeight(m.cx[n], m.cz[n], m.radius[n], rawX, rawZ);
            if (w <= 0) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            double k = w * sea * valley(region, hits);
            lowering = Math.min(lowering, k * (region.target - steep));
        }
        double result = steep + lowering;
        if (fjord > 0 && result < coastEdge) result = Math.max(Math.min(result, depthFloor), coastEdge - (1 + (FJORD_DEPTH - 1) * fjord) * (coastEdge - result));
        return new At(result, weight);
    }

    /**
     * Valley factor 0..1 of one region from the arm segments near the position: the largest over the segments of the valley
     * profile (full inside {@link #ARM_CORE} of the half width, 0 at it) times the arm fade at the segment's own u (today's fade, so
     * ria arms keep full strength out to BAY_PLATEAU of the reach and shoal over the rest, fjord arms shoal from the coast point).
     */
    private static double valley(Region region, HighlandArms.Hits hits) {
        double core = region.fjord ? FJORD_ARM_CORE : ARM_CORE, ref = region.fjord ? FJORD_HW_REF : HW_REF, best = 0;
        for (int h = 0; h < hits.count; h++) {
            double hw = Math.max(HW_MIN, Math.min(MAX_HALF_WIDTH, ref * hits.width[h])), u = hits.u[h];
            if (hits.distance[h] >= hw) continue;
            double fade = u <= 0 ? 1 : 1 - smooth(region.fjord ? 0 : BAY_PLATEAU * region.reach, region.reach, u);
            if (fade > 0) best = Math.max(best, fade * (1 - smooth(core, 1, hits.distance[h] / hw)));
        }
        return best;
    }

    /** Width factor of an arm from its drainage area (blocks^2): {@code (area / 1 km^2)^HW_EXP}; the half width is HW_REF times it. */
    static double armWidth(double area) { return Math.pow(Math.max(area, 0) / 1e6, HW_EXP); }

    /** The widest half width (blocks) an arm of this width factor can have in any region type; also its index radius. */
    static double armRadius(double width) { return Math.max(HW_MIN, Math.min(MAX_HALF_WIDTH, Math.max(HW_REF, FJORD_HW_REF) * width)); }

    /** True where a position lies within a region disc grown by {@link #MAX_HALF_WIDTH} (the arm data filter). */
    boolean nearRegion(double x, double z) {
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        for (int n = 0; n < m.count; n++) if (discWeight(m.cx[n], m.cz[n], m.radius[n] + MAX_HALF_WIDTH, x, z) > 0) return true;
        return false;
    }

    /**
     * The arm data of an owner, built once: plan0 of the owner from the base planner (a separate coast whose drowned layer is
     * never bound, so it never reads this layer), reduced to {@link HighlandArms} and discarded. Lock-free lookups with a per-thread
     * last-owner memo; single flight: the first thread to ask builds on its own thread, others wait on its future (plan0 samples
     * only the base coast, so a waiting thread never holds another entry and no two threads wait on each other). The cache keeps the
     * {@link #MAX_ARM_OWNERS} most recently used owners. Cancellation is never cached; any other failure of plan0 is logged once and
     * caches {@link HighlandArms#EMPTY} for the owner (plan0 is a pure function of seed, preset and owner, so the failure repeats
     * and the coast there is the RH9 one, never an invented network). Empty without a base planner.
     * Throws IllegalStateException when entered on a thread that is building plan0.
     * // note: copies the owner-plan cache pattern of RollingHighlandsContinent.plan (second use, not extracted).
     */
    HighlandArms arms(int ownerX, int ownerZ) {
        if (BUILDING_PLAN0.get() != null) throw new Plan0Read();
        var base = this.base;
        if (base == null) return HighlandArms.EMPTY;
        long key = PosUtil.pack(ownerX, ownerZ);
        int generation = armGeneration;
        var last = lastArms.get();
        if (last != null && last.key == key && last.generation == generation) return last.arms;
        var entry = armCache.get(key);
        if (entry == null) {
            var created = new ArmEntry();
            created.lastUse = armClock.incrementAndGet();
            entry = armCache.putIfAbsent(key, created);
            if (entry == null) {
                entry = created;
                buildArms(created, key, base, ownerX, ownerZ);
                evictArms();
            }
        }
        entry.lastUse = armClock.incrementAndGet();
        var arms = entry.future.join();
        lastArms.set(new LastArms(generation, key, arms));
        return arms;
    }

    private void buildArms(ArmEntry entry, long key, HighlandDrainage base, int ownerX, int ownerZ) {
        try {
            HighlandDrainage.Plan plan0;
            BUILDING_PLAN0.set(Boolean.TRUE);
            try { plan0 = base.build(ownerX, ownerZ); } finally { BUILDING_PLAN0.remove(); }
            entry.future.complete(HighlandArms.of(plan0, coast, coastEdge, armReach, this));
        } catch (java.util.concurrent.CancellationException | Plan0Read | Error failure) {
            entry.future.completeExceptionally(failure);
            armCache.remove(key, entry);
            throw failure;
        } catch (RuntimeException failure) {
            etcodehome.freeterraforged.FTFCommon.LOGGER.warn("Rolling Highlands: owner {},{} has no drowned arms ({}); its coast stays undrowned",
                ownerX, ownerZ, failure.getMessage());
            entry.future.complete(HighlandArms.EMPTY);
        }
    }

    private void evictArms() {
        while (armCache.size() > MAX_ARM_OWNERS) {
            long oldest = Long.MAX_VALUE; Long victim = null;
            for (var e : armCache.entrySet()) {
                if (!e.getValue().future.isDone()) continue;
                if (e.getValue().lastUse < oldest) { oldest = e.getValue().lastUse; victim = e.getKey(); }
            }
            if (victim == null) return;
            armCache.remove(victim);
        }
    }

    /** Test access: drops every cached arm entry and the calling thread's memo (other threads' memos are invalidated by generation). */
    void forgetArms() {
        armGeneration++;
        armCache.clear();
        lastArms.remove();
    }

    /** Test access: the packed owners (PosUtil.pack of owner x, z) that currently have an arm entry. */
    java.util.Set<Long> cachedArmOwners() { return java.util.Set.copyOf(armCache.keySet()); }

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

    /** Open-sea island candidates of the 3x3 cells around (gi, gj), memoized per thread for the last cell. */
    private Memo open(int gi, int gj) {
        var m = memo.get();
        if (m.ovalid && m.oi == gi && m.oj == gj) return m;
        m.ocount = 0;
        for (int j = gj - 1; j <= gj + 1; j++) for (int i = gi - 1; i <= gi + 1; i++) {
            long h = mix(openSeed ^ (i * 0x9e3779b97f4a7c15L) ^ (j * 0x632be59bd9b4e019L));
            if (unit(h) >= OPEN_CHANCE) continue;
            for (int t = 0; t < OPEN_TRIALS; t++) {
                double cx = (i + unit(mix(h + 11 + 2 * t))) * OPEN_SPACING, cz = (j + unit(mix(h + 12 + 2 * t))) * OPEN_SPACING;
                double e = coast.undrowned(cx, cz).edge();
                if (!(e >= islandMinEdge && e <= openBandEdge)) continue;
                int n = m.ocount++;
                m.oh[n] = h; m.ox[n] = cx; m.oz[n] = cz;
                m.oradius[n] = OPEN_MIN_RADIUS * Math.pow(OPEN_MAX_RADIUS / OPEN_MIN_RADIUS, unit(mix(h + 5)));
                break;
            }
        }
        m.oi = gi; m.oj = gj; m.ovalid = true;
        return m;
    }

    /** Footprint weight 0..1 of open-sea island candidate n at a position: 0 outside its (noisy) outline, rising over the outer OPEN_FLANK of the radius. */
    private double openWeight(Memo m, int n, double x, double z) {
        double dx = x - m.ox[n], dz = z - m.oz[n], r = m.oradius[n], d2 = dx * dx + dz * dz, reach = r * (1 + OPEN_OUTLINE);
        if (d2 >= reach * reach) return 0;
        double outline = r * (1 + OPEN_OUTLINE * (2 * noise(openSeed + 3, x, z, Math.max(120, .8 * r)) - 1));
        return smooth(0, OPEN_FLANK, 1 - Math.sqrt(d2) / outline);
    }

    /**
     * The standalone edge with the open-sea islands raised in: zero weight at and below {@link #islandMinEdge}, full from twice it,
     * and no island within the coast gap of the coast point. Position and standalone edge only, like the rest of the transform.
     */
    private double openRaise(double x, double z, double edge) {
        double window = smooth(islandMinEdge, (1 + OPEN_GUARD_FADE) * islandMinEdge, edge)
            * (1 - smooth(coastEdge - (ISLAND_GAP + ISLAND_SOFT) * HighlandCoast.EDGE_PER_BLOCK, coastEdge - ISLAND_GAP * HighlandCoast.EDGE_PER_BLOCK, edge));
        if (window <= 0) return edge;
        var m = open((int) Math.floor(x / OPEN_SPACING), (int) Math.floor(z / OPEN_SPACING));
        double best = edge;
        for (int n = 0; n < m.ocount; n++) {
            double s = openWeight(m, n, x, z);
            if (s <= 0) continue;
            double peak = OPEN_PEAK * HighlandCoast.EDGE_PER_BLOCK * Math.sqrt(m.oradius[n] / OPEN_MAX_RADIUS);
            best = Math.max(best, edge + window * s * (coastEdge + peak - edge));
        }
        return best;
    }

    /** Open-sea island footprint weight (0..1) at a position: above 0 inside an island outline, where the edge may be raised (used by the identity gate). */
    double openIsland(double x, double z) {
        if (land == null) return 0;
        var m = open((int) Math.floor(x / OPEN_SPACING), (int) Math.floor(z / OPEN_SPACING));
        double best = 0;
        for (int n = 0; n < m.ocount; n++) best = Math.max(best, openWeight(m, n, x, z));
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
