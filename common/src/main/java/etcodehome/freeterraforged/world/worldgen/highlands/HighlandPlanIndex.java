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
    /** Oxbow influence (open water plus wetland margin) never exceeds this distance from the arc. */
    static final double OXBOW_REACH = 32;
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
            head = new double[MAX_HITS], slope = new double[MAX_HITS], offsetX = new double[MAX_HITS], offsetZ = new double[MAX_HITS],
            axis = new double[MAX_HITS], belt = new double[MAX_HITS];
    }
    private static final ThreadLocal<Hits> HITS = ThreadLocal.withInitial(Hits::new);
    /** Oxbow query result: distance to the arc, open-water half width there, and the still-water level. */
    public record OxbowSample(double distance, double halfWidth, double water) {}
    /**
     * Confluences (refined nodes with two upstream channels, outside lakes): position, parent flow direction
     * (downstream), unit vector toward the tributary's side, drainage areas of the main and tributary branches and the
     * angle between the two upstream branches (degrees, over JUNCTION_REFERENCE blocks).
     */
    public record Junction(int node, double x, double z, double flowX, double flowZ, double sideX, double sideZ,
                           double mainArea, double tributaryArea, double angle) {}
    /** Junction influence radius bound (blocks). */
    public static final double JUNCTION_REACH = 96;
    static final double JUNCTION_REFERENCE = 48;
    private final List<Junction> junctions;
    private final Map<Long, int[]> junctionBuckets;
    /** Oxbow arcs by bucket (indices into plan.oxbows()). */
    private final Map<Long, int[]> oxbowBuckets;

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
            for (int v = reach.axisFrom(); v <= reach.axisTo(); v++) {
                var node = plan.axis().get(v);
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
        long estimatedBytes = entries * 4 + building.size() * 96L + nodes.size() * 100L + plan.axis().size() * 40L + boxes.length * 8L
            + plan.gridNodes() * 20L + plan.reaches().size() * 32L;
        if (estimatedBytes > 8L * 1024 * 1024) throw new IllegalStateException("RH retained plan exceeds 8 MiB budget");
        // Confluences.
        List<List<Integer>> children = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) children.add(new ArrayList<>(2));
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).parent() >= 0) children.get(nodes.get(i).parent()).add(i);
        List<Junction> found = new ArrayList<>();
        Map<Long, List<Integer>> junctionBuilding = new HashMap<>();
        for (int j = 0; j < nodes.size(); j++) {
            var kids = children.get(j);
            var node = nodes.get(j);
            if (kids.size() != 2 || node.parent() < 0) continue;
            var lake = plan.basins().sample(node.x(), node.z());
            if (lake != null && lake.depth() > 0) continue;
            int main = nodes.get(kids.get(0)).area() >= nodes.get(kids.get(1)).area() ? kids.get(0) : kids.get(1);
            int trib = main == kids.get(0) ? kids.get(1) : kids.get(0);
            double[] mu = upstream(nodes, children, j, main), tu = upstream(nodes, children, j, trib);
            var down = nodes.get(node.parent());
            double fx = down.x() - node.x(), fz = down.z() - node.z(), fl = Math.hypot(fx, fz);
            if (fl < 1e-9 || mu == null || tu == null) continue;
            fx /= fl; fz /= fl;
            double lx = -fz, lz = fx, side = Math.signum(tu[0] * lx + tu[1] * lz);
            if (side == 0) side = 1;
            double angle = Math.toDegrees(Math.acos(clamp(mu[0] * tu[0] + mu[1] * tu[1], -1, 1)));
            int index = found.size();
            found.add(new Junction(j, node.x(), node.z(), fx, fz, side * lx, side * lz, nodes.get(main).area(), nodes.get(trib).area(), angle));
            for (int z = bucket(node.z() - JUNCTION_REACH); z <= bucket(node.z() + JUNCTION_REACH); z++)
                for (int x = bucket(node.x() - JUNCTION_REACH); x <= bucket(node.x() + JUNCTION_REACH); x++)
                    junctionBuilding.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(index);
        }
        this.junctions = List.copyOf(found);
        Map<Long, int[]> junctionFinished = new HashMap<>();
        junctionBuilding.forEach((k, v) -> junctionFinished.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        this.junctionBuckets = Map.copyOf(junctionFinished);
        Map<Long, List<Integer>> oxbowBuilding = new HashMap<>();
        for (int i = 0; i < plan.oxbows().size(); i++) {
            var oxbow = plan.oxbows().get(i);
            double bx0 = Double.POSITIVE_INFINITY, bz0 = Double.POSITIVE_INFINITY, bx1 = Double.NEGATIVE_INFINITY, bz1 = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < oxbow.x().length; p++) {
                bx0 = Math.min(bx0, oxbow.x()[p]); bz0 = Math.min(bz0, oxbow.z()[p]);
                bx1 = Math.max(bx1, oxbow.x()[p]); bz1 = Math.max(bz1, oxbow.z()[p]);
            }
            for (int z = bucket(bz0 - OXBOW_REACH); z <= bucket(bz1 + OXBOW_REACH); z++)
                for (int x = bucket(bx0 - OXBOW_REACH); x <= bucket(bx1 + OXBOW_REACH); x++)
                    oxbowBuilding.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(i);
        }
        Map<Long, int[]> oxbowFinished = new HashMap<>();
        oxbowBuilding.forEach((k, v) -> oxbowFinished.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        this.oxbowBuckets = Map.copyOf(oxbowFinished);
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
    /**
     * Base level at a position: smoothstep-weighted bilinear interpolation of the per-node water levels. Bounded by
     * the four surrounding nodes (no overshoot) and C1. Land is never rendered below it, so legacy land detail minus
     * a smooth incision cannot leave dry pits below the rivers that drain them.
     */
    public double waterFloor(double x, double z) {
        return smoothBilinear(plan.water(), x, z);
    }
    /** Smoothed landscape height for climate (RH5 C), interpolated like the base level. */
    public double climateHeight(double x, double z) {
        return smoothBilinear(plan.climate(), x, z);
    }
    private double smoothBilinear(double[] grid, double x, double z) {
        int width = plan.width(), depth = grid.length / Math.max(1, width), step = plan.gridStep();
        double gx = (x - plan.originX()) / step, gz = (z - plan.originZ()) / step;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        double tx = gx - ix, tz = gz - iz;
        tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        double a = node(grid, width, depth, ix, iz), b = node(grid, width, depth, ix + 1, iz);
        double c = node(grid, width, depth, ix, iz + 1), d = node(grid, width, depth, ix + 1, iz + 1);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }
    private static double node(double[] grid, int width, int depth, int x, int z) {
        return x < 0 || z < 0 || x >= width || z >= depth ? 0 : grid[z * width + x];
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

    /** Unit direction from junction node j up the branch starting at child c, over JUNCTION_REFERENCE blocks. */
    private static double[] upstream(List<HighlandDrainage.Node> nodes, List<List<Integer>> children, int j, int c) {
        var start = nodes.get(j);
        int v = c;
        double travelled = 0;
        var prev = start;
        while (true) {
            var node = nodes.get(v);
            travelled += Math.hypot(node.x() - prev.x(), node.z() - prev.z());
            prev = node;
            if (travelled >= JUNCTION_REFERENCE || children.get(v).size() != 1) break;
            v = children.get(v).get(0);
        }
        double dx = prev.x() - start.x(), dz = prev.z() - start.z(), l = Math.hypot(dx, dz);
        return l < 1e-9 ? null : new double[]{dx / l, dz / l};
    }

    private static final int[] NONE = new int[0];
    /** Indices of confluences whose JUNCTION_REACH may contain (x, z) (a superset; check the distance). */
    public int[] junctionCandidates(double x, double z) {
        int[] candidates = junctionBuckets.get(key(bucket(x), bucket(z)));
        return candidates == null ? NONE : candidates;
    }
    public Junction junction(int index) { return junctions.get(index); }
    /** True when reach r ends at (flows into) junction node j or contains it. */
    public boolean touches(int reach, int node) {
        return plan.reaches().get(reach).downstream() == node || nodeReach[node] == reach;
    }

    /** Nearest oxbow arc within OXBOW_REACH, or null. */
    public OxbowSample oxbow(double x, double z) {
        int[] candidates = oxbowBuckets.get(key(bucket(x), bucket(z)));
        if (candidates == null) return null;
        double best = OXBOW_REACH, half = 0, water = 0;
        boolean found = false;
        for (int index : candidates) {
            var oxbow = plan.oxbows().get(index);
            double[] ax = oxbow.x(), az = oxbow.z(), hw = oxbow.halfWidth();
            for (int p = 0; p + 1 < ax.length; p++) {
                double dx = ax[p + 1] - ax[p], dz = az[p + 1] - az[p], length2 = dx * dx + dz * dz;
                double t = length2 > 1e-12 ? clamp(((x - ax[p]) * dx + (z - az[p]) * dz) / length2, 0, 1) : 0;
                double d = Math.hypot(x - ax[p] - dx * t, z - az[p] - dz * t);
                if (d < best) { best = d; half = lerp(hw[p], hw[p + 1], t); water = oxbow.water(); found = true; }
            }
        }
        return found ? new OxbowSample(best, half, water) : null;
    }

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
            double best2 = Double.POSITIVE_INFINITY, nearestX = 0, nearestZ = 0;
            int left = reach.downstream();
            for (int piece = 0; piece < pieces; piece++) {
                int right = reach.first() + piece;
                var a = nodes.get(left);
                var b = nodes.get(right);
                double dx = b.x() - a.x(), dz = b.z() - a.z(), length2 = dx * dx + dz * dz;
                double t = length2 > 1e-12 ? clamp(((x - a.x()) * dx + (z - a.z()) * dz) / length2, 0, 1) : 0;
                double ox = x - a.x() - dx * t, oz = z - a.z() - dz * t, d2 = ox * ox + oz * oz;
                dist[piece] = d2; along[piece] = t; // squared until the blend below
                if (d2 < best2) { best2 = d2; nearestX = ox; nearestZ = oz; }
                left = right;
            }
            double best = Math.sqrt(best2);
            // Valley axis distance and the meander belt there (floors widen to contain the belt).
            double axisBest = Double.POSITIVE_INFINITY, belt = 0;
            var axis = plan.axis();
            for (int v = reach.axisFrom(); v < reach.axisTo(); v++) {
                var a = axis.get(v);
                var b = axis.get(v + 1);
                double dx = b.x() - a.x(), dz = b.z() - a.z(), length2 = dx * dx + dz * dz;
                double t = length2 > 1e-12 ? clamp(((x - a.x()) * dx + (z - a.z()) * dz) / length2, 0, 1) : 0;
                double ox = x - a.x() - dx * t, oz = z - a.z() - dz * t, d2 = ox * ox + oz * oz;
                if (d2 < axisBest) { axisBest = d2; belt = lerp(a.belt(), b.belt(), t); }
            }
            axisBest = Math.sqrt(axisBest);
            if (best >= INFLUENCE && axisBest - belt >= INFLUENCE) continue;
            if (out.count == MAX_HITS) break; // far beyond any real density; deterministic order, never throws
            // A curved reach can have separate nearly equidistant parts (inside a bend). Attributes are a smooth
            // weighted blend over pieces by their excess distance; the blend width grows with distance, so on and
            // near the channel the result is the exact interpolated profile, and far away it varies continuously.
            double sigma = Math.max(1, BLEND_RATE * best), reach2 = (best + 3 * sigma) * (best + 3 * sigma);
            double weights = 0, water = 0, area = 0, conf = 0, head = 0, fx = 0, fz = 0, slope = 0;
            for (int piece = 0; piece < pieces; piece++) {
                if (dist[piece] >= reach2) continue;
                double excess = Math.sqrt(dist[piece]) - best;
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
            out.axis[k] = axisBest;
            out.belt[k] = belt;
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
