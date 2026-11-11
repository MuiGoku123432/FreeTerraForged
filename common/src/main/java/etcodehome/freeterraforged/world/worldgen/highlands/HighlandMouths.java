package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.clamp;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.lerp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RH10 river mouths (D-11-18-1, 11-24): the mouth profile of every sea-reaching channel of one final plan. A profile is a polyline of
 * stations along the channel (land stations upstream of the coast crossing, sea stations seaward of it), each with the signed
 * along-channel arclength {@code s} (0 at the crossing, positive upstream), the bed {@code b(s)} the land branch and the sea branch both
 * render, the natural bed the land branch would draw there, the water and the half width. The land branch (the wet bed of the channel)
 * and the ocean branch (a trough in the sea floor) read it through {@code RollingHighlandsContinent.mouthProfile}, so both sides of the
 * contour share one bed at the same real distance along the channel.
 *
 * <p>Stations stay in {@code s} ascending order: index 0 is the seaward end (the trough end plus its clear run, or the reach limit),
 * the crossing station {@code crossing} has {@code s = 0}. Segments between consecutive stations are indexed in a uniform bucket grid,
 * each over its box grown by its profile's corridor radius, so a query reads one bucket. Pure data of one plan: a memo of a
 * function of that plan's nodes and the position-only coast and sea floor.
 */
final class HighlandMouths {
    /** Profile status: usable, re-emerging land came before the trough end, no trough end within the reach limit. */
    static final int FEASIBLE = 0, LAND = 1, REACH = 2;
    /** Bucket size of the segment grid (blocks). */
    private static final int BUCKET = 64;
    static final HighlandMouths EMPTY = new HighlandMouths(List.of());

    /**
     * One sea-reaching channel. {@code reaches}: plan reach ids of its land stations; arrays are per station; {@code trough}: station
     * index of the trough end E (-1 when none); {@code outsideBand} and {@code crossOwnerStations}: stations whose centre or lateral corner
     * lies below the mouth band, and stations whose coast owner is not the plan's owner; {@code minEdge}: the lowest edge among station
     * centres and lateral corners; {@code radius}: the corridor radius (largest half width plus the largest bank run plus a margin).
     */
    record Profile(int[] reaches, double[] x, double[] z, double[] s, double[] bed, double[] natural, double[] water, double[] halfWidth,
                   int crossing, int trough, int status, int outsideBand, int crossOwnerStations, double minEdge, double radius) {
        boolean ownsReach(int reach) { for (int r : reaches) if (r == reach) return true; return false; }
        int stations() { return x.length; }
    }

    /** Reusable per-thread query result: values at index i describe one profile whose corridor contains the position (blended over bend segments). */
    static final class Hits {
        int count;
        Profile[] profile = new Profile[8];
        double[] s = new double[8], distance = new double[8], bed = new double[8], natural = new double[8], halfWidth = new double[8];
        void add(Profile p, double along, double d, double b, double n, double hw) {
            if (count == profile.length) {
                int size = 2 * count;
                profile = Arrays.copyOf(profile, size); s = Arrays.copyOf(s, size); distance = Arrays.copyOf(distance, size);
                bed = Arrays.copyOf(bed, size); natural = Arrays.copyOf(natural, size); halfWidth = Arrays.copyOf(halfWidth, size);
            }
            profile[count] = p; s[count] = along; distance[count] = d; bed[count] = b; natural[count] = n; halfWidth[count] = hw;
            count++;
        }
    }
    private static final ThreadLocal<Hits> HITS = ThreadLocal.withInitial(Hits::new);
    /** Scratch of one query: per candidate segment its distance to the position and its projection parameter. */
    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[][]{new double[64], new double[64]});

    private final Profile[] profiles;
    /** Global segment list: owning profile and the station index the segment starts at. */
    private final int[] segmentProfile, segmentStation;
    private final Map<Long, int[]> buckets;
    private final long entries, stations;

    HighlandMouths(List<Profile> found) {
        profiles = found.toArray(new Profile[0]);
        int count = 0;
        long stationCount = 0;
        for (var profile : profiles) { count += Math.max(0, profile.stations() - 1); stationCount += profile.stations(); }
        segmentProfile = new int[count]; segmentStation = new int[count];
        Map<Long, List<Integer>> building = new HashMap<>();
        long total = 0;
        int next = 0;
        for (int p = 0; p < profiles.length; p++) {
            var profile = profiles[p];
            for (int i = 0; i + 1 < profile.stations(); i++, next++) {
                segmentProfile[next] = p; segmentStation[next] = i;
                double r = profile.radius();
                // note: bucket grid over grown boxes is the third copy of HighlandPlanIndex's and HighlandArms' bucket keys (HighlandArms stays byte-identical in 11-24, so no extraction).
                for (int z = bucket(Math.min(profile.z()[i], profile.z()[i + 1]) - r); z <= bucket(Math.max(profile.z()[i], profile.z()[i + 1]) + r); z++)
                    for (int x = bucket(Math.min(profile.x()[i], profile.x()[i + 1]) - r); x <= bucket(Math.max(profile.x()[i], profile.x()[i + 1]) + r); x++) {
                        building.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(next);
                        total++;
                    }
            }
        }
        entries = total; stations = stationCount;
        buckets = new HashMap<>(building.size() * 2);
        building.forEach((k, v) -> buckets.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    List<Profile> profiles() { return List.of(profiles); }

    /** Estimated retained size (bytes) of the station arrays, the segment list and the bucket grid. */
    long estimatedBytes() { return stations * 7 * 8L + segmentProfile.length * 8L + entries * 4 + buckets.size() * 96L + profiles.length * 200L; }

    /**
     * The profiles whose corridor contains a position, with the distance to the channel (nearest segment), and the along-channel
     * arclength, bed, natural bed and half width there. Where several segments of one profile are nearly equidistant (inside a bend) the
     * values are an excess-distance weighted blend, as in {@link HighlandPlanIndex#near}, so they stay continuous. Per thread and reused.
     */
    Hits query(double x, double z) {
        var hits = HITS.get();
        hits.count = 0;
        int[] candidates = buckets.get(key(bucket(x), bucket(z)));
        if (candidates == null) return hits;
        double[][] scratch = SCRATCH.get();
        // Candidates of one profile are contiguous: segments were bucketed in profile order.
        for (int from = 0; from < candidates.length; ) {
            int p = segmentProfile[candidates[from]], to = from;
            while (to < candidates.length && segmentProfile[candidates[to]] == p) to++;
            if (scratch[0].length < to - from) scratch = new double[][]{new double[2 * (to - from)], new double[2 * (to - from)]};
            var profile = profiles[p];
            double best = Double.POSITIVE_INFINITY;
            for (int c = from; c < to; c++) {
                int i = segmentStation[candidates[c]];
                double dx = profile.x()[i + 1] - profile.x()[i], dz = profile.z()[i + 1] - profile.z()[i], length2 = dx * dx + dz * dz;
                double t = length2 > 1e-12 ? clamp(((x - profile.x()[i]) * dx + (z - profile.z()[i]) * dz) / length2, 0, 1) : 0;
                double ex = profile.x()[i] + dx * t - x, ez = profile.z()[i] + dz * t - z, d = Math.sqrt(ex * ex + ez * ez);
                scratch[0][c - from] = d; scratch[1][c - from] = t;
                best = Math.min(best, d);
            }
            if (best <= profile.radius()) {
                double sigma = Math.max(1, .25 * best), weights = 0, along = 0, bed = 0, natural = 0, half = 0;
                for (int c = from; c < to; c++) {
                    double excess = scratch[0][c - from] - best;
                    if (excess >= 3 * sigma) continue;
                    int i = segmentStation[candidates[c]];
                    double t = scratch[1][c - from], w = Math.exp(-(excess / sigma) * (excess / sigma));
                    weights += w;
                    along += w * lerp(profile.s()[i], profile.s()[i + 1], t);
                    bed += w * lerp(profile.bed()[i], profile.bed()[i + 1], t);
                    natural += w * lerp(profile.natural()[i], profile.natural()[i + 1], t);
                    half += w * lerp(profile.halfWidth()[i], profile.halfWidth()[i + 1], t);
                }
                hits.add(profile, along / weights, best, bed / weights, natural / weights, half / weights);
            }
            from = to;
        }
        return hits;
    }

    private static int bucket(double x) { return (int) Math.floor(x / BUCKET); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
