package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Bounded, pure terrain-aware drainage construction. Never calls final terrain or chunk caches. */
public final class HighlandDrainage {
    public static final int MAX_NODES = 65_536;
    private final HighlandCoast coast;
    private final HighlandFields fields;
    private final double spacing, coastEdge, inlandEdge;
    private static final int[] DX = {0, -1, 1, 0, -1, 1};
    private static final int[] DZ = {-1, 0, 0, 1, -1, 1};

    public HighlandDrainage(HighlandCoast coast, HighlandFields fields, double spacing, WorldSettings.ControlPoints controls) {
        this.coast = coast; this.fields = fields; this.spacing = spacing;
        this.coastEdge = controls.coast; this.inlandEdge = Math.max(controls.inland, controls.coast + .8 * Math.max(1, 16000.0 / coast.tectonicScale));
        if (!(inlandEdge > coastEdge)) throw new IllegalArgumentException("Rolling Highlands needs inland > coast");
    }
    public double landBlend(double edge) { return smooth(coastEdge, inlandEdge, edge); }
    public double planningHeight(double x, double z, double edge) {
        return fields.sample(x, z).planningHeight() * landBlend(edge);
    }
    public record Node(double x, double z, double ground, double water, double area, int parent) {}
    public record Reach(int downstream, int first, int last) {}
    public record Plan(int ownerX, int ownerZ, int gridStep, int gridNodes, int landNodes, List<Node> nodes, List<Reach> reaches, HighlandBasins basins, long buildNanos) {}
    private record QueueNode(int index, double level) {}

    public Plan build(int ownerX, int ownerZ) {
        long started = System.nanoTime();
        var bounds = coast.bounds(ownerX, ownerZ);
        double minX = bounds.minX(), minZ = bounds.minZ(), maxX = bounds.maxX(), maxZ = bounds.maxZ();
        int step = 64, width, depth, originX, originZ;
        while (true) {
            originX = (int) Math.floor(minX / step) * step;
            originZ = (int) Math.floor(minZ / step) * step;
            width = (int) Math.ceil((maxX - originX) / step) + 1;
            depth = (int) Math.ceil((maxZ - originZ) / step) + 1;
            if ((long) width * depth <= MAX_NODES) break;
            step *= 2;
        }
        if (step > spacing / 8) throw new IllegalArgumentException("Rolling Highlands continent scale needs upland spacing >= " + step * 8);
        int n = width * depth, landCount = 0, count = 0;
        double[] ground = new double[n], filled = new double[n], area = new double[n];
        int[] parent = new int[n], order = new int[n];
        boolean[] owned = new boolean[n], land = new boolean[n], seen = new boolean[n];
        Arrays.fill(parent, -1);
        PriorityQueue<QueueNode> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueNode::level).thenComparingInt(QueueNode::index));
        for (int iz = 0; iz < depth; iz++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("RH domain interrupted");
            for (int ix = 0; ix < width; ix++) {
                int i = iz * width + ix;
                double x = originX + ix * step, z = originZ + iz * step;
                var s = coast.sample(x, z);
                owned[i] = s.ownerX() == ownerX && s.ownerZ() == ownerZ;
                if (!owned[i]) continue;
                land[i] = s.edge() > coastEdge;
                if (land[i]) {
                    landCount++;
                    ground[i] = planningHeight(x, z, s.edge());
                    area[i] = (double) step * step;
                    if (ix == 0 || iz == 0 || ix == width - 1 || iz == depth - 1) throw new IllegalStateException("RH bound contains land at box edge");
                } else {
                    seen[i] = true;
                    queue.add(new QueueNode(i, 0));
                }
            }
        }
        while (!queue.isEmpty()) {
            var entry = queue.remove();
            int i = entry.index, ix = i % width, iz = i / width;
            order[count++] = i;
            for (int d = 0; d < DX.length; d++) {
                int nx = ix + DX[d], nz = iz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (!owned[j] || seen[j]) continue;
                seen[j] = true; parent[j] = i;
                filled[j] = Math.max(entry.level, ground[j]);
                queue.add(new QueueNode(j, filled[j]));
            }
        }
        for (int i = 0; i < n; i++) {
            if (!land[i]) continue;
            if (!seen[i]) throw new IllegalStateException("RH land component has no verified ocean outlet at " + ownerX + "," + ownerZ);
            int ix = i % width, iz = i / width;
            for (int d = 0; d < DX.length; d++) {
                int nx = ix + DX[d], nz = iz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (!owned[j]) {
                    var adjacent = coast.sample(originX + nx * step, originZ + nz * step);
                    if (adjacent.edge() > coastEdge) throw new IllegalStateException("RH land bridge requires shared ports at " + ownerX + "," + ownerZ);
                }
            }
        }
        boolean[] channel = new boolean[n];
        double threshold = Math.max(3.0 * step * step, .03 * spacing * spacing);
        for (int k = count - 1; k >= 0; k--) {
            int i = order[k];
            channel[i] |= area[i] >= threshold;
            if (parent[i] >= 0) {
                area[parent[i]] += area[i];
                channel[parent[i]] |= channel[i];
            }
        }
        int[] tributaries = new int[n];
        for (int i = 0; i < n; i++) if (channel[i] && parent[i] >= 0) tributaries[parent[i]]++;
        double[] pathX = new double[n], pathZ = new double[n];
        int[] singleChild = new int[n];
        for (int i = 0; i < n; i++) {
            pathX[i] = originX + i % width * step; pathZ[i] = originZ + i / width * step;
            if (channel[i] && parent[i] >= 0) singleChild[parent[i]] = i;
        }
        for (int pass = 0; pass < 2; pass++) {
            double[] nextX = pathX.clone(), nextZ = pathZ.clone();
            for (int i = 0; i < n; i++) {
                if (!channel[i] || parent[i] < 0 || tributaries[i] != 1 || filled[i] > ground[i] + 2) continue;
                int child = singleChild[i], p = parent[i];
                double rawX = originX + i % width * step, rawZ = originZ + i / width * step;
                nextX[i] = clamp(lerp(pathX[i], (pathX[p] + pathX[child]) * .5, .5), rawX - step * .25, rawX + step * .25);
                nextZ[i] = clamp(lerp(pathZ[i], (pathZ[p] + pathZ[child]) * .5, .5), rawZ - step * .25, rawZ + step * .25);
            }
            pathX = nextX; pathZ = nextZ;
        }
        HighlandBasins basins = new HighlandBasins(originX, originZ, width, depth, step, ground, filled, land, channel);
        List<Node> samples = new ArrayList<>();
        List<Reach> reaches = new ArrayList<>();
        List<Double> upper = new ArrayList<>(), lower = new ArrayList<>(), drops = new ArrayList<>(), targets = new ArrayList<>();
        int[] endpoint = new int[n]; Arrays.fill(endpoint, -1);
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (!channel[i]) continue;
            double x = pathX[i], z = pathZ[i];
            int p = parent[i];
            if (p < 0) {
                endpoint[i] = samples.size();
                samples.add(new Node(x, z, 0, 0, area[i], -1));
                lower.add(0.0); upper.add(0.0); drops.add(0.0); targets.add(0.0);
                continue;
            }
            double px = pathX[p], pz = pathZ[p];
            int previous = endpoint[p];
            int downstream = previous, first = samples.size();
            double reachLength = Math.hypot(x - px, z - pz);
            int pieces = (int) Math.ceil(reachLength / 16.0);
            double slope = Math.max(Math.abs(ground[i] - ground[p]), Math.abs(filled[i] - filled[p])) / reachLength > .08 ? .35 : .12;
            for (int piece = 1; piece <= pieces; piece++) {
                if (samples.size() >= MAX_NODES) throw new IllegalStateException("RH profile refinement budget exceeded");
                double t = piece / (double) pieces;
                double sx = lerp(px, x, t), sz = lerp(pz, z, t);
                var location = coast.sample(sx, sz);
                double h = planningHeight(sx, sz, location.edge());
                double lo = Math.max(0, h - 44);
                double hi = Math.max(0, Math.max(h, lerp(filled[p], filled[i], t)) - 2);
                double desired = hi;
                // A bounded local bank regrade accommodates sub-grid outlet shoulders and flat junction cores.
                // The preferred profile stays at ground minus two; this is not a raised regional surface.
                hi += 8;
                var lake = basins.sample(sx, sz);
                if (lake != null && lake.depth() > 0) lo = hi = lake.water();
                if (location.edge() <= coastEdge) lo = hi = 0;
                int next = samples.size();
                samples.add(new Node(sx, sz, h, 0, lerp(area[p], area[i], t), previous));
                lower.add(lo); upper.add(hi); targets.add(clamp(desired, lo, hi));
                // Level wet cores around coarse junctions/bends connect branch cross-sections.
                drops.add(((piece <= 1 && tributaries[p] >= 2) || (piece == pieces && tributaries[i] >= 2)) ? 0.0 : slope * reachLength / pieces);
                previous = next;
            }
            endpoint[i] = previous;
            reaches.add(new Reach(downstream, first, previous));
        }
        int refined = samples.size();
        int[] parents = new int[refined];
        double[] low = new double[refined], high = new double[refined], target = new double[refined], minDrop = new double[refined], maxDrop = new double[refined];
        for (int j = 0; j < refined; j++) {
            parents[j] = samples.get(j).parent(); low[j] = lower.get(j); high[j] = upper.get(j);
            target[j] = targets.get(j); maxDrop[j] = drops.get(j);
        }
        double[] water;
        try { water = TreeProfileSolver.solve(parents, low, high, target, minDrop, maxDrop); }
        catch (TreeProfileSolver.Infeasible failure) {
            var point = samples.get(failure.node);
            throw new IllegalStateException("RH profile failed in owner " + ownerX + "," + ownerZ
                + " at " + point.x() + "," + point.z() + " (ground=" + point.ground() + ")", failure);
        }
        List<Node> nodes = new ArrayList<>(refined);
        for (int j = 0; j < refined; j++) {
            var point = samples.get(j);
            nodes.add(new Node(point.x(), point.z(), point.ground(), water[j], point.area(), point.parent()));
        }
        return new Plan(ownerX, ownerZ, step, n, landCount, List.copyOf(nodes), List.copyOf(reaches), basins, System.nanoTime() - started);
    }
}
