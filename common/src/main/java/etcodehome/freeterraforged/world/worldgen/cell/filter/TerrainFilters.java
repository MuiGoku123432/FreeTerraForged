package etcodehome.freeterraforged.world.worldgen.cell.filter;

import java.util.Set;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainCeiling;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

public class TerrainFilters {
    private final boolean rollingHighlands;
    private final CliffRelaxation cliffRelaxation1;
    private final Erosion erosion;
    private final CliffRelaxation cliffRelaxation2;
    private final BeachDetect beachDetect;
    private final Steepness steepness;
    private final TerrainCeiling terrainCeiling;
    /** One block in normalized cell height units (RH5 valley-floor guard). */
    private final float blockHeight;
    private final float ground;

    public TerrainFilters(GeneratorContext context) {
        this.rollingHighlands = context.heightmap != null && context.heightmap.continent() instanceof etcodehome.freeterraforged.world.worldgen.highlands.RollingHighlandsContinent;
        this.cliffRelaxation1 = CliffRelaxation.factory(context, 1).apply(0);
        this.erosion = Erosion.factory(context).apply(0);
        this.cliffRelaxation2 = CliffRelaxation.factory(context, 2).apply(0);
        this.beachDetect = BeachDetect.make(context);
        this.steepness = Steepness.make(1, 10.0F, context.levels);
        this.blockHeight = context.levels == null ? 0 : 1F / context.levels.terrainScaleFactor;
        this.ground = context.levels.ground;
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
                // RH5: erosion never digs dry valley ground below one block above the water of the channel owning it
                // (a pit there would fill with sea-level or spilled water beside the river).
                if (!c.hasRiverWater && c.riverZone != etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings.RiverZone.None) {
                    c.height = Math.max(c.height, Math.min(before[i], c.riverWaterLevel + this.blockHeight));
                }
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

	/**
	 * Returns whether filtering can change whether a raw cell matches {@code target}.
	 *
	 * <p>Keeping this contract beside the pipeline makes raw locate sampling an explicit optimization rather than an
	 * assumption hidden inside the command. Any future filter that mutates terrain identity must extend this method.
	 */
	public boolean affectsTerrainIdentity(Terrain target) {
		return target == TerrainType.COAST || target == TerrainType.BEACH;
	}

	/**
	 * Returns whether a raw cell is equivalent to its filtered form for biome locate.
	 *
	 * <p>The locate query always consumes height (to choose the surface Y), biome-region ownership, and whether terrain
	 * is a river. Its climate sampler may consume additional {@link CellSampler.Field fields}. Below ground, both cliff
	 * passes and erosion have a zero height modifier. The remaining filters either populate derived fields, convert a
	 * coast to a beach, or compress only high terrain. This method keeps that equivalence proof beside the production
	 * filter pipeline so future filters and fields must extend the same contract.
	 */
	public boolean preservesRawBiomeLocateInputs(Cell cell, Set<CellSampler.Field> sampledFields) {
		if (cell.height > this.ground) {
			return false;
		}
		if (this.terrainCeiling != null
			&& Float.floatToIntBits(this.terrainCeiling.apply(cell.height)) != Float.floatToIntBits(cell.height)) {
			return false;
		}
		for (CellSampler.Field field : sampledFields) {
			switch (field) {
				case GRADIENT, HEIGHT_EROSION, SEDIMENT -> {
					return false;
				}
				case CONTINENT -> {
					if (cell.terrain.isCoast()) {
						return false;
					}
				}
				case HEIGHT, CONTINENT_EDGE, EROSION, TERRAIN_EROSION, WEIRDNESS,
					BIOME_REGION, TEMPERATURE, MOISTURE -> {
				}
			}
		}
		return true;
	}
}
