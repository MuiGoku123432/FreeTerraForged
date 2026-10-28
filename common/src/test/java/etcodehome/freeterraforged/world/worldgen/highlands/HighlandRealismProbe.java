package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

/**
 * Continent-scale realism metrics (PLAN-RH3 Q0), compared against the geomorphology targets in RESEARCH-RH3 §3.
 * Samples the origin continent every RES blocks and analyses its drainage plan; writes a hillshade render.
 */
final class HighlandRealismProbe {
    static final int RES = 16;

    record Metrics(long seed, long landSamples, double lakeFraction, double wetFraction, double depressionFraction,
                   double meanSlopeDegrees, double[] slopeBins, double incisedFraction, double meanJunctionAngle,
                   int junctions, double hackExponent, double bifurcationRatio, double concavity, int maxOrder) {
        String summary() {
            StringBuilder bins = new StringBuilder();
            for (double b : slopeBins) bins.append(bins.length() == 0 ? "" : "/").append(String.format(Locale.ROOT, "%.0f", 100 * b));
            return String.format(Locale.ROOT,
                "RHR seed=%d land=%d lakes=%.2f%% wet=%.2f%% depressions=%.2f%% meanSlope=%.1fdeg slope5degBins=%s "
                    + "incised>60=%.1f%% junctionAngle=%.1fdeg(n=%d) hack=%.2f Rb=%.2f concavity=%.2f maxOrder=%d",
                seed, landSamples, 100 * lakeFraction, 100 * wetFraction, 100 * depressionFraction, meanSlopeDegrees, bins,
                100 * incisedFraction, meanJunctionAngle, junctions, hackExponent, bifurcationRatio, concavity, maxOrder);
        }
    }

    static Metrics measure(long seed, boolean writeImage) throws Exception {
        var preset = HighlandQualityProbe.preset();
        try (var context = WorldgenFixture.context(preset, seed)) {
            var continent = (RollingHighlandsContinent) context.heightmap.continent();
            var bounds = continent.coast().bounds(0, 0);
            int w = (int) ((bounds.maxX() - bounds.minX()) / RES), h = (int) ((bounds.maxZ() - bounds.minZ()) / RES);
            double[] surface = new double[w * h];
            byte[] kind = new byte[w * h]; // 0 ocean, 1 dry land, 2 channel/wetland water, 3 lake
            Cell c = new Cell();
            long land = 0, lakes = 0, wet = 0;
            float coastEdge = preset.world().controlPoints.coast;
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
                double bx = bounds.minX() + x * RES, bz = bounds.minZ() + z * RES;
                context.heightmap.apply(c.reset(), (int) bx, (int) bz, false);
                int i = z * w + x;
                double height = (c.height - context.levels.water) * context.levels.terrainScaleFactor;
                double water = (c.riverWaterLevel - context.levels.water) * context.levels.terrainScaleFactor;
                boolean ocean = c.continentEdge <= coastEdge;
                surface[i] = c.hasRiverWater ? Math.max(height, water) : Math.max(height, ocean ? 0 : height);
                if (ocean) { kind[i] = 0; continue; }
                land++;
                if (c.hasRiverWater) {
                    wet++;
                    var lake = continent.planAt(bx, bz).lake(bx, bz);
                    boolean isLake = lake != null && lake.depth() > 0;
                    kind[i] = (byte) (isLake ? 3 : 2);
                    if (isLake) lakes++;
                } else kind[i] = 1;
            }

            // Depression area (Dendry metric): priority-flood fill from the ocean; dry land more than 1 block below
            // its filled level lies in a closed depression.
            double[] filled = surface.clone();
            boolean[] seen = new boolean[w * h];
            PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
            for (int i = 0; i < w * h; i++) {
                int x = i % w, z = i / w;
                if (kind[i] == 0 || x == 0 || z == 0 || x == w - 1 || z == h - 1) {
                    seen[i] = true; queue.add(new long[]{Double.doubleToLongBits(Math.max(0, filled[i]) + 1e6), i});
                }
            }
            while (!queue.isEmpty()) {
                long[] e = queue.remove();
                int i = (int) e[1], x = i % w, z = i / w;
                double level = Double.longBitsToDouble(e[0]) - 1e6;
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if ((dx == 0 && dz == 0) || nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                    int j = nz * w + nx;
                    if (seen[j]) continue;
                    seen[j] = true;
                    filled[j] = Math.max(filled[j], level);
                    queue.add(new long[]{Double.doubleToLongBits(filled[j] + 1e6), j});
                }
            }
            long depressed = 0;
            for (int i = 0; i < w * h; i++) if (kind[i] == 1 && filled[i] - surface[i] > 1) depressed++;

            // Slopes on dry land.
            double[] bins = new double[10]; double slopeSum = 0; long slopes = 0;
            for (int z = 1; z < h - 1; z++) for (int x = 1; x < w - 1; x++) {
                int i = z * w + x;
                if (kind[i] != 1) continue;
                double gx = (surface[i + 1] - surface[i - 1]) / (2.0 * RES), gz = (surface[i + w] - surface[i - w]) / (2.0 * RES);
                double degrees = Math.toDegrees(Math.atan(Math.hypot(gx, gz)));
                bins[(int) Math.min(9, degrees / 5)]++; slopeSum += degrees; slopes++;
            }
            for (int b = 0; b < bins.length; b++) bins[b] /= Math.max(1, slopes);

            // Drainage plan analysis.
            var plan = continent.planOf(0, 0).plan();
            var nodes = plan.nodes();
            int n = nodes.size();
            List<List<Integer>> children = new ArrayList<>();
            for (int i = 0; i < n; i++) children.add(new ArrayList<>());
            for (int i = 0; i < n; i++) if (nodes.get(i).parent() >= 0) children.get(nodes.get(i).parent()).add(i);

            long incised = 0, channelSamples = 0;
            for (var node : nodes) {
                if (node.parent() < 0) continue;
                channelSamples++;
                if (continent.uncarvedLand(node.x(), node.z()) - node.water() > 60) incised++;
            }

            // Junction angles: directions from the junction to a point ~48 blocks up each branch.
            double angleSum = 0; int junctions = 0;
            for (int j = 0; j < n; j++) {
                if (children.get(j).size() != 2) continue;
                double[][] dirs = new double[2][];
                for (int k = 0; k < 2; k++) {
                    int v = children.get(j).get(k);
                    double travelled = 0;
                    var prev = nodes.get(j);
                    while (travelled < 48 && children.get(v).size() == 1) {
                        travelled += Math.hypot(nodes.get(v).x() - prev.x(), nodes.get(v).z() - prev.z());
                        prev = nodes.get(v); v = children.get(v).get(0);
                    }
                    dirs[k] = new double[]{nodes.get(v).x() - nodes.get(j).x(), nodes.get(v).z() - nodes.get(j).z()};
                }
                double dot = dirs[0][0] * dirs[1][0] + dirs[0][1] * dirs[1][1];
                double norm = Math.hypot(dirs[0][0], dirs[0][1]) * Math.hypot(dirs[1][0], dirs[1][1]);
                if (norm < 1e-9) continue;
                angleSum += Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / norm)))); junctions++;
            }

            // Longest upstream flow length per node (children appear after parents in the node list).
            double[] longest = new double[n];
            for (int i = n - 1; i >= 0; i--) {
                int p = nodes.get(i).parent();
                if (p < 0) continue;
                double length = longest[i] + Math.hypot(nodes.get(i).x() - nodes.get(p).x(), nodes.get(i).z() - nodes.get(p).z());
                longest[p] = Math.max(longest[p], length);
            }
            // Hack's law L = C A^h, least squares in log space over channel nodes with real upstream extent.
            double sx = 0, sy = 0, sxx = 0, sxy = 0; int m = 0;
            for (int i = 0; i < n; i++) {
                if (longest[i] < 256 || nodes.get(i).area() <= 0) continue;
                double lx = Math.log(nodes.get(i).area()), ly = Math.log(longest[i]);
                sx += lx; sy += ly; sxx += lx * lx; sxy += lx * ly; m++;
            }
            double hack = m > 2 ? (m * sxy - sx * sy) / (m * sxx - sx * sx) : Double.NaN;

            // Concavity: water-surface slope vs area on channel nodes, S = ks A^-theta.
            sx = sy = sxx = sxy = 0; m = 0;
            for (int i = 0; i < n; i++) {
                int p = nodes.get(i).parent();
                if (p < 0) continue;
                double run = Math.hypot(nodes.get(i).x() - nodes.get(p).x(), nodes.get(i).z() - nodes.get(p).z());
                double drop = nodes.get(i).water() - nodes.get(p).water();
                if (run < 1 || drop <= 1e-3) continue;
                double lx = Math.log(nodes.get(i).area()), ly = Math.log(drop / run);
                sx += lx; sy += ly; sxx += lx * lx; sxy += lx * ly; m++;
            }
            double concavity = m > 2 ? -(m * sxy - sx * sy) / (m * sxx - sx * sx) : Double.NaN;

            // Strahler orders on the refined tree and the bifurcation ratio of stream counts.
            int[] order = new int[n];
            for (int i = n - 1; i >= 0; i--) {
                var kids = children.get(i);
                if (kids.isEmpty()) { order[i] = 1; continue; }
                int max = 0, count = 0;
                for (int k : kids) { if (order[k] > max) { max = order[k]; count = 1; } else if (order[k] == max) count++; }
                order[i] = count >= 2 ? max + 1 : max;
            }
            int maxOrder = 0; for (int o : order) maxOrder = Math.max(maxOrder, o);
            long[] streams = new long[maxOrder + 2];
            for (int i = 0; i < n; i++) {
                int p = nodes.get(i).parent();
                if (p < 0 || order[p] != order[i]) streams[order[i]]++; // a stream starts where its order begins
            }
            double ratioSum = 0; int ratios = 0;
            for (int o = 1; o < maxOrder; o++) if (streams[o + 1] > 0) { ratioSum += (double) streams[o] / streams[o + 1]; ratios++; }

            if (writeImage) {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int z = 1; z < h - 1; z++) for (int x = 1; x < w - 1; x++) {
                    int i = z * w + x;
                    double gx = (surface[i + 1] - surface[i - 1]) / (2.0 * RES), gz = (surface[i + w] - surface[i - w]) / (2.0 * RES);
                    double shade = Math.max(0, Math.min(1, .55 + (-gx - gz) * 1.1));
                    int rgb = switch (kind[i]) {
                        case 0 -> 0x163a65;
                        case 2 -> 0x2a7fd4;
                        case 3 -> 0x1f5fb0;
                        default -> {
                            double e = Math.max(0, Math.min(1, surface[i] / 320));
                            int r = (int) ((90 + 120 * e) * shade), g = (int) ((130 + 80 * e) * shade), b = (int) ((70 + 120 * e) * shade);
                            yield (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
                        }
                    };
                    image.setRGB(x, z, rgb);
                }
                Path out = Path.of("build/rolling-highlands/continent-" + seed + ".png");
                Files.createDirectories(out.getParent());
                ImageIO.write(image, "png", out.toFile());
            }
            return new Metrics(seed, land, ratio(lakes, land), ratio(wet, land), ratio(depressed, land), slopeSum / Math.max(1, slopes),
                bins, ratio(incised, channelSamples), junctions == 0 ? Double.NaN : angleSum / junctions, junctions, hack,
                ratios == 0 ? Double.NaN : ratioSum / ratios, concavity, maxOrder);
        }
    }

    private static double ratio(long a, long b) { return b == 0 ? 0 : (double) a / b; }
}
