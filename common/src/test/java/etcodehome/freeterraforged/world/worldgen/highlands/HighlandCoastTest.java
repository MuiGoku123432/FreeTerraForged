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
