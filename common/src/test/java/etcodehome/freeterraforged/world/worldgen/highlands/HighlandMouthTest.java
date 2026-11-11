package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
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
}
