package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.clamp;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.lerp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RH10 drowned arms (D-GC1): the compact coastal valley data of one owner, derived from its undrowned plan0 (the RH9
 * planner's plan on a coast whose drowned layer is never bound) and discarded with it. Arms are drowned along these valleys
 * only, so they follow the valleys the planner draws.
 *
 * <p>Data are 16-block segments of plan0's valley axis (not the meandering refined channel), per segment the endpoints, a width
 * factor (drainage area to the power {@link HighlandDrowned#HW_EXP}, from the nearest refined node of the same plan0 reach)
 * and {@code u}, the undrowned edge above the coast point, at both ends, the plan0 reach and its downstream reach (the ancestry
 * the arm gates read). Only the connected coastal subtree is kept: from each reach's downstream end up to and including the
 * first sample beyond the arm reach, only segments within a region disc grown by {@link HighlandDrowned#MAX_HALF_WIDTH}, and
 * only reaches whose downstream chain of kept reaches ends at a plan0 root.
 *
 * <p>Segments are indexed in a uniform bucket grid, each over its box grown by its own largest half width, so a query reads one
 * bucket.
 */
final class HighlandArms {
    /** Bucket size of the segment grid (blocks). */
    private static final int BUCKET = 256;
    static final HighlandArms EMPTY = new HighlandArms(new ArrayList<>());

    /** Segment endpoints, width factor and u at both ends, largest half width, plan0 reach and downstream plan0 reach (-1 at a root). */
    final double[] ax, az, bx, bz, wa, wb, ua, ub, radius;
    final int[] reach, down;
    private final Map<Long, int[]> buckets;
    private final long entries;

    /** Reusable per-thread query result: values at index i describe one segment within its half-width reach of the position. */
    static final class Hits {
        int count;
        int[] segment = new int[64];
        double[] distance = new double[64], width = new double[64], u = new double[64];
        void add(int s, double d, double w, double uAt) {
            if (count == segment.length) {
                int n = 2 * count;
                segment = Arrays.copyOf(segment, n); distance = Arrays.copyOf(distance, n);
                width = Arrays.copyOf(width, n); u = Arrays.copyOf(u, n);
            }
            segment[count] = s; distance[count] = d; width[count] = w; u[count] = uAt;
            count++;
        }
    }
    private static final ThreadLocal<Hits> HITS = ThreadLocal.withInitial(Hits::new);

    private HighlandArms(List<double[]> found) {
        int n = found.size();
        ax = new double[n]; az = new double[n]; bx = new double[n]; bz = new double[n];
        wa = new double[n]; wb = new double[n]; ua = new double[n]; ub = new double[n]; radius = new double[n];
        reach = new int[n]; down = new int[n];
        Map<Long, List<Integer>> building = new HashMap<>();
        long count = 0;
        for (int s = 0; s < n; s++) {
            var f = found.get(s);
            ax[s] = f[0]; az[s] = f[1]; bx[s] = f[2]; bz[s] = f[3]; wa[s] = f[4]; wb[s] = f[5]; ua[s] = f[6]; ub[s] = f[7];
            reach[s] = (int) f[8]; down[s] = (int) f[9];
            radius[s] = HighlandDrowned.armRadius(Math.max(wa[s], wb[s]));
            // note: bucket grid over grown boxes copies HighlandPlanIndex's bucket keys (second use, not extracted).
            for (int z = bucket(Math.min(az[s], bz[s]) - radius[s]); z <= bucket(Math.max(az[s], bz[s]) + radius[s]); z++)
                for (int x = bucket(Math.min(ax[s], bx[s]) - radius[s]); x <= bucket(Math.max(ax[s], bx[s]) + radius[s]); x++) {
                    building.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(s);
                    count++;
                }
        }
        entries = count;
        buckets = new HashMap<>(building.size() * 2);
        building.forEach((k, v) -> buckets.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    /**
     * The arm data of the owner of {@code plan0}. {@code coastEdge} is the coast control point and {@code maxReachEdge} how far
     * inland of it (edge units) any region's arms reach; {@code coast} gives the undrowned edge and {@code drowned} the region
     * discs and width law.
     */
    static HighlandArms of(HighlandDrainage.Plan plan0, HighlandCoast coast, double coastEdge, double maxReachEdge, HighlandDrowned drowned) {
        var reaches = plan0.reaches();
        var nodes = plan0.nodes();
        var axis = plan0.axis();
        int[] nodeReach = new int[nodes.size()];
        Arrays.fill(nodeReach, -1);
        for (int r = 0; r < reaches.size(); r++) for (int v = reaches.get(r).first(); v <= reaches.get(r).last(); v++) nodeReach[v] = r;
        boolean[] kept = new boolean[reaches.size()], used = new boolean[axis.size()];
        List<double[]> found = new ArrayList<>();
        // Reaches are listed downstream first (parent stems before tributaries, upstream along each stem).
        for (int r = 0; r < reaches.size(); r++) {
            var reach = reaches.get(r);
            int down = nodeReach[reach.downstream()];
            if (down >= 0 && !kept[down]) continue;
            boolean any = false;
            double px = 0, pz = 0, pu = 0, pw = 0;
            for (int s = reach.axisFrom(); s <= reach.axisTo(); s++) {
                var a = axis.get(s);
                double u = coast.undrowned(a.x(), a.z()).edge() - coastEdge, w = HighlandDrowned.armWidth(nearestArea(nodes, reach, a));
                if (s > reach.axisFrom() && (drowned.nearRegion(px, pz) || drowned.nearRegion(a.x(), a.z()))) {
                    any = true; // a segment shared with the previous reach still keeps this reach (and its upstream subtree) connected
                    if (!used[s - 1]) {
                        found.add(new double[]{px, pz, a.x(), a.z(), pw, w, pu, u, r, down});
                        used[s - 1] = true;
                    }
                }
                if (u > maxReachEdge) break;
                px = a.x(); pz = a.z(); pu = u; pw = w;
            }
            kept[r] = any;
        }
        return new HighlandArms(found);
    }

    /** Drainage area of the refined node of the reach nearest to an axis sample. */
    private static double nearestArea(List<HighlandDrainage.Node> nodes, HighlandDrainage.Reach reach, HighlandDrainage.AxisNode a) {
        double best = Double.POSITIVE_INFINITY, area = 0;
        for (int v = reach.first(); v <= reach.last(); v++) {
            var node = nodes.get(v);
            double d = (node.x() - a.x()) * (node.x() - a.x()) + (node.z() - a.z()) * (node.z() - a.z());
            if (d < best) { best = d; area = node.area(); }
        }
        return area;
    }

    int segments() { return ax.length; }

    /** Estimated retained size (bytes) of the arrays and the bucket grid. */
    long estimatedBytes() { return segments() * (9 * 8L + 2 * 4L) + entries * 4 + buckets.size() * 96L; }

    /**
     * The segments within their own half-width reach of a position, with their perpendicular distance (clamped to the
     * segment) and the width factor and u interpolated at the projection. The result is per thread and reused: read it before the
     * next query on this thread.
     */
    Hits query(double x, double z) {
        var hits = HITS.get();
        hits.count = 0;
        int[] candidates = buckets.get(key(bucket(x), bucket(z)));
        if (candidates == null) return hits;
        for (int s : candidates) {
            double dx = bx[s] - ax[s], dz = bz[s] - az[s], length2 = dx * dx + dz * dz;
            double t = length2 > 0 ? clamp(((x - ax[s]) * dx + (z - az[s]) * dz) / length2, 0, 1) : 0;
            double ex = ax[s] + t * dx - x, ez = az[s] + t * dz - z, d2 = ex * ex + ez * ez;
            if (d2 <= radius[s] * radius[s]) hits.add(s, Math.sqrt(d2), lerp(wa[s], wb[s], t), lerp(ua[s], ub[s], t));
        }
        return hits;
    }

    private static int bucket(double x) { return (int) Math.floor(x / BUCKET); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
