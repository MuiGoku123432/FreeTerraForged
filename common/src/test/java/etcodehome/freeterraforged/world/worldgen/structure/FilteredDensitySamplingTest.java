package etcodehome.freeterraforged.world.worldgen.structure;

import static org.junit.jupiter.api.Assertions.*;

import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.ArrayList;

class FilteredDensitySamplingTest {
    @Test
    void concurrentColdQueriesAndCacheClearsRetainCoordinateAndWorldOwnership() throws Exception {
        try (var first = StructureTestContext.create(42); var second = StructureTestContext.create(8675309);
             var workers = Executors.newFixedThreadPool(4)) {
            int[][] points = {{63, 63}, {64, 64}, {-65, -65}, {100003, 100007}};
            var contexts = java.util.List.of(first, second);
            double[][] expected = new double[2][points.length];
            for (int owner = 0; owner < contexts.size(); owner++) {
                for (int i = 0; i < points.length; i++) {
                    expected[owner][i] = contexts.get(owner).filteredChunkCache.getCell(points[i][0], points[i][1]).height;
                }
                contexts.get(owner).filteredChunkCache.clear();
            }
            var tasks = new ArrayList<Callable<Void>>();
            for (int repeat = 0; repeat < 4; repeat++) for (int owner = 0; owner < 2; owner++) {
                int world = owner;
                tasks.add(() -> {
                    var context = contexts.get(world);
                    var sampler = new CellSampler(() -> context.lookup, CellSampler.Field.HEIGHT);
                    var density = sampler.new CacheChunk((CellSampler.Cache2d) null);
                    for (int i = points.length - 1; i >= 0; i--) {
                        assertEquals(expected[world][i], density.compute(
                            new DensityFunction.SinglePointContext(points[i][0], 80, points[i][1])), 0.0);
                    }
                    return null;
                });
            }
            for (var result : workers.invokeAll(tasks)) result.get();
            first.filteredChunkCache.clear();
            for (int i = 0; i < points.length; i++) {
                assertEquals(expected[0][i], first.filteredChunkCache.getCell(points[i][0], points[i][1]).height, 0.0);
            }
        }
    }

    @Test
    void coldDensityQueryUsesTheSameFilteredHeightAsTheGeneratedChunk() {
        try (var context = StructureTestContext.create(42)) {
            var sampler = new CellSampler(() -> context.lookup, CellSampler.Field.HEIGHT);
            var density = sampler.new CacheChunk((CellSampler.Cache2d) null);
            for (int[] point : new int[][]{{100003, 100007}, {-65, -1}, {63, 64}, {-24000, -45000}}) {
                context.filteredChunkCache.clear();
                int x = point[0], z = point[1];
                double cold = density.compute(new DensityFunction.SinglePointContext(x, 80, z));
                var cells = context.filteredChunkCache.getFilteredChunk(x >> 4, z >> 4);
                double expected = ChunkFilterGrid.getPaddedCell(cells, x & 15, z & 15).height;
                assertEquals(expected, cold, 0.0, "cold lookup at " + x + "," + z);
            }
        }
    }

    @Test
    void paddedRegionBoundaryIsOwnedByTheSampledCoordinate() {
        try (var context = StructureTestContext.create(42)) {
            var sampler = new CellSampler(() -> context.lookup, CellSampler.Field.HEIGHT);
            var cells = context.filteredChunkCache.getFilteredChunk(3, 0);
            // Poison only the non-owning padded copy to make a wrong ownership decision visible.
            ChunkFilterGrid.getPaddedCell(cells, 16, 4).height = -123.0F;
            var density = sampler.new CacheChunk(cells, 48, 0, null);
            double actual = density.compute(new DensityFunction.SinglePointContext(64, 80, 4));
            var owner = context.filteredChunkCache.getFilteredChunk(4, 0);
            assertEquals(ChunkFilterGrid.getPaddedCell(owner, 0, 4).height, actual, 0.0);
        }
    }
}
