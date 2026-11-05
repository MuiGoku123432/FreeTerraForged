package etcodehome.freeterraforged.world.worldgen.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import etcodehome.freeterraforged.mixin.BeardifierAccessor;
import etcodehome.freeterraforged.mixin.PoolElementStructurePieceAccessor;
import etcodehome.freeterraforged.mixin.SinglePoolElementAccessor;
import etcodehome.freeterraforged.mixin.StructureTemplateAccessor;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;

/**
 * The part of a template that actually meets the ground, derived from its blocks: the columns occupied in its lowest
 * layers. A giant tree's bounding box covers its whole canopy, but only its trunk and roots should shape the terrain.
 */
public final class StructureFootprint {
    /** Layers above a template's lowest occupied block that count as ground contact. */
    private static final int CONTACT_LAYERS = 4;
    /** Contact is merged on this grid: finer detail is lost anyway in Beardifier's 12-block kernel. */
    private static final int TILE = 8;
    private static final Map<StructureTemplate, List<Rect>> LOCAL_RECTS = Collections.synchronizedMap(new WeakHashMap<>());

    private StructureFootprint() {}

    public record Rect(int minX, int minZ, int maxX, int maxZ) {}

    /** Marker for diagnostic structure managers requesting vanilla's whole-box beard, for comparison. */
    public interface UntrimmedBeard {}

    /** Contact rectangles in template coordinates for {x, y, z} occupied blocks; empty when there are none. */
    static List<Rect> contactRects(Iterable<int[]> occupied) {
        int base = Integer.MAX_VALUE, maxX = -1, maxZ = -1;
        for (int[] b : occupied) {
            base = Math.min(base, b[1]);
            maxX = Math.max(maxX, b[0]);
            maxZ = Math.max(maxZ, b[2]);
        }
        if (maxX < 0) return List.of();

        int width = maxX / TILE + 1, depth = maxZ / TILE + 1;
        // Per tile: tight bounds of its contact columns. A negative maxX marks an empty tile.
        int[][] bounds = new int[width * depth][];
        for (int[] b : occupied) {
            if (b[1] > base + CONTACT_LAYERS) continue;
            int i = b[2] / TILE * width + b[0] / TILE;
            int[] t = bounds[i];
            if (t == null) bounds[i] = new int[] {b[0], b[2], b[0], b[2]};
            else {
                t[0] = Math.min(t[0], b[0]);
                t[1] = Math.min(t[1], b[2]);
                t[2] = Math.max(t[2], b[0]);
                t[3] = Math.max(t[3], b[2]);
            }
        }
        fillEnclosedTiles(bounds, width, depth);

        List<Rect> rects = new ArrayList<>();
        boolean[] used = new boolean[bounds.length];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                if (bounds[z * width + x] == null || used[z * width + x]) continue;
                int x1 = x;
                while (x1 + 1 < width && bounds[z * width + x1 + 1] != null && !used[z * width + x1 + 1]) x1++;
                int z1 = z;
                while (z1 + 1 < depth && rowFree(bounds, used, width, z1 + 1, x, x1)) z1++;
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, rectMaxX = Integer.MIN_VALUE, rectMaxZ = Integer.MIN_VALUE;
                for (int tz = z; tz <= z1; tz++) {
                    for (int tx = x; tx <= x1; tx++) {
                        used[tz * width + tx] = true;
                        int[] t = bounds[tz * width + tx];
                        minX = Math.min(minX, t[0]);
                        minZ = Math.min(minZ, t[1]);
                        rectMaxX = Math.max(rectMaxX, t[2]);
                        rectMaxZ = Math.max(rectMaxZ, t[3]);
                    }
                }
                rects.add(new Rect(minX, minZ, rectMaxX, rectMaxZ));
            }
        }
        return rects;
    }

    private static boolean rowFree(int[][] bounds, boolean[] used, int width, int z, int x0, int x1) {
        for (int x = x0; x <= x1; x++) {
            if (bounds[z * width + x] == null || used[z * width + x]) return false;
        }
        return true;
    }

    /** Empty tiles no path from the template's edge can reach (courtyards, room floors) become contact. */
    private static void fillEnclosedTiles(int[][] bounds, int width, int depth) {
        boolean[] outside = new boolean[bounds.length];
        int[] stack = new int[bounds.length];
        int top = 0;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int i = z * width + x;
                if ((x == 0 || z == 0 || x == width - 1 || z == depth - 1) && bounds[i] == null) {
                    outside[i] = true;
                    stack[top++] = i;
                }
            }
        }
        while (top > 0) {
            int i = stack[--top], x = i % width, z = i / width;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int n = nz * width + nx;
                if (bounds[n] == null && !outside[n]) {
                    outside[n] = true;
                    stack[top++] = n;
                }
            }
        }
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int i = z * width + x;
                if (bounds[i] == null && !outside[i]) {
                    bounds[i] = new int[] {x * TILE, z * TILE, x * TILE + TILE - 1, z * TILE + TILE - 1};
                }
            }
        }
    }

    /** World rectangle of a template rectangle, for vanilla's rotation about the piece position. */
    static Rect placed(Rect r, Rotation rotation, int originX, int originZ) {
        return switch (rotation) {
            case NONE -> new Rect(originX + r.minX, originZ + r.minZ, originX + r.maxX, originZ + r.maxZ);
            case CLOCKWISE_90 -> new Rect(originX - r.maxZ, originZ + r.minX, originX - r.minZ, originZ + r.maxX);
            case CLOCKWISE_180 -> new Rect(originX - r.maxX, originZ - r.maxZ, originX - r.minX, originZ - r.minZ);
            case COUNTERCLOCKWISE_90 -> new Rect(originX + r.minZ, originZ - r.maxX, originX + r.maxZ, originZ - r.minX);
        };
    }

    /** World contact rectangles of a piece. Anything that is not a plain template uses its bounding box. */
    public static List<Rect> worldRects(PoolElementStructurePiece piece, StructureTemplateManager templates) {
        BoundingBox box = piece.getBoundingBox();
        List<Rect> whole = List.of(new Rect(box.minX(), box.minZ(), box.maxX(), box.maxZ()));
        if (!(piece.getElement() instanceof SinglePoolElement element)) return whole;
        StructureTemplate template = ((SinglePoolElementAccessor) (Object) element).ftf$getTemplate(templates);
        List<Rect> local = LOCAL_RECTS.computeIfAbsent(template, StructureFootprint::readTemplate);
        if (local.isEmpty()) return whole;
        BlockPos origin = piece.getPosition();
        List<Rect> placed = new ArrayList<>(local.size());
        for (Rect r : local) placed.add(placed(r, piece.getRotation(), origin.getX(), origin.getZ()));
        return placed;
    }

    private static List<Rect> readTemplate(StructureTemplate template) {
        List<int[]> occupied = new ArrayList<>();
        for (StructureTemplate.Palette palette : ((StructureTemplateAccessor) template).ftf$getPalettes()) {
            for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
                BlockState state = info.state();
                if (state.isAir() || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK)) continue;
                occupied.add(new int[] {info.pos().getX(), info.pos().getY(), info.pos().getZ()});
            }
        }
        return contactRects(occupied);
    }

    /**
     * Replaces the bounding box of each beard-adapted template piece with its contact rectangles. Boxes are matched by
     * identity, so rigid entries that came from anywhere else (another mod's piece, NeoForge's beardifier modifier) are
     * left exactly as they are. Beardifier's own density maths is unchanged. Fitted structures also grade the terrain
     * around them.
     */
    public static Beardifier trimToContact(Beardifier original, StructureManager structures, ChunkPos chunk, GeneratorContext terrain) {
        Map<BoundingBox, PoolElementStructurePiece> pieces = new IdentityHashMap<>();
        List<StructureGrade.Contact> contacts = new ArrayList<>();
        structures.startsForStructure(chunk, s -> isBeard(s.terrainAdaptation())).forEach(start -> {
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof PoolElementStructurePiece pool) pieces.put(pool.getBoundingBox(), pool);
            }
            if (terrain == null || !StructureGroundFit.grades(start.getStructure())) return;
            List<PoolElementStructurePiece> base = StructureGroundFit.basePieces(start.getPieces());
            if (!StructureGroundFit.meetsSurface(terrain, base)) return;
            // Measured over the whole structure, so every chunk around it derives the same reach.
            int relief = StructureGroundFit.relief(terrain, base);
            int reach = StructureGrade.reach(relief);
            for (PoolElementStructurePiece pool : base) {
                int plane = pool.getBoundingBox().minY() + pool.getGroundLevelDelta();
                for (Rect r : worldRects(pool, ((PoolElementStructurePieceAccessor) pool).ftf$getTemplates())) {
                    if (nearChunk(r, chunk, reach)) contacts.add(new StructureGrade.Contact(r, plane, reach));
                }
            }
        });
        if (pieces.isEmpty()) return original;

        BeardifierAccessor accessor = (BeardifierAccessor) original;
        var rigid = accessor.ftf$getPieceIterator();
        ObjectArrayList<Beardifier.Rigid> trimmed = new ObjectArrayList<>();
        while (rigid.hasNext()) {
            Beardifier.Rigid entry = rigid.next();
            PoolElementStructurePiece piece = pieces.get(entry.box());
            if (piece == null || !isBeard(entry.terrainAdjustment())) {
                trimmed.add(entry);
                continue;
            }
            BoundingBox box = entry.box();
            for (Rect r : worldRects(piece, ((PoolElementStructurePieceAccessor) piece).ftf$getTemplates())) {
                if (!nearChunk(r, chunk, Beardifier.BEARD_KERNEL_RADIUS)) continue;
                trimmed.add(new Beardifier.Rigid(new BoundingBox(r.minX, box.minY(), r.minZ, r.maxX, box.maxY(), r.maxZ), entry.terrainAdjustment(), entry.groundLevelDelta()));
            }
        }
        return contacts.isEmpty()
            ? new Beardifier(trimmed.iterator(), accessor.ftf$getJunctionIterator())
            : new StructureGrade(trimmed.iterator(), accessor.ftf$getJunctionIterator(), contacts, terrain);
    }

    private static boolean nearChunk(Rect r, ChunkPos chunk, int distance) {
        return r.maxX >= chunk.getMinBlockX() - distance && r.minX <= chunk.getMaxBlockX() + distance
            && r.maxZ >= chunk.getMinBlockZ() - distance && r.minZ <= chunk.getMaxBlockZ() + distance;
    }

    public static boolean isBeard(TerrainAdjustment adjustment) {
        return adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX;
    }
}
