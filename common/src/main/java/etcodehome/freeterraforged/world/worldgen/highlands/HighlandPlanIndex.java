package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Immutable index of coarse reach bounds; profiles retain their dense constraints. */
public final class HighlandPlanIndex {
    public static final int INFLUENCE = 1536;
    private static final int BUCKET = 512;
    private final HighlandDrainage.Plan plan;
    private final Map<Long, int[]> buckets;
    private final int[] outlets;
    public record Hit(double distance, double water, double area, double flowX, double flowZ, double confinement, double divide) {}

    public HighlandPlanIndex(HighlandDrainage.Plan plan) {
        this.plan = plan;
        this.outlets = new int[plan.nodes().size()];
        for (int i = 0; i < outlets.length; i++) outlets[i] = plan.nodes().get(i).parent() < 0 ? i : outlets[plan.nodes().get(i).parent()];
        Map<Long, List<Integer>> building = new HashMap<>();
        long entries = 0;
        for (int i = 0; i < plan.reaches().size(); i++) {
            var reach = plan.reaches().get(i);
            var a = plan.nodes().get(reach.downstream());
            var b = plan.nodes().get(reach.last());
            int minX = bucket(Math.min(a.x(), b.x()) - INFLUENCE), maxX = bucket(Math.max(a.x(), b.x()) + INFLUENCE);
            int minZ = bucket(Math.min(a.z(), b.z()) - INFLUENCE), maxZ = bucket(Math.max(a.z(), b.z()) + INFLUENCE);
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (++entries > 1_000_000) throw new IllegalStateException("RH segment index budget exceeded");
                building.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(i);
            }
        }
        long estimatedBytes = entries * 4 + building.size() * 96L + plan.nodes().size() * 84L
            + plan.gridNodes() * 12L + plan.reaches().size() * 32L;
        if (estimatedBytes > 8L * 1024 * 1024) throw new IllegalStateException("RH retained plan exceeds 8 MiB budget");
        Map<Long, int[]> finished = new HashMap<>();
        building.forEach((k, v) -> finished.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        this.buckets = Map.copyOf(finished);
    }
    public HighlandBasins.Sample lake(double x, double z) { return plan.basins().sample(x, z); }
    public Hit nearest(double x, double z) {
        int[] candidates = buckets.get(key(bucket(x), bucket(z)));
        if (candidates == null) return null;
        double best = (double) INFLUENCE * INFLUENCE;
        Hit hit = null;
        double second = Double.POSITIVE_INFINITY;
        int bestOutlet = -1;
        for (int index : candidates) {
            var reach = plan.reaches().get(index);
            var a = plan.nodes().get(reach.downstream());
            var b = plan.nodes().get(reach.last());
            double dx = b.x() - a.x(), dz = b.z() - a.z(), length2 = dx * dx + dz * dz;
            double t = clamp(((x - a.x()) * dx + (z - a.z()) * dz) / length2, 0, 1);
            double ox = x - a.x() - dx * t, oz = z - a.z() - dz * t;
            double d2 = ox * ox + oz * oz;
            int outlet = outlets[reach.downstream()];
            if (d2 >= best) {
                if (outlet != bestOutlet) second = Math.min(second, d2);
                continue;
            }
            if (outlet != bestOutlet && bestOutlet >= 0) second = best;
            bestOutlet = outlet;
            best = d2;
            int pieces = reach.last() - reach.first() + 1;
            double along = t * pieces;
            int piece = Math.min(pieces - 1, (int) along);
            var left = plan.nodes().get(piece == 0 ? reach.downstream() : reach.first() + piece - 1);
            var right = plan.nodes().get(reach.first() + piece);
            double water = lerp(left.water(), right.water(), along - piece);
            double confinement = smooth(8, 40, Math.abs(b.ground() - a.ground()));
            double length = Math.sqrt(length2);
            hit = new Hit(Math.sqrt(d2), water, lerp(a.area(), b.area(), t), -dx / length, -dz / length, confinement, 0);
        }
        if (hit == null) return null;
        double divide = Double.isFinite(second) ? smooth(96, 256, hit.distance())
            * (1 - smooth(0, 256, Math.sqrt(second) - hit.distance())) : 0;
        return new Hit(hit.distance(), hit.water(), hit.area(), hit.flowX(), hit.flowZ(), hit.confinement(), divide);
    }
    private static int bucket(double x) { return (int) Math.floor(x / BUCKET); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
