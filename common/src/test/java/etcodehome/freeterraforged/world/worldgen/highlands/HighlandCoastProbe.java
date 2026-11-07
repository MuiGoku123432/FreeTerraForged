package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * RH9 P0: sea-coast metrics. Coast samples are the land/sea crossings of the coast edge field on rows every
 * {@link #ROW} blocks; at each, a transect runs inland and offshore along the edge field's gradient.
 *
 * <ul>
 * <li>type: by geometry (height 4 blocks inland ≥ 15 cliff, ≥ 6 bluff, else beach) until the coast profile exists;</li>
 * <li>beach width: BEACH columns from the waterline inland (first {@link #MAX_BEACH} blocks);</li>
 * <li>sinuosity and stretches: along coastlines traced on an 8-block grid (path / chord over 2 km; runs of one type);</li>
 * <li>stacks: transects with a sea column (edge at or below the coast point) ≥ 5 above water 10-60 blocks offshore,
 * per 10 km of cliff coast (land of a neighbouring headland does not count).</li>
 * </ul>
 *
 * <p>RH10: the RH9 gates describe the RH9 coast, so samples whose {@code coast.sample(x, z).drowned()} is at least
 * {@link #DROWNED_SKIP} (inside a drowned region, where the coastline is a flooded valley shore) are left out of the
 * type shares, beach widths, cliff height/depth, stacks, hollow share and the stretch runs (runs are split at drowned
 * points). {@code sunk} still counts every transect (no sea pit anywhere) and the cost covers every transect.
 */
final class HighlandCoastProbe {
    /** RH10: drowned-region weight from which a coast sample no longer counts as RH9 coast. */
    static final double DROWNED_SKIP = .1;
    static final int ROW = 32, MAX_BEACH = 96, INLAND = 96, OFFSHORE = 60, WINDOW = 2000, TRACE = 8, RESAMPLE = 8, HOLLOW_REACH = 48;

    record Sample(int x, int z, String type, String geometry, int beachWidth, double h4, double h8, double h32, double depth8, boolean stack, double slope, boolean hollow, int sunk) {}

    record Report(int samples, double beachShare, double bluffShare, double cliffShare, double beachWidthMedian,
                  double beachWidthP10, double sinuosity, double cliffHeight4Median, double cliffDepth8Median,
                  double stacksPer10km, double nanosPerColumn, double edgeSlope, double stretchMedian, double hollowShare, long sunk, List<Sample> list) {
        String summary(String label) {
            return String.format(Locale.ROOT,
                "RHC %s n=%d beach=%.0f%% bluff=%.0f%% cliff=%.0f%% beachWidth median=%.1f p10=%.1f sinuosity=%.3f "
                    + "cliffH4=%.1f cliffDepth8=%.1f stacks/10km=%.1f ns/column=%.0f edgeSlope=%.2e stretch=%.0f hollow=%.1f%% sunk=%d",
                label, samples, 100 * beachShare, 100 * bluffShare, 100 * cliffShare, beachWidthMedian, beachWidthP10,
                sinuosity, cliffHeight4Median, cliffDepth8Median, stacksPer10km, nanosPerColumn, edgeSlope, stretchMedian, 100 * hollowShare, sunk);
        }
    }

    private HighlandCoastProbe() {}

    static Report measure(GeneratorContext context, int originX, int originZ, int radius) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var levels = context.levels;
        var samples = new ArrayList<Sample>();
        Cell cell = new Cell();
        long columns = 0, nanos = 0, sunkInDrowned = 0;
        for (int z = originZ - radius; z <= originZ + radius; z += ROW) {
            boolean previous = coast.sample(originX - radius, z).edge() > coastEdge;
            for (int x = originX - radius + 1; x <= originX + radius; x++) {
                boolean land = coast.sample(x, z).edge() > coastEdge;
                if (land == previous) continue;
                previous = land;
                // Inland direction: up the edge field.
                double e = coast.sample(x, z).edge();
                double gx = coast.sample(x + 2, z).edge() - e, gz = coast.sample(x, z + 2).edge() - e;
                double norm = Math.hypot(gx, gz);
                if (norm == 0) continue;
                gx /= norm; gz /= norm;
                long start = System.nanoTime();
                double[] height = new double[INLAND + OFFSHORE + 1];
                var terrain = new etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain[INLAND + 1];
                boolean wet = false;
                boolean[] sea = new boolean[OFFSHORE + 1];
                int sunk = 0;
                for (int d = -OFFSHORE; d <= INLAND; d++) {
                    context.lookup.applyCell(cell.reset(), (int) Math.round(x + gx * d), (int) Math.round(z + gz * d), false, true);
                    height[d + OFFSHORE] = (cell.height - levels.water) * levels.terrainScaleFactor;
                    if (d >= 0) terrain[d] = cell.terrain;
                    else sea[-d] = cell.continentEdge <= coastEdge;
                    if (d >= 0) wet |= cell.hasRiverWater;
                    // Dry land below sea level beside the coast (it would flood or show as a pit).
                    if (d >= 1 && !cell.hasRiverWater && cell.continentEdge > coastEdge && height[d + OFFSHORE] < -.5) sunk++;
                }
                nanos += System.nanoTime() - start;
                columns += height.length;
                if (coast.sample(x, z).drowned() >= DROWNED_SKIP) { sunkInDrowned += sunk; continue; }
                int width = 0;
                int d0 = 0;
                while (d0 < 4 && height[d0 + OFFSHORE] <= 0) d0++;
                for (int d = d0; d <= Math.min(INLAND, MAX_BEACH) && terrain[d] == TerrainType.BEACH; d++) width++;
                double h4 = height[OFFSHORE + 4], h8 = height[OFFSHORE + 8], h32 = height[OFFSHORE + 32];
                double depth8 = -height[OFFSHORE - 8];
                boolean stack = false;
                for (int d = 10; d <= OFFSHORE; d++) if (sea[d] && height[OFFSHORE - d] >= 5) { stack = true; break; }
                String geometry = h4 >= 15 ? "cliff" : h4 >= 6 ? "bluff" : "beach";
                String type = coast.profile().shore(x, z).dominant().name().toLowerCase(Locale.ROOT);
                // Hollow behind a scarp: the coast profile's ground (before the plan's valleys, which always drain)
                // within HOLLOW_REACH falling more than 2 blocks below the highest ground seaward of it (transects with
                // river or lake water are left out).
                boolean hollow = false;
                if (!type.equals("beach") && !wet) {
                    double top = Double.NEGATIVE_INFINITY;
                    for (int d = 1; d <= HOLLOW_REACH; d++) {
                        double ground = continent.uncarvedLand(Math.round(x + gx * d), Math.round(z + gz * d));
                        top = Math.max(top, ground);
                        if (ground < top - 2) { hollow = true; break; }
                    }
                }
                samples.add(new Sample(x, z, type, geometry, width, h4, h8, h32, depth8, stack, norm / 2, hollow, sunk));
            }
        }
        int n = samples.size();
        long beaches = samples.stream().filter(s -> s.type.equals("beach")).count();
        long bluffs = samples.stream().filter(s -> s.type.equals("bluff")).count();
        var cliffs = samples.stream().filter(s -> s.type.equals("cliff")).toList();
        double[] widths = samples.stream().filter(s -> s.type.equals("beach")).mapToDouble(Sample::beachWidth).sorted().toArray();
        double[] cliffH = cliffs.stream().mapToDouble(Sample::h4).sorted().toArray();
        double[] cliffD = cliffs.stream().mapToDouble(Sample::depth8).sorted().toArray();
        // Each row sample stands for about ROW / |cos| blocks of coast; take ROW as a lower bound.
        double cliffKm = cliffs.size() * ROW / 1000.0;
        long stacks = cliffs.stream().filter(Sample::stack).count();
        var lines = coastlines(coast, coastEdge, originX, originZ, radius);
        double sinuosity = sinuosity(lines);
        return new Report(n, (double) beaches / n, (double) bluffs / n, (double) cliffs.size() / n, quantile(widths, .5),
            quantile(widths, .1), sinuosity, quantile(cliffH, .5), quantile(cliffD, .5), cliffKm > 0 ? 10 * stacks / cliffKm : 0,
            columns > 0 ? (double) nanos / columns : 0, quantile(samples.stream().mapToDouble(Sample::slope).sorted().toArray(), .5), stretchMedian(coast, lines),
            (double) samples.stream().filter(Sample::hollow).count() / Math.max(1, samples.stream().filter(t -> !t.type().equals("beach")).count()),
            samples.stream().mapToLong(Sample::sunk).sum() + sunkInDrowned, samples);
    }

    /**
     * Coastlines traced as ordered polylines: the cracks between land and sea pixels of a TRACE-block grid, followed
     * with land on the left, then resampled every RESAMPLE cracks so the pixel staircase does not count as length.
     */
    static List<List<double[]>> coastlines(HighlandCoast coast, double coastEdge, int originX, int originZ, int radius) {
        int n = 2 * radius / TRACE + 1;
        boolean[][] land = new boolean[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
            land[i][j] = coast.sample(originX - radius + i * TRACE, originZ - radius + j * TRACE).edge() > coastEdge;
        // Directed cracks keyed by start corner (corner (a, b) sits at pixel coordinates (a - .5, b - .5)).
        var next = new java.util.HashMap<Long, List<long[]>>();
        var incoming = new java.util.HashSet<Long>();
        int[][] side = {{0, -1, 0, 0, 1, 0}, {1, 0, 1, 0, 1, 1}, {0, 1, 1, 1, 0, 1}, {-1, 0, 0, 1, 0, 0}};
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (!land[i][j]) continue;
            for (int[] d : side) {
                int ni = i + d[0], nj = j + d[1];
                if (ni < 0 || nj < 0 || ni >= n || nj >= n || land[ni][nj]) continue;
                long from = key(i + d[2], j + d[3]), to = key(i + d[4], j + d[5]);
                next.computeIfAbsent(from, k -> new ArrayList<>()).add(new long[]{from, to});
                incoming.add(to);
            }
        }
        var lines = new ArrayList<List<double[]>>();
        for (int pass = 0; pass < 2; pass++) {
            for (long start : new ArrayList<>(next.keySet())) {
                if (pass == 0 && incoming.contains(start)) continue;
                while (next.containsKey(start)) {
                    var cracks = new ArrayList<double[]>();
                    long at = start;
                    while (true) {
                        var out = next.get(at);
                        if (out == null) break;
                        long[] edge = out.remove(out.size() - 1);
                        if (out.isEmpty()) next.remove(at);
                        double a = ((edge[0] >> 32) + (edge[1] >> 32)) * .5 - .5, b = ((int) edge[0] + (int) edge[1]) * .5 - .5;
                        cracks.add(new double[]{originX - radius + a * TRACE, originZ - radius + b * TRACE});
                        at = edge[1];
                    }
                    var line = new ArrayList<double[]>();
                    for (int c = 0; c < cracks.size(); c += RESAMPLE) line.add(cracks.get(c));
                    if (line.size() >= 2) lines.add(line);
                }
            }
        }
        return lines;
    }

    private static long key(int a, int b) { return ((long) a << 32) | (b & 0xffffffffL); }

    private static double[] distances(List<double[]> line) {
        double[] at = new double[line.size()];
        for (int i = 1; i < line.size(); i++) at[i] = at[i - 1] + Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
        return at;
    }

    /**
     * Median length (blocks) of runs of one dominant coast type along the traced coastlines; a run ends at a drowned
     * point (DROWNED_SKIP) and the next one starts after it.
     */
    static double stretchMedian(HighlandCoast coast, List<List<double[]>> lines) {
        var profile = coast.profile();
        var runs = new ArrayList<Double>();
        for (var line : lines) {
            double[] at = distances(line);
            HighlandCoastProfile.Type previous = null;
            double start = 0;
            for (int i = 0; i < line.size(); i++) {
                double x = line.get(i)[0], z = line.get(i)[1];
                if (coast.sample(x, z).drowned() >= DROWNED_SKIP) {
                    if (previous != null) runs.add(at[i - 1] - start);
                    previous = null;
                    continue;
                }
                var type = profile.shore(x, z).dominant();
                if (previous == null) { previous = type; start = at[i]; continue; }
                if (type == previous) continue;
                double change = (at[i] + at[i - 1]) * .5;
                runs.add(change - start);
                start = change; previous = type;
            }
            if (previous != null) runs.add(at[at.length - 1] - start);
        }
        return quantile(runs.stream().mapToDouble(Double::doubleValue).sorted().toArray(), .5);
    }

    /** Mean path length / chord over WINDOW blocks of path along the traced coastlines. */
    static double sinuosity(List<List<double[]>> lines) {
        double sum = 0; int count = 0;
        for (var line : lines) {
            double[] at = distances(line);
            for (int i = 0, j = 0; i < line.size(); i += 4) {
                while (j < line.size() && at[j] - at[i] < WINDOW) j++;
                if (j >= line.size()) break;
                sum += (at[j] - at[i]) / Math.hypot(line.get(j)[0] - line.get(i)[0], line.get(j)[1] - line.get(i)[1]);
                count++;
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
    }

    public static void main(String[] args) throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            System.out.println(measure(context, 10109, 18236, 4096).summary("user"));
        }
    }
}
