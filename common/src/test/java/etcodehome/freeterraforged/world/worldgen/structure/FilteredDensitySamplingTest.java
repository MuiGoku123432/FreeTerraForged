package etcodehome.freeterraforged.world.worldgen.structure;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class FilteredDensitySamplingTest {
    @Test
    @Timeout(60)
    void parallelPreparationAndCommonPoolFallbackMatchSerialFilters() throws Exception {
        for (long seed : new long[]{42, 8675309}) {
            try (var serial = StructureTestContext.create(seed); var prepared = StructureTestContext.create(seed)) {
                for (int[] region : new int[][]{{6304, 5744}, {-8, -8}}) {
                    var grid = new RegionFilterGrid();
                    grid.init(region[0], region[1], serial);
                    serial.filters.apply(grid);
                    for (boolean commonPoolCaller : new boolean[]{false, true}) {
                        prepared.filteredChunkCache.clear();
                        prepared.rawChunkCache.clear();
                        if (commonPoolCaller) {
                            CompletableFuture.runAsync(() -> {
                                if (ForkJoinPool.getCommonPoolParallelism() > 1) {
                                    assertSame(ForkJoinPool.commonPool(), ForkJoinTask.getPool());
                                }
                                prepared.filteredChunkCache.getFilteredChunk(region[0], region[1]);
                            }, ForkJoinPool.commonPool()).get(30, TimeUnit.SECONDS);
                        }
                        for (int cz = 0; cz < 4; cz++) for (int cx = 0; cx < 4; cx++) {
                            var expected = grid.extractChunkCells(cx, cz);
                            var actual = prepared.filteredChunkCache.getFilteredChunk(region[0] + cx, region[1] + cz);
                            for (var field : Cell.class.getFields()) {
                                if (Modifier.isStatic(field.getModifiers())) continue;
                                for (int i = 0; i < expected.length; i++) {
                                    Object before = field.get(expected[i]);
                                    Object after = field.get(actual[i]);
                                    if (before instanceof Terrain terrain) {
                                        before = terrain.getName();
                                        after = ((Terrain) after).getName();
                                    }
                                    assertEquals(before, after, field.getName());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @Timeout(60)
    void coldQueriesFromAnotherForkJoinPoolCompleteWithoutReenteringTheirRegion() throws Exception {
        var workers = new ForkJoinPool(4);
        try (var context = StructureTestContext.create(42)) {
            var results = new ArrayList<Future<?>>();
            // Minecraft uses a separate ForkJoinPool. Contending cold queries must
            // finish without a waiting worker executing a query for its own region.
            for (int repeat = 0; repeat < 8; repeat++) for (int region = 0; region < 4; region++) {
                int x = 6304 + region * 4;
                results.add(workers.submit(() -> {
                    var cells = context.filteredChunkCache.getFilteredChunk(x, 5744);
                    assertNotNull(cells);
                    assertSame(cells, context.filteredChunkCache.getFilteredChunk(x, 5744));
                }));
            }
            for (var result : results) result.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

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
