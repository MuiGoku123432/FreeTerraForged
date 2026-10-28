package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;

class HighlandDeterminismTest {
    private static Preset preset(RollingHighlandsSettings settings) {
        var p = Presets.makeFTFDefault(); p.world().continent.continentType = ContinentType.ROLLING_HIGHLANDS;
        p.terrain().rollingHighlands = settings; return p;
    }
    @Test void orderConcurrencyRebuildAndOwnerIsolation() throws Exception {
        var p = preset(RollingHighlandsSettings.DEFAULT);
        try (var context = WorldgenFixture.context(p, 0); var other = WorldgenFixture.context(p.copy(), 42);
             var executor = Executors.newFixedThreadPool(4)) {
            float[] reference = new float[128];
            for (int i = 0; i < reference.length; i++) {
                Cell c = new Cell(); context.heightmap.apply(c, 5000 + i * 11, 6000 + i * 7, false); reference[i] = c.height;
            }
            var futures = new ArrayList<Future<?>>();
            for (int i = reference.length - 1; i >= 0; i--) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    Cell c = new Cell(); other.heightmap.apply(c, 5000 + index * 11, 6000 + index * 7, false);
                    context.heightmap.apply(c.reset(), 5000 + index * 11, 6000 + index * 7, false);
                    assertEquals(Float.floatToIntBits(reference[index]), Float.floatToIntBits(c.height));
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
            try (var rebuilt = WorldgenFixture.context(p.copy(), 0)) {
                for (int i = 0; i < reference.length; i++) {
                    Cell c = new Cell(); rebuilt.heightmap.apply(c, 5000 + i * 11, 6000 + i * 7, false);
                    assertEquals(Float.floatToIntBits(reference[i]), Float.floatToIntBits(c.height));
                }
            }
        }
    }
    @Test void filteredRegionsAgreeAcrossBoundaryAndProtectWater() {
        try (var context = WorldgenFixture.context(preset(RollingHighlandsSettings.DEFAULT), 0)) {
            var left = context.filteredChunkCache.getFilteredChunk(319, 384);
            var right = context.filteredChunkCache.getFilteredChunk(320, 384);
            for (int z = 0; z < 16; z++) {
                var a = ChunkFilterGrid.getPaddedCell(left, 15, z);
                var b = ChunkFilterGrid.getPaddedCell(right, -1, z);
                assertEquals(Float.floatToIntBits(a.height), Float.floatToIntBits(b.height));
                assertEquals(a.hasRiverWater, b.hasRiverWater);
                if (b.hasRiverWater) assertTrue(b.height <= b.riverWaterLevel);
            }
        }
    }
    /**
     * The Meanders setting scales how far channels wind around their valley axes (channel length over axis length):
     * 0 keeps them on the axis without oxbows; 200 stays crossing-free and inside the belt.
     */
    @Test void meanderStrengthControlsChannelLength() {
        double[] ratio = new double[3];
        int[] strengths = {0, 100, 200};
        for (int i = 0; i < 3; i++) {
            try (var context = WorldgenFixture.context(preset(RollingHighlandsSettings.DEFAULT.withMeanders(strengths[i])), 0)) {
                var plan = ((RollingHighlandsContinent) context.heightmap.continent()).planOf(0, 0).plan();
                ratio[i] = HighlandPlanformProbe.channelToAxis(plan);
                if (i == 0) assertEquals(0, plan.oxbows().size());
                if (i == 2) {
                    assertEquals(0, HighlandPlanformProbe.crossings(plan.nodes()));
                    assertEquals(0, HighlandPlanformProbe.outsideBelt(plan));
                }
            }
        }
        assertTrue(ratio[0] < 1.01 && ratio[0] < ratio[1] - .005 && ratio[1] < ratio[2] - .01, java.util.Arrays.toString(ratio));
    }
    /** Valley width only reshapes floors: the drainage plan and water profile are unchanged. */
    @Test void valleyWidthKeepsTheChannelProfile() {
        var settings = new RollingHighlandsSettings[]{RollingHighlandsSettings.DEFAULT,
            new RollingHighlandsSettings(RollingHighlandsSettings.MODEL_VERSION, 4096, 80, 100, 64), new RollingHighlandsSettings(RollingHighlandsSettings.MODEL_VERSION, 4096, 80, 100, 512)};
        double siteX, siteZ;
        try (var context = WorldgenFixture.context(preset(RollingHighlandsSettings.DEFAULT), 0)) {
            var plan = ((RollingHighlandsContinent) context.heightmap.continent()).planOf(0, 0).plan();
            var site = plan.nodes().stream().filter(n -> n.water() > 20 && n.parent() >= 0).findFirst().orElseThrow();
            siteX = site.x(); siteZ = site.z();
        }
        float[] reference = null;
        for (var s : settings) {
            try (var context = WorldgenFixture.context(preset(s), 0)) {
                float[] values = new float[3];
                for (int i = 0; i < values.length; i++) {
                    Cell c = new Cell(); context.heightmap.applyPrecise(c, siteX, siteZ + i - 1, false);
                    assertTrue(c.hasRiverWater); values[i] = c.riverWaterLevel;
                }
                if (reference == null) reference = values; else assertArrayEquals(reference, values);
            }
        }
    }
}
