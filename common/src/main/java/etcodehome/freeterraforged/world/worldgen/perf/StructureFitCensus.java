package etcodehome.freeterraforged.world.worldgen.perf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import etcodehome.freeterraforged.mixin.JigsawStructureAccessor;
import etcodehome.freeterraforged.mixin.SinglePoolElementAccessor;
import etcodehome.freeterraforged.mixin.StructureTemplateAccessor;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.structure.StructureFootprint;
import etcodehome.freeterraforged.world.worldgen.structure.StructureGroundFit;

/**
 * Probe section: assembles every registered beard-adapted jigsaw structure at fixed chunks, with and without ground fit,
 * from the same seed. The two differ only by the fit, so the comparison and its invariants need no second build.
 */
final class StructureFitCensus {
    private static final int GRID = 6, SPACING = 67, NOISE_COMPARISONS_PER_SIZE = 5;
    /** Compared area around a structure's box, and the height step that counts as a cliff. */
    private static final int COMPARE_MARGIN = 48, CLIFF_STEP = 5;
    private static final int LARGE_BOX_AREA = 1000;
    /** Vanilla placements overhanging further than this are worth comparing. */
    private static final int WORTH_COMPARING_OVERHANG = 6;
    /** Every column is measured here, while the fit samples every 4 blocks. Terrain may dip a little between samples. */
    private static final int MEASURED_OVERHANG_TOLERANCE = 4;

    private StructureFitCensus() {}

    private record AnyBiome() implements Predicate<Holder<Biome>> {
        @Override public boolean test(Holder<Biome> biome) { return true; }
    }

    private record Unfitted() implements Predicate<Holder<Biome>>, StructureGroundFit.UnfittedPlacement {
        @Override public boolean test(Holder<Biome> biome) { return true; }
    }

    static final class Row {
        String id;
        int attempts, unfittedPlaced, terrainFollowing, fitted, rejected, moved, movedSideways;
        int maxOverhangBefore = Integer.MIN_VALUE, maxBuryBefore = Integer.MIN_VALUE;
        int maxOverhangAfter = Integer.MIN_VALUE, maxBuryAfter = Integer.MIN_VALUE;
        long boxArea, contactArea;
        boolean projected;
        transient ChunkPos worstChunk;
        transient int worstOverhang = Integer.MIN_VALUE;
    }

    /** Terrain columns with solid ground above air, at the NOISE stage, around one real start. */
    static final class NoiseComparison {
        String id, chunk;
        int boxColumns, vanillaFloating, fittedFloating, vanillaMaxGap, fittedMaxGap, vanillaChanged, fittedChanged;
        int verticalShift;
        boolean rejected;
        /** Solid template blocks above the ground plane that end up inside terrain, and how many there are. */
        int vanillaBuried, vanillaAboveGround, fittedBuried, fittedAboveGround;
        /** Columns with a cliff the untouched terrain did not have, and the largest change to the terrain's top. */
        int vanillaNewCliffs, fittedNewCliffs, vanillaMaxFill, fittedMaxFill, vanillaMaxCut, fittedMaxCut;
    }

    private static final class VanillaBeard extends StructurePlacementProbe.ProbeStructures implements StructureFootprint.UntrimmedBeard {
        VanillaBeard(ServerLevel level, List<StructureStart> starts) { super(level, starts); }
    }

    /** Large and small projected structures, each at the placed sample where vanilla overhangs the most. */
    static List<NoiseComparison> compareNoise(ServerLevel level, List<Row> rows) {
        var generator = (TerraForgedChunkGenerator) level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        List<NoiseComparison> out = new ArrayList<>();
        for (boolean large : new boolean[] {true, false}) {
            rows.stream().filter(r -> r.projected && r.worstChunk != null && r.worstOverhang > WORTH_COMPARING_OVERHANG
                    && (r.boxArea / Math.max(1, r.unfittedPlaced) >= LARGE_BOX_AREA) == large)
                .sorted(java.util.Comparator.<Row>comparingInt(r -> -r.worstOverhang).thenComparing(r -> r.id)).limit(NOISE_COMPARISONS_PER_SIZE)
                .forEach(row -> {
                    Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(row.id));
                    var base = structure.findValidGenerationPoint(context(new Unfitted(), row.worstChunk, level, generator, random));
                    var fit = structure.findValidGenerationPoint(context(new AnyBiome(), row.worstChunk, level, generator, random));
                    if (base.isEmpty()) return;
                    out.add(compare(row, structure, base.get(), fit, level, generator, random));
                });
        }
        return out;
    }

    private static NoiseComparison compare(Row row, Structure structure, Structure.GenerationStub base, Optional<Structure.GenerationStub> fit, ServerLevel level,
                                           TerraForgedChunkGenerator generator, RandomState random) {
        var result = new NoiseComparison();
        result.id = row.id;
        result.chunk = row.worstChunk.toString();
        result.rejected = fit.isEmpty();
        PiecesContainer vanillaPieces = base.getPiecesBuilder().build();
        PiecesContainer fittedPieces = fit.map(stub -> stub.getPiecesBuilder().build()).orElse(null);
        BoundingBox box = vanillaPieces.calculateBoundingBox();
        result.boxColumns = box.getXSpan() * box.getZSpan();
        var fittedManager = new StructurePlacementProbe.ProbeStructures(level, fittedPieces != null
            ? List.of(new StructureStart(structure, row.worstChunk, 0, fittedPieces)) : List.of());
        var vanillaManager = new VanillaBeard(level, List.of(new StructureStart(structure, row.worstChunk, 0, vanillaPieces)));
        var terrainManager = new StructurePlacementProbe.ProbeStructures(level, List.of());
        if (fit.isPresent()) result.verticalShift = fit.get().position().getY() - base.position().getY();

        int minChunkX = (box.minX() - COMPARE_MARGIN) >> 4, minChunkZ = (box.minZ() - COMPARE_MARGIN) >> 4;
        int width = (((box.maxX() + COMPARE_MARGIN) >> 4) - minChunkX + 1) * 16, depth = (((box.maxZ() + COMPARE_MARGIN) >> 4) - minChunkZ + 1) * 16;
        int originX = minChunkX << 4, originZ = minChunkZ << 4;
        int[] terrainTops = new int[width * depth], vanillaTops = new int[width * depth], fittedTops = new int[width * depth];
        Map<Long, ProtoChunk> vanillaChunks = new HashMap<>(), fittedChunks = new HashMap<>();
        for (int cx = minChunkX; cx < minChunkX + width / 16; cx++) {
            for (int cz = minChunkZ; cz < minChunkZ + depth / 16; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                ProtoChunk terrain = filled(chunk, terrainManager, level, generator, random);
                ProtoChunk vanilla = filled(chunk, vanillaManager, level, generator, random);
                ProtoChunk fitted = filled(chunk, fittedManager, level, generator, random);
                vanillaChunks.put(chunk.toLong(), vanilla);
                fittedChunks.put(chunk.toLong(), fitted);
                for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                    for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                        int i = (z - originZ) * width + x - originX;
                        int top = terrainTops[i] = topSolid(terrain, x, z, level);
                        int v = floatingGap(vanilla, x, z, top, level), f = floatingGap(fitted, x, z, top, level);
                        if (v > 0) { result.vanillaFloating++; result.vanillaMaxGap = Math.max(result.vanillaMaxGap, v); }
                        if (f > 0) { result.fittedFloating++; result.fittedMaxGap = Math.max(result.fittedMaxGap, f); }
                        vanillaTops[i] = topSolid(vanilla, x, z, level);
                        fittedTops[i] = topSolid(fitted, x, z, level);
                        if (vanillaTops[i] != top) result.vanillaChanged++;
                        if (fittedTops[i] != top) result.fittedChanged++;
                        result.vanillaMaxFill = Math.max(result.vanillaMaxFill, vanillaTops[i] - top);
                        result.vanillaMaxCut = Math.max(result.vanillaMaxCut, top - vanillaTops[i]);
                        result.fittedMaxFill = Math.max(result.fittedMaxFill, fittedTops[i] - top);
                        result.fittedMaxCut = Math.max(result.fittedMaxCut, top - fittedTops[i]);
                    }
                }
            }
        }
        result.vanillaNewCliffs = newCliffs(terrainTops, vanillaTops, width);
        result.fittedNewCliffs = newCliffs(terrainTops, fittedTops, width);
        var templates = level.getServer().getStructureManager();
        int[] vanillaBurial = burial(vanillaPieces, vanillaChunks, templates);
        result.vanillaBuried = vanillaBurial[0];
        result.vanillaAboveGround = vanillaBurial[1];
        if (fittedPieces != null) {
            int[] fittedBurial = burial(fittedPieces, fittedChunks, templates);
            result.fittedBuried = fittedBurial[0];
            result.fittedAboveGround = fittedBurial[1];
        }
        return result;
    }

    private static int newCliffs(int[] terrain, int[] tops, int width) {
        int count = 0;
        for (int i = 0; i < tops.length; i++) {
            boolean east = (i + 1) % width != 0 && Math.abs(tops[i + 1] - tops[i]) >= CLIFF_STEP && Math.abs(terrain[i + 1] - terrain[i]) < CLIFF_STEP;
            boolean south = i + width < tops.length && Math.abs(tops[i + width] - tops[i]) >= CLIFF_STEP && Math.abs(terrain[i + width] - terrain[i]) < CLIFF_STEP;
            if (east || south) count++;
        }
        return count;
    }

    /** {template blocks inside terrain, template blocks counted}: solid blocks above each plain template piece's ground plane. */
    private static int[] burial(PiecesContainer pieces, Map<Long, ProtoChunk> chunks, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates) {
        int buried = 0, counted = 0;
        for (StructurePiece piece : pieces.pieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool) || !(pool.getElement() instanceof SinglePoolElement element)) continue;
            int plane = pool.getBoundingBox().minY() + pool.getGroundLevelDelta();
            var palettes = ((StructureTemplateAccessor) ((SinglePoolElementAccessor) (Object) element).ftf$getTemplate(templates)).ftf$getPalettes();
            if (palettes.isEmpty()) continue;
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                if (!info.state().isSolid()) continue;
                BlockPos world = pool.getPosition().offset(StructureTemplate.transform(info.pos(), Mirror.NONE, pool.getRotation(), BlockPos.ZERO));
                ProtoChunk chunk = chunks.get(ChunkPos.asLong(world));
                if (world.getY() <= plane || chunk == null) continue;
                counted++;
                if (chunk.getBlockState(world).isSolid()) buried++;
            }
        }
        return new int[] {buried, counted};
    }

    private static ProtoChunk filled(ChunkPos position, StructureManager structures, ServerLevel level, TerraForgedChunkGenerator generator, RandomState random) {
        var chunk = new ProtoChunk(position, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        ((IChunkCellsHolder) chunk).freeterraforged$getOrCreateChunkCells(((FTFRandomState) (Object) random).generatorContext());
        generator.fillFromNoise(Blender.empty(), random, structures, chunk).join();
        return chunk;
    }

    private static int topSolid(ProtoChunk chunk, int x, int z, ServerLevel level) {
        var pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            if (chunk.getBlockState(pos.set(x, y, z)).isSolid()) return y;
        }
        return level.getMinBuildHeight();
    }

    /** Largest air gap between the terrain's own surface and solid blocks above it; 0 when nothing floats. */
    private static int floatingGap(ProtoChunk chunk, int x, int z, int terrainTop, ServerLevel level) {
        var pos = new BlockPos.MutableBlockPos();
        int gap = 0, largest = 0;
        for (int y = terrainTop + 1; y < level.getMaxBuildHeight(); y++) {
            if (chunk.getBlockState(pos.set(x, y, z)).isSolid()) {
                largest = Math.max(largest, gap);
                gap = 0;
            } else {
                gap++;
            }
        }
        return largest;
    }

    static List<Row> run(ServerLevel level, int originChunkX, int originChunkZ, List<String> failures) {
        var generator = (TerraForgedChunkGenerator) level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        GeneratorContext terrain = ((FTFRandomState) (Object) random).generatorContext();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<Row> rows = new ArrayList<>();
        for (var entry : registry.entrySet()) {
            Structure structure = entry.getValue();
            if (!(structure instanceof JigsawStructure) || !StructureFootprint.isBeard(structure.terrainAdaptation())) continue;
            Row row = new Row();
            row.id = entry.getKey().location().toString();
            row.projected = StructureGroundFit.projectsToSurface(((JigsawStructureAccessor) structure).ftf$getProjection());
            for (int i = 0; i < GRID; i++) {
                for (int j = 0; j < GRID; j++) {
                    ChunkPos chunk = new ChunkPos(originChunkX + i * SPACING, originChunkZ + j * SPACING);
                    row.attempts++;
                    sample(structure, row, chunk, level, generator, random, terrain, failures);
                    // Terrain-following structures are outside the fit, and each assembly is costly.
                    if (row.terrainFollowing > 0) break;
                }
                if (row.terrainFollowing > 0) break;
            }
            rows.add(row);
        }
        return rows;
    }

    private static void sample(Structure structure, Row row, ChunkPos chunk, ServerLevel level, TerraForgedChunkGenerator generator,
                               RandomState random, GeneratorContext terrain, List<String> failures) {
        Optional<Structure.GenerationStub> base = structure.findValidGenerationPoint(context(new Unfitted(), chunk, level, generator, random));
        String where = row.id + " at chunk " + chunk;
        if (base.isEmpty()) return;
        row.unfittedPlaced++;
        var templates = level.getServer().getStructureManager();
        List<StructurePiece> before = base.get().getPiecesBuilder().build().pieces();
        for (StructurePiece piece : before) {
            if (!(piece instanceof PoolElementStructurePiece pool) || pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                row.terrainFollowing++;
                return;
            }
        }
        Optional<Structure.GenerationStub> fit = structure.findValidGenerationPoint(context(new AnyBiome(), chunk, level, generator, random));
        Optional<Structure.GenerationStub> again = structure.findValidGenerationPoint(context(new AnyBiome(), chunk, level, generator, random));
        int[] measured = measure(before, terrain, templates, row, true, where, failures);
        row.maxOverhangBefore = Math.max(row.maxOverhangBefore, measured[0]);
        row.maxBuryBefore = Math.max(row.maxBuryBefore, measured[1]);
        if (fit.isEmpty()) {
            row.rejected++;
            if (again.isPresent()) failures.add("fit is not repeatable: " + where);
            return;
        }
        if (row.projected && measured[0] > row.worstOverhang) {
            row.worstOverhang = measured[0];
            row.worstChunk = chunk;
        }
        row.fitted++;
        List<StructurePiece> after = fit.get().getPiecesBuilder().build().pieces();
        if (after.size() != before.size()) {
            failures.add("fit changed piece count: " + where);
            return;
        }
        var first = new int[] {after.get(0).getBoundingBox().minX() - before.get(0).getBoundingBox().minX(),
            after.get(0).getBoundingBox().minY() - before.get(0).getBoundingBox().minY(),
            after.get(0).getBoundingBox().minZ() - before.get(0).getBoundingBox().minZ()};
        for (int p = 0; p < after.size(); p++) {
            BoundingBox a = before.get(p).getBoundingBox(), b = after.get(p).getBoundingBox();
            if (b.minX() - a.minX() != first[0] || b.minY() - a.minY() != first[1] || b.minZ() - a.minZ() != first[2]) {
                failures.add("pieces did not move as one body: " + where);
            }
            var movedPiece = (PoolElementStructurePiece) after.get(p);
            var originalPiece = (PoolElementStructurePiece) before.get(p);
            if (movedPiece.getRotation() != originalPiece.getRotation()) failures.add("fit changed rotation: " + where);
            List<JigsawJunction> ja = originalPiece.getJunctions(), jb = movedPiece.getJunctions();
            if (ja.size() != jb.size()) failures.add("fit changed junction count: " + where);
            else for (int q = 0; q < ja.size(); q++) {
                if (jb.get(q).getSourceX() - ja.get(q).getSourceX() != first[0] || jb.get(q).getSourceGroundY() - ja.get(q).getSourceGroundY() != first[1]
                    || jb.get(q).getSourceZ() - ja.get(q).getSourceZ() != first[2]) failures.add("junction did not follow its piece: " + where);
            }
        }
        var stubBefore = base.get().position();
        var stubAfter = fit.get().position();
        if (stubAfter.getX() - stubBefore.getX() != first[0] || stubAfter.getY() - stubBefore.getY() != first[1] || stubAfter.getZ() - stubBefore.getZ() != first[2]) {
            failures.add("stub position disagrees with pieces: " + where);
        }
        if (first[0] != 0 || first[1] != 0 || first[2] != 0) row.moved++;
        if (first[0] != 0 || first[2] != 0) row.movedSideways++;
        if (again.isEmpty() || !again.get().position().equals(stubAfter)) failures.add("fit is not repeatable: " + where);

        measured = measure(after, terrain, templates, row, false, where, failures);
        row.maxOverhangAfter = Math.max(row.maxOverhangAfter, measured[0]);
        row.maxBuryAfter = Math.max(row.maxBuryAfter, measured[1]);
        if (!row.projected) return;
        if (measured[0] + measured[1] > 2 * (StructureGroundFit.MAX_RELIEF + MEASURED_OVERHANG_TOLERANCE)) {
            failures.add("relief " + (measured[0] + measured[1]) + " after fit: " + where);
        }
    }

    /** {greatest height above the ground, greatest depth below it} of a base piece's plane over every contact column. */
    private static int[] measure(List<StructurePiece> pieces, GeneratorContext terrain, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates,
                                 Row row, boolean countArea, String where, List<String> failures) {
        int overhang = Integer.MIN_VALUE, bury = Integer.MIN_VALUE;
        for (PoolElementStructurePiece pool : StructureGroundFit.basePieces(pieces)) {
            BoundingBox box = pool.getBoundingBox();
            int plane = box.minY() + pool.getGroundLevelDelta();
            if (countArea) row.boxArea += (long) box.getXSpan() * box.getZSpan();
            for (var r : StructureFootprint.worldRects(pool, templates)) {
                if (r.minX() < box.minX() || r.maxX() > box.maxX() || r.minZ() < box.minZ() || r.maxZ() > box.maxZ()) {
                    failures.add("contact rectangle outside the piece's box, rotation " + pool.getRotation() + ": " + where);
                }
                if (countArea) row.contactArea += (long) (r.maxX() - r.minX() + 1) * (r.maxZ() - r.minZ() + 1);
                for (int x = r.minX(); x <= r.maxX(); x++) {
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        int surface = StructureGroundFit.surface(terrain, x, z);
                        overhang = Math.max(overhang, plane - surface);
                        bury = Math.max(bury, surface - plane);
                    }
                }
            }
        }
        return new int[] {overhang, bury};
    }

    private static Structure.GenerationContext context(Predicate<Holder<Biome>> biomes, ChunkPos chunk, ServerLevel level, TerraForgedChunkGenerator generator, RandomState random) {
        return new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), random,
            level.getServer().getStructureManager(), level.getSeed(), chunk, level, biomes);
    }
}
