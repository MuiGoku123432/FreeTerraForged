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
     * coast edge; inside a region the edge is never raised, except under the island chains (11-06). Share half: regions cover 20-30% of the RH9 coastline
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
                if (bound.drowned().openIsland(x, z) > 0) continue; // an open-sea island footprint raises the edge (D-10)
                outside++;
                assertEquals(Double.doubleToLongBits(reference), Double.doubleToLongBits(sample.edge()), "edge differs outside drowned regions and island footprints at " + x + "," + z);
            } else {
                inside++;
                assertTrue(sample.edge() <= reference || bound.drowned().island(x, z) > 0 || bound.drowned().openIsland(x, z) > 0, "drowned edge above the RH9 edge outside the island footprints at " + x + "," + z);
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

    /** Second fixed seed of the D-02 share gate (also one of the five plan seeds of plansBuildOnFiveSeeds). */
    static final long OTHER_SEED = 1;
    /** D-02 bounds per seed and preset (inclusive), and for the mean of the four. */
    static final double SEED_MIN_SHARE = .15, SEED_MAX_SHARE = .35;
    /** Half side of the share window (a 64 km box around the calibration site): an 8 km box holds two or three regions, so its share is mostly luck on a second seed. */
    static final int SHARE_RADIUS = 32768;

    /**
     * D-02: the drowned share of the RH9 coastline stays in range on two seeds and two presets (user preset, ROCKIES; USER_SEED
     * and OTHER_SEED): each within 15-35%, the mean of the four within 20-30%.
     */
    @Test void drownedShareAcrossSeeds() throws Exception {
        double[] shares = new double[4];
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        shares[0] = HighlandDrownedProbe.drownedShare(user, SITE_X, SITE_Z, SHARE_RADIUS);
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            shares[1] = HighlandDrownedProbe.drownedShare(rockies, SITE_X, SITE_Z, SHARE_RADIUS);
        }
        try (var other = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), OTHER_SEED)) {
            shares[2] = HighlandDrownedProbe.drownedShare(other, SITE_X, SITE_Z, SHARE_RADIUS);
        }
        try (var other = WorldgenFixture.context(BiomeCensusProbe.rockies(), OTHER_SEED)) {
            shares[3] = HighlandDrownedProbe.drownedShare(other, SITE_X, SITE_Z, SHARE_RADIUS);
        }
        double mean = (shares[0] + shares[1] + shares[2] + shares[3]) / 4;
        String line = String.format(java.util.Locale.ROOT, "RH10 drowned share user/USER_SEED=%.1f%% rockies/USER_SEED=%.1f%% user/seed %d=%.1f%% rockies/seed %d=%.1f%% mean=%.1f%%",
            100 * shares[0], 100 * shares[1], OTHER_SEED, 100 * shares[2], OTHER_SEED, 100 * shares[3], 100 * mean);
        System.out.println(line);
        for (double share : shares) assertTrue(share >= SEED_MIN_SHARE && share <= SEED_MAX_SHARE, "drowned share outside 15-35%: " + line);
        assertTrue(mean >= MIN_SHARE && mean <= MAX_SHARE, "mean drowned share outside 20-30%: " + line);
    }

    /**
     * Lookup-path independence (T-11-11): at 2000 columns of the drowned regions near the coast (sea and land) the cell lookup
     * and the precise heightmap path agree on terrain, height and continent edge.
     */
    @Test void drownedIsPathIndependent() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double band = 1500 * HighlandCoast.EDGE_PER_BLOCK, coastEdge = context.preset.world().controlPoints.coast;
        var random = new Random(29);
        var a = new Cell();
        var b = new Cell();
        int checked = 0, sea = 0, land = 0, differ = 0;
        String first = null;
        for (int tries = 0; checked < 2000 && tries < 400_000; tries++) {
            int x = RIA_SITE_X + random.nextInt(2 * SITE_RADIUS + 1) - SITE_RADIUS, z = RIA_SITE_Z + random.nextInt(2 * SITE_RADIUS + 1) - SITE_RADIUS;
            var sample = coast.sample(x, z);
            if (sample.drowned() <= 0 || !(sample.edge() > 0) || Math.abs(sample.edge() - coastEdge) > band) continue;
            context.lookup.applyCell(a.reset(), x, z, false, true);
            context.heightmap.applyPrecise(b.reset(), x, z, true);
            checked++;
            if (sample.edge() > coastEdge) land++; else sea++;
            if (a.terrain != b.terrain || a.height != b.height || a.continentEdge != b.continentEdge) {
                differ++;
                if (first == null) first = x + "," + z + " terrain " + a.terrain + "/" + b.terrain + " height " + a.height + "/" + b.height + " edge " + a.continentEdge + "/" + b.continentEdge;
            }
        }
        System.out.println("RH10 drowned path independence checked=" + checked + " sea=" + sea + " land=" + land + " differ=" + differ);
        assertTrue(checked == 2000 && sea >= 300 && land >= 300, "too few drowned-region columns: " + checked + " (sea " + sea + ", land " + land + ")");
        assertEquals(0, differ, differ + " of " + checked + " drowned-region columns differ with the lookup path, first at " + first);
    }

    /**
     * Largest change of the transformed edge between neighbouring blocks anywhere on a scan, and on a region rim. The plan started at
     * 20 blocks of the standalone slope scale (EDGE_PER_BLOCK) everywhere; the measured steepest step inside a drowned region is 50.7
     * (7722,20283, ROCKIES, drowned weight 1, standalone step 0.7): the valley factor goes from 0 to 1 across a ridged-noise wall
     * about 70 blocks wide, so a valley wall is steep by design (a continuous 1-block step of the factor times the 0.4 to 0.7 of edge
     * between land and the arm target). The limit inside is therefore twice the measurement, which still fails a seam or hash
     * discontinuity (a full valley step is 0.05 and more, 170 blocks of slope and up). Region seams are what the gate is about: on the
     * rims (weight under RIM_WEIGHT on either side of the step) the measured steepest step is 3.4, and the limit there is 10.
     */
    static final double EDGE_JUMP = 100 * HighlandCoast.EDGE_PER_BLOCK, RIM_JUMP = 10 * HighlandCoast.EDGE_PER_BLOCK;
    private static final int SCAN_LINES = 50, SCAN_LENGTH = 8192;
    /** Drowned weight under which a scan step counts as lying on a region rim (seam). */
    private static final double RIM_WEIGHT = .05;

    /**
     * Continuity (T-11-11): on 50 dense line scans (1-block steps, 8 km) through the drowned regions of the ria site (user world),
     * the fjord site and the fjord arm site (ROCKIES), the transformed edge never changes by more than EDGE_JUMP between
     * neighbouring blocks, and by at most RIM_JUMP on a region rim, where region seams and fjord/ria boundaries lie. At least 30
     * lines cross a rim (the drowned weight leaves or reaches 0).
     */
    @Test void edgeIsContinuous() throws Exception {
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var random = new Random(31);
        var results = new double[4];
        var worst = new String[1];
        scanEdges(user, new HighlandCoast(user), RIA_SITE_X, RIA_SITE_Z, 20, random, results, worst);
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var standalone = new HighlandCoast(rockies);
            scanEdges(rockies, standalone, FJORD_SITE_X, FJORD_SITE_Z, 15, random, results, worst);
            scanEdges(rockies, standalone, FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z, 15, random, results, worst);
        }
        System.out.printf(java.util.Locale.ROOT, "RH10 edge continuity lines=%d crossRim=%d steps=%d maxStep=%.5f (limit %.5f, %.1f blocks of slope) maxRimStep=%.5f (%.1f blocks of slope)%n",
            SCAN_LINES, (int) results[0], (long) results[1], results[2], EDGE_JUMP, results[2] / HighlandCoast.EDGE_PER_BLOCK, results[3], results[3] / HighlandCoast.EDGE_PER_BLOCK);
        System.out.println("RH10 edge continuity worst step " + worst[0]);
        assertTrue(results[0] >= 30, "too few scan lines cross a drowned-region rim: " + (int) results[0]);
        assertTrue(results[2] <= EDGE_JUMP, "transformed edge jumps between neighbouring blocks: " + worst[0] + ", limit " + EDGE_JUMP);
        assertTrue(results[3] <= RIM_JUMP, "transformed edge jumps across a region rim: " + results[3] + ", limit " + RIM_JUMP);
    }

    /** One batch of scan lines around a site (alternating east and south), asserting the step bound; results: lines crossing a rim, steps, max step. */
    private static void scanEdges(GeneratorContext context, HighlandCoast standalone, int cx, int cz, int lines, Random random, double[] results, String[] worst) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        for (int line = 0; line < lines; line++) {
            boolean east = line % 2 == 0;
            int fixed = (east ? cz : cx) + random.nextInt(2 * SITE_RADIUS + 1) - SITE_RADIUS, start = (east ? cx : cz) - SCAN_LENGTH / 2;
            double previous = Double.NaN, previousStandalone = Double.NaN, previousWeight = 0;
            boolean rim = false;
            for (int step = 0; step <= SCAN_LENGTH; step++) {
                int x = east ? start + step : fixed, z = east ? fixed : start + step;
                var sample = coast.sample(x, z);
                double edge = sample.edge(), reference = standalone.sample(x, z).edge();
                if (!Double.isNaN(previous)) {
                    double jump = Math.abs(edge - previous), referenceJump = Math.abs(reference - previousStandalone);
                    results[1]++;
                    if (previousWeight < RIM_WEIGHT || sample.drowned() < RIM_WEIGHT) results[3] = Math.max(results[3], jump);
                    if (jump > results[2]) {
                        results[2] = jump;
                        worst[0] = String.format(java.util.Locale.ROOT, "%.5f at %d,%d (standalone step %.5f, drowned weight %.3f -> %.3f)", jump, x, z, referenceJump, previousWeight, sample.drowned());
                    }
                    if (previousWeight == 0 && sample.drowned() > 0 || previousWeight > 0 && sample.drowned() == 0) rim = true;
                }
                previous = edge; previousStandalone = reference; previousWeight = sample.drowned();
            }
            if (rim) results[0]++;
        }
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

    /** Server world (HighlandPlanformProbe.serverPreset): the stranded river mouth the owner plan of the G-11-1 gates is read at, and the X crossing of two noise networks. */
    static final int MOUTH_X = 7453, MOUTH_Z = 3341, CROSSING_X = 9373, CROSSING_Z = 2111;

    /**
     * D-GC1: the plan behind the arm data is the RH9 planner's plan. On the server preset, the owner of 7453,3341 built by
     * the base planner (a separate, never-bound coast) has the node count, node positions and valley-axis positions of the
     * plan built on a fresh context whose drowned layer is unbound.
     */
    @Test void plan0IsUndrowned() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.serverPreset(), HighlandPlanformProbe.SERVER_SEED);
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var owner = continent.coast().undrowned(MOUTH_X, MOUTH_Z);
        var plan0 = continent.basePlan(owner.ownerX(), owner.ownerZ());
        try (var inert = WorldgenFixture.context(HighlandPlanformProbe.serverPreset(), HighlandPlanformProbe.SERVER_SEED)) {
            var reference = (RollingHighlandsContinent) inert.heightmap.continent();
            reference.coast().bindDrowned(null, null);
            var expected = reference.drainage().build(owner.ownerX(), owner.ownerZ());
            assertEquals(expected.nodes().size(), plan0.nodes().size(), "plan0 node count differs from the RH9 plan");
            assertEquals(expected.axis().size(), plan0.axis().size(), "plan0 axis count differs from the RH9 plan");
            assertTrue(plan0.nodes().size() > 1000, "no river plan at the owner: " + plan0.nodes().size());
            for (int i = 0; i < expected.nodes().size(); i++) {
                assertEquals(expected.nodes().get(i).x(), plan0.nodes().get(i).x(), "node x at " + i);
                assertEquals(expected.nodes().get(i).z(), plan0.nodes().get(i).z(), "node z at " + i);
            }
            for (int i = 0; i < expected.axis().size(); i++) {
                assertEquals(expected.axis().get(i).x(), plan0.axis().get(i).x(), "axis x at " + i);
                assertEquals(expected.axis().get(i).z(), plan0.axis().get(i).z(), "axis z at " + i);
            }
        }
    }

    /**
     * D-GC1: arms lie along the valleys of plan0. The arm data of the owner of 7453,3341 holds at least 20 coastal axis
     * segments, all inside the owner's coast bounds. Over a 16-block grid of the 8 km box around the X crossing 9373,2111, at
     * least 500 columns are land on the unbound coast and sea on the bound one, and each lies within MAX_HALF_WIDTH of an
     * axis segment of its own owner's arm data.
     */
    @Test void armsFollowPlan0() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.serverPreset(), HighlandPlanformProbe.SERVER_SEED);
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        var owner = coast.undrowned(MOUTH_X, MOUTH_Z);
        var bounds = coast.bounds(owner.ownerX(), owner.ownerZ());
        var ownerArms = coast.drowned().arms(owner.ownerX(), owner.ownerZ());
        System.out.println("RH10 arm segments owner " + owner.ownerX() + "," + owner.ownerZ() + ": " + ownerArms.segments() + " (" + ownerArms.estimatedBytes() + " bytes)");
        assertTrue(ownerArms.segments() >= 20, "too few coastal axis segments: " + ownerArms.segments());
        for (int i = 0; i < ownerArms.segments(); i++) {
            assertTrue(ownerArms.ax[i] >= bounds.minX() && ownerArms.ax[i] <= bounds.maxX() && ownerArms.az[i] >= bounds.minZ() && ownerArms.az[i] <= bounds.maxZ()
                && ownerArms.bx[i] >= bounds.minX() && ownerArms.bx[i] <= bounds.maxX() && ownerArms.bz[i] >= bounds.minZ() && ownerArms.bz[i] <= bounds.maxZ(),
                "arm segment " + i + " outside the owner bounds");
        }
        int flooded = 0, far = 0;
        String first = null;
        for (int z = CROSSING_Z - SITE_RADIUS; z <= CROSSING_Z + SITE_RADIUS; z += 16) for (int x = CROSSING_X - SITE_RADIUS; x <= CROSSING_X + SITE_RADIUS; x += 16) {
            var reference = standalone.sample(x, z);
            if (!(reference.edge() > coastEdge) || coast.sample(x, z).edge() > coastEdge) continue;
            flooded++;
            var hits = coast.drowned().arms(reference.ownerX(), reference.ownerZ()).query(x, z);
            double nearest = Double.POSITIVE_INFINITY;
            for (int h = 0; h < hits.count; h++) nearest = Math.min(nearest, hits.distance[h]);
            if (!(nearest <= HighlandDrowned.MAX_HALF_WIDTH)) { far++; if (first == null) first = x + "," + z; }
        }
        System.out.println("RH10 arms follow plan0: " + flooded + " flooded columns, " + far + " farther than " + HighlandDrowned.MAX_HALF_WIDTH + " from an arm segment");
        assertTrue(flooded >= 500, "too few flooded columns around the crossing: " + flooded);
        assertEquals(0, far, far + " flooded columns lie farther than MAX_HALF_WIDTH from an arm segment of their owner, first at " + first);
    }

    /**
     * Pitfall 4, T-11-09: plans build without exceptions where regions meet the coast and under open-sea islands, on the user seed
     * and four other seeds, on the user preset and on ROCKIES. Per seed and preset, the owner of the first drowned coast point and
     * the owner of the first open-sea island found by a ring search around the origin build their plans (no land bridge, box edge
     * or outlet exception) with land nodes; the user preset must have a drowned coast point for every seed, and each preset must
     * show both kinds of owner somewhere over the five seeds.
     */
    @Test void plansBuildOnFiveSeeds() throws Exception {
        long[] seeds = {HighlandPlanformProbe.USER_SEED, 1L, 42L, -314159L, 20261107L};
        int[] drownedOwners = new int[2], openOwners = new int[2];
        for (long seed : seeds) {
            for (int preset = 0; preset < 2; preset++) {
                var settings = preset == 0 ? HighlandPlanformProbe.userPreset() : BiomeCensusProbe.rockies();
                try (var context = WorldgenFixture.context(settings, seed)) {
                    var continent = (RollingHighlandsContinent) context.heightmap.continent();
                    var coast = continent.coast();
                    double coastEdge = context.preset.world().controlPoints.coast;
                    HighlandCoast.Sample drowned = null, open = null;
                    for (int ring = 4096; ring <= 65536 && (drowned == null || open == null); ring += 4096) {
                        for (int a = 0; a < 128 && (drowned == null || open == null); a++) {
                            double angle = 2 * Math.PI * a / 128;
                            for (int d = -2048; d <= 2048 && (drowned == null || open == null); d += 64) {
                                double x = Math.cos(angle) * (ring + d), z = Math.sin(angle) * (ring + d);
                                var s = coast.sample(x, z);
                                if (drowned == null && s.drowned() >= HighlandDrownedProbe.DROWNED_WEIGHT && s.edge() > 0 && s.edge() < coastEdge + .1) drowned = s;
                                if (open == null && s.drowned() == 0 && s.edge() > coastEdge && coast.drowned().openIsland(x, z) > 0) open = s;
                            }
                        }
                    }
                    String where = "seed " + seed + (preset == 0 ? " user" : " ROCKIES");
                    if (preset == 0) assertTrue(drowned != null, "no drowned coast point near the origin for " + where);
                    if (drowned != null) {
                        drownedOwners[preset]++;
                        assertTrue(continent.planOf(drowned.ownerX(), drowned.ownerZ()).plan().landNodes() > 0, where + " drowned owner " + drowned.ownerX() + "," + drowned.ownerZ() + " has no land nodes");
                    }
                    if (open != null) {
                        openOwners[preset]++;
                        assertTrue(continent.planOf(open.ownerX(), open.ownerZ()).plan().landNodes() > 0, where + " island owner " + open.ownerX() + "," + open.ownerZ() + " has no land nodes");
                    }
                }
            }
        }
        System.out.println("RH10 plans built: drowned owners user/ROCKIES " + drownedOwners[0] + "/" + drownedOwners[1] + ", island owners " + openOwners[0] + "/" + openOwners[1]);
        assertTrue(drownedOwners[1] > 0 && openOwners[0] > 0 && openOwners[1] > 0, "a preset without drowned or island owners over five seeds: drowned " + java.util.Arrays.toString(drownedOwners) + " islands " + java.util.Arrays.toString(openOwners));
    }

    /** Open-sea island scan (D-10): radius and lattice step (blocks), and the sea cells an owner needs in the scan to count as covered (about 70 km2 of the 60-100 km2 of sea of an owner). */
    static final int OPEN_SCAN = 32768, OPEN_STEP = 32, OPEN_FULL_SEA = 70_000;
    /** Open-sea island extent, blocks (D-10: 0.3-3 km across). */
    static final double OPEN_ACROSS_MIN = 300, OPEN_ACROSS_MAX = 3000;

    /**
     * D-10 (user world and ROCKIES, 32 km scan around the site): sparse open-sea islands, 1-4 per owner with ocean on average,
     * at least 90% of them 0.3-3 km across (a fragment of an island cut by the owner-border guard can fall below, none is over
     * 3.6 km), none with land where the standalone edge is below the guard edge, each inside its owner's plan
     * bounds, and (for a sample of islands) in a plan with land nodes and shored by the RH9 profile (beach or scarp tags on the
     * columns at their coast).
     */
    @Test void islandsOpenSea() throws Exception {
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        assertOpenSea("user", user);
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            assertOpenSea("rockies", rockies);
        }
    }

    private static void assertOpenSea(String label, GeneratorContext context) {
        var census = HighlandDrownedProbe.openIslands(context, SITE_X, SITE_Z, OPEN_SCAN, OPEN_STEP, OPEN_FULL_SEA);
        System.out.println("RH10 open-sea islands " + label + " " + census.summary());
        assertTrue(census.islands().size() >= 5, label + ": too few open-sea islands: " + census.summary());
        assertTrue(census.perOwner() >= 1 && census.perOwner() <= 4, label + ": islands per owner with ocean outside 1-4: " + census.summary());
        assertEquals(0, census.belowGuard(), label + ": land below the guard edge: " + census.summary());
        assertEquals(0, census.outOfBounds(), label + ": island land outside its owner's plan bounds: " + census.summary());
        long inRange = census.islands().stream().filter(i -> i.across() >= OPEN_ACROSS_MIN && i.across() <= OPEN_ACROSS_MAX).count();
        assertTrue(inRange >= .9 * census.islands().size(), label + ": islands " + OPEN_ACROSS_MIN + "-" + OPEN_ACROSS_MAX + " blocks across: " + inRange + " of " + census.islands().size() + ": " + census.summary());
        assertTrue(census.islands().stream().noneMatch(i -> i.across() > 1.2 * OPEN_ACROSS_MAX), label + ": an island far over " + OPEN_ACROSS_MAX + " blocks across: " + census.summary());
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var cell = new Cell();
        int stride = Math.max(1, census.islands().size() / 6);
        for (int k = 0; k < census.islands().size(); k += stride) {
            var island = census.islands().get(k);
            String where = label + " island " + island.ownerX() + "," + island.ownerZ() + " at " + island.shore()[0] + "," + island.shore()[1];
            assertTrue(continent.planOf(island.ownerX(), island.ownerZ()).plan().landNodes() > 0, where + ": the owner plan has no land nodes");
            boolean shored = false;
            for (int dz = -OPEN_STEP; dz <= OPEN_STEP; dz += 8) for (int dx = -OPEN_STEP; dx <= OPEN_STEP; dx += 8) {
                context.lookup.applyCell(cell.reset(), island.shore()[0] + dx, island.shore()[1] + dz, false, true);
                if (cell.terrain == TerrainType.BEACH || cell.highlandCoast > 0) shored = true;
            }
            assertTrue(shored, where + ": no beach or scarp tag at the coast, the RH9 shore profile did not act");
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
            model8.coast().bindDrowned(null, null);
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
            ((RollingHighlandsContinent) inert.heightmap.continent()).coast().bindDrowned(null, null);
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
        HighlandDrownedProbe.Bays inland;
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            inland = HighlandDrownedProbe.bays(rockies, FJORD_SITE_X, FJORD_SITE_Z, SITE_RADIUS);
        }
        System.out.println("RH10 bays rockies " + inland.summary());
        assertTrue(bays.count() >= 5, "too few bays: " + bays.summary());
        double widest = bays.inners()[bays.inners().length - 1];
        assertTrue(widest >= 1000 && widest <= 5000, "widest bay " + widest + " outside 1-5 km: " + bays.summary());
        assertTrue(java.util.Arrays.stream(bays.inners()).filter(w -> w >= 1000).count() >= 3, "fewer than 3 bays 1 km across: " + bays.summary());
        assertTrue(bays.narrowMouth() >= .7 * bays.count(), "narrow mouths: " + bays.narrowMouth() + " of " + bays.count() + ": " + bays.summary());
        assertTrue(bays.branching() >= .5 * bays.count(), "branching bays: " + bays.branching() + " of " + bays.count() + ": " + bays.summary());
        assertEquals(bays.count(), bays.singleOwner(), "bays across owners: " + bays.summary());
        assertEquals(bays.count(), bays.connected(), "bays closed to the sea: " + bays.summary());
        assertTrue(bays.closedShare() <= MAX_CLOSED, "flooded water closed off from the sea (lagoons behind a bar): " + bays.summary());
        assertTrue(inland.count() >= 1 && inland.connected() == inland.count() && inland.closedShare() <= MAX_CLOSED, "ROCKIES ria box has closed or unreachable bays: " + inland.summary());
    }

    /** Boxes (leading RIA_BOXES) whose bays are probed through full column lookups (marsh, path independence): each lookup builds the owner's plan, the cost of those gates. */
    static final int MARSH_BOXES = 4;

    /** Most of the flooded area (share) that may lie in water bodies with no column touching the standalone sea (the RH9-era tracer already has small ponds, 2.7% in the ria boxes). */
    static final double MAX_CLOSED = .05;

    /**
     * D-16 (ria boxes pooled): bay shores are calmer than the open RH9 coast. The beach-dominant share of bay shore columns is at
     * least 10 points above the share on the coast outside every drowned region, the median dry beach width of bay beaches is
     * at most 0.7 times the open one, and at least half of the bays have marsh (WETLAND) columns on the low land inside 24 blocks
     * of their shore (at least 3 each).
     */
    @Test void innerShores() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var shores = HighlandDrownedProbe.bayShores(context, RIA_BOXES, MARSH_BOXES, SITE_RADIUS);
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
        for (int[] box : java.util.Arrays.copyOf(RIA_BOXES, MARSH_BOXES)) {
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

    /**
     * D-08, D-09 (ria boxes on the user world and fjord boxes on ROCKIES, islands pooled): at least one island of 1-3 km across per
     * 25 km2 of drowned region, at least 60% of the islands under 1 km, at least half of the islands within CHAIN_GAP of another
     * (chains), and islands in both scopes.
     */
    @Test void islandsMixAndChains() throws Exception {
        var census = islandCensus();
        var all = HighlandDrownedProbe.poolIslands(List.of(census.ria, census.fjord));
        System.out.println("RH10 islands ria " + census.ria.summary() + "\nRH10 islands fjord " + census.fjord.summary() + "\nRH10 islands pooled " + all.summary());
        assertTrue(census.ria.count() > 0 && census.fjord.count() > 0, "no islands in a scope: ria " + census.ria.count() + " fjord " + census.fjord.count());
        assertTrue(all.per25km2(1000, 3000) >= 1, "islands of 1-3 km per 25 km2 of drowned region: " + all.per25km2(1000, 3000) + ": " + all.summary());
        assertTrue(all.under(0, 1000) >= .6 * all.count(), "islands under 1 km: " + all.under(0, 1000) + " of " + all.count());
        assertTrue(all.chainedCount() >= .5 * all.count(), "islands within " + HighlandDrownedProbe.CHAIN_GAP + " blocks of another: " + all.chainedCount() + " of " + all.count());
    }

    /** D-11: island relief follows the drowned land: the median island top in fjord country is at least twice that in bay country, and its median steepest slope is higher. */
    @Test void islandsReliefFollowsDrownedLand() throws Exception {
        var census = islandCensus();
        System.out.printf(java.util.Locale.ROOT, "RH10 island relief fjord top=%.1f steepest=%.3f ria top=%.1f steepest=%.3f%n",
            census.fjord.medianTop(), census.fjord.medianSteepest(), census.ria.medianTop(), census.ria.medianSteepest());
        assertTrue(census.fjord.medianTop() >= 2 * census.ria.medianTop(), "island tops: fjord " + census.fjord.medianTop() + " vs ria " + census.ria.medianTop());
        assertTrue(census.fjord.medianSteepest() > census.ria.medianSteepest(), "island slopes: fjord " + census.fjord.medianSteepest() + " vs ria " + census.ria.medianSteepest());
    }

    /**
     * D-12: small islands are dry. No island under 1 km has a channel plan node with at least the creek area or any river or lake
     * water on it; the share of islands of 1 km or more that do is printed (recorded, not gated).
     */
    @Test void islandsSmallAreDry() throws Exception {
        var census = islandCensus();
        var all = HighlandDrownedProbe.poolIslands(List.of(census.ria, census.fjord));
        System.out.printf(java.util.Locale.ROOT, "RH10 island water: under 1 km %d of %d wet; 1 km and more %d of %d wet%n",
            all.creekCount(0, 1000), all.under(0, 1000), all.creekCount(1000, Double.MAX_VALUE), all.under(1000, Double.MAX_VALUE));
        assertEquals(0, all.creekCount(0, 1000), "islands under 1 km with a creek, river or lake: " + all.creekCount(0, 1000) + " of " + all.under(0, 1000));
    }

    /**
     * D-15 targets. 11-03 ("targets: accept", see 11-03-SUMMARY.md, Decision) agreed at least 25% sheltered water; 11-07
     * revises that to 15% per site with the user's explicit approval ("Lower to 15% per site"), because the finished coast
     * measures 17.7% (ria) and 21.3% (fjord) and one tuning pass that reached 23.9% / 29.2% broke the closed-water (5.5% over 5%)
     * and big-islands (0.95 under 1) gates. The sheltered share is the drowned-scope sea deep enough for boats with a fetch of at
     * most 400 blocks, asserted per site (ria user world, fjord ROCKIES). Still agreed: at least 1 harbor per 25 km2 of
     * drowned sea, and at least 95% of the drowned-scope sea in water that reaches the open sea through channels of 6 blocks
     * or more. Nothing places harbors; they come from bays, narrow mouths, spits and islands.
     */
    static final double SHELTERED_TARGET = .15, HARBOR_TARGET = 1, NAVIGABLE_TARGET = .95;
    /** Model-8 references from 11-01-SUMMARY.md, user world and ROCKIES (printed with the measurement, not asserted). */
    static final double MODEL8_SHELTERED_USER = .002, MODEL8_SHELTERED_ROCKIES = .002, MODEL8_HARBORS_USER = 1.56, MODEL8_HARBORS_ROCKIES = 1.65;

    /**
     * D-15: sheltered share, harbors per 25 km2 and navigability of the drowned-scope sea meet the agreed targets at the ria
     * site (user world) and the fjord site (ROCKIES). The finished coast (bays, narrow mouths, islands) has to produce them.
     */
    @Test void shelteredWaterIsMeasured() throws Exception {
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var ria = HighlandDrownedProbe.measure(user, RIA_SITE_X, RIA_SITE_Z, SITE_RADIUS).drowned();
        HighlandDrownedProbe.Scope fjord;
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            fjord = HighlandDrownedProbe.measure(rockies, FJORD_SITE_X, FJORD_SITE_Z, SITE_RADIUS).drowned();
        }
        System.out.printf(java.util.Locale.ROOT,
            "RH10 sheltered ria=%.1f%% (model 8 %.1f%%) rockies=%.1f%% (model 8 %.1f%%) | harbors/25km2 ria=%.2f (model 8 %.2f) rockies=%.2f (model 8 %.2f) | navigable ria=%.1f%% rockies=%.1f%%%n",
            100 * ria.shelteredShare(), 100 * MODEL8_SHELTERED_USER, 100 * fjord.shelteredShare(), 100 * MODEL8_SHELTERED_ROCKIES,
            ria.harborsPer25km2(), MODEL8_HARBORS_USER, fjord.harborsPer25km2(), MODEL8_HARBORS_ROCKIES,
            100 * ria.navigableShare(), 100 * fjord.navigableShare());
        assertTrue(ria.shelteredShare() >= SHELTERED_TARGET && fjord.shelteredShare() >= SHELTERED_TARGET,
            "sheltered share under " + SHELTERED_TARGET + ": ria " + ria.shelteredShare() + " rockies " + fjord.shelteredShare());
        assertTrue(ria.harborsPer25km2() >= HARBOR_TARGET && fjord.harborsPer25km2() >= HARBOR_TARGET,
            "harbors per 25 km2 under " + HARBOR_TARGET + ": ria " + ria.harborsPer25km2() + " rockies " + fjord.harborsPer25km2());
        assertTrue(ria.navigableShare() >= NAVIGABLE_TARGET && fjord.navigableShare() >= NAVIGABLE_TARGET,
            "navigable share under " + NAVIGABLE_TARGET + ": ria " + ria.navigableShare() + " rockies " + fjord.navigableShare());
    }

    /** RH10-08: channels between islands stay boatable: the drowned-scope navigable share holds the agreed target at the ria site (user world) and the fjord site (ROCKIES). */
    @Test void navigableChannels() throws Exception {
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var ria = HighlandDrownedProbe.measure(user, RIA_SITE_X, RIA_SITE_Z, SITE_RADIUS).drowned();
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var fjord = HighlandDrownedProbe.measure(rockies, FJORD_SITE_X, FJORD_SITE_Z, SITE_RADIUS).drowned();
            var arm = HighlandDrownedProbe.measure(rockies, FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z, SITE_RADIUS).drowned();
            System.out.printf(java.util.Locale.ROOT, "RH10 navigable ria=%.1f%% fjord=%.1f%% fjord arm site=%.1f%%%n", 100 * ria.navigableShare(), 100 * fjord.navigableShare(), 100 * arm.navigableShare());
            assertTrue(ria.navigableShare() >= NAVIGABLE_TARGET && fjord.navigableShare() >= NAVIGABLE_TARGET && arm.navigableShare() >= NAVIGABLE_TARGET,
                "navigable share under " + NAVIGABLE_TARGET + ": ria " + ria.navigableShare() + " fjord " + fjord.navigableShare() + " arm site " + arm.navigableShare());
        }
    }

    /** The island censuses of the two scopes, taken once (every gate that needs them reads the same one). */
    private record IslandCensus(HighlandDrownedProbe.Islands ria, HighlandDrownedProbe.Islands fjord) {}
    private static IslandCensus islandCensus;

    private static synchronized IslandCensus islandCensus() throws Exception {
        if (islandCensus == null) {
            var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
            try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
                islandCensus = new IslandCensus(riaIslands(user), fjordIslands(rockies));
            }
        }
        return islandCensus;
    }

    /** Island census over the ria boxes (user world, ria scope) and over the fjord boxes (ROCKIES, fjord scope). */
    private static HighlandDrownedProbe.Islands riaIslands(GeneratorContext context) {
        var parts = new ArrayList<HighlandDrownedProbe.Islands>();
        for (int[] box : RIA_BOXES) parts.add(HighlandDrownedProbe.islands(context, box[0], box[1], SITE_RADIUS, false));
        return HighlandDrownedProbe.poolIslands(parts);
    }

    private static HighlandDrownedProbe.Islands fjordIslands(GeneratorContext rockies) {
        var parts = new ArrayList<HighlandDrownedProbe.Islands>();
        for (int[] box : FJORD_BOXES) parts.add(HighlandDrownedProbe.islands(rockies, box[0], box[1], SITE_RADIUS, true));
        return HighlandDrownedProbe.poolIslands(parts);
    }

    /** 11-06: island census per scope and the drowned-scope navigable share at the two sites (probe tag): build/rh10-islands.txt. */
    @Tag("probe")
    @Test void reportsIslands() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var out = new StringBuilder();
        for (int[] box : RIA_BOXES) out.append("ria box " + box[0] + "," + box[1] + " " + HighlandDrownedProbe.islands(context, box[0], box[1], SITE_RADIUS, false).summary() + "\n");
        out.append("ria pooled " + riaIslands(context).summary() + "\n");
        out.append("ria navigable " + HighlandDrownedProbe.measure(context, RIA_SITE_X, RIA_SITE_Z, SITE_RADIUS).drowned().summary() + "\n");
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            for (int[] box : FJORD_BOXES) out.append("fjord box " + box[0] + "," + box[1] + " " + HighlandDrownedProbe.islands(rockies, box[0], box[1], SITE_RADIUS, true).summary() + "\n");
            out.append("fjord pooled " + fjordIslands(rockies).summary() + "\n");
            out.append("fjord navigable " + HighlandDrownedProbe.measure(rockies, FJORD_SITE_X, FJORD_SITE_Z, SITE_RADIUS).drowned().summary() + "\n");
            out.append("fjord arm site navigable " + HighlandDrownedProbe.measure(rockies, FJORD_ARM_SITE_X, FJORD_ARM_SITE_Z, SITE_RADIUS).drowned().summary() + "\n");
        }
        System.out.print(out);
        Files.writeString(Path.of("build/rh10-islands" + System.getenv().getOrDefault("FTF_ISLANDS_TAG", "") + ".txt"), out.toString());
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

    /**
     * Drowned-water tags (locate "fjord" / "bay"): fjord water in the ROCKIES fjord boxes and bay water in the user-world ria
     * boxes; every tagged cell is sea that the RH9 coast has as land, and a FJORD tag only where a fjord region outweighs
     * any ria region. Untagged sea stays the legacy ocean terrain, so the tag never changes terrain identity.
     */
    @Test void drownedWaterTags() throws Exception {
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            int[] fjord = tagCensus(rockies, FJORD_BOXES);
            System.out.println("RH10 water tags ROCKIES fjord boxes: fjord " + fjord[1] + " bay " + fjord[2] + " of " + fjord[0]);
            assertTrue(fjord[1] >= 100, "too few fjord-tagged cells in the fjord boxes: " + fjord[1]);
        }
        var user = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        int[] bay = tagCensus(user, new int[][] {RIA_BOXES[0], RIA_BOXES[1]});
        System.out.println("RH10 water tags user ria boxes: fjord " + bay[1] + " bay " + bay[2] + " of " + bay[0]);
        assertTrue(bay[2] >= 100, "too few bay-tagged cells in the ria boxes: " + bay[2]);
    }

    /** Samples the boxes on a 32-block grid and checks each tag's contract; returns {cells, fjord, bay}. */
    private static int[] tagCensus(GeneratorContext context, int[][] boxes) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var cell = new Cell();
        int[] counts = new int[3];
        for (int[] box : boxes) {
            for (int z = box[1] - SITE_RADIUS; z <= box[1] + SITE_RADIUS; z += 32) {
                for (int x = box[0] - SITE_RADIUS; x <= box[0] + SITE_RADIUS; x += 32) {
                    context.heightmap.apply(cell.reset(), x, z, false);
                    counts[0]++;
                    if (cell.highlandWater == HighlandWater.NONE) continue;
                    counts[cell.highlandWater]++;
                    var at = coast.sample(x, z);
                    assertTrue(at.edge() <= coastEdge, "tagged cell is not sea at " + x + "," + z);
                    assertTrue(coast.undrowned(x, z).edge() > coastEdge, "tagged cell is sea on the RH9 coast at " + x + "," + z);
                    assertTrue(cell.terrain.isSubmerged() || cell.terrain.isCoast(), "tagged cell has non-water terrain " + cell.terrain.getName() + " at " + x + "," + z);
                    if (cell.highlandWater == HighlandWater.FJORD) {
                        assertTrue(coast.drowned().fjord(x, z) >= coast.drowned().ria(x, z), "fjord tag where a ria region dominates at " + x + "," + z);
                    }
                }
            }
        }
        return counts;
    }
}
