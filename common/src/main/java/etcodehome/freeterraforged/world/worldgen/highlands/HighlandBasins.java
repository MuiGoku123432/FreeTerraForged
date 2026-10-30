package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/**
 * Lakes: priority-flood depressions that a selected channel passes through and that are deep enough to hold
 * water. Shallow fills are breached by the river profile instead of flooded. Each lake has one fixed surface
 * (spill level minus two). Shorelines come from a bilinear depth field over the lake and its shore region (dry
 * nodes rising up to REGION_DEPTH above the surface), perturbed by low-amplitude noise, so they follow the
 * planning surface's contours rather than grid cells.
 */
public final class HighlandBasins {
    /** Lakes are decided by the drainage plan (depth, size, chance); any remaining fill deeper than this floods. */
    static final double MIN_LAKE_DEPTH = 1;
    static final int MIN_LAKE_NODES = 1;
    /** The surface stands this far below the spill so the rim always contains it. */
    static final double FREEBOARD = 1;
    /** Widest shore blend in depth units. */
    static final double MAX_SHORE = 40;
    /** Shore regions extend over dry nodes less than this far above the lake; also the depth assumed beyond. */
    static final double REGION_DEPTH = 96;
    /** Horizontal reach (blocks) of a lake's shore influence from its flooded nodes. */
    static final double SHORE_REACH = 384;
    private static final int[] CARDINAL = {1, 3, 4, 6};
    /** Nodes more than this above their lake's level stay as islands. */
    static final double ISLAND_MIN = 3;
    private static final int[] RX = {-1, 0, 1, -1, 1, -1, 0, 1}, RZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private final int originX, originZ, width, depth, step;
    private final long seed;
    private final double[] levels, depthField, reach, outletX, outletZ, nodeSurface;
    private final int[] basin;

    /** {@code proximity} is 1 near flooded nodes and falls smoothly to 0 at SHORE_REACH from them. */
    public record Sample(int lake, double water, double depth, double proximity) {}

    HighlandBasins(long seed, int originX, int originZ, int width, int depth, int step, double[] ground,
                   double[] filled, boolean[] land, boolean[] channel, double[] nodeX, double[] nodeZ) {
        this.seed = seed;
        this.originX = originX; this.originZ = originZ; this.width = width; this.depth = depth; this.step = step;
        int n = ground.length;
        this.basin = new int[n];
        Arrays.fill(basin, -1);
        List<Double> surfaces = new ArrayList<>();
        int[] queue = new int[n];
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (visited[i] || !land[i] || filled[i] <= ground[i] + .5) continue;
            int head = 0, tail = 1; queue[0] = i; visited[i] = true;
            boolean connectedRiver = false;
            double deepest = 0;
            while (head < tail) {
                int v = queue[head++];
                connectedRiver |= channel[v];
                deepest = Math.max(deepest, filled[v] - ground[v]);
                int x = v % width, z = v / width;
                for (int dir = 0; dir < 8; dir++) {
                    int nx = x + RX[dir], nz = z + RZ[dir];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                    int j = nz * width + nx;
                    if (!visited[j] && land[j] && filled[j] == filled[i] && filled[j] > ground[j] + .5) {
                        visited[j] = true; queue[tail++] = j;
                    }
                }
            }
            if (connectedRiver && deepest >= MIN_LAKE_DEPTH && tail >= MIN_LAKE_NODES) {
                int id = surfaces.size(); surfaces.add(Math.max(0, filled[i] - FREEBOARD));
                for (int q = 0; q < tail; q++) basin[queue[q]] = id;
            }
        }
        this.levels = surfaces.stream().mapToDouble(Double::doubleValue).toArray();
        this.nodeSurface = new double[n];
        for (int i = 0; i < n; i++) nodeSurface[i] = basin[i] >= 0 ? levels[basin[i]] : Double.NaN;
        this.outletX = new double[levels.length]; this.outletZ = new double[levels.length];
        Arrays.fill(outletX, Double.NaN); Arrays.fill(outletZ, Double.NaN);
        // Shore region: grow outward from each lake over dry rising ground (never into lower ground such as the
        // outlet valley) while within REGION_DEPTH of its level, so the shore blend sees real node depths and
        // reaches zero before the region ends.
        // Chamfer distance from flooded nodes bounds the region horizontally (Dijkstra over 8 neighbours).
        boolean[] flooded = new boolean[n];
        this.reach = new double[n];
        Arrays.fill(reach, SHORE_REACH);
        PriorityQueue<double[]> frontier = new PriorityQueue<>(Comparator.<double[]>comparingDouble(e -> e[0]).thenComparingDouble(e -> e[1]));
        for (int i = 0; i < n; i++) if (basin[i] >= 0) { flooded[i] = true; reach[i] = 0; frontier.add(new double[]{0, i}); }
        while (!frontier.isEmpty()) {
            double[] entry = frontier.remove();
            int v = (int) entry[1];
            if (entry[0] > reach[v]) continue;
            int x = v % width, z = v / width, id = basin[v];
            for (int dir = 0; dir < 8; dir++) {
                int nx = x + RX[dir], nz = z + RZ[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (flooded[j] || !land[j] || basin[j] >= 0 && basin[j] != id) continue;
                double rise = ground[j] - levels[id], distance = entry[0] + step * Math.hypot(RX[dir], RZ[dir]);
                if (rise >= REGION_DEPTH || rise < 1 || distance >= reach[j] || distance >= SHORE_REACH) continue;
                basin[j] = id; reach[j] = distance;
                frontier.add(new double[]{distance, j});
            }
        }
        this.depthField = new double[n];
        for (int i = 0; i < n; i++) {
            depthField[i] = basin[i] < 0 ? -REGION_DEPTH : flooded[i] ? levels[basin[i]] - ground[i] : Math.min(-1, levels[basin[i]] - ground[i]);
        }
        // Fill shallow pinholes: a node barely above its lake's level but enclosed by flooded nodes would surface as
        // one of a lattice of identical islands at grid spacing. Real islands (more than ISLAND_MIN above) stay.
        for (int pass = 0; pass < 2; pass++) {
            double[] next = depthField.clone();
            for (int i = 0; i < n; i++) {
                if (basin[i] < 0 || depthField[i] > 0 || depthField[i] < -ISLAND_MIN) continue;
                int x = i % width, z = i / width, wet = 0;
                double sum = 0;
                for (int dir : CARDINAL) {
                    int nx = x + RX[dir], nz = z + RZ[dir];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                    int j = nz * width + nx;
                    if (basin[j] == basin[i] && depthField[j] > 0) { wet++; sum += depthField[j]; }
                }
                if (wet >= 3) next[i] = Math.max(.5, .5 * sum / wet);
            }
            System.arraycopy(next, 0, depthField, 0, n);
        }
        // Node values belong to jittered node positions; resample onto the lattice the sampler interpolates over.
        // Each lattice point keeps its own node's lake; nodes of other lakes count as outside (dry, out of reach).
        double[] depthOut = depthField.clone(), reachOut = reach.clone(), weights = new double[3];
        for (int iz = 1; iz < depth - 1; iz++) for (int ix = 1; ix < width - 1; ix++) {
            int i = iz * width + ix, id = basin[i];
            if (id < 0) continue;
            int[] t = HighlandDrainage.containing(ix, iz, nodeX, nodeZ, width, originX, originZ, step, weights);
            if (t == null) continue;
            double d = 0, r = 0;
            for (int k = 0; k < 3; k++) {
                boolean member = basin[t[k]] == id;
                d += weights[k] * (member ? depthField[t[k]] : -REGION_DEPTH);
                r += weights[k] * (member ? reach[t[k]] : SHORE_REACH);
            }
            depthOut[i] = d; reachOut[i] = r;
        }
        System.arraycopy(depthOut, 0, depthField, 0, n);
        System.arraycopy(reachOut, 0, reach, 0, n);
    }

    public int count() { return levels.length; }

    /** Lake surface at plan node i if the node is flooded, else NaN (RH6 L2). */
    double nodeSurface(int i) { return nodeSurface[i]; }

    /** Records where the channel leaves a lake; set once while the owning plan is built, before publication. */
    void setOutlet(int lake, double x, double z) {
        if (Double.isNaN(outletX[lake])) { outletX[lake] = x; outletZ[lake] = z; }
    }
    /** Outlet position of a lake, or NaN when no channel leaves it inside this plan. */
    public double outletX(int lake) { return outletX[lake]; }
    public double outletZ(int lake) { return outletZ[lake]; }

    /**
     * Lake surface and signed depth below it, or null away from every lake's shore region. Never throws.
     * <p>
     * RH5 S: corner weights are smoothstep-bilinear (C1 across grid lines, so shore shaping leaves no creases along
     * them), and where corners belong to different lakes each lake's field is evaluated on its own and the highest
     * (wettest) one wins: the result is continuous where ownership changes instead of switching at grid mid-lines.
     */
    public Sample sample(double x, double z) {
        double gx = (x - originX) / step, gz = (z - originZ) / step;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= depth - 1) return null;
        double tx = gx - ix, tz = gz - iz;
        tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        int c0 = iz * width + ix, c1 = c0 + 1, c2 = c0 + width, c3 = c2 + 1;
        int b0 = basin[c0], b1 = basin[c1], b2 = basin[c2], b3 = basin[c3];
        if (b0 < 0 && b1 < 0 && b2 < 0 && b3 < 0) return null;
        double w0 = (1 - tx) * (1 - tz), w1 = tx * (1 - tz), w2 = (1 - tx) * tz, w3 = tx * tz;
        int id = -1;
        double d = Double.NEGATIVE_INFINITY, distance = SHORE_REACH;
        for (int k = 0; k < 4; k++) {
            int candidate = k == 0 ? b0 : k == 1 ? b1 : k == 2 ? b2 : b3;
            if (candidate < 0 || candidate == id) continue;
            double cd = w0 * (b0 == candidate ? depthField[c0] : -REGION_DEPTH) + w1 * (b1 == candidate ? depthField[c1] : -REGION_DEPTH)
                + w2 * (b2 == candidate ? depthField[c2] : -REGION_DEPTH) + w3 * (b3 == candidate ? depthField[c3] : -REGION_DEPTH);
            if (cd > d || cd == d && candidate < id) {
                d = cd; id = candidate;
                distance = w0 * (b0 == candidate ? reach[c0] : SHORE_REACH) + w1 * (b1 == candidate ? reach[c1] : SHORE_REACH)
                    + w2 * (b2 == candidate ? reach[c2] : SHORE_REACH) + w3 * (b3 == candidate ? reach[c3] : SHORE_REACH);
            }
        }
        // Irregular shoreline: perturbs only the shallow margin, never the deep interior.
        double shoreline = 1 - smooth(0, 10, Math.abs(d));
        d += shoreline * (2 * (2 * noise(seed, x, z, 160) - 1) + (2 * noise(seed + 1, x, z, 56) - 1));
        return new Sample(id, levels[id], d, 1 - smooth(SHORE_REACH / 3, SHORE_REACH, distance));
    }
}
