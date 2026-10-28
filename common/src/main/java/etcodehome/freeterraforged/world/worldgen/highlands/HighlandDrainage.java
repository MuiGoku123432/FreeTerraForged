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
    /** Depressions left after incision become lakes only if at least this deep and no larger than MAX_LAKE_NODES. */
    static final double LAKE_MIN_DEPTH = 4;
    static final int MAX_LAKE_NODES = 600;
    /** Single-node pits read as square ponds at plan resolution; such depressions are breached instead. */
    static final int MIN_DEPRESSION_LAKE_NODES = 3;
    /** Routing wander amplitude (blocks) and wavelength (blocks). */
    static final double ROUTE_WANDER = 6, ROUTE_WAVELENGTH = 700;
    /** Dam lakes never extend further than this from their dam (keeps them from drowning a whole trunk valley). */
    static final double LAKE_MAX_REACH = 2800;
    /** Dam-lake sites: channel gradient limit, share of eligible sites used at lake chance 1, minimum spacing. */
    static final double LAKE_SITE_SLOPE = .015, LAKE_SITE_DENSITY = 1, LAKE_SPACING = 1000;
    /** Steepest water-surface gradient between refined samples (rapids); keeps adjacent wet cells within a block. */
    static final double MAX_WATER_SLOPE = .2;
    private final HighlandCoast coast;
    private final HighlandLand surface;
    private final double spacing, coastEdge, inlandEdge, lakeChance, lakeDepth, ks, channelScale, meander;
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
        this.meander = clamp(meander, 0, 2);
        this.coast = coast; this.surface = surface; this.spacing = spacing; this.lakeChance = clamp(lakeChance, 0, 1);
        this.lakeDepth = clamp(lakeDepth, 1, 32);
        // Valley depth scales the stream-power steepness; FTF's river count sets drainage density (10 = default).
        this.ks = KS * 100 / clamp(valleyDepthPercent, 25, 200);
        this.channelScale = clamp(10.0 / Math.max(1, riverCount), .4, 4);
        this.coastEdge = controls.coast; this.inlandEdge = controls.coast + Math.max(controls.inland - controls.coast, COASTAL_RISE * Math.max(1, 16000.0 / coast.tectonicScale));
        if (!(inlandEdge > coastEdge)) throw new IllegalArgumentException("Rolling Highlands needs inland > coast");
    }
    /** Ease-out rise from the coastline: land leaves sea level immediately and flattens toward full relief. */
    public double landBlend(double edge) {
        double t = clamp((edge - coastEdge) / (inlandEdge - coastEdge), 0, 1);
        return t * (2 - t);
    }
    public double planningHeight(double x, double z, double edge) {
        return surface.height(x, z) * landBlend(edge);
    }
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
     * the still-water level (the lowest level of the channel beside it).
     */
    public record Oxbow(double[] x, double[] z, double[] halfWidth, double water) {}
    /**
     * {@code incision} holds, per plan grid node (origin, width, gridStep), how far stream-power incision lowered the
     * land; the rendered surface is the full-resolution land minus its smooth interpolation. {@code water} holds the
     * per-node water level (base level) that dry ground must stay above.
     */
    public record Plan(int ownerX, int ownerZ, int gridStep, int gridNodes, int landNodes, List<Node> nodes, List<Reach> reaches, List<AxisNode> axis, List<Oxbow> oxbows,
                       HighlandBasins basins, long buildNanos, int originX, int originZ, int width, double[] incision, double[] water) {}
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
                          boolean[] land, double[] area, double[] run, double[] ground, double[] incised, double[] water, double threshold) {
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
                    if (Math.hypot(vx - d % width, vz - d / width) * step > LAKE_MAX_REACH) { leaks = true; break; }
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
                // Restore basin marks of nodes not flooded in this attempt for the next attempt.
                if (leaks) { for (int q = 0; q < tail; q++) mark[queue[q]] = basinStamp; continue; }
                placed = tail >= 2;
                break;
            }
            if (!placed) continue;
            for (int q = 0; q < tail; q++) water[queue[q]] = level;
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

    /** Deterministic river-less plan used when an owner's drainage cannot be built. */
    public Plan emptyPlan(int ownerX, int ownerZ) {
        var basins = new HighlandBasins(surface.seed(), 0, 0, 1, 1, 64, new double[1], new double[1], new boolean[1], new boolean[1], new double[1], new double[1]);
        return new Plan(ownerX, ownerZ, 64, 0, 0, List.of(), List.of(), List.of(), List.of(), basins, 0, 0, 0, 1, new double[1], new double[1]);
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
                var s = coast.sample(x, z);
                owned[i] = s.ownerX() == ownerX && s.ownerZ() == ownerZ;
                if (!owned[i]) continue;
                land[i] = s.edge() > coastEdge;
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
                        double via = routed[j] + dist / step;
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
                queue.add(new QueueNode(j, level, routed[i] + Math.hypot(nodeX[i] - nodeX[j], nodeZ[i] - nodeZ[j]) / step));
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
                    var adjacent = coast.sample(nodeX[j], nodeZ[j]);
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
        double captureArea = Math.max(3.0 * step * step, .03 * spacing * spacing * channelScale);
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
        if (captured) {
            // Re-order outlet-first (breadth-first from the roots) and recompute drainage areas.
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
            count = tail;
            System.arraycopy(cellArea, 0, area, 0, n);
            for (int k = count - 1; k >= 0; k--) {
                int i = order[k];
                if (parent[i] >= 0) area[parent[i]] += area[i];
            }
        }
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
        for (int round = 0; ; round++) {
            for (int k = 0; k < count; k++) {
                int i = order[k], p = parent[i];
                if (!land[i]) { incised[i] = 0; water[i] = 0; continue; }
                double slope = clamp(ks * Math.pow(area[i] / 1e6, -THETA), SLOPE_MIN, SLOPE_MAX);
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
                boolean lake = round == 0 && water[start] - incised[bottom] >= LAKE_MIN_DEPTH && tail >= MIN_DEPRESSION_LAKE_NODES && tail <= MAX_LAKE_NODES && hash < lakeChance;
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
        double threshold = Math.max(3.0 * step * step, .03 * spacing * spacing * channelScale);
        damLakes(ownerX, ownerZ, width, depth, step, n, count, order, parent, land, area, run, ground, incised, water, threshold);
        boolean[] channel = new boolean[n];
        for (int i = 0; i < n; i++) channel[i] = land[i] && area[i] >= threshold;
        for (int k = count - 1; k >= 0; k--) {
            int i = order[k];
            if (channel[i] && parent[i] >= 0) channel[parent[i]] = true;
        }
        double[] incision = new double[n];
        // Positive: incision; negative only at lake dams (a natural barrier raised above the land, like a moraine).
        for (int i = 0; i < n; i++) incision[i] = land[i] ? ground[i] - incised[i] : 0;
        // Per-node water level (base level): dry ground is never rendered below it (see HighlandPlanIndex.waterFloor).
        double[] waterGrid = new double[n];
        for (int i = 0; i < n; i++) waterGrid[i] = land[i] ? water[i] : 0;
        // Node values live at jittered positions; the field interpolators work on the regular lattice.
        incision = rasterise(incision, nodeX, nodeZ, width, depth, originX, originZ, step);
        waterGrid = rasterise(waterGrid, nodeX, nodeZ, width, depth, originX, originZ, step);
        int[] tributaries = new int[n], mainChild = new int[n];
        Arrays.fill(mainChild, -1);
        for (int i = 0; i < n; i++) {
            if (!channel[i] || parent[i] < 0) continue;
            int p = parent[i];
            tributaries[p]++;
            if (mainChild[p] < 0 || area[i] > area[mainChild[p]] || area[i] == area[mainChild[p]] && i < mainChild[p]) mainChild[p] = i;
        }
        HighlandBasins basins = new HighlandBasins(surface.seed() + 52361, originX, originZ, width, depth, step, incised, water, land, channel, nodeX, nodeZ);
        // Channels run through the routing nodes' own (jittered) positions.
        double[] pathX = nodeX, pathZ = nodeZ;
        // Valley axis (RH3 centreline): tangent-continuous Hermite per grid link, 16-block samples.
        AxisSamples axis = new AxisSamples();
        int[] endpoint = new int[n], linkFirst = new int[n];
        Arrays.fill(endpoint, -1); Arrays.fill(linkFirst, -1);
        double[] curveX = new double[17], curveZ = new double[17];
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (!channel[i]) continue;
            double x = pathX[i], z = pathZ[i];
            int p = parent[i];
            if (p < 0) { endpoint[i] = axis.add(x, z, 0, 0, area[i], -1, 1, OCEAN, i); continue; }
            double px = pathX[p], pz = pathZ[p];
            // Hermite curve from the downstream node to this node, tangent-continuous along each main stem.
            int beyond = parent[p], upstream = mainChild[i];
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
            boolean headReach = tributaries[i] == 0;
            int previous = endpoint[p];
            linkFirst[i] = axis.size;
            for (int piece = 1; piece <= pieces; piece++) {
                if (axis.size >= MAX_REFINED_NODES) throw new IllegalStateException("RH profile refinement budget exceeded");
                double t = piece / (double) pieces, f = t * 16;
                int c = Math.min(15, (int) f);
                double sx = lerp(curveX[c], curveX[c + 1], f - c), sz = lerp(curveZ[c], curveZ[c + 1], f - c);
                var location = coast.sample(sx, sz);
                double h = planningHeight(sx, sz, location.edge());
                double level = lerp(water[p], water[i], t);
                var lake = basins.sample(sx, sz);
                boolean flooded = lake != null && lake.depth() > 0;
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
            if (level > 0 && Double.isFinite(level)) oxbows.add(new Oxbow(site.x(), site.z(), site.halfWidth(), level));
        }
        List<Node> nodes = new ArrayList<>(refined);
        for (int j = 0; j < refined; j++) {
            var point = samples.get(j);
            nodes.add(new Node(point.x(), point.z(), point.ground(), profile[j], point.area(), point.parent(), point.head()));
        }
        return new Plan(ownerX, ownerZ, step, n, landCount, List.copyOf(nodes), List.copyOf(reaches), List.copyOf(channels.axis()), List.copyOf(oxbows), basins, System.nanoTime() - started, originX, originZ, width, incision, waterGrid);
    }
}
