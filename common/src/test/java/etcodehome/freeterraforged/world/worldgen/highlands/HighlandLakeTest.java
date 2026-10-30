package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * PLAN-RH6 L lake gates on the user's world (D-d: fewer, deeper valley lakes; RH4 measured 21 lakes, 0.92 km², mean
 * depth 3.3, 54% under 3 blocks with this probe; RH5 38 lakes, 2.82 km², 6.5, 31%, 108 stepped inlet cells, a
 * 139-block shore-parallel channel).
 */
class HighlandLakeTest {
    @Test void lakesAreValleyLakesWithCleanJunctions() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var m = HighlandLakeProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z, null);
            System.out.println(m.summary());
            var failures = new ArrayList<String>();
            // L1: count and area near RH4 (the user's reference, about 1.5 km² by the research's planned-field measure).
            if (m.lakes() < 15 || m.lakes() > 27) failures.add("lake count outside 15-27 (RH4 21 +-30%)");
            if (m.area() < .65e6 || m.area() > 1.6e6) failures.add("lake area outside 0.65-1.6 km2");
            if (m.meanDepth() < 6) failures.add("mean lake depth < 6");
            if (m.shallowShare() > .25) failures.add("more than 25% of lake area under 3 blocks deep");
            // L2/L3: one level at inlets; channels enter lakes instead of running beside them.
            if (m.inletSteps() > 0) failures.add("stream water steps against lake water");
            if (m.longestParallel() > 32) failures.add("a channel runs > 32 blocks within 12 blocks of a lake at another level");
            // L4: no constant-height shelf around lakes.
            if (m.rimAt1() > .3 || m.rimAt2() > .3) failures.add("more than 30% of shore columns on a shelf 1 or 2 above the water");
            assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + m.summary());
        }
    }
}
