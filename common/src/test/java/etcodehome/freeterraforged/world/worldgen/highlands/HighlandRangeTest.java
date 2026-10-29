package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * RH5 M: mountain ranges. Ranges are long and continuous (crest lines several kilometres long), smooth (no jumps), off
 * at range height 0, and the landscape and water gates still hold with ranges on.
 */
class HighlandRangeTest {
    private static final int RANGE_HEIGHT = 140, RANGE_LENGTH = 6144;

    private static etcodehome.freeterraforged.data.worldgen.preset.settings.Preset withRanges() {
        var preset = HighlandQualityProbe.preset();
        preset.terrain().rollingHighlands = RollingHighlandsSettings.DEFAULT.withRanges(RANGE_HEIGHT, RANGE_LENGTH);
        return preset;
    }

    @Test void rangesAreLongContinuousAndOffAtZero() {
        try (var context = WorldgenFixture.context(withRanges(), 0); var flat = WorldgenFixture.context(HighlandQualityProbe.preset(), 0)) {
            var land = HighlandLand.create(context);
            var plain = HighlandLand.create(flat);
            var bounds = ((RollingHighlandsContinent) context.heightmap.continent()).coast().bounds(0, 0);
            int step = 64, w = (int) ((bounds.maxX() - bounds.minX()) / step), h = (int) ((bounds.maxZ() - bounds.minZ()) / step);
            boolean[] crest = new boolean[w * h];
            double worstStep = 0;
            Cell a = new Cell(), b = new Cell();
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
                double bx = bounds.minX() + x * step, bz = bounds.minZ() + z * step;
                double core = land.rangeCore(bx, bz);
                crest[z * w + x] = core > .85;
                // Range height 0 adds nothing; with ranges the added height is continuous block to block.
                assertEquals(plain.apply(b.reset(), bx, bz), plain.height(bx, bz), 1e-9);
                if (core > 0 && x % 4 == 0 && z % 4 == 0) {
                    double h0 = land.apply(a.reset(), bx, bz) - plain.apply(b.reset(), bx, bz);
                    double h1 = land.apply(a.reset(), bx + 1, bz) - plain.apply(b.reset(), bx + 1, bz);
                    worstStep = Math.max(worstStep, Math.abs(h1 - h0));
                }
            }
            // Longest crest: the largest bounding-box diagonal of an 8-connected crest component.
            boolean[] seen = new boolean[w * h];
            double longest = 0;
            var queue = new ArrayDeque<Integer>();
            for (int i = 0; i < w * h; i++) {
                if (!crest[i] || seen[i]) continue;
                int minX = Integer.MAX_VALUE, maxX = -1, minZ = Integer.MAX_VALUE, maxZ = -1;
                queue.add(i); seen[i] = true;
                while (!queue.isEmpty()) {
                    int v = queue.poll(), vx = v % w, vz = v / w;
                    minX = Math.min(minX, vx); maxX = Math.max(maxX, vx); minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = vx + dx, nz = vz + dz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                        int j = nz * w + nx;
                        if (crest[j] && !seen[j]) { seen[j] = true; queue.add(j); }
                    }
                }
                longest = Math.max(longest, Math.hypot(maxX - minX, maxZ - minZ) * step);
            }
            String summary = String.format(java.util.Locale.ROOT, "RHM longestCrest=%.0f worstStep=%.3f", longest, worstStep);
            System.out.println(summary);
            assertTrue(longest >= 4000, summary);
            assertTrue(worstStep < 1.5, summary);
        }
    }

    @Test void rangeCrestsReadAsMountainsAndWaterGatesHold() throws Exception {
        var m = HighlandQualityProbe.measure(withRanges(), 0, false);
        System.out.println("ranges " + m.summary());
        assertEquals(0, m.waterSteps(), m.summary());
        assertEquals(0, m.dryJumps(), m.summary());
        assertEquals(0, m.shoreCliffs(), m.summary());
        assertEquals(0, m.belowSea(), m.summary());
        assertTrue(m.flowCoverage() >= .999, m.summary());
        assertTrue(m.positiveWeirdness() == 0, m.summary());
        try (var context = WorldgenFixture.context(withRanges(), 0)) {
            var land = HighlandLand.create(context);
            var plan = ((RollingHighlandsContinent) context.heightmap.continent()).planOf(0, 0).plan();
            assertFalse(plan.nodes().isEmpty(), "drainage plan fell back to no rivers");
            // A crest away from valleys is a mountain for biome selection.
            var bounds = ((RollingHighlandsContinent) context.heightmap.continent()).coast().bounds(0, 0);
            Cell c = new Cell();
            int crests = 0, mountains = 0;
            for (double x = bounds.minX(); x < bounds.maxX(); x += 96) for (double z = bounds.minZ(); z < bounds.maxZ(); z += 96) {
                if (land.rangeCore(x, z) < .97) continue;
                context.heightmap.apply(c.reset(), (int) x, (int) z, false);
                if (c.hasRiverWater || c.riverMask < .5 || c.continentEdge <= context.preset.world().controlPoints.coast) continue;
                crests++;
                if (c.terrain == TerrainType.MOUNTAIN_CHAIN) mountains++;
            }
            assertTrue(crests > 20 && mountains > .6 * crests, "crests=" + crests + " mountains=" + mountains);
        }
    }
}
