package etcodehome.freeterraforged.world.worldgen.highlands;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * River planform (PLAN-RH4). Reports sinuosity, routing direction bias, junction angles, confluence tongue width and
 * channel crossings for the default preset (seeds 0, 42, 8675309) and the user's Test-America-v3 world, and renders
 * hillshades (build/rolling-highlands/planform-*.png). Gates are added as RH4 phases land (R3: belt containment,
 * sinuosity, anisotropy, oxbows present; R4: junction angles, no crossings;
 * R5: rounded confluence tips).
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
            // R3: meandering channels stay inside their valley's meander belt (and so on its floor).
            if (m.outsideBelt() > 0) failures.add("channel outside its meander belt: " + m.summary());
            if (m.sinuosity() < 1.15 || m.sinuosity() > 1.65) failures.add("sinuosity outside 1.15-1.65: " + m.summary());
            if (m.anisotropy() > .08) failures.add("direction anisotropy > 8%: " + m.summary());
            // R4: tributaries meet their main channel at natural angles, and no two channels cross.
            if (m.junctionAngle() < 60 || m.junctionAngle() > 80) failures.add("junction angle mean outside 60-80 deg: " + m.summary());
            if (m.acuteShare() > .1) failures.add("more than 10% of junctions under 25 deg: " + m.summary());
            if (m.crossings() > 0) failures.add("channels cross: " + m.summary());
            // R5: rounded confluence apex (dry width 2 blocks behind the tip).
            if (m.tongueMedian() < 8 || m.narrowTongueShare() > .05) failures.add("confluence tips too sharp: " + m.summary());
        }
        if (results.stream().mapToInt(HighlandPlanformProbe.Metrics::oxbows).sum() == 0) failures.add("no oxbows in any test continent");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
