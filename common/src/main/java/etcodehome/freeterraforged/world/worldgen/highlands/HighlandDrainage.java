package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Bounded, pure terrain-aware drainage construction. Never calls final terrain or chunk caches. */
public final class HighlandDrainage {
    /**
     * Routing/field lattice budget. Sized so continent scale 5000 (≈ 34 km bounding box) still routes at 128 blocks;
     * larger continents coarsen the step.
     */
    public static final int MAX_GRID_NODES = 131_072;
    /** Refined channel samples per continent (meandering channels are longer than their valley axis). */
    public static final int MAX_REFINED_NODES = 262_144;
    static final double COASTAL_RISE = .8;
    /**
     * Stream-power steady state (Flint's law): channel slope S = KS * (A / 1 km^2)^-THETA, capped by a talus slope.
     * Concavity THETA is the global median of real landscapes (RESEARCH-RH3 §3).
     */
    static final double KS = .04, THETA = .45, SLOPE_MIN = .0005, SLOPE_MAX = .6;
    /** Extra channel steepness on a range's crest line (RH6 R; scales with the range core). */
    static final double RANGE_STEEPNESS = 2;
    /** Depressions left after incision become lakes only if at least this deep and no larger than MAX_LAKE_NODES. */
    static final double LAKE_MIN_DEPTH = 4;
    static final int MAX_LAKE_NODES = 600;
    /** Single-node pits read as square ponds at plan resolution; such depressions are breached instead. */
    static final int MIN_DEPRESSION_LAKE_NODES = 3;
    /** Tarns (RH5 H): off-network lakes need at least this depth (blocks) and this spacing (blocks) from each other. */
    static final double TARN_MIN_DEPTH = 8, TARN_SPACING = 2000;
    /** Routing wander amplitude (blocks) and wavelength (blocks). */
    static final double ROUTE_WANDER = 6, ROUTE_WAVELENGTH = 700;
    /** Dam lakes never extend further than this from their dam (keeps them from drowning a whole trunk valley). */
    static final double LAKE_MAX_REACH = 2800;
    /**
     * Dam-lake shape (RH6 L1): valley lakes, not sheets on flats. The flood's mean depth must reach
     * max(LAKE_MEAN_DEPTH, LAKE_MEAN_SHARE × dam height); at most LAKE_SHALLOW_SHARE of its nodes may be shallower than
     * LAKE_SHALLOW; the ground beside the dam (across the flow, within LAKE_CONFINE nodes) must reach the lake level on
     * both sides; and the flood stays within LAKE_SIZE_EXTENT × the FTF lake size of the dam.
     */
    static final double LAKE_MEAN_DEPTH = 5, LAKE_MEAN_SHARE = .45, LAKE_SHALLOW = 3, LAKE_SHALLOW_SHARE = .25, LAKE_SIZE_EXTENT = 4, DEFAULT_LAKE_SIZE = 500;
    static final int LAKE_CONFINE = 3;
    /** Shore absorption (RH6 L3): largest rise (blocks) of a shore node's water above the lake it drains into. */
    static final double SHORE_ABSORB = 2;
    /** Shore band (planned depth units below the shoreline) in which inflowing channel samples join their lake (RH6 L3). */
    static final double SHORE_BAND = 6;
    /** Dam-lake sites: channel gradient limit, share of eligible sites used at lake chance 1, minimum spacing. */
    static final double LAKE_SITE_SLOPE = .015, LAKE_SITE_DENSITY = 1, LAKE_SPACING = 1000;
    /** Steepest water-surface gradient between refined samples (rapids); keeps adjacent wet cells within a block. */
    static final double MAX_WATER_SLOPE = .2;
    private final HighlandCoast coast;
    private final HighlandLand surface;
    private final double spacing, coastEdge, inlandEdge, lakeChance, lakeDepth, ks, meander, lakeReach;
    /**
     * River hierarchy (RH5 D2), as fractions of uplandSpacing²: drainage area at which the drawn network starts
     * (creek heads, at River Count 7; River Count scales it inversely), at which a channel has grown to an established
     * river, and the fixed structural area used for stream capture and lake placement (independent of River Count so
     * thinning the drawn network keeps the same merges and lakes).
     */
    static final double CREEK_AREA = .065, RIVER_AREA = .36, STRUCTURAL_AREA = .043;
    /** River Count that maps to CREEK_AREA, and the range of the resulting area scale. */
    static final double REFERENCE_RIVER_COUNT = 7, MIN_DENSITY_SCALE = .4, MAX_DENSITY_SCALE = 3;
    /**
     * Drainage area (fraction of uplandSpacing²) at which the physical valley network starts (D-GC8): the finest network any
     * River Count draws (River Count 30 and every count from 18 on draw exactly this), so the drawn network is always a subset of it.
     */
    static final double VALLEY_AREA = CREEK_AREA * MIN_DENSITY_SCALE;
    /** Bound on the samples of the physical valley network of one plan (twice the drawn refinement budget). */
    static final int VALLEY_SAMPLE_BUDGET = 2 * MAX_REFINED_NODES;
    /** Half width (blocks) of a channel at its head: 2-4 block creeks once the valley texture's width noise applies. */
    static final double CREEK_HALF_WIDTH = 1.4;
    /** Established rivers: half width 16 (A/spacing²)^0.35 within these bounds (the RH4 law, unchanged). */
    static final double RIVER_MIN_HALF_WIDTH = 4, RIVER_MAX_HALF_WIDTH = 12;
    /** Head taper: the first HEAD_TAPER blocks below a channel head narrow to this share of the width. */
    static final double HEAD_WIDTH = .85;
    /** Drawn creek-head area, established-river area and structural (capture/lake) area, in blocks². */
    private final double creekArea, riverArea, structuralArea, valleyArea, creekExponent;
    /** D8 neighbours; diagonals cost sqrt(2) in the flat-resolution distance and may not cross each other. */
    private static final int[] DX = {0, -1, 1, 0, -1, 1, -1, 1};
    private static final int[] DZ = {-1, 0, 0, 1, -1, 1, 1, -1};
    /** Routing neighbourhood: the 8 lattice neighbours plus the 8 knight moves (26.6/63.4 degree directions). */
    static final boolean KNIGHT_MOVES = false;
    private static final int[] RX = KNIGHT_MOVES ? new int[]{0, -1, 1, 0, -1, 1, -1, 1, 1, 2, 2, 1, -1, -2, -2, -1} : DX;
    private static final int[] RZ = KNIGHT_MOVES ? new int[]{-1, 0, 0, 1, -1, 1, 1, -1, 2, 1, -1, -2, -2, -1, 1, 2} : DZ;
    /** Subtle meander: peak lateral offset as a fraction of reach length on flat reaches. */
    static final double MEANDER = .12;
    /** Channel heads taper from a narrow stream to full width over this length (blocks). */
    static final double HEAD_TAPER = 160;
    /**
     * Routing-node jitter per axis as a fraction of a grid step (RH4 R2). Every lattice node is sampled and routed at
     * its jittered position, so flow directions are not limited to the 8 lattice axes. Below 0.25 every lattice quad
     * stays convex: links never cross and node fields rasterise back onto the lattice by barycentric interpolation.
     */
    static final double NODE_JITTER = .24;
    /**
     * Flat routing friction (RH5 D1). On flats and filled depressions every receiver has zero drop, so routing falls
     * back to the shortest path to the outlet; on uniform friction that is a radial starburst of parallel channels
     * that only meet at the outlet. Path cost per block is 1 + FLAT_FRICTION × n² for a smooth noise n of wavelength
     * FLAT_FRICTION_WAVELENGTH: shortest paths through such a medium coalesce along its low-cost corridors, so flat
     * drainage collects into branching trunks roughly a wavelength apart. Sloping ground is unaffected (its receiver
     * is the steepest descent).
     */
    static final double FLAT_FRICTION = 3, FLAT_FRICTION_WAVELENGTH = 1400;
    /**
     * Thalweg refinement (RH6 F2): valley-axis samples search ±min(THALWEG_REACH, THALWEG_SHARE·step) across their link
     * (every THALWEG_STEP blocks) for the low unincised ground and move toward it (softly weighted within
     * THALWEG_SOFTNESS blocks of the lowest); the move is smoothed along the link, fades in over THALWEG_TAPER blocks
     * from the link's end nodes (nodes and junctions stay put) and never exceeds THALWEG_CLEARANCE of the distance (less
     * THALWEG_MARGIN) to any other drawn link. Channels drawn between coarse plan nodes then run in the valley they
     * appear to drain instead of across its sides.
     */
    static final double THALWEG_REACH = 48, THALWEG_SHARE = .3, THALWEG_STEP = 6, THALWEG_TAPER = 96, THALWEG_SOFTNESS = 1.5,
        THALWEG_CLEARANCE = .45, THALWEG_MARGIN = 16;

    public HighlandDrainage(HighlandCoast coast, HighlandLand surface, double spacing, WorldSettings.ControlPoints controls) {
        this(coast, surface, spacing, controls, .5, 10, 100, 10, 1);
    }
    public HighlandDrainage(HighlandCoast coast, HighlandLand surface, double spacing, WorldSettings.ControlPoints controls,
                            double lakeChance, double lakeDepth, double valleyDepthPercent, int riverCount) {
        this(coast, surface, spacing, controls, lakeChance, lakeDepth, valleyDepthPercent, riverCount, 1);
    }
    /** @param lakeChance FTF river settings' lake chance: probability that an eligible depression is kept as a lake. */
    /** @param meander meander strength (1 = default, 0 = channels follow the valley axis, 2 = maximum). */
    public HighlandDrainage(HighlandCoast coast, HighlandLand surface, double spacing, WorldSettings.ControlPoints controls,
                            double lakeChance, double lakeDepth, double valleyDepthPercent, int riverCount, double meander) {
        this(coast, surface, spacing, controls, lakeChance, lakeDepth, valleyDepthPercent, riverCount, meander, DEFAULT_LAKE_SIZE);
    }
    /** @param lakeSize FTF lake settings' largest size (blocks); dam lakes extend at most LAKE_SIZE_EXTENT times it from the dam. */
    public HighlandDrainage(HighlandCoast coast, HighlandLand surface, double spacing, WorldSettings.ControlPoints controls,
                            double lakeChance, double lakeDepth, double valleyDepthPercent, int riverCount, double meander, double lakeSize) {
        this.lakeReach = Math.min(LAKE_MAX_REACH, LAKE_SIZE_EXTENT * Math.max(64, lakeSize));
        this.meander = clamp(meander, 0, 2);
        this.coast = coast; this.surface = surface; this.spacing = spacing; this.lakeChance = clamp(lakeChance, 0, 1);
        this.lakeDepth = clamp(lakeDepth, 1, 32);
        // Valley depth scales the stream-power steepness; FTF's river count sets drainage density (10 = default).
        this.ks = KS * 100 / clamp(valleyDepthPercent, 25, 200);
        double area2 = spacing * spacing;
        this.riverArea = RIVER_AREA * area2;
        this.structuralArea = STRUCTURAL_AREA * area2;
        double scale = clamp(REFERENCE_RIVER_COUNT / Math.max(1, riverCount), MIN_DENSITY_SCALE, MAX_DENSITY_SCALE);
        this.creekArea = Math.min(CREEK_AREA * area2 * scale, riverArea / 1.5);
        this.valleyArea = Math.min(VALLEY_AREA * area2, riverArea / 1.5);
        // Creeks widen as a power of drainage area from CREEK_HALF_WIDTH at their head to the river law at riverArea.
        this.creekExponent = Math.log(riverHalfWidth(riverArea) / CREEK_HALF_WIDTH) / Math.log(riverArea / creekArea);
        this.coastEdge = controls.coast; this.inlandEdge = controls.coast + Math.max(controls.inland - controls.coast, COASTAL_RISE * Math.max(1, 16000.0 / coast.tectonicScale));
        if (!(inlandEdge > coastEdge)) throw new IllegalArgumentException("Rolling Highlands needs inland > coast");
    }
    /** Ease-out rise from the coastline: land leaves sea level immediately and flattens toward full relief. */
    public double landBlend(double edge) {
        double t = clamp((edge - coastEdge) / (inlandEdge - coastEdge), 0, 1);
        return t * (2 - t);
    }
    public double planningHeight(double x, double z, double edge) {
        return coastalLand(x, z, edge, surface.height(x, z), false);
    }
    /**
     * RH9 S: land height (blocks) on the coastal rise, replacing {@code landBlend(edge) * land}, for the plan and the
     * renderer alike (A-6). Per coast type, mixed by the profile's weights:
     * <ul>
     * <li>beach: the rise starts W blocks inland (shift faded out linearly over COAST_BAND edge units, so the field
     * stays monotone), with a berm and dunes in front;</li>
     * <li>bluff and cliff: the rise is remapped to start at t0, where it already stands H_eff = min(H, land) high
     * (A-8), and still reaches full relief at the inland edge, so no ground behind a scarp lies below it; a bluff keeps
     * a beach to W_b and its scarp rises over BLUFF_SCARP blocks, a cliff stands within CLIFF_FACE blocks.</li>
     * </ul>
     * The plan sees the beach shift but not the scarps ({@code scarps} false): its grid cannot resolve a scarp, and
     * channels planned on the lifted rise would sit perched above the beach beside them. Planned water stays low, and
     * the renderer's valley carving cuts each channel's valley through the scarp down to it (creek gullies, A-5).
     */
    public double coastalLand(double x, double z, double edge, double land, boolean scarps) {
        double range = inlandEdge - coastEdge, u = edge - coastEdge, t = clamp(u / range, 0, 1);
        if (t >= 1 || land <= 0) return land * landBlend(edge);
        var shore = coast.profile().shore(x, z);
        double d = Double.POSITIVE_INFINITY, slope = 0;
        if (u < COAST_BAND) {
            slope = coast.edgeSlope(x, z, edge);
            if (slope > 0) d = u / slope;
        }
        double fade = 1 - Math.min(1, u / COAST_BAND), berm = HighlandCoastProfile.berm(d) * fade;
        double height = 0;
        if (shore.beach() > 0) {
            double shifted = ease(clamp((u - shore.width() * slope * fade) / range, 0, 1)) * land;
            height += shore.beach() * Math.max(shifted, berm + coast.profile().dune(x, z, d, shore) * fade);
        }
        if (shore.bluff() + shore.cliff() > 0) {
            // RH10 fjord walls (11-16): under the effective fjord weight the wall stands FJORD_RELIEF of the local land (so it follows the
            // relief behind it, not a fixed height) and its face runs wall / FJORD_WALL_SLOPE blocks (steep rock, not a slab). 0 elsewhere.
            double fjord = scarps ? coast.drowned().fjord(x, z) : 0;
            double wall = fjord > 0 ? lerp(shore.height(), FJORD_RELIEF * land, fjord) : shore.height();
            double face = fjord > 0 ? lerp(CLIFF_FACE, Math.max(CLIFF_FACE, wall / FJORD_WALL_SLOPE), fjord) : CLIFF_FACE;
            double t0 = scarps ? 1 - Math.sqrt(1 - Math.min(1, wall / land)) : 0;
            double lifted = ease(t0 + (1 - t0) * t) * land;
            double wb = HighlandCoastProfile.bluffWidth(shore.width());
            double s = scarps ? smooth(wb, wb + BLUFF_SCARP, d) : 1;
            height += shore.bluff() * (s * lifted + (1 - s) * berm);
            height += shore.cliff() * (scarps ? smooth(0, face, d) : 1) * lifted;
        }
        return height;
    }
    private static double ease(double t) { return t * (2 - t); }
    /** Edge band (edge units above the coast point) holding the beach shift, berm and scarps; scarp run and cliff face (blocks). */
    static final double COAST_BAND = .08, BLUFF_SCARP = 4, CLIFF_FACE = 2;
    /** RH10 fjord walls (11-16): wall height as a share of the local land height, and the wall height over its face run (blocks per block of run). */
    static final double FJORD_RELIEF = .6, FJORD_WALL_SLOPE = 4;
    /** Refined channel sample. {@code head} ramps 0..1 over the first HEAD_TAPER blocks below a channel head. */
    public record Node(double x, double z, double ground, double water, double area, int parent, double head) {}
    /**
     * Channel polyline {@code downstream, first..last} (refined nodes) and the valley-axis polyline
     * {@code axisFrom..axisTo} (indices into {@link Plan#axis()}) it meanders around.
     */
    public record Reach(int downstream, int first, int last, int axisFrom, int axisTo) {}
    /** Valley-axis sample; {@code belt} is the largest lateral channel offset (meander amplitude) nearby. */
    public record AxisNode(double x, double z, double belt) {}
    /**
     * Abandoned meander loop (oxbow): still water along the middle of the arc, infilled wetland toward its ends and
     * around it. {@code halfWidth} is the open-water half width per point (zero at the infilled ends); {@code water}
     * the still-water level (the lowest level of the channel beside it). The inclusive node range preserves its
     * channel provenance when final lake constraints lower the profile.
     */
    public record Oxbow(double[] x, double[] z, double[] halfWidth, double water, int nodeFrom, int nodeTo) {}
    /**
     * {@code incision} holds, per plan grid node (origin, width, gridStep), how far stream-power incision lowered the
     * land; the rendered surface is the full-resolution land minus its smooth interpolation. {@code water} holds the
     * per-node water level (base level; diagnostic since RH6). {@code climate} holds the smoothed landscape
     * height (incised planning surface, low-passed over about two grid steps) that RH climate reads (RH5 C).
     */
    public record Plan(int ownerX, int ownerZ, int gridStep, int gridNodes, int landNodes, List<Node> nodes, List<Reach> reaches, List<AxisNode> axis, List<Oxbow> oxbows,
                       HighlandBasins basins, long buildNanos, int originX, int originZ, int width, double[] incision, double[] water, double[] climate, Valleys valleys) {
        /**
         * A plan without a physical valley network: only {@link #build} fills {@code valleys}; plans derived from a built plan
         * (resolved lakes) and the river-less plan carry {@link Valleys#EMPTY}, so the owner plan cache never retains the network.
         */
        public Plan(int ownerX, int ownerZ, int gridStep, int gridNodes, int landNodes, List<Node> nodes, List<Reach> reaches, List<AxisNode> axis, List<Oxbow> oxbows,
                    HighlandBasins basins, long buildNanos, int originX, int originZ, int width, double[] incision, double[] water, double[] climate) {
            this(ownerX, ownerZ, gridStep, gridNodes, landNodes, nodes, reaches, axis, oxbows, basins, buildNanos, originX, originZ, width, incision, water, climate, Valleys.EMPTY);
        }
    }
    /**
     * Physical valley network (D-GC8): the valley axes of every link draining at least {@link #VALLEY_AREA}, a function of seed,
     * preset, coast and land only (never River Count). Branches are chains of links along the largest-area physical child; a branch
     * is {@code id} (grid index of its downstream-most land node) and owns the samples {@code first[b] .. first[b+1]-1}, ordered
     * downstream to upstream, the first being the junction point (the root node for a sea root), so consecutive samples form connected
     * 16-block segments. {@code down[b]} is the index of the downstream branch (always earlier in the list) or -1 at a sea root;
     * {@code area} is the drainage area (blocks²) at each sample and {@code area2} the spacing squared.
     */
    record Valleys(double[] x, double[] z, double[] area, int[] first, int[] id, int[] down, double area2) {
        static final Valleys EMPTY = new Valleys(new double[0], new double[0], new double[0], new int[]{0}, new int[0], new int[0], 1);
        int branches() { return id.length; }
        long estimatedBytes() { return x.length * 24L + first.length * 4L + id.length * 8L; }
    }
    private record QueueNode(int index, double level, double distance) {}
    /** Lake id of axis samples in the sea (river mouths). */
    static final int OCEAN = -2;

    /** Growable valley-axis samples (16-block pieces of the Hermite centreline), parents downstream. */
    static final class AxisSamples {
        int size;
        double[] x = new double[1024], z = new double[1024], ground = new double[1024], level = new double[1024],
            area = new double[1024], head = new double[1024];
        int[] parent = new int[1024], lake = new int[1024], grid = new int[1024];
        int add(double px, double pz, double h, double lvl, double a, int p, double hd, int lk, int g) {
            if (size == x.length) {
                int m = size * 2;
                x = Arrays.copyOf(x, m); z = Arrays.copyOf(z, m); ground = Arrays.copyOf(ground, m); level = Arrays.copyOf(level, m);
                area = Arrays.copyOf(area, m); head = Arrays.copyOf(head, m);
                parent = Arrays.copyOf(parent, m); lake = Arrays.copyOf(lake, m); grid = Arrays.copyOf(grid, m);
            }
            x[size] = px; z[size] = pz; ground[size] = h; level[size] = lvl; area[size] = a; head[size] = hd;
            parent[size] = p; lake[size] = lk; grid[size] = g;
            return size++;
        }
    }
    private double riverHalfWidth(double area) {
        return clamp(16 * Math.pow(Math.max(.01, area / (spacing * spacing)), .35), RIVER_MIN_HALF_WIDTH, RIVER_MAX_HALF_WIDTH);
    }
    /**
     * Channel half width (blocks, before the valley texture's width noise) from drainage area: established rivers
     * (area at least riverArea) follow the RH4 hydraulic-geometry law; smaller channels are creeks that widen
     * gradually (a power of area) from CREEK_HALF_WIDTH at the drawn network's head. {@code head} (0..1) tapers
     * the first HEAD_TAPER blocks below a channel head.
     */
    public double halfWidth(double area, double head) {
        double base = area >= riverArea ? riverHalfWidth(area)
            : CREEK_HALF_WIDTH * Math.pow(Math.max(1, area / creekArea), creekExponent);
        return base * lerp(HEAD_WIDTH, 1, clamp(head, 0, 1));
    }
    /** 1 for a creek at its head, falling smoothly to 0 for an established river. */
    public double creekness(double area) {
        return 1 - smooth(Math.log(creekArea), Math.log(riverArea), Math.log(Math.max(1, area)));
    }
    double creekArea() { return creekArea; }
    double riverArea() { return riverArea; }
    HighlandCoast coast() { return coast; }
    double coastEdge() { return coastEdge; }
    double spacing() { return spacing; }
    long seed() { return surface.seed(); }
    double meander() { return meander; }

    /**
     * FTF-style lakes on flat, large rivers (legacy lakes widen rivers on flat steps): at deterministic dam sites on
     * low-gradient main channels, water rises behind a natural barrier (moraine, landslide or alluvial fan) by the
     * FTF lake depth, flooding the upstream valley. A site is used only when the flooded region stays inside the
     * dam's own drainage basin (no spill elsewhere) and within the lake size limit. Mutates {@code incised} (dam crest)
     * and {@code water}.
     */
    private void damLakes(int ownerX, int ownerZ, int width, int depth, int step, int n, int count, int[] order, int[] parent,
                          boolean[] land, double[] area, double[] run, double[] ground, double[] incised, double[] water, double threshold,
                          boolean[] flooded) {
        if (lakeChance <= 0) return;
        int[] childCount = new int[n];
        for (int i = 0; i < n; i++) if (parent[i] >= 0) childCount[parent[i]]++;
        int[] offset = new int[n + 1];
        for (int i = 0; i < n; i++) offset[i + 1] = offset[i] + childCount[i];
        int[] children = new int[offset[n]], fill = offset.clone();
        for (int i = 0; i < n; i++) if (parent[i] >= 0) children[fill[parent[i]]++] = i;
        // Candidates: main channels on gentle reaches, largest first (deterministic order).
        List<Integer> candidates = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int i = order[k], p = parent[i];
            if (!land[i] || p < 0 || !land[p] || area[i] < 4 * threshold) continue;
            if ((water[i] - water[p]) / Math.max(1, run[i]) > LAKE_SITE_SLOPE) continue;
            candidates.add(i);
        }
        candidates.sort(Comparator.<Integer>comparingDouble(i -> -area[i]).thenComparingInt(i -> i));
        int[] mark = new int[n];
        int stamp = 0;
        List<int[]> sites = new ArrayList<>();
        int[] queue = new int[n];
        for (int d : candidates) {
            double hash = (mix(surface.seed() * 131 + d) >>> 11) * 0x1.0p-53;
            if (hash >= lakeChance * LAKE_SITE_DENSITY) continue;
            boolean spaced = true;
            for (int[] site : sites) if (Math.hypot(site[0] - d % width, site[1] - d / width) * step < LAKE_SPACING) { spaced = false; break; }
            if (!spaced) continue;
            double fullHeight = Math.max(4, lakeDepth * (.6 + .8 * ((mix(surface.seed() * 137 + d) >>> 11) * 0x1.0p-53)));
            // Mark the dam's drainage basin (its subtree).
            stamp++;
            int head = 0, tail = 0;
            queue[tail++] = d;
            while (head < tail) { int v = queue[head++]; mark[v] = stamp; for (int c = offset[v]; c < offset[v + 1]; c++) queue[tail++] = children[c]; }
            int basinStamp = stamp;
            double level = 0;
            boolean placed = false;
            // Try the full dam height, then lower ones, until the flood stays inside the basin, the size limit and
            // the reach from the dam.
            for (double fraction : new double[]{1, .7, .5}) {
                level = water[d] + Math.max(3, fullHeight * fraction);
                stamp++;
                head = 0; tail = 0;
                for (int c = offset[d]; c < offset[d + 1]; c++) if (incised[children[c]] < level) { queue[tail++] = children[c]; mark[children[c]] = stamp; }
                boolean leaks = false;
                while (head < tail && !leaks) {
                    int v = queue[head++], vx = v % width, vz = v / width;
                    if (Math.hypot(vx - d % width, vz - d / width) * step > lakeReach) { leaks = true; break; }
                    for (int dir = 0; dir < DX.length; dir++) {
                        int nx = vx + DX[dir], nz = vz + DZ[dir];
                        if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                        int j = nz * width + nx;
                        if (j == d || mark[j] == stamp || incised[j] >= level) continue;
                        if (!land[j] || mark[j] != basinStamp) { leaks = true; break; }
                        mark[j] = stamp; queue[tail++] = j;
                        if (tail > MAX_LAKE_NODES) { leaks = true; break; }
                    }
                }
                // RH6 L1: a valley lake, not a sheet on a flat.
                if (!leaks) leaks = tail < 2 || !lakeShape(queue, tail, level, level - water[d], incised)
                    || !confined(d, parent[d], level, incised, land, width, depth);
                // Restore basin marks of nodes not flooded in this attempt for the next attempt.
                if (leaks) { for (int q = 0; q < tail; q++) mark[queue[q]] = basinStamp; continue; }
                placed = true;
                break;
            }
            if (!placed) continue;
            for (int q = 0; q < tail; q++) { water[queue[q]] = level; flooded[queue[q]] = true; }
            incised[d] = Math.max(incised[d], level + 1);
            water[d] = Math.max(water[d], incised[d]);
            sites.add(new int[]{d % width, d / width});
        }
        if (!sites.isEmpty()) {
            for (int k = 0; k < count; k++) {
                int i = order[k], p = parent[i];
                if (land[i] && p >= 0) water[i] = Math.max(water[i], Math.max(incised[i], water[p]));
            }
        }
    }

    /** Re-orders the drainage tree outlet-first (breadth-first from the roots) and recomputes drainage areas. */
    static int reorder(int n, int count, int[] order, int[] parent, double[] area, double[] cellArea) {
        int[] childCount = new int[n];
        for (int i = 0; i < n; i++) if (parent[i] >= 0) childCount[parent[i]]++;
        int[] offset = new int[n + 1];
        for (int i = 0; i < n; i++) offset[i + 1] = offset[i] + childCount[i];
        int[] children = new int[offset[n]], fill = offset.clone();
        for (int i = 0; i < n; i++) if (parent[i] >= 0) children[fill[parent[i]]++] = i;
        int head = 0, tail = 0;
        for (int k = 0; k < count; k++) if (parent[order[k]] < 0) order[tail++] = order[k];
        while (head < tail) {
            int v = order[head++];
            for (int c = offset[v]; c < offset[v + 1]; c++) order[tail++] = children[c];
        }
        System.arraycopy(cellArea, 0, area, 0, n);
        for (int k = tail - 1; k >= 0; k--) {
            int i = order[k];
            if (parent[i] >= 0) area[parent[i]] += area[i];
        }
        return tail;
    }

    /**
     * RH6 L3: shore absorption. A node beside a lake (an 8-neighbour is flooded) whose water stands at most
     * SHORE_ABSORB above the lake's level but whose receiver is dry runs along the shore; it drains into the lake
     * instead (the deepest flooded neighbour), so channels enter lakes head-on instead of running beside them.
     * Links never cross and never close a cycle. Returns whether any receiver changed.
     */
    static boolean absorbShores(int n, int width, int depth, int[] parent, boolean[] land, boolean[] lakeNode, double[] incised, double[] water,
                                double[] run, double[] nodeX, double[] nodeZ) {
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            if (!land[i] || lakeNode[i] || p < 0 || lakeNode[p]) continue;
            int ix = i % width, iz = i / width, best = -1;
            for (int d = 0; d < DX.length; d++) {
                int x = ix + DX[d], z = iz + DZ[d];
                if (x < 0 || z < 0 || x >= width || z >= depth) continue;
                int j = z * width + x;
                if (!lakeNode[j] || water[i] < water[j] - 1e-6 || water[i] > water[j] + SHORE_ABSORB) continue;
                if (best < 0 || incised[j] < incised[best] || incised[j] == incised[best] && j < best) best = j;
            }
            if (best < 0 || crosses(i, best, parent, nodeX, nodeZ, width, depth)) continue;
            boolean cycle = false;
            for (int v = best, hops = 0; v >= 0 && hops < n; v = parent[v], hops++) if (v == i) { cycle = true; break; }
            if (cycle) continue;
            parent[i] = best;
            run[i] = Math.hypot(nodeX[i] - nodeX[best], nodeZ[i] - nodeZ[best]);
            changed = true;
        }
        return changed;
    }

    /**
     * RH6 L3: a channel sample in a lake's shore band (planned depth above -SHORE_BAND) whose water stands between the
     * lake surface and the spill level belongs to the lake: the channel ends where it reaches the shore band instead of
     * running along the shore just above the lake.
     */
    static boolean absorbed(HighlandBasins.Sample lake, double level) {
        return lake.depth() > -SHORE_BAND && level >= lake.water() - 1e-6 && level <= lake.water() + HighlandBasins.FREEBOARD + 1e-6;
    }

    /** RH6 L1: mean flooded depth and shallow share of a dam-lake flood. */
    static boolean lakeShape(int[] nodes, int count, double level, double damHeight, double[] incised) {
        double sum = 0;
        int shallow = 0;
        for (int q = 0; q < count; q++) {
            double depth = level - incised[nodes[q]];
            sum += depth;
            if (depth < LAKE_SHALLOW) shallow++;
        }
        return sum / count >= Math.max(LAKE_MEAN_DEPTH, LAKE_MEAN_SHARE * damHeight) && shallow <= LAKE_SHALLOW_SHARE * count;
    }

    /**
     * RH6 L1: the valley at the dam is confined: on both sides across the flow (dam to its receiver), the ground within
     * LAKE_CONFINE nodes reaches the lake level.
     */
    static boolean confined(int dam, int receiver, double level, double[] incised, boolean[] land, int width, int depth) {
        if (receiver < 0) return false;
        int dx = Integer.signum(receiver % width - dam % width), dz = Integer.signum(receiver / width - dam / width);
        int px = -dz, pz = dx;
        for (int side = -1; side <= 1; side += 2) {
            boolean wall = false;
            for (int k = 1; k <= LAKE_CONFINE && !wall; k++) {
                int x = dam % width + side * k * px, z = dam / width + side * k * pz;
                if (x < 0 || z < 0 || x >= width || z >= depth) break;
                int j = z * width + x;
                wall = land[j] && incised[j] >= level;
            }
            if (!wall) return false;
        }
        return true;
    }

    /**
     * Hollows (RH5 H): filled depressions away from the structural network. Before RH5 these were neither lakes (a lake
     * needed a channel) nor drained, so the base-level floor turned them into dead-flat dry basins that Streams
     * Reflowing filled with straight-edged lakes. Now a hollow kept by the lake decision (chosen by the lake chance), at
     * least TARN_MIN_DEPTH deep and TARN_SPACING from a deeper tarn, becomes a tarn: an RH lake without a drawn outlet
     * (it drains through the ground). Every other such
     * hollow loses its fill level, so the floor follows the incised surface: a gently dished hollow instead of a flat.
     * Mutates {@code water}; returns the lake eligibility mask for the basins (structural network plus tarns).
     */
    private static boolean[] hollows(int width, int depth, int n, int step, boolean[] land, boolean[] lakeChannel, boolean[] keep,
                                     double[] incised, double[] water) {
        boolean[] mask = lakeChannel.clone(), visited = new boolean[n];
        int[] queue = new int[n];
        List<int[]> tarns = new ArrayList<>();
        List<Double> tarnDepth = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (visited[start] || !land[start] || water[start] - incised[start] <= .5) continue;
            int head = 0, tail = 1;
            queue[0] = start; visited[start] = true;
            boolean connected = false, kept = true;
            double deepest = 0;
            while (head < tail) {
                int v = queue[head++], vx = v % width, vz = v / width;
                connected |= lakeChannel[v];
                kept &= keep[v];
                deepest = Math.max(deepest, water[v] - incised[v]);
                for (int d = 0; d < DX.length; d++) {
                    int nx = vx + DX[d], nz = vz + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                    int j = nz * width + nx;
                    if (!visited[j] && land[j] && water[j] == water[start] && water[j] - incised[j] > .5) { visited[j] = true; queue[tail++] = j; }
                }
            }
            if (connected) continue;
            if (kept && deepest >= TARN_MIN_DEPTH && lakeShape(queue, tail, water[start], 0, incised)) {
                tarns.add(Arrays.copyOf(queue, tail));
                tarnDepth.add(deepest);
            } else {
                for (int q = 0; q < tail; q++) water[queue[q]] = incised[queue[q]];
            }
        }
        // Tarns are scattered, not clustered: deepest first, at least TARN_SPACING apart; the others are dished too.
        Integer[] byDepth = new Integer[tarns.size()];
        for (int i = 0; i < byDepth.length; i++) byDepth[i] = i;
        Arrays.sort(byDepth, Comparator.<Integer>comparingDouble(i -> -tarnDepth.get(i)).thenComparingInt(i -> tarns.get(i)[0]));
        List<double[]> placed = new ArrayList<>();
        for (int t : byDepth) {
            int[] nodes = tarns.get(t);
            double cx = 0, cz = 0;
            for (int v : nodes) { cx += v % width; cz += v / width; }
            cx /= nodes.length; cz /= nodes.length;
            boolean spaced = true;
            for (double[] p : placed) if (Math.hypot(p[0] - cx, p[1] - cz) * step < TARN_SPACING) { spaced = false; break; }
            if (spaced) {
                placed.add(new double[]{cx, cz});
                for (int v : nodes) mask[v] = true;
            } else {
                for (int v : nodes) water[v] = incised[v];
            }
        }
        return mask;
    }

    /**
     * RH6 F2: unincised ground (planning height minus the plan's incision) across a valley-axis sample: the softly
     * weighted offset of the low ground along the normal. Off-land samples are ignored.
     */
    private double thalwegOffset(double x, double z, double normalX, double normalZ, double reach, double[] incision, int width,
                                 int originX, int originZ, int step) {
        int count = 2 * (int) Math.floor(reach / THALWEG_STEP) + 1, half = count / 2;
        double[] values = new double[count];
        double low = Double.POSITIVE_INFINITY;
        for (int m = 0; m < count; m++) {
            double o = (m - half) * THALWEG_STEP, px = x + normalX * o, pz = z + normalZ * o;
            var s = coast.sample(px, pz);
            values[m] = s.edge() <= coastEdge ? Double.NaN
                : planningHeight(px, pz, s.edge()) - HighlandPlanIndex.incision(incision, width, originX, originZ, step, px, pz);
            if (values[m] < low) low = values[m];
        }
        double weights = 0, offset = 0;
        for (int m = 0; m < count; m++) {
            if (Double.isNaN(values[m])) continue;
            double w = Math.exp(-(values[m] - low) / THALWEG_SOFTNESS);
            weights += w; offset += w * (m - half) * THALWEG_STEP;
        }
        return weights > 0 ? offset / weights : 0;
    }

    /** RH6 F2: distance from a point to the nearest drawn link (node to receiver chord) other than link {@code self}. */
    private static double otherLinkDistance(double x, double z, int self, double[] nodeX, double[] nodeZ, int[] parent, boolean[] channel,
                                            int width, int depth, int originX, int originZ, int step) {
        int cx = (int) Math.round((x - originX) / step), cz = (int) Math.round((z - originZ) / step);
        double best = Double.POSITIVE_INFINITY;
        for (int gz = Math.max(0, cz - 2); gz <= Math.min(depth - 1, cz + 2); gz++) for (int gx = Math.max(0, cx - 2); gx <= Math.min(width - 1, cx + 2); gx++) {
            int k = gz * width + gx, q = parent[k];
            if (k == self || q < 0 || !channel[k]) continue;
            double ax = nodeX[q], az = nodeZ[q], dx = nodeX[k] - ax, dz = nodeZ[k] - az, l2 = dx * dx + dz * dz;
            double t = l2 > 1e-12 ? clamp(((x - ax) * dx + (z - az) * dz) / l2, 0, 1) : 0;
            best = Math.min(best, Math.hypot(x - ax - dx * t, z - az - dz * t));
        }
        return best;
    }

    /**
     * The physical valley network of the plan from the per-link samples: branches in flood order (a branch after its downstream branch),
     * each a chain of links along the physical main child, each starting with its junction sample (see {@link Valleys}).
     */
    private static Valleys valleys(int n, int count, int[] order, boolean[] valley, int[] parent, int[] valleyChild, double[] area, double area2,
                                   double[] pathX, double[] pathZ, int[] linkStart, int[] linkPieces, double[] linkX, double[] linkZ) {
        int branches = 0, samples = 0;
        for (int i = 0; i < n; i++) {
            if (!valley[i] || parent[i] < 0) continue;
            samples += linkPieces[i];
            if (parent[parent[i]] < 0 || valleyChild[parent[i]] != i) { branches++; samples++; }
        }
        double[] x = new double[samples], z = new double[samples], a = new double[samples];
        int[] first = new int[branches + 1], id = new int[branches], down = new int[branches], branchOf = new int[n];
        int b = 0, s = 0;
        for (int k = 0; k < count; k++) {
            int i = order[k], p = parent[i];
            if (!valley[i] || p < 0 || parent[p] >= 0 && valleyChild[p] == i) continue; // a main child continues its downstream link's branch
            id[b] = i; first[b] = s; down[b] = parent[p] < 0 ? -1 : branchOf[p];
            if (parent[p] < 0) { x[s] = pathX[p]; z[s] = pathZ[p]; } // the junction: the sea root node, or the end of the downstream link
            else { int last = linkStart[p] + linkPieces[p] - 1; x[s] = linkX[last]; z[s] = linkZ[last]; }
            a[s++] = area[p];
            for (int v = i; v >= 0; v = valleyChild[v]) {
                branchOf[v] = b;
                for (int piece = 1; piece <= linkPieces[v]; piece++, s++) {
                    x[s] = linkX[linkStart[v] + piece - 1]; z[s] = linkZ[linkStart[v] + piece - 1];
                    a[s] = lerp(area[parent[v]], area[v], piece / (double) linkPieces[v]);
                }
            }
            b++;
        }
        first[b] = s;
        return new Valleys(x, z, a, first, id, down, area2);
    }

    /** Deterministic river-less plan used when an owner's drainage cannot be built. */
    public Plan emptyPlan(int ownerX, int ownerZ) {
        var basins = new HighlandBasins(surface.seed(), 0, 0, 1, 1, 64, new double[1], new double[1], new boolean[1], new boolean[1], new double[1], new double[1]);
        return new Plan(ownerX, ownerZ, 64, 0, 0, List.of(), List.of(), List.of(), List.of(), basins, 0, 0, 0, 1, new double[1], new double[1], new double[1]);
    }

    /**
     * Resamples node values (at jittered positions) onto the regular lattice: each lattice point lies within
     * NODE_JITTER of its own node, hence inside one of the four convex quads around that node; barycentric
     * interpolation over the containing triangle (nearest node as a guard) gives a continuous, overshoot-free field.
     */
    /**
     * Whether the link i→j would properly cross an existing receiver link. Links are at most a knight move long, so
     * every link that can cross one has an endpoint within two lattice steps of i or j.
     */
    static boolean crosses(int i, int j, int[] parent, double[] nodeX, double[] nodeZ, int width, int depth) {
        int ix = i % width, iz = i / width, jx = j % width, jz = j / width;
        int x0 = Math.max(0, Math.min(ix, jx) - 2), x1 = Math.min(width - 1, Math.max(ix, jx) + 2);
        int z0 = Math.max(0, Math.min(iz, jz) - 2), z1 = Math.min(depth - 1, Math.max(iz, jz) + 2);
        double ax = nodeX[i], az = nodeZ[i], bx = nodeX[j], bz = nodeZ[j];
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int k = z * width + x, p = parent[k];
            if (p < 0 || k == i || k == j || p == i || p == j) continue;
            double cx = nodeX[k], cz = nodeZ[k], dx = nodeX[p], dz = nodeZ[p];
            double d1 = (dx - cx) * (az - cz) - (dz - cz) * (ax - cx), d2 = (dx - cx) * (bz - cz) - (dz - cz) * (bx - cx);
            double d3 = (bx - ax) * (cz - az) - (bz - az) * (cx - ax), d4 = (bx - ax) * (dz - az) - (bz - az) * (dx - ax);
            if (d1 * d2 < 0 && d3 * d4 < 0) return true;
        }
        return false;
    }

    /** One 3x3 binomial (1-2-1) smoothing pass over a lattice field; edges use the available neighbours. */
    static double[] smooth3(double[] values, int width, int depth) {
        double[] out = new double[values.length];
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            double sum = 0, weights = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int x = ix + dx, z = iz + dz;
                if (x < 0 || z < 0 || x >= width || z >= depth) continue;
                double w = (2 - Math.abs(dx)) * (2 - Math.abs(dz));
                sum += w * values[z * width + x]; weights += w;
            }
            out[iz * width + ix] = sum / weights;
        }
        return out;
    }

    static double[] rasterise(double[] values, double[] nodeX, double[] nodeZ, int width, int depth, int originX, int originZ, int step) {
        double[] out = values.clone();
        double[] weights = new double[3];
        for (int iz = 1; iz < depth - 1; iz++) for (int ix = 1; ix < width - 1; ix++) {
            int[] t = containing(ix, iz, nodeX, nodeZ, width, originX, originZ, step, weights);
            if (t != null) out[iz * width + ix] = weights[0] * values[t[0]] + weights[1] * values[t[1]] + weights[2] * values[t[2]];
        }
        return out;
    }

    /**
     * The jittered-node triangle containing lattice point (ix, iz) and its barycentric weights, or null if none (the
     * caller keeps the point's own node value). The point lies within NODE_JITTER of its own node, hence inside one
     * of the four convex quads around it.
     */
    static int[] containing(int ix, int iz, double[] nodeX, double[] nodeZ, int width, int originX, int originZ, int step, double[] weights) {
        double px = originX + ix * step, pz = originZ + iz * step;
        for (int qz = iz - 1; qz <= iz; qz++) for (int qx = ix - 1; qx <= ix; qx++) {
            int a = qz * width + qx, b = a + 1, c = a + width, d = c + 1;
            for (int[] t : new int[][]{{a, b, d}, {a, d, c}}) {
                double x0 = nodeX[t[0]], z0 = nodeZ[t[0]], x1 = nodeX[t[1]], z1 = nodeZ[t[1]], x2 = nodeX[t[2]], z2 = nodeZ[t[2]];
                double det = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2);
                if (Math.abs(det) < 1e-9) continue;
                double l0 = ((z1 - z2) * (px - x2) + (x2 - x1) * (pz - z2)) / det;
                double l1 = ((z2 - z0) * (px - x2) + (x0 - x2) * (pz - z2)) / det;
                double l2 = 1 - l0 - l1;
                if (l0 < -1e-9 || l1 < -1e-9 || l2 < -1e-9) continue;
                weights[0] = l0; weights[1] = l1; weights[2] = l2;
                return t;
            }
        }
        return null;
    }

    /**
     * RH10 conservative plan mask (narrow arms): whether any of four sub-samples half a plan step either side of a node in x
     * and z is sea. Arms narrower than the lattice can slip between nodes; a land node beside one counts as ocean (more flood
     * sources, never fewer), so no planned link crosses arm water. Only nodes inside drowned regions pay for it.
     */
    private boolean touchesSea(double x, double z, int step) {
        double half = step / 2.0;
        return coast.sample(x - half, z).edge() <= coastEdge || coast.sample(x + half, z).edge() <= coastEdge
            || coast.sample(x, z - half).edge() <= coastEdge || coast.sample(x, z + half).edge() <= coastEdge;
    }

    public Plan build(int ownerX, int ownerZ) {
        long started = System.nanoTime();
        var bounds = coast.bounds(ownerX, ownerZ);
        double minX = bounds.minX(), minZ = bounds.minZ(), maxX = bounds.maxX(), maxZ = bounds.maxZ();
        int step = 64, width, depth, originX, originZ;
        while (true) {
            originX = (int) Math.floor(minX / step) * step;
            originZ = (int) Math.floor(minZ / step) * step;
            width = (int) Math.ceil((maxX - originX) / step) + 1;
            depth = (int) Math.ceil((maxZ - originZ) / step) + 1;
            if ((long) width * depth <= MAX_GRID_NODES) break;
            step *= 2;
        }
        if (step > spacing / 8) throw new IllegalArgumentException("Rolling Highlands continent scale needs upland spacing >= " + step * 8);
        int n = width * depth, landCount = 0, count = 0;
        double[] ground = new double[n], filled = new double[n], area = new double[n];
        int[] parent = new int[n], order = new int[n];
        boolean[] owned = new boolean[n], land = new boolean[n], seen = new boolean[n];
        Arrays.fill(parent, -1);
        double[] routed = new double[n];
        double[] nodeX = new double[n], nodeZ = new double[n];
        for (int i = 0; i < n; i++) {
            long hash = mix(surface.seed() * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL + 7919);
            nodeX[i] = originX + i % width * step + NODE_JITTER * step * (((hash & 0xFFFF) / 65535.0) * 2 - 1);
            nodeZ[i] = originZ + i / width * step + NODE_JITTER * step * ((((hash >>> 16) & 0xFFFF) / 65535.0) * 2 - 1);
        }
        double[] friction = new double[n];
        for (int i = 0; i < n; i++) {
            double f = .65 * noise(surface.seed() + 4409, nodeX[i], nodeZ[i], FLAT_FRICTION_WAVELENGTH)
                + .35 * noise(surface.seed() + 4410, nodeX[i], nodeZ[i], FLAT_FRICTION_WAVELENGTH * .4);
            friction[i] = 1 + FLAT_FRICTION * f * f;
        }
        boolean[] done = new boolean[n], stalled = new boolean[n];
        // Equal levels (flats and filled depressions) resolve by geometric distance from the outlet, not by
        // array order, so flat drainage spreads radially instead of in parallel grid lines.
        PriorityQueue<QueueNode> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueNode::level)
            .thenComparingDouble(QueueNode::distance).thenComparingInt(QueueNode::index));
        for (int iz = 0; iz < depth; iz++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("RH domain interrupted");
            for (int ix = 0; ix < width; ix++) {
                int i = iz * width + ix;
                double x = nodeX[i], z = nodeZ[i];
                // Ownership is the undrowned owner (drowning keeps it): a node of another owner is skipped without a sample, so a
                // final build never asks a neighbour's arm data (plan0) for nodes it does not use.
                var rh9 = coast.undrowned(x, z);
                owned[i] = rh9.ownerX() == ownerX && rh9.ownerZ() == ownerZ;
                if (!owned[i]) continue;
                var s = coast.sample(x, z);
                land[i] = s.edge() > coastEdge && !(s.drowned() > 0 && touchesSea(x, z, step));
                if (land[i]) {
                    landCount++;
                    ground[i] = planningHeight(x, z, s.edge());
                    area[i] = (double) step * step;
                    if (ix == 0 || iz == 0 || ix == width - 1 || iz == depth - 1) throw new IllegalStateException("RH bound contains land at box edge");
                } else {
                    seen[i] = true;
                    queue.add(new QueueNode(i, 0, 0));
                }
            }
        }
        while (!queue.isEmpty()) {
            var entry = queue.remove();
            int i = entry.index, ix = i % width, iz = i / width;
            if (done[i]) continue;
            // Receiver: the steepest descent (by real jittered distance) among already-resolved neighbours; on flats
            // and filled depressions (zero drop) the one closest to the outlet along the routed path.
            if (land[i]) {
                int best = -1; double bestRouted = Double.POSITIVE_INFINITY;
                boolean[] excluded = new boolean[RX.length];
                while (true) {
                    int pick = -1, pickDir = -1; double pickSlope = Double.NEGATIVE_INFINITY, pickVia = Double.POSITIVE_INFINITY;
                    for (int d = 0; d < RX.length; d++) {
                        if (excluded[d]) continue;
                        int nx = ix + RX[d], nz = iz + RZ[d];
                        if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                        int j = nz * width + nx;
                        if (!done[j]) continue;
                        double dist = Math.hypot(nodeX[i] - nodeX[j], nodeZ[i] - nodeZ[j]);
                        double slope = Math.max(0, filled[i] - filled[j]) / dist;
                        double via = routed[j] + dist / step * .5 * (friction[i] + friction[j]);
                        if (slope > pickSlope + 1e-12 || Math.abs(slope - pickSlope) <= 1e-12 && via < pickVia) {
                            pick = j; pickDir = d; pickSlope = slope; pickVia = via;
                        }
                    }
                    if (pick < 0) break;
                    if (!crosses(i, pick, parent, nodeX, nodeZ, width, depth)) { best = pick; bestRouted = pickVia; break; }
                    excluded[pickDir] = true;
                }
                if (best < 0) { stalled[i] = true; continue; } // only crossing links so far; re-queued by the next neighbour
                parent[i] = best; routed[i] = bestRouted;
            }
            done[i] = true;
            order[count++] = i;
            for (int d = 0; d < RX.length; d++) {
                int nx = ix + RX[d], nz = iz + RZ[d];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (!owned[j] || done[j]) continue;
                // Routing only: a low-amplitude wander field breaks long straight flow paths on uniform slopes.
                // Incision always uses the true ground.
                double wander = ROUTE_WANDER * (2 * noise(surface.seed() + 9371, nodeX[j], nodeZ[j], ROUTE_WAVELENGTH) - 1);
                double level = Math.max(filled[i], ground[j] + wander);
                if (seen[j] && !stalled[j] && level >= filled[j]) continue;
                if (seen[j]) level = Math.min(level, filled[j]);
                seen[j] = true; stalled[j] = false;
                filled[j] = level;
                queue.add(new QueueNode(j, level, routed[i] + Math.hypot(nodeX[i] - nodeX[j], nodeZ[i] - nodeZ[j]) / step * .5 * (friction[i] + friction[j])));
            }
        }
        for (int i = 0; i < n; i++) {
            if (!land[i]) continue;
            if (!done[i]) throw new IllegalStateException("RH land component has no verified ocean outlet at " + ownerX + "," + ownerZ);
            int ix = i % width, iz = i / width;
            for (int d = 0; d < DX.length; d++) {
                int nx = ix + DX[d], nz = iz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (!owned[j]) {
                    var adjacent = coast.undrowned(nodeX[j], nodeZ[j]); // the RH9 coast the planner always accepted; drowning only removes land
                    if (adjacent.edge() > coastEdge) throw new IllegalStateException("RH land bridge requires shared ports at " + ownerX + "," + ownerZ);
                }
            }
        }
        // Drainage area (flood order is outlet-first, so reverse order visits children before parents).
        double[] cellArea = area.clone();
        for (int k = count - 1; k >= 0; k--) {
            int i = order[k];
            if (parent[i] >= 0) area[parent[i]] += area[i];
        }
        // Stream capture: a channel that runs alongside a larger channel (orthogonal neighbours, not already joined)
        // joins it there instead of flowing in parallel to a later confluence. Only downhill-consistent captures that
        // cannot form a cycle are taken; the drainage tree is then re-ordered and areas recomputed.
        double captureArea = Math.max(3.0 * step * step, structuralArea);
        boolean captured = false;
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (!land[i] || area[i] < captureArea || parent[i] < 0) continue;
            int ix = i % width, iz = i / width, best = -1;
            for (int d = 0; d < 4; d++) {
                int nx = ix + DX[d], nz = iz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (j == parent[i] || parent[j] == i || !land[j] || area[j] < 2 * area[i] || filled[j] > filled[i]) continue;
                if (crosses(i, j, parent, nodeX, nodeZ, width, depth)) continue;
                if (best < 0 || area[j] > area[best]) best = j;
            }
            if (best < 0) continue;
            boolean cycle = false;
            for (int v = best, hops = 0; v >= 0 && hops < n; v = parent[v], hops++) if (v == i) { cycle = true; break; }
            if (cycle) continue;
            parent[i] = best;
            captured = true;
        }
        if (captured) count = reorder(n, count, order, parent, area, cellArea);
        // Stream-power incision of the rendered land: z = min(ground, z_receiver + run * S(A)), outlet-first. Valleys
        // deepen where land stands high above its outlet; divides stay at the land surface.
        double[] run = new double[n], cap = new double[n], incised = new double[n], water = new double[n];
        Arrays.fill(cap, Double.POSITIVE_INFINITY);
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            if (p < 0) continue;
            run[i] = Math.hypot(nodeX[i] - nodeX[p], nodeZ[i] - nodeZ[p]);
        }
        boolean[] keep = new boolean[n];
        // RH6 R: ranges are uplifted faster, so their steady-state channels are steeper (channel steepness grows with
        // uplift rate): incision cuts less deeply into a range than into the uplands around it.
        double[] steepness = new double[n];
        for (int i = 0; i < n; i++) steepness[i] = land[i] ? ks * (1 + RANGE_STEEPNESS * surface.rangeCore(nodeX[i], nodeZ[i])) : ks;
        for (int round = 0; ; round++) {
            for (int k = 0; k < count; k++) {
                int i = order[k], p = parent[i];
                if (!land[i]) { incised[i] = 0; water[i] = 0; continue; }
                double slope = clamp(steepness[i] * Math.pow(area[i] / 1e6, -THETA), SLOPE_MIN, SLOPE_MAX);
                incised[i] = Math.min(Math.min(ground[i], cap[i]), p < 0 ? ground[i] : incised[p] + run[i] * slope);
                water[i] = p < 0 ? incised[i] : Math.max(incised[i], water[p]);
            }
            // Depressions that survive incision: keep as lakes (deep enough, not too large, chosen by the lake chance)
            // or breach them by lowering the outlet path below the depression bottom.
            boolean breached = false;
            boolean[] visited = new boolean[n];
            int[] queueBuffer = new int[n];
            for (int k = 0; k < count; k++) {
                int start = order[k];
                if (visited[start] || !land[start] || keep[start] || water[start] - incised[start] <= .5) continue;
                int head = 0, tail = 1, bottom = start; queueBuffer[0] = start; visited[start] = true;
                while (head < tail) {
                    int v = queueBuffer[head++];
                    if (incised[v] < incised[bottom] || incised[v] == incised[bottom] && v < bottom) bottom = v;
                    int vx = v % width, vz = v / width;
                    for (int d = 0; d < DX.length; d++) {
                        int nx = vx + DX[d], nz = vz + DZ[d];
                        if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                        int j = nz * width + nx;
                        if (!visited[j] && land[j] && water[j] == water[start] && water[j] - incised[j] > .5) { visited[j] = true; queueBuffer[tail++] = j; }
                    }
                }
                double hash = (mix(surface.seed() * 31 + bottom) >>> 11) * 0x1.0p-53;
                boolean lake = round == 0 && water[start] - incised[bottom] >= LAKE_MIN_DEPTH && tail >= MIN_DEPRESSION_LAKE_NODES && tail <= MAX_LAKE_NODES && hash < lakeChance
                    && lakeShape(queueBuffer, tail, water[start], 0, incised); // RH6 L1: deep enough throughout, not a sheet
                if (lake) { for (int q = 0; q < tail; q++) keep[queueBuffer[q]] = true; continue; }
                double limit = incised[bottom];
                for (int v = parent[bottom]; v >= 0 && land[v]; v = parent[v]) {
                    limit -= .01 * run[v];
                    if (incised[v] <= limit) break;
                    cap[v] = Math.min(cap[v], Math.max(0, limit));
                }
                breached = true;
            }
            if (!breached || round == 3) break;
        }
        double structural = Math.max(3.0 * step * step, structuralArea), threshold = Math.max(3.0 * step * step, creekArea),
            valleyThreshold = Math.max(3.0 * step * step, valleyArea);
        boolean[] lakeNode = keep.clone();
        damLakes(ownerX, ownerZ, width, depth, step, n, count, order, parent, land, area, run, ground, incised, water, structural, lakeNode);
        for (int i = 0; i < n; i++) lakeNode[i] &= land[i] && water[i] > incised[i];
        if (absorbShores(n, width, depth, parent, land, lakeNode, incised, water, run, nodeX, nodeZ))
            count = reorder(n, count, order, parent, area, cellArea);
        // Lakes form on the structural network (independent of River Count); the drawn network is every channel
        // above the creek threshold plus the outlet of every lake, so a lake never sits in a dry valley.
        boolean[] lakeChannel = new boolean[n], channel = new boolean[n];
        for (int i = 0; i < n; i++) lakeChannel[i] = land[i] && area[i] >= structural;
        boolean[] lakeMask = hollows(width, depth, n, step, land, lakeChannel, keep, incised, water);
        // The physical network (D-GC8) is every link at or above the finest drawn threshold plus the same lake outlets; the drawn
        // network (this River Count's creek threshold) is chosen from it and can never leave it.
        boolean[] valley = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean lakeOutlet = lakeChannel[i] && water[i] - incised[i] > .5;
            valley[i] = land[i] && (area[i] >= valleyThreshold || lakeOutlet);
            channel[i] = land[i] && (area[i] >= threshold || lakeOutlet);
        }
        for (int k = count - 1; k >= 0; k--) {
            int i = order[k];
            if (lakeChannel[i] && parent[i] >= 0) lakeChannel[parent[i]] = true;
            if (valley[i] && parent[i] >= 0) valley[parent[i]] = true;
            if (channel[i] && parent[i] >= 0) channel[parent[i]] = true;
        }
        for (int i = 0; i < n; i++) if (channel[i] && !valley[i]) throw new IllegalStateException("RH drawn channel outside the physical network");
        double[] incision = new double[n];
        // Positive: incision; negative only at lake dams (a natural barrier raised above the land, like a moraine).
        for (int i = 0; i < n; i++) incision[i] = land[i] ? ground[i] - incised[i] : 0;
        // Per-node water level (base level, for diagnostics; RH6 no longer floors the land to it).
        double[] waterGrid = new double[n];
        for (int i = 0; i < n; i++) waterGrid[i] = land[i] ? water[i] : 0;
        // Climate height (RH5 C): the incised planning surface (valley-scale, no riverbeds), binomial-smoothed twice.
        double[] climate = new double[n];
        for (int i = 0; i < n; i++) climate[i] = land[i] ? Math.max(0, Math.min(ground[i], incised[i])) : 0;
        for (int pass = 0; pass < 2; pass++) climate = smooth3(climate, width, depth);
        // Node values live at jittered positions; the field interpolators work on the regular lattice.
        incision = rasterise(incision, nodeX, nodeZ, width, depth, originX, originZ, step);
        climate = rasterise(climate, nodeX, nodeZ, width, depth, originX, originZ, step);
        waterGrid = rasterise(waterGrid, nodeX, nodeZ, width, depth, originX, originZ, step);
        int[] tributaries = new int[n], mainChild = new int[n];
        Arrays.fill(mainChild, -1);
        for (int i = 0; i < n; i++) {
            if (!channel[i] || parent[i] < 0) continue;
            int p = parent[i];
            tributaries[p]++;
            if (mainChild[p] < 0 || area[i] > area[mainChild[p]] || area[i] == area[mainChild[p]] && i < mainChild[p]) mainChild[p] = i;
        }
        // Main child over the physical network (largest drainage area, ties to the lower index): the tangent source of every valley link.
        // The drawn mainChild above stays the drawn reach structure's input.
        int[] valleyChild = new int[n];
        Arrays.fill(valleyChild, -1);
        for (int i = 0; i < n; i++) {
            if (!valley[i] || parent[i] < 0) continue;
            int p = parent[i];
            if (valleyChild[p] < 0 || area[i] > area[valleyChild[p]] || area[i] == area[valleyChild[p]] && i < valleyChild[p]) valleyChild[p] = i;
        }
        HighlandBasins basins = new HighlandBasins(surface.seed() + 52361, originX, originZ, width, depth, step, incised, water, land, lakeMask, nodeX, nodeZ);
        // RH6 L2: one lake level. Flooded nodes carry the lake surface (spill minus freeboard), not the spill level, so
        // channels in a lake's backwater meet the lake at its own level instead of stepping down one block.
        for (int i = 0; i < n; i++) {
            double lakeSurface = basins.nodeSurface(i);
            if (!Double.isNaN(lakeSurface)) water[i] = Math.min(water[i], lakeSurface);
        }
        // Channels run through the routing nodes' own (jittered) positions.
        double[] pathX = nodeX, pathZ = nodeZ;
        // Valley axis (RH3 centreline, D-GC8): tangent-continuous Hermite per physical link, 16-block samples, kept per link; the
        // drawn network below takes its samples from here.
        int[] linkStart = new int[n], linkPieces = new int[n];
        double[] linkLength = new double[n], linkX = new double[1024], linkZ = new double[1024];
        int linkSamples = 0;
        double[] curveX = new double[17], curveZ = new double[17];
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (!valley[i] || parent[i] < 0) continue;
            double x = pathX[i], z = pathZ[i];
            int p = parent[i];
            double px = pathX[p], pz = pathZ[p];
            // Hermite curve from the downstream node to this node, tangent-continuous along each main stem.
            int beyond = parent[p], upstream = valleyChild[i];
            double qx = beyond >= 0 ? pathX[beyond] : 2 * px - x, qz = beyond >= 0 ? pathZ[beyond] : 2 * pz - z;
            double rx = upstream >= 0 ? pathX[upstream] : 2 * x - px, rz = upstream >= 0 ? pathZ[upstream] : 2 * z - pz;
            double chord = Math.max(1e-6, Math.hypot(x - px, z - pz));
            double before = Math.max(1e-6, Math.hypot(px - qx, pz - qz)), after = Math.max(1e-6, Math.hypot(rx - x, rz - z));
            double m1x = (x - qx) * chord / (before + chord), m1z = (z - qz) * chord / (before + chord);
            double m2x = (rx - px) * chord / (chord + after), m2z = (rz - pz) * chord / (chord + after);
            double coarseSlope = Math.abs(water[i] - water[p]) / chord;
            double meander = MEANDER * chord * (1 - smooth(.02, .1, coarseSlope))
                * (2 * noise(surface.seed() + 6143, (x + px) * .5, (z + pz) * .5, 300) - 1);
            double nx = -(z - pz) / chord, nz = (x - px) / chord;
            double length = 0;
            for (int c = 0; c <= 16; c++) {
                double t = c / 16.0, t2 = t * t, t3 = t2 * t;
                double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
                double bulge = meander * Math.sin(Math.PI * t);
                curveX[c] = h00 * px + h10 * m1x + h01 * x + h11 * m2x + nx * bulge;
                curveZ[c] = h00 * pz + h10 * m1z + h01 * z + h11 * m2z + nz * bulge;
                if (c > 0) length += Math.hypot(curveX[c] - curveX[c - 1], curveZ[c] - curveZ[c - 1]);
            }
            int pieces = Math.max(1, (int) Math.ceil(length / 16.0));
            // RH6 F2: thalweg refinement. Samples move across the link toward the low ground (smoothed, tapered to
            // zero at both end nodes, clear of other links).
            double[] sxs = new double[pieces + 1], szs = new double[pieces + 1], move = new double[pieces + 1];
            double reachAcross = Math.min(THALWEG_REACH, THALWEG_SHARE * step);
            for (int piece = 1; piece <= pieces; piece++) {
                double t = piece / (double) pieces, f = t * 16;
                int c = Math.min(15, (int) f);
                sxs[piece] = lerp(curveX[c], curveX[c + 1], f - c); szs[piece] = lerp(curveZ[c], curveZ[c + 1], f - c);
                move[piece] = thalwegOffset(sxs[piece], szs[piece], nx, nz, reachAcross, incision, width, originX, originZ, step);
            }
            move[pieces] = 0; // the upstream end is node i itself
            for (int pass = 0; pass < 2; pass++) {
                double[] next = move.clone();
                for (int piece = 1; piece < pieces; piece++) next[piece] = .25 * move[piece - 1] + .5 * move[piece] + .25 * move[piece + 1];
                move = next;
            }
            for (int piece = 1; piece <= pieces; piece++) {
                double t = piece / (double) pieces, fromEnds = Math.min(t, 1 - t) * length;
                double shift = move[piece] * smooth(0, THALWEG_TAPER, fromEnds);
                // Clearance: never more than a share of the distance to any other physical link, so channels drawn into
                // the same valley bottom cannot meet or cross.
                double room = THALWEG_CLEARANCE * Math.max(0, otherLinkDistance(sxs[piece], szs[piece], i, pathX, pathZ, parent, valley,
                    width, depth, originX, originZ, step) - THALWEG_MARGIN);
                shift = clamp(shift, -room, room);
                sxs[piece] += nx * shift; szs[piece] += nz * shift;
            }
            if (linkSamples + pieces > VALLEY_SAMPLE_BUDGET) throw new IllegalStateException("RH valley network budget exceeded");
            while (linkSamples + pieces > linkX.length) { linkX = Arrays.copyOf(linkX, linkX.length * 2); linkZ = Arrays.copyOf(linkZ, linkZ.length * 2); }
            linkStart[i] = linkSamples; linkPieces[i] = pieces; linkLength[i] = length;
            for (int piece = 1; piece <= pieces; piece++, linkSamples++) { linkX[linkSamples] = sxs[piece]; linkZ[linkSamples] = szs[piece]; }
        }
        // Drawn valley axis: the physical samples of the drawn links, with the planning surface, water level and flags of each.
        AxisSamples axis = new AxisSamples();
        int[] endpoint = new int[n], linkFirst = new int[n];
        Arrays.fill(endpoint, -1); Arrays.fill(linkFirst, -1);
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (!channel[i]) continue;
            int p = parent[i];
            if (p < 0) { endpoint[i] = axis.add(pathX[i], pathZ[i], 0, 0, area[i], -1, 1, OCEAN, i); continue; }
            int pieces = linkPieces[i];
            double length = linkLength[i];
            boolean headReach = tributaries[i] == 0;
            int previous = endpoint[p];
            linkFirst[i] = axis.size;
            for (int piece = 1; piece <= pieces; piece++) {
                if (axis.size >= MAX_REFINED_NODES) throw new IllegalStateException("RH profile refinement budget exceeded");
                double t = piece / (double) pieces;
                double sx = linkX[linkStart[i] + piece - 1], sz = linkZ[linkStart[i] + piece - 1];
                var location = coast.sample(sx, sz);
                double h = planningHeight(sx, sz, location.edge());
                double level = lerp(water[p], water[i], t);
                var lake = basins.sample(sx, sz);
                boolean flooded = lake != null && (lake.depth() > 0 || absorbed(lake, level));
                if (flooded) level = lake.water();
                boolean ocean = location.edge() <= coastEdge;
                if (ocean) level = 0;
                double head = headReach ? smooth(0, HEAD_TAPER, (1 - t) * length) : 1;
                previous = axis.add(sx, sz, h, level, lerp(area[p], area[i], t), previous, head, ocean ? OCEAN : flooded ? lake.lake() : -1, i);
            }
            endpoint[i] = previous;
        }
        var channels = new HighlandMeanders(this, axis, endpoint, linkFirst, mainChild, parent, channel, n, basins).build();
        List<Node> samples = channels.samples();
        List<Reach> reaches = channels.reaches();
        List<Double> levels = channels.levels();
        List<Integer> lakeOf = channels.lakeOf();
        int refined = samples.size();
        int[] parents = new int[refined];
        double[] profile = new double[refined];
        for (int j = 0; j < refined; j++) { parents[j] = samples.get(j).parent(); profile[j] = levels.get(j); }
        // Water never rises downstream: a lake outlet or a confluence lowers everything below it to its level.
        for (int j = refined - 1; j >= 0; j--) {
            int p = parents[j];
            if (p >= 0 && profile[p] > profile[j]) profile[p] = profile[j];
        }
        // Rapids, not walls: outside lakes, water climbs at most MAX_WATER_SLOPE per block upstream of its parent, so a
        // tributary entering a lowered trunk descends over its last reach instead of stepping at the junction.
        int[] childCount = new int[refined];
        for (int j = 0; j < refined; j++) if (parents[j] >= 0) childCount[parents[j]]++;
        for (int j = 0; j < refined; j++) {
            int p = parents[j];
            if (p < 0 || lakeOf.get(j) >= 0) continue;
            var a = samples.get(j); var b = samples.get(p);
            // Level wet core at confluences: the first sample above a junction stays at the junction's level.
            double rise = childCount[p] >= 2 ? 0 : MAX_WATER_SLOPE * Math.hypot(a.x() - b.x(), a.z() - b.z());
            profile[j] = Math.min(profile[j], profile[p] + rise);
        }
        // Lake outlets: the first channel sample downstream of a flooded sample of that lake.
        for (int j = 0; j < refined; j++) {
            int lake = lakeOf.get(j), p = parents[j];
            if (lake >= 0 && p >= 0 && lakeOf.get(p) != lake) basins.setOutlet(lake, samples.get(p).x(), samples.get(p).z());
        }
        // Oxbows hold the lowest water of the channel stretch beside them (never above the surrounding floor).
        List<Oxbow> oxbows = new ArrayList<>();
        for (var site : channels.oxbows()) {
            double level = Double.POSITIVE_INFINITY;
            for (int j = site.nodeFrom(); j <= site.nodeTo(); j++) level = Math.min(level, profile[j]);
            if (level > 0 && Double.isFinite(level)) oxbows.add(new Oxbow(site.x(), site.z(), site.halfWidth(), level, site.nodeFrom(), site.nodeTo()));
        }
        List<Node> nodes = new ArrayList<>(refined);
        for (int j = 0; j < refined; j++) {
            var point = samples.get(j);
            nodes.add(new Node(point.x(), point.z(), point.ground(), profile[j], point.area(), point.parent(), point.head()));
        }
        return new Plan(ownerX, ownerZ, step, n, landCount, List.copyOf(nodes), List.copyOf(reaches), List.copyOf(channels.axis()), List.copyOf(oxbows), basins, System.nanoTime() - started, originX, originZ, width, incision, waterGrid, climate,
            valleys(n, count, order, valley, parent, valleyChild, area, spacing * spacing, pathX, pathZ, linkStart, linkPieces, linkX, linkZ));
    }
}
