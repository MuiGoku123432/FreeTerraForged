package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * RH10 river mouths (G-11-6 part A, D-GC4, 11-17): every wide river runs to block-resolution sea. The touchesSea mask makes coastal
 * land nodes into plan roots up to half a plan step from real sea, and the renderer's sea branch never draws the missing stretch;
 * the planner extends such a root to verified sea. Promoted from the untracked RiverMouthProbe (outlets, seaDistance, ray walk).
 */
class HighlandMouthTest {
    /** Rivers at least this wide (blocks) must reach the sea. */
    static final double MIN_WIDTH = 4;
    /** The river's last wet cell lies within half its width plus this many blocks of sea. */
    static final double SEA_SLACK = 4;
    /** Dry cells a ray from the last river cell to the sea may cross. */
    static final int MAX_DRY = 2;
    /** Land cells without river water the rendered channel is followed over, upstream from its plan root, to find its first wet cell. */
    static final int REACH = 160;
    static final int USER_OWNER_SITE_X = 14215, USER_OWNER_SITE_Z = 14731;

    /** One preset and seed with the owners to measure: explicit owners and owners of block positions. */
    private record Fixture(String label, Callable<Preset> preset, long seed, int[][] owners, int[][] blocks) {}

    private static Preset defaultPreset() { return HighlandQualityProbe.preset(); }

    private static List<Fixture> fixtures() {
        return List.of(
            new Fixture("server", HighlandPlanformProbe::serverPreset, HighlandPlanformProbe.SERVER_SEED, new int[][]{{0, 0}}, new int[0][]),
            new Fixture("default 42", HighlandMouthTest::defaultPreset, 42, new int[][]{{0, 0}}, new int[0][]),
            new Fixture("default -314159", HighlandMouthTest::defaultPreset, -314159, new int[][]{{0, 0}}, new int[0][]),
            new Fixture("default 8675309", HighlandMouthTest::defaultPreset, 8675309, new int[][]{{0, 0}}, new int[0][]),
            new Fixture("Test-America-v3", HighlandPlanformProbe::userPreset, HighlandPlanformProbe.USER_SEED, new int[0][],
                new int[][]{{HighlandDrownedTest.RIA_SITE_X, HighlandDrownedTest.RIA_SITE_Z}, {USER_OWNER_SITE_X, USER_OWNER_SITE_Z}}));
    }

    /** Distance (blocks, 2-block rings up to max) from (x, z) to the nearest block-resolution sea sample, and the direction to it; 0 inside the sea, infinite beyond max. */
    private static double[] seaDistance(HighlandCoast coast, double coastEdge, double x, double z, double max) {
        if (coast.sample(x, z).edge() <= coastEdge) return new double[]{0, 0, 0};
        for (double r = 2; r <= max; r += 2) {
            int m = (int) Math.max(16, Math.ceil(Math.PI * r));
            for (int k = 0; k < m; k++) {
                double a = 2 * Math.PI * k / m, px = x + r * Math.cos(a), pz = z + r * Math.sin(a);
                if (coast.sample(px, pz).edge() <= coastEdge) return new double[]{r, Math.cos(a), Math.sin(a)};
            }
        }
        return new double[]{Double.POSITIVE_INFINITY, 0, 0};
    }

    /** Land cells without river water (1-block steps) from (x, z) along (dx, dz) up to the first sea cell or {@code length} blocks. */
    private static int dryCells(GeneratorContext context, double coastEdge, double x, double z, double dx, double dz, double length) {
        var cell = new Cell();
        int dry = 0;
        for (double d = 0; d <= length; d++) {
            context.heightmap.apply(cell.reset(), (int) Math.round(x + dx * d), (int) Math.round(z + dz * d), false);
            if (cell.continentEdge <= coastEdge) break;
            if (!cell.hasRiverWater) dry++;
        }
        return dry;
    }

    /**
     * The first rendered river cell upstream of plan node {@code root}: the main channel (largest drainage area child) is followed at
     * 1-block steps; sea cells are skipped (an estuary channel may run in the sea) and {@link #REACH} land cells without river water end the walk.
     * Null when no river water is drawn on it.
     */
    private static double[] lastRiverCell(GeneratorContext context, double coastEdge, List<HighlandDrainage.Node> nodes, int root) {
        var cell = new Cell();
        int land = 0, current = root;
        while (true) {
            int next = -1;
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).parent() == current && (next < 0 || nodes.get(i).area() > nodes.get(next).area())) next = i;
            var a = nodes.get(current);
            double length = next < 0 ? 0 : Math.hypot(nodes.get(next).x() - a.x(), nodes.get(next).z() - a.z());
            for (double d = 0; d == 0 || d < length; d++) {
                double t = length == 0 ? 0 : d / length;
                double x = next < 0 ? a.x() : a.x() + (nodes.get(next).x() - a.x()) * t, z = next < 0 ? a.z() : a.z() + (nodes.get(next).z() - a.z()) * t;
                context.heightmap.apply(cell.reset(), (int) Math.round(x), (int) Math.round(z), false);
                if (cell.continentEdge <= coastEdge) continue;
                if (cell.hasRiverWater) return new double[]{x, z};
                if (++land > REACH) return null;
            }
            if (next < 0) return null;
            current = next;
        }
    }

    /**
     * Every plan root of width at least {@link #MIN_WIDTH} (a refined node with no parent and a child): its rendered channel ends within
     * halfWidth + {@link #SEA_SLACK} blocks of sea, and the ray from the last river cell to that sea crosses at most {@link #MAX_DRY}
     * cells without river water. Returns the failures; one line per root goes to {@code out}.
     */
    private static List<String> measure(String label, GeneratorContext context, int ownerX, int ownerZ, StringBuilder out) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var nodes = continent.planOf(ownerX, ownerZ).plan().nodes();
        boolean[] hasChild = new boolean[nodes.size()];
        for (var node : nodes) if (node.parent() >= 0) hasChild[node.parent()] = true;
        var failures = new ArrayList<String>();
        int roots = 0, wide = 0, landRoots = 0;
        for (int i = 0; i < nodes.size(); i++) {
            var root = nodes.get(i);
            if (root.parent() >= 0 || !hasChild[i]) continue;
            roots++;
            boolean land = coast.sample(root.x(), root.z()).edge() > coastEdge;
            if (land) landRoots++;
            double halfWidth = continent.drainage().halfWidth(root.area(), 1);
            if (2 * halfWidth < MIN_WIDTH) continue;
            wide++;
            // The root itself, as the old gate read it: the ray from a land root to the nearest sea.
            double[] rootSea = seaDistance(coast, coastEdge, root.x(), root.z(), 400);
            int rootDry = Double.isFinite(rootSea[0]) ? dryCells(context, coastEdge, root.x(), root.z(), rootSea[1], rootSea[2], rootSea[0]) : -1;
            var last = lastRiverCell(context, coastEdge, nodes, i);
            String row;
            if (last == null) {
                row = "no river water within " + REACH + " blocks of the root";
                failures.add(String.format(Locale.ROOT, "%s owner %d,%d root @%.0f,%.0f w=%.1f: %s", label, ownerX, ownerZ, root.x(), root.z(), 2 * halfWidth, row));
            } else {
                double[] sea = seaDistance(coast, coastEdge, last[0], last[1], halfWidth + SEA_SLACK);
                int dry = Double.isFinite(sea[0]) ? dryCells(context, coastEdge, last[0], last[1], sea[1], sea[2], sea[0]) : -1;
                row = String.format(Locale.ROOT, "lastRiverCell %.0f,%.0f seaDistance=%.0f dry=%d", last[0], last[1], sea[0], dry);
                if (!Double.isFinite(sea[0]) || dry > MAX_DRY)
                    failures.add(String.format(Locale.ROOT, "%s owner %d,%d root @%.0f,%.0f w=%.1f: %s (limit %.1f blocks, %d dry cells)", label, ownerX, ownerZ, root.x(), root.z(), 2 * halfWidth, row, halfWidth + SEA_SLACK, MAX_DRY));
            }
            out.append(String.format(Locale.ROOT, "  %s owner %d,%d root @%.0f,%.0f w=%.1f land=%s rootSeaDistance=%.0f rootDry=%d %s%n", label, ownerX, ownerZ, root.x(), root.z(), 2 * halfWidth, land, rootSea[0], rootDry, row));
        }
        out.append(String.format(Locale.ROOT, "%s owner %d,%d: roots=%d land roots=%d wide roots=%d failures=%d%n", label, ownerX, ownerZ, roots, landRoots, wide, failures.size()));
        return failures;
    }

    /**
     * G-11-6 A (D-GC4): on the server preset (owner 0,0, with the river at 7453,3341), the default preset (seeds 42, -314159 and
     * 8675309, owner 0,0, with the root at -4339,252 of seed 42) and Test-America-v3 (the owners of the RIA site and of 14215,14731),
     * every river of width 4 or more reaches block-resolution sea.
     */
    @Test void mouthsReachTheSea() throws Exception {
        var out = new StringBuilder();
        var failures = new ArrayList<String>();
        for (var fixture : fixtures()) {
            var context = WorldgenFixture.shared(fixture.preset().call(), fixture.seed());
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            var owners = new ArrayList<int[]>(List.of(fixture.owners()));
            for (int[] block : fixture.blocks()) {
                var owner = coast.undrowned(block[0], block[1]);
                owners.add(new int[]{owner.ownerX(), owner.ownerZ()});
            }
            for (int[] owner : owners) failures.addAll(measure(fixture.label(), context, owner[0], owner[1], out));
        }
        System.out.println("RH10 river mouths (11-17):\n" + out);
        assertTrue(failures.isEmpty(), failures.size() + " rivers of width " + MIN_WIDTH + " or more do not reach the sea:\n" + String.join("\n", failures));
    }

    /** The bed at the last river cell may differ from the sea floor this many blocks seaward of the mouth by at most {@link #SILL_STEP}. */
    static final double SILL_STEP = 2, SILL_WATER = .5, SILL_SLACK = .05;
    /** Seaward distances (blocks past the coast contour) at which the floor is compared with the bed, and the range in which it may not be shallower. */
    static final int SILL_NEAR = 2, SILL_FROM = 4, SILL_TO = 30;

    private static double ground(GeneratorContext context, Cell cell) { return (cell.height - context.levels.water) * context.levels.terrainScaleFactor; }

    /**
     * Ground height of the sea column {@code distance} blocks past the coast contour along (dx, dz) from the contour point (x, z), or negative
     * infinity where the column is not sea floor (a sea stack stands there, or the ray has reached other land) and a river bed is not held to it.
     */
    private static double seaFloor(GeneratorContext context, double x, double z, double dx, double dz, double distance) {
        var cell = new Cell();
        context.heightmap.apply(cell.reset(), (int) Math.round(x + dx * distance), (int) Math.round(z + dz * distance), false);
        return cell.highlandCoast > 0 || cell.continentEdge > context.preset.world().controlPoints.coast ? Double.NEGATIVE_INFINITY : ground(context, cell);
    }

    /** The coast contour on the segment from the land point (x, z) toward (dx, dz), found by bisection within {@code length} blocks (the start when it already is sea). */
    private static double contour(HighlandCoast coast, double coastEdge, double x, double z, double dx, double dz, double length) {
        double lo = 0, hi = length;
        if (coast.sample(x, z).edge() <= coastEdge) return 0;
        for (int i = 0; i < 10; i++) {
            double mid = (lo + hi) / 2;
            if (coast.sample(x + dx * mid, z + dz * mid).edge() <= coastEdge) hi = mid; else lo = mid;
        }
        return hi;
    }

    /** Step, sill and dome of the sea floor along (dx, dz) from the contour point (mx, mz) against {@code bed}: {floor at SILL_NEAR, step, sill length, crest, dome}. */
    private static double[] sillRay(GeneratorContext context, double mx, double mz, double dx, double dz, double bed) {
        double near = seaFloor(context, mx, mz, dx, dz, SILL_NEAR), step = Double.isInfinite(near) ? 0 : near - bed, crest = Double.NEGATIVE_INFINITY, sill = 0;
        for (int d = 0; d <= SILL_TO; d++) {
            double floor = seaFloor(context, mx, mz, dx, dz, d);
            if (floor > bed + SILL_SLACK) { sill = d; crest = Math.max(crest, floor); }
        }
        double dome = Double.NEGATIVE_INFINITY;
        for (int d = SILL_FROM; d <= SILL_TO; d += 2) dome = Math.max(dome, seaFloor(context, mx, mz, dx, dz, d) - bed);
        return new double[]{near, step, sill, crest, dome};
    }

    /** Seaward unit direction of the drawn main channel (largest-area children from {@code root}) at the segment nearest (x, z), from plan nodes only. */
    private static double[] channelDirection(List<HighlandDrainage.Node> nodes, int root, double x, double z) {
        double best = Double.POSITIVE_INFINITY, dx = 1, dz = 0;
        for (int current = root; ; ) {
            int next = -1;
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).parent() == current && (next < 0 || nodes.get(i).area() > nodes.get(next).area())) next = i;
            if (next < 0) break;
            var a = nodes.get(current); var b = nodes.get(next);
            double sx = a.x() - b.x(), sz = a.z() - b.z(), length2 = sx * sx + sz * sz;
            double t = length2 > 1e-12 ? Math.max(0, Math.min(1, ((x - b.x()) * sx + (z - b.z()) * sz) / length2)) : 0;
            double d = Math.hypot(x - b.x() - sx * t, z - b.z() - sz * t);
            if (d < best && length2 > 1e-12) { best = d; double l = Math.sqrt(length2); dx = sx / l; dz = sz / l; }
            current = next;
        }
        return new double[]{dx, dz};
    }

    /**
     * Every sea-reaching channel (a plan root with a child whose last rendered river cell lies within halfWidth + {@link #SEA_SLACK}
     * of sea): the bed at that cell against the sea floor {@link #SILL_NEAR} blocks seaward of the contour (at most {@link #SILL_STEP}
     * apart), the floor from {@link #SILL_FROM} to {@link #SILL_TO} blocks seaward never shallower than that bed (no dome), and the
     * planned water there at most {@link #SILL_WATER} above sea. Where the nearest-sea ray leaves the channel before {@link #SILL_TO} blocks (the channel meets the coast at an angle, or is narrow) the
     * step and dome are measured along the drawn channel's own direction instead (user ruling 2026-11-11, thresholds unchanged); every row prints both measures and the mode.
     * Returns the failures; one line per channel goes to {@code out}.
     */
    private static List<String> sills(String label, GeneratorContext context, int ownerX, int ownerZ, StringBuilder out) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var nodes = continent.planOf(ownerX, ownerZ).plan().nodes();
        boolean[] hasChild = new boolean[nodes.size()];
        for (var node : nodes) if (node.parent() >= 0) hasChild[node.parent()] = true;
        var failures = new ArrayList<String>();
        var cell = new Cell();
        int channels = 0;
        for (int i = 0; i < nodes.size(); i++) {
            var root = nodes.get(i);
            if (root.parent() >= 0 || !hasChild[i]) continue;
            double halfWidth = continent.drainage().halfWidth(root.area(), 1);
            var last = lastRiverCell(context, coastEdge, nodes, i);
            if (last == null) continue;
            double[] sea = seaDistance(coast, coastEdge, last[0], last[1], halfWidth + SEA_SLACK);
            if (!Double.isFinite(sea[0])) continue;
            channels++;
            context.heightmap.apply(cell.reset(), (int) Math.round(last[0]), (int) Math.round(last[1]), false);
            double bed = ground(context, cell), water = (cell.riverWaterLevel - context.levels.water) * context.levels.terrainScaleFactor;
            double reach = contour(coast, coastEdge, last[0], last[1], sea[1], sea[2], sea[0]);
            double mx = last[0] + sea[1] * reach, mz = last[1] + sea[2] * reach;
            double[] ray = sillRay(context, mx, mz, sea[1], sea[2], bed);
            // 11-24 diagnosis (user ruling 2026-11-11): the drawn channel's own direction at the last river cell, its angle to the nearest-sea ray, and the
            // distance at which the ray leaves the channel (lateral distance above the half width). Where the ray leaves before SILL_TO the same step and
            // dome are also measured along the channel direction, from where that line crosses the coast.
            double[] chan = channelDirection(nodes, i, last[0], last[1]);
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, chan[0] * sea[1] + chan[1] * sea[2]))));
            double leaves = Double.POSITIVE_INFINITY;
            for (int d = 1; d <= SILL_TO + SILL_NEAR && Double.isInfinite(leaves); d++)
                if (Math.abs((sea[1] * d) * chan[1] - (sea[2] * d) * chan[0]) > halfWidth) leaves = d;
            double[] along = null;
            double reachC = contour(coast, coastEdge, last[0], last[1], chan[0], chan[1], 64);
            if (coast.sample(last[0] + chan[0] * reachC, last[1] + chan[1] * reachC).edge() <= coastEdge)
                along = sillRay(context, last[0] + chan[0] * reachC, last[1] + chan[1] * reachC, chan[0], chan[1], bed);
            boolean rayFails = Math.abs(ray[1]) > SILL_STEP || ray[4] > SILL_SLACK;
            boolean alongMode = rayFails && leaves < SILL_TO && along != null;
            double[] used = alongMode ? along : ray;
            String row = String.format(Locale.ROOT, "last %.0f,%.0f seaDistance=%.0f contour=%.1f bed=%.2f floor(+%d)=%.2f step=%.2f sillLength=%.0f crest=%.2f dome=%.2f water=%.2f | angle=%.0f leavesAt=%.0f channelStep=%s channelDome=%s mode=%s",
                last[0], last[1], sea[0], reach, bed, SILL_NEAR, ray[0], ray[1], ray[2], ray[3], ray[4], water, angle, leaves,
                along == null ? "n/a" : String.format(Locale.ROOT, "%.2f", along[1]), along == null ? "n/a" : String.format(Locale.ROOT, "%.2f", along[4]), alongMode ? "channel" : "ray");
            out.append(String.format(Locale.ROOT, "  %s owner %d,%d root @%.0f,%.0f w=%.1f %s%n", label, ownerX, ownerZ, root.x(), root.z(), 2 * halfWidth, row));
            if (Math.abs(used[1]) > SILL_STEP || used[4] > SILL_SLACK || water > SILL_WATER)
                failures.add(String.format(Locale.ROOT, "%s owner %d,%d root @%.0f,%.0f w=%.1f: %s", label, ownerX, ownerZ, root.x(), root.z(), 2 * halfWidth, row));
        }
        out.append(String.format(Locale.ROOT, "%s owner %d,%d: channels=%d failures=%d%n", label, ownerX, ownerZ, channels, failures.size()));
        return failures;
    }

    /**
     * G-11-6 B1 (D-GC4, 11-18): the river bed meets the sea floor at sea level (RH8.1 origin: the bed stopped at the coast contour while
     * the shoreface floor started shallow): no step, no wall, no submarine dome, no raised water at any sea-reaching channel.
     */
    @Test void mouthsHaveNoSills() throws Exception {
        var out = new StringBuilder();
        var failures = new ArrayList<String>();
        for (var fixture : fixtures()) {
            var context = WorldgenFixture.shared(fixture.preset().call(), fixture.seed());
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            var owners = new ArrayList<int[]>(List.of(fixture.owners()));
            for (int[] block : fixture.blocks()) {
                var owner = coast.undrowned(block[0], block[1]);
                owners.add(new int[]{owner.ownerX(), owner.ownerZ()});
            }
            for (int[] owner : owners) failures.addAll(sills(fixture.label(), context, owner[0], owner[1], out));
        }
        System.out.println("RH10 river mouth sills (11-18):\n" + out);
        assertTrue(failures.isEmpty(), failures.size() + " channels have a sill, dome or raised water at the mouth:\n" + String.join("\n", failures));
    }

    /** Rendered ground (blocks above sea) and cell kind at a position, through the continent's own precise path. Null where the column is not sea floor or river bed (a stack, or land away from the channel). */
    private static double render(GeneratorContext context, Cell cell, double x, double z) {
        context.heightmap.applyPrecise(cell.reset(), x, z, false);
        return ground(context, cell);
    }

    private static boolean stackOrLand(GeneratorContext context, Cell cell) {
        return cell.highlandCoast > 0 || (cell.continentEdge > context.preset.world().controlPoints.coast && !cell.hasRiverWater);
    }

    /**
     * The mouth profile checks of one owner (D-11-18-1): every sea-reaching channel's profile, through context.heightmap at its stations.
     * (a) along: from the crossing to the trough end the bed never rises seaward and the centreline ground never stands more than {@link #SILL_SLACK} above it (the natural floor may dip below the bed and rise again, which is no sill; beyond the trough end the clear run follows it);
     * (b) wet depth: water minus ground at least {@link RollingHighlandsContinent#MOUTH_MIN_WET} minus {@link #SILL_SLACK} from the upstream
     * end to the trough end; (c) agreement: at sea stations where the bed lies below the sea floor the rendered ground equals it; (d) across:
     * at the first sea station and at the trough midpoint a 1-block transect from the centre outward, measured as depth below the sea floor there (the floor itself
     * slopes), never deepens, its steps beyond the core are at most {@link RollingHighlandsContinent#MOUTH_BANK_SLOPE} + slack and its outer end is back at the floor; (e) beyond: ground from the
     * trough end to 32 blocks past it is never shallower than the trough bed plus slack; (f) status: no LAND or REACH profile, no station
     * outside the mouth band. One line per owner goes to {@code out}, then one per profile; returns the failures.
     */
    private static List<String> profileChecks(String label, GeneratorContext context, int ownerX, int ownerZ, StringBuilder out) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        double coastEdge = context.preset.world().controlPoints.coast;
        var profiles = continent.mouths(continent.planOf(ownerX, ownerZ)).profiles();
        var failures = new ArrayList<String>();
        var cell = new Cell();
        int feasible = 0, outside = 0, crossOwner = 0;
        double deepest = 0, radius = 0;
        for (var p : profiles) {
            int c = p.crossing(), e = p.trough();
            String id = String.format(Locale.ROOT, "%s owner %d,%d mouth @%.0f,%.0f w=%.1f", label, ownerX, ownerZ, p.x()[c], p.z()[c], 2 * p.halfWidth()[c]);
            outside += p.outsideBand(); crossOwner += p.crossOwnerStations(); radius = Math.max(radius, p.radius());
            deepest = Math.max(deepest, (coastEdge - p.minEdge()) / HighlandCoast.EDGE_PER_BLOCK);
            var row = new StringBuilder(String.format(Locale.ROOT, "  %s status=%d stations=%d trough=%s b0=%.2f outsideBand=%d crossOwner=%d minEdgeBlocks=%.0f", id, p.status(), p.stations(),
                e < 0 ? "none" : String.format(Locale.ROOT, "%.0f", -p.s()[e]), p.bed()[c], p.outsideBand(), p.crossOwnerStations(), (coastEdge - p.minEdge()) / HighlandCoast.EDGE_PER_BLOCK));
            if (p.status() == HighlandMouths.FEASIBLE) feasible++;
            else failures.add(id + ": status " + (p.status() == HighlandMouths.LAND ? "LAND (re-emerging land before the trough end)" : "REACH (no trough end within the reach limit)") + String.format(Locale.ROOT, ", last station s=%.0f at %.0f,%.0f: sea floor there %.2f, trough bed %.2f (floor at the crossing %.2f)", p.s()[0], p.x()[0], p.z()[0], continent.backgroundAt(p.x()[0], p.z()[0]), p.bed()[0], continent.backgroundAt(p.x()[c - 1], p.z()[c - 1])));
            if (p.outsideBand() > 0) failures.add(String.format(Locale.ROOT, "%s: %d stations outside the mouth band (deepest %.0f of %.0f blocks)", id, p.outsideBand(), (coastEdge - p.minEdge()) / HighlandCoast.EDGE_PER_BLOCK, RollingHighlandsContinent.MOUTH_BAND_BLOCKS));
            if (p.status() != HighlandMouths.FEASIBLE) { out.append(row).append('\n'); continue; }
            // (a) along, seaward from the crossing to the trough end: the bed never rises seaward and the ground never stands above it (the natural floor may dip
            // below the bed and rise again, which is not a sill); beyond the trough end the clear run follows the natural floor. (b) wet depth from the upstream end to the trough end.
            double worstRise = 0, worstDepth = Double.POSITIVE_INFINITY, worstOver = Double.NEGATIVE_INFINITY, previous = Double.NaN;
            for (int i = c; i >= e; i--) {
                if (i < c && p.bed()[i] > p.bed()[i + 1] + 1e-9) failures.add(String.format(Locale.ROOT, "%s: along: bed rises seaward from %.2f to %.2f at s=%.0f", id, p.bed()[i + 1], p.bed()[i], p.s()[i]));
                double g = render(context, cell, p.x()[i], p.z()[i]);
                if (stackOrLand(context, cell)) continue;
                if (!Double.isNaN(previous)) worstRise = Math.max(worstRise, g - previous);
                worstOver = Math.max(worstOver, g - p.bed()[i]);
                if (g > p.bed()[i] + SILL_SLACK) failures.add(String.format(Locale.ROOT, "%s: along: ground %.2f above the bed %.2f at s=%.0f (%.0f,%.0f)", id, g, p.bed()[i], p.s()[i], p.x()[i], p.z()[i]));
                previous = g;
            }
            for (int i = p.stations() - 1; i >= e; i--) {
                double g = render(context, cell, p.x()[i], p.z()[i]);
                double water;
                if (cell.hasRiverWater) water = (cell.riverWaterLevel - context.levels.water) * context.levels.terrainScaleFactor;
                else if (cell.continentEdge <= coastEdge) water = 0;
                else { failures.add(String.format(Locale.ROOT, "%s: wet depth: no water at s=%.0f (%.0f,%.0f)", id, p.s()[i], p.x()[i], p.z()[i])); continue; }
                worstDepth = Math.min(worstDepth, water - g);
                if (water - g < RollingHighlandsContinent.MOUTH_MIN_WET - SILL_SLACK) failures.add(String.format(Locale.ROOT, "%s: wet depth: %.2f at s=%.0f (%.0f,%.0f) water=%.2f ground=%.2f", id, water - g, p.s()[i], p.x()[i], p.z()[i], water, g));
            }
            // (c) agreement at sea stations where the bed lies below the sea floor.
            for (int i = c - 1; i >= 0; i--) {
                double floor = continent.backgroundAt(p.x()[i], p.z()[i]);
                if (Double.isNaN(floor) || p.bed()[i] >= floor - SILL_SLACK) continue;
                double g = render(context, cell, p.x()[i], p.z()[i]);
                if (cell.highlandCoast > 0) continue;
                if (Math.abs(g - p.bed()[i]) > SILL_SLACK) failures.add(String.format(Locale.ROOT, "%s: agreement: ground %.2f vs bed %.2f at s=%.0f (%.0f,%.0f), floor %.2f", id, g, p.bed()[i], p.s()[i], p.x()[i], p.z()[i], floor));
            }
            // (d) across.
            double worstAcross = 0;
            for (int i : new int[]{c - 1, (c + e) / 2}) {
                if (i < 0 || i >= c) continue;
                int lo = Math.max(0, i - 1), hi = Math.min(p.stations() - 1, i + 1);
                double tx = p.x()[hi] - p.x()[lo], tz = p.z()[hi] - p.z()[lo], tl = Math.hypot(tx, tz), nx = -tz / tl, nz = tx / tl;
                double run = Math.max(RollingHighlandsContinent.MOUTH_BANK_MIN, RollingHighlandsContinent.MOUTH_BANK_FACTOR * Math.max(0, -p.bed()[i]) / RollingHighlandsContinent.MOUTH_BANK_SLOPE);
                int reach = (int) Math.ceil(p.halfWidth()[i] + run + 8);
                for (int side = -1; side <= 1; side += 2) {
                    double prev = Double.NaN, prevFloor = Double.NaN;
                    for (int d = 0; d <= reach; d++) {
                        double px = p.x()[i] + side * nx * d, pz = p.z()[i] + side * nz * d, g = render(context, cell, px, pz), floorHere = continent.backgroundAt(px, pz);
                        boolean valid = !stackOrLand(context, cell) && !Double.isNaN(floorHere);
                        if (valid && floorHere - g < -SILL_SLACK) failures.add(String.format(Locale.ROOT, "%s: across: ground %.2f above the floor %.2f at s=%.0f, lateral %d (%.0f,%.0f)", id, g, floorHere, p.s()[i], side * d, px, pz));
                        // Bank steepness: the ground's rise outward beyond the floor's own rise (the floor itself may slope across the channel).
                        if (valid && !Double.isNaN(prev) && d > p.halfWidth()[i]) {
                            double step = g - prev - Math.max(0, floorHere - prevFloor);
                            worstAcross = Math.max(worstAcross, step);
                            if (step > RollingHighlandsContinent.MOUTH_BANK_SLOPE + SILL_SLACK) failures.add(String.format(Locale.ROOT, "%s: across: bank step %.2f beyond the core at s=%.0f, lateral %d (%.0f,%.0f)", id, step, p.s()[i], side * d, px, pz));
                        }
                        if (valid) { prev = g; prevFloor = floorHere; } else prev = Double.NaN;
                        if (valid && d == reach && Math.abs(floorHere - g) > SILL_SLACK)
                            failures.add(String.format(Locale.ROOT, "%s: across: outer end %.2f vs floor %.2f at s=%.0f, lateral %d", id, g, floorHere, p.s()[i], side * d));
                    }
                }
            }
            // (e) beyond: from the trough end along the profile, then straight on to 32 blocks past it.
            double ceiling = p.bed()[e] + SILL_SLACK, worstBeyond = Double.NEGATIVE_INFINITY;
            for (int d = 0; d <= 32; d += 2) {
                int i = Math.max(0, e - d / 2);
                double ax = p.x()[i], az = p.z()[i];
                if (e - d / 2 < 0) {
                    double dx = p.x()[0] - p.x()[Math.min(p.stations() - 1, 1)], dz = p.z()[0] - p.z()[Math.min(p.stations() - 1, 1)], dl = Math.hypot(dx, dz);
                    ax += dx / dl * (d - 2 * e);
                    az += dz / dl * (d - 2 * e);
                }
                double g = render(context, cell, ax, az);
                if (stackOrLand(context, cell)) continue;
                worstBeyond = Math.max(worstBeyond, g - p.bed()[e]);
                if (g > ceiling) failures.add(String.format(Locale.ROOT, "%s: beyond: ground %.2f above the trough bed %.2f at %d blocks past the trough end (%.0f,%.0f)", id, g, p.bed()[e], d, ax, az));
            }
            out.append(row.append(String.format(Locale.ROOT, " worstRise=%.2f worstOverBed=%.2f worstWetDepth=%.2f worstAcrossStep=%.2f worstBeyond=%.2f%n", worstRise, worstOver, worstDepth, worstAcross, worstBeyond)));
        }
        long infeasible = profiles.size() - feasible;
        out.append(String.format(Locale.ROOT, "%s owner %d,%d: mouths=%d feasible=%d infeasible=%d outsideBand=%d crossOwnerStations=%d deepestStationBlocks=%.0f maxCorridorRadius=%.0f failures=%d%n", label, ownerX, ownerZ,
            profiles.size(), feasible, infeasible, outside, crossOwner, deepest, radius, failures.size()));
        return failures;
    }

    /**
     * D-11-18-1 (11-24): every sea-reaching channel has one plan-owned mouth profile the land and the sea branch both render. Along the
     * channel the bed never rises seaward, wet depth is at least one block, the trough blends laterally into the seabed and nothing is
     * shallower beyond its end; no profile is infeasible or leaves the mouth band (see {@link #profileChecks}).
     */
    @Test void mouthProfilesAlongTheChannel() throws Exception {
        var out = new StringBuilder();
        var failures = new ArrayList<String>();
        for (var fixture : fixtures()) {
            var context = WorldgenFixture.shared(fixture.preset().call(), fixture.seed());
            var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
            var owners = new ArrayList<int[]>(List.of(fixture.owners()));
            for (int[] block : fixture.blocks()) {
                var owner = coast.undrowned(block[0], block[1]);
                owners.add(new int[]{owner.ownerX(), owner.ownerZ()});
            }
            for (int[] owner : owners) failures.addAll(profileChecks(fixture.label(), context, owner[0], owner[1], out));
        }
        System.out.println("RH10 river mouth profiles (11-24):\n" + out);
        assertTrue(failures.isEmpty(), failures.size() + " mouth profile checks fail:\n" + String.join("\n", failures));
    }

    /** Per column in the given order: height, river water level, river water flag, coast flag and water class, as raw bits; index = position in {@code columns}. */
    private static long[][] capture(GeneratorContext context, List<int[]> columns, int[] order, List<int[]> first) {
        var cell = new Cell();
        for (int[] c : first) context.heightmap.applyPrecise(cell.reset(), c[0], c[1], false);
        long[][] rows = new long[columns.size()][];
        for (int i : order) {
            context.heightmap.applyPrecise(cell.reset(), columns.get(i)[0], columns.get(i)[1], false);
            rows[i] = new long[]{Float.floatToIntBits(cell.height), Float.floatToIntBits(cell.riverWaterLevel), cell.hasRiverWater ? 1 : 0, Float.floatToIntBits(cell.highlandCoast), cell.highlandWater};
        }
        return rows;
    }

    /**
     * D-11-18-1 no generation-order seams (11-24, T-11-40): on server (0,0), default 42 (0,0) and the seam owner server (-1,-1), the corridor columns (every
     * 4th station of every profile and two lateral points at half width plus half a bank run) and 32 band sea columns away from every corridor render
     * bit-identical height, river water, coast flag and water class in three fresh contexts that meet them in different orders: A sea columns first, B land
     * columns first, C one land column of each neighbour owner first and then the list in reverse.
     */
    @Test void mouthProfilesArePathIndependent() throws Exception {
        var fixtures = fixtures();
        var cases = List.of(new Object[]{fixtures.get(0), 0, 0}, new Object[]{fixtures.get(1), 0, 0},
            new Object[]{fixtures.get(0), HighlandDrownedTest.SEAM_OWNER[0], HighlandDrownedTest.SEAM_OWNER[1]});
        var failures = new ArrayList<String>();
        var out = new StringBuilder();
        for (var one : cases) {
            var fixture = (Fixture) one[0];
            int ownerX = (Integer) one[1], ownerZ = (Integer) one[2];
            var shared = WorldgenFixture.shared(fixture.preset().call(), fixture.seed());
            var continent = (RollingHighlandsContinent) shared.heightmap.continent();
            double coastEdge = shared.preset.world().controlPoints.coast;
            var mouths = continent.mouths(continent.planOf(ownerX, ownerZ));
            var columns = new ArrayList<int[]>();
            var sea = new ArrayList<Boolean>();
            for (var p : mouths.profiles()) for (int i = 0; i < p.stations(); i += 4) {
                int lo = Math.max(0, i - 1), hi = Math.min(p.stations() - 1, i + 1);
                double tx = p.x()[hi] - p.x()[lo], tz = p.z()[hi] - p.z()[lo], tl = Math.hypot(tx, tz);
                double floor = continent.backgroundAt(p.x()[i], p.z()[i]);
                double run = Math.max(RollingHighlandsContinent.MOUTH_BANK_MIN, RollingHighlandsContinent.MOUTH_BANK_FACTOR * (Double.isNaN(floor) ? 0 : Math.max(0, floor - p.bed()[i])) / RollingHighlandsContinent.MOUTH_BANK_SLOPE);
                double offset = p.halfWidth()[i] + run / 2;
                for (double[] at : new double[][]{{p.x()[i], p.z()[i]}, {p.x()[i] - tz / tl * offset, p.z()[i] + tx / tl * offset}, {p.x()[i] + tz / tl * offset, p.z()[i] - tx / tl * offset}})
                    columns.add(new int[]{(int) Math.round(at[0]), (int) Math.round(at[1])});
            }
            int corridor = columns.size(), far = 0;
            for (int[] c : missColumns(shared, new CostKey("seam", fixture, ownerX, ownerZ))) {
                if (far == 32) break;
                if (continent.mouths(continent.planOf(ownerX, ownerZ)).query(c[0], c[1]).count == 0) { columns.add(c); far++; }
            }
            for (int[] c : columns) sea.add(continent.coast().sample(c[0], c[1]).edge() <= coastEdge);
            var seaFirst = new ArrayList<Integer>(); var landFirst = new ArrayList<Integer>();
            for (int i = 0; i < columns.size(); i++) if (sea.get(i)) seaFirst.add(i); else landFirst.add(i);
            int[] orderA = new int[columns.size()], orderB = new int[columns.size()], orderC = new int[columns.size()];
            int n = 0;
            for (int i : seaFirst) orderA[n++] = i;
            for (int i : landFirst) orderA[n++] = i;
            n = 0;
            for (int i : landFirst) orderB[n++] = i;
            for (int i : seaFirst) orderB[n++] = i;
            for (int i = 0; i < columns.size(); i++) orderC[i] = columns.size() - 1 - i;
            // Neighbour land columns for C: land samples of other owners around the first corridor column.
            var neighbours = new ArrayList<int[]>();
            int[] anchor = columns.get(0);
            for (int ring = 1; ring <= 3 && neighbours.size() < 4; ring++)
                for (int dz = -ring; dz <= ring; dz++) for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || neighbours.size() >= 4) continue;
                    int px = anchor[0] + dx * 2500, pz = anchor[1] + dz * 2500;
                    var at = continent.coast().sample(px, pz);
                    if ((at.ownerX() != ownerX || at.ownerZ() != ownerZ) && at.edge() > coastEdge) neighbours.add(new int[]{px, pz});
                }
            long[][] a, b, c;
            var firstSea = List.of(columns.get(seaFirst.isEmpty() ? 0 : seaFirst.get(0)));
            try (var context = WorldgenFixture.context(fixture.preset().call(), fixture.seed())) { a = capture(context, columns, orderA, firstSea); }
            try (var context = WorldgenFixture.context(fixture.preset().call(), fixture.seed())) { b = capture(context, columns, orderB, List.of()); }
            try (var context = WorldgenFixture.context(fixture.preset().call(), fixture.seed())) { c = capture(context, columns, orderC, neighbours); }
            int mismatches = 0;
            for (int i = 0; i < columns.size(); i++) {
                if (!Arrays.equals(a[i], b[i]) || !Arrays.equals(a[i], c[i])) {
                    if (mismatches++ == 0) failures.add(String.format(Locale.ROOT, "%s owner %d,%d: first mismatch at column %d (%d,%d) sea=%s: A=%s B=%s C=%s", fixture.label(), ownerX, ownerZ, i, columns.get(i)[0], columns.get(i)[1], sea.get(i),
                        Arrays.toString(a[i]), Arrays.toString(b[i]), Arrays.toString(c[i])));
                }
            }
            if (mismatches > 0) failures.add(String.format(Locale.ROOT, "%s owner %d,%d: %d of %d columns differ between orders", fixture.label(), ownerX, ownerZ, mismatches, columns.size()));
            out.append(String.format(Locale.ROOT, "%s owner %d,%d: columns=%d (corridor %d, away %d, sea %d, neighbour land columns first in C %d) mismatches=%d%n", fixture.label(), ownerX, ownerZ, columns.size(), corridor, far, seaFirst.size(), neighbours.size(), mismatches));
        }
        System.out.println("RH10 river mouth path independence (11-24):\n" + out);
        assertTrue(failures.isEmpty(), failures.size() + " path independence failures:\n" + String.join("\n", failures));
    }

    /** One owner to measure the mouth cost on (label "<fixture>/<x>,<z>"). */
    private record CostKey(String name, Fixture fixture, int ownerX, int ownerZ) {}

    /** The seven cost owners: the six of {@link #fixtures()} (Test-America-v3 by the owners of its two block positions) and the seam owner of the server preset. */
    private static List<CostKey> costKeys() throws Exception {
        var fixtures = fixtures();
        String[] names = {"server", "default42", "default-314159", "default8675309"};
        var keys = new ArrayList<CostKey>();
        for (int i = 0; i < names.length; i++) keys.add(new CostKey(names[i] + "/0,0", fixtures.get(i), 0, 0));
        var america = fixtures.get(4);
        var coast = ((RollingHighlandsContinent) WorldgenFixture.shared(america.preset().call(), america.seed()).heightmap.continent()).coast();
        for (int[] block : america.blocks()) {
            var owner = coast.undrowned(block[0], block[1]);
            keys.add(new CostKey("TAv3/" + owner.ownerX() + "," + owner.ownerZ(), america, owner.ownerX(), owner.ownerZ()));
        }
        keys.add(new CostKey("server/" + HighlandDrownedTest.SEAM_OWNER[0] + "," + HighlandDrownedTest.SEAM_OWNER[1], fixtures.get(0), HighlandDrownedTest.SEAM_OWNER[0], HighlandDrownedTest.SEAM_OWNER[1]));
        return keys;
    }

    /** Plan roots of an owner with a child (the sea-reaching channels). */
    private static List<HighlandDrainage.Node> channelRoots(RollingHighlandsContinent continent, int ownerX, int ownerZ) {
        var nodes = continent.planOf(ownerX, ownerZ).plan().nodes();
        boolean[] hasChild = new boolean[nodes.size()];
        for (var node : nodes) if (node.parent() >= 0) hasChild[node.parent()] = true;
        var roots = new ArrayList<HighlandDrainage.Node>();
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).parent() < 0 && hasChild[i]) roots.add(nodes.get(i));
        return roots;
    }

    /** Mouth set (position-only): sea columns on a 2-block grid (even coordinates) within 64 blocks (square window) of each channel root of the owner's plan. */
    private static List<int[]> mouthColumns(GeneratorContext context, CostKey key) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        double coastEdge = context.preset.world().controlPoints.coast;
        var seen = new LinkedHashSet<Long>();
        var columns = new ArrayList<int[]>();
        for (var root : channelRoots(continent, key.ownerX(), key.ownerZ()))
            for (int z = (int) Math.floor(root.z() / 2) * 2 - 64; z <= root.z() + 64; z += 2)
                for (int x = (int) Math.floor(root.x() / 2) * 2 - 64; x <= root.x() + 64; x += 2)
                    if (continent.coast().sample(x, z).edge() <= coastEdge && seen.add(((long) x << 32) ^ (z & 0xffffffffL))) columns.add(new int[]{x, z});
        return columns;
    }

    /**
     * Miss set (position-only): sea columns on a 16-block grid (z outer, x inner) inside the owner's bounds whose edge lies within 256 x
     * {@link HighlandCoast#EDGE_PER_BLOCK} below the coast point and which are farther than 160 blocks from every channel root; the first 2000.
     */
    private static List<int[]> missColumns(GeneratorContext context, CostKey key) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        double coastEdge = context.preset.world().controlPoints.coast, band = 256 * HighlandCoast.EDGE_PER_BLOCK;
        var roots = channelRoots(continent, key.ownerX(), key.ownerZ());
        var bounds = continent.coast().bounds(key.ownerX(), key.ownerZ());
        var columns = new ArrayList<int[]>();
        for (int z = (int) Math.ceil(bounds.minZ() / 16) * 16; z <= bounds.maxZ() && columns.size() < 2000; z += 16)
            for (int x = (int) Math.ceil(bounds.minX() / 16) * 16; x <= bounds.maxX() && columns.size() < 2000; x += 16) {
                double edge = continent.coast().sample(x, z).edge();
                if (edge > coastEdge || edge < coastEdge - band) continue;
                final int fx = x, fz = z;
                if (roots.stream().noneMatch(r -> Math.hypot(r.x() - fx, r.z() - fz) <= 160)) columns.add(new int[]{x, z});
            }
        return columns;
    }

    private static double medianNsPerColumn(GeneratorContext context, List<int[]> columns, int passes) {
        var cell = new Cell();
        var times = new double[passes];
        for (int pass = -1; pass < passes; pass++) {
            long t = System.nanoTime();
            for (int[] c : columns) context.heightmap.apply(cell.reset(), c[0], c[1], false);
            if (pass >= 0) times[pass] = (System.nanoTime() - t) / (double) Math.max(1, columns.size());
        }
        Arrays.sort(times);
        return times[passes / 2];
    }

    private static List<int[]> box(int centreX, int centreZ) {
        var columns = new ArrayList<int[]>();
        for (int z = centreZ - 512; z < centreZ + 512; z += 4) for (int x = centreX - 512; x < centreX + 512; x += 4) columns.add(new int[]{x, z});
        return columns;
    }

    /** Count of owners with a sea sample inside the test band (256 blocks of edge) but no land sample, over an n x n raster at the given stride around (0,0). */
    private static int onlySeaOwners(RollingHighlandsContinent continent, double coastEdge, int n, int stride) {
        Set<Long> sea = new HashSet<>(), land = new HashSet<>();
        double band = 256 * HighlandCoast.EDGE_PER_BLOCK;
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            var s = continent.coast().sample((i - n / 2) * stride, (j - n / 2) * stride);
            long owner = ((long) s.ownerX() << 32) ^ (s.ownerZ() & 0xffffffffL);
            if (s.edge() > coastEdge) land.add(owner); else if (s.edge() >= coastEdge - band) sea.add(owner);
        }
        sea.removeAll(land);
        return sea.size();
    }

    /** The first {@code mouths()} on a fresh index (empty slot) of the owner's rebuilt plan: build ms, profiles, feasible ones, stations, mouth bytes and the index's own retained bytes. */
    private static String mouthBuildLine(GeneratorContext context, CostKey key) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        double x = 0, z = 0;
        for (var node : continent.planOf(key.ownerX(), key.ownerZ()).plan().nodes()) {
            var owner = continent.coast().sample(node.x(), node.z());
            if (owner.ownerX() == key.ownerX() && owner.ownerZ() == key.ownerZ()) { x = node.x(); z = node.z(); break; }
        }
        // The rebuilt index may already carry mouths (its lake passes render coast columns), so time the build on a copy of its plan with an empty slot.
        var rebuilt = continent.rebuildAt(x, z);
        var index = new HighlandPlanIndex(rebuilt.plan(), rebuilt.relief);
        long t = System.nanoTime();
        var mouths = continent.mouths(index);
        double ms = (System.nanoTime() - t) / 1e6;
        long stations = 0, feasible = 0;
        for (var profile : mouths.profiles()) { stations += profile.stations(); if (profile.status() == HighlandMouths.FEASIBLE) feasible++; }
        return String.format(Locale.ROOT, "mouthBuild %s ms=%.2f mouths=%d feasible=%d stations=%d bytes=%d planBytes=%d", key.name(), ms, mouths.profiles().size(), feasible, stations, mouths.estimatedBytes(), index.retainedBytes());
    }

    /**
     * Probe (11-24, D-11-18-1): the cost of the sea-branch mouth lookup, written to build/rh10-gap/mouth-cost.txt (the baseline run on the
     * unchanged 11-18 code is kept as mouth-cost-0a8691cb.txt). Per owner key: warm heightmap.apply ns per column on the mouth set and on
     * the miss set (median of 5 passes after one warm-up), a cold first apply on the first miss column on a fresh context and the owner's
     * planOf right after, plus two 1024 x 1024 boxes at 4-block stride (median of 3 passes) and the owners that only have sea in reach
     * (11-20 preview raster and a 4096 box). Nothing is gated here: 11-24's verify compares the lines with the baseline.
     */
    @Tag("probe")
    @Test void mouthProfileCost() throws Exception {
        var lines = new ArrayList<String>();
        var keys = costKeys();
        for (var key : keys) {
            var shared = WorldgenFixture.shared(key.fixture().preset().call(), key.fixture().seed());
            var mouth = mouthColumns(shared, key);
            var miss = missColumns(shared, key);
            lines.add(String.format(Locale.ROOT, "warmBand %s set=mouth ns=%.1f", key.name(), medianNsPerColumn(shared, mouth, 5)));
            lines.add(String.format(Locale.ROOT, "warmBand %s set=miss ns=%.1f", key.name(), medianNsPerColumn(shared, miss, 5)));
            lines.add(String.format(Locale.ROOT, "columns %s mouth=%d miss=%d", key.name(), mouth.size(), miss.size()));
            lines.add(mouthBuildLine(shared, key));
            if (miss.isEmpty()) { lines.add("coldMiss " + key.name() + " ms=NaN planMs=NaN (no miss column)"); continue; }
            double applyMs, planMs;
            try (var fresh = WorldgenFixture.context(key.fixture().preset().call(), key.fixture().seed())) {
                long t = System.nanoTime();
                fresh.heightmap.apply(new Cell(), miss.get(0)[0], miss.get(0)[1], false);
                applyMs = (System.nanoTime() - t) / 1e6;
                t = System.nanoTime();
                ((RollingHighlandsContinent) fresh.heightmap.continent()).planOf(key.ownerX(), key.ownerZ());
                planMs = (System.nanoTime() - t) / 1e6;
            }
            lines.add(String.format(Locale.ROOT, "coldMiss %s ms=%.1f planMs=%.1f", key.name(), applyMs, planMs));
        }
        var server = WorldgenFixture.shared(HighlandPlanformProbe.serverPreset(), HighlandPlanformProbe.SERVER_SEED);
        lines.add(String.format(Locale.ROOT, "warmBox server-3088,515 ns=%.1f", medianNsPerColumn(server, box(3088, 515), 3)));
        var standard = WorldgenFixture.shared(defaultPreset(), 42);
        lines.add(String.format(Locale.ROOT, "warmBox default42-771,-5605 ns=%.1f", medianNsPerColumn(standard, box(771, -5605), 3)));
        var continent = (RollingHighlandsContinent) server.heightmap.continent();
        double coastEdge = server.preset.world().controlPoints.coast;
        lines.add(String.format(Locale.ROOT, "onlySeaOwners raster=%d box=%d", onlySeaOwners(continent, coastEdge, 256, 64), onlySeaOwners(continent, coastEdge, 64, 64)));
        lines.forEach(System.out::println);
        Files.createDirectories(Path.of("build/rh10-gap"));
        Files.write(Path.of("build/rh10-gap/mouth-cost.txt"), lines);
    }
}
