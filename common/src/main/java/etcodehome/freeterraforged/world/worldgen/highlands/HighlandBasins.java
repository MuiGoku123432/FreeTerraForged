package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/**
 * Lakes: priority-flood depressions that a selected channel passes through and that are deep enough to hold
 * water. Shallow fills are breached by the river profile instead of flooded. Each lake has one fixed surface
 * (spill level minus two). Shorelines come from a bilinear depth field over the lake and a one-node ring around
 * it, perturbed by low-amplitude noise, so they are continuous contours rather than grid triangles.
 */
public final class HighlandBasins {
    /** Minimum flooded depth (planning blocks) before a depression becomes a lake rather than being breached. */
    static final double MIN_LAKE_DEPTH = 12;
    static final int MIN_LAKE_NODES = 1;
    /** Depth assigned outside lakes and their rings; well below the -8 shore blend. */
    static final double OUTSIDE = -64;
    /** Widest shore blend in depth units; must stay well above OUTSIDE. */
    static final double MAX_SHORE = 40;
    private static final int[] RX = {-1, 0, 1, -1, 1, -1, 0, 1}, RZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private final int originX, originZ, width, depth, step;
    private final long seed;
    private final double[] depthField, levels;
    private final int[] basin;

    public record Sample(double water, double depth) {}

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
        // One-node ring so the shore contour can form between a lake node and its dry neighbours.
        int[] ring = basin.clone();
        for (int i = 0; i < n; i++) {
            if (basin[i] < 0) continue;
            int x = i % width, z = i / width;
            for (int dir = 0; dir < 8; dir++) {
                int nx = x + RX[dir], nz = z + RZ[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (ring[j] < 0 && land[j]) ring[j] = basin[i];
            }
        }
        System.arraycopy(ring, 0, basin, 0, n);
        this.depthField = new double[n];
        for (int i = 0; i < n; i++) depthField[i] = basin[i] >= 0 ? levels[basin[i]] - ground[i] : OUTSIDE;
    }

    public int count() { return levels.length; }

    /** Lake surface and signed depth below it, or null where no lake or ring node is nearby. Never throws. */
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
        double d = 0;
        for (int k = 0; k < 4; k++) d += weights[k] * (basin[corners[k]] == id ? depthField[corners[k]] : OUTSIDE);
        // Irregular shoreline: perturbs only the shallow margin, never the deep interior.
        double shoreline = 1 - smooth(0, 10, Math.abs(d));
        d += shoreline * (2 * (2 * noise(seed, x, z, 160) - 1) + (2 * noise(seed + 1, x, z, 56) - 1));
        return new Sample(levels[id], d);
    }
}
