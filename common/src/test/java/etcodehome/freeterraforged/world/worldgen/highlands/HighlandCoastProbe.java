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
 * <li>sinuosity: Cauchy-Crofton coastline length on a 16-block grid over the length on a 512-block grid;</li>
 * <li>stacks: transects with a column ≥ 5 above water 10-60 blocks offshore, per 10 km of cliff coast.</li>
 * </ul>
 */
final class HighlandCoastProbe {
    static final int ROW = 32, MAX_BEACH = 96, INLAND = 96, OFFSHORE = 60;

    record Sample(int x, int z, String type, int beachWidth, double h4, double h8, double h32, double depth8, boolean stack) {}

    record Report(int samples, double beachShare, double bluffShare, double cliffShare, double beachWidthMedian,
                  double beachWidthP10, double sinuosity, double cliffHeight4Median, double cliffDepth8Median,
                  double stacksPer10km, double nanosPerColumn, List<Sample> list) {
        String summary(String label) {
            return String.format(Locale.ROOT,
                "RHC %s n=%d beach=%.0f%% bluff=%.0f%% cliff=%.0f%% beachWidth median=%.1f p10=%.1f sinuosity=%.3f "
                    + "cliffH4=%.1f cliffDepth8=%.1f stacks/10km=%.1f ns/column=%.0f",
                label, samples, 100 * beachShare, 100 * bluffShare, 100 * cliffShare, beachWidthMedian, beachWidthP10,
                sinuosity, cliffHeight4Median, cliffDepth8Median, stacksPer10km, nanosPerColumn);
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
        long columns = 0, nanos = 0;
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
                for (int d = -OFFSHORE; d <= INLAND; d++) {
                    context.lookup.applyCell(cell.reset(), (int) Math.round(x + gx * d), (int) Math.round(z + gz * d), false, true);
                    height[d + OFFSHORE] = (cell.height - levels.water) * levels.terrainScaleFactor;
                    if (d >= 0) terrain[d] = cell.terrain;
                }
                nanos += System.nanoTime() - start;
                columns += height.length;
                int width = 0;
                int d0 = 0;
                while (d0 < 4 && height[d0 + OFFSHORE] <= 0) d0++;
                for (int d = d0; d <= Math.min(INLAND, MAX_BEACH) && terrain[d] == TerrainType.BEACH; d++) width++;
                double h4 = height[OFFSHORE + 4], h8 = height[OFFSHORE + 8], h32 = height[OFFSHORE + 32];
                double depth8 = -height[OFFSHORE - 8];
                boolean stack = false;
                for (int d = 10; d <= OFFSHORE; d++) if (height[OFFSHORE - d] >= 5) { stack = true; break; }
                String type = h4 >= 15 ? "cliff" : h4 >= 6 ? "bluff" : "beach";
                samples.add(new Sample(x, z, type, width, h4, h8, h32, depth8, stack));
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
        double sinuosity = length(coast, coastEdge, originX, originZ, radius, 16) / length(coast, coastEdge, originX, originZ, radius, 512);
        return new Report(n, (double) beaches / n, (double) bluffs / n, (double) cliffs.size() / n, quantile(widths, .5),
            quantile(widths, .1), sinuosity, quantile(cliffH, .5), quantile(cliffD, .5), cliffKm > 0 ? 10 * stacks / cliffKm : 0,
            columns > 0 ? (double) nanos / columns : 0, samples);
    }

    /** Cauchy-Crofton coastline length: π/4 · step · crossings of grid lines in both directions. */
    static double length(HighlandCoast coast, double coastEdge, int originX, int originZ, int radius, int step) {
        long crossings = 0;
        for (int a = originZ - radius; a <= originZ + radius; a += step) {
            boolean row = coast.sample(originX - radius, a).edge() > coastEdge, column = coast.sample(a - originZ + originX, originZ - radius).edge() > coastEdge;
            for (int b = -radius + step; b <= radius; b += step) {
                boolean r = coast.sample(originX + b, a).edge() > coastEdge;
                boolean c = coast.sample(a - originZ + originX, originZ + b).edge() > coastEdge;
                if (r != row) crossings++;
                if (c != column) crossings++;
                row = r; column = c;
            }
        }
        return Math.PI / 4 * step * crossings;
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
