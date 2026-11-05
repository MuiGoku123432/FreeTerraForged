package etcodehome.freeterraforged.world.worldgen.structure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.mojang.datafixers.util.Either;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import etcodehome.freeterraforged.mixin.JigsawStructureAccessor;
import etcodehome.freeterraforged.mixin.PoolElementStructurePieceAccessor;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

/**
 * Fits a fully rigid, beard-adapted jigsaw structure to the terrain under its real footprint. Vanilla takes the height
 * from one point, the bounding box centre, which on steep ground leaves part of the structure far above or below the
 * terrain. Here the height balances the whole footprint, and {@link StructureGrade} blends what difference remains.
 */
public final class StructureGroundFit {
    /** Largest height difference between the ground plane and the ground beneath it that grading is asked to blend. */
    public static final int MAX_RELIEF = 24;
    public static final int GRADE_REACH = StructureGrade.MAX_REACH;
    /** Pieces this close to the start's plane stand on the ground; cellars and upper floors do not meet it. */
    private static final int BASE_PLANE_TOLERANCE = 8;
    /** Fixed alternatives to the original anchor, tried in order when it has no fit. No RNG, so upstream draws are unchanged. */
    private static final int[][] ANCHOR_OFFSETS = {{0, 0}, {8, 8}, {-8, -8}, {8, -8}};
    /** Beard and terrain vary slowly compared with this column spacing. Rectangle edges are always sampled too. */
    private static final int SAMPLE_STEP = 4;
    /** {@code ChunkGenerator.createReferences} finds starts this many chunks away; a moved piece must stay within it. */
    private static final int REFERENCE_CHUNKS = 8;

    private StructureGroundFit() {}

    /** Marks a probe's biome predicate: assemble as vanilla would, so the fit can be compared against the same seed. */
    public interface UnfittedPlacement {}

    /**
     * Vertical shift placing the plane at the median ground, which balances fill against cut, plus the height a structure's
     * author set relative to the ground. Empty when the ground varies too much for grading to blend.
     */
    static OptionalInt gradeShift(int[] gaps, int authoredHeight) {
        if (gaps.length == 0) return OptionalInt.of(0);
        int[] sorted = gaps.clone();
        Arrays.sort(sorted);
        int median = sorted[sorted.length / 2];
        if (sorted[sorted.length - 1] - median > MAX_RELIEF || median - sorted[0] > MAX_RELIEF) return OptionalInt.empty();
        return OptionalInt.of(median + authoredHeight);
    }

    /** Greatest height an author may set a structure above or below the ground and still have the ground blended to it. */
    static final int SURFACE_TOLERANCE = 4;

    /**
     * Height the author set a fitted structure relative to the ground, from its ground gaps after the fit. The fit
     * leaves their median at exactly minus that height, so every chunk recovers the same value.
     */
    static int authoredHeight(int[] gaps) {
        if (gaps.length == 0) return 0;
        int[] sorted = gaps.clone();
        Arrays.sort(sorted);
        return -sorted[sorted.length / 2];
    }

    /** False for structures set into the ground or above it on purpose, such as buried ruins: blending would dig them up. */
    static boolean meetsSurface(int authoredHeight) {
        return Math.abs(authoredHeight) <= SURFACE_TOLERANCE;
    }

    /** Height the author set these placed base pieces relative to the ground. */
    public static int authoredHeight(GeneratorContext terrain, List<PoolElementStructurePiece> base) {
        return authoredHeight(groundGaps(terrain, base, 0, 0));
    }

    /** True when the ground around these placed base pieces should be blended to them. */
    static boolean meetsSurface(GeneratorContext terrain, List<PoolElementStructurePiece> base) {
        return meetsSurface(authoredHeight(terrain, base));
    }

    /** True when this start's own projection targets a surface. Sea-floor structures keep their own placement. */
    public static boolean projectsToSurface(Optional<Heightmap.Types> projection) {
        return projection.isPresent()
            && projection.get() != Heightmap.Types.OCEAN_FLOOR
            && projection.get() != Heightmap.Types.OCEAN_FLOOR_WG;
    }

    /** True for structures whose placement this class fits and whose surroundings are graded. Underground ones are left alone. */
    public static boolean grades(Structure structure) {
        return structure instanceof JigsawStructure jigsaw
            && StructureFootprint.isBeard(structure.terrainAdaptation())
            && projectsToSurface(((JigsawStructureAccessor) (Object) jigsaw).ftf$getProjection());
    }

    /** The pieces of a fully rigid structure that stand on the ground; empty when any piece follows the terrain itself. */
    public static List<PoolElementStructurePiece> basePieces(List<StructurePiece> pieces) {
        List<PoolElementStructurePiece> base = new ArrayList<>(pieces.size());
        int startPlane = 0;
        for (int i = 0; i < pieces.size(); i++) {
            if (!(pieces.get(i) instanceof PoolElementStructurePiece pool) || pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) return List.of();
            int plane = plane(pool);
            if (i == 0) startPlane = plane;
            if (Math.abs(plane - startPlane) <= BASE_PLANE_TOLERANCE) base.add(pool);
        }
        return base;
    }

    private static int plane(PoolElementStructurePiece piece) {
        return piece.getBoundingBox().minY() + piece.getGroundLevelDelta();
    }

    /**
     * Returns the fitted stub, or empty when no anchor fits. Structures containing a non-rigid or non-pool piece follow
     * the terrain piece by piece, so moving them as one body would be wrong; they are returned untouched.
     */
    public static Optional<Structure.GenerationStub> fit(Structure.GenerationContext context, GeneratorContext terrain, Structure.GenerationStub stub) {
        StructurePiecesBuilder builder = stub.getPiecesBuilder();
        List<StructurePiece> built = builder.build().pieces();
        if (built.isEmpty()) return Optional.of(stub);
        List<PoolElementStructurePiece> pieces = new ArrayList<>(built.size());
        for (StructurePiece piece : built) {
            if (!(piece instanceof PoolElementStructurePiece pool) || pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                return Optional.of(new Structure.GenerationStub(stub.position(), Either.right(builder)));
            }
            pieces.add(pool);
        }

        BoundingBox whole = builder.getBoundingBox();
        ChunkPos start = context.chunkPos();
        List<PoolElementStructurePiece> base = basePieces(built);
        BoundingBox first = base.get(0).getBoundingBox();
        int authoredHeight = plane(base.get(0)) - surface(terrain, (first.minX() + first.maxX()) / 2, (first.minZ() + first.maxZ()) / 2);
        for (int[] offset : ANCHOR_OFFSETS) {
            int dx = offset[0], dz = offset[1];
            if ((dx != 0 || dz != 0) && !withinReferenceRange(whole, dx, dz, start)) continue;
            OptionalInt dy = gradeShift(groundGaps(terrain, base, dx, dz), authoredHeight);
            if (dy.isEmpty()) continue;
            int shift = dy.getAsInt();
            if (whole.minY() + shift < context.heightAccessor().getMinBuildHeight() || whole.maxY() + shift >= context.heightAccessor().getMaxBuildHeight()) continue;
            move(pieces, dx, shift, dz);
            // A builder caches its bounding box, so moved pieces need a fresh one.
            StructurePiecesBuilder moved = new StructurePiecesBuilder();
            for (PoolElementStructurePiece piece : pieces) moved.addPiece(piece);
            return Optional.of(new Structure.GenerationStub(stub.position().offset(dx, shift, dz), Either.right(moved)));
        }
        return Optional.empty();
    }

    private static boolean withinReferenceRange(BoundingBox box, int dx, int dz, ChunkPos start) {
        return ((box.minX() + dx) >> 4) >= start.x - REFERENCE_CHUNKS && ((box.maxX() + dx) >> 4) <= start.x + REFERENCE_CHUNKS
            && ((box.minZ() + dz) >> 4) >= start.z - REFERENCE_CHUNKS && ((box.maxZ() + dz) >> 4) <= start.z + REFERENCE_CHUNKS;
    }

    /** Greatest difference between each base piece's plane and the ground at its contact corners. */
    static int relief(GeneratorContext terrain, List<PoolElementStructurePiece> pieces) {
        int relief = 0;
        for (PoolElementStructurePiece piece : pieces) {
            int plane = plane(piece);
            for (StructureFootprint.Rect r : StructureFootprint.worldRects(piece, ((PoolElementStructurePieceAccessor) piece).ftf$getTemplates())) {
                relief = Math.max(relief, Math.abs(ground(terrain, r.minX(), r.minZ()) - plane));
                relief = Math.max(relief, Math.abs(ground(terrain, r.maxX(), r.maxZ()) - plane));
                relief = Math.max(relief, Math.abs(ground(terrain, r.minX(), r.maxZ()) - plane));
                relief = Math.max(relief, Math.abs(ground(terrain, r.maxX(), r.minZ()) - plane));
            }
        }
        return relief;
    }

    private static int ground(GeneratorContext terrain, int x, int z) {
        return terrain.levels.scale(terrain.filteredChunkCache.getCell(x, z).height);
    }

    /** Ground height minus ground plane, sampled across every base piece's contact area at this anchor. */
    private static int[] groundGaps(GeneratorContext terrain, List<PoolElementStructurePiece> pieces, int dx, int dz) {
        IntArrayList gaps = new IntArrayList();
        for (PoolElementStructurePiece piece : pieces) {
            int plane = plane(piece);
            for (StructureFootprint.Rect r : StructureFootprint.worldRects(piece, ((PoolElementStructurePieceAccessor) piece).ftf$getTemplates())) {
                for (int x = r.minX(); ; x = Math.min(x + SAMPLE_STEP, r.maxX())) {
                    for (int z = r.minZ(); ; z = Math.min(z + SAMPLE_STEP, r.maxZ())) {
                        gaps.add(surface(terrain, x + dx, z + dz) - plane);
                        if (z == r.maxZ()) break;
                    }
                    if (x == r.maxX()) break;
                }
            }
        }
        return gaps.toIntArray();
    }

    /** Ground or water surface in the same terms as WORLD_SURFACE_WG projection: the first free Y. */
    public static int surface(GeneratorContext terrain, int x, int z) {
        Cell cell = terrain.filteredChunkCache.getCell(x, z);
        return Math.max(terrain.levels.scale(cell.height), Math.max(terrain.levels.waterY, terrain.levels.scale(cell.riverWaterLevel)));
    }

    /** Moves pieces and the absolute coordinates their junctions store, which {@code move} does not update. */
    private static void move(List<PoolElementStructurePiece> pieces, int dx, int dy, int dz) {
        for (PoolElementStructurePiece piece : pieces) {
            piece.move(dx, dy, dz);
            piece.getJunctions().replaceAll(j -> new JigsawJunction(
                j.getSourceX() + dx, j.getSourceGroundY() + dy, j.getSourceZ() + dz, j.getDeltaY(), j.getDestProjection()));
        }
    }
}
