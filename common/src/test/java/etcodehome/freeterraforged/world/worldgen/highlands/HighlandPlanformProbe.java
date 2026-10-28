package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import com.mojang.serialization.JsonOps;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * River planform metrics (PLAN-RH4 R0), measured on one continent's drainage plan and on rendered cells:
 * sinuosity, routing direction bias, junction angles, confluence tongue width, channel crossings and plan cost.
 * Also renders hillshade maps of any preset/seed/area for visual review.
 */
final class HighlandPlanformProbe {
    /** The user's in-game preset (Test-America-v3, continent scale 5000, sea level 62) and world seed. */
    static final long USER_SEED = -6240223350076425471L;
    static final int USER_X = 4551, USER_Z = 9377;

    record Metrics(String label, int gridStep, int gridNodes, int landNodes, int refinedNodes, double buildMs,
                   double sinuosity, double straightShare, double meanderShare, int sinuosityWindows,
                   double directionBias, double knightBias, double anisotropy, int directionWindows, double junctionAngle, double acuteShare, int junctions,
                   double tongueMedian, double narrowTongueShare, int tongues, long crossings, int oxbows, long outsideBelt) {
        String summary() {
            return String.format(Locale.ROOT,
                "RHP %s grid=%d nodes=%d land=%d refined=%d build=%.0fms sinuosity=%.3f straight<1.1=%.0f%% meander>1.5=%.0f%% (n=%d) "
                    + "directionBias=%.1f%% knightBias=%.1f%% anisotropy=%.1f%% (n=%d, uniform 22.2%%) junction=%.1fdeg acute<25=%.0f%% (n=%d) tipWidth=%.1f sharp<5=%.0f%% (n=%d) crossings=%d oxbows=%d outsideBelt=%d",
                label, gridStep, gridNodes, landNodes, refinedNodes, buildMs, sinuosity, 100 * straightShare, 100 * meanderShare,
                sinuosityWindows, 100 * directionBias, 100 * knightBias, 100 * anisotropy, directionWindows, junctionAngle, 100 * acuteShare, junctions, tongueMedian,
                100 * narrowTongueShare, tongues, crossings, oxbows, outsideBelt);
        }
    }

    static Preset userPreset() throws Exception {
        try (var in = HighlandPlanformProbe.class.getResourceAsStream("/rolling-highlands/test-america-v3.json")) {
            var json = com.google.gson.JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
            return Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    /** Origin continent (owner 0, 0) of a preset and seed, as in the realism probe. */
    static Metrics measureOrigin(String label, Preset preset, long seed) {
        try (var context = WorldgenFixture.context(preset, seed)) {
            return measure(label, context, ((RollingHighlandsContinent) context.heightmap.continent()).planOf(0, 0));
        }
    }

    /** The continent that owns block (px, pz). */
    static Metrics measure(String label, GeneratorContext context, int px, int pz) {
        return measure(label, context, ((RollingHighlandsContinent) context.heightmap.continent()).planAt(px, pz));
    }

    static Metrics measure(String label, GeneratorContext context, HighlandPlanIndex index) {
        var plan = index.plan();
        var nodes = plan.nodes();
        int n = nodes.size();
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < n; i++) children.add(new ArrayList<>());
        for (int i = 0; i < n; i++) if (nodes.get(i).parent() >= 0) children.get(nodes.get(i).parent()).add(i);
        boolean[] lake = new boolean[n];
        for (int i = 0; i < n; i++) {
            var s = plan.basins().sample(nodes.get(i).x(), nodes.get(i).z());
            lake[i] = s != null && s.depth() > 0;
        }

        // Sinuosity: 1 km windows walked downstream from channel samples on rivers > 4 km2, away from lakes.
        double sinSum = 0; int windows = 0, straight = 0, meander = 0;
        // Direction bias: 512-block chords on rivers > 1 km2.
        int biased = 0, knight = 0, chords = 0;
        double[] sector = new double[9];
        for (int i = 0; i < n; i++) {
            var start = nodes.get(i);
            if (start.parent() < 0 || lake[i]) continue;
            if (start.area() > 4e6 && i % 5 == 0) {
                double[] w = walk(nodes, lake, i, 1000);
                if (w != null) {
                    double s = w[0] / Math.max(1e-9, Math.hypot(w[1] - start.x(), w[2] - start.z()));
                    sinSum += s; windows++;
                    if (s < 1.1) straight++;
                    if (s > 1.5) meander++;
                }
            }
            if (start.area() > 1e6 && i % 4 == 0) {
                double[] w = walk(nodes, lake, i, 512);
                if (w != null) {
                    double angle = Math.toDegrees(Math.atan2(w[2] - start.z(), w[1] - start.x()));
                    double m = ((angle % 45) + 45) % 45;
                    if (Math.min(m, 45 - m) <= 5) biased++;
                    // Knight-move axes (26.57 and 63.43 degrees, i.e. 26.57 or 18.43 within each 45-degree sector).
                    if (Math.abs(m - 26.565) <= 5 || Math.abs(m - 18.435) <= 5) knight++;
                    sector[(int) Math.min(8, Math.min(m, 45 - m) / 2.5)]++;
                    chords++;
                }
            }
        }

        // Junctions: angle between the two upstream branches 48 blocks above the confluence (as RHR), tongue width.
        double angleSum = 0; int junctions = 0, acute = 0;
        List<Double> tongues = new ArrayList<>();
        Cell c = new Cell();
        for (int j = 0; j < n; j++) {
            if (children.get(j).size() != 2 || lake[j]) continue;
            double[][] dirs = new double[2][];
            for (int k = 0; k < 2; k++) {
                int v = children.get(j).get(k);
                double travelled = 0;
                var prev = nodes.get(j);
                while (travelled < 48 && children.get(v).size() == 1) {
                    travelled += Math.hypot(nodes.get(v).x() - prev.x(), nodes.get(v).z() - prev.z());
                    prev = nodes.get(v); v = children.get(v).get(0);
                }
                double dx = nodes.get(v).x() - nodes.get(j).x(), dz = nodes.get(v).z() - nodes.get(j).z(), len = Math.hypot(dx, dz);
                if (len < 1e-9) { dirs = null; break; }
                dirs[k] = new double[]{dx / len, dz / len};
            }
            if (dirs == null) continue;
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dirs[0][0] * dirs[1][0] + dirs[0][1] * dirs[1][1]))));
            angleSum += angle; junctions++;
            if (angle < 25) acute++;
            // Tongue: march along the bisector to the first dry cell, then measure the dry width 8 blocks further.
            double bx = dirs[0][0] + dirs[1][0], bz = dirs[0][1] + dirs[1][1], bl = Math.hypot(bx, bz);
            if (bl < 1e-6) continue;
            bx /= bl; bz /= bl;
            double jx = nodes.get(j).x(), jz = nodes.get(j).z();
            double tip = -1;
            for (int t = 0; t <= 80; t++) {
                context.heightmap.apply(c.reset(), (float) (jx + bx * t), (float) (jz + bz * t), false);
                if (!c.hasRiverWater) { tip = t; break; }
            }
            if (tip < 0) continue;
            // Width 2 blocks behind the dry tip: a knife edge (straight banks meeting at the junction angle) is ~3
            // blocks there; a rounded apex of radius r is ~2*sqrt(4r - 4).
            tongues.add((double) dryWidth(context, c, jx + bx * (tip + 2), jz + bz * (tip + 2), bx, bz));
        }
        Collections.sort(tongues);
        long narrow = tongues.stream().filter(v -> v < 5).count();

        return new Metrics(label, plan.gridStep(), plan.gridNodes(), plan.landNodes(), n, plan.buildNanos() / 1e6,
            windows == 0 ? Double.NaN : sinSum / windows, ratio(straight, windows), ratio(meander, windows), windows,
            ratio(biased, chords), ratio(knight, chords), anisotropy(sector, chords), chords, junctions == 0 ? Double.NaN : angleSum / junctions, ratio(acute, junctions), junctions,
            tongues.isEmpty() ? Double.NaN : tongues.get(tongues.size() / 2), ratio(narrow, tongues.size()), tongues.size(),
            crossings(nodes), plan.oxbows().size(), outsideBelt(plan));
    }

    /** Total channel length over total valley-axis length (axis samples are ≤ 16 blocks apart; larger gaps are stem jumps). */
    static double channelToAxis(HighlandDrainage.Plan plan) {
        double channel = 0, axis = 0;
        for (var node : plan.nodes()) if (node.parent() >= 0) {
            var p = plan.nodes().get(node.parent());
            channel += Math.hypot(node.x() - p.x(), node.z() - p.z());
        }
        for (int i = 1; i < plan.axis().size(); i++) {
            var a = plan.axis().get(i - 1); var b = plan.axis().get(i);
            double d = Math.hypot(a.x() - b.x(), a.z() - b.z());
            if (d < 40) axis += d;
        }
        return axis > 0 ? channel / axis : Double.NaN;
    }

    /** Channel nodes farther from their own reach's valley axis than the meander belt there (+1 block). */
    static long outsideBelt(HighlandDrainage.Plan plan) {
        long outside = 0;
        var axis = plan.axis();
        for (var reach : plan.reaches()) {
            for (int v = reach.first(); v <= reach.last(); v++) {
                var node = plan.nodes().get(v);
                double best = Double.POSITIVE_INFINITY, belt = 0;
                for (int a = reach.axisFrom(); a < reach.axisTo(); a++) {
                    var p = axis.get(a); var q = axis.get(a + 1);
                    double dx = q.x() - p.x(), dz = q.z() - p.z(), l2 = dx * dx + dz * dz;
                    double t = l2 > 1e-12 ? Math.max(0, Math.min(1, ((node.x() - p.x()) * dx + (node.z() - p.z()) * dz) / l2)) : 0;
                    double d = Math.hypot(node.x() - p.x() - dx * t, node.z() - p.z() - dz * t);
                    if (d < best) { best = d; belt = p.belt() + (q.belt() - p.belt()) * t; }
                }
                if (best > belt + 1) outside++;
            }
        }
        return outside;
    }

    private static int dryWidth(GeneratorContext context, Cell c, double sx, double sz, double bx, double bz) {
        int width = 1;
        for (int side = -1; side <= 1; side += 2) {
            for (int d = 1; d <= 40; d++) {
                context.heightmap.apply(c.reset(), (float) (sx - bz * d * side), (float) (sz + bx * d * side), false);
                if (c.hasRiverWater) break;
                width++;
            }
        }
        return width;
    }

    /** Walks downstream from a node until {@code length} blocks of channel; returns {path, endX, endZ} or null. */
    private static double[] walk(List<HighlandDrainage.Node> nodes, boolean[] lake, int start, double length) {
        double path = 0;
        int k = start;
        while (path < length) {
            int p = nodes.get(k).parent();
            if (p < 0 || lake[p]) return null;
            path += Math.hypot(nodes.get(k).x() - nodes.get(p).x(), nodes.get(k).z() - nodes.get(p).z());
            k = p;
        }
        return new double[]{path, nodes.get(k).x(), nodes.get(k).z()};
    }

    /** Proper intersections between channel segments that share no endpoint. */
    static long crossings(List<HighlandDrainage.Node> nodes) {
        Map<Long, List<Integer>> grid = new HashMap<>();
        int cell = 64;
        for (int i = 0; i < nodes.size(); i++) {
            int p = nodes.get(i).parent();
            if (p < 0) continue;
            var a = nodes.get(i); var b = nodes.get(p);
            int x0 = Math.floorDiv((int) Math.min(a.x(), b.x()), cell), x1 = Math.floorDiv((int) Math.max(a.x(), b.x()), cell);
            int z0 = Math.floorDiv((int) Math.min(a.z(), b.z()), cell), z1 = Math.floorDiv((int) Math.max(a.z(), b.z()), cell);
            for (int gx = x0; gx <= x1; gx++) for (int gz = z0; gz <= z1; gz++) grid.computeIfAbsent(((long) gx << 32) ^ (gz & 0xffffffffL), k -> new ArrayList<>()).add(i);
        }
        Set<Long> counted = new HashSet<>();
        for (var list : grid.values()) {
            for (int u = 0; u < list.size(); u++) for (int v = u + 1; v < list.size(); v++) {
                int i = list.get(u), j = list.get(v);
                int pi = nodes.get(i).parent(), pj = nodes.get(j).parent();
                if (i == pj || j == pi || pi == pj) continue;
                if (!intersects(nodes.get(i), nodes.get(pi), nodes.get(j), nodes.get(pj))) continue;
                counted.add(((long) Math.min(i, j) << 32) | Math.max(i, j));
            }
        }
        return counted.size();
    }

    private static boolean intersects(HighlandDrainage.Node a, HighlandDrainage.Node b, HighlandDrainage.Node c, HighlandDrainage.Node d) {
        double d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }
    private static double cross(HighlandDrainage.Node o, HighlandDrainage.Node a, HighlandDrainage.Node b) {
        return (a.x() - o.x()) * (b.z() - o.z()) - (a.z() - o.z()) * (b.x() - o.x());
    }

    /** Hillshade with water classes (channel, lake, wetland) of a square area. */
    static void render(GeneratorContext context, int cx, int cz, int size, int res, Path out) throws Exception {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        int px = size / res;
        double[][] h = new double[px][px];
        int[][] kind = new int[px][px];
        Cell c = new Cell();
        float coast = context.preset.world().controlPoints.coast;
        for (int z = 0; z < px; z++) for (int x = 0; x < px; x++) {
            int bx = cx - size / 2 + x * res, bz = cz - size / 2 + z * res;
            context.heightmap.apply(c.reset(), bx, bz, false);
            h[x][z] = (c.height - context.levels.water) * context.levels.terrainScaleFactor;
            if (c.continentEdge <= coast) { kind[x][z] = 0; continue; }
            if (!c.hasRiverWater) { kind[x][z] = c.terrain == TerrainType.WETLAND ? 5 : 1; continue; }
            var lk = continent.planAt(bx, bz).lake(bx, bz);
            kind[x][z] = c.terrain == TerrainType.WETLAND ? 4 : lk != null && lk.depth() > 0 ? 3 : 2;
        }
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int z = 1; z < px - 1; z++) for (int x = 1; x < px - 1; x++) {
            double gx = (h[x + 1][z] - h[x - 1][z]) / (2.0 * res), gz = (h[x][z + 1] - h[x][z - 1]) / (2.0 * res);
            double shade = Math.max(0, Math.min(1, .55 + (-gx - gz) * 1.1));
            int rgb = switch (kind[x][z]) {
                case 0 -> 0x163a65;
                case 2 -> 0x2a7fd4;
                case 3 -> 0x1f5fb0;
                case 4 -> 0x3f8f8a;
                default -> {
                    double e = Math.max(0, Math.min(1, h[x][z] / 400));
                    double tint = kind[x][z] == 5 ? .8 : 1;
                    int r = (int) ((90 + 140 * e) * shade * tint), g = (int) ((130 + 70 * e) * shade), b = (int) ((70 + 110 * e) * shade * tint);
                    yield (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
                }
            };
            image.setRGB(x, z, rgb);
        }
        Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
    }

    /** Total-variation distance of the folded chord-direction histogram (offset from the nearest 45-degree axis,
     *  0-22.5 degrees in 2.5-degree bins) from uniform: 0 = isotropic. */
    private static double anisotropy(double[] bins, int total) {
        if (total == 0) return 0;
        double tv = 0;
        for (double b : bins) tv += Math.abs(b / total - 1.0 / bins.length);
        return tv / 2;
    }

    private static double ratio(long a, long b) { return b == 0 ? 0 : (double) a / b; }
}
