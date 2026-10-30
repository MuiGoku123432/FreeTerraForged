package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

/**
 * Range metrics on the rendered surface (PLAN-RH6 R), on the continent owning a block position:
 * <ul>
 * <li>crest components: crest-line samples (range core ≥ CREST_CORE) on a 32-block grid, 8-connected;</li>
 * <li>evenness: along-crest height standard deviation over the crest's relief above its foot, and the share of crest
 * samples within 25% (of that relief) of the highest crest within 1 km;</li>
 * <li>spur orientation: on mid-flank windows, the dominant gradient direction of the detrended surface (spurs and the
 * gullies between them make the surface vary along the strike) relative to the strike.</li>
 * </ul>
 */
final class HighlandRangeProbe {
    static final int STEP = 32;
    static final double CREST_CORE = .95, FOOT_LOW = .02, FOOT_HIGH = .15, MIN_CREST = 2000, LOCAL = 1000, GAP = 96;

    record Metrics(int crests, double longest, double evenness, double nearMax, double spurAlignment, int spurWindows, double crestHeight,
                   double footHeight) {
        String summary() {
            return String.format(Locale.ROOT,
                "RHRange crests=%d longest=%.0f crestStd/relief=%.2f within25%%OfLocalMax=%.0f%% spursAcrossStrike=%.0f%% (n=%d) crest=%.0f foot=%.0f",
                crests, longest, evenness, 100 * nearMax, 100 * spurAlignment, spurWindows, crestHeight, footHeight);
        }
    }

    private static double blocks(GeneratorContext context, float normalized) {
        return (normalized - context.levels.water) * context.levels.terrainScaleFactor;
    }

    static Metrics measure(GeneratorContext context, int px, int pz) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var land = HighlandLand.create(context);
        var coast = continent.coast();
        var owner = coast.sample(px, pz);
        var bounds = coast.bounds(owner.ownerX(), owner.ownerZ());
        int w = (int) ((bounds.maxX() - bounds.minX()) / STEP), d = (int) ((bounds.maxZ() - bounds.minZ()) / STEP);
        float coastEdge = context.preset.world().controlPoints.coast;
        double[] core = new double[w * d];
        boolean[] crest = new boolean[w * d];
        for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
            double bx = bounds.minX() + x * STEP, bz = bounds.minZ() + z * STEP;
            var site = coast.sample(bx, bz);
            if (site.ownerX() != owner.ownerX() || site.ownerZ() != owner.ownerZ() || site.edge() <= coastEdge) continue;
            core[z * w + x] = land.rangeCore(bx, bz);
            crest[z * w + x] = core[z * w + x] >= CREST_CORE;
        }
        Cell c = new Cell();
        boolean[] seen = new boolean[w * d];
        int crests = 0;
        double longest = 0, evenSum = 0, evenWeight = 0, crestSum = 0, footSum = 0;
        long near = 0, total = 0;
        for (int start = 0; start < w * d; start++) {
            if (!crest[start] || seen[start]) continue;
            List<Integer> cells = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start); seen[start] = true;
            int minX = w, maxX = 0, minZ = d, maxZ = 0;
            while (!queue.isEmpty()) {
                int v = queue.poll(), vx = v % w, vz = v / w;
                cells.add(v);
                minX = Math.min(minX, vx); maxX = Math.max(maxX, vx); minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = vx + dx, nz = vz + dz;
                    if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                    int j = nz * w + nx;
                    if (crest[j] && !seen[j]) { seen[j] = true; queue.add(j); }
                }
            }
            double length = Math.hypot(maxX - minX, maxZ - minZ) * STEP;
            longest = Math.max(longest, length);
            if (length < MIN_CREST) continue;
            crests++;
            // Rendered crest heights (dry columns only: a river crossing at a pass is not the crest).
            double[] hx = new double[cells.size()], hz = new double[cells.size()], hh = new double[cells.size()];
            int n = 0;
            var index = continent.planOf(owner.ownerX(), owner.ownerZ());
            for (int v : cells) {
                double bx = bounds.minX() + v % w * STEP, bz = bounds.minZ() + v / w * STEP;
                // Inland crest only (the coastal rise lowers everything near the sea), away from river gaps.
                if (continent.drainage().landBlend(coast.sample(bx, bz).edge()) < .8) continue;
                var hits = index.near(bx, bz);
                boolean gap = false;
                for (int k = 0; k < hits.count && !gap; k++) gap = hits.distance[k] < GAP;
                if (gap) continue;
                context.heightmap.apply(c.reset(), (int) bx, (int) bz, false);
                if (c.hasRiverWater) continue;
                hx[n] = bx; hz[n] = bz; hh[n] = blocks(context, c.height); n++;
            }
            if (n < 32) continue;
            // Foot: rendered height of the range's outer edge within the crest's box expanded by 1.5 km.
            int pad = 1500 / STEP;
            double foot = 0; int feet = 0;
            for (int z = Math.max(0, minZ - pad); z <= Math.min(d - 1, maxZ + pad); z += 2) for (int x = Math.max(0, minX - pad); x <= Math.min(w - 1, maxX + pad); x += 2) {
                double k = core[z * w + x];
                if (k <= FOOT_LOW || k >= FOOT_HIGH) continue;
                context.heightmap.apply(c.reset(), (int) (bounds.minX() + x * STEP), (int) (bounds.minZ() + z * STEP), false);
                foot += blocks(context, c.height); feet++;
            }
            if (feet == 0) continue;
            foot /= feet;
            double mean = 0;
            for (int i = 0; i < n; i++) mean += hh[i];
            mean /= n;
            double var = 0;
            for (int i = 0; i < n; i++) var += (hh[i] - mean) * (hh[i] - mean);
            double relief = Math.max(1, mean - foot);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) { lo = Math.min(lo, hh[i]); hi = Math.max(hi, hh[i]); }
            System.out.printf(Locale.ROOT, "RHRange crest at %.0f,%.0f length %.0f samples %d mean %.0f std %.0f min %.0f max %.0f foot %.0f%n",
                hx[0], hz[0], length, n, mean, Math.sqrt(var / n), lo, hi, foot);
            evenSum += Math.sqrt(var / n) / relief * n; evenWeight += n;
            crestSum += mean * n; footSum += foot * n;
            for (int i = 0; i < n; i++) {
                double localMax = hh[i];
                for (int j = 0; j < n; j++) if (Math.hypot(hx[i] - hx[j], hz[i] - hz[j]) <= LOCAL) localMax = Math.max(localMax, hh[j]);
                total++;
                if (hh[i] >= localMax - .25 * Math.max(1, localMax - foot)) near++;
            }
        }
        // Spur orientation on mid-flank windows (every 12th flank sample).
        int windows = 0, aligned = 0, flank = 0;
        for (int i = 0; i < w * d; i++) {
            if (core[i] < .3 || core[i] > .7 || flank++ % 12 != 0) continue;
            double bx = bounds.minX() + i % w * STEP, bz = bounds.minZ() + i / w * STEP;
            // Strike: perpendicular to the core's gradient (the core falls away from the crest line).
            double cgx = land.rangeCore(bx + 8, bz) - land.rangeCore(bx - 8, bz), cgz = land.rangeCore(bx, bz + 8) - land.rangeCore(bx, bz - 8);
            if (Math.hypot(cgx, cgz) < 1e-9) continue;
            double strike = Math.atan2(cgz, cgx) + Math.PI / 2;
            int r = 6, s = 12;
            double[][] g = new double[2 * r + 1][2 * r + 1];
            boolean wet = false;
            for (int dz = -r; dz <= r && !wet; dz++) for (int dx = -r; dx <= r; dx++) {
                context.heightmap.apply(c.reset(), (int) (bx + dx * s), (int) (bz + dz * s), false);
                if (c.hasRiverWater) { wet = true; break; }
                g[dx + r][dz + r] = blocks(context, c.height);
            }
            if (wet) continue;
            // Detrend: remove the least-squares quadratic surface (the flank's overall slope down from the crest and its
            // concave profile), leaving the spurs, gullies and other relief.
            double[] q = quadratic(g, r);
            // Structure tensor of the detrended surface's gradient.
            double jxx = 0, jzz = 0, jxz = 0;
            for (int dz = -r + 1; dz < r; dz++) for (int dx = -r + 1; dx < r; dx++) {
                double gx = (g[dx + r + 1][dz + r] - g[dx + r - 1][dz + r]) / 2 - (q[1] + 2 * q[3] * dx + q[5] * dz);
                double gz = (g[dx + r][dz + r + 1] - g[dx + r][dz + r - 1]) / 2 - (q[2] + 2 * q[4] * dz + q[5] * dx);
                jxx += gx * gx; jzz += gz * gz; jxz += gx * gz;
            }
            double dominant = .5 * Math.atan2(2 * jxz, jxx - jzz);
            double difference = Math.abs(((dominant - strike) % Math.PI + Math.PI) % Math.PI);
            difference = Math.min(difference, Math.PI - difference);
            windows++;
            if (difference <= Math.toRadians(30)) aligned++;
        }
        return new Metrics(crests, longest, evenWeight == 0 ? Double.NaN : evenSum / evenWeight, total == 0 ? Double.NaN : (double) near / total,
            windows == 0 ? Double.NaN : (double) aligned / windows, windows, evenWeight == 0 ? 0 : crestSum / evenWeight, evenWeight == 0 ? 0 : footSum / evenWeight);
    }

    /** Least-squares fit h = q0 + q1 dx + q2 dz + q3 dx² + q4 dz² + q5 dx dz over a (2r+1)² window. */
    private static double[] quadratic(double[][] g, int r) {
        double[][] a = new double[6][7];
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            double[] f = {1, dx, dz, dx * dx, dz * dz, dx * dz};
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 6; j++) a[i][j] += f[i] * f[j];
                a[i][6] += f[i] * g[dx + r][dz + r];
            }
        }
        for (int i = 0; i < 6; i++) {
            int p = i;
            for (int k = i + 1; k < 6; k++) if (Math.abs(a[k][i]) > Math.abs(a[p][i])) p = k;
            double[] t = a[i]; a[i] = a[p]; a[p] = t;
            for (int k = 0; k < 6; k++) {
                if (k == i) continue;
                double s = a[k][i] / a[i][i];
                for (int j = i; j < 7; j++) a[k][j] -= s * a[i][j];
            }
        }
        double[] q = new double[6];
        for (int i = 0; i < 6; i++) q[i] = a[i][6] / a[i][i];
        return q;
    }

}
