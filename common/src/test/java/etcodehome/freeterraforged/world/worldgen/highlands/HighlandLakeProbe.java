package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * Lake metrics (PLAN-RH6 L) on one continent, from rendered cells at 4-block resolution around every lake:
 * count, area, depth, shallow share, inlet water steps, shore-parallel channel runs and constant-height rim rings.
 */
final class HighlandLakeProbe {
    static final int RES = 4;

    record Lake(int id, long cells, double meanDepth, double shallowShare, double x, double z) {
        double area() { return cells * (double) RES * RES; }
    }

    record Metrics(int lakes, double area, double meanDepth, double shallowShare, double largestArea, long inletSteps,
                   int steppedLakes, double longestParallel, double rimAt1, double rimAt2, List<Lake> each) {
        String summary() {
            return String.format(Locale.ROOT,
                "RHL lakes=%d area=%.2fkm2 largest=%.2fkm2 meanDepth=%.1f shallow<3=%.0f%% inletSteps=%d (lakes=%d) longestParallel=%.0f shelf+1=%.0f%% shelf+2=%.0f%%",
                lakes, area / 1e6, largestArea / 1e6, meanDepth, 100 * shallowShare, inletSteps, steppedLakes, longestParallel, 100 * rimAt1, 100 * rimAt2);
        }
    }

    /** Values per plan cell, allocated only where sampled. */
    private static final class Sparse {
        final int width, per;
        final Map<Integer, float[]> h = new HashMap<>(), w = new HashMap<>();
        final Map<Integer, byte[]> kind = new HashMap<>();
        final Map<Integer, int[]> lake = new HashMap<>();
        Sparse(int width, int per) { this.width = width; this.per = per; }
        private int cell(int gx, int gz) { return gx < 0 || gz < 0 ? -1 : (gz / per) * width + gx / per; }
        private int slot(int gx, int gz) { return (gz % per) * per + gx % per; }
        void set(int gx, int gz, double hv, double wv, byte k, int id) {
            int c = cell(gx, gz), s = slot(gx, gz);
            h.computeIfAbsent(c, x -> new float[per * per])[s] = (float) hv;
            w.computeIfAbsent(c, x -> new float[per * per])[s] = (float) wv;
            setKind(gx, gz, k, id);
        }
        void setKind(int gx, int gz, byte k, int id) {
            int c = cell(gx, gz), s = slot(gx, gz);
            kind.computeIfAbsent(c, x -> new byte[per * per])[s] = k;
            lake.computeIfAbsent(c, x -> new int[per * per])[s] = id;
        }
        byte kind(int gx, int gz) { byte[] a = kind.get(cell(gx, gz)); return a == null ? 0 : a[slot(gx, gz)]; }
        float h(int gx, int gz) { float[] a = h.get(cell(gx, gz)); return a == null ? 0 : a[slot(gx, gz)]; }
        float w(int gx, int gz) { float[] a = w.get(cell(gx, gz)); return a == null ? 0 : a[slot(gx, gz)]; }
        int lake(int gx, int gz) { int[] a = lake.get(cell(gx, gz)); return a == null ? -1 : a[slot(gx, gz)]; }
    }

    private static double blocks(GeneratorContext context, float normalized) {
        return (normalized - context.levels.water) * context.levels.terrainScaleFactor;
    }

    static Metrics measure(GeneratorContext context, int px, int pz, Path image) throws Exception {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var index = continent.planAt(px, pz);
        var plan = index.plan();
        var basins = plan.basins();
        int step = plan.gridStep(), width = plan.width(), depth = plan.incision().length / width;
        // Plan cells touching a lake's shore band, dilated by one cell.
        boolean[] marked = new boolean[width * depth];
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            var s = basins.sample(plan.originX() + ix * step + .5, plan.originZ() + iz * step + .5);
            if (s == null || s.depth() <= -24) continue;
            for (int dz = -1; dz <= 0; dz++) for (int dx = -1; dx <= 0; dx++) {
                int x = ix + dx, z = iz + dz;
                if (x >= 0 && z >= 0) marked[z * width + x] = true;
            }
        }
        boolean[] region = marked.clone();
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            if (!marked[iz * width + ix]) continue;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int x = ix + dx, z = iz + dz;
                if (x >= 0 && z >= 0 && x < width && z < depth) region[z * width + x] = true;
            }
        }
        int per = step / RES, gw = width * per, gd = depth * per;
        var grid = new Sparse(width, per);
        List<int[]> sampled = new ArrayList<>();
        Cell c = new Cell();
        float coastEdge = context.preset.world().controlPoints.coast;
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            if (!region[iz * width + ix]) continue;
            for (int sz = 0; sz < per; sz++) for (int sx = 0; sx < per; sx++) {
                int gx = ix * per + sx, gz = iz * per + sz;
                sampled.add(new int[]{gx, gz});
                int bx = plan.originX() + gx * RES, bz = plan.originZ() + gz * RES;
                context.heightmap.apply(c.reset(), bx, bz, false);
                grid.set(gx, gz, blocks(context, c.height), blocks(context, c.riverWaterLevel), (byte) 0, -1);
                if (c.continentEdge <= coastEdge) continue;
                if (!c.hasRiverWater) { grid.setKind(gx, gz, (byte) 1, -1); continue; }
                var s = index.lake(bx, bz);
                var oxbow = index.oxbow(bx, bz);
                boolean still = oxbow != null && oxbow.distance() < oxbow.halfWidth();
                boolean inLake = s != null && c.terrain != TerrainType.WETLAND && !still && (s.depth() > 0 || !c.hasFlow);
                if (inLake) grid.setKind(gx, gz, (byte) 3, s.lake());
                else grid.setKind(gx, gz, (byte) (c.hasFlow && c.terrain != TerrainType.WETLAND ? 2 : 4), -1);
            }
        }
        // Per-lake statistics.
        Map<Integer, double[]> stats = new TreeMap<>(); // cells, depth sum, shallow, x sum, z sum
        for (int[] at : sampled) {
            int gx = at[0], gz = at[1];
            if (grid.kind(gx, gz) != 3) continue;
            double d = grid.w(gx, gz) - grid.h(gx, gz);
            double[] s = stats.computeIfAbsent(grid.lake(gx, gz), k -> new double[5]);
            s[0]++; s[1] += d; if (d < 3) s[2]++;
            s[3] += plan.originX() + gx * RES; s[4] += plan.originZ() + gz * RES;
        }
        List<Lake> each = new ArrayList<>();
        long cells = 0; double depthSum = 0, shallow = 0, largest = 0;
        for (var e : stats.entrySet()) {
            double[] s = e.getValue();
            // Specks of a few cells (shore noise of a lake owned by another plan) are not lakes.
            if (s[0] < 8) continue;
            each.add(new Lake(e.getKey(), (long) s[0], s[1] / s[0], s[2] / s[0], s[3] / s[0], s[4] / s[0]));
            cells += (long) s[0]; depthSum += s[1]; shallow += s[2];
            largest = Math.max(largest, s[0] * RES * RES);
        }
        // Inlet steps: stream water touching lake water at a different level.
        long steps = 0;
        Set<Integer> stepped = new TreeSet<>();
        long shore = 0, rim1 = 0, rim2 = 0;
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] at : sampled) {
            int gx = at[0], gz = at[1];
            byte kg = grid.kind(gx, gz);
            if (kg == 2) {
                for (int[] d : around) {
                    int jx = gx + d[0], jz = gz + d[1];
                    if (grid.kind(jx, jz) == 3 && Math.abs(grid.w(jx, jz) - grid.w(gx, gz)) >= .5) {
                        if (steps == 0) System.out.printf(Locale.ROOT, "RHL first inlet step at %d,%d stream %.2f lake %.2f%n",
                            plan.originX() + gx * RES, plan.originZ() + gz * RES, grid.w(gx, gz), grid.w(jx, jz));
                        steps++; stepped.add(grid.lake(jx, jz)); break;
                    }
                }
            } else if (kg == 1) {
                for (int[] d : around) {
                    int jx = gx + d[0], jz = gz + d[1];
                    if (grid.kind(jx, jz) != 3) continue;
                    shore++;
                    int k = (int) Math.floor(grid.h(gx, gz)) - (int) Math.floor(grid.w(jx, jz));
                    if (k == 1 || k == 2) {
                        // A shelf: the land 4 and 8 blocks further from the water stands on the same block level.
                        int ox = gx - d[0], oz = gz - d[1], fx = gx - 2 * d[0], fz = gz - 2 * d[1];
                        int level = (int) Math.floor(grid.h(gx, gz));
                        boolean shelf = grid.kind(ox, oz) == 1 && grid.kind(fx, fz) == 1
                            && (int) Math.floor(grid.h(ox, oz)) == level && (int) Math.floor(grid.h(fx, fz)) == level;
                        if (shelf && k == 1) rim1++;
                        if (shelf && k == 2) rim2++;
                    }
                    break;
                }
            }
        }
        // Shore-parallel channel runs: consecutive channel samples (outside lakes) with lake water within 12 blocks.
        var nodes = plan.nodes();
        double[] run = new double[nodes.size()];
        double longest = 0;
        int reach = 12 / RES;
        boolean[] near = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            int gx = (int) Math.round((node.x() - plan.originX()) / RES), gz = (int) Math.round((node.z() - plan.originZ()) / RES);
            if (gx < reach || gz < reach || gx >= gw - reach || gz >= gd - reach) continue;
            if (grid.kind(gx, gz) == 3) continue;
            var s = basins.sample(node.x(), node.z());
            if (s != null && s.depth() > 0) continue;
            // A separate channel: its water stands at least half a block off the lake's (a channel at the lake's own level
            // is the lake's margin, RH6 L3).
            for (int dz = -reach; dz <= reach && !near[i]; dz++) for (int dx = -reach; dx <= reach; dx++) {
                if (dx * dx + dz * dz > reach * reach) continue;
                if (grid.kind(gx + dx, gz + dz) == 3 && Math.abs(node.water() - grid.w(gx + dx, gz + dz)) >= .5) { near[i] = true; break; }
            }
        }
        // Lengths: accumulate from each node to its parent while both are near (children have higher indices).
        for (int i = nodes.size() - 1; i >= 0; i--) {
            var node = nodes.get(i);
            int p = node.parent();
            if (!near[i]) continue;
            longest = Math.max(longest, run[i]);
            if (p >= 0 && near[p]) {
                double length = run[i] + Math.hypot(node.x() - nodes.get(p).x(), node.z() - nodes.get(p).z());
                run[p] = Math.max(run[p], length);
                if (length > longest && length > 32) System.out.printf(Locale.ROOT, "RHL parallel run %.0f ending at %.0f,%.0f%n", length, nodes.get(p).x(), nodes.get(p).z());
                longest = Math.max(longest, length);
            }
        }
        if (image != null) {
            int x0 = gw, z0 = gd, x1 = 0, z1 = 0;
            for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) if (region[iz * width + ix]) {
                x0 = Math.min(x0, ix * per); z0 = Math.min(z0, iz * per); x1 = Math.max(x1, ix * per + per); z1 = Math.max(z1, iz * per + per);
            }
            if (x1 > x0 && z1 > z0) {
                BufferedImage map = new BufferedImage(x1 - x0, z1 - z0, BufferedImage.TYPE_INT_RGB);
                for (int gz = z0 + 1; gz < z1 - 1; gz++) for (int gx = x0 + 1; gx < x1 - 1; gx++) {
                    int rgb = switch (grid.kind(gx, gz)) {
                        case 2 -> 0x2a7fd4; case 3 -> 0x1f5fb0; case 4 -> 0x3f8f8a;
                        case 1 -> {
                            double gxs = grid.h(gx + 1, gz) - grid.h(gx - 1, gz), gzs = grid.h(gx, gz + 1) - grid.h(gx, gz - 1);
                            double shade = Math.max(0, Math.min(1, .55 + (-gxs - gzs) / (2.0 * RES) * 1.1));
                            int v = (int) (Math.min(255, 90 + grid.h(gx, gz) * .5) * shade);
                            yield (v << 16) | (Math.min(255, v + 30) << 8) | (v / 2);
                        }
                        default -> 0;
                    };
                    map.setRGB(gx - x0, gz - z0, rgb);
                }
                Files.createDirectories(image.getParent());
                ImageIO.write(map, "png", image.toFile());
            }
        }
        return new Metrics(each.size(), cells * (double) RES * RES, cells == 0 ? 0 : depthSum / cells, cells == 0 ? 0 : shallow / cells, largest,
            steps, stepped.size(), longest, shore == 0 ? 0 : (double) rim1 / shore, shore == 0 ? 0 : (double) rim2 / shore, each);
    }
}
