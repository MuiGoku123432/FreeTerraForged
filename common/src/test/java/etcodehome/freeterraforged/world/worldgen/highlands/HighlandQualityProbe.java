package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ContinentType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings.RiverZone;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * Repeatable RH landscape quality measurements (PLAN-RH2 P0). Windows are chosen by a fixed rule
 * (a coarse land sample of the origin owner halfway between coast and core), never by attractiveness.
 */
final class HighlandQualityProbe {
    static final int WINDOW = 3072, RES = 4, SIZE = WINDOW / RES;

    record Metrics(long seed, int centerX, int centerZ,
                   long dryJumps, double worstJump, int worstX, int worstZ,
                   long seamPixels, double valleyZone, double wetFraction, long waterSteps, long perched,
                   long shoreCliffs, double flowCoverage, double flowUpstream,
                   double positiveWeirdness, double peakSlice, double peakOnFloor,
                   double beach, double lowLand, double oceanFloorMin, double gridAligned,
                   double[] weirdnessSlices, double[] erosionLevels, double wetland) {
        String summary() {
            return String.format(java.util.Locale.ROOT,
                "RHQ seed=%d center=%d,%d dryJumps>3=%d worstJump=%.1f@%d,%d seams=%d valleyZone=%.1f%% wet=%.1f%% "
                    + "waterSteps=%d perched=%d shoreCliffs=%d channelFlow=%.1f%% flowUpstream=%.1f%% weirdness+=%.1f%% "
                    + "peakSlice=%.1f%% peakOnFloor=%.1f%% beach=%.1f%% low<5=%.1f%% oceanFloorMin=%.1f gridAligned=%.1f%% slices(low/mid/high/peak)=%s erosionLevels=%s wetland=%.2f%%",
                seed, centerX, centerZ, dryJumps, worstJump, worstX, worstZ, seamPixels, 100 * valleyZone, 100 * wetFraction,
                waterSteps, perched, shoreCliffs, 100 * flowCoverage, 100 * flowUpstream, 100 * positiveWeirdness,
                100 * peakSlice, 100 * peakOnFloor, 100 * beach, 100 * lowLand, oceanFloorMin, 100 * gridAligned,
                percent(weirdnessSlices), percent(erosionLevels), 100 * wetland);
        }
        private static String percent(double[] values) {
            StringBuilder out = new StringBuilder();
            for (double v : values) out.append(out.length() == 0 ? "" : "/").append(String.format(java.util.Locale.ROOT, "%.0f", 100 * v));
            return out.toString();
        }
    }

    static Preset preset() {
        var preset = Presets.makeFTFDefault();
        preset.world().continent.continentType = ContinentType.ROLLING_HIGHLANDS;
        return preset;
    }

    private static double blocks(GeneratorContext context, float normalized) {
        return (normalized - context.levels.water) * context.levels.terrainScaleFactor;
    }

    static Metrics measure(long seed, boolean writeMap) throws Exception {
        var preset = preset();
        try (var context = WorldgenFixture.context(preset, seed)) {
            var continent = (RollingHighlandsContinent) context.heightmap.continent();
            var coast = continent.coast();
            float coastEdge = preset.world().controlPoints.coast;
            var bounds = coast.bounds(0, 0);
            Cell c = new Cell();

            // Owner-wide coarse statistics and the fixed window anchor.
            long land = 0, positive = 0, peak = 0, floor = 0, peakFloor = 0, beach = 0, low = 0;
            double[] slices = new double[4], erosion = new double[7];
            float[] erosionBounds = {-.78F, -.375F, -.2225F, .05F, .45F, .55F};
            float beachLevel = context.levels.water(5);
            double bestEdge = -1, oceanMin = 0; int cx = 0, cz = 0;
            var anchors = new java.util.ArrayList<double[]>();
            for (double x = bounds.minX(); x < bounds.maxX(); x += 32) for (double z = bounds.minZ(); z < bounds.maxZ(); z += 32) {
                var site = coast.sample(x, z);
                if (site.ownerX() != 0 || site.ownerZ() != 0) continue;
                context.heightmap.apply(c.reset(), (int) x, (int) z, true);
                double h = blocks(context, c.height);
                if (c.continentEdge <= coastEdge) { oceanMin = Math.min(oceanMin, h); continue; }
                if (((int) (x - bounds.minX()) & 255) < 32 && ((int) (z - bounds.minZ()) & 255) < 32) {
                    anchors.add(new double[]{x, z, c.continentEdge});
                    bestEdge = Math.max(bestEdge, c.continentEdge);
                }
                if (c.hasRiverWater) continue;
                land++;
                boolean onFloor = c.riverZone == RiverZone.ValleyFloor || c.riverZone == RiverZone.Banks;
                if (onFloor) floor++;
                if (c.weirdness > .05F) positive++;
                if (Math.abs(c.weirdness) >= .5667F) { peak++; if (onFloor) peakFloor++; }
                float magnitude = Math.abs(c.weirdness);
                slices[magnitude >= .5667F ? 3 : magnitude >= .4F ? 2 : magnitude >= .2667F ? 1 : 0]++;
                int level = 0; while (level < 6 && c.erosion >= erosionBounds[level]) level++;
                erosion[level]++;
                // Mirrors WorldLookup: coast cells at or below the beach level become beaches.
                if (c.terrain == TerrainType.BEACH || c.terrain == TerrainType.COAST && c.height <= beachLevel) beach++;
                if (h < 5) low++;
            }

            // Fixed anchor: the first coarse land sample halfway (by edge value) between coast and core,
            // where trunk rivers, tributaries and slopes all occur.
            double mid = (coastEdge + bestEdge) / 2, bestScore = Double.POSITIVE_INFINITY;
            for (double[] anchor : anchors) {
                double score = Math.abs(anchor[2] - mid);
                if (score < bestScore - 1e-9) { bestScore = score; cx = (int) anchor[0]; cz = (int) anchor[1]; }
            }
            // 4-block window around the anchor.
            int ox = cx - WINDOW / 2, oz = cz - WINDOW / 2;
            double[][] h = new double[SIZE][SIZE], w = new double[SIZE][SIZE];
            boolean[][] wet = new boolean[SIZE][SIZE];
            long dry = 0, valley = 0, wetCount = 0, flowing = 0, channelCells = 0, wetland = 0;
            BufferedImage image = writeMap ? new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB) : null;
            for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
                context.heightmap.apply(c.reset(), ox + x * RES, oz + z * RES, false);
                h[x][z] = blocks(context, c.height); w[x][z] = blocks(context, c.riverWaterLevel); wet[x][z] = c.hasRiverWater;
                if (c.terrain == TerrainType.WETLAND) wetland++;
                if (c.hasRiverWater) {
                    wetCount++;
                    var lake = continent.planAt(ox + x * RES, oz + z * RES).lake(ox + x * RES, oz + z * RES);
                    if ((lake == null || lake.depth() <= 0) && c.terrain != TerrainType.WETLAND) { channelCells++; if (c.hasFlow) flowing++; }
                }
                else if (c.continentEdge > coastEdge) { dry++; if (c.riverZone != RiverZone.None) valley++; }
                if (image != null) {
                    int v = (int) HighlandMath.clamp(40 + h[x][z] * .5, 0, 255);
                    int color = c.hasRiverWater ? 0x287dcc : h[x][z] < 0 ? 0x163a65 : c.terrain == TerrainType.BEACH ? 0xe8d890
                        : c.terrain == TerrainType.WETLAND ? 0x4f7a5a
                        : (v << 16) | ((int) HighlandMath.clamp(v + 35, 0, 255) << 8) | (v / 2);
                    image.setRGB(x, z, color);
                }
            }
            long seams = 0, steps = 0, perched = 0, shore = 0;
            for (int z = 1; z < SIZE - 1; z++) for (int x = 1; x < SIZE - 1; x++) {
                if (wet[x][z]) {
                    for (int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                        int nx = x + d[0], nz = z + d[1];
                        if (wet[nx][nz]) continue;
                        if (h[nx][nz] < w[x][z] - 1) { perched++; if (image != null) image.setRGB(x, z, 0xff00ff); break; }
                        if (h[nx][nz] - w[x][z] > 12) { shore++; if (image != null) image.setRGB(x, z, 0xffa000); break; }
                    }
                    if (wet[x + 1][z] && Math.abs(w[x + 1][z] - w[x][z]) >= 2) steps++;
                    if (wet[x][z + 1] && Math.abs(w[x][z + 1] - w[x][z]) >= 2) steps++;
                    continue;
                }
                if (h[x][z] <= 0) continue;
                if ((Math.abs(h[x + 1][z] - h[x][z]) > 12 && !wet[x + 1][z]) || (Math.abs(h[x][z + 1] - h[x][z]) > 12 && !wet[x][z + 1])) {
                    seams++; if (image != null) image.setRGB(x, z, 0xff2020);
                }
            }

            // 1-block transects through the window.
            long jumps = 0; double worst = 0; int wx = 0, wz = 0;
            for (int row = 0; row < 12; row++) {
                int z = oz + 128 + row * 240;
                double previous = Double.NaN; boolean previousWet = true;
                for (int x = ox; x < ox + WINDOW; x++) {
                    context.heightmap.apply(c.reset(), x, z, false);
                    double hh = blocks(context, c.height);
                    if (!previousWet && !c.hasRiverWater && hh > 0 && !Double.isNaN(previous)) {
                        double jump = Math.abs(hh - previous);
                        if (jump > 3) jumps++;
                        if (jump > worst) { worst = jump; wx = x; wz = z; }
                    }
                    previous = hh; previousWet = c.hasRiverWater;
                }
            }

            // Flow points downstream: compare the packed direction to the descending water gradient.
            long checked = 0, upstream = 0;
            Cell a = new Cell(), b = new Cell();
            for (int z = 2; z < SIZE - 2; z += 3) for (int x = 2; x < SIZE - 2; x += 3) {
                if (!wet[x][z]) continue;
                context.heightmap.apply(c.reset(), ox + x * RES, oz + z * RES, false);
                if (!c.hasFlow) continue;
                double angle = ((c.flowAngle & 0x1F) / 32.0) * Math.PI * 2;
                double fx = Math.cos(angle), fz = Math.sin(angle);
                context.heightmap.apply(a.reset(), (int) Math.round(ox + x * RES + fx * 6), (int) Math.round(oz + z * RES + fz * 6), false);
                context.heightmap.apply(b.reset(), (int) Math.round(ox + x * RES - fx * 6), (int) Math.round(oz + z * RES - fz * 6), false);
                if (!a.hasRiverWater || !b.hasRiverWater) continue;
                double ahead = blocks(context, a.riverWaterLevel), behind = blocks(context, b.riverWaterLevel);
                if (Math.abs(ahead - behind) < .05) continue;
                checked++;
                if (ahead > behind) upstream++;
            }

            // Share of refined channel length running exactly along grid directions (0/45/90/135 deg +-1 deg).
            // Natural channels give about 4%; grid-routed straight reaches push this far higher.
            var plan = continent.planAt(cx, cz).plan();
            double aligned = 0, total = 0;
            for (var node : plan.nodes()) {
                if (node.parent() < 0) continue;
                var parent = plan.nodes().get(node.parent());
                double dx = node.x() - parent.x(), dz = node.z() - parent.z(), length = Math.hypot(dx, dz);
                if (length < 1e-6) continue;
                double degrees = (Math.toDegrees(Math.atan2(dz, dx)) % 45 + 45) % 45;
                if (degrees <= 1 || degrees >= 44) aligned += length;
                total += length;
            }

            if (image != null) {
                Path out = Path.of("build/rolling-highlands/quality-" + seed + ".png");
                Files.createDirectories(out.getParent());
                ImageIO.write(image, "png", out.toFile());
            }
            return new Metrics(seed, cx, cz, jumps, worst, wx, wz, seams, ratio(valley, dry), ratio(wetCount, wetCount + dry),
                steps, perched, shore, channelCells == 0 ? 1 : ratio(flowing, channelCells), ratio(upstream, checked),
                ratio(positive, land), ratio(peak, land), ratio(peakFloor, floor), ratio(beach, land), ratio(low, land),
                oceanMin, total > 0 ? aligned / total : 0, scale(slices, land), scale(erosion, land), ratio(wetland, wetCount + dry));
        }
    }

    private static double[] scale(double[] counts, long total) {
        double[] out = counts.clone();
        for (int i = 0; i < out.length; i++) out[i] = total == 0 ? 0 : out[i] / total;
        return out;
    }
    private static double ratio(long a, long b) { return b == 0 ? 0 : (double) a / b; }
}
