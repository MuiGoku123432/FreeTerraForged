package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * Ground honesty metrics (PLAN-RH6 G0), measured on the user's world (the fixture reproduces the server world at the
 * same coordinates):
 * <ul>
 * <li>lifted: share of dry land the floor raises above the unfloored land (legacy land minus incision);</li>
 * <li>elevated: share of channel columns with dry land well below their water within 48 blocks (perched, canal
 * streams);</li>
 * <li>dead-flat: share of dry columns whose 9×9 neighbourhood at 4-block spacing lies on one block level, at the
 * screenshot sites;</li>
 * <li>pillow: mean dry slope at plan lattice nodes over the mean slope at quad centres, away from water (a smoothstep
 * interpolant of node values is flat at every node, so a grid imprint reads below 1).</li>
 * </ul>
 */
final class HighlandGroundProbe {
    /** Window around the screenshot sites of the RH5 feedback (2026-10-29). */
    static final int CENTER_X = 4800, CENTER_Z = 10000, WINDOW = 2048, RES = 4, SIZE = WINDOW / RES;
    static final int[][] SITES = {{4689, 9704}, {4819, 9817}, {4870, 10210}, {4675, 9884}, {4924, 10456}};
    static final int SITE_WINDOW = 176, ELEVATED_REACH = 48, CONTINENT_STEP = 64;

    record Metrics(double lifted2, double lifted8, double liftedFar, long drySamples, double elevated2, double elevated5,
                   double beside2, double beside5, long streamColumns, double[] deadFlat, double pillow, double buildMs) {
        double deadFlatMax() { double m = 0; for (double v : deadFlat) m = Math.max(m, v); return m; }
        String summary() {
            StringBuilder flats = new StringBuilder();
            for (double v : deadFlat) flats.append(flats.length() == 0 ? "" : "/").append(String.format(Locale.ROOT, "%.0f", 100 * v));
            return String.format(Locale.ROOT,
                "RHG lifted>2=%.1f%% lifted>8=%.1f%% far(>200)lifted>2=%.1f%% (n=%d) elevatedAny>2=%.1f%% >5=%.2f%% elevatedBeside>2=%.1f%% >5=%.2f%% (streams=%d) "
                    + "deadFlat=%s%% pillow=%.2f build=%.0fms",
                100 * lifted2, 100 * lifted8, 100 * liftedFar, drySamples, 100 * elevated2, 100 * elevated5, 100 * beside2, 100 * beside5, streamColumns,
                flats, pillow, buildMs);
        }
    }

    private static double blocks(GeneratorContext context, float normalized) {
        return (normalized - context.levels.water) * context.levels.terrainScaleFactor;
    }

    static Metrics measure(GeneratorContext context, Path image) throws Exception {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        float coastEdge = context.preset.world().controlPoints.coast;
        Cell c = new Cell();

        // Lifted land over the whole user continent.
        var owner = coast.sample(HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
        var index = continent.planOf(owner.ownerX(), owner.ownerZ());
        var bounds = coast.bounds(owner.ownerX(), owner.ownerZ());
        long dry = 0, lifted2 = 0, lifted8 = 0, far = 0, farLifted = 0;
        for (double x = bounds.minX(); x < bounds.maxX(); x += CONTINENT_STEP) for (double z = bounds.minZ(); z < bounds.maxZ(); z += CONTINENT_STEP) {
            var site = coast.sample(x, z);
            if (site.ownerX() != owner.ownerX() || site.ownerZ() != owner.ownerZ() || site.edge() <= coastEdge) continue;
            context.heightmap.apply(c.reset(), (int) x, (int) z, false);
            if (c.hasRiverWater) continue;
            double lift = blocks(context, c.height) - (continent.uncarvedLand(x, z) - index.incision(x, z));
            dry++;
            if (lift > 2) lifted2++;
            if (lift > 8) lifted8++;
            var hits = index.near(x, z);
            double nearest = Double.POSITIVE_INFINITY;
            for (int k = 0; k < hits.count; k++) nearest = Math.min(nearest, hits.distance[k]);
            if (nearest > 200) { far++; if (lift > 2) farLifted++; }
        }

        // Window at the screenshot sites.
        int ox = CENTER_X - WINDOW / 2, oz = CENTER_Z - WINDOW / 2;
        double[][] h = new double[SIZE][SIZE], w = new double[SIZE][SIZE];
        boolean[][] wet = new boolean[SIZE][SIZE], stream = new boolean[SIZE][SIZE], land = new boolean[SIZE][SIZE];
        BufferedImage map = image != null ? new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB) : null;
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int bx = ox + x * RES, bz = oz + z * RES;
            context.heightmap.apply(c.reset(), bx, bz, false);
            h[x][z] = blocks(context, c.height); w[x][z] = blocks(context, c.riverWaterLevel);
            wet[x][z] = c.hasRiverWater;
            land[x][z] = c.continentEdge > coastEdge;
            if (c.hasRiverWater) {
                var lake = continent.planAt(bx, bz).lake(bx, bz);
                boolean inLake = lake != null && lake.depth() > 0;
                stream[x][z] = c.hasFlow && !inLake && c.terrain != TerrainType.WETLAND;
            }
        }

        // Elevated streams. Any: dry land more than 2 / 5 blocks below the water anywhere within ELEVATED_REACH (counts
        // steep reaches, whose banks a little downstream lie below the water upstream). Beside: the same, but each dry
        // column is compared with its nearest stream column only (land beside the stream lower than the stream).
        int r = ELEVATED_REACH / RES;
        long streams = 0, elevated2 = 0, elevated5 = 0, gentle = 0, gentle2 = 0;
        for (int z = r; z < SIZE - r; z++) for (int x = r; x < SIZE - r; x++) {
            if (!stream[x][z]) continue;
            streams++;
            double lowest = Double.POSITIVE_INFINITY;
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue;
                int nx = x + dx, nz = z + dz;
                if (wet[nx][nz] || !land[nx][nz]) continue;
                lowest = Math.min(lowest, h[nx][nz]);
            }
            if (lowest < w[x][z] - 2) elevated2++;
            if (lowest < w[x][z] - 5) elevated5++;
            // Diagnostics: the same on gentle reaches only (water gradient under 2% over ±8 blocks).
            double gx = (w[x + 2][z] - w[x - 2][z]) / 16.0, gz = (w[x][z + 2] - w[x][z - 2]) / 16.0;
            if (stream[x + 2][z] && stream[x - 2][z] && stream[x][z + 2] && stream[x][z - 2] && Math.hypot(gx, gz) < .02) {
                gentle++;
                if (lowest < w[x][z] - 2) gentle2++;
            }
        }
        int[] source = new int[SIZE * SIZE];
        java.util.Arrays.fill(source, -1);
        var frontier = new java.util.ArrayDeque<Integer>();
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) if (stream[x][z]) { source[z * SIZE + x] = z * SIZE + x; frontier.add(z * SIZE + x); }
        while (!frontier.isEmpty()) {
            int v = frontier.poll(), vx = v % SIZE, vz = v / SIZE, s = source[v];
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int nx = vx + dx, nz = vz + dz;
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) continue;
                int j = nz * SIZE + nx;
                if (source[j] >= 0) {
                    // Keep the nearer source (approximate Voronoi assignment).
                    int t = source[j];
                    if (dist2(j, s) < dist2(j, t) - 1e-9 && !stream[nx][nz]) source[j] = s;
                    continue;
                }
                if (dist2(j, s) > r * r) continue;
                source[j] = s; frontier.add(j);
            }
        }
        boolean[] beside2 = new boolean[SIZE * SIZE], beside5 = new boolean[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int i = z * SIZE + x, s = source[i];
            if (s < 0 || wet[x][z] || !land[x][z]) continue;
            double water = w[s % SIZE][s / SIZE];
            if (h[x][z] < water - 2) beside2[s] = true;
            if (h[x][z] < water - 5) beside5[s] = true;
        }
        long besideCount2 = 0, besideCount5 = 0;
        for (int z = r; z < SIZE - r; z++) for (int x = r; x < SIZE - r; x++) {
            if (!stream[x][z]) continue;
            if (beside2[z * SIZE + x]) besideCount2++;
            if (beside5[z * SIZE + x]) besideCount5++;
        }
        System.out.printf(Locale.ROOT, "RHG gentle reaches: %d columns, elevatedAny>2 %.1f%%%n", gentle, 100.0 * gentle2 / Math.max(1, gentle));

        // Dead-flat ground at the screenshot sites.
        double[] flats = new double[SITES.length];
        int half = SITE_WINDOW / 2 / RES;
        for (int s = 0; s < SITES.length; s++) {
            int cx = (SITES[s][0] - ox) / RES, cz = (SITES[s][1] - oz) / RES;
            long checked = 0, flat = 0;
            for (int z = cz - half; z < cz + half; z++) for (int x = cx - half; x < cx + half; x++) {
                if (wet[x][z] || !land[x][z]) continue;
                // The dry land samples of the neighbourhood (at least half of it) all on the column's block level.
                boolean level = true;
                int base = (int) Math.floor(h[x][z]), dryCount = 0;
                for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++) {
                    if (wet[x + dx][z + dz] || !land[x + dx][z + dz]) continue;
                    dryCount++;
                    if ((int) Math.floor(h[x + dx][z + dz]) != base) level = false;
                }
                if (dryCount < 41) continue;
                checked++;
                if (level) flat++;
            }
            flats[s] = checked == 0 ? 0 : (double) flat / checked;
        }

        // Grid pillows: slope near lattice nodes vs near quad centres, at least 64 blocks from water.
        int[][] wetSum = new int[SIZE + 1][SIZE + 1];
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++)
            wetSum[x + 1][z + 1] = (wet[x][z] ? 1 : 0) + wetSum[x][z + 1] + wetSum[x + 1][z] - wetSum[x][z];
        var plan = continent.planAt(CENTER_X, CENTER_Z).plan();
        int step = plan.gridStep(), dryReach = 64 / RES;
        double nodeSlope = 0, centreSlope = 0; long nodes = 0, centres = 0;
        for (int z = dryReach + 1; z < SIZE - dryReach - 1; z++) for (int x = dryReach + 1; x < SIZE - dryReach - 1; x++) {
            int mx = Math.floorMod(ox + x * RES - plan.originX(), step), mz = Math.floorMod(oz + z * RES - plan.originZ(), step);
            boolean node = near(mx, 0, step) && near(mz, 0, step), centre = near(mx, step / 2, step) && near(mz, step / 2, step);
            if (!node && !centre || !land[x][z]) continue;
            int x0 = x - dryReach, x1 = x + dryReach + 1, z0 = z - dryReach, z1 = z + dryReach + 1;
            if (wetSum[x1][z1] - wetSum[x0][z1] - wetSum[x1][z0] + wetSum[x0][z0] > 0) continue;
            double slope = Math.hypot(h[x + 1][z] - h[x - 1][z], h[x][z + 1] - h[x][z - 1]) / (2.0 * RES);
            if (node) { nodeSlope += slope; nodes++; } else { centreSlope += slope; centres++; }
        }
        double pillow = nodes == 0 || centres == 0 || centreSlope == 0 ? Double.NaN : (nodeSlope / nodes) / (centreSlope / centres);

        if (map != null) {
            for (int z = 1; z < SIZE - 1; z++) for (int x = 1; x < SIZE - 1; x++) {
                double gx = (h[x + 1][z] - h[x - 1][z]) / (2.0 * RES), gz = (h[x][z + 1] - h[x][z - 1]) / (2.0 * RES);
                double shade = Math.max(0, Math.min(1, .55 + (-gx - gz) * 1.1));
                int rgb;
                if (!land[x][z]) rgb = 0x163a65;
                else if (wet[x][z]) rgb = stream[x][z] ? 0x2a7fd4 : 0x1f5fb0;
                else {
                    double e = Math.max(0, Math.min(1, h[x][z] / 300));
                    int rr = (int) ((90 + 140 * e) * shade), gg = (int) ((130 + 70 * e) * shade), bb = (int) ((70 + 110 * e) * shade);
                    rgb = (Math.min(255, rr) << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb);
                }
                map.setRGB(x, z, rgb);
            }
            Files.createDirectories(image.getParent());
            ImageIO.write(map, "png", image.toFile());
        }
        return new Metrics(ratio(lifted2, dry), ratio(lifted8, dry), ratio(farLifted, far), dry, ratio(elevated2, streams), ratio(elevated5, streams),
            ratio(besideCount2, streams), ratio(besideCount5, streams), streams, flats, pillow, index.plan().buildNanos() / 1e6);
    }

    private static double dist2(int a, int b) {
        double dx = a % SIZE - b % SIZE, dz = a / SIZE - b / SIZE;
        return dx * dx + dz * dz;
    }

    /** Within 8 blocks of {@code target} on a lattice of period {@code step}. */
    private static boolean near(int offset, int target, int step) {
        int d = Math.abs(offset - target);
        return Math.min(d, step - d) <= 8;
    }

    private static double ratio(long a, long b) { return b == 0 ? 0 : (double) a / b; }
}
