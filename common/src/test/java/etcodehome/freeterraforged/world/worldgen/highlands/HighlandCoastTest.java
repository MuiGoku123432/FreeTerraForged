package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
        }
    }
}
