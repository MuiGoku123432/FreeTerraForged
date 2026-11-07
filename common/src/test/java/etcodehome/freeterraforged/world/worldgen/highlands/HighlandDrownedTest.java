package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
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

    /** The plan's land mask reads the transformed edge: no channel link between two land nodes runs through arm water. */
    @Test void planAndRenderAgree() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var plan = continent.planAt(SITE_X, SITE_Z).plan();
        var nodes = plan.nodes();
        int links = 0;
        for (var node : nodes) {
            if (node.parent() < 0) continue;
            var parent = nodes.get(node.parent());
            if (coast.sample(node.x(), node.z()).edge() <= coastEdge || coast.sample(parent.x(), parent.z()).edge() <= coastEdge) continue;
            links++;
            double length = Math.hypot(parent.x() - node.x(), parent.z() - node.z());
            for (double d = 0; d <= length; d += 8) {
                double t = length > 0 ? d / length : 0, x = node.x() + (parent.x() - node.x()) * t, z = node.z() + (parent.z() - node.z()) * t;
                assertTrue(coast.sample(x, z).edge() > coastEdge, "link crosses sea water at " + x + "," + z);
            }
        }
        assertTrue(links > 0 && plan.landNodes() > 0, "no land links in the site plan");
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
