package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** RH9 coast gates (SPEC-RH9 §3) on the user's world near the 2026-11-05 screenshot site (10109, 18236). */
class HighlandCoastTest {
    static final int SITE_X = 10109, SITE_Z = 18236, RADIUS = 4096;

    @Test void reportsCoast() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm the owner plans so ns/column is column cost
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            System.out.println(report.summary("user"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/rh9-coast.txt"), report.summary("user") + "\n");
            assertTrue(report.samples() >= 100, "too few coast samples to judge: " + report.samples());
            // C: coves and points.
            assertTrue(report.sinuosity() >= 1.15, "coastline too straight: " + report.summary("user"));
            // A-1 shares (profile's dominant type), within ±8 points.
            double typeBand = .08 + HighlandDrownedTest.tol(.20);
            assertTrue(Math.abs(report.beachShare() - .55) <= typeBand && Math.abs(report.bluffShare() - .25) <= typeBand
                && Math.abs(report.cliffShare() - .20) <= typeBand, "type shares off by more than " + typeBand + " (.08 plus the D-11-14-2 margin): " + report.summary("user"));
            // A-2 stretches, not flicker.
            assertTrue(report.stretchMedian() >= 300, "coast type flickers: " + report.summary("user"));
        }
    }

    /** V: the coast gates also hold on the ROCKIES profile (tall frame, Range Height 400), same seed and box. */
    @Test void rockiesCoastMeetsGates() throws Exception {
        try (var context = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            String summary = report.summary("rockies");
            System.out.println(summary);
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/rh9-coast-rockies.txt"), summary + "\n");
            assertTrue(report.samples() >= 100, "too few coast samples to judge: " + report.samples());
            assertTrue(report.sinuosity() >= 1.15, "coastline too straight: " + summary);
            double typeBand = .08 + HighlandDrownedTest.tol(.20);
            assertTrue(Math.abs(report.beachShare() - .55) <= typeBand && Math.abs(report.bluffShare() - .25) <= typeBand
                && Math.abs(report.cliffShare() - .20) <= typeBand, "type shares off by more than " + typeBand + " (.08 plus the D-11-14-2 margin): " + summary);
            assertTrue(report.beachWidthMedian() >= 18 && report.beachWidthP10() >= 8, "beaches too narrow: " + summary);
            assertTrue(report.cliffHeight4Median() >= 15, "cliffs too low: " + summary);
            assertTrue(report.cliffDepth8Median() >= 5, "water at cliff foot too shallow: " + summary);
            assertEquals(0, report.sunk(), "dry land below sea level beside the coast: " + summary);
        }
    }

    /** S: beaches, bluffs and sea cliffs on the ground (SPEC-RH9 §3 S gates, A-6, A-8). */
    @Test void shoresHaveTheirShape() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            String summary = report.summary("user");
            assertTrue(report.beachWidthMedian() >= 18 && report.beachWidthP10() >= 8, "beaches too narrow: " + summary);
            assertTrue(report.cliffHeight4Median() >= 15, "cliffs too low: " + summary);
            assertTrue(report.cliffDepth8Median() >= 5, "water at cliff foot too shallow: " + summary);
            assertTrue(report.hollowShare() <= .05 + HighlandDrownedTest.tol(.05), "hollows behind scarps, share " + report.hollowShare() + HighlandDrownedTest.margin(.05 + HighlandDrownedTest.tol(.05)) + ": " + summary);
            assertEquals(0, report.sunk(), "dry land below sea level beside the coast: " + summary);
        }
    }

    /** Beaches must not come and go with the lookup path (the reason RollingHighlandsContinent.shore exists). */
    @Test void beachTagIsPathIndependent() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
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
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
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
            assertTrue(rock >= (.9 - HighlandDrownedTest.tol(.9)) * steep, rock + " of " + steep + " steep cliff columns are rock" + HighlandDrownedTest.margin(.9 - HighlandDrownedTest.tol(.9)));
        }
    }

    /** M: cool, wet (PNW) beaches stay beaches, never cliff cover. */
    @Test void coldBeachesStayBeaches() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
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
            assertTrue(wrong <= (.05 + HighlandDrownedTest.tol(.05)) * checked, wrong + " of " + checked + " PNW beach columns are not plain beach" + HighlandDrownedTest.margin(.05 + HighlandDrownedTest.tol(.05)));
        }
    }

    /** X: sea stacks stand only off cliff coasts, clear of the shore. */
    @Test void stacksOnlyOffCliffs() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            double coastEdge = context.preset.world().controlPoints.coast;
            var c = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int stack = 0, misplaced = 0, wrongRock = 0;
            String example = "";
            for (int z = SITE_Z - 2048; z <= SITE_Z + 2048; z += 2) for (int x = SITE_X - 2048; x <= SITE_X + 2048; x += 2) {
                double edge = coast.sample(x, z).edge();
                if (edge > coastEdge || edge < coastEdge - .04) continue;
                context.lookup.applyCell(c.reset(), x, z, false, true);
                if ((c.height - context.levels.water) * context.levels.terrainScaleFactor <= .5) continue;
                stack++;
                if (c.highlandCoastClimate != HighlandCoastProfile.climateClass(coast.profile().shore(x, z))) wrongRock++;
                double slope = coast.edgeSlope(x, z, edge), d = slope > 0 ? (coastEdge - edge) / slope : Double.POSITIVE_INFINITY;
                // Stack columns lie within a stack's radius (≤ 6) of a centre that obeys the rule (cliff ≥ .6, ≥ 10 out).
                if (coast.profile().shore(x, z).cliff() < .5 || d < 4) { misplaced++; example = x + "," + z + " d=" + d; }
            }
            assertTrue(stack > 0, "no sea-stack columns");
            assertEquals(0, misplaced, misplaced + " of " + stack + " stack columns off a cliff coast or at the shore, e.g. " + example);
            assertTrue(wrongRock <= (.01 + HighlandDrownedTest.tol(.01)) * stack, wrongRock + " of " + stack + " stack columns carry another climate's rock" + HighlandDrownedTest.margin(.01 + HighlandDrownedTest.tol(.01)));
        }
    }

    @Test void stacksAreRecorded() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            var report = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
            assertTrue(report.stacksPer10km() > 0, "no sea stacks off cliff coasts: " + report.summary("user"));
        }
    }

    /** Review Focus 1: lakes inside the coastal band of bluff/cliff shores keep water-tight banks. */
    @Test void coastalLakesKeepTheirBanks() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            var rh = (RollingHighlandsContinent) context.heightmap.continent();
            var coast = rh.coast();
            double coastEdge = context.preset.world().controlPoints.coast;
            var basins = rh.planOf(SITE_X, SITE_Z).plan().basins();
            var wet = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            var dry = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int lakes = 0, checked = 0, spills = 0;
            String example = "";
            for (int id = 0; id < basins.count(); id++) {
                double[] b = basins.lakeBounds(id);
                boolean coastal = false;
                for (double z = b[1]; z <= b[3] && !coastal; z += 4) for (double x = b[0]; x <= b[2] && !coastal; x += 4) {
                    var shore = coast.profile().shore(x, z);
                    coastal = coast.sample(x, z).edge() - coastEdge < HighlandDrainage.COAST_BAND && shore.bluff() + shore.cliff() > 0;
                }
                if (!coastal) continue;
                lakes++;
                for (int z = (int) b[1]; z <= b[3]; z++) for (int x = (int) b[0]; x <= b[2]; x++) {
                    var lake = basins.sample(x, z);
                    if (lake == null || lake.lake() != id) continue;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        if (basins.sample(x + d[0], z + d[1]) != null) continue;
                        context.heightmap.apply(wet.reset(), x, z, false);
                        context.heightmap.apply(dry.reset(), x + d[0], z + d[1], false);
                        if (!wet.hasRiverWater || dry.hasRiverWater) continue;
                        checked++;
                        if (context.levels.scale(dry.height) < context.levels.scale(wet.riverWaterLevel)) {
                            spills++; example = (x + d[0]) + "," + (z + d[1]);
                        }
                    }
                }
            }
            System.out.println("RHC coastal lakes=" + lakes + " banks=" + checked + " spills=" + spills);
            assertTrue(checked > 0, "no coastal lake banks in the site's owner (lakes=" + lakes + ")");
            assertEquals(0, spills, spills + " of " + checked + " coastal lake banks below the water, e.g. " + example);
        }
    }

    /** Review Focus 2: no dry land below sea level anywhere in the coastal band, peninsulas and straits included. */
    @Test void narrowLandHasNoSeaPits() throws Exception {
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            double coastEdge = context.preset.world().controlPoints.coast;
            var c = new etcodehome.freeterraforged.world.worldgen.cell.Cell();
            int land = 0, narrow = 0, pits = 0;
            String example = "";
            for (int z = SITE_Z - RADIUS; z <= SITE_Z + RADIUS; z += 4) for (int x = SITE_X - RADIUS; x <= SITE_X + RADIUS; x += 4) {
                double edge = coast.sample(x, z).edge();
                if (edge <= coastEdge || edge - coastEdge >= HighlandDrainage.COAST_BAND) continue;
                context.lookup.applyCell(c.reset(), x, z, false, true);
                if (c.hasRiverWater) continue;
                land++;
                double[] g = inland(coast, x, z);
                boolean thin = false;
                for (int k = 16; k <= 96 && !thin; k += 16) thin = coast.sample(x + g[0] * k, z + g[1] * k).edge() <= coastEdge;
                if (thin) narrow++;
                if (blocksAt(context, x, z) < -.5) { pits++; example = x + "," + z + (thin ? " (narrow)" : ""); }
            }
            System.out.println("RHC band land=" + land + " narrow=" + narrow + " pits=" + pits);
            assertTrue(narrow > 0, "no narrow land in the box to judge");
            assertEquals(0, pits, pits + " of " + land + " coastal land columns below sea level, e.g. " + example);
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
        { var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            var profile = new HighlandCoastProfile(context);
            var random = new Random(11);
            for (int i = 0; i < 10000; i++) {
                double w = profile.warp(random.nextInt(80000) - 40000, random.nextInt(80000) - 40000);
                assertTrue(Math.abs(w) <= HighlandCoastProfile.WARP_AMPLITUDE, "warp " + w);
            }
        }
    }
}
