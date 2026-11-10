package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.clamp;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.lerp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RH10 drowned arms (D-GC1, D-GC8): the compact coastal valley data of one owner, derived from the physical valley network of its
 * undrowned plan0 (the RH9 planner's plan on a coast whose drowned layer is never bound) and discarded with it. Arms are drowned along
 * these valleys only. The network is independent of River Count ({@link HighlandDrainage.Valleys}), so the arms are too.
 *
 * <p>Data are 16-block segments of the valley axis (not the meandering refined channel), per segment the endpoints, a width
 * factor (drainage area to the power {@link HighlandDrowned#HW_EXP}, interpolated along the link) and {@code u}, the undrowned edge
 * above the coast point, at both ends, the physical branch (its id, the grid index of its downstream-most land node; "plan0 reach"
 * below means this branch) and its downstream branch (the ancestry the arm gates read). Only the connected coastal subtree is kept:
 * from each branch's junction up to and including the first sample beyond the arm reach, only segments within a region disc grown by
 * {@link HighlandDrowned#MAX_HALF_WIDTH}, and only branches whose downstream chain of kept branches ends at a sea root.
 *
 * <p>Segments are indexed in a uniform bucket grid, each over its box grown by its own largest half width, so a query reads one
 * bucket.
 */
final class HighlandArms {
    /** Bucket size of the segment grid (blocks). */
    private static final int BUCKET = 256;
    static final HighlandArms EMPTY = new HighlandArms(new ArrayList<>());

    /** Segment endpoints, width factor and u at both ends, largest half width, physical branch and downstream branch (-1 at a sea root). */
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
        var valleys = plan0.valleys();
        boolean[] kept = new boolean[valleys.branches()];
        List<double[]> found = new ArrayList<>();
        // Branches are listed downstream first (a branch follows its downstream branch).
        for (int b = 0; b < kept.length; b++) {
            int down = valleys.down()[b];
            if (down >= 0 && !kept[down]) continue;
            boolean any = false;
            double px = 0, pz = 0, pu = 0, pw = 0;
            for (int s = valleys.first()[b]; s < valleys.first()[b + 1]; s++) {
                double x = valleys.x()[s], z = valleys.z()[s];
                double u = coast.undrowned(x, z).edge() - coastEdge, w = HighlandDrowned.armWidth(valleys.area()[s]);
                if (s > valleys.first()[b] && (drowned.nearRegion(px, pz) || drowned.nearRegion(x, z))) {
                    any = true;
                    found.add(new double[]{px, pz, x, z, pw, w, pu, u, valleys.id()[b], down < 0 ? -1 : valleys.id()[down]});
                }
                if (u > maxReachEdge) break;
                px = x; pz = z; pu = u; pw = w;
            }
            kept[b] = any;
        }
        return new HighlandArms(found);
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
