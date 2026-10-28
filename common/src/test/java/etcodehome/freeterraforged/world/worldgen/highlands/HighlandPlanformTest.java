package etcodehome.freeterraforged.world.worldgen.highlands;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * River planform (PLAN-RH4). Reports sinuosity, routing direction bias, junction angles, confluence tongue width and
 * channel crossings for the default preset (seeds 0, 42, 8675309) and the user's Test-America-v3 world, and renders
 * hillshades (build/rolling-highlands/planform-*.png). Gates are added as RH4 phases land.
 */
class HighlandPlanformTest {
    @Test void planform() throws Exception {
        var failures = new ArrayList<String>();
        var results = new ArrayList<HighlandPlanformProbe.Metrics>();
        for (long seed : new long[]{0, 42, 8675309}) {
            results.add(HighlandPlanformProbe.measureOrigin("seed=" + seed, HighlandQualityProbe.preset(), seed));
        }
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            results.add(HighlandPlanformProbe.measure("user", context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z));
            HighlandPlanformProbe.render(context, 4650, 9480, 6144, 4, Path.of("build/rolling-highlands/planform-user-zoom.png"));
            HighlandPlanformProbe.render(context, 4650, 9480, 24576, 16, Path.of("build/rolling-highlands/planform-user.png"));
        }
        for (var m : results) {
            System.out.println(m.summary());
            if (!Double.isFinite(m.sinuosity()) || !Double.isFinite(m.junctionAngle())) failures.add("non-finite metrics: " + m.summary());
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
