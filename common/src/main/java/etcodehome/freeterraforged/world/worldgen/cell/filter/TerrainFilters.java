package etcodehome.freeterraforged.world.worldgen.cell.filter;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainCeiling;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

public class TerrainFilters {
    private final boolean rollingHighlands;
    private final CliffRelaxation cliffRelaxation1;
    private final Erosion erosion;
    private final CliffRelaxation cliffRelaxation2;
    private final BeachDetect beachDetect;
    private final Steepness steepness;
    private final TerrainCeiling terrainCeiling;

    public TerrainFilters(GeneratorContext context) {
        this.rollingHighlands = context.heightmap != null && context.heightmap.continent() instanceof etcodehome.freeterraforged.world.worldgen.highlands.RollingHighlandsContinent;
        this.cliffRelaxation1 = CliffRelaxation.factory(context, 1).apply(0);
        this.erosion = Erosion.factory(context).apply(0);
        this.cliffRelaxation2 = CliffRelaxation.factory(context, 2).apply(0);
        this.beachDetect = BeachDetect.make(context);
        this.steepness = Steepness.make(1, 10.0F, context.levels);
        if (context.preset != null && TerrainCeiling.isEnabled(context.preset)) {
            this.terrainCeiling = TerrainCeiling.make(context.preset.world().properties);
        } else {
            this.terrainCeiling = null;
        }
    }

    public void apply(FilterGrid grid) {
        if (this.rollingHighlands) {
            Cell[] cells = grid.getBacking();
            float[] before = new float[cells.length];
            for (int i = 0; i < cells.length; i++) before[i] = cells[i].height;
            WorldgenPerfTracker.ThreadStats stats = WorldgenPerfTracker.ACTIVE ? WorldgenPerfTracker.stats() : null;
            long start = stats == null ? 0 : System.nanoTime();
            this.cliffRelaxation1.apply(grid, grid.getBlockX(), grid.getBlockZ(), 1);
            long afterCliff = stats == null ? 0 : System.nanoTime();
            this.erosion.apply(grid, grid.getBlockX(), grid.getBlockZ(), 1);
            long afterErosion = stats == null ? 0 : System.nanoTime();
            this.cliffRelaxation2.apply(grid, grid.getBlockX(), grid.getBlockZ(), 1);
            if (stats != null) {
                stats.filterCliff1Nanos += afterCliff - start;
                stats.filterErosionNanos += afterErosion - afterCliff;
                stats.filterCliff2Nanos += System.nanoTime() - afterErosion;
            }
            for (int i = 0; i < cells.length; i++) {
                Cell c = cells[i];
                double strength = c.hasRiverWater ? 0 : etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.smooth(0, .5, c.riverMask);
                c.height = before[i] + (float) ((c.height - before[i]) * strength);
            }
            this.beachDetect.apply(grid, grid.getBlockX(), grid.getBlockZ(), 1);
            this.steepness.apply(grid, grid.getBlockX(), grid.getBlockZ(), 1);
            return;
        }
        int seedX = grid.getBlockX();
        int seedZ = grid.getBlockZ();

        if (!WorldgenPerfTracker.ACTIVE) {
            // 1. Pre-erosion cliff relaxation: smooths spikes/pits and records preErosionHeight
            this.cliffRelaxation1.apply(grid, seedX, seedZ, 1);

            // 2. Procedural Phacelle erosion: computes sediment, heightErosion, and eroded height
            this.erosion.apply(grid, seedX, seedZ, 1);

            // 3. Post-erosion cliff relaxation: softens sharp erosion gullies
            this.cliffRelaxation2.apply(grid, seedX, seedZ, 1);

            // 4. Beach detection: marks coastal cells as BEACH where slope is low
            this.beachDetect.apply(grid, seedX, seedZ, 1);

            // 5. Steepness: computes cell.gradient
            this.steepness.apply(grid, seedX, seedZ, 1);

            // 6. Terrain ceiling compression (if enabled)
            if (this.terrainCeiling != null) {
                for (Cell cell : grid.getBacking()) {
                    cell.height = this.terrainCeiling.apply(cell.height);
                }
            }
            return;
        }

        WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
        long t0 = System.nanoTime();
        this.cliffRelaxation1.apply(grid, seedX, seedZ, 1);
        long t1 = System.nanoTime();
        ts.filterCliff1Nanos += (t1 - t0);

        this.erosion.apply(grid, seedX, seedZ, 1);
        long t2 = System.nanoTime();
        ts.filterErosionNanos += (t2 - t1);

        this.cliffRelaxation2.apply(grid, seedX, seedZ, 1);
        long t3 = System.nanoTime();
        ts.filterCliff2Nanos += (t3 - t2);

        this.beachDetect.apply(grid, seedX, seedZ, 1);
        long t4 = System.nanoTime();
        ts.filterBeachNanos += (t4 - t3);

        this.steepness.apply(grid, seedX, seedZ, 1);
        long t5 = System.nanoTime();
        ts.filterSteepnessNanos += (t5 - t4);

        if (this.terrainCeiling != null) {
            for (Cell cell : grid.getBacking()) {
                cell.height = this.terrainCeiling.apply(cell.height);
            }
            ts.filterCeilingNanos += (System.nanoTime() - t5);
        }
    }
}
