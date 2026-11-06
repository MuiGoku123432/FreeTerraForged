package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** RH9 coast gates (SPEC-RH9 §3) on the user's world near the 2026-11-05 screenshot site (10109, 18236). */
class HighlandCoastTest {
    static final int SITE_X = 10109, SITE_Z = 18236, RADIUS = 4096;

    @Test void reportsCoast() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm the owner plans so ns/column is column cost
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            System.out.println(report.summary("user"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/rh9-coast.txt"), report.summary("user") + "\n");
            assertTrue(report.samples() >= 100, "too few coast samples to judge: " + report.samples());
            // C: coves and points.
            assertTrue(report.sinuosity() >= 1.15, "coastline too straight: " + report.summary("user"));
            // A-1 shares (profile's dominant type), within ±8 points.
            assertTrue(Math.abs(report.beachShare() - .55) <= .08 && Math.abs(report.bluffShare() - .25) <= .08
                && Math.abs(report.cliffShare() - .20) <= .08, "type shares off: " + report.summary("user"));
            // A-2 stretches, not flicker.
            assertTrue(report.stretchMedian() >= 300, "coast type flickers: " + report.summary("user"));
        }
    }

    /** S: beaches, bluffs and sea cliffs on the ground (SPEC-RH9 §3 S gates, A-6, A-8). */
    @Test void shoresHaveTheirShape() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            String summary = report.summary("user");
            assertTrue(report.beachWidthMedian() >= 18 && report.beachWidthP10() >= 8, "beaches too narrow: " + summary);
            assertTrue(report.cliffHeight4Median() >= 15, "cliffs too low: " + summary);
            assertTrue(report.cliffDepth8Median() >= 5, "water at cliff foot too shallow: " + summary);
            assertTrue(report.hollowShare() <= .05, "hollows behind scarps: " + summary);
            assertEquals(0, report.sunk(), "dry land below sea level beside the coast: " + summary);
        }
    }

    /** Beaches must not come and go with the lookup path (the reason RollingHighlandsContinent.shore exists). */
    @Test void beachTagIsPathIndependent() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, 1024);
            var a = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            var b = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int checked = 0, differ = 0;
            for (var s : report.list()) {
                for (int d = 0; d < 40; d += 4) {
                    context.lookup.applyCell(a.reset(), s.x() + d, s.z(), false, true);
                    context.heightmap.applyPrecise(b.reset(), s.x() + d, s.z(), true);
                    checked++;
                    if ((a.terrain == etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.BEACH)
                        != (b.terrain == etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.BEACH)) differ++;
                }
            }
            assertTrue(checked >= 200, "too few columns: " + checked);
            assertEquals(0, differ, differ + " of " + checked + " coastal columns change BEACH tag with the lookup path");
        }
    }

    /** M: steep coastal faces are bare rock (SPEC-RH9 M). */
    @Test void cliffFacesAreRock() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            var c = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int steep = 0, rock = 0;
            for (var s : report.list()) {
                if (!s.type().equals("cliff")) continue;
                double[] g = inland(coast, s.x(), s.z());
                for (int d = 0; d <= 8; d++) {
                    int x = (int) Math.round(s.x() + g[0] * d), z = (int) Math.round(s.z() + g[1] * d);
                    context.lookup.applyCell(c.reset(), x, z, false, true);
                    if (c.hasRiverWater || c.continentEdge <= context.preset.world().controlPoints.coast) continue;
                    double slope = slope(context, x, z);
                    if (slope < HighlandSurface.FOREST_CLIFF_SLOPE) continue;
                    steep++;
                    var cover = HighlandSurface.choose(slope, context.levels.scale(c.height), context.levels.scale(c.highlandSnowline),
                        context.levels.scale(c.highlandTreeline), c.highlandRange, c.highlandCoast, 0);
                    if (cover == HighlandSurface.Cover.CLIFF) rock++;
                }
            }
            assertTrue(steep >= 20, "too few steep cliff columns: " + steep);
            assertTrue(rock >= .9 * steep, rock + " of " + steep + " steep cliff columns are rock");
        }
    }

    /** M: cool, wet (PNW) beaches stay beaches, never cliff cover. */
    @Test void coldBeachesStayBeaches() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            var c = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int checked = 0, wrong = 0;
            for (var s : report.list()) {
                var shore = coast.profile().shore(s.x(), s.z());
                if (!s.type().equals("beach") || HighlandCoastProfile.climateClass(shore) != HighlandCoastProfile.PNW) continue;
                double[] g = inland(coast, s.x(), s.z());
                for (int d = 2; d <= 10; d++) {
                    int x = (int) Math.round(s.x() + g[0] * d), z = (int) Math.round(s.z() + g[1] * d);
                    context.lookup.applyCell(c.reset(), x, z, false, true);
                    if (c.hasRiverWater) continue;
                    checked++;
                    var cover = HighlandSurface.choose(slope(context, x, z), context.levels.scale(c.height), context.levels.scale(c.highlandSnowline),
                        context.levels.scale(c.highlandTreeline), c.highlandRange, c.highlandCoast, 0);
                    if (c.terrain != etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.BEACH
                        || cover == HighlandSurface.Cover.CLIFF || c.highlandCoastClimate != HighlandCoastProfile.PNW) wrong++;
                }
            }
            assertTrue(checked >= 20, "too few PNW beach columns: " + checked);
            assertTrue(wrong <= .05 * checked, wrong + " of " + checked + " PNW beach columns are not plain beach");
        }
    }

    /** X: sea stacks stand only off cliff coasts, clear of the shore. */
    @Test void stacksOnlyOffCliffs() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            double coastEdge = context.preset.world().controlPoints.coast;
            var c = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int stack = 0, misplaced = 0;
            String example = "";
            for (int z = SITE_Z - 2048; z <= SITE_Z + 2048; z += 2) for (int x = SITE_X - 2048; x <= SITE_X + 2048; x += 2) {
                double edge = coast.sample(x, z).edge();
                if (edge > coastEdge || edge < coastEdge - .04) continue;
                context.lookup.applyCell(c.reset(), x, z, false, true);
                if ((c.height - context.levels.water) * context.levels.terrainScaleFactor <= .5) continue;
                stack++;
                double slope = coast.edgeSlope(x, z, edge), d = slope > 0 ? (coastEdge - edge) / slope : Double.POSITIVE_INFINITY;
                // Stack columns lie within a stack's radius (≤ 6) of a centre that obeys the rule (cliff ≥ .6, ≥ 10 out).
                if (coast.profile().shore(x, z).cliff() < .5 || d < 4) { misplaced++; example = x + "," + z + " d=" + d; }
            }
            assertTrue(stack > 0, "no sea-stack columns");
            assertEquals(0, misplaced, misplaced + " of " + stack + " stack columns off a cliff coast or at the shore, e.g. " + example);
        }
    }

    @Test void stacksAreRecorded() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            assertTrue(report.stacksPer10km() > 0, "no sea stacks off cliff coasts: " + report.summary("user"));
        }
    }

    private static double[] inland(HighlandCoast coast, double x, double z) {
        double e = coast.sample(x, z).edge();
        double gx = coast.sample(x + 2, z).edge() - e, gz = coast.sample(x, z + 2).edge() - e, n = Math.hypot(gx, gz);
        return new double[]{gx / n, gz / n};
    }

    /** Surface slope (degrees) from cell heights two blocks either side, as ScreeFeature measures it. */
    private static double slope(etcodehome.freeterraforged.world.worldgen.GeneratorContext context, int x, int z) {
        double dx = (blocksAt(context, x + 2, z) - blocksAt(context, x - 2, z)) / 4, dz = (blocksAt(context, x, z + 2) - blocksAt(context, x, z - 2)) / 4;
        return Math.toDegrees(Math.atan(Math.hypot(dx, dz)));
    }
    private static double blocksAt(etcodehome.freeterraforged.world.worldgen.GeneratorContext context, int x, int z) {
        var c = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
        context.lookup.applyCell(c, x, z, false, true);
        return (c.height - context.levels.water) * context.levels.terrainScaleFactor;
    }

    @Test void profileIsDeterministic() throws Exception {
        try (var a = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
             var b = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var pa = new HighlandCoastProfile(a);
            var pb = new HighlandCoastProfile(b);
            var random = new Random(9);
            for (int i = 0; i < 1000; i++) {
                double x = random.nextInt(40000) - 20000, z = random.nextInt(40000) - 20000;
                assertEquals(pa.shore(x, z), pb.shore(x, z));
                assertEquals(pa.warp(x, z), pb.warp(x, z));
            }
        }
    }

    @Test void warpStaysInBound() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var profile = new HighlandCoastProfile(context);
            var random = new Random(11);
            for (int i = 0; i < 10000; i++) {
                double w = profile.warp(random.nextInt(80000) - 40000, random.nextInt(80000) - 40000);
                assertTrue(Math.abs(w) <= HighlandCoastProfile.WARP_AMPLITUDE, "warp " + w);
            }
        }
    }
}
