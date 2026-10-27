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
    /** Minimum flooded depth (planning blocks) before a depression becomes a lake rather than being breached. */
    static final double MIN_LAKE_DEPTH = 12;
    static final int MIN_LAKE_NODES = 1;
    /** Widest shore blend in depth units. */
    static final double MAX_SHORE = 40;
    /** Shore regions extend over dry nodes less than this far above the lake; also the depth assumed beyond. */
    static final double REGION_DEPTH = 96;
    /** Horizontal reach (blocks) of a lake's shore influence from its flooded nodes. */
    static final double SHORE_REACH = 384;
    private static final int[] RX = {-1, 0, 1, -1, 1, -1, 0, 1}, RZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private final int originX, originZ, width, depth, step;
    private final long seed;
    private final double[] levels, depthField, reach;
    private final int[] basin;

    /** {@code proximity} is 1 near flooded nodes and falls smoothly to 0 at SHORE_REACH from them. */
    public record Sample(double water, double depth, double proximity) {}

    HighlandBasins(long seed, int originX, int originZ, int width, int depth, int step, double[] ground,
                   double[] filled, boolean[] land, boolean[] channel) {
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
                int id = surfaces.size(); surfaces.add(Math.max(0, filled[i] - 2));
                for (int q = 0; q < tail; q++) basin[queue[q]] = id;
            }
        }
        this.levels = surfaces.stream().mapToDouble(Double::doubleValue).toArray();
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
    }

    public int count() { return levels.length; }

    /** Lake surface and signed depth below it, or null away from every lake's shore region. Never throws. */
    public Sample sample(double x, double z) {
        double gx = (x - originX) / step, gz = (z - originZ) / step;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= depth - 1) return null;
        double tx = gx - ix, tz = gz - iz;
        int[] corners = {iz * width + ix, iz * width + ix + 1, (iz + 1) * width + ix, (iz + 1) * width + ix + 1};
        double[] weights = {(1 - tx) * (1 - tz), tx * (1 - tz), (1 - tx) * tz, tx * tz};
        int id = -1; double best = 0;
        for (int k = 0; k < 4; k++) {
            if (basin[corners[k]] >= 0 && weights[k] > best) { best = weights[k]; id = basin[corners[k]]; }
        }
        if (id < 0) return null;
        double d = 0, distance = 0;
        for (int k = 0; k < 4; k++) {
            boolean member = basin[corners[k]] == id;
            d += weights[k] * (member ? depthField[corners[k]] : -REGION_DEPTH);
            distance += weights[k] * (member ? reach[corners[k]] : SHORE_REACH);
        }
        // Irregular shoreline: perturbs only the shallow margin, never the deep interior.
        double shoreline = 1 - smooth(0, 10, Math.abs(d));
        d += shoreline * (2 * (2 * noise(seed, x, z, 160) - 1) + (2 * noise(seed + 1, x, z, 56) - 1));
        return new Sample(levels[id], d, 1 - smooth(SHORE_REACH / 3, SHORE_REACH, distance));
    }
}
