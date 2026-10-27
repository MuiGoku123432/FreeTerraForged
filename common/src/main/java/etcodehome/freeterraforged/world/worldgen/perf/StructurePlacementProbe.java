package etcodehome.freeterraforged.world.worldgen.perf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.gson.GsonBuilder;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.runtime.FullHeightNoiseReference;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

/** Opt-in transformed-runtime checks of detached noise chunks in a disposable server world. */
public final class StructurePlacementProbe {
    private static MinecraftServer completedServer;

    private StructurePlacementProbe() {}

    public static boolean onServerTick(MinecraftServer server) {
        if (!Boolean.getBoolean("freeterraforged.structureProbe")) return false;
        if (server.getTickCount() < 20 || completedServer == server) return true;
        completedServer = server;
        var report = new Report(server.overworld().getSeed());
        try {
            run(server.overworld(), report);
        } catch (Throwable failure) {
            report.failures.add(failure.toString());
            FTFCommon.LOGGER.error("[FTF-StructureProbe] Failed", failure);
        } finally {
            report.passed = report.failures.isEmpty();
            try {
                Path output = Path.of("perf-reports/structure-probe.json");
                Files.createDirectories(output.getParent());
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (Exception failure) {
                report.passed = false;
                FTFCommon.LOGGER.error("[FTF-StructureProbe] Could not write report", failure);
            }
            FTFCommon.LOGGER.info("[FTF-StructureProbe] passed={}, heightChecks={}, blockChecks={}, failures={}",
                report.passed, report.heightChecks, report.blockChecks, report.failures);
            server.halt(false);
            // As in the existing benchmark runner, loader/worker threads can outlive the
            // stopped server. This opt-in diagnostic process must also report a failed exit.
            Thread exit = new Thread(() -> {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(report.passed ? 0 : 1);
            }, "FTF-StructureProbe-Exit");
            exit.setDaemon(true);
            exit.start();
        }
        return true;
    }

    private static void run(ServerLevel level, Report report) {
        if (!(level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator generator)) {
            throw new IllegalStateException("Probe requires an FTF world");
        }
        var random = level.getChunkSource().randomState();
        var context = ((FTFRandomState) (Object) random).generatorContext();
        int[][] points = {{0, 0}, {63, 63}, {64, 64}, {-1, -1}, {-65, -65},
            {100003, 100007}, {-24000, -45000}, {2048, -4096}};
        LevelHeightAccessor[] ranges = {level, LevelHeightAccessor.create(-64, 128),
            LevelHeightAccessor.create(0, 64), LevelHeightAccessor.create(512, 16),
            LevelHeightAccessor.create(-512, 16)};
        for (int[] point : points) {
            for (var range : ranges) {
                int x = point[0], z = point[1];
                context.filteredChunkCache.clear();
                var column = generator.getBaseColumn(x, z, range, random);
                var bounds = generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(range);
                for (var type : Heightmap.Types.values()) {
                    int expected = range.getMinBuildHeight();
                    for (int y = bounds.minY() + bounds.height() - 1; y >= bounds.minY(); y--) {
                        if (type.isOpaque().test(column.getBlock(y))) {
                            expected = y + 1;
                            break;
                        }
                    }
                    context.filteredChunkCache.clear();
                    int cold = generator.getBaseHeight(x, z, type, range, random);
                    int warm = generator.getBaseHeight(x, z, type, range, random);
                    report.heightChecks++;
                    if (cold != expected || warm != expected) {
                        report.failures.add("height " + type + " at " + x + "," + z
                            + " expected=" + expected + " cold=" + cold + " warm=" + warm);
                    }
                }
            }
        }

        // Low terrain plus a deliberately high rigid piece exercises the fill-cap failure.
        for (int[] coords : new int[][]{{-4, -4}, {3, 3}, {6250, 6250}}) {
            for (var adjustment : TerrainAdjustment.values()) {
                ChunkPos position = new ChunkPos(coords[0], coords[1]);
                int x = position.getMinBlockX(), z = position.getMinBlockZ();
                int surface = generator.getBaseHeight(x + 8, z + 8, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                int ground = Math.min(level.getMaxBuildHeight() - 64, surface + 96);
                Structure structure = new Structure(new Structure.StructureSettings(
                    HolderSet.direct(level.getBiome(new BlockPos(x, surface, z))), java.util.Map.of(),
                    GenerationStep.Decoration.SURFACE_STRUCTURES, adjustment)) {
                    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
                        return Optional.empty();
                    }
                    @Override public StructureType<?> type() { return StructureType.JIGSAW; }
                };
                var piece = new PoolElementStructurePiece(level.getServer().getStructureManager(),
                    StructurePoolElement.single("minecraft:empty").apply(StructureTemplatePool.Projection.RIGID),
                    new BlockPos(x, ground, z), 0, Rotation.NONE,
                    new BoundingBox(x - 8, ground, z - 8, x + 24, ground + 12, z + 24),
                    LiquidSettings.APPLY_WATERLOGGING);
                piece.addJunction(new JigsawJunction(x + 8, ground, z + 8, 0, StructureTemplatePool.Projection.RIGID));
                var start = new StructureStart(structure, position, 0, new PiecesContainer(List.of(piece)));
                var candidateManager = new ProbeStructures(level, List.of(start));
                var referenceManager = new ReferenceStructures(level, List.of(start));
                var candidate = new ProtoChunk(position, UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
                var reference = new ProtoChunk(position, UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
                // Normal generation reaches NOISE after BIOMES has initialized chunk cells.
                // Without this, the optimization is bypassed and the comparison is vacuous.
                ((IChunkCellsHolder) candidate).freeterraforged$getOrCreateChunkCells(context);
                ((IChunkCellsHolder) reference).freeterraforged$getOrCreateChunkCells(context);
                generator.fillFromNoise(Blender.empty(), random, candidateManager, candidate).join();
                generator.fillFromNoise(Blender.empty(), random, referenceManager, reference).join();
                var mutable = new BlockPos.MutableBlockPos();
                long differences = 0;
                for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        mutable.set(x + dx, y, z + dz);
                        report.blockChecks++;
                        if (!candidate.getBlockState(mutable).equals(reference.getBlockState(mutable))) differences++;
                    }
                }
                report.fillComparisons.add(position + " " + adjustment + ": differences=" + differences + ", ground=" + ground);
                if (differences != 0) report.failures.add("fill " + position + " " + adjustment + ": " + differences + " blocks differ");
            }
        }
    }

    private static class ProbeStructures extends StructureManager {
        private final List<StructureStart> starts;
        ProbeStructures(ServerLevel level, List<StructureStart> starts) {
            super(level, level.getServer().getWorldData().worldGenOptions(), null);
            this.starts = starts;
        }
        @Override public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> filter) {
            return starts.stream().filter(start -> filter.test(start.getStructure())).toList();
        }
    }

    private static final class ReferenceStructures extends ProbeStructures implements FullHeightNoiseReference {
        ReferenceStructures(ServerLevel level, List<StructureStart> starts) { super(level, starts); }
    }

    private static final class Report {
        final int harnessVersion = 3;
        final long seed;
        boolean passed;
        int heightChecks;
        long blockChecks;
        final List<String> failures = new ArrayList<>();
        final List<String> fillComparisons = new ArrayList<>();
        Report(long seed) { this.seed = seed; }
    }
}
