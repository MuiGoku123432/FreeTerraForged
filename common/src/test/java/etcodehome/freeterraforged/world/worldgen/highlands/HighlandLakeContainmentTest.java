package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

class HighlandLakeContainmentTest {
    @Test void blockRefinementKeepsTheBowlButFindsANarrowEscape() {
        var plan = bowlPlan(); var candidates = plan.basins();
        java.util.function.DoubleBinaryOperator bowl = (x,z) -> {
            double r = Math.hypot(x-256,z-256);
            return r < 150 ? 30 : r < 200 ? 50 : 10;
        };
        double[] caps = candidates.levelCaps();
        var coarse = candidates.resolve(candidates.prepare(bowl),caps);
        var retained = candidates.resolve(candidates.refine(coarse,body -> bowl),caps.clone());
        assertEquals(1,retained.count());
        assertEquals(40,retained.sample(256,256).water());
        assertEquals(40,retained.sealBank(406,256,38.5),1e-9,"small bank mismatch seals at the same lake level");
        assertEquals(12,retained.sealBank(406,256,10),1e-9,"sealing cannot build a tall retaining wall");
        assertEquals(10,retained.sealBank(410,256,10),1e-9,"sealing is limited to the immediate dry bank");
        assertEquals(43,retained.shapeBank(406,256,70),1e-9,"a steep shore is excavated above its retained water");
        assertEquals(12,retained.shapeBank(406,256,10),1e-9,"shore shaping retains the two-block fill bound");
        assertEquals(70,retained.shapeBank(440,256,70),1e-9,"shore excavation ends outside the local band");
        for (int z = 106; z <= 406; z++) for (int x = 106; x <= 406; x++)
            assertEquals(Math.hypot(x-256,z-256) < 150,retained.sample(x,z) != null,"block membership at "+x+","+z);
        var drained = candidates.resolve(candidates.refine(coarse,body -> (x,z) ->
            x == 257 && z <= 256 ? 10 : bowl.applyAsDouble(x,z)),caps.clone());
        assertEquals(0,drained.count(),"the one-block outlet between coarse samples drains the bowl");
    }

    @Test void openValleyIsNotAContainingBasin() {
        var plan = bowlPlan();
        var resolved = plan.basins().resolve(plan.basins().prepare((x,z) -> 20 + .01*x), plan.basins().levelCaps());
        assertEquals(0, resolved.count(), "a planning outline cannot make an open slope hold water");
        assertNull(resolved.sample(256,256));
    }

    @Test void connectedBowlSurvivesLowGroundOutsideItsDivide() {
        var plan = bowlPlan();
        var resolved = plan.basins().resolve(plan.basins().prepare((x,z) -> {
            double r = Math.hypot(x-256,z-256);
            return r < 150 ? 30 : r < 200 ? 50 : 10;
        }), plan.basins().levelCaps());
        assertEquals(1, resolved.count());
        assertEquals(40, resolved.sample(256,256).water(), 1e-6);
        assertNull(resolved.sample(500,256), "low terrain beyond the divide is not part of the lake");
    }

    @Test void lakeBedDoesNotFillTheLowerValleyFromTheServerReproduction() throws Exception {
        // Frozen measured values at (10112,11500), relative to the world's Y=61 water datum.
        // This stays a regression even when a newer mountain layout moves the original lake.
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var rh = (RollingHighlandsContinent) context.heightmap.continent();
            var cell = new Cell();
            double before = 88.1769 - 61;
            double after = shape(rh, cell, new HighlandBasins.Sample(0, 117.3574 - 61, 12.9682, 1), before);
            assertTrue(cell.hasRiverWater);
            assertTrue(after <= before, "lake bed must not fill the lower valley: " + (after - before));
        }
    }

    @Test void bankFillIsBoundedAndContainedLakeStillCarves() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), 42)) {
            var rh = (RollingHighlandsContinent) context.heightmap.continent();
            var bank = new Cell();
            double before = 87.3557 - 61;
            double after = shape(rh, bank, new HighlandBasins.Sample(0, 117.3574 - 61, -3.9162, 1), before);
            assertFalse(bank.hasRiverWater);
            assertTrue(after - before <= 2.000001, "bank fill was " + (after - before));
            var contained = new Cell();
            assertEquals(30, shape(rh, contained, new HighlandBasins.Sample(0, 40, 10, 1), 32), 1e-6);
            assertTrue(contained.hasRiverWater, "properly supported lake remains wet");
        }
    }

    private static double shape(RollingHighlandsContinent rh, Cell cell, HighlandBasins.Sample sample,
                                double ground) throws Exception {
        var index = new HighlandPlanIndex(bowlPlan());
        var method = RollingHighlandsContinent.class.getDeclaredMethod("shapeLake", Cell.class, double.class, double.class,
            HighlandPlanIndex.class, HighlandBasins.Sample.class, double.class);
        method.setAccessible(true);
        return (double) method.invoke(rh, cell, 256.0, 256.0, index, sample, ground);
    }

    private static HighlandDrainage.Plan bowlPlan() {
        int width = 5, count = width * width;
        double[] heights = new double[count], filled = new double[count], x = new double[count], z = new double[count];
        boolean[] land = new boolean[count], channel = new boolean[count];
        Arrays.fill(heights, 60); Arrays.fill(filled, 60); Arrays.fill(land, true);
        heights[12] = 30; filled[12] = 41; channel[12] = true;
        for (int i = 0; i < count; i++) { x[i] = i % width * 128; z[i] = i / width * 128; }
        var basins = new HighlandBasins(42, 0, 0, width, width, 128, heights, filled, land, channel, x, z);
        return new HighlandDrainage.Plan(0, 0, 128, count, count, List.of(), List.of(), List.of(), List.of(),
            basins, 0, 0, 0, width, new double[count], new double[count], new double[count]);
    }
}
