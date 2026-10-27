package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Connected inundation components with fixed spill surfaces; routing fill is never emitted as dry land. */
public final class HighlandBasins {
    private final int originX, originZ, width, depth, step;
    private final double[] ground, levels;
    private final int[] basin;

    public record Sample(double water, double depth) {}

    HighlandBasins(int originX, int originZ, int width, int depth, int step, double[] ground,
                   double[] filled, boolean[] land, boolean[] channel) {
        this.originX = originX; this.originZ = originZ; this.width = width; this.depth = depth; this.step = step;
        this.ground = ground.clone(); this.basin = new int[ground.length];
        Arrays.fill(basin, -1);
        List<Double> surfaces = new ArrayList<>();
        int[] queue = new int[ground.length];
        boolean[] visited = new boolean[ground.length];
        for (int i = 0; i < ground.length; i++) {
            if (visited[i] || !land[i] || filled[i] <= ground[i] + 2) continue;
            int head = 0, tail = 1; queue[0] = i; visited[i] = true;
            boolean connectedRiver = false;
            while (head < tail) {
                int v = queue[head++]; connectedRiver |= channel[v];
                int x = v % width, z = v / width;
                for (int dir = 0; dir < 6; dir++) {
                    int nx = x + (dir == 0 || dir == 4 ? -1 : dir == 1 || dir == 5 ? 1 : 0);
                    int nz = z + (dir == 2 || dir == 4 ? -1 : dir == 3 || dir == 5 ? 1 : 0);
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                    int j = nz * width + nx;
                    if (!visited[j] && land[j] && filled[j] == filled[i] && filled[j] > ground[j] + 2) {
                        visited[j] = true; queue[tail++] = j;
                    }
                }
            }
            if (connectedRiver) {
                int id = surfaces.size(); surfaces.add(Math.max(0, filled[i] - 2));
                for (int q = 0; q < tail; q++) basin[queue[q]] = id;
            }
        }
        this.levels = surfaces.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public int count() { return levels.length; }

    public Sample sample(double x, double z) {
        double gx = (x - originX) / step, gz = (z - originZ) / step;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= depth - 1) return null;
        int a = iz * width + ix, b = a + 1, c = a + width, d = c + 1;
        double tx = gx - ix, tz = gz - iz;
        int[] corners = tx >= tz ? new int[]{a, b, d} : new int[]{a, c, d};
        double[] weights = tx >= tz ? new double[]{1 - tx, tx - tz, tz} : new double[]{1 - tz, tz - tx, tx};
        int id = -1;
        for (int k = 0; k < 3; k++) if (weights[k] > 1e-12 && basin[corners[k]] >= 0) { id = basin[corners[k]]; break; }
        if (id < 0) return null;
        double water = levels[id];
        double floor = weights[0] * ground[corners[0]] + weights[1] * ground[corners[1]] + weights[2] * ground[corners[2]];
        for (int k = 0; k < 3; k++) {
            int other = basin[corners[k]];
            if (weights[k] > 1e-12 && other >= 0 && levels[other] != water && floor < Math.min(water, levels[other])) {
                throw new IllegalStateException("RH adjacent basins need a shared spill merge at " + x + "," + z);
            }
        }
        return new Sample(water, water - floor);
    }
}
