package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/**
 * Immutable spatial index over refined reaches. Queries return every reach within {@link #INFLUENCE}, with
 * attributes interpolated from per-node values so they are continuous along reaches and across junctions
 * (junction nodes are shared by every reach that meets there).
 */
public final class HighlandPlanIndex {
    /** Upper bound of any reach's lateral valley influence; see RollingHighlandsContinent valley limits. */
    public static final int INFLUENCE = 960;
    private static final int BUCKET = 512;
    private static final int MAX_HITS = 512;
    /** Attribute blend width per block of distance from the channel (see near()). */
    private static final double BLEND_RATE = .25;
    private final HighlandDrainage.Plan plan;
    private final Map<Long, int[]> buckets;
    private final double[] confinement;
    /** Reach that owns each refined node (-1 for outlet roots). */
    private final int[] nodeReach;
    /** Per-reach bounding boxes of the refined polyline (minX, minZ, maxX, maxZ). */
    private final double[] boxes;

    /** Reusable per-thread query result. Values at index i describe one reach. */
    public static final class Hits {
        public int count;
        public final int[] reach = new int[MAX_HITS];
        public final double[] distance = new double[MAX_HITS], water = new double[MAX_HITS], area = new double[MAX_HITS],
            confinement = new double[MAX_HITS], flowX = new double[MAX_HITS], flowZ = new double[MAX_HITS],
            head = new double[MAX_HITS], slope = new double[MAX_HITS], offsetX = new double[MAX_HITS], offsetZ = new double[MAX_HITS];
    }
    private static final ThreadLocal<Hits> HITS = ThreadLocal.withInitial(Hits::new);

    public HighlandPlanIndex(HighlandDrainage.Plan plan) {
        this.plan = plan;
        var nodes = plan.nodes();
        this.confinement = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            if (node.parent() < 0) continue;
            var parent = nodes.get(node.parent());
            double run = Math.max(1, Math.hypot(node.x() - parent.x(), node.z() - parent.z()));
            confinement[i] = smooth(.03, .25, Math.abs(node.ground() - parent.ground()) / run);
        }
        this.nodeReach = new int[nodes.size()];
        Arrays.fill(nodeReach, -1);
        for (int r = 0; r < plan.reaches().size(); r++) {
            var reach = plan.reaches().get(r);
            for (int v = reach.first(); v <= reach.last(); v++) nodeReach[v] = r;
        }
        Map<Long, List<Integer>> building = new HashMap<>();
        long entries = 0;
        this.boxes = new double[plan.reaches().size() * 4];
        for (int i = 0; i < plan.reaches().size(); i++) {
            var reach = plan.reaches().get(i);
            var a = nodes.get(reach.downstream());
            double bx0 = a.x(), bz0 = a.z(), bx1 = a.x(), bz1 = a.z();
            for (int v = reach.first(); v <= reach.last(); v++) {
                var node = nodes.get(v);
                bx0 = Math.min(bx0, node.x()); bz0 = Math.min(bz0, node.z());
                bx1 = Math.max(bx1, node.x()); bz1 = Math.max(bz1, node.z());
            }
            boxes[i * 4] = bx0; boxes[i * 4 + 1] = bz0; boxes[i * 4 + 2] = bx1; boxes[i * 4 + 3] = bz1;
            int minX = bucket(bx0 - INFLUENCE), maxX = bucket(bx1 + INFLUENCE);
            int minZ = bucket(bz0 - INFLUENCE), maxZ = bucket(bz1 + INFLUENCE);
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (++entries > 1_000_000) throw new IllegalStateException("RH segment index budget exceeded");
                building.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(i);
            }
        }
        long estimatedBytes = entries * 4 + building.size() * 96L + nodes.size() * 100L + boxes.length * 8L
            + plan.gridNodes() * 12L + plan.reaches().size() * 32L;
        if (estimatedBytes > 8L * 1024 * 1024) throw new IllegalStateException("RH retained plan exceeds 8 MiB budget");
        Map<Long, int[]> finished = new HashMap<>();
        building.forEach((k, v) -> finished.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        this.buckets = Map.copyOf(finished);
    }

    HighlandDrainage.Plan plan() { return plan; }

    /** True when two reaches meet in the network (one flows into the other, or both join at the same node). */
    public boolean connected(int a, int b) {
        var ra = plan.reaches().get(a);
        var rb = plan.reaches().get(b);
        return ra.downstream() == rb.downstream() || nodeReach[ra.downstream()] == b || nodeReach[rb.downstream()] == a;
    }

    /** Stream-power incision depth at a position: C1 Catmull-Rom interpolation of the plan grid, never negative. */
    public double incision(double x, double z) {
        double[] grid = plan.incision();
        int width = plan.width(), depth = grid.length / Math.max(1, width), step = plan.gridStep();
        double gx = (x - plan.originX()) / step, gz = (z - plan.originZ()) / step;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        double tx = gx - ix, tz = gz - iz, sum = 0;
        for (int dz = -1; dz <= 2; dz++) {
            double row = 0;
            for (int dx = -1; dx <= 2; dx++) {
                int cx = ix + dx, cz = iz + dz;
                double v = cx < 0 || cz < 0 || cx >= width || cz >= depth ? 0 : grid[cz * width + cx];
                row += v * catmullRom(dx, tx);
            }
            sum += row * catmullRom(dz, tz);
        }
        return Math.max(0, sum);
    }
    private static double catmullRom(int offset, double t) {
        double t2 = t * t, t3 = t2 * t;
        return switch (offset) {
            case -1 -> .5 * (-t3 + 2 * t2 - t);
            case 0 -> .5 * (3 * t3 - 5 * t2 + 2);
            case 1 -> .5 * (-3 * t3 + 4 * t2 + t);
            default -> .5 * (t3 - t2);
        };
    }
    double basinsOutletX(int lake) { return plan.basins().outletX(lake); }
    double basinsOutletZ(int lake) { return plan.basins().outletZ(lake); }
    public HighlandBasins.Sample lake(double x, double z) { return plan.basins().sample(x, z); }

    /** All reaches whose centreline lies within INFLUENCE of (x, z), in ascending reach order. */
    public Hits near(double x, double z) {
        Hits out = HITS.get();
        out.count = 0;
        int[] candidates = buckets.get(key(bucket(x), bucket(z)));
        if (candidates == null) return out;
        var nodes = plan.nodes();
        double limit = (double) INFLUENCE * INFLUENCE;
        for (int index : candidates) {
            double ex = Math.max(0, Math.max(boxes[index * 4] - x, x - boxes[index * 4 + 2]));
            double ez = Math.max(0, Math.max(boxes[index * 4 + 1] - z, z - boxes[index * 4 + 3]));
            if (ex * ex + ez * ez >= limit) continue;
            var reach = plan.reaches().get(index);
            // Distance to every piece of the refined polyline downstream, first, ..., last.
            int pieces = reach.last() - reach.first() + 1;
            double[] dist = scratch(pieces, 0), along = scratch(pieces, 1);
            double best = Double.POSITIVE_INFINITY, nearestX = 0, nearestZ = 0;
            int left = reach.downstream();
            for (int piece = 0; piece < pieces; piece++) {
                int right = reach.first() + piece;
                var a = nodes.get(left);
                var b = nodes.get(right);
                double dx = b.x() - a.x(), dz = b.z() - a.z(), length2 = dx * dx + dz * dz;
                double t = length2 > 1e-12 ? clamp(((x - a.x()) * dx + (z - a.z()) * dz) / length2, 0, 1) : 0;
                double ox = x - a.x() - dx * t, oz = z - a.z() - dz * t;
                dist[piece] = Math.sqrt(ox * ox + oz * oz); along[piece] = t;
                if (dist[piece] < best) { best = dist[piece]; nearestX = ox; nearestZ = oz; }
                left = right;
            }
            if (best >= INFLUENCE) continue;
            if (out.count == MAX_HITS) break; // far beyond any real density; deterministic order, never throws
            // A curved reach can have separate nearly equidistant parts (inside a bend). Attributes are a smooth
            // weighted blend over pieces by their excess distance; the blend width grows with distance, so on and
            // near the channel the result is the exact interpolated profile, and far away it varies continuously.
            double sigma = Math.max(1, BLEND_RATE * best);
            double weights = 0, water = 0, area = 0, conf = 0, head = 0, fx = 0, fz = 0, slope = 0;
            for (int piece = 0; piece < pieces; piece++) {
                double excess = dist[piece] - best;
                if (excess >= 3 * sigma) continue;
                int l = piece == 0 ? reach.downstream() : reach.first() + piece - 1, r = reach.first() + piece;
                var a = nodes.get(l);
                var b = nodes.get(r);
                double t = along[piece], w = Math.exp(-(excess / sigma) * (excess / sigma));
                double dx = b.x() - a.x(), dz = b.z() - a.z(), length = Math.hypot(dx, dz);
                weights += w;
                water += w * lerp(a.water(), b.water(), t);
                area += w * lerp(a.area(), b.area(), t);
                conf += w * lerp(confinement[l], confinement[r], t);
                head += w * lerp(a.head(), b.head(), t);
                if (length > 1e-9) { fx -= w * dx / length; fz -= w * dz / length; slope += w * Math.max(0, b.water() - a.water()) / length; }
            }
            double dmin = best;
            int k = out.count++;
            double flow = Math.hypot(fx, fz);
            out.reach[k] = index;
            out.distance[k] = dmin;
            out.water[k] = water / weights;
            out.area[k] = area / weights;
            out.confinement[k] = conf / weights;
            out.head[k] = head / weights;
            out.slope[k] = slope / weights;
            out.offsetX[k] = nearestX; out.offsetZ[k] = nearestZ;
            out.flowX[k] = flow > 1e-9 ? fx / flow : 0;
            out.flowZ[k] = flow > 1e-9 ? fz / flow : 0;
        }
        return out;
    }
    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[][]{new double[64], new double[64]});
    private static double[] scratch(int size, int slot) {
        double[][] buffers = SCRATCH.get();
        if (buffers[slot].length < size) buffers[slot] = new double[Math.max(size, buffers[slot].length * 2)];
        return buffers[slot];
    }
    private static int bucket(double x) { return (int) Math.floor(x / BUCKET); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
