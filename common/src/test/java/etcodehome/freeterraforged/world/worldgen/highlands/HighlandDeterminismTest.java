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
    @Test void independentAmplitudesAndWidthKeepTheChannelProfile() {
        var settings = new RollingHighlandsSettings[]{RollingHighlandsSettings.DEFAULT,
            new RollingHighlandsSettings(1,4096,240,48,256), new RollingHighlandsSettings(1,4096,160,80,256),
            new RollingHighlandsSettings(1,4096,160,48,512), new RollingHighlandsSettings(1,4096,32,0,64)};
        double siteX, siteZ;
        try (var context = WorldgenFixture.context(preset(RollingHighlandsSettings.DEFAULT), 0);
             var coast = new HighlandCoast(context)) {
            var plan = new HighlandDrainage(coast, new HighlandFields(0,4096),4096,context.preset.world().controlPoints).build(0,0);
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
