package etcodehome.freeterraforged.world.worldgen.highlands;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings;

/**
 * Diagnostic (no assertions): range-field layout against distance from the world origin. rangeField rotates world
 * coordinates by a position-dependent strike angle, which may shear the field far from the origin. For square windows
 * at growing distance this reports, on the raw field (ignoring continents):
 * <ul>
 * <li>coverage: share of samples inside a range frame (core &gt; 0);</li>
 * <li>spacing: transect length per crossing of the 0.5 crest iso-line (blocks between crest lines);</li>
 * <li>align: mean |cos| between crest direction and the intended strike (1 = along strike, ~0.64 = random);</li>
 * <li>curv R: median radius of curvature of the crest iso-line (blocks; small = winding / chaotic).</li>
 * </ul>
 * Writes build/range-field-probe.txt.
 */
@Tag("probe")
class HighlandRangeFieldProbe {
    static final int WINDOW = 16384, STEP = 64, LENGTH = RollingHighlandsSettings.DEFAULT_RANGE_LENGTH;
    static final int[] DISTANCES = {0, 10_000, 20_000, 40_000, 80_000};

    @Test void rangeLayoutVersusDistance() throws Exception {
        WorldgenFixture.initialize();
        var out = new StringBuilder();
        for (int ridges : new int[] {1, 3}) for (long seed : new long[] {0, 12345}) {
            var preset = HighlandQualityProbe.preset();
            preset.terrain().rollingHighlands = RollingHighlandsSettings.ROCKIES.withRangeRidges(ridges);
            try (var context = WorldgenFixture.context(preset, seed)) {
                var land = HighlandLand.create(context);
                Method field = HighlandLand.class.getDeclaredMethod("rangeField", double.class, double.class);
                field.setAccessible(true);
                out.append(String.format(Locale.ROOT, "%nridges=%d seed=%d%n%-8s %-6s %8s %8s %7s %8s%n", ridges, seed, "dist", "dir", "coverage", "spacing", "align", "curvR"));
                for (int d : DISTANCES) for (String dir : new String[] {"+x", "diag"}) {
                    double cx = dir.equals("+x") ? d : d / Math.sqrt(2), cz = dir.equals("+x") ? 0 : d / Math.sqrt(2);
                    out.append(String.format(Locale.ROOT, "%-8d %-6s %s%n", d, dir, measure(land, field, cx, cz)));
                }
            }
        }
        Path file = Path.of("build", "range-field-probe.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.println(out);
    }

    private static String measure(HighlandLand land, Method field, double cx, double cz) throws Exception {
        int n = WINDOW / STEP;
        double x0 = cx - WINDOW / 2.0, z0 = cz - WINDOW / 2.0;
        double[] f = new double[n * n];
        int inside = 0;
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            double x = x0 + i * STEP, z = z0 + j * STEP;
            f[j * n + i] = (double) field.invoke(land, x, z);
            if (land.rangeCore(x, z) > 0) inside++;
        }
        int crossings = 0, aligned = 0;
        double alignSum = 0;
        double[] radii = new double[2 * n * n];
        int radiusCount = 0;
        long seed = land.seed();
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            double a = f[j * n + i] - .5;
            boolean right = i + 1 < n && a * (f[j * n + i + 1] - .5) < 0;
            boolean down = j + 1 < n && a * (f[(j + 1) * n + i] - .5) < 0;
            if (!right && !down) continue;
            crossings += (right ? 1 : 0) + (down ? 1 : 0);
            double x = x0 + i * STEP, z = z0 + j * STEP, e = 16;
            double gx = ((double) field.invoke(land, x + e, z) - (double) field.invoke(land, x - e, z)) / (2 * e);
            double gz = ((double) field.invoke(land, x, z + e) - (double) field.invoke(land, x, z - e)) / (2 * e);
            if (Math.hypot(gx, gz) < 1e-12) continue;
            double crest = Math.atan2(gz, gx) + Math.PI / 2;
            double strike = Math.PI * HighlandMath.noise(seed + 6091, x, z, 5 * LENGTH);
            alignSum += Math.abs(Math.cos(crest - strike));
            aligned++;
            // Curvature of the iso-line: divergence of the unit gradient, by central differences.
            double h = 32;
            double div = (unitX(land, field, x + h, z) - unitX(land, field, x - h, z)) / (2 * h)
                + (unitZ(land, field, x, z + h) - unitZ(land, field, x, z - h)) / (2 * h);
            radii[radiusCount++] = Math.abs(div) < 1e-9 ? 1e9 : 1 / Math.abs(div);
        }
        double transect = 2.0 * n * (n - 1) * STEP;
        double[] r = Arrays.copyOf(radii, radiusCount);
        Arrays.sort(r);
        double median = r.length == 0 ? Double.NaN : r[r.length / 2];
        return String.format(Locale.ROOT, "%7.1f%% %8.0f %7.2f %8.0f", 100.0 * inside / (n * n),
            crossings == 0 ? Double.NaN : transect / crossings, aligned == 0 ? Double.NaN : alignSum / aligned, median);
    }

    private static double[] gradient(HighlandLand land, Method field, double x, double z) throws Exception {
        double e = 16;
        double gx = ((double) field.invoke(land, x + e, z) - (double) field.invoke(land, x - e, z)) / (2 * e);
        double gz = ((double) field.invoke(land, x, z + e) - (double) field.invoke(land, x, z - e)) / (2 * e);
        double g = Math.max(1e-12, Math.hypot(gx, gz));
        return new double[] {gx / g, gz / g};
    }
    private static double unitX(HighlandLand land, Method field, double x, double z) throws Exception { return gradient(land, field, x, z)[0]; }
    private static double unitZ(HighlandLand land, Method field, double x, double z) throws Exception { return gradient(land, field, x, z)[1]; }
}
