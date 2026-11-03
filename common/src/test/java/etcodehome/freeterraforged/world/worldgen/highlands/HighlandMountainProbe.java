package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

/**
 * Mountain morphology on the rendered surface of a continent's ranges (SPEC-RH7 G0):
 * <ul>
 * <li>valley spacing: along strike-parallel transects at mid-flank (±1 km), local height minima at least 10% of the
 * range relief below the higher of their neighbouring maxima; reported as range half width over mean spacing;</li>
 * <li>dissection depth: mean of (300-block max filter − surface) over flank columns, relative to range height;</li>
 * <li>hillslope angles on flank columns away from drawn water: median and share under 10°;</li>
 * <li>crest sharpness: width (blocks) of the band within 5 blocks of the local crest height across the strike;</li>
 * <li>convexity: share of flank columns whose 64-block Laplacian is negative (convex).</li>
 * </ul>
 */
final class HighlandMountainProbe {
    static final int STEP = 64;

    record Metrics(double widthOverSpacing, double spacing, double dissection, double medianSlope, double gentleShare, double crestWidth,
                   double convexShare, int flankSamples) {
        String summary() {
            return String.format(Locale.ROOT,
                "RHMountain halfWidth/spacing=%.2f spacing=%.0f dissection=%.2f medianSlope=%.1fdeg gentle<10=%.0f%% crestWidth=%.0f convex=%.0f%% (n=%d)",
                widthOverSpacing, spacing, dissection, medianSlope, 100 * gentleShare, crestWidth, 100 * convexShare, flankSamples);
        }
    }

    private static double blocks(GeneratorContext context, Cell c, double x, double z) {
        context.heightmap.apply(c.reset(), (int) Math.floor(x), (int) Math.floor(z), false);
        return (c.height - context.levels.water) * context.levels.terrainScaleFactor;
    }

    static Metrics measure(GeneratorContext context, int px, int pz) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var land = HighlandLand.create(context);
        var coast = continent.coast();
        var owner = coast.sample(px, pz);
        var bounds = coast.bounds(owner.ownerX(), owner.ownerZ());
        float coastEdge = context.preset.world().controlPoints.coast;
        double rangeHeight = context.preset.terrain().highlands().rangeHeight();
        double halfWidth = land.rangeHalfWidth();
        Cell c = new Cell();
        Random random = new Random(7);
        List<Double> slopes = new ArrayList<>(), crestWidths = new ArrayList<>(), spacings = new ArrayList<>();
        double dissection = 0; int flank = 0, convex = 0, crestSeen = 0;
        for (double z = bounds.minZ(); z < bounds.maxZ(); z += STEP) for (double x = bounds.minX(); x < bounds.maxX(); x += STEP) {
            var site = coast.sample(x, z);
            if (site.ownerX() != owner.ownerX() || site.ownerZ() != owner.ownerZ() || site.edge() <= coastEdge) continue;
            if (continent.drainage().landBlend(site.edge()) < .8) continue;
            var frame = land.rangeFrame(x, z);
            if (frame == null) continue;
            double core = frame.core();
            if (core >= .2 && core <= .8) {
                context.heightmap.apply(c.reset(), (int) x, (int) z, false);
                if (c.hasRiverWater) continue;
                double h = blocks(context, c, x, z);
                double gx = (blocks(context, c, x + 2, z) - blocks(context, c, x - 2, z)) / 4, gz = (blocks(context, c, x, z + 2) - blocks(context, c, x, z - 2)) / 4;
                slopes.add(Math.toDegrees(Math.atan(Math.hypot(gx, gz))));
                double max = h;
                for (int k = 0; k < 16; k++) {
                    double a = random.nextDouble() * 2 * Math.PI, r = 150 * Math.sqrt(random.nextDouble());
                    max = Math.max(max, blocks(context, c, x + r * Math.cos(a), z + r * Math.sin(a)));
                }
                dissection += (max - h) / rangeHeight;
                double lap = blocks(context, c, x + 64, z) + blocks(context, c, x - 64, z) + blocks(context, c, x, z + 64) + blocks(context, c, x, z - 64) - 4 * h;
                if (lap < 0) convex++;
                flank++;
            }
            if (core >= .97 && (crestSeen++ % 6) == 0) {
                double ax = Math.cos(frame.strike()), az = Math.sin(frame.strike()), nx = -az, nz = ax;
                // Crest width across the strike.
                double top = Double.NEGATIVE_INFINITY;
                double[] across = new double[101];
                for (int i = 0; i <= 100; i++) { across[i] = blocks(context, c, x + (i - 50) * 4 * nx, z + (i - 50) * 4 * nz); top = Math.max(top, across[i]); }
                int lo = 50, hi = 50;
                int peak = 0; for (int i = 0; i <= 100; i++) if (across[i] == top) peak = i;
                lo = peak; hi = peak;
                while (lo > 0 && across[lo - 1] >= top - 5) lo--;
                while (hi < 100 && across[hi + 1] >= top - 5) hi++;
                crestWidths.add((double) (hi - lo + 1) * 4);
                // Valley spacing along a strike-parallel transect at mid-flank on both sides.
                for (int side = -1; side <= 1; side += 2) {
                    double ox = x + side * .5 * halfWidth * frame.width() * nx, oz = z + side * .5 * halfWidth * frame.width() * nz;
                    double[] line = new double[126];
                    for (int i = 0; i < line.length; i++) line[i] = blocks(context, c, ox + (i - 62) * 16 * ax, oz + (i - 62) * 16 * az);
                    int minima = 0;
                    double prominence = .1 * rangeHeight;
                    for (int i = 1; i < line.length - 1; i++) {
                        if (line[i] > line[i - 1] || line[i] > line[i + 1]) continue;
                        double left = line[i], right = line[i];
                        for (int j = i - 1; j >= 0; j--) left = Math.max(left, line[j]);
                        for (int j = i + 1; j < line.length; j++) right = Math.max(right, line[j]);
                        if (Math.min(left, right) - line[i] >= prominence) minima++;
                    }
                    spacings.add(minima == 0 ? 2000.0 : 2000.0 / minima);
                }
            }
        }
        Collections.sort(slopes);
        double median = slopes.isEmpty() ? Double.NaN : slopes.get(slopes.size() / 2);
        long gentle = slopes.stream().filter(s -> s < 10).count();
        Collections.sort(crestWidths);
        double spacing = spacings.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        return new Metrics(halfWidth / spacing, spacing, flank == 0 ? Double.NaN : dissection / flank, median,
            slopes.isEmpty() ? Double.NaN : (double) gentle / slopes.size(), crestWidths.isEmpty() ? Double.NaN : crestWidths.get(crestWidths.size() / 2),
            flank == 0 ? Double.NaN : (double) convex / flank, flank);
    }

    /** RH8 cross-section metrics; see {@link #ridges}. */
    record Ridges(int sections, double multiShare, double meanCrests, double saddleDepth, double spacing, double spacingCv, double highestModeShare) {
        String summary() {
            return String.format(Locale.ROOT,
                "RHRidges sections=%d multiCrest=%.0f%% meanCrests=%.2f saddleDepth/H=%.2f spacing=%.0f spacingCv=%.2f highestSameThird=%.0f%%",
                sections, 100 * multiShare, meanCrests, saddleDepth, spacing, spacingCv, 100 * highestModeShare);
        }
    }
    static final int SECTION_STEP = 16;
    static final double CREST_PROMINENCE = .15;

    /**
     * RH8 P0 cross-sections of the rendered surface: at range centre-line samples, a transect across the strike over the
     * range (± its half width on that flank), smoothed over three samples. Crests are local maxima whose 1-D prominence
     * is at least CREST_PROMINENCE of the Range Height. Reports the share of sections with two or more crests, the mean
     * crest count, the mean depth of the lowest point between neighbouring crests below the lower crest (over Range
     * Height), the mean and coefficient of variation of neighbouring crest spacing, and the largest share of sections
     * whose highest crest lies in the same third of the transect.
     */
    static Ridges ridges(GeneratorContext context, int px, int pz) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var land = HighlandLand.create(context);
        var coast = continent.coast();
        var owner = coast.sample(px, pz);
        var bounds = coast.bounds(owner.ownerX(), owner.ownerZ());
        float coastEdge = context.preset.world().controlPoints.coast;
        double rangeHeight = context.preset.terrain().highlands().rangeHeight();
        double halfWidth = land.rangeHalfWidth();
        Cell c = new Cell();
        int sections = 0, multi = 0, crestTotal = 0, saddles = 0, seen = 0;
        int[] thirds = new int[3];
        double saddleSum = 0;
        List<Double> gaps = new ArrayList<>();
        for (double z = bounds.minZ(); z < bounds.maxZ(); z += STEP) for (double x = bounds.minX(); x < bounds.maxX(); x += STEP) {
            var site = coast.sample(x, z);
            if (site.ownerX() != owner.ownerX() || site.ownerZ() != owner.ownerZ() || site.edge() <= coastEdge) continue;
            if (continent.drainage().landBlend(site.edge()) < .8) continue;
            var frame = land.rangeFrame(x, z);
            if (frame == null || frame.distance() > STEP / 2.0 || frame.presence() < .6) continue;
            if (seen++ % 4 != 0) continue;
            double nx = -Math.sin(frame.strike()), nz = Math.cos(frame.strike());
            int n = (int) (halfWidth * frame.width() / SECTION_STEP);
            double[] raw = new double[2 * n + 1], p = new double[2 * n + 1];
            for (int i = 0; i <= 2 * n; i++) raw[i] = blocks(context, c, x + (i - n) * SECTION_STEP * nx, z + (i - n) * SECTION_STEP * nz);
            for (int i = 0; i <= 2 * n; i++) p[i] = (raw[Math.max(0, i - 1)] + raw[i] + raw[Math.min(2 * n, i + 1)]) / 3;
            List<Integer> crests = new ArrayList<>();
            for (int i = 1; i < 2 * n; i++) {
                if (p[i] < p[i - 1] || p[i] <= p[i + 1]) continue;
                double left = p[i], right = p[i];
                int j = i - 1;
                for (; j >= 0 && p[j] <= p[i]; j--) left = Math.min(left, p[j]);
                int k = i + 1;
                for (; k <= 2 * n && p[k] <= p[i]; k++) right = Math.min(right, p[k]);
                if (p[i] - Math.max(left, right) >= CREST_PROMINENCE * rangeHeight) crests.add(i);
            }
            sections++;
            crestTotal += crests.size();
            if (crests.size() >= 2) multi++;
            int highest = -1;
            for (int q = 0; q < crests.size(); q++) {
                int a = crests.get(q);
                if (highest < 0 || p[a] > p[highest]) highest = a;
                if (q == 0) continue;
                int b = crests.get(q - 1);
                double low = Double.MAX_VALUE;
                for (int i = b; i <= a; i++) low = Math.min(low, p[i]);
                saddleSum += (Math.min(p[a], p[b]) - low) / rangeHeight;
                saddles++;
                gaps.add((double) (a - b) * SECTION_STEP);
            }
            if (highest >= 0) thirds[Math.min(2, 3 * highest / (2 * n + 1))]++;
        }
        double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double variance = gaps.stream().mapToDouble(g -> (g - mean) * (g - mean)).average().orElse(Double.NaN);
        int crested = thirds[0] + thirds[1] + thirds[2];
        return new Ridges(sections, sections == 0 ? Double.NaN : (double) multi / sections, sections == 0 ? Double.NaN : (double) crestTotal / sections,
            saddles == 0 ? Double.NaN : saddleSum / saddles, mean, Math.sqrt(variance) / mean,
            crested == 0 ? Double.NaN : (double) Math.max(thirds[0], Math.max(thirds[1], thirds[2])) / crested);
    }
}
