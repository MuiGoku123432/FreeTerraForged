package etcodehome.freeterraforged.world.worldgen.structure;

import java.util.List;

import it.unimi.dsi.fastutil.objects.ObjectListIterator;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;

/**
 * Blends terrain into a structure: the ground eases from the structure's plane back to its natural height. The blend
 * fades out completely at its reach, so it always meets natural ground, and the reach grows with the height difference
 * under the structure, so flat sites barely change.
 */
final class StructureGrade extends Beardifier {
    /** Must stay within {@code ChunkGenerator.createReferences}' range, and a structure's references are inflated to match. */
    static final int MAX_REACH = 48;
    private static final int MIN_REACH = 12, REACH_PER_RELIEF = 3;
    /** Most the blend moves natural ground. Being a limit on the change, not on where it applies, it adds no step. */
    private static final int MAX_CHANGE = 36;
    /** Strong enough to decide the surface, over a ramp taller than a noise cell so interpolation keeps its shape. */
    private static final double STRENGTH = 8.0, RAMP = 16.0;
    /** Fill stops this far below natural ground, so caves around a structure survive. */
    private static final int FILL_DEPTH = 16;

    record Contact(StructureFootprint.Rect rect, int plane, int reach) {}

    private final List<Contact> contacts;
    private final GeneratorContext terrain;
    private int columnX = Integer.MIN_VALUE, columnZ = Integer.MIN_VALUE, ground;
    private double target;
    private boolean fills, cuts;

    StructureGrade(ObjectListIterator<Rigid> pieces, ObjectListIterator<JigsawJunction> junctions, List<Contact> contacts, GeneratorContext terrain) {
        super(pieces, junctions);
        this.contacts = contacts;
        this.terrain = terrain;
    }

    static int reach(int relief) {
        return Mth.clamp(relief * REACH_PER_RELIEF, MIN_REACH, MAX_REACH);
    }

    /** 1 at the structure, easing smoothly to 0 at the reach. */
    static double weight(double distance, int reach) {
        double t = Math.max(0.0, 1.0 - distance / reach);
        return t * t * (3.0 - 2.0 * t);
    }

    static double target(int ground, int plane, double weight) {
        return ground + Mth.clamp((plane - ground) * weight, -MAX_CHANGE, MAX_CHANGE);
    }

    /** Density added at {@code y} to move the surface to {@code target}. */
    static double density(int y, double target, int fillFloor, boolean fills, boolean cuts) {
        if (fills && y < target && y >= fillFloor) return STRENGTH * Math.min(1.0, (target - y) / RAMP);
        if (cuts && y > target) return -STRENGTH * Math.min(1.0, (y - target) / RAMP);
        return 0.0;
    }

    private void prepareColumn(int x, int z) {
        columnX = x;
        columnZ = z;
        fills = cuts = false;
        double strongest = 0.0;
        int plane = 0;
        for (Contact contact : contacts) {
            var r = contact.rect();
            int dx = Math.max(0, Math.max(r.minX() - x, x - r.maxX())), dz = Math.max(0, Math.max(r.minZ() - z, z - r.maxZ()));
            double weight = weight(Math.sqrt(dx * dx + dz * dz), contact.reach());
            if (weight > strongest) {
                strongest = weight;
                plane = contact.plane();
            }
        }
        if (strongest == 0.0) return;
        ground = terrain.levels.scale(terrain.filteredChunkCache.getCell(x, z).height);
        target = target(ground, plane, strongest);
        fills = ground < target;
        cuts = ground > target;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double beard = super.compute(context);
        if (context.blockX() != columnX || context.blockZ() != columnZ) prepareColumn(context.blockX(), context.blockZ());
        return beard + density(context.blockY(), target, ground - FILL_DEPTH, fills, cuts);
    }
}
