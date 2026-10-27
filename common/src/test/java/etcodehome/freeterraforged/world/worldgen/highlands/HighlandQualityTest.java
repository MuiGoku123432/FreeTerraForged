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
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void check(java.util.List<String> failures, HighlandQualityProbe.Metrics m, boolean ok, String gate) {
        if (!ok) failures.add("seed=" + m.seed() + " failed " + gate + ": " + m.summary());
    }
}
