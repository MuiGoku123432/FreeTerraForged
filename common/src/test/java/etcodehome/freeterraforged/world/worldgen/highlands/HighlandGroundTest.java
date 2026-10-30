package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * PLAN-RH6 ground gates on the user's world. The server world matches Test-America-v3 without ranges (it was created
 * before the preset gained Range Height 120): heights and lake levels at the screenshot sites agree with the fixture.
 */
class HighlandGroundTest {
    @Test void groundIsHonest() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var m = HighlandGroundProbe.measure(context, Path.of("build/rolling-highlands/ground-user.png"));
            System.out.println(m.summary());
            var failures = new ArrayList<String>();
            // F3: no global floor; land is only held up beside water.
            if (m.lifted2() > .10) failures.add("more than 10% of land lifted > 2 blocks");
            // F2/F3: streams sit in the valley they drain. Land beside a stream (compared with its nearest stream column)
            // is not below the water; anywhere within 48 blocks, nothing lies far below it. ("Any" land > 2 below within
            // 48 blocks also counts banks a little downstream of steep reaches: ~12% overall, ~4% on gentle reaches;
            // RH5 22%.)
            if (m.beside2() > .05) failures.add("more than 5% of stream columns have land beside them > 2 below the water");
            if (m.beside5() > .005) failures.add("more than 0.5% of stream columns have land beside them > 5 below the water");
            if (m.elevated5() > .005) failures.add("more than 0.5% of stream columns have land > 5 below them within 48 blocks");
            if (m.elevated2() > .15) failures.add("more than 15% of stream columns have land > 2 below them within 48 blocks");
            if (m.deadFlatMax() > .15) failures.add("dead-flat share > 15% at a screenshot site");
            if (!(m.pillow() >= .9)) failures.add("grid pillows: node slope / centre slope < 0.9");
            assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + m.summary());
        }
    }
}
