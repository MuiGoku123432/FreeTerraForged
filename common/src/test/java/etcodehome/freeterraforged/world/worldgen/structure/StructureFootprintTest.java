package etcodehome.freeterraforged.world.worldgen.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.world.worldgen.structure.StructureFootprint.Rect;

class StructureFootprintTest {
    private static int[] block(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    private static int area(List<Rect> rects) {
        int total = 0;
        for (Rect r : rects) total += (r.maxX() - r.minX() + 1) * (r.maxZ() - r.minZ() + 1);
        return total;
    }

    private static boolean covers(List<Rect> rects, int x, int z) {
        for (Rect r : rects) if (x >= r.minX() && x <= r.maxX() && z >= r.minZ() && z <= r.maxZ()) return true;
        return false;
    }

    @Test void canopyAboveTheContactLayersDoesNotWidenTheFootprint() {
        List<int[]> blocks = new ArrayList<>();
        for (int y = 0; y < 40; y++) for (int x = 38; x < 42; x++) for (int z = 38; z < 42; z++) blocks.add(block(x, y, z));
        for (int x = 0; x < 80; x++) for (int z = 0; z < 80; z++) blocks.add(block(x, 40, z));

        List<Rect> rects = StructureFootprint.contactRects(blocks);

        assertEquals(List.of(new Rect(38, 38, 41, 41)), rects);
    }

    @Test void fullBaseLayerStaysOneRectangle() {
        List<int[]> blocks = new ArrayList<>();
        for (int x = 0; x < 89; x++) for (int z = 0; z < 76; z++) blocks.add(block(x, 0, z));

        assertEquals(List.of(new Rect(0, 0, 88, 75)), StructureFootprint.contactRects(blocks));
    }

    @Test void enclosedCourtyardIsFilledSoTerrainCannotRiseThroughIt() {
        List<int[]> blocks = new ArrayList<>();
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            boolean wall = x < 8 || z < 8 || x >= 40 || z >= 40;
            if (wall) blocks.add(block(x, 0, z));
        }

        List<Rect> rects = StructureFootprint.contactRects(blocks);

        assertTrue(covers(rects, 24, 24));
        assertEquals(48 * 48, area(rects));
    }

    @Test void openNotchIsNotFilled() {
        List<int[]> blocks = new ArrayList<>();
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            boolean notch = x >= 16 && x < 32 && z < 32;
            if (!notch) blocks.add(block(x, 0, z));
        }

        List<Rect> rects = StructureFootprint.contactRects(blocks);

        assertFalse(covers(rects, 24, 8));
        assertTrue(covers(rects, 8, 8));
        assertTrue(covers(rects, 24, 40));
    }

    @Test void separateContactsStaySeparate() {
        List<int[]> blocks = new ArrayList<>();
        blocks.add(block(0, 0, 0));
        blocks.add(block(60, 0, 60));

        List<Rect> rects = StructureFootprint.contactRects(blocks);

        assertEquals(2, rects.size());
        assertFalse(covers(rects, 30, 30));
    }

    @Test void noOccupiedBlocksMeansNoRectangles() {
        assertTrue(StructureFootprint.contactRects(List.of()).isEmpty());
    }

    @Test void placedRectFollowsVanillaRotationAboutTheOrigin() {
        Rect local = new Rect(1, 2, 3, 5);

        assertEquals(new Rect(101, 202, 103, 205), StructureFootprint.placed(local, Rotation.NONE, 100, 200));
        assertEquals(new Rect(95, 201, 98, 203), StructureFootprint.placed(local, Rotation.CLOCKWISE_90, 100, 200));
        assertEquals(new Rect(97, 195, 99, 198), StructureFootprint.placed(local, Rotation.CLOCKWISE_180, 100, 200));
        assertEquals(new Rect(102, 197, 105, 199), StructureFootprint.placed(local, Rotation.COUNTERCLOCKWISE_90, 100, 200));
    }

    private static int[] gaps(int... values) {
        return values;
    }

    @Test void balancedGroundDoesNotMove() {
        assertEquals(OptionalInt.of(0), StructureGroundFit.gradeShift(gaps(-5, -1, 0, 1, 5), 0));
    }

    @Test void planeMovesToTheMedianGround() {
        assertEquals(OptionalInt.of(-14), StructureGroundFit.gradeShift(gaps(-30, -14, -14, -2, 2), 0));
    }

    @Test void authoredHeightIsKept() {
        assertEquals(OptionalInt.of(-5), StructureGroundFit.gradeShift(gaps(0, 0, 0), -5));
    }

    @Test void reliefLimitIsInclusive() {
        assertEquals(OptionalInt.of(0), StructureGroundFit.gradeShift(gaps(-StructureGroundFit.MAX_RELIEF, 0, StructureGroundFit.MAX_RELIEF), 0));
        assertTrue(StructureGroundFit.gradeShift(gaps(-StructureGroundFit.MAX_RELIEF - 1, 0, 0), 0).isEmpty());
        assertTrue(StructureGroundFit.gradeShift(gaps(0, 0, StructureGroundFit.MAX_RELIEF + 1), 0).isEmpty());
    }

    @Test void nothingToFitLeavesPlacementAlone() {
        assertEquals(OptionalInt.of(0), StructureGroundFit.gradeShift(gaps(), 0));
    }

    @Test void blendIsFullAtTheStructureAndGoneAtItsReach() {
        assertEquals(1.0, StructureGrade.weight(0, 30));
        assertEquals(0.5, StructureGrade.weight(15, 30));
        assertEquals(0.0, StructureGrade.weight(30, 30));
        assertEquals(0.0, StructureGrade.weight(90, 30));
    }

    @Test void reachGrowsWithReliefWithinItsLimits() {
        assertEquals(12, StructureGrade.reach(0));
        assertEquals(30, StructureGrade.reach(10));
        assertEquals(StructureGrade.MAX_REACH, StructureGrade.reach(StructureGroundFit.MAX_RELIEF));
    }

    @Test void fillRisesToTheTargetButLeavesDeepCavesAlone() {
        assertTrue(StructureGrade.density(99, 100.0, 80, true, false) > 0);
        assertEquals(0.0, StructureGrade.density(79, 100.0, 80, true, false));
        assertEquals(0.0, StructureGrade.density(100, 100.0, 80, true, false));
    }

    @Test void cutOnlyAppliesAboveTheTarget() {
        assertTrue(StructureGrade.density(121, 120.0, 0, false, true) < 0);
        assertEquals(0.0, StructureGrade.density(120, 120.0, 0, false, true));
    }

    @Test void columnsAtTheirNaturalHeightAreUntouched() {
        assertEquals(0.0, StructureGrade.density(99, 100.0, 80, false, false));
        assertEquals(0.0, StructureGrade.density(121, 100.0, 80, false, false));
    }

    @Test void blendMovesGroundTowardThePlaneWithinItsLimit() {
        assertEquals(100.0, StructureGrade.target(80, 100, 1.0));
        assertEquals(90.0, StructureGrade.target(80, 100, 0.5));
        assertEquals(236.0 - 72.0 + 36.0, StructureGrade.target(164, 300, 1.0));
        assertEquals(128.0, StructureGrade.target(164, 28, 1.0));
    }
}
