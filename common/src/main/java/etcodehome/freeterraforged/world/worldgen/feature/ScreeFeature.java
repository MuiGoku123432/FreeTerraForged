package etcodehome.freeterraforged.world.worldgen.feature;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import etcodehome.freeterraforged.tags.FTFBlockTags;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.highlands.HighlandCoastProfile;
import etcodehome.freeterraforged.world.worldgen.highlands.HighlandSurface;
import etcodehome.freeterraforged.world.worldgen.feature.ScreeFeature.Config;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ScreeFeature extends Feature<Config> {

    // Internal fixed modifiers
    private static final float SEDIMENT_NOISE = 3F / 255F;
    private static final float SCREE_VALUE = 0.55F;

    // Noise sampling parameters
    private static final float CLUSTER_SCALE = 0.05F;
    private static final float WARP_SCALE = 0.04F;
    private static final float WARP_STRENGTH = 22.0F;

    // Internal Mixture Matrices
    private static final WeightedBlockSelector SCREE_MATERIALS = new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.GRAVEL.defaultBlockState(), 1),
            new WeightedBlockEntry(Blocks.COARSE_DIRT.defaultBlockState(), 1),
            new WeightedBlockEntry(Blocks.ANDESITE.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.TUFF.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.MOSS_BLOCK.defaultBlockState(), 1)
    ));

    private static final WeightedBlockSelector DIRT_MATERIALS = new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.COARSE_DIRT.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.MOSS_BLOCK.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.GRAVEL.defaultBlockState(), 1)
    ));

    public ScreeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> placeContext) {
        WorldGenLevel level = placeContext.level();
        RandomState randomState = level.getLevel().getChunkSource().randomState();

        @Nullable
        GeneratorContext generatorContext;
        if((Object) randomState instanceof FTFRandomState ftfRandomState && (generatorContext = ftfRandomState.generatorContext()) != null) {
            ChunkPos chunkPos = new ChunkPos(placeContext.origin());
            int chunkX = chunkPos.x;
            int chunkZ = chunkPos.z;
            ChunkGenerator generator = placeContext.chunkGenerator();
            ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
            etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap heightmap = generatorContext.heightmap;
            Levels levels = heightmap.levels();

            ScreeNoises noises = generatorContext.screeNoises;
            Noise rand = noises != null ? noises.rand : Noises.white((int) heightmap.climate().randomSeed(), 1);
            Noise clusterNoise = noises != null ? noises.clusterNoise : Noises.perlin((int) (heightmap.climate().randomSeed() + 7777), 2, 1);
            Noise warpX = noises != null ? noises.warpX : Noises.perlin((int) (heightmap.climate().randomSeed() + 1001), 2, 1);
            Noise warpZ = noises != null ? noises.warpZ : Noises.perlin((int) (heightmap.climate().randomSeed() + 2002), 2, 1);
            Noise desertErosionVariance = noises != null ? noises.desertErosionVariance : makeDesertErosionVariance(levels);
            BlockPos.MutableBlockPos pos = new MutableBlockPos();
            Config config = placeContext.config();
            IChunkCellsHolder cellsHolder = chunk instanceof IChunkCellsHolder h ? h : null;
            Cell[] chunkCells = cellsHolder != null ? cellsHolder.freeterraforged$getOrCreateChunkCells(generatorContext) : null;
            for(int z = 0; z < 16; z++) {
                for(int x = 0; x < 16; x++) {
                    int worldX = chunkPos.getBlockX(x);
                    int worldZ = chunkPos.getBlockZ(z);

                    Cell cell = chunkCells != null ? chunkCells[z * 16 + x] : generatorContext.lookup.lookup(worldX, worldZ);
                    int scaledY = levels.scale(cell.height);
                    int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                    Holder<Biome> biome = level.getBiome(pos.set(worldX, surfaceY, worldZ));

                    pos.set(worldX, surfaceY, worldZ);

                    if (cell.rollingHighlands && (cell.highlandRange >= HighlandSurface.MIN_RANGE || cell.highlandCoast >= HighlandSurface.MIN_RANGE) && surfaceY <= scaledY
                        && surfaceY >= generator.getSeaLevel() - 1 && !cell.terrain.isRiver() && !cell.terrain.isWetland()) {
                        double slope = slope(generatorContext, levels, chunkCells, chunkPos, x, z);
                        var cover = HighlandSurface.choose(slope, surfaceY, levels.scale(cell.highlandSnowline), levels.scale(cell.highlandTreeline),
                            cell.highlandRange, cell.highlandCoast, 2 * clusterNoise.compute(worldX * .08F, worldZ * .08F, 31) - 1);
                        if (cover != HighlandSurface.Cover.BIOME) {
                            coverColumn(cover, rand, clusterNoise, warpX, warpZ, chunk, cell, pos, surfaceY);
                            pos.setY(surfaceY);
                            while(!level.getBlockState(pos.setY(pos.getY() + 1)).canSurvive(level, pos)) {
                                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                            }
                            continue;
                        }
                    }

                    // RH9 M: cool, wet (PNW) beaches are part gravel, in clusters; snowy beaches stay vanilla.
                    if (cell.rollingHighlands && cell.terrain == TerrainType.BEACH && cell.highlandCoastClimate == HighlandCoastProfile.PNW
                        && chunk.getBlockState(pos.setY(surfaceY)).is(Blocks.SAND) && clusterNoise.compute(worldX * .06F, worldZ * .06F, 47) > BEACH_GRAVEL) {
                        ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 2, Blocks.GRAVEL.defaultBlockState());
                        pos.setY(surfaceY);
                        continue;
                    }

                    if(biome.is(Biomes.DESERT)) {
                        erodeDesert(desertErosionVariance, levels, chunk, cell, pos, surfaceY);
                        continue;
                    }

                    if(surfaceY <= scaledY && surfaceY >= generator.getSeaLevel() - 1 && !biome.is(Biomes.WOODED_BADLANDS) && !biome.is(Biomes.BADLANDS)) {
                        if (chunkCells == null && cell.gradient == 0.0F) {
                            generatorContext.lookup.computeSteepness(cell, worldX, worldZ);
                        }
                        erodeColumn(config, levels, rand, clusterNoise, warpX, warpZ, generator, chunk, cell, pos, surfaceY);
                        // remove any foliage that may have generated above
                        pos.setY(surfaceY);
                        while(!level.getBlockState(pos.setY(pos.getY() + 1)).canSurvive(level, pos)) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
				return true;
        } else {
            return false;
        }
    }

    /** RH7 S: scree on range flanks (no soil or moss). */
    private static final WeightedBlockSelector RANGE_SCREE = new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.GRAVEL.defaultBlockState(), 3),
            new WeightedBlockEntry(Blocks.ANDESITE.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.COBBLESTONE.defaultBlockState(), 1),
            new WeightedBlockEntry(Blocks.TUFF.defaultBlockState(), 1)
    ));

    /**
     * RH9 M: sea-cliff and bluff rock by coast climate class (HighlandCoastProfile.CALIFORNIA, PNW, BAJA): sandstone
     * with a little terracotta; dark stone, andesite and tuff; red sandstone and orange/red terracotta.
     */
    private static final WeightedBlockSelector[] COAST_ROCK = {
        new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.SANDSTONE.defaultBlockState(), 4),
            new WeightedBlockEntry(Blocks.SMOOTH_SANDSTONE.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.TERRACOTTA.defaultBlockState(), 1))),
        new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.STONE.defaultBlockState(), 3),
            new WeightedBlockEntry(Blocks.ANDESITE.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.TUFF.defaultBlockState(), 2))),
        new WeightedBlockSelector(List.of(
            new WeightedBlockEntry(Blocks.RED_SANDSTONE.defaultBlockState(), 4),
            new WeightedBlockEntry(Blocks.ORANGE_TERRACOTTA.defaultBlockState(), 2),
            new WeightedBlockEntry(Blocks.RED_TERRACOTTA.defaultBlockState(), 1)))
    };
    /** Depth (blocks) of coastal rock cover; cluster noise above which a PNW beach column is gravel (about 30%). */
    private static final int COAST_ROCK_DEPTH = 4;
    private static final float BEACH_GRAVEL = .4F;

    /** RH7 S: surface slope (degrees) at a chunk column from the cell heights two blocks either side. */
    private static double slope(GeneratorContext context, Levels levels, @Nullable Cell[] cells, ChunkPos chunk, int x, int z) {
        int x0 = Math.max(0, x - 2), x1 = Math.min(15, x + 2), z0 = Math.max(0, z - 2), z1 = Math.min(15, z + 2);
        double dx = (blocks(context, levels, cells, chunk, x1, z) - blocks(context, levels, cells, chunk, x0, z)) / (x1 - x0);
        double dz = (blocks(context, levels, cells, chunk, x, z1) - blocks(context, levels, cells, chunk, x, z0)) / (z1 - z0);
        return Math.toDegrees(Math.atan(Math.hypot(dx, dz)));
    }
    private static double blocks(GeneratorContext context, Levels levels, @Nullable Cell[] cells, ChunkPos chunk, int x, int z) {
        Cell cell = cells != null ? cells[z * 16 + x] : context.lookup.lookup(chunk.getBlockX(x), chunk.getBlockZ(z));
        return (cell.height - levels.water) * levels.terrainScaleFactor;
    }

    /** RH7 S: writes the range cover chosen by HighlandSurface into the column's top blocks. */
    private static void coverColumn(HighlandSurface.Cover cover, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        BlockState top = chunk.getBlockState(pos.setY(surfaceY));
        if (top.isAir() || !top.getFluidState().isEmpty()) return;
        switch (cover) {
            case CLIFF -> {
                if (cell.highlandRange >= HighlandSurface.MIN_RANGE) erodeRock(chunk, cell, pos, surfaceY);
                else ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - COAST_ROCK_DEPTH, sampleMaterial(
                    COAST_ROCK[cell.highlandCoastClimate], pos.getX(), pos.getZ(), clusterNoise, warpX, warpZ, rand, 7, Blocks.STONE.defaultBlockState()));
            }
            case SCREE -> ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 2,
                sampleMaterial(RANGE_SCREE, pos.getX(), pos.getZ(), clusterNoise, warpX, warpZ, rand, 5, Blocks.GRAVEL.defaultBlockState()));
            case SNOW -> ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 2, Blocks.SNOW_BLOCK.defaultBlockState());
            case MEADOW -> {
                ColumnDecorator.fillDownSolid(chunk, pos, surfaceY - 1, surfaceY - 3, Blocks.DIRT.defaultBlockState());
                chunk.setBlockState(pos.setY(surfaceY), Blocks.GRASS_BLOCK.defaultBlockState(), false);
            }
            default -> {}
        }
    }

    @Deprecated(forRemoval = true)
    private static Noise makeDesertErosionVariance(Levels levels) {
        Noise noise = Noises.perlin(435, 8, 1);
        return Noises.mul(noise, levels.scale(16));
    }

    private static void erodeDesert(Noise variance, Levels levels, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        float min = levels.ground(10);
        float threshold = levels.ground(40);

        if (cell.gradient < 0.15F) {
            return;
        }

        if (cell.height < min) {
            return;
        }

        float value = cell.height + variance.compute(pos.getX(), pos.getZ(), 0);
        if (cell.gradient > 0.3F || value > threshold) {
            BlockState state = Blocks.SMOOTH_SANDSTONE.defaultBlockState();

            if (value > threshold) {
                if (cell.gradient > 0.975) {
                    state = Blocks.TERRACOTTA.defaultBlockState();
                } else if (cell.gradient > 0.85) {
                    state = Blocks.BROWN_TERRACOTTA.defaultBlockState();
                } else if (cell.gradient > 0.75) {
                    state = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
                } else if (cell.gradient > 0.65) {
                    state = Blocks.TERRACOTTA.defaultBlockState();
                }
            }

            for (int dy = 0; dy < 4; dy++) {
                chunk.setBlockState(pos.setY(surfaceY - dy), state, false);
            }
        }
    }

    private static void erodeColumn(Config config, Levels levels, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkGenerator generator, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        if (cell.terrain.isRiver() || cell.terrain.isWetland()) {
            return;
        }

        if (cell.terrain == TerrainType.VOLCANO_PIPE) {
            return;
        }

        BlockState top = chunk.getBlockState(pos);
        if(top.is(FTFBlockTags.ERODIBLE)) {
            BlockState material = getMaterial(config, levels, rand, clusterNoise, warpX, warpZ, generator, cell, pos, surfaceY, top, generator instanceof NoiseBasedChunkGenerator noiseChunkGenerator ? noiseChunkGenerator.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState());
            if (material != top) {
                // Treat TUFF as a decorative band (like Mossy Cobble) rather than running erodeRock on it
                if (material.is(FTFBlockTags.ROCK) && !material.is(Blocks.TUFF)) {
                    erodeRock(chunk, cell, pos, surfaceY);
                    return;
                } else {
                    ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 4, material);
                }
            }
            placeScree(config, rand, clusterNoise, warpX, warpZ, chunk, cell, pos, surfaceY);
        }
    }

    private static void erodeRock(ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int y) {
        int depth = 32;
        BlockState material = Blocks.GRAVEL.defaultBlockState();
        for (int dy = 3; dy < 32; dy++) {
            pos.setY(y - dy);
            BlockState state = chunk.getBlockState(pos);
            if (state.is(FTFBlockTags.ROCK)) {
                material = state;
                depth = dy + 1;
                break;
            }
        }

        for (int dy = 0; dy < depth; dy++) {
            ColumnDecorator.replaceSolid(chunk, pos.setY(y - dy), material);
        }
    }

    private static void placeScree(Config config, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkAccess chunk, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY) {
        int x = pos.getX();
        int z = pos.getZ();
        float steepness = cell.gradient + rand.compute(x, z, 1) * config.slopeModifier();
        if (steepness < config.screeSteepness()) {
            return;
        }

        float sediment = cell.sediment * SEDIMENT_NOISE;
        float noise = rand.compute(x, z, 2) * SEDIMENT_NOISE;
        if (sediment + noise > SCREE_VALUE) {
            BlockState chosenScree = sampleMaterial(SCREE_MATERIALS, x, z, clusterNoise, warpX, warpZ, rand, 3, Blocks.GRAVEL.defaultBlockState());
            ColumnDecorator.fillDownSolid(chunk, pos, surfaceY, surfaceY - 2, chosenScree);
        }
    }

    private static BlockState getMaterial(Config config, Levels levels, Noise rand, Noise clusterNoise, Noise warpX, Noise warpZ, ChunkGenerator generator, Cell cell, BlockPos.MutableBlockPos pos, int surfaceY, BlockState top, BlockState middle) {
        int x = pos.getX();
        int z = pos.getZ();
        float height = cell.height + rand.compute(x, z, 0) * config.heightModifier();
        float steepness = cell.gradient + rand.compute(x, z, 1) * config.slopeModifier();

        // Continental uplift offset in normalized (0.0 - 1.0) float space
        float upliftNormalized = ContinentalHydrology.getComplexWaterHeight(
                cell.waterTable,
                cell.globalContinentScale,
                cell.continentSizeModifier
        );

        // Uplift baseline in normalized float space (sea level + uplift)
        float upliftBaselineNormalized = cell.rollingHighlands ? cell.riverWaterLevel : levels.water + upliftNormalized;

        // Add config relative offsets (converted from 0..255 scale to 0.0..1.0 float scale)
        float effectiveRockBias = upliftBaselineNormalized + (config.rockMin() / 255.0f);
        float effectiveDirtBias = upliftBaselineNormalized + (config.dirtMin() / 255.0f);

        float rockVarFloat = config.rockVar() / 255.0f;
        float dirtVarFloat = config.dirtVar() / 255.0f;

        // Sample noise using the float overload (scale, bias)
        float rockThreshold = ColumnDecorator.sampleNoise(x, z, rockVarFloat, effectiveRockBias);
        float dirtThreshold = ColumnDecorator.sampleNoise(x, z, dirtVarFloat, effectiveDirtBias);

        // Compare normalized 'height' (0.0 - 1.0) against normalized thresholds
        if (steepness > config.rockSteepness() || height > rockThreshold) {
            return rock(middle);
        }

        if (steepness > config.screeSteepness() || height > rockThreshold) {
            return Blocks.TUFF.defaultBlockState();
        }

        if (steepness > config.dirtSteepness() && height > dirtThreshold) {
            return ground(config, clusterNoise, warpX, warpZ, rand, pos, top);
        }

        return top;
    }

    private static BlockState rock(BlockState state) {
        if (state.is(FTFBlockTags.ROCK)) {
            return state;
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState ground(Config config, Noise clusterNoise, Noise warpX, Noise warpZ, Noise rand, BlockPos.MutableBlockPos pos, BlockState state) {
        int x = pos.getX();
        int z = pos.getZ();

        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM)) {
            return sampleMaterial(DIRT_MATERIALS, x, z, clusterNoise, warpX, warpZ, rand, 4, Blocks.COARSE_DIRT.defaultBlockState());
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            return Blocks.MOSS_BLOCK.defaultBlockState();
        }
        if (state.is(BlockTags.DIRT)) {
            return state;
        }
        if (state.is(Blocks.SAND)) {
            return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }
        if (state.is(Blocks.RED_SAND)) {
            return Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
        }

        return sampleMaterial(DIRT_MATERIALS, x, z, clusterNoise, warpX, warpZ, rand, 4, Blocks.COARSE_DIRT.defaultBlockState());
    }

    private static BlockState sampleMaterial(WeightedBlockSelector selector, int x, int z, Noise clusterNoise, Noise warpX, Noise warpZ, Noise rand, int seedOffset, BlockState fallback) {
        // Standard unwarped noise pass for non-moss blocks
        float rawNoise = clusterNoise.compute(x * CLUSTER_SCALE, z * CLUSTER_SCALE, seedOffset);
        float oldSample = Math.max(0.0f, Math.min(1.0f, (rawNoise + 1.0f) / 2.0f));

        // Domain-warped noise pass for moss
        float warpedSample = sampleWarpedNoise(x, z, CLUSTER_SCALE, WARP_SCALE, WARP_STRENGTH, clusterNoise, warpX, warpZ, rand, seedOffset);

        BlockState warpedState = selector.sample(warpedSample, fallback);

        // If domain-warped noise selects moss, place moss with organic creep boundaries
        if (warpedState.is(Blocks.MOSS_BLOCK)) {
            return warpedState;
        }

        // For all non-moss blocks, use the old unwarped noise
        BlockState oldState = selector.sample(oldSample, fallback);
        if (oldState.is(Blocks.MOSS_BLOCK)) {
            return selector.sampleNonMoss(oldSample, fallback);
        }

        return oldState;
    }

    private static float sampleWarpedNoise(int x, int z, float scale, float warpScale, float warpStrength, Noise clusterNoise, Noise warpX, Noise warpZ, Noise rand, int seedOffset) {
        float wx = warpX.compute(x * warpScale, z * warpScale, seedOffset) * warpStrength;
        float wz = warpZ.compute(x * warpScale, z * warpScale, seedOffset + 10) * warpStrength;

        float warpedX = (x + wx) * scale;
        float warpedZ = (z + wz) * scale;
        float baseSample = clusterNoise.compute(warpedX, warpedZ, seedOffset);

        float dither = (rand.compute(x, z, seedOffset) - 0.5f) * 0.12f;

        float normalized = (baseSample + 1.0f) / 2.0f + dither;
        return Math.max(0.0f, Math.min(1.0f, normalized));
    }

    public record WeightedBlockEntry(BlockState state, int weight) {}

    public static class WeightedBlockSelector {
        private final NavigableMap<Float, BlockState> cumulativeMap = new TreeMap<>();
        private final NavigableMap<Float, BlockState> nonMossCumulativeMap = new TreeMap<>();
        private final float totalWeight;
        private final float nonMossTotalWeight;

        public WeightedBlockSelector(List<WeightedBlockEntry> entries) {
            float sum = 0.0f;
            float nonMossSum = 0.0f;
            for (WeightedBlockEntry entry : entries) {
                if (entry.weight() > 0) {
                    sum += entry.weight();
                    cumulativeMap.put(sum, entry.state());

                    if (!entry.state().is(Blocks.MOSS_BLOCK)) {
                        nonMossSum += entry.weight();
                        nonMossCumulativeMap.put(nonMossSum, entry.state());
                    }
                }
            }
            this.totalWeight = sum;
            this.nonMossTotalWeight = nonMossSum;
        }

        public BlockState sample(float noiseSample, BlockState fallback) {
            if (cumulativeMap.isEmpty()) {
                return fallback;
            }
            float target = noiseSample * totalWeight;
            Map.Entry<Float, BlockState> entry = cumulativeMap.ceilingEntry(target);
            return entry != null ? entry.getValue() : fallback;
        }

        public BlockState sampleNonMoss(float noiseSample, BlockState fallback) {
            if (nonMossCumulativeMap.isEmpty()) {
                return fallback;
            }
            float target = noiseSample * nonMossTotalWeight;
            Map.Entry<Float, BlockState> entry = nonMossCumulativeMap.ceilingEntry(target);
            return entry != null ? entry.getValue() : fallback;
        }
    }

    public record Config(
            int rockVar, int rockMin, int dirtVar, int dirtMin,
            float rockSteepness, float dirtSteepness, float screeSteepness,
            float heightModifier, float slopeModifier
    ) implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("rock_var").forGetter(Config::rockVar),
                Codec.INT.fieldOf("rock_min").forGetter(Config::rockMin),
                Codec.INT.fieldOf("dirt_var").forGetter(Config::dirtVar),
                Codec.INT.fieldOf("dirt_min").forGetter(Config::dirtMin),
                Codec.FLOAT.fieldOf("rock_steepness").forGetter(Config::rockSteepness),
                Codec.FLOAT.fieldOf("dirt_steepness").forGetter(Config::dirtSteepness),
                Codec.FLOAT.fieldOf("scree_steepness").forGetter(Config::screeSteepness),
                Codec.FLOAT.fieldOf("height_modifier").forGetter(Config::heightModifier),
                Codec.FLOAT.fieldOf("slope_modifier").forGetter(Config::slopeModifier)
        ).apply(instance, Config::new));
    }
}
