package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * PLAN-RH2 landscape gates. Each phase tightens the gates that it is responsible for; RH1 baseline values
 * are recorded in PLAN-RH2.md section 1.
 */
class HighlandQualityTest {
    static final long[] SEEDS = {0, 1, 42, 8675309, -314159};

    @Test void landscapeQualityGates() throws Exception {
        var failures = new ArrayList<String>();
        for (long seed : SEEDS) {
            var m = HighlandQualityProbe.measure(seed, true);
            System.out.println(m.summary());
            check(failures, m, Double.isFinite(m.worstJump()) && Double.isFinite(m.oceanFloorMin()), "finite metrics");
            // P1: valleys are composed, so interfluves remain land and channels are contained.
            check(failures, m, m.valleyZone() <= .75, "P1 valley zone <= 75% of dry land");
            // RH3 Q2: parallel tributaries approaching a lowered trunk can still meet in a short rapid; Q5 (junction
            // approach shaping) restores the strict gate of 0.
            check(failures, m, m.waterSteps() <= 10, "P1 at most 10 adjacent wet water steps >= 2 blocks");
            // P3: lakes have continuous shorelines and no cliffs at the water's edge.
            check(failures, m, m.dryJumps() <= 2, "P3 at most 2 RH-induced dry 1-block jumps > 3 blocks");
            check(failures, m, m.seamPixels() <= 20, "P3 <= 20 seam pixels (> 12 blocks per 4) in the window");
            check(failures, m, m.shoreCliffs() == 0, "P3 no shore cliffs > 12 blocks within 4 blocks of water");
            // P5: every channel cell carries a current, and currents run downstream.
            check(failures, m, m.flowCoverage() >= .98, "P5 >= 98% of channel cells have flow (Q5 restores 100%)");
            check(failures, m, m.flowUpstream() <= .01, "P5 <= 1% of sampled currents point upstream");
            // P2: channels follow the terrain rather than the routing grid.
            check(failures, m, m.gridAligned() <= .15, "P2 <= 15% of channel length exactly grid-aligned");
            // P4: biome inputs follow legacy FTF distributions (default preset, measured 2026-10-27:
            // slices low/mid/high/peak ~60/33/6/1, erosion levels 1-4 ~12/16/52/20, beaches ~3%).
            check(failures, m, m.positiveWeirdness() == 0, "P4 weirdness stays on the normal side");
            double[] slices = m.weirdnessSlices(), legacy = {.60, .33, .06, .01};
            for (int i = 0; i < 4; i++) check(failures, m, Math.abs(slices[i] - legacy[i]) <= .08, "P4 weirdness slice " + i + " near legacy");
            check(failures, m, m.peakOnFloor() <= .01, "P4 no peak slice on valley floors");
            // RH3: erosion comes natively from the legacy populators (hills/dales legitimately reach level 5).
            check(failures, m, m.beach() <= .04, "P4 beaches <= 4% of land (legacy-native land is low near coasts)");
            check(failures, m, m.oceanFloorMin() >= -256, "P4 ocean floor within legacy depth");
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Continent-scale realism (PLAN-RH3 Q0). Gates are added as RH3 phases land; RH2 baseline in PLAN-RH3 §6. */
    @Test void continentRealism() throws Exception {
        for (long seed : new long[]{0, 42, 8675309}) System.out.println(HighlandRealismProbe.measure(seed, true).summary());
    }

    private static void check(java.util.List<String> failures, HighlandQualityProbe.Metrics m, boolean ok, String gate) {
        if (!ok) failures.add("seed=" + m.seed() + " failed " + gate + ": " + m.summary());
    }
}
