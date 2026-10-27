package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Bounded, pure terrain-aware drainage construction. Never calls final terrain or chunk caches. */
public final class HighlandDrainage {
    public static final int MAX_NODES = 65_536;
    static final double COASTAL_RISE = .8;
    /** Deepest saddle cut used to breach a non-lake fill; deeper fills keep the water up to fill minus this. */
    static final double BREACH_CUT = 36;
    private final HighlandCoast coast;
    private final HighlandFields fields;
    private final double spacing, coastEdge, inlandEdge;
    /** D8 neighbours; diagonals cost sqrt(2) in the flat-resolution distance and may not cross each other. */
    private static final int[] DX = {0, -1, 1, 0, -1, 1, -1, 1};
    private static final int[] DZ = {-1, 0, 0, 1, -1, 1, 1, -1};
    /** Subtle meander: peak lateral offset as a fraction of reach length on flat reaches. */
    static final double MEANDER = .12;
    /** Channel heads taper from a narrow stream to full width over this length (blocks). */
    static final double HEAD_TAPER = 160;
    /** Fraction of a grid step a channel node may move toward the local low point. */
    static final double SNAP = .3;
    /** Random lateral node offset as a fraction of a grid step; SNAP + JITTER < 0.5 keeps nodes in their cell. */
    static final double JITTER = .15;

    public HighlandDrainage(HighlandCoast coast, HighlandFields fields, double spacing, WorldSettings.ControlPoints controls) {
        this.coast = coast; this.fields = fields; this.spacing = spacing;
        this.coastEdge = controls.coast; this.inlandEdge = controls.coast + Math.max(controls.inland - controls.coast, COASTAL_RISE * Math.max(1, 16000.0 / coast.tectonicScale));
        if (!(inlandEdge > coastEdge)) throw new IllegalArgumentException("Rolling Highlands needs inland > coast");
    }
    /** Ease-out rise from the coastline: land leaves sea level immediately and flattens toward full relief. */
    public double landBlend(double edge) {
        double t = clamp((edge - coastEdge) / (inlandEdge - coastEdge), 0, 1);
        return t * (2 - t);
    }
    public double planningHeight(double x, double z, double edge) {
        return fields.sample(x, z).planningHeight() * landBlend(edge);
    }
    /** Refined channel sample. {@code head} ramps 0..1 over the first HEAD_TAPER blocks below a channel head. */
    public record Node(double x, double z, double ground, double water, double area, int parent, double head) {}
    public record Reach(int downstream, int first, int last) {}
    public record Plan(int ownerX, int ownerZ, int gridStep, int gridNodes, int landNodes, List<Node> nodes, List<Reach> reaches, HighlandBasins basins, long buildNanos) {}
    private record QueueNode(int index, double level, double distance) {}

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
        double[] routed = new double[n];
        // Equal levels (flats and filled depressions) resolve by geometric distance from the outlet, not by
        // array order, so flat drainage spreads radially instead of in parallel grid lines.
        PriorityQueue<QueueNode> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueNode::level)
            .thenComparingDouble(QueueNode::distance).thenComparingInt(QueueNode::index));
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
                    queue.add(new QueueNode(i, 0, 0));
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
                boolean diagonal = DX[d] != 0 && DZ[d] != 0;
                if (diagonal) {
                    int a = iz * width + nx, b = nz * width + ix;
                    if (parent[a] == b || parent[b] == a) continue;
                }
                seen[j] = true; parent[j] = i;
                filled[j] = Math.max(entry.level, ground[j]);
                routed[j] = entry.distance + (diagonal ? Math.sqrt(2) : 1);
                queue.add(new QueueNode(j, filled[j], routed[j]));
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
        int[] tributaries = new int[n], mainChild = new int[n];
        Arrays.fill(mainChild, -1);
        for (int i = 0; i < n; i++) {
            if (!channel[i] || parent[i] < 0) continue;
            int p = parent[i];
            tributaries[p]++;
            if (mainChild[p] < 0 || area[i] > area[mainChild[p]] || area[i] == area[mainChild[p]] && i < mainChild[p]) mainChild[p] = i;
        }
        HighlandBasins basins = new HighlandBasins(fields.seed() + 52361, originX, originZ, width, depth, step, ground, filled, land, channel);
        // Snap channel nodes toward the local low point of the planning surface so rivers follow real hollows
        // between grid nodes. Nodes stay inside their own cell, so the D8 no-crossing guarantee survives.
        double[] pathX = new double[n], pathZ = new double[n];
        for (int i = 0; i < n; i++) {
            pathX[i] = originX + i % width * step; pathZ[i] = originZ + i / width * step;
            if (!channel[i] || !land[i]) continue;
            double bestX = pathX[i], bestZ = pathZ[i], best = ground[i];
            for (int oz = -1; oz <= 1; oz++) for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oz == 0) continue;
                double sx = pathX[i] + ox * SNAP * step, sz = pathZ[i] + oz * SNAP * step;
                var site = coast.sample(sx, sz);
                if (site.edge() <= coastEdge) continue;
                double h = planningHeight(sx, sz, site.edge()) + .5 * Math.hypot(ox, oz);
                if (h < best) { best = h; bestX = sx; bestZ = sz; }
            }
            // Deterministic jitter breaks long collinear chains on uniform slopes (stays within the node's cell).
            long hash = mix(fields.seed() ^ (i * 0x9E3779B97F4A7C15L));
            pathX[i] = bestX + JITTER * step * (((hash & 0xFFFF) / 65535.0) * 2 - 1);
            pathZ[i] = bestZ + JITTER * step * ((((hash >>> 16) & 0xFFFF) / 65535.0) * 2 - 1);
        }
        List<Node> samples = new ArrayList<>();
        List<Reach> reaches = new ArrayList<>();
        List<Double> upper = new ArrayList<>(), lower = new ArrayList<>(), drops = new ArrayList<>(), targets = new ArrayList<>();
        List<Integer> lakeOf = new ArrayList<>();
        int[] endpoint = new int[n]; Arrays.fill(endpoint, -1);
        double[] curveX = new double[17], curveZ = new double[17];
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (!channel[i]) continue;
            double x = pathX[i], z = pathZ[i];
            int p = parent[i];
            if (p < 0) {
                endpoint[i] = samples.size();
                samples.add(new Node(x, z, 0, 0, area[i], -1, 1));
                lower.add(0.0); upper.add(0.0); drops.add(0.0); targets.add(0.0); lakeOf.add(-1);
                continue;
            }
            double px = pathX[p], pz = pathZ[p];
            int previous = endpoint[p];
            int downstream = previous, first = samples.size();
            // Hermite curve from the downstream node to this node, tangent-continuous along each main stem.
            int beyond = parent[p], upstream = mainChild[i];
            double qx = beyond >= 0 ? pathX[beyond] : 2 * px - x, qz = beyond >= 0 ? pathZ[beyond] : 2 * pz - z;
            double rx = upstream >= 0 ? pathX[upstream] : 2 * x - px, rz = upstream >= 0 ? pathZ[upstream] : 2 * z - pz;
            double chord = Math.max(1e-6, Math.hypot(x - px, z - pz));
            double before = Math.max(1e-6, Math.hypot(px - qx, pz - qz)), after = Math.max(1e-6, Math.hypot(rx - x, rz - z));
            double m1x = (x - qx) * chord / (before + chord), m1z = (z - qz) * chord / (before + chord);
            double m2x = (rx - px) * chord / (chord + after), m2z = (rz - pz) * chord / (chord + after);
            double coarseSlope = Math.max(Math.abs(ground[i] - ground[p]), Math.abs(filled[i] - filled[p])) / chord;
            double meander = MEANDER * chord * (1 - smooth(.02, .1, coarseSlope))
                * (2 * noise(fields.seed() + 6143, (x + px) * .5, (z + pz) * .5, 300) - 1);
            double nx = -(z - pz) / chord, nz = (x - px) / chord;
            double length = 0;
            for (int c = 0; c <= 16; c++) {
                double t = c / 16.0, t2 = t * t, t3 = t2 * t;
                double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
                double bulge = meander * Math.sin(Math.PI * t);
                curveX[c] = h00 * px + h10 * m1x + h01 * x + h11 * m2x + nx * bulge;
                curveZ[c] = h00 * pz + h10 * m1z + h01 * z + h11 * m2z + nz * bulge;
                if (c > 0) length += Math.hypot(curveX[c] - curveX[c - 1], curveZ[c] - curveZ[c - 1]);
            }
            int pieces = Math.max(1, (int) Math.ceil(length / 16.0));
            double slope = coarseSlope > .08 ? .35 : .12;
            boolean headReach = tributaries[i] == 0;
            for (int piece = 1; piece <= pieces; piece++) {
                if (samples.size() >= MAX_NODES) throw new IllegalStateException("RH profile refinement budget exceeded");
                double t = piece / (double) pieces, f = t * 16;
                int c = Math.min(15, (int) f);
                double sx = lerp(curveX[c], curveX[c + 1], f - c), sz = lerp(curveZ[c], curveZ[c + 1], f - c);
                var location = coast.sample(sx, sz);
                double h = planningHeight(sx, sz, location.edge());
                double lo = Math.max(0, h - 44);
                // Shallow priority fills are breached: the preferred surface follows the ground, and the lower
                // bound allows a bounded cut through the outlet saddle. Only real lakes hold a raised level.
                double fill = lerp(filled[p], filled[i], t);
                double desired = Math.max(0, h - 2);
                var lake = basins.sample(sx, sz);
                // Around lakes the water may stand at the lake's fill level; elsewhere fills are breached.
                double hi = Math.max(desired, fill - 2 - (lake != null ? 0 : BREACH_CUT));
                // A bounded local bank regrade accommodates sub-grid outlet shoulders and flat junction cores.
                // The preferred profile stays at ground minus two; this is not a raised regional surface.
                hi += 8;
                boolean flooded = lake != null && lake.depth() > 0;
                if (flooded) lo = hi = lake.water();
                lakeOf.add(flooded ? lake.lake() : -1);
                if (location.edge() <= coastEdge) lo = hi = 0;
                int next = samples.size();
                double head = headReach ? smooth(0, HEAD_TAPER, (1 - t) * length) : 1;
                samples.add(new Node(sx, sz, h, 0, lerp(area[p], area[i], t), previous, head));
                lower.add(lo); upper.add(hi); targets.add(clamp(desired, lo, hi));
                // Level wet cores around coarse junctions/bends connect branch cross-sections.
                drops.add(((piece <= 1 && tributaries[p] >= 2) || (piece == pieces && tributaries[i] >= 2)) ? 0.0 : slope * length / pieces);
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
        // Feasibility repair, deterministic and bounded:
        // 1. Rapids: where an upstream bound (e.g. a lake outlet) cannot descend within the slope limit to its
        //    downstream reach, that one edge may drop faster. P5 renders these as rapids/waterfalls.
        for (int j = refined - 1; j >= 0; j--) {
            int p = parents[j];
            if (p < 0) continue;
            if (low[j] - maxDrop[j] > high[p]) maxDrop[j] = low[j] - high[p];
            low[p] = Math.max(low[p], low[j] - maxDrop[j]);
        }
        // 2. Backwater: a reach upstream of a fixed level never sits below it; floods sub-grid hollows only.
        double[] reachable = new double[refined];
        for (int j = 0; j < refined; j++) {
            int p = parents[j];
            reachable[j] = p < 0 ? low[j] : Math.max(low[j], reachable[p] + minDrop[j]);
            if (high[j] < reachable[j]) high[j] = reachable[j];
            target[j] = clamp(target[j], low[j], high[j]);
        }
        double[] water;
        try { water = TreeProfileSolver.solve(parents, low, high, target, minDrop, maxDrop); }
        catch (TreeProfileSolver.Infeasible failure) {
            var point = samples.get(failure.node);
            throw new IllegalStateException("RH profile failed in owner " + ownerX + "," + ownerZ
                + " at " + point.x() + "," + point.z() + " (ground=" + point.ground() + ")", failure);
        }
        // Lake outlets: the first channel sample downstream of a flooded sample of that lake.
        for (int j = 0; j < refined; j++) {
            int lake = lakeOf.get(j), p = parents[j];
            if (lake >= 0 && p >= 0 && lakeOf.get(p) != lake) basins.setOutlet(lake, samples.get(p).x(), samples.get(p).z());
        }
        List<Node> nodes = new ArrayList<>(refined);
        for (int j = 0; j < refined; j++) {
            var point = samples.get(j);
            nodes.add(new Node(point.x(), point.z(), point.ground(), water[j], point.area(), point.parent(), point.head()));
        }
        return new Plan(ownerX, ownerZ, step, n, landCount, List.copyOf(nodes), List.copyOf(reaches), basins, System.nanoTime() - started);
    }
}
