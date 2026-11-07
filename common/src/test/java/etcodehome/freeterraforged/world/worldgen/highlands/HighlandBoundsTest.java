package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;

class HighlandBoundsTest {
    @org.junit.jupiter.api.Test void preciseCoastBoundsContainFarAndNegativeOwners() {
        var p = Presets.makeFTFDefault();
        try (var context = WorldgenFixture.context(p,42); var coast = new HighlandCoast(context)) {
            var random = new java.util.Random(731993);
            var boxes = new java.util.HashMap<String, HighlandCoast.Bounds>();
            for (int i=0; i<2000; i++) {
                double x = (i % 2 == 0 ? 29000000 : -29000000) + random.nextInt(64000);
                double z = -16000 + random.nextInt(32000);
                var site = coast.sample(x,z);
                var box = boxes.computeIfAbsent(site.ownerX()+","+site.ownerZ(), k -> coast.bounds(site.ownerX(),site.ownerZ()));
                assertTrue(x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ());
            }
        }
    }
    /** Gate seeds: the first ten and the two seeds used across the highlands tests. */
    static final long[] GATE_SEEDS = java.util.stream.LongStream.concat(java.util.stream.LongStream.range(0, 10), java.util.stream.LongStream.of(8675309, -314159)).toArray();
    /** The rest of the 52-seed sweep, run on demand with probeTest (about 5 min). */
    static final long[] SWEEP_SEEDS = java.util.stream.LongStream.range(10, 50).toArray();

    @ParameterizedTest @CsvSource({"1000,4096", "1000,8192", "3000,4096", "4000,4096", "4000,8192", "5000,4096", "5000,8192"})
    void supportedDomainBounds(int continentScale, int spacing) {
        checkDomainBounds(continentScale, spacing, GATE_SEEDS);
    }

    @ParameterizedTest @CsvSource({"1000,4096", "1000,8192", "3000,4096", "4000,4096", "4000,8192", "5000,4096", "5000,8192"})
    @org.junit.jupiter.api.Tag("slow")
    void supportedDomainBoundsSweep(int continentScale, int spacing) {
        checkDomainBounds(continentScale, spacing, SWEEP_SEEDS);
    }

    private static void checkDomainBounds(int continentScale, int spacing, long[] seeds) {
        for (long seed : seeds) {
            var p = Presets.makeFTFDefault(); p.world().continent.continentScale = continentScale;
            new RollingHighlandsSettings(RollingHighlandsSettings.MODEL_VERSION, spacing, 80, 100, 256).validate(p.world());
            try (var context = WorldgenFixture.context(p, seed); var coast = new HighlandCoast(context)) {
                var planner = new HighlandDrainage(coast, HighlandLand.create(context), spacing, p.world().controlPoints);
                var plan = planner.build(0, 0);
                assertTrue(plan.gridStep() <= spacing / 8);
                new HighlandPlanIndex(plan);
            }
        }
    }
}
