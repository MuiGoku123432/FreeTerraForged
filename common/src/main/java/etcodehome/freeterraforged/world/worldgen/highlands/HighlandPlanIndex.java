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
    private final HighlandDrainage.Plan plan;
    private final Map<Long, int[]> buckets;
    private final double[] confinement;

    /** Reusable per-thread query result. Values at index i describe one reach. */
    public static final class Hits {
        public int count;
        public final int[] reach = new int[MAX_HITS];
        public final double[] distance = new double[MAX_HITS], water = new double[MAX_HITS], area = new double[MAX_HITS],
            confinement = new double[MAX_HITS], flowX = new double[MAX_HITS], flowZ = new double[MAX_HITS];
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
        Map<Long, List<Integer>> building = new HashMap<>();
        long entries = 0;
        for (int i = 0; i < plan.reaches().size(); i++) {
            var reach = plan.reaches().get(i);
            var a = nodes.get(reach.downstream());
            var b = nodes.get(reach.last());
            int minX = bucket(Math.min(a.x(), b.x()) - INFLUENCE), maxX = bucket(Math.max(a.x(), b.x()) + INFLUENCE);
            int minZ = bucket(Math.min(a.z(), b.z()) - INFLUENCE), maxZ = bucket(Math.max(a.z(), b.z()) + INFLUENCE);
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (++entries > 1_000_000) throw new IllegalStateException("RH segment index budget exceeded");
                building.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(i);
            }
        }
        long estimatedBytes = entries * 4 + building.size() * 96L + nodes.size() * 92L
            + plan.gridNodes() * 12L + plan.reaches().size() * 32L;
        if (estimatedBytes > 8L * 1024 * 1024) throw new IllegalStateException("RH retained plan exceeds 8 MiB budget");
        Map<Long, int[]> finished = new HashMap<>();
        building.forEach((k, v) -> finished.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        this.buckets = Map.copyOf(finished);
    }

    HighlandDrainage.Plan plan() { return plan; }
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
            var reach = plan.reaches().get(index);
            var a = nodes.get(reach.downstream());
            var b = nodes.get(reach.last());
            double dx = b.x() - a.x(), dz = b.z() - a.z(), length2 = dx * dx + dz * dz;
            double t = length2 > 1e-12 ? clamp(((x - a.x()) * dx + (z - a.z()) * dz) / length2, 0, 1) : 0;
            double ox = x - a.x() - dx * t, oz = z - a.z() - dz * t;
            double d2 = ox * ox + oz * oz;
            if (d2 >= limit) continue;
            if (out.count == MAX_HITS) throw new IllegalStateException("RH reach query budget exceeded at " + x + "," + z);
            int pieces = reach.last() - reach.first() + 1;
            double along = t * pieces;
            int piece = Math.min(pieces - 1, (int) along);
            int left = piece == 0 ? reach.downstream() : reach.first() + piece - 1, right = reach.first() + piece;
            double f = along - piece;
            var l = nodes.get(left);
            var r = nodes.get(right);
            double length = Math.sqrt(length2);
            int k = out.count++;
            out.reach[k] = index;
            out.distance[k] = Math.sqrt(d2);
            out.water[k] = lerp(l.water(), r.water(), f);
            out.area[k] = lerp(l.area(), r.area(), f);
            out.confinement[k] = lerp(confinement[left], confinement[right], f);
            out.flowX[k] = length > 1e-9 ? -dx / length : 0;
            out.flowZ[k] = length > 1e-9 ? -dz / length : 0;
        }
        return out;
    }
    private static int bucket(double x) { return (int) Math.floor(x / BUCKET); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
