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
 * {@link #REGION_CHANCE}); each has a weight 1 at its core falling to 0 at its rim, the rim warped by the bearing ({@link #RIM_WARP}) so no
 * outline is a circle. An arm takes the region weight at its own axis point, not at the column, so it fades along its valley and is never cut by
 * the disc contour; every column an arm lowered, or lies beside one ({@link #ARM_INFLUENCE}), has the arm's weight as its effective region weight
 * ({@link #compute}), the one number {@code Sample.drowned()}, {@link #weight}, {@link #fjord} and {@link #ria} report. Inside a region the edge is
 * pulled down toward {@link #riaTarget} along the coastal valleys of the column's owner (D-GC1, the only source of arms; there
 * is no noise network and no fallback to one): the valley axes of its plan0, the RH9 planner's plan on a coast whose drowned
 * layer is never bound ({@link HighlandArms}), each with a half width from its drainage area, fading out inland of the coast
 * point so arms shoal toward their heads.
 *
 * <p>Arm data dependency is one-way (D-GC1). seed + preset + undrowned coast + land give plan0; plan0 is reduced to
 * {@link HighlandArms} and discarded; the arm data give this layer; the final plan and the renderer read this layer. plan0 never
 * reads this layer: it samples the inert base coast, and {@link #arms} throws IllegalStateException if it is entered on a thread
 * that is building plan0. The arm data of an owner are built once per owner ({@link #arms}: single flight, the
 * {@link #MAX_ARM_OWNERS} most recently used owners kept, cancellation never cached, any other failure of plan0 logged once and
 * cached as no arms), so a column's edge never depends on cache state, sample order or threads.
 *
 * <p>Apart from the island chains (11-06, on the shelf seaward of the coast point, away from owner borders) the transform only
 * lowers the edge (it is monotone in it in fjord regions and in ria regions away from the mouth), so land never goes below sea
 * level, skipped owners (edge 0) are untouched and the coast is bit-for-bit the RH9 coast wherever the weight or the
 * valley factor is 0 and no island stands. The land binding is inert until {@link #bind}: an unbound instance is the identity.
 *
 * <p>Each region is typed once, at its anchor (memoized with its orientation): FJORD where the land has ranges, the
 * regional climate at the region centre is PNW and a range core stands within {@link #REGION_SIZE}/2 of the centre
 * (D-01), otherwise RIA (D-03). Typing reads {@link HighlandCoastProfile#climateClassAt} (raw climate), never the
 * shore, so the shore may read {@link #fjord} without recursion.
 */
final class HighlandDrowned {
    /** Region cell size and chance, disc radius (fraction of the cell size, plus its jitter) and the share of it that is full weight. */
    static final double REGION_SIZE = 5500, REGION_CHANCE = .32, REGION_RADIUS = .4, REGION_RADIUS_JITTER = .2, REGION_CORE = .8;
    /** Blocks inland of the coast point over which arms shoal, and how far below the beach point arm water sits (fraction of beach to shallow ocean). */
    static final double ARM_REACH = 2500, DROWN_MARGIN = .3;
    /**
     * Arms along plan0 valleys (D-GC1): the base arm half width is {@code HW_REF (area / 1 km^2)^HW_EXP} blocks of drainage area
     * ({@code HW_REF} per region type: bays, then fjords), shaped by the terms below, at least {@code HW_MIN} and at most
     * {@code MAX_HALF_WIDTH} (the widest any term may produce, also how far a region disc is grown for the arm data filter); the
     * valley profile is full inside {@code ARM_CORE} of the half width (but its wall is never shorter than {@code ARM_WALL_MIN}
     * blocks) and falls to 0 at the half width; the lowering fades in over the {@code ARM_SEA_BAND} blocks seaward of the old coastline.
     */
    static final double MAX_HALF_WIDTH = 1200, ARM_SEA_BAND = 150, HW_REF = 145, FJORD_HW_REF = 83, HW_EXP = .5, HW_MIN = 24, ARM_CORE = .55, FJORD_ARM_CORE = .5,
        ARM_WALL_MIN = 16;
    /**
     * Arm eligibility (D-GC8): a physical valley carries an arm only where its drainage area is at least {@code ARM_AREA} of
     * uplandSpacing squared (the RH5 creek-head area at the reference River Count), or {@code FJORD_ARM_AREA} in a fjord region (steep
     * range country drains in small valleys, and side valleys become side arms). Fixed shape constants; they never read River Count
     * (creekArea) and never the structural lake area.
     */
    static final double ARM_AREA = .065, FJORD_ARM_AREA = .045;
    /** Range core (0..1) a region needs within half a region of its centre to be a fjord region. */
    static final double FJORD_RANGE_CORE = .25;
    /**
     * Fjord regions (D-05, D-06), tuned in 11-04 Task 2: ramp steepening factor and the blocks inland it acts over; arms fade out
     * over {@code FJORD_REACH} blocks inland and {@code LONG_CHANCE} of fjord regions fade over {@code LONG_FACTOR} times that
     * (the occasional long arm).
     */
    static final double FJORD_STEEP = 4, FJORD_WALL = 500, FJORD_REACH = 6000, LONG_CHANCE = .15, LONG_FACTOR = 2.5;
    /**
     * Blocks beyond the half width of an arm over which the arm's region weight fades to 0 (11-13): a column an arm lowered, or lies within
     * its half width or this band of one, is treated as drowned by every column-facing reader (shores, water tags, marsh, steepening), also
     * beyond the disc rim. The plan started at {@link #FJORD_WALL} (500); that took the weight, hence the RH9 coast gates' "drowned" skip, over
     * the cliff coast beside the arms (user box: cliff share 8%, no sea stacks left to measure), so the band is the 80 blocks a shore column
     * can lie from arm water (tested at 64) and the weight inside the half width is what carries the shores.
     */
    static final double ARM_INFLUENCE = 80;
    /**
     * Rim warp (11-13): the radius of a region disc is scaled by {@code 1 + RIM_WARP n(bearing)}, n a zero-mean angular noise of the
     * bearing from the centre (3 and 5 lobes per circle, phases from the region hash, |n| at most 1), so no flood ends on a circle.
     * The static check below keeps the widest warped disc plus its arm reach inside the 3x3 cells every lookup reads.
     */
    static final double RIM_WARP = .15;
    static {
        if (REGION_SIZE * (REGION_RADIUS + REGION_RADIUS_JITTER) * (1 + RIM_WARP) + MAX_HALF_WIDTH + ARM_INFLUENCE > REGION_SIZE)
            throw new ExceptionInInitializerError("RH10 region reach exceeds the 3x3 cells of a lookup");
    }
    /**
     * Fjord depth (D-06, D-17): the flooded part of the edge, its distance below the coast point, is stretched by
     * {@code 1 + (FJORD_DEPTH - 1) f}, so arm water falls below the shallow-ocean point where the legacy ocean populators
     * give deep floors (the shoreface alone never goes deeper than the shelf). The flooded set is unchanged (the stretch
     * keeps the sign of {@code edge - coast}); the edge never falls below {@link #depthFloor}.
     */
    static final double FJORD_DEPTH = 2;
    /**
     * Sounds (D-07): {@code SOUND_CHANCE} of fjord regions get a sound basin around the region's coast point; arm half widths there are
     * multiplied by {@code 1 + SOUND_WIDEN} within half of {@code SOUND_RADIUS} blocks of it, falling to 1 at {@code SOUND_RADIUS}, so arms
     * meeting near the coast point open into water 1-3 km across (the land left between merged arms is the sound's islands).
     */
    static final double SOUND_CHANCE = .3, SOUND_RADIUS = 3000, SOUND_WIDEN = 8;
    /**
     * Ria bays (D-13, D-14): the arm half width is multiplied by {@code MOUTH_NARROW_MIN} at and seaward of the coast point, rising to 1
     * over {@code MOUTH_LEN} blocks inland (the bay narrows where its valley crosses the old coastline and never closes), and by up to
     * {@code 1 + BAY_WIDEN_FACTOR} over the middle of the arm reach (from {@code WIDEN_FROM} to half of it full, falling to 0 at
     * {@code WIDEN_TO}), so water spreads into a basin inside the mouth. Ria arms keep full strength out to {@code BAY_PLATEAU} of the reach and
     * shoal over the rest (fjords shoal from the coast point).
     */
    static final double MOUTH_LEN = 400, MOUTH_NARROW_MIN = .25, BAY_WIDEN_FACTOR = 2.2, WIDEN_FROM = .15, WIDEN_TO = .9, BAY_PLATEAU = .6;
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
    static final double ISLAND_GAP = 100, ISLAND_SOFT = 220, ISLAND_PEAK = 300, ISLAND_PEAK_FJORD = 750,
        CHAIN_ANISO = 3, CHAIN_SCALE = 1200, CHAIN_AT = .4, CHAIN_HIGH = .7, GAP_SCALE = 700, GAP_AT = .15, BLOB_SCALE = 2400, BLOB_AT = .4;
    /**
     * Island reshaping (11-15, G-11-2 b, G-11-4, D-GC3): the chain crest is {@code CHAIN_OCTAVES} ridge octaves and its (across, along) coordinates are
     * domain-warped by {@code CHAIN_WARP} blocks at wavelength {@code CHAIN_WARP_SCALE}, so chains bend and break instead of running as ruler lines.
     * An island is land only where its raise lifts the ground at least {@code ISLAND_MIN_TOP} blocks of coast ramp above the coast point
     * ({@code ISLAND_MIN_TOP_FJORD} in fjord regions): the raise is shifted down by that amount and the shift fades out by {@code LIFT_BLEND} times it
     * ({@link #lift}; a fade over 3 times it steepened the island flanks past the edge step limit of edgeIsContinuous), so no few-block strips stand.
     * The density falls gradually toward the warped rim: the chain and blob thresholds rise by {@code ENV_RISE (1 - e)} with e the island envelope, 1 inside {@code ENV_CORE} of the warped radius and 0 at the rim
     * ({@link #envelope}), instead of the weight cutting the field on the disc contour. The shelf window's coast side is pushed seaward
     * by up to {@code WINDOW_ROUGH} blocks of coast ramp of a low-frequency noise ({@code WINDOW_ROUGH_SCALE}), never toward the coast.
     */
    static final double CHAIN_WARP = 400, CHAIN_WARP_SCALE = 1500, ISLAND_MIN_TOP = 5, ISLAND_MIN_TOP_FJORD = 12, ENV_CORE = .7, ENV_RISE = .1,
        WINDOW_ROUGH = 120, WINDOW_ROUGH_SCALE = 2200, LIFT_BLEND = 8;
    static final int CHAIN_OCTAVES = 2;
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
        OPEN_FLANK = .3, OPEN_GUARD = .5, OPEN_GUARD_FADE = .5;
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
    /**
     * A region anchor: centre, disc radius, inland direction (unit), noise offsets, type (fjord, else ria), arm reach and target, sound basin centre (NaN: none),
     * the rim warp phases as (cos, sin) pairs of the 3-lobe and the 5-lobe term.
     */
    record Region(int i, int j, double cx, double cz, double radius, double gx, double gz, double ox, double oz, boolean fjord, double reach, double target, double soundX, double soundZ,
        double ca, double sa, double cb, double sb) {}
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
        final double[] cx = new double[9], cz = new double[9], radius = new double[9], ca = new double[9], sa = new double[9], cb = new double[9], sb = new double[9];
        /** Effective region weights of the last columns looked up (round robin), see {@link #compute}. */
        final Eff[] eff = {new Eff(), new Eff(), new Eff(), new Eff()};
        int nextEff;
        /** The open-sea island candidates of the 3x3 cells around (oi, oj): centre, radius and the hash that shapes them. */
        int oi, oj, ocount;
        boolean ovalid;
        final double[] ox = new double[9], oz = new double[9], oradius = new double[9];
        final long[] oh = new long[9];
    }

    /**
     * Effective region weights at one column (11-13): per active disc of the column's 3x3 cells (same order as {@link Memo}), the weight
     * {@code w} (the disc weight, or the larger weight an arm of the region gives the column) and the arm lowering strength {@code k}
     * (sea fade included); {@code all} is their largest weight. Valid for the exact (x, z) and arm generation it was computed for.
     */
    private static final class Eff {
        double x, z, all;
        int generation, count;
        boolean valid;
        final double[] w = new double[9], k = new double[9], term = new double[2];
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
            double a = 2 * Math.PI * unit(mix(h + 13)), b = 2 * Math.PI * unit(mix(h + 14));
            m.ca[n] = Math.cos(a); m.sa[n] = Math.sin(a); m.cb[n] = Math.cos(b); m.sb[n] = Math.sin(b);
        }
        m.gi = gi; m.gj = gj; m.valid = true;
        return m;
    }

    /**
     * Position-only effective region weight (0..1): the largest over the regions near the position of the weight the transform used
     * there, which is the disc weight or, beside a lowered arm (also beyond the disc rim), the weight the arm gives its neighbourhood
     * ({@link #ARM_INFLUENCE}); {@code Sample.drowned()} is the same number. Memoized per thread per column.
     */
    double weight(double x, double z) { return effective(x, z).all; }

    /** Test access: the largest warped disc weight at the column alone (the pre-11-13 meaning of {@link #weight}), no arm term. */
    double columnWeight(double x, double z) {
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) best = Math.max(best, discWeight(m, n, x, z));
        return best;
    }

    /**
     * Position-only effective fjord weight (0..1): the largest {@link #weight} term over the FJORD regions near the position; 0 when
     * unbound or when the land has no ranges.
     */
    double fjord(double x, double z) {
        var land = this.land;
        if (land == null || land.rangeHeight() <= 0) return 0;
        return kindWeight(effective(x, z), x, z, true);
    }

    /**
     * Position-only effective ria weight (0..1): the largest {@link #weight} term over the RIA (non-fjord) regions near the position; 0
     * when unbound. Used by the shore profile and the marsh tagging (D-16), which want the bay character, not the fjord one.
     */
    double ria(double x, double z) {
        if (land == null) return 0;
        return kindWeight(effective(x, z), x, z, false);
    }

    /** The largest weight of {@code e} over the regions of one kind (FJORD or RIA); {@code e} was computed for the column (x, z). */
    private double kindWeight(Eff e, double x, double z, boolean fjord) {
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < e.count; n++) {
            double w = e.w[n];
            if (w > best && region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).fjord == fjord) best = w;
        }
        return best;
    }

    /**
     * The region with the largest effective weight at the position (among FJORD regions only when {@code fjordOnly}); null when
     * there is none.
     */
    Region dominant(double x, double z, boolean fjordOnly) {
        var e = effective(x, z);
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        Region best = null;
        double bestWeight = 0;
        for (int n = 0; n < e.count; n++) {
            double w = e.w[n];
            if (w <= bestWeight) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            if (fjordOnly && !region.fjord) continue;
            best = region; bestWeight = w;
        }
        return best;
    }

    /** The effective region weights at a column (readers without the owner and base edge): the memoized entry, else computed with the owning coast's undrowned sample. */
    private Eff effective(double x, double z) {
        var m = memo.get();
        int generation = armGeneration;
        for (var e : m.eff) if (e.valid && e.x == x && e.z == z && e.generation == generation) return e;
        return compute(x, z, Double.NaN, 0, 0);
    }

    /**
     * The one routine behind {@code Sample.drowned()}, {@link #weight}, {@link #fjord}, {@link #ria} and the arm lowering of {@link #at}.
     * Per active disc n: {@code W_n = max(w_n(column), sea x max over arm segments s of w_n(axis point of s) x fade_n(u_s) x
     * (1 - smooth(hw_s, hw_s + ARM_INFLUENCE, d_s)))}, so an arm's whole cross-section, and the band beside it, carries the region
     * weight at its own axis whatever the disc rim does at the column, and {@code k_n = sea x max over s of w_n(axis) x fade x
     * profile(d_s / hw_s)} (see {@link #armTerms}). {@code edge} and the owner are the column's undrowned sample (NaN edge: looked up here,
     * only when a disc is near enough for an arm to matter). The arms are read exactly where the old lowering read them (sea above 0 and
     * the edge inside the arm reach), so a base coast (no land, no arms) never reaches the arm lookup and plan0 stays one-way.
     */
    private Eff compute(double x, double z, double edge, int ownerX, int ownerZ) {
        var mem = memo.get();
        int gi = (int) Math.floor(x / REGION_SIZE), gj = (int) Math.floor(z / REGION_SIZE);
        var m = discs(gi, gj);
        var e = mem.eff[mem.nextEff];
        mem.nextEff = (mem.nextEff + 1) % mem.eff.length;
        e.valid = false;
        e.count = m.count;
        e.all = 0;
        boolean near = false;
        for (int n = 0; n < m.count; n++) {
            double dx = x - m.cx[n], dz = z - m.cz[n], reach = m.radius[n] * (1 + RIM_WARP) + MAX_HALF_WIDTH + ARM_INFLUENCE;
            near |= dx * dx + dz * dz < reach * reach;
            double w = discWeight(m, n, x, z);
            e.w[n] = w; e.k[n] = 0;
            e.all = Math.max(e.all, w);
        }
        if (near && this.land != null) {
            if (Double.isNaN(edge)) {
                var base = coast.undrowned(x, z);
                edge = base.edge(); ownerX = base.ownerX(); ownerZ = base.ownerZ();
            }
            double sea = smooth(coastEdge - ARM_SEA_BAND * HighlandCoast.EDGE_PER_BLOCK, coastEdge, edge);
            if (sea > 0 && edge < coastEdge + armReach) {
                var arms = arms(ownerX, ownerZ);
                m = discs(gi, gj); // building the arms read other discs and replaced the memoized cell
                var hits = arms.query(x, z);
                if (hits.count > 0) for (int n = 0; n < m.count; n++) {
                    armTerms(region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]), hits, x, z, e.term);
                    e.k[n] = sea * e.term[0];
                    e.w[n] = Math.max(e.w[n], sea * e.term[1]);
                    e.all = Math.max(e.all, e.w[n]);
                }
            }
        }
        e.x = x; e.z = z; e.generation = armGeneration; e.valid = true;
        return e;
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
     * The edge value at a raw position given the standalone (pre-drowning) edge there and the owner of the position: steepened by the
     * effective fjord weight, then lowered toward each region's target by the arm strength {@link #compute} found for it (the smallest
     * result wins, which is continuous where regions of different type overlap). The drowned weight is the effective region weight, 0 where
     * the edge is the RH9 one (every lowered, steepened or raised column has an effective weight above 0).
     */
    At at(double rawX, double rawZ, double edge, int ownerX, int ownerZ) {
        if (!inBand(edge)) return new At(edge, 0);
        double open = openRaise(rawX, rawZ, edge);
        var e = compute(rawX, rawZ, edge, ownerX, ownerZ);
        if (e.all <= 0) return new At(open, 0);
        var m = discs((int) Math.floor(rawX / REGION_SIZE), (int) Math.floor(rawZ / REGION_SIZE));
        double fjord = kindWeight(e, rawX, rawZ, true);
        double raised = Math.max(open, raise(m, rawX, rawZ, edge)); // the chains read the standalone edge, not the open-sea raise: stacked windows made a step
        double steep = steepen(raised, fjord), lowering = 0;
        for (int n = 0; n < e.count; n++) {
            if (e.k[n] <= 0) continue;
            lowering = Math.min(lowering, e.k[n] * (region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).target - steep));
        }
        double result = steep + lowering;
        if (fjord > 0 && result < coastEdge) result = Math.max(Math.min(result, depthFloor), coastEdge - (1 + (FJORD_DEPTH - 1) * fjord) * (coastEdge - result));
        return new At(result, e.all);
    }

    /**
     * The arm terms of one region at a position, from the arm segments near it: {@code out[0]} the lowering strength, the largest over the
     * segments of the region weight at the segment's own axis point ({@code anchor}, so an arm's cross-section is never cut by a disc
     * contour and the arm fades along its valley) times the arm fade at the segment's own u (ria arms keep full strength out to BAY_PLATEAU of
     * the reach and shoal over the rest, fjord arms shoal from the coast point) times the valley profile (full inside {@link #ARM_CORE} of the
     * half width, 0 at it); {@code out[1]} the influence, the same without the profile and with a band of {@link #ARM_INFLUENCE} blocks beyond the
     * half width over which it falls to 0 (the weight the arm gives the columns beside it). The half width is the area law times {@link #shape},
     * so an arm stays centred on its plan0 valley. A segment whose axis lies beyond the region's rim (kept for connectivity, see
     * {@link #armKinds}) has anchor 0 and adds nothing, and the anchor scales the ria basin widening, so a wide arm never spills basin water
     * into the disc beside it (11-12, D-GC2 fidelity screen).
     */
    private static void armTerms(Region region, HighlandArms.Hits hits, double x, double z, double[] out) {
        double core = region.fjord ? FJORD_ARM_CORE : ARM_CORE, ref = region.fjord ? FJORD_HW_REF : HW_REF, sound = soundFactor(region, x, z), strength = 0, influence = 0;
        for (int h = 0; h < hits.count; h++) {
            double anchor = regionWeight(region, hits.x[h], hits.z[h]); // the region weight at the axis point
            if (anchor <= 0) continue;
            double u = hits.u[h], d = hits.distance[h];
            double hw = Math.max(HW_MIN, Math.min(MAX_HALF_WIDTH, ref * hits.width[h] * shape(region, u, sound, anchor)));
            if (d >= hw + ARM_INFLUENCE) continue;
            double w = anchor * (u <= 0 ? 1 : 1 - smooth(region.fjord ? 0 : BAY_PLATEAU * region.reach, region.reach, u));
            if (w <= 0) continue;
            influence = Math.max(influence, w * (1 - smooth(hw, hw + ARM_INFLUENCE, d)));
            if (d < hw) strength = Math.max(strength, w * (1 - smooth(Math.min(core, 1 - ARM_WALL_MIN / hw), 1, d / hw)));
        }
        out[0] = strength; out[1] = influence;
    }

    /** Half width factor of an arm at inland coordinate {@code u}: the ria mouth and basin, or the sound factor of a fjord region. */
    private static double shape(Region region, double u, double sound, double anchor) {
        if (region.fjord) return sound;
        double narrow = MOUTH_NARROW_MIN + (1 - MOUTH_NARROW_MIN) * smooth(0, MOUTH_LEN * HighlandCoast.EDGE_PER_BLOCK, u);
        return narrow * (1 + BAY_WIDEN_FACTOR * bayWiden(region, u) * anchor);
    }

    /** Sound factor (1 to 1 + SOUND_WIDEN) of a fjord region at a position: full within half of SOUND_RADIUS of its sound centre, 1 from SOUND_RADIUS; 1 without a sound. */
    private static double soundFactor(Region region, double x, double z) {
        if (Double.isNaN(region.soundX)) return 1;
        return 1 + SOUND_WIDEN * (1 - smooth(.5 * SOUND_RADIUS, SOUND_RADIUS, Math.hypot(x - region.soundX, z - region.soundZ)));
    }

    /** The arm eligibility area (fraction of uplandSpacing squared) at a position: {@link #FJORD_ARM_AREA} inside a fjord region, else {@link #ARM_AREA}. */
    double armArea(double x, double z) { return columnFjord(x, z) > 0 ? FJORD_ARM_AREA : ARM_AREA; }

    /** The fjord disc weight at the column alone (no arm term: the arm data filter reads it while the arms are being built); 0 when unbound or the land has no ranges. */
    private double columnFjord(double x, double z) {
        var land = this.land;
        if (land == null || land.rangeHeight() <= 0) return 0;
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) {
            double w = discWeight(m, n, x, z);
            if (w > best && region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]).fjord) best = w;
        }
        return best;
    }

    /** Width factor of an arm from its drainage area (blocks^2): {@code (area / 1 km^2)^HW_EXP}; the half width is HW_REF times it. */
    static double armWidth(double area) { return Math.pow(Math.max(area, 0) / 1e6, HW_EXP); }

    /** The widest half width (blocks) an arm of this width factor can have in the region kinds ({@link #armKinds}) near it, basin or sound widening included. */
    static double armRadius(double width, int kinds) {
        double factor = 0;
        if ((kinds & RIA) != 0) factor = HW_REF * (1 + BAY_WIDEN_FACTOR);
        if ((kinds & FJORD) != 0) factor = Math.max(factor, FJORD_HW_REF);
        if ((kinds & FJORD_SOUND) != 0) factor = Math.max(factor, FJORD_HW_REF * (1 + SOUND_WIDEN));
        return Math.max(HW_MIN, Math.min(MAX_HALF_WIDTH, factor * width));
    }

    /** Index radius of an arm segment (blocks): its widest half width plus {@link #ARM_INFLUENCE}, so a query finds every segment whose influence band holds the position. */
    static double queryRadius(double width, int kinds) { return armRadius(width, kinds) + ARM_INFLUENCE; }

    /** Region kinds of {@link #armKinds}: bay regions, fjord regions, fjord regions with a sound. */
    static final int RIA = 1, FJORD = 2, FJORD_SOUND = 4;

    /**
     * The kinds of region ({@link #RIA}, {@link #FJORD}, {@link #FJORD_SOUND} bits) whose warped disc grown by {@link #MAX_HALF_WIDTH} holds a
     * position; 0 where none does (the arm data filter, and what sets the index radius of a segment there).
     */
    int armKinds(double x, double z) {
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        int kinds = 0;
        for (int n = 0; n < m.count; n++) {
            double dx = x - m.cx[n], dz = z - m.cz[n], d2 = dx * dx + dz * dz, outer = m.radius[n] * (1 + RIM_WARP) + MAX_HALF_WIDTH;
            if (d2 >= outer * outer) continue;
            double d = Math.sqrt(d2);
            if (d > 0 && d >= rimRadius(m.radius[n], m.ca[n], m.sa[n], m.cb[n], m.sb[n], dx / d, dz / d) + MAX_HALF_WIDTH) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            kinds |= !region.fjord ? RIA : Double.isNaN(region.soundX) ? FJORD : FJORD_SOUND;
        }
        return kinds;
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
        double window = shelfWindow(x, z, edge);
        if (window <= 0) return edge;
        double best = edge;
        for (int n = 0; n < m.count; n++) {
            double env = envelope(m, n, x, z);
            if (env <= 0) continue;
            var region = region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]);
            double s = window * island(region, x, z, env);
            if (s <= 0) continue;
            double peak = (region.fjord ? ISLAND_PEAK_FJORD : ISLAND_PEAK) * HighlandCoast.EDGE_PER_BLOCK;
            best = Math.max(best, lift(edge + s * (coastEdge + peak - edge), (region.fjord ? ISLAND_MIN_TOP_FJORD : ISLAND_MIN_TOP) * HighlandCoast.EDGE_PER_BLOCK));
        }
        return best;
    }

    /** The shelf window of the island chains at a position: over the shelf edge, and not within the coast gap (its coast side roughened seaward by a low-frequency noise). */
    private double shelfWindow(double x, double z, double edge) {
        double rough = WINDOW_ROUGH * HighlandCoast.EDGE_PER_BLOCK * noise(0x524831304BL, x, z, WINDOW_ROUGH_SCALE);
        return smooth(shelfEdge, shelfEdge + ISLAND_SOFT * HighlandCoast.EDGE_PER_BLOCK, edge)
            * (1 - smooth(coastEdge - (ISLAND_GAP + ISLAND_SOFT) * HighlandCoast.EDGE_PER_BLOCK, coastEdge - ISLAND_GAP * HighlandCoast.EDGE_PER_BLOCK, edge + rough));
    }

    /**
     * Minimum island top: a raised edge value whose lift above the coast point is {@code u} becomes {@code u - m (1 - smooth(m, LIFT_BLEND m, u))} (m the minimum
     * top in edge units): land begins where the raise reaches m, and from {@code LIFT_BLEND} m up the raise is untouched. Monotone and continuous, never above the input.
     */
    private double lift(double raised, double min) {
        double u = raised - coastEdge;
        return coastEdge + u - min * (1 - smooth(min, LIFT_BLEND * min, u));
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

    /**
     * Island weight 0..1 of one region at a position, given the island envelope {@code env} there (see {@link #envelope}): broken, warped chains along the
     * coast (rotated rigidly about the region centre) and broad blobs, both thinned toward the rim by the thresholds rising with {@code 1 - env}.
     */
    private static double island(Region region, double x, double z, double env) {
        double dx = x - region.cx, dz = z - region.cz;
        double along = dx * region.gx + dz * region.gz, across = -dx * region.gz + dz * region.gx;
        double warpA = CHAIN_WARP * (2 * noise(0x524831304CL, x, z, CHAIN_WARP_SCALE) - 1), warpB = CHAIN_WARP * (2 * noise(0x524831304DL, x, z, CHAIN_WARP_SCALE) - 1);
        double rise = ENV_RISE * (1 - env);
        double chain = smooth(CHAIN_AT + rise, CHAIN_HIGH + rise, ridge(0x5248313048L, (across + warpA) / CHAIN_ANISO + region.ox, along + warpB + region.oz, CHAIN_SCALE, CHAIN_OCTAVES))
            * smooth(GAP_AT, GAP_AT + .15, noise(0x5248313049L, x, z, GAP_SCALE));
        double blob = smooth(BLOB_AT + rise, BLOB_AT + .1 + rise, noise(0x524831304AL, x, z, BLOB_SCALE));
        return Math.max(chain, blob) * smooth(0, .1, env);
    }

    /** Island weight (0..1) at a position: the footprint of the island chains, 0 where the edge is not raised (used by the identity gate). */
    double island(double x, double z) {
        var land = this.land;
        if (land == null) return 0;
        var m = discs((int) Math.floor(x / REGION_SIZE), (int) Math.floor(z / REGION_SIZE));
        double best = 0;
        for (int n = 0; n < m.count; n++) {
            double env = envelope(m, n, x, z);
            if (env > 0) best = Math.max(best, island(region(m.h[n], m.ci[n], m.cj[n], m.cx[n], m.cz[n]), x, z, env));
        }
        return best;
    }

    /**
     * Island envelope of active disc n of the memoized cell: 1 within {@link #ENV_CORE} of the warped rim radius, falling smoothly to 0 at the rim
     * (the support of {@link #discWeight}, with a wider ramp so the island density fades instead of being cut on the disc contour).
     */
    private static double envelope(Memo m, int n, double x, double z) {
        double dx = x - m.cx[n], dz = z - m.cz[n], d2 = dx * dx + dz * dz, outer = m.radius[n] * (1 + RIM_WARP);
        if (d2 >= outer * outer) return 0;
        double inner = ENV_CORE * m.radius[n] * (1 - RIM_WARP);
        if (d2 <= inner * inner) return 1;
        double d = Math.sqrt(d2), rim = rimRadius(m.radius[n], m.ca[n], m.sa[n], m.cb[n], m.sb[n], dx / d, dz / d);
        return d >= rim ? 0 : 1 - smooth(ENV_CORE * rim, rim, d);
    }

    /** Basin bump 0..1 of a ria region at inland coordinate {@code u} (edge units above the coast point): full at half the reach, 0 outside {@code WIDEN_FROM}..{@code WIDEN_TO} of it. */
    private static double bayWiden(Region region, double u) {
        return smooth(WIDEN_FROM * region.reach, .5 * region.reach, u) * (1 - smooth(.5 * region.reach, WIDEN_TO * region.reach, u));
    }

    /** Disc weight of active disc n of the memoized cell at a position, see {@link #discWeight(double, double, double, double, double, double, double, double, double)}. */
    private static double discWeight(Memo m, int n, double x, double z) {
        return discWeight(m.cx[n], m.cz[n], m.radius[n], m.ca[n], m.sa[n], m.cb[n], m.sb[n], x, z);
    }

    /** Disc weight of a region at a position (test access: the region as {@link #dominant} returns it). */
    static double regionWeight(Region r, double x, double z) { return discWeight(r.cx, r.cz, r.radius, r.ca, r.sa, r.cb, r.sb, x, z); }

    /**
     * Disc weight at a position: 1 inside the core, falling smoothly to 0 at the rim, both scaled by the warped radius of the bearing
     * ({@link #rimRadius}); squared distances until needed (hypot is slow), and no bearing at all inside the narrowest core or outside the widest rim.
     */
    private static double discWeight(double cx, double cz, double radius, double ca, double sa, double cb, double sb, double x, double z) {
        double dx = x - cx, dz = z - cz, d2 = dx * dx + dz * dz, outer = radius * (1 + RIM_WARP), inner = REGION_CORE * radius * (1 - RIM_WARP);
        if (d2 >= outer * outer) return 0;
        if (d2 <= inner * inner) return 1;
        double d = Math.sqrt(d2), rim = rimRadius(radius, ca, sa, cb, sb, dx / d, dz / d), core = REGION_CORE * rim;
        if (d >= rim) return 0;
        return d <= core ? 1 : 1 - smooth(core, rim, d);
    }

    /**
     * Warped disc radius toward the unit bearing (ux, uz) from the centre: {@code radius (1 + RIM_WARP n)}, {@code n = (cos(3 t + a) + cos(5 t + b)) / 2}
     * for the bearing angle t, evaluated by multiplying the unit vector (no trigonometry). Periodic, so the rim closes, and continuous.
     */
    private static double rimRadius(double radius, double ca, double sa, double cb, double sb, double ux, double uz) {
        double c2 = ux * ux - uz * uz, s2 = 2 * ux * uz, c3 = c2 * ux - s2 * uz, s3 = s2 * ux + c2 * uz, c5 = c3 * c2 - s3 * s2, s5 = s3 * c2 + c3 * s2;
        return radius * (1 + RIM_WARP * .5 * (c3 * ca - s3 * sa + c5 * cb - s5 * sb));
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
        double a = 2 * Math.PI * unit(mix(h + 13)), b = 2 * Math.PI * unit(mix(h + 14));
        var region = new Region(i, j, cx, cz, radius(h), gx, gz, 1e4 * unit(mix(h + 6)), 1e4 * unit(mix(h + 7)), fjord, reach, fjord ? fjordTarget : riaTarget,
            sound == null ? Double.NaN : sound[0], sound == null ? Double.NaN : sound[1], Math.cos(a), Math.sin(a), Math.cos(b), Math.sin(b));
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
