package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** RH10 drowned-coast gates and reports. */
class HighlandDrownedTest {
    static final int SITE_X = HighlandCoastTest.SITE_X, SITE_Z = HighlandCoastTest.SITE_Z, RADIUS = HighlandCoastTest.RADIUS;
    /**
     * Model-8 cost references for the RH10-15 budget. Transect cost (ns/column, HighlandCoastProbe, user world, warm) and
     * the site owner's plan build (ns) are the values recorded in 11-01-SUMMARY.md; 11-01 recorded only open-sea box
     * columns (1101 ns), so the mixed land and sea box cost (HighlandDrownedProbe.boxNanos over the drowned box with the
     * inert, model-8, coast on the same box, see modelEightBoxCost) was measured in 11-02 Task 2 (box 7037,19260: 11397 ns model 8, 11622 ns model 9) and is recorded here.
     */
    static final double MODEL8_COAST_NS = 10_062, MODEL8_PLAN_NS = 8_516e6, MODEL8_BOX_NS = 11_397;
    /**
     * Test sites fixed in 11-03 (see 11-03-SUMMARY.md): box centres and half side (an 8 km box). RIA is the user-world box
     * (USER_SEED) with the most drowned-scope sea at model 9, FJORD the best ROCKIES PNW + range box; every later gate that
     * needs a ria or fjord site uses these.
     */
    static final int RIA_SITE_X = 20349, RIA_SITE_Z = 28476, FJORD_SITE_X = 8061, FJORD_SITE_Z = 20284, SITE_RADIUS = 4096;
    /**
     * Fjord arm site (11-04, ROCKIES, USER_SEED): the 8 km box with the most fjord-scope flooded land at model 9. The 11-03
     * FJORD_SITE has no FJORD region under the D-01 typing (its region anchors read California, not PNW), so the fjord
     * shape gates use this box; FJORD_SITE stays for the ria-shaped checks.
     */
    static final int FJORD_ARM_SITE_X = 34685, FJORD_ARM_SITE_Z = -12484;
    /**
     * The ROCKIES fjord boxes (centres, 8 km boxes, USER_SEED): the arm site plus the other 4 km tiles of the 32 km scan with
     * the most fjord-scope flooded land (scan around the 11-01 box centre, see 11-04-SUMMARY.md). Shape gates pool their arms.
     */
    static final int[][] FJORD_BOXES = {{FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z}, {14205, 48956}, {20349, 30524}};
    /**
     * Ria bay boxes (user world, USER_SEED, 8 km boxes): the 11-03 ria site and its second candidate, widened (the two hold
     * fewer than 5 bays) with the six 8 km tiles of a scan of the user world (-32 to 64 km east, -48 to 64 km south) that have
     * the most coastline inside a drowned region (19.9, 17.1, 15.4, 13.3, 13.3 and 13.0 km); the ranking reads region weights
     * on the standalone coast, so it does not depend on the bay shape being tuned.
     */
    static final int[][] RIA_BOXES = {{RIA_SITE_X, RIA_SITE_Z}, {8061, 24380}, {12288, -36864}, {45056, 20480}, {-28672, 20480}, {45056, 28672}, {61440, 61440}, {-4096, 53248}};
    /** Centre of a sound basin (ROCKIES, USER_SEED): the coast point of region (-9, 2), one of the fjord regions that drew SOUND_CHANCE. */
    static final int SOUND_SITE_X = -46448, SOUND_SITE_Z = 13654;
    /** D-02: drowned share of the RH9 coastline, inclusive bounds. */
    static final double MIN_SHARE = .20, MAX_SHARE = .30;

    /**
     * D-02: where the bound coast reports no drowned region its edge is bit-for-bit the unbound standalone (RH9)
     * coast edge; inside a region the edge is never raised. Share half: regions cover 20-30% of the RH9 coastline
     * (traced on the unbound coast) in the user box and in the ROCKIES box.
     */
    @Test void drownedShareAndIdentity() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var bound = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        var random = new Random(17);
        int outside = 0, inside = 0, lowered = 0;
        for (int i = 0; i < 100_000; i++) {
            double x = SITE_X + (random.nextDouble() * 2 - 1) * 40_000, z = SITE_Z + (random.nextDouble() * 2 - 1) * 40_000;
            var sample = bound.sample(x, z);
            double reference = standalone.sample(x, z).edge();
            if (sample.drowned() == 0) {
                outside++;
                assertEquals(Double.doubleToLongBits(reference), Double.doubleToLongBits(sample.edge()), "edge differs outside drowned regions at " + x + "," + z);
            } else {
                inside++;
                assertTrue(sample.edge() <= reference, "drowned edge above the RH9 edge at " + x + "," + z);
                if (sample.edge() < reference) lowered++;
            }
        }
        assertTrue(outside > 50_000 && inside > 0 && lowered > 0, "outside=" + outside + " inside=" + inside + " lowered=" + lowered);
        double user = HighlandDrownedProbe.drownedShare(context, SITE_X, SITE_Z, RADIUS), rockies;
        try (var other = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            rockies = HighlandDrownedProbe.drownedShare(other, SITE_X, SITE_Z, RADIUS);
        }
        System.out.printf(java.util.Locale.ROOT, "RH10 drowned share user=%.1f%% rockies=%.1f%%%n", 100 * user, 100 * rockies);
        assertTrue(user >= MIN_SHARE && user <= MAX_SHARE && rockies >= MIN_SHARE && rockies <= MAX_SHARE,
            String.format(java.util.Locale.ROOT, "drowned share outside 20-30%%: user=%.1f%% rockies=%.1f%%", 100 * user, 100 * rockies));
    }

    /**
     * D-01: fjord weight exists only under FJORD regions, whose anchors are PNW coast with a range core nearby. In the ROCKIES
     * fjord site box at least one region is a fjord, and every point with fjord weight has such an anchor.
     */
    @Test void fjordsOnlyWhereRangesMeetCoolCoast() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var coast = ((RollingHighlandsContinent) rockies.heightmap.continent()).coast();
            var drowned = coast.drowned();
            var land = drowned.land();
            int fjordPoints = 0;
            for (int z = FJORD_ARM_SITE_Z - SITE_RADIUS; z <= FJORD_ARM_SITE_Z + SITE_RADIUS; z += 64) for (int x = FJORD_ARM_SITE_X - SITE_RADIUS; x <= FJORD_ARM_SITE_X + SITE_RADIUS; x += 64) {
                if (drowned.fjord(x, z) <= 0) continue;
                fjordPoints++;
                var region = drowned.dominant(x, z, true);
                String where = x + "," + z;
                assertTrue(region != null && region.fjord(), "no fjord region under fjord weight at " + where);
                assertEquals(HighlandCoastProfile.PNW, coast.profile().climateClassAt(region.cx(), region.cz()), "fjord anchor is not PNW at " + where);
                double half = HighlandDrowned.REGION_SIZE / 2, core = land.rangeCore(region.cx(), region.cz());
                for (double[] probe : new double[][]{{half, 0}, {-half, 0}, {0, half}, {0, -half}})
                    core = Math.max(core, land.rangeCore(region.cx() + probe[0], region.cz() + probe[1]));
                assertTrue(core >= HighlandDrowned.FJORD_RANGE_CORE, "fjord anchor without a range core at " + where + ": " + core);
            }
            assertTrue(fjordPoints > 0, "no fjord region in the ROCKIES fjord site box");
        }
    }

    /** D-03: the low Rolling world (no ranges) gets rias only: fjord weight is 0 at 100,000 random points while drowned regions exist. */
    @Test void lowWorldGetsRiasOnly() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var drowned = ((RollingHighlandsContinent) context.heightmap.continent()).coast().drowned();
        var random = new Random(23);
        int rias = 0;
        for (int i = 0; i < 100_000; i++) {
            double x = SITE_X + (random.nextDouble() * 2 - 1) * 40_000, z = SITE_Z + (random.nextDouble() * 2 - 1) * 40_000;
            assertEquals(0, drowned.fjord(x, z), "fjord weight in the low world at " + x + "," + z);
            if (drowned.weight(x, z) > 0) rias++;
        }
        assertTrue(rias > 0, "no drowned regions in the low world");
    }

    /** Pitfall 4: plans build without exceptions where regions meet the coast, on the user seed and four other seeds. */
    @Test void plansBuildOnFiveSeeds() throws Exception {
        long[] seeds = {HighlandPlanformProbe.USER_SEED, 1L, 42L, -314159L, 20261107L};
        for (long seed : seeds) {
            try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), seed)) {
                var continent = (RollingHighlandsContinent) context.heightmap.continent();
                var coast = continent.coast();
                double coastEdge = context.preset.world().controlPoints.coast;
                HighlandCoast.Sample found = null;
                for (int ring = 4096; ring <= 65536 && found == null; ring += 4096) {
                    for (int a = 0; a < 64 && found == null; a++) {
                        double angle = 2 * Math.PI * a / 64;
                        for (int d = -2048; d <= 2048 && found == null; d += 64) {
                            var s = coast.sample(Math.cos(angle) * (ring + d), Math.sin(angle) * (ring + d));
                            if (s.drowned() >= HighlandDrownedProbe.DROWNED_WEIGHT && s.edge() > 0 && s.edge() < coastEdge + .1) found = s;
                        }
                    }
                }
                assertTrue(found != null, "no drowned coast point near the origin for seed " + seed);
                var plan = continent.planOf(found.ownerX(), found.ownerZ()).plan();
                assertTrue(plan.landNodes() > 0, "seed " + seed + " owner " + found.ownerX() + "," + found.ownerZ() + " has no land nodes");
            }
        }
    }

    /** Arm water reads the ocean biome band: at least 95% of the sea columns inside drowned regions (weight at least .5, depth at least 1). */
    @Test void oceanBandInArms() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var cell = new Cell();
        int wet = 0, band = 0;
        for (int z = SITE_Z - RADIUS; z <= SITE_Z + RADIUS; z += 16) for (int x = SITE_X - RADIUS; x <= SITE_X + RADIUS; x += 16) {
            var sample = coast.sample(x, z);
            if (sample.edge() > coastEdge || sample.drowned() < HighlandDrownedProbe.DROWNED_WEIGHT) continue;
            context.lookup.applyCell(cell.reset(), x, z, false, true);
            if ((context.levels.water - cell.height) * context.levels.terrainScaleFactor < 1) continue;
            wet++;
            if (CellSampler.Field.CONTINENT.read(cell, context.heightmap) < Continentalness.COAST.min()) band++;
        }
        assertTrue(wet >= 500, "too few drowned-scope sea columns: " + wet);
        assertTrue(band >= .95 * wet, band + " of " + wet + " drowned-scope sea columns read the ocean band");
    }

    /**
     * RH10-15 cost budget (probe tag, machine-dependent): warm coast-transect ns/column at most 1.15 x the model-8 value,
     * the worst drowned 2 km box (land and sea columns) at most 1.25 x, the site owner's plan build at most 1.10 x. The
     * plan ratio is taken like for like, against a fresh context whose drowned transform is unbound (the model-8 coast),
     * both rebuilt three times in this JVM: a single rebuild varies by about 15% with JIT state, so the 11-01 absolute
     * value (MODEL8_PLAN_NS) is only printed as a reference.
     */
    @Tag("probe")
    @Test void drownedCostWithinBudget() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm the owner plans
        double transect = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS).nanosPerColumn();
        var report = HighlandDrownedProbe.measure(context, SITE_X, SITE_Z, RADIUS);
        double box = report.drownedBoxNanos(), plan9 = 0, plan8 = 0;
        var model9 = (RollingHighlandsContinent) context.heightmap.continent();
        try (var inert = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var model8 = (RollingHighlandsContinent) inert.heightmap.continent();
            model8.coast().bindDrowned(null);
            for (int pass = 0; pass < 3; pass++) { // warm, then the last pass is the number
                plan8 = model8.rebuildAt(SITE_X, SITE_Z).plan().buildNanos();
                plan9 = model9.rebuildAt(SITE_X, SITE_Z).plan().buildNanos();
            }
        }
        String ratios = String.format(java.util.Locale.ROOT,
            "RH10 cost transect=%.0f ns (x%.3f) box=%.0f ns at (%d,%d) (x%.3f) plan=%.0f ms vs model-8 %.0f ms (x%.3f; 11-01 reference %.0f ms, x%.3f)",
            transect, transect / MODEL8_COAST_NS, box, report.boxX(), report.boxZ(), box / MODEL8_BOX_NS, plan9 / 1e6, plan8 / 1e6, plan9 / plan8,
            MODEL8_PLAN_NS / 1e6, plan9 / MODEL8_PLAN_NS);
        System.out.println(ratios);
        Files.writeString(Path.of("build/rh10-cost.txt"), ratios + "\n");
        assertTrue(transect <= 1.15 * MODEL8_COAST_NS && box <= 1.25 * MODEL8_BOX_NS && plan9 <= 1.10 * plan8, "cost over budget: " + ratios);
    }

    /**
     * Measures the model-8 box cost: the box with the highest drowned weight, on a fresh context whose drowned transform
     * is unbound before anything samples it (the RH9 coast), against a fresh model-9 context. Prints the numbers to record.
     */
    @Tag("probe")
    @Test void modelEightBoxCost() throws Exception {
        try (var bound = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
             var inert = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            ((RollingHighlandsContinent) inert.heightmap.continent()).coast().bindDrowned(null);
            var report = HighlandDrownedProbe.measure(bound, SITE_X, SITE_Z, RADIUS);
            double model9 = HighlandDrownedProbe.boxNanos(bound, report.boxX(), report.boxZ());
            double model8 = HighlandDrownedProbe.boxNanos(inert, report.boxX(), report.boxZ());
            var model9Plan = (RollingHighlandsContinent) bound.heightmap.continent();
            var model8Plan = (RollingHighlandsContinent) inert.heightmap.continent();
            long plan9 = 0, plan8 = 0;
            for (int pass = 0; pass < 3; pass++) { // warm, then the last pass is the number
                plan8 = model8Plan.rebuildAt(SITE_X, SITE_Z).plan().buildNanos();
                plan9 = model9Plan.rebuildAt(SITE_X, SITE_Z).plan().buildNanos();
            }
            String line = String.format(java.util.Locale.ROOT, "RH10 box (%d,%d) model8=%.0f ns model9=%.0f ns ratio=%.3f; plan build model8=%.0f ms model9=%.0f ms ratio=%.3f",
                report.boxX(), report.boxZ(), model8, model9, model9 / model8, plan8 / 1e6, plan9 / 1e6, (double) plan9 / plan8);
            System.out.println(line);
            Files.writeString(Path.of("build/rh10-model8-box.txt"), line + "\n");
        }
    }

    /**
     * D-17: the arms are sea in the renderer. In the user box some columns that are land under model 8 are sea under
     * model 9; they take the ocean branch (edge at or below the coast point, ground under water, no river water, an
     * ocean-band continentalness) and no land column anywhere ends up below water.
     */
    @Test void riaArmsReachTheGround() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var bound = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        var drowned = new ArrayList<int[]>();
        for (int z = SITE_Z - RADIUS; z <= SITE_Z + RADIUS; z += 16) for (int x = SITE_X - RADIUS; x <= SITE_X + RADIUS; x += 16)
            if (standalone.sample(x, z).edge() > coastEdge && bound.sample(x, z).edge() <= coastEdge) drowned.add(new int[]{x, z});
        assertTrue(drowned.size() >= 500, "too few drowned columns in the user box: " + drowned.size());
        var cell = new Cell();
        int stride = drowned.size() / 500;
        for (int i = 0; i < 500; i++) {
            int[] at = drowned.get(i * stride);
            context.lookup.applyCell(cell.reset(), at[0], at[1], false, true);
            String where = at[0] + "," + at[1];
            assertTrue(cell.continentEdge <= coastEdge, "continentEdge above the coast point at " + where);
            assertTrue(cell.height < context.levels.water, "drowned column not under water at " + where + ": " + cell.height);
            assertTrue(!cell.hasRiverWater, "river water in an arm at " + where);
            assertTrue(CellSampler.Field.CONTINENT.read(cell, context.heightmap) < Continentalness.COAST.min(), "arm water is not an ocean band at " + where);
        }
    }

    /**
     * The plan's land mask reads the transformed edge: no channel link between two land nodes runs through arm water, at
     * the ria site (user world) and the fjord site (ROCKIES), where the conservative mask covers arms narrower than the lattice.
     */
    @Test void planAndRenderAgree() throws Exception {
        assertPlanAgrees(WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED), SITE_X, SITE_Z);
        assertPlanAgrees(WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED), RIA_SITE_X, RIA_SITE_Z);
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            assertPlanAgrees(rockies, FJORD_SITE_X, FJORD_SITE_Z);
            assertPlanAgrees(rockies, FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z);
        }
    }

    private static void assertPlanAgrees(GeneratorContext context, int x, int z) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var plan = continent.planAt(x, z).plan();
        var nodes = plan.nodes();
        int links = 0;
        for (var node : nodes) {
            if (node.parent() < 0) continue;
            var parent = nodes.get(node.parent());
            if (coast.sample(node.x(), node.z()).edge() <= coastEdge || coast.sample(parent.x(), parent.z()).edge() <= coastEdge) continue;
            links++;
            double length = Math.hypot(parent.x() - node.x(), parent.z() - node.z());
            for (double d = 0; d <= length; d += 8) {
                double t = length > 0 ? d / length : 0, px = node.x() + (parent.x() - node.x()) * t, pz = node.z() + (parent.z() - node.z()) * t;
                assertTrue(coast.sample(px, pz).edge() > coastEdge, "link crosses sea water at " + px + "," + pz + " (site " + x + "," + z + ")");
            }
        }
        assertTrue(links > 0 && plan.landNodes() > 0, "no land links in the site plan at " + x + "," + z);
    }

    /**
     * D-06/D-17: fjord water is deep because the edge falls below the shallow-ocean point, not because anything is carved.
     * Over the fjord-scope arm columns of the ROCKIES site box (flooded land, fjord weight at least .5) the median depth is at
     * least twice the shelf depth and at least 95% of the columns read the ocean continentalness band.
     */
    @Test void fjordWaterIsDeep() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var coast = ((RollingHighlandsContinent) rockies.heightmap.continent()).coast();
            var standalone = new HighlandCoast(rockies);
            double coastEdge = rockies.preset.world().controlPoints.coast;
            double shelf = etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators.shallowOceanDepth(rockies.preset.world().properties.oceanDepth);
            var cell = new Cell();
            var depths = new ArrayList<Double>();
            int band = 0;
            for (int z = FJORD_ARM_SITE_Z - SITE_RADIUS; z <= FJORD_ARM_SITE_Z + SITE_RADIUS; z += 16) for (int x = FJORD_ARM_SITE_X - SITE_RADIUS; x <= FJORD_ARM_SITE_X + SITE_RADIUS; x += 16) {
                if (coast.sample(x, z).edge() > coastEdge || standalone.sample(x, z).edge() <= coastEdge || coast.drowned().fjord(x, z) < HighlandDrownedProbe.DROWNED_WEIGHT) continue;
                rockies.lookup.applyCell(cell.reset(), x, z, false, true);
                double depth = -(cell.height - rockies.levels.water) * rockies.levels.terrainScaleFactor;
                if (depth < 1) continue;
                depths.add(depth);
                if (CellSampler.Field.CONTINENT.read(cell, rockies.heightmap) < Continentalness.COAST.min()) band++;
            }
            assertTrue(depths.size() >= 300, "too few fjord-scope sea columns: " + depths.size());
            double[] sorted = depths.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double median = HighlandCoastProbe.quantile(sorted, .5);
            System.out.printf(java.util.Locale.ROOT, "RH10 fjord depth median=%.1f shelf=%.0f p10=%.1f p90=%.1f n=%d oceanBand=%.1f%%%n", median, shelf,
                HighlandCoastProbe.quantile(sorted, .1), HighlandCoastProbe.quantile(sorted, .9), depths.size(), 100.0 * band / depths.size());
            assertTrue(median >= 2 * shelf, "fjord water too shallow: median " + median + " vs 2 x shelf " + 2 * shelf);
            assertTrue(band >= .95 * depths.size(), band + " of " + depths.size() + " fjord-scope columns read the ocean band");
        }
    }

    /**
     * Steepening keeps the edge monotone across walls (Pitfall 7): at 200 positions inside fjord regions the remap of a dense
     * run of edge values (the coast point to the end of the wall band and beyond) never decreases, is the identity at the
     * coast point and from the band's inland end on, and never lowers an edge.
     */
    @Test void steepeningIsMonotone() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var coast = ((RollingHighlandsContinent) rockies.heightmap.continent()).coast();
            var drowned = coast.drowned();
            double coastEdge = rockies.preset.world().controlPoints.coast, wall = HighlandDrowned.FJORD_WALL * HighlandCoast.EDGE_PER_BLOCK;
            var random = new Random(31);
            int positions = 0;
            double steepest = 0;
            for (int tries = 0; positions < 200 && tries < 200_000; tries++) {
                double x = FJORD_ARM_SITE_X + (random.nextDouble() * 2 - 1) * 6_000, z = FJORD_ARM_SITE_Z + (random.nextDouble() * 2 - 1) * 6_000;
                double f = drowned.fjord(x, z);
                if (f <= 0) continue;
                positions++;
                double previous = coastEdge;
                assertEquals(coastEdge, drowned.steepen(coastEdge, f), "steepening moves the coast point at " + x + "," + z);
                for (int k = 1; k <= 2000; k++) {
                    double edge = coastEdge + 1.5 * wall * k / 2000, steep = drowned.steepen(edge, f);
                    assertTrue(steep >= previous - 1e-12, "steepened edge decreases inland at " + x + "," + z + " edge " + edge);
                    assertTrue(steep >= edge, "steepening lowers the edge at " + x + "," + z);
                    if (edge >= coastEdge + wall) assertEquals(edge, steep, "steepening acts beyond the wall band");
                    steepest = Math.max(steepest, (steep - previous) / (1.5 * wall / 2000));
                    previous = steep;
                }
            }
            assertTrue(positions == 200, "too few fjord positions: " + positions);
            assertTrue(steepest > 1.5, "steepening never steepens: max slope " + steepest);
        }
    }

    /** Land stays at or above sea level (D-17): the narrowLandHasNoSeaPits logic over the fjord site box finds 0 pits. */
    @Test void fjordLandHasNoSeaPits() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var coast = ((RollingHighlandsContinent) rockies.heightmap.continent()).coast();
            double coastEdge = rockies.preset.world().controlPoints.coast;
            var cell = new Cell();
            int land = 0, pits = 0;
            String example = "";
            for (int z = FJORD_ARM_SITE_Z - SITE_RADIUS; z <= FJORD_ARM_SITE_Z + SITE_RADIUS; z += 8) for (int x = FJORD_ARM_SITE_X - SITE_RADIUS; x <= FJORD_ARM_SITE_X + SITE_RADIUS; x += 8) {
                double edge = coast.sample(x, z).edge();
                if (edge <= coastEdge || edge - coastEdge >= HighlandDrainage.COAST_BAND || coast.drowned().fjord(x, z) <= 0) continue;
                rockies.lookup.applyCell(cell.reset(), x, z, false, true);
                if (cell.hasRiverWater) continue;
                land++;
                if ((cell.height - rockies.levels.water) * rockies.levels.terrainScaleFactor < -.5) { pits++; example = x + "," + z; }
            }
            assertTrue(land >= 500, "too little fjord coastal land to judge: " + land);
            assertEquals(0, pits, pits + " of " + land + " fjord coastal land columns below sea level, e.g. " + example);
        }
    }

    /**
     * D-05, D-06, D-07 (ROCKIES fjord boxes, arms pooled; the plan widens the site box to a scan box when it has fewer than 5
     * arms, here the boxes are the scan's richest tiles): at least 5 arms, length median 1-4 km and p95 at most 8 km; trunk
     * (widest reach) median width 250-600 and side arm median width 80-250, so side arms are narrower; at least 60% of fjords
     * have a side arm; the land 64 blocks from fjord water is at least half as high as 512 blocks inland.
     */
    @Test void fjordShape() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var arms = fjordCensus(rockies);
            var walls = HighlandDrownedProbe.walls(rockies, FJORD_BOXES, SITE_RADIUS);
            System.out.println("RH10 fjord shape " + arms.summary() + " | " + walls.summary());
            assertTrue(arms.arms() >= 5, "too few fjord arms: " + arms.arms());
            double median = HighlandCoastProbe.quantile(arms.lengths(), .5), p95 = HighlandCoastProbe.quantile(arms.lengths(), .95);
            assertTrue(median >= 1000 && median <= 4000 && p95 <= 8000, String.format(java.util.Locale.ROOT, "arm length median %.0f p95 %.0f outside 1-4 km / 8 km", median, p95));
            double trunk = HighlandCoastProbe.quantile(arms.trunkWidths(), .5), side = HighlandCoastProbe.quantile(arms.sideWidths(), .5);
            assertTrue(trunk >= 250 && trunk <= 600, "trunk width median " + trunk + " outside 250-600");
            assertTrue(arms.sideWidths().length > 0 && side >= 80 && side <= 250, "side arm width median " + side + " outside 80-250");
            assertTrue(arms.armsWithSide() >= .6 * arms.arms(), "fjords with a side arm: " + arms.armsWithSide() + " of " + arms.arms());
            assertTrue(walls.transects() >= 300 && walls.near() >= .5 * walls.far(), "fjord walls too low: " + walls.summary());
        }
    }

    /** D-07: sounds are occasional: water at least 1000 blocks across exists in the fjord boxes, in at most half of the fjord regions that hold arms. */
    @Test void soundsAreOccasional() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var arms = fjordCensus(rockies);
            System.out.println("RH10 sounds " + arms.summary());
            assertTrue(arms.maxWidth() >= HighlandDrownedProbe.SOUND_WIDTH && arms.soundCells() > 0, "no sound in the fjord boxes: widest water " + arms.maxWidth());
            assertTrue(arms.armRegions() >= 2 && arms.soundRegions() <= arms.armRegions() / 2.0,
                "sounds in " + arms.soundRegions() + " of " + arms.armRegions() + " fjord regions");
            var basin = HighlandDrownedProbe.fjords(rockies, SOUND_SITE_X, SOUND_SITE_Z, SITE_RADIUS);
            System.out.println("RH10 sound basin " + basin.summary());
            assertTrue(basin.maxWidth() >= HighlandDrownedProbe.SOUND_WIDTH, "the sound basin at " + SOUND_SITE_X + "," + SOUND_SITE_Z + " opens no water 1000 blocks across: " + basin.maxWidth());
        }
    }

    /**
     * D-16: fjord shores are rock walls in the PNW palette: at least 70% of arm shore columns are cliff-dominant, and the river
     * mouths that enter fjord water (found within 32 blocks of arm shores) meet the sea at sea level (no water step of 2 or more).
     */
    @Test void fjordShoresAreRock() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var shores = HighlandDrownedProbe.shores(rockies, FJORD_BOXES, SITE_RADIUS);
            System.out.println("RH10 fjord " + shores.summary());
            assertTrue(shores.samples() >= 500, "too few fjord shore columns: " + shores.samples());
            assertTrue(shores.cliff() >= .7 * shores.samples(), shores.summary());
            assertEquals(0, shores.steps(), "river mouth water steps at fjord heads: " + shores.summary());
        }
    }

    /**
     * D-13, D-14 (user world, ria boxes pooled; at least 5 bays): the largest bay is at least 1 km across and none exceeds 5 km
     * (inscribed width); the mouth (water width along the standalone coastline) is at most half the inner width for at least
     * 70% of the bays; at least half of the bays have two or more heads; every bay lies in one owner and reaches the open sea.
     */
    @Test void baysBranchAndNarrowAtTheMouth() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var bays = bayCensus(context);
        System.out.println("RH10 bays " + bays.summary());
        assertTrue(bays.count() >= 5, "too few bays: " + bays.summary());
        double widest = bays.inners()[bays.inners().length - 1];
        assertTrue(widest >= 1000 && widest <= 5000, "widest bay " + widest + " outside 1-5 km: " + bays.summary());
        assertTrue(java.util.Arrays.stream(bays.inners()).filter(w -> w >= 1000).count() >= 3, "fewer than 3 bays 1 km across: " + bays.summary());
        assertTrue(bays.narrowMouth() >= .7 * bays.count(), "narrow mouths: " + bays.narrowMouth() + " of " + bays.count() + ": " + bays.summary());
        assertTrue(bays.branching() >= .5 * bays.count(), "branching bays: " + bays.branching() + " of " + bays.count() + ": " + bays.summary());
        assertEquals(bays.count(), bays.singleOwner(), "bays across owners: " + bays.summary());
        assertEquals(bays.count(), bays.connected(), "bays closed to the sea: " + bays.summary());
    }

    /**
     * D-16 (ria boxes pooled): bay shores are calmer than the open RH9 coast. The beach-dominant share of bay shore columns is at
     * least 10 points above the share on the coast outside every drowned region, the median dry beach width of bay beaches is
     * at most 0.7 times the open one, and at least half of the bays have marsh (WETLAND) columns on the low land inside 24 blocks
     * of their shore (at least 3 each).
     */
    @Test void innerShores() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var shores = HighlandDrownedProbe.bayShores(context, RIA_BOXES, SITE_RADIUS);
        System.out.println("RH10 bay " + shores.summary());
        assertTrue(shores.samples() >= 500 && shores.outsideSamples() >= 500, "too few shore columns: " + shores.summary());
        double bay = (double) shores.beach() / shores.samples(), open = (double) shores.outsideBeach() / shores.outsideSamples();
        assertTrue(bay >= open + .10, "bay shores not more beach-dominant than open coast: " + shores.summary());
        double bayWidth = HighlandCoastProbe.quantile(shores.widths(), .5), openWidth = HighlandCoastProbe.quantile(shores.outsideWidths(), .5);
        assertTrue(bayWidth <= .7 * openWidth, "bay beaches not smaller: " + shores.summary());
        assertTrue(shores.marshBays() >= .5 * shores.bays(), "marsh on too few bays: " + shores.summary());
    }

    /**
     * Shore tags in drowned regions do not change with the lookup path: at 200 or more bay shore columns (and up to 28 blocks
     * inland of each), context.lookup.applyCell and context.heightmap.applyPrecise agree on the BEACH and WETLAND tags.
     */
    @Test void innerShoresArePathIndependent() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var a = new Cell();
        var b = new Cell();
        int shoreColumns = 0, checked = 0, differ = 0, beaches = 0, marsh = 0;
        for (int[] box : RIA_BOXES) {
            var shore = HighlandDrownedProbe.armShore(context, box, SITE_RADIUS, false);
            int stride = Math.max(1, shore.size() / 60);
            for (int k = 0; k < shore.size(); k += stride) {
                int[] at = shore.get(k);
                double e = coast.sample(at[0], at[1]).edge();
                double gx = coast.sample(at[0] + 8, at[1]).edge() - coast.sample(at[0] - 8, at[1]).edge(), gz = coast.sample(at[0], at[1] + 8).edge() - coast.sample(at[0], at[1] - 8).edge(), norm = Math.hypot(gx, gz);
                if (norm == 0 || !(e > 0)) continue;
                gx /= norm; gz /= norm;
                shoreColumns++;
                for (int d = 0; d <= 28; d += 4) {
                    int x = (int) Math.round(at[0] + gx * d), z = (int) Math.round(at[1] + gz * d);
                    context.lookup.applyCell(a.reset(), x, z, false, true);
                    context.heightmap.applyPrecise(b.reset(), x, z, true);
                    checked++;
                    boolean beachA = a.terrain == TerrainType.BEACH, beachB = b.terrain == TerrainType.BEACH;
                    boolean marshA = a.terrain == TerrainType.WETLAND, marshB = b.terrain == TerrainType.WETLAND;
                    if (beachA) beaches++;
                    if (marshA) marsh++;
                    if (beachA != beachB || marshA != marshB) differ++;
                }
            }
        }
        System.out.println("RH10 bay path independence shoreColumns=" + shoreColumns + " checked=" + checked + " beach=" + beaches + " marsh=" + marsh + " differ=" + differ);
        assertTrue(shoreColumns >= 200 && beaches > 0 && marsh > 0, "too few drowned-region shore columns: " + shoreColumns + " (beach " + beaches + ", marsh " + marsh + ")");
        assertEquals(0, differ, differ + " of " + checked + " bay shore columns change the BEACH or WETLAND tag with the lookup path");
    }

    /**
     * 11-05 spike (Open Question 5, A12), probe tag: the biome of marsh (WETLAND) columns on bay shores, read the way the biome
     * censuses read it (the Field values of the column through the vanilla overworld parameter table), next to beach columns of
     * the same shores. Writes build/rh10-marsh-biomes.txt.
     */
    @Tag("probe")
    @Test void reportsMarshBiomes() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var table = BiomeCensusProbe.overworld();
        var cell = new Cell();
        var counts = new java.util.TreeMap<String, Integer>();
        var lines = new StringBuilder();
        int marsh = 0, shown = 0;
        for (int[] box : RIA_BOXES) {
            var shore = HighlandDrownedProbe.armShore(context, box, SITE_RADIUS, false);
            int stride = Math.max(1, shore.size() / 120);
            for (int k = 0; k < shore.size(); k += stride) {
                int[] at = shore.get(k);
                double e = coast.sample(at[0], at[1]).edge();
                double gx = coast.sample(at[0] + 8, at[1]).edge() - coast.sample(at[0] - 8, at[1]).edge(), gz = coast.sample(at[0], at[1] + 8).edge() - coast.sample(at[0], at[1] - 8).edge(), norm = Math.hypot(gx, gz);
                if (norm == 0 || !(e > 0)) continue;
                gx /= norm; gz /= norm;
                for (int d = 4; d <= 24; d += 4) {
                    int x = (int) Math.round(at[0] + gx * d), z = (int) Math.round(at[1] + gz * d);
                    context.heightmap.apply(cell.reset(), x, z, true);
                    boolean wet = cell.terrain == TerrainType.WETLAND;
                    if (!wet && cell.terrain != TerrainType.BEACH) continue;
                    float t = CellSampler.Field.TEMPERATURE.read(cell, context.heightmap), h = CellSampler.Field.MOISTURE.read(cell, context.heightmap),
                        c = CellSampler.Field.CONTINENT.read(cell, context.heightmap), er = CellSampler.Field.EROSION.read(cell, context.heightmap), w = CellSampler.Field.WEIRDNESS.read(cell, context.heightmap);
                    String biome = table.findValue(net.minecraft.world.level.biome.Climate.target(t, h, c, er, 0, w)).location().getPath();
                    counts.merge((wet ? "WETLAND " : "BEACH ") + biome, 1, Integer::sum);
                    if (wet) marsh++;
                    if (wet && shown < 16) {
                        shown++;
                        lines.append(String.format(java.util.Locale.ROOT, "marsh %d,%d d=%d biome=%s t=%.2f h=%.2f c=%.2f e=%.2f w=%.2f height=%.1f%n", x, z, d, biome, t, h, c, er, w, (cell.height - context.levels.water) * context.levels.terrainScaleFactor));
                    }
                }
            }
        }
        String out = lines + "census " + counts + "\nmarsh columns " + marsh + "\n";
        System.out.print(out);
        Files.writeString(Path.of("build/rh10-marsh-biomes.txt"), out);
    }

    private static HighlandDrownedProbe.Bays bayCensus(GeneratorContext context) {
        var parts = new ArrayList<HighlandDrownedProbe.Bays>();
        for (int[] box : RIA_BOXES) parts.add(HighlandDrownedProbe.bays(context, box[0], box[1], SITE_RADIUS));
        return HighlandDrownedProbe.poolBays(parts);
    }

    /** 11-05: ria bay census per box and pooled, plus renders of the ria site (probe tag): build/rh10-bays.txt and bay-*.png. */
    @Tag("probe")
    @Test void reportsBays() throws Exception {
        var dir = Path.of("build/rh10-arms");
        Files.createDirectories(dir);
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        String out = "";
        var parts = new ArrayList<HighlandDrownedProbe.Bays>();
        for (int[] box : RIA_BOXES) {
            var census = HighlandDrownedProbe.bays(context, box[0], box[1], SITE_RADIUS);
            parts.add(census);
            out += "box " + box[0] + "," + box[1] + " " + census.summary() + " heads=" + java.util.Arrays.toString(census.heads()) + "\n";
        }
        out += "pooled " + HighlandDrownedProbe.poolBays(parts).summary() + "\n";
        String tag = System.getenv().getOrDefault("FTF_BAYS_TAG", "now");
        for (int[] box : RIA_BOXES) {
            HighlandPlanformProbe.render(context, box[0], box[1], 2 * SITE_RADIUS, 8, dir.resolve("bay-" + tag + "-" + box[0] + "," + box[1] + ".png"));
            HighlandDrownedProbe.maskImage(context, box[0], box[1], 2 * SITE_RADIUS, 8, dir.resolve("bay-mask-" + tag + "-" + box[0] + "," + box[1] + ".png"));
        }
        System.out.print(out);
        Files.writeString(Path.of("build/rh10-bays.txt"), out);
    }

    private static HighlandDrownedProbe.Fjords fjordCensus(GeneratorContext rockies) {
        var parts = new ArrayList<HighlandDrownedProbe.Fjords>();
        for (int[] box : FJORD_BOXES) parts.add(HighlandDrownedProbe.fjords(rockies, box[0], box[1], SITE_RADIUS));
        return HighlandDrownedProbe.pool(parts);
    }

    /** 11-04 review: fjord arm census over the fjord boxes and renders of the arm site (probe tag): build/rh10-fjords.txt and fjord-*.png. */
    @Tag("probe")
    @Test void reportsFjords() throws Exception {
        var dir = Path.of("build/rh10-arms");
        Files.createDirectories(dir);
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            String out = "";
            var parts = new ArrayList<HighlandDrownedProbe.Fjords>();
            for (int[] box : FJORD_BOXES) {
                var census = HighlandDrownedProbe.fjords(rockies, box[0], box[1], SITE_RADIUS);
                parts.add(census);
                out += "box " + box[0] + "," + box[1] + " " + census.summary() + "\n";
            }
            out += "pooled " + HighlandDrownedProbe.pool(parts).summary() + "\n" + "pooled " + HighlandDrownedProbe.walls(rockies, FJORD_BOXES, SITE_RADIUS).summary() + "\n"
                + "pooled " + HighlandDrownedProbe.shores(rockies, FJORD_BOXES, SITE_RADIUS).summary() + "\n";
            HighlandPlanformProbe.render(rockies, FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z, 2 * SITE_RADIUS, 8, dir.resolve("fjord-now.png"));
            HighlandDrownedProbe.maskImage(rockies, FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z, 2 * SITE_RADIUS, 8, dir.resolve("fjord-mask-now.png"));
            System.out.print(out);
            Files.writeString(Path.of("build/rh10-fjords.txt"), out);
        }
    }

    /** P0: the drowned-coast metrics and the RH9 coast cost on today's tree, for the user world and ROCKIES. */
    @Tag("probe")
    @Test void reportsDrowned() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        HighlandDrownedProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm the owner plans so ns/column is column cost
        var user = HighlandDrownedProbe.measure(context, SITE_X, SITE_Z, RADIUS);
        System.out.println(user.summary("user"));
        Files.writeString(Path.of("build/rh10-drowned-user.txt"), user.summary("user") + "\n");
        assertTrue(user.samples() > 0, "no sea in the user box: " + user.summary("user"));

        HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm
        var coast = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
        System.out.println(coast.summary("user"));
        Files.writeString(Path.of("build/rh10-coast-baseline.txt"), coast.summary("user") + "\n");

        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandDrownedProbe.measure(rockies, SITE_X, SITE_Z, RADIUS);
            System.out.println(report.summary("rockies"));
            Files.writeString(Path.of("build/rh10-drowned-rockies.txt"), report.summary("rockies") + "\n");
            assertTrue(report.samples() > 0, "no sea in the ROCKIES box: " + report.summary("rockies"));
        }
    }

    /**
     * 11-03 review renders: the ria site (user world) and the fjord candidate site (ROCKIES), each as a hillshade and a
     * mask image with the arm metrics, into build/rh10-arms as {prefix}-{site}.png, {prefix}-mask-{site}.png and
     * {prefix}.txt. The prefix is $FTF_ARMS_PREFIX (default noise, the production network; lowness is the temporary
     * variant). The noise run also writes targets.txt, the D-15 proposal from the measured drowned scope.
     */
    @Tag("probe")
    @Test void rendersArms() throws Exception {
        String prefix = System.getenv().getOrDefault("FTF_ARMS_PREFIX", "noise");
        var dir = Path.of("build/rh10-arms");
        Files.createDirectories(dir);
        var out = new StringBuilder();
        HighlandDrownedProbe.Report ria, fjord;
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        ria = renderSite(out, dir, prefix, "ria", user, RIA_SITE_X, RIA_SITE_Z);
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            fjord = renderSite(out, dir, prefix, "fjord", rockies, FJORD_SITE_X, FJORD_SITE_Z);
        }
        Files.writeString(dir.resolve(prefix + ".txt"), out.toString());
        if (prefix.equals("noise")) Files.writeString(dir.resolve("targets.txt"), targets(ria.drowned(), fjord.drowned()));
        assertTrue(ria.drowned().samples() > 0 && fjord.nearShore().samples() > 0, "no drowned sea or no coast at the sites:\n" + out);
    }

    /** Renders one site and appends its metrics: the probe summary, the arm geometry and the plan/render link check. */
    private static HighlandDrownedProbe.Report renderSite(StringBuilder out, Path dir, String prefix, String label,
                                                          GeneratorContext context, int x, int z) throws Exception {
        HighlandPlanformProbe.render(context, x, z, 2 * SITE_RADIUS, 8, dir.resolve(prefix + "-" + label + ".png"));
        HighlandDrownedProbe.maskImage(context, x, z, 2 * SITE_RADIUS, 8, dir.resolve(prefix + "-mask-" + label + ".png"));
        var report = HighlandDrownedProbe.measure(context, x, z, SITE_RADIUS);
        var arms = HighlandDrownedProbe.arms(context, x, z, SITE_RADIUS);
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var plan = continent.planAt(x, z).plan();
        var nodes = plan.nodes();
        // note: same link walk as planAndRenderAgree, which asserts; this one counts, because the conservative
        // narrow-arm plan mask is 11-04's and the tracer still lets rivers cross thin arms.
        int links = 0, crossing = 0;
        for (var node : nodes) {
            if (node.parent() < 0) continue;
            var parent = nodes.get(node.parent());
            if (coast.sample(node.x(), node.z()).edge() <= coastEdge || coast.sample(parent.x(), parent.z()).edge() <= coastEdge) continue;
            links++;
            double length = Math.hypot(parent.x() - node.x(), parent.z() - node.z());
            for (double d = 0; d <= length; d += 8) {
                double t = length > 0 ? d / length : 0;
                if (coast.sample(node.x() + (parent.x() - node.x()) * t, node.z() + (parent.z() - node.z()) * t).edge() <= coastEdge) { crossing++; break; }
            }
        }
        out.append(String.format(java.util.Locale.ROOT, "%s site (%d,%d) radius %d%n  %s%n  %s%n  plan links=%d crossingSea=%d%n",
            label, x, z, SITE_RADIUS, report.summary(label), arms.summary(), links, crossing));
        System.out.print(out);
        return report;
    }

    /**
     * D-15 proposal from the measured drowned scope: the research starting target where the tracer is below it, else
     * 5 points below the measured value (never below the starting target for the shares; harbors per 25 km2 are a count
     * per box, so the starting target stays).
     */
    private static String targets(HighlandDrownedProbe.Scope ria, HighlandDrownedProbe.Scope fjord) {
        double sheltered = pool(ria, fjord, HighlandDrownedProbe.Scope::shelteredShare);
        double navigable = pool(ria, fjord, HighlandDrownedProbe.Scope::navigableShare);
        double harbors = pool(ria, fjord, HighlandDrownedProbe.Scope::harborsPer25km2);
        return target("sheltered", ria.shelteredShare(), fjord.shelteredShare(), sheltered, .25, sheltered < .25 ? .25 : Math.max(.25, sheltered - .05))
            + target("harbors", ria.harborsPer25km2(), fjord.harborsPer25km2(), harbors, 1, 1)
            + target("navigable", ria.navigableShare(), fjord.navigableShare(), navigable, .95, navigable < .95 ? .95 : Math.max(.95, navigable - .05))
            + "note: shares are of drowned-scope sea (region weight >= .5, within 1500 blocks of land) in the two 8 km site boxes, pooled by sea area;\n"
            + "fjords (11-04), narrow bay mouths (11-05) and islands (11-06) will raise sheltered water; 11-07 enforces the agreed numbers.\n";
    }

    /** Sea-area-weighted mean of a scope metric over the two sites; a site without drowned sea (NaN) does not count. */
    private static double pool(HighlandDrownedProbe.Scope ria, HighlandDrownedProbe.Scope fjord, java.util.function.ToDoubleFunction<HighlandDrownedProbe.Scope> metric) {
        double sum = 0, sea = 0;
        for (var scope : List.of(ria, fjord)) {
            double value = metric.applyAsDouble(scope);
            if (scope.samples() == 0 || Double.isNaN(value)) continue;
            sum += value * scope.seaArea();
            sea += scope.seaArea();
        }
        return sea > 0 ? sum / sea : Double.NaN;
    }

    private static String target(String name, double ria, double fjord, double pooled, double start, double proposed) {
        return String.format(java.util.Locale.ROOT, "%-10s measured ria=%.3f fjord=%.3f pooled=%.3f | research start %.3f | proposed %.3f%n", name, ria, fjord, pooled, start, proposed);
    }

    /**
     * P0: ranked candidate boxes for later plans. Fjord candidates are ROCKIES coast boxes where PNW shore meets a
     * range core; ria candidates are user-world boxes with river mouths. Widens the ROCKIES scan once if no box qualifies.
     */
    @Tag("probe")
    @Test void scansSites() throws Exception {
        int box = 4096, radius = 16384;
        boolean widened = false;
        List<HighlandDrownedProbe.Site> fjords;
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            fjords = fjordCandidates(HighlandDrownedProbe.scanSites(rockies, SITE_X, SITE_Z, radius, box));
            if (fjords.isEmpty()) {
                widened = true;
                radius = 32768;
                fjords = fjordCandidates(HighlandDrownedProbe.scanSites(rockies, SITE_X, SITE_Z, radius, box));
            }
        }
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var rias = HighlandDrownedProbe.scanSites(context, SITE_X, SITE_Z, 16384, box).stream()
            .filter(site -> site.mouths() > 0)
            .sorted(Comparator.comparingInt(HighlandDrownedProbe.Site::mouths).thenComparingInt(HighlandDrownedProbe.Site::crossings).reversed())
            .limit(5).toList();
        var out = new StringBuilder("fjord candidates (ROCKIES, seed " + HighlandPlanformProbe.USER_SEED + ", centre " + SITE_X + "," + SITE_Z
            + ", radius " + radius + (widened ? " widened from 16384" : "") + ", box " + box + "): x z crossings pnwShare rangeCore mouths\n");
        fjords.forEach(site -> out.append(line(site)));
        out.append("ria candidates (user world, radius 16384, box " + box + "): x z crossings pnwShare rangeCore mouths\n");
        rias.forEach(site -> out.append(line(site)));
        System.out.print(out);
        Files.writeString(Path.of("build/rh10-sites.txt"), out.toString());
        assertTrue(!fjords.isEmpty() && !rias.isEmpty(), "no fjord or ria candidate boxes:\n" + out);
    }

    private static List<HighlandDrownedProbe.Site> fjordCandidates(List<HighlandDrownedProbe.Site> sites) {
        return sites.stream().filter(site -> site.pnwShare() >= .5 && site.rangeCore() > 0)
            .sorted(Comparator.comparingDouble(HighlandDrownedProbe.Site::rangeCore).thenComparingInt(HighlandDrownedProbe.Site::crossings).reversed())
            .limit(5).toList();
    }

    private static String line(HighlandDrownedProbe.Site site) {
        return String.format(java.util.Locale.ROOT, "  %d %d %d %.2f %.2f %d\n", site.x(), site.z(), site.crossings(), site.pnwShare(), site.rangeCore(), site.mouths());
    }
}
