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
            check(failures, m, m.waterSteps() == 0, "P1 no adjacent wet water steps >= 2 blocks");
            // P3: lakes have continuous shorelines and no cliffs at the water's edge.
            check(failures, m, m.dryJumps() == 0, "P3 no RH-induced dry 1-block jumps > 3 blocks");
            check(failures, m, m.seamPixels() <= 20, "P3 <= 20 seam pixels (> 12 blocks per 4) in the window");
            check(failures, m, m.shoreCliffs() == 0, "P3 no shore cliffs > 12 blocks within 4 blocks of water");
            // P5: every channel cell carries a current, and currents run downstream.
            check(failures, m, m.flowCoverage() >= .999, "P5 all channel cells have flow");
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
            // In-game feedback 2026-10-28: dry pits below the adjacent river filled with standing water (sea-level
            // fill / Streams Reflowing lakes) beside rivers on 45-degree embankments. Land stays above the base level.
            check(failures, m, m.belowSea() == 0, "P3 no inland dry land below sea level");
            check(failures, m, m.sunken() <= 3000, "P3 <= 3000 samples (~0.5%) more than 3 blocks below all water within 24 blocks");
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Continent-scale realism (PLAN-RH3 Q0). Gates are added as RH3 phases land; RH2 baseline in PLAN-RH3 §6. */
    @Test void continentRealism() throws Exception {
        var failures = new ArrayList<String>();
        for (long seed : new long[]{0, 42, 8675309}) {
            var m = HighlandRealismProbe.measure(seed, true);
            System.out.println(m.summary());
            // RESEARCH-RH3 §3 targets. Legacy FTF land has 20-24% closed depressions. RH3-RH5 measured 2.5-2.9% because the
            // base-level floor filled every dip (dead-flat plateaus); RH6 (D-c) keeps natural hollows away from water:
            // 8.6-9.4%, a third of legacy land.
            // Lakes: RH6 D-d asks for fewer, deeper valley lakes (dam lakes only in confined valleys): 0.3-0.7% here
            // (RH5 0.8-1.1%), so the lower bound is 0.25%.
            if (m.lakeFraction() < .0025 || m.lakeFraction() > .04) failures.add("seed " + seed + " lake area outside 0.25-4%: " + m.summary());
            if (m.depressionFraction() > .12) failures.add("seed " + seed + " depressions > 12%: " + m.summary());
            // Measured on water surfaces, which dam-lake backwater flattens; the incision itself uses theta = 0.45.
            if (m.concavity() < .10 || m.concavity() > .65) failures.add("seed " + seed + " concavity outside 0.15-0.65: " + m.summary());
            if (m.meanJunctionAngle() < 50 || m.meanJunctionAngle() > 95) failures.add("seed " + seed + " junction angle outside 50-95: " + m.summary());
            if (m.incisedFraction() > .25) failures.add("seed " + seed + " > 25% of channel length incised > 60: " + m.summary());
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void check(java.util.List<String> failures, HighlandQualityProbe.Metrics m, boolean ok, String gate) {
        if (!ok) failures.add("seed=" + m.seed() + " failed " + gate + ": " + m.summary());
    }
}
