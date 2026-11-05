package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/**
 * Ordinary lake candidates from the coarse drainage flood, resolved before publication against detailed terrain
 * and river constraints. Each retained body has one water level and connected block-resolution membership.
 * Provisional shore fields guide routing only; the renderer uses the resolved bodies and bounded bank sealing.
 */
public final class HighlandBasins {
    /** Lakes are decided by the drainage plan (depth, size, chance); any remaining fill deeper than this floods. */
    static final double MIN_LAKE_DEPTH = 1;
    static final int MIN_LAKE_NODES = 1;
    /** The surface stands this far below the spill so the rim always contains it. */
    static final double FREEBOARD = 1;
    /** Maximum residual sealing at a finalized dry bank; never applies to lake beds. */
    static final double BANK_SEAL = 2;
    /** Shore regions extend over dry nodes less than this far above the lake; also the depth assumed beyond. */
    static final double REGION_DEPTH = 96;
    /** Horizontal reach (blocks) of a lake's shore influence from its flooded nodes. */
    static final double SHORE_REACH = 384;
    private static final int[] CARDINAL = {1, 3, 4, 6};
    /** Nodes more than this above their lake's level stay as islands. */
    static final double ISLAND_MIN = 3;
    private static final int[] RX = {-1, 0, 1, -1, 1, -1, 0, 1}, RZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private final int originX, originZ, width, depth, step;
    private final long seed;
    private final double[] levels, depthField, reach, outletX, outletZ, nodeSurface;
    private final int[] basin;
    private final double[] nodeX, nodeZ;
    private final int[] surfaceNodes;
    private final List<Body> bodies;

    /**
     * Resolved body: signed depth in eighth-block units, clamped to the renderer's excavation range. Fine grids
     * retain wet row spans plus dry edge samples. Candidate provenance survives changes in retained-body order.
     */
    record Body(int candidate, int x, int z, int width, int height, int spacing, double water, byte[] bed, int[] row, int[] start, int[] offset) {
        double depth(double px, double pz) {
            double gx = (px - x) / spacing, gz = (pz - z) / spacing;
            int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
            if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= height - 1) return -1;
            double tx = gx - ix, tz = gz - iz;
            return ((1-tz)*((1-tx)*value(ix,iz)+tx*value(ix+1,iz))
                + tz*((1-tx)*value(ix,iz+1)+tx*value(ix+1,iz+1))) / 8;
        }
        private byte value(int x, int z) {
            if (row == null) return bed[z*width+x];
            for (int k = row[z]; k < row[z+1]; k++) {
                int at = x-start[k];
                if (at >= 0 && at < offset[k+1]-offset[k]) return bed[offset[k]+at];
            }
            return -127;
        }
        /** Distance to a wet row span, without scanning its interior or retaining another distance field. */
        double shoreDistance(double px, double pz, double limit) {
            if (row == null) return limit;
            int z0 = Math.max(0, (int) Math.ceil(pz-z-limit)), z1 = Math.min(height-1, (int) Math.floor(pz-z+limit));
            double best = limit*limit;
            for (int iz = z0; iz <= z1; iz++) {
                double dz = pz-z-iz;
                if (dz*dz >= best) continue;
                for (int k = row[iz]; k < row[iz+1]; k++) {
                    // Each packed run includes one dry sample at both ends.
                    double first = x+start[k]+1, last = x+start[k]+offset[k+1]-offset[k]-2;
                    double dx = px-clamp(px,first,last);
                    best = Math.min(best,dx*dx+dz*dz);
                }
            }
            return Math.sqrt(best);
        }
    }

    /** {@code proximity} is 1 near flooded nodes and falls smoothly to 0 at SHORE_REACH from them. */
    public record Sample(int lake, double water, double depth, double proximity) {}

    HighlandBasins(long seed, int originX, int originZ, int width, int depth, int step, double[] ground,
                   double[] filled, boolean[] land, boolean[] channel, double[] nodeX, double[] nodeZ) {
        this.seed = seed;
        this.nodeX = nodeX; this.nodeZ = nodeZ; this.bodies = null; this.surfaceNodes = null;
        this.originX = originX; this.originZ = originZ; this.width = width; this.depth = depth; this.step = step;
        int n = ground.length;
        this.basin = new int[n];
        Arrays.fill(basin, -1);
        List<Double> surfaces = new ArrayList<>();
        int[] queue = new int[n];
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (visited[i] || !land[i] || filled[i] <= ground[i] + .5) continue;
            int head = 0, tail = 1; queue[0] = i; visited[i] = true;
            boolean connectedRiver = false;
            double deepest = 0;
            while (head < tail) {
                int v = queue[head++];
                connectedRiver |= channel[v];
                deepest = Math.max(deepest, filled[v] - ground[v]);
                int x = v % width, z = v / width;
                for (int dir = 0; dir < 8; dir++) {
                    int nx = x + RX[dir], nz = z + RZ[dir];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                    int j = nz * width + nx;
                    if (!visited[j] && land[j] && filled[j] == filled[i] && filled[j] > ground[j] + .5) {
                        visited[j] = true; queue[tail++] = j;
                    }
                }
            }
            if (connectedRiver && deepest >= MIN_LAKE_DEPTH && tail >= MIN_LAKE_NODES) {
                int id = surfaces.size(); surfaces.add(Math.max(0, filled[i] - FREEBOARD));
                for (int q = 0; q < tail; q++) basin[queue[q]] = id;
            }
        }
        this.levels = surfaces.stream().mapToDouble(Double::doubleValue).toArray();
        this.nodeSurface = new double[n];
        for (int i = 0; i < n; i++) nodeSurface[i] = basin[i] >= 0 ? levels[basin[i]] : Double.NaN;
        this.outletX = new double[levels.length]; this.outletZ = new double[levels.length];
        Arrays.fill(outletX, Double.NaN); Arrays.fill(outletZ, Double.NaN);
        // Shore region: grow outward from each lake over dry rising ground (never into lower ground such as the
        // outlet valley) while within REGION_DEPTH of its level, so the shore blend sees real node depths and
        // reaches zero before the region ends.
        // Chamfer distance from flooded nodes bounds the region horizontally (Dijkstra over 8 neighbours).
        boolean[] flooded = new boolean[n];
        this.reach = new double[n];
        Arrays.fill(reach, SHORE_REACH);
        PriorityQueue<double[]> frontier = new PriorityQueue<>(Comparator.<double[]>comparingDouble(e -> e[0]).thenComparingDouble(e -> e[1]));
        for (int i = 0; i < n; i++) if (basin[i] >= 0) { flooded[i] = true; reach[i] = 0; frontier.add(new double[]{0, i}); }
        while (!frontier.isEmpty()) {
            double[] entry = frontier.remove();
            int v = (int) entry[1];
            if (entry[0] > reach[v]) continue;
            int x = v % width, z = v / width, id = basin[v];
            for (int dir = 0; dir < 8; dir++) {
                int nx = x + RX[dir], nz = z + RZ[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                int j = nz * width + nx;
                if (flooded[j] || !land[j] || basin[j] >= 0 && basin[j] != id) continue;
                double rise = ground[j] - levels[id], distance = entry[0] + step * Math.hypot(RX[dir], RZ[dir]);
                if (rise >= REGION_DEPTH || rise < 1 || distance >= reach[j] || distance >= SHORE_REACH) continue;
                basin[j] = id; reach[j] = distance;
                frontier.add(new double[]{distance, j});
            }
        }
        this.depthField = new double[n];
        for (int i = 0; i < n; i++) {
            depthField[i] = basin[i] < 0 ? -REGION_DEPTH : flooded[i] ? levels[basin[i]] - ground[i] : Math.min(-1, levels[basin[i]] - ground[i]);
        }
        // Fill shallow pinholes: a node barely above its lake's level but enclosed by flooded nodes would surface as
        // one of a lattice of identical islands at grid spacing. Real islands (more than ISLAND_MIN above) stay.
        for (int pass = 0; pass < 2; pass++) {
            double[] next = depthField.clone();
            for (int i = 0; i < n; i++) {
                if (basin[i] < 0 || depthField[i] > 0 || depthField[i] < -ISLAND_MIN) continue;
                int x = i % width, z = i / width, wet = 0;
                double sum = 0;
                for (int dir : CARDINAL) {
                    int nx = x + RX[dir], nz = z + RZ[dir];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                    int j = nz * width + nx;
                    if (basin[j] == basin[i] && depthField[j] > 0) { wet++; sum += depthField[j]; }
                }
                if (wet >= 3) next[i] = Math.max(.5, .5 * sum / wet);
            }
            System.arraycopy(next, 0, depthField, 0, n);
        }
        // Node values belong to jittered node positions; resample onto the lattice the sampler interpolates over.
        // Each lattice point keeps its own node's lake; nodes of other lakes count as outside (dry, out of reach).
        double[] depthOut = depthField.clone(), reachOut = reach.clone(), weights = new double[3];
        for (int iz = 1; iz < depth - 1; iz++) for (int ix = 1; ix < width - 1; ix++) {
            int i = iz * width + ix, id = basin[i];
            if (id < 0) continue;
            int[] t = HighlandDrainage.containing(ix, iz, nodeX, nodeZ, width, originX, originZ, step, weights);
            if (t == null) continue;
            double d = 0, r = 0;
            for (int k = 0; k < 3; k++) {
                boolean member = basin[t[k]] == id;
                d += weights[k] * (member ? depthField[t[k]] : -REGION_DEPTH);
                r += weights[k] * (member ? reach[t[k]] : SHORE_REACH);
            }
            depthOut[i] = d; reachOut[i] = r;
        }
        System.arraycopy(depthOut, 0, depthField, 0, n);
        System.arraycopy(reachOut, 0, reach, 0, n);
    }

    public int count() { return levels.length; }

    private HighlandBasins(HighlandBasins candidates, List<Body> bodies) {
        this.seed = candidates.seed;
        this.originX = candidates.originX; this.originZ = candidates.originZ;
        this.width = candidates.width; this.depth = candidates.depth; this.step = candidates.step;
        this.basin = null; this.depthField = null; this.reach = null; this.nodeX = null; this.nodeZ = null;
        this.bodies = List.copyOf(bodies);
        this.levels = bodies.stream().mapToDouble(Body::water).toArray();
        this.outletX = new double[bodies.size()]; this.outletZ = new double[bodies.size()];
        Arrays.fill(outletX, Double.NaN); Arrays.fill(outletZ, Double.NaN);
        double[] water = new double[candidates.nodeSurface.length];
        int[] occupied = new int[water.length]; int size = 0;
        for (int i = 0; i < water.length; i++) {
            Sample s = sample(candidates.nodeX[i], candidates.nodeZ[i]);
            if (s != null) { occupied[size] = i; water[size++] = s.water(); }
        }
        this.nodeSurface = Arrays.copyOf(water,size);
        this.surfaceNodes = Arrays.copyOf(occupied,size);
    }

    long resolvedBytes() {
        if (bodies == null) return 0;
        long bytes = nodeSurface.length * 8L + surfaceNodes.length * 4L;
        for (Body body : bodies) {
            bytes += 112L + body.bed.length;
            if (body.row != null) bytes += 48L + 4L*(body.row.length+body.start.length+body.offset.length);
        }
        return bytes;
    }

    /** Temporary terrain evidence shared by successive water-cap solves; never retained in a published plan. */
    record Window(int x, int z, int width, int height, int spacing, double[] ground, double[] spill, boolean[] seed) {}

    Window[] prepare(java.util.function.DoubleBinaryOperator terrain) {
        Window[] windows = new Window[levels.length];
        final int resolution = 4;
        java.util.stream.IntStream.range(0, levels.length).parallel().forEach(id -> {
            int minX = Integer.MAX_VALUE, minZ = minX, maxX = Integer.MIN_VALUE, maxZ = maxX;
            for (int i = 0; i < basin.length; i++) if (basin[i] == id) {
                int x = originX + i % width * step, z = originZ + i / width * step;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            minX -= step; minZ -= step; maxX += step; maxZ += step;
            int w = (maxX-minX)/resolution+1, h = (maxZ-minZ)/resolution+1;
            windows[id] = prepareWindow(id, minX, minZ, w, h, resolution, terrain, null);
        });
        return windows;
    }

    /** Refine each retained body's local domain at block resolution, retaining original candidate identity. */
    Window[] refine(HighlandBasins coarse, java.util.function.Function<Body, java.util.function.DoubleBinaryOperator> terrain) {
        Window[] windows = new Window[levels.length];
        coarse.bodies.parallelStream().forEach(body -> {
            int x = body.x-8, z = body.z-8;
            int w = (body.width-1)*body.spacing+17, h = (body.height-1)*body.spacing+17;
            // Restrict the local domain to the candidate plus an eight-block margin. Every interior and boundary
            // column is sampled exactly; distant exterior space is open drainage, not an artificial retaining wall.
            boolean[] domain = new boolean[body.width*body.height];
            for (int iz = 0; iz < body.height; iz++) for (int ix = 0; ix < body.width; ix++) {
                if (body.value(ix,iz) <= 0) continue;
                for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                    int nx = ix+dx, nz = iz+dz;
                    if (nx >= 0 && nz >= 0 && nx < body.width && nz < body.height) domain[nz*body.width+nx] = true;
                }
            }
            var exact = terrain.apply(body);
            windows[body.candidate] = prepareWindow(body.candidate, x, z, w, h, 1, (px,pz) -> {
                int ix = (int) Math.floor((px-body.x)/body.spacing), iz = (int) Math.floor((pz-body.z)/body.spacing);
                int x0 = Math.max(0,Math.min(body.width-1,ix)), x1 = Math.max(0,Math.min(body.width-1,ix+1));
                int z0 = Math.max(0,Math.min(body.height-1,iz)), z1 = Math.max(0,Math.min(body.height-1,iz+1));
                if (domain[z0*body.width+x0] || domain[z0*body.width+x1]
                    || domain[z1*body.width+x0] || domain[z1*body.width+x1]) return exact.applyAsDouble(px,pz);
                return Double.NEGATIVE_INFINITY;
            }, body);
        });
        return windows;
    }

    private Window prepareWindow(int id, int minX, int minZ, int w, int h, int resolution,
                                 java.util.function.DoubleBinaryOperator terrain, Body seedBody) {
        if ((long) w*h > 4_000_000) throw new IllegalStateException("Lake terrain window exceeds resolution budget");
        double[] ground = new double[w*h], spill = new double[w*h];
        boolean[] seen = new boolean[w*h], seed = new boolean[w*h];
        // note: same boundary-inward priority flood as HighlandRangeRelief.fillWindow, with a local domain.
        var queue = new PriorityQueue<double[]>(Comparator.<double[]>comparingDouble(e -> e[0]).thenComparingDouble(e -> e[1]));
        java.util.stream.IntStream.range(0,h).parallel().forEach(z -> {
            for (int x = 0; x < w; x++) {
                int i = z*w+x, px = minX+x*resolution, pz = minZ+z*resolution;
                ground[i] = terrain.applyAsDouble(px, pz);
                if (!Double.isFinite(ground[i])) { ground[i] = spill[i] = Double.NEGATIVE_INFINITY; seen[i] = true; }
                var s = seedBody == null ? sample(px, pz) : null;
                seed[i] = seedBody == null ? s != null && s.lake() == id && s.depth() > 0 : seedBody.depth(px, pz) > 0;
            }
        });
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            int i = z*w+x;
            if (seen[i]) continue;
            boolean escape = x == 0 || z == 0 || x == w-1 || z == h-1;
            for (int dir : CARDINAL) {
                int nx = x+RX[dir], nz = z+RZ[dir];
                if (nx >= 0 && nz >= 0 && nx < w && nz < h && !Double.isFinite(ground[nz*w+nx])) escape = true;
            }
            if (escape) {
                seen[i] = true; spill[i] = ground[i]; queue.add(new double[]{spill[i], i});
            }
        }
        while (!queue.isEmpty()) {
            var entry = queue.remove(); int i = (int) entry[1], x = i%w, z = i/w;
            for (int dir : CARDINAL) {
                int nx = x+RX[dir], nz = z+RZ[dir];
                if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                int j = nz*w+nx;
                if (seen[j]) continue;
                seen[j] = true; spill[j] = Math.max(ground[j], entry[0]); queue.add(new double[]{spill[j], j});
            }
        }
        return new Window(minX, minZ, w, h, resolution, ground, spill, seed);
    }

    /** Build-time only: candidates stay fixed; no rerouting or replacement dam discovery. */
    HighlandBasins resolve(Window[] windows, double[] caps) {
        var resolved = new ArrayList<Body>();
        for (int id = 0; id < windows.length; id++) {
            if (!Double.isFinite(caps[id])) continue;
            var window = windows[id];
            int minX = window.x, minZ = window.z, w = window.width, h = window.height, resolution = window.spacing;
            double[] ground = window.ground, spill = window.spill;
            boolean[] seed = window.seed, seen = new boolean[ground.length];
            double cap = Math.min(levels[id], caps[id]);
            int[] visit = new int[w*h], best = new int[0]; double bestWater = 0;
            for (int start = 0; start < ground.length; start++) {
                double water = Math.min(cap, spill[start]-FREEBOARD);
                if (seen[start] || water <= ground[start] || water <= 0) continue;
                int head = 0, tail = 1; visit[0] = start; seen[start] = true;
                boolean overlap = false; double deepest = 0;
                while (head < tail) {
                    int i = visit[head++], x = i%w, z = i/w;
                    overlap |= seed[i]; deepest = Math.max(deepest, water-ground[i]);
                    for (int dir : CARDINAL) {
                        int nx = x+RX[dir], nz = z+RZ[dir];
                        if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                        int j = nz*w+nx;
                        if (!seen[j] && ground[j] < water && Math.min(cap, spill[j]-FREEBOARD) == water) {
                            seen[j] = true; visit[tail++] = j;
                        }
                    }
                }
                if (overlap && deepest >= HighlandDrainage.LAKE_MIN_DEPTH && tail > best.length) {
                    best = Arrays.copyOf(visit, tail); bestWater = water;
                }
            }
            if (best.length == 0) { caps[id] = Double.NEGATIVE_INFINITY; continue; }
            caps[id] = bestWater;
            int bx0 = w, bz0 = h, bx1 = 0, bz1 = 0;
            for (int i : best) { bx0 = Math.min(bx0, i%w); bx1 = Math.max(bx1, i%w); bz0 = Math.min(bz0, i/w); bz1 = Math.max(bz1, i/w); }
            bx0--; bz0--; bx1++; bz1++;
            int bw = bx1-bx0+1, bh = bz1-bz0+1;
            byte[] bed = new byte[bw*bh];
            for (int z = 0; z < bh; z++) for (int x = 0; x < bw; x++)
                bed[z*bw+x] = (byte) Math.max(-127, Math.min(-1, Math.round(8*(bestWater-ground[(z+bz0)*w+x+bx0]))));
            for (int i : best) bed[(i/w-bz0)*bw+i%w-bx0] = (byte) Math.min(127, Math.max(1, Math.round(8*(bestWater-ground[i]))));
            int[] row = null, start = null, offset = null;
            if (resolution == 1) {
                // Keep wet row spans with one dry sample on either side. Interior empty rectangles are discarded;
                // integer-block membership and wet depths remain exact, including holes and disconnected row spans.
                int runs = 0, pixels = 0;
                for (int z = 0; z < bh; z++) for (int x = 0; x < bw;) {
                    if (bed[z*bw+x] <= 0) { x++; continue; }
                    int first = x;
                    while (x < bw && bed[z*bw+x] > 0) x++;
                    runs++; pixels += Math.min(bw,x+1)-Math.max(0,first-1);
                }
                byte[] packed = new byte[pixels]; row = new int[bh+1]; start = new int[runs]; offset = new int[runs+1];
                int run = 0, at = 0;
                for (int z = 0; z < bh; z++) {
                    row[z] = run;
                    for (int x = 0; x < bw;) {
                        if (bed[z*bw+x] <= 0) { x++; continue; }
                        int first = Math.max(0,x-1);
                        while (x < bw && bed[z*bw+x] > 0) x++;
                        int length = Math.min(bw,x+1)-first;
                        start[run] = first; offset[run++] = at;
                        System.arraycopy(bed,z*bw+first,packed,at,length); at += length;
                    }
                }
                row[bh] = run; offset[run] = at; bed = packed;
            }
            resolved.add(new Body(id, minX+bx0*resolution, minZ+bz0*resolution, bw, bh, resolution, bestWater, bed, row, start, offset));
        }
        return new HighlandBasins(this, resolved);
    }

    double[] levelCaps() { return levels.clone(); }

    /** World-block bounds of a finalized body, including its outer dry samples (diagnostics and refinement). */
    double[] lakeBounds(int id) {
        Body b = bodies.get(id);
        return new double[]{b.x, b.z, b.x + (b.width-1)*b.spacing, b.z + (b.height-1)*b.spacing};
    }

    /** Lower a whole candidate to the feasible ceiling of every contacting river sample. */
    boolean constrain(int[] contacts, double[] ceilings, double[] caps) {
        boolean changed = false;
        for (int i = 0; i < contacts.length; i++) {
            if (contacts[i] < 0) continue;
            int candidate = bodies.get(contacts[i]).candidate;
            if (ceilings[i] < caps[candidate] - 1e-9) {
                caps[candidate] = ceilings[i]; changed = true;
            }
        }
        return changed;
    }

    /** Final block-height check after profile changes. Wet neighbours are intentional water connections. */
    boolean constrainBanks(java.util.function.DoubleBinaryOperator bank, double datum, double[] caps) {
        boolean changed = false;
        for (Body body : bodies) {
            int surface = (int) Math.floor(datum+body.water);
            for (int z = 1; z < body.height-1; z++) for (int x = 1; x < body.width-1; x++) {
                if (body.value(x,z) <= 0) continue;
                for (int dir : CARDINAL) {
                    int nx = x+RX[dir], nz = z+RZ[dir];
                    if (body.value(nx,nz) > 0) continue;
                    int px = body.x+nx*body.spacing, pz = body.z+nz*body.spacing;
                    if (sample(px,pz) != null) continue;
                    double ground = bank.applyAsDouble(px,pz);
                    if (ground+BANK_SEAL < surface && ground-datum+BANK_SEAL < caps[body.candidate]) {
                        etcodehome.freeterraforged.FTFCommon.LOGGER.debug("Lake {} bank {},{} lowers cap {} to {}", body.candidate, px, pz, caps[body.candidate], ground-datum+BANK_SEAL);
                        caps[body.candidate] = ground-datum+BANK_SEAL; changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** Only immediate dry neighbours may receive the small sealing budget after river profiles change. */
    double sealBank(double x, double z, double height) {
        return Math.max(height,Math.min(height+BANK_SEAL,adjacentWater(x,z)));
    }

    /** Restore a gently excavated shore without the old unbounded fill or an independently advanced water mask. */
    double shapeBank(double x, double z, double height) {
        if (bodies == null) return height;
        double shaped = height;
        final double reach = 32;
        for (Body body : bodies) {
            if (height <= body.water+1 || x < body.x-reach || z < body.z-reach
                || x > body.x+body.width-1+reach || z > body.z+body.height-1+reach) continue;
            double distance = body.shoreDistance(x,z,reach);
            double shore = Math.min(height,body.water+1+2*distance);
            shaped = Math.min(shaped,lerp(shore,height,smooth(4,reach,distance)));
        }
        // A lower nearby shore must not excavate the immediate retaining bank of a higher body.
        return Math.max(shaped,Math.min(height+BANK_SEAL,adjacentWater(x,z)));
    }

    /** Water beside an immediate dry bank, or negative infinity away from a finalized body. */
    double adjacentWater(double x, double z) {
        if (bodies == null) return Double.NEGATIVE_INFINITY;
        double water = Double.NEGATIVE_INFINITY;
        for (Body body : bodies) {
            if (x < body.x-1 || z < body.z-1 || x > body.x+(body.width-1)*body.spacing+1
                || z > body.z+(body.height-1)*body.spacing+1) continue;
            for (int dir : CARDINAL) if (body.depth(x+RX[dir],z+RZ[dir]) > 0) {
                water = Math.max(water,body.water); break;
            }
        }
        return water;
    }


    /** River contact includes the wet channel width and interpolation to its adjacent profile samples. */
    Sample channelContact(double x, double z, double radius) {
        Sample direct = sample(x,z);
        if (direct != null || bodies == null) return direct;
        int closest = -1; double distance = radius*radius;
        for (int id = 0; id < bodies.size(); id++) {
            Body body = bodies.get(id);
            int x0 = Math.max(0, (int) Math.floor((x-radius-body.x)/body.spacing));
            int z0 = Math.max(0, (int) Math.floor((z-radius-body.z)/body.spacing));
            int x1 = Math.min(body.width-1, (int) Math.ceil((x+radius-body.x)/body.spacing));
            int z1 = Math.min(body.height-1, (int) Math.ceil((z+radius-body.z)/body.spacing));
            for (int iz = z0; iz <= z1; iz++) for (int ix = x0; ix <= x1; ix++) {
                if (body.value(ix,iz) <= 0) continue;
                double dx = body.x+ix*body.spacing-x, dz = body.z+iz*body.spacing-z, d = dx*dx+dz*dz;
                if (d < distance) { distance = d; closest = id; }
            }
        }
        return closest < 0 ? null : new Sample(closest, bodies.get(closest).water, 0, 1);
    }

    /** Lake surface at plan node i if the node is flooded, else NaN (RH6 L2). */
    double nodeSurface(int i) {
        if (surfaceNodes == null) return nodeSurface[i];
        int at = Arrays.binarySearch(surfaceNodes,i);
        return at < 0 ? Double.NaN : nodeSurface[at];
    }

    /** Records where the channel leaves a lake; set once while the owning plan is built, before publication. */
    void setOutlet(int lake, double x, double z) {
        if (Double.isNaN(outletX[lake])) { outletX[lake] = x; outletZ[lake] = z; }
    }
    /** Outlet position of a lake, or NaN when no channel leaves it inside this plan. */
    public double outletX(int lake) { return outletX[lake]; }
    public double outletZ(int lake) { return outletZ[lake]; }

    /**
     * Resolved membership and excavation depth, or null outside the water. During provisional routing only,
     * returns the coarse signed-depth shore field described below.
     * <p>
     * RH5 S: corner weights are smoothstep-bilinear (C1 across grid lines, so shore shaping leaves no creases along
     * them), and where corners belong to different lakes each lake's field is evaluated on its own and the highest
     * (wettest) one wins: the result is continuous where ownership changes instead of switching at grid mid-lines.
     */
    public Sample sample(double x, double z) {
        if (bodies != null) {
            for (int id = 0; id < bodies.size(); id++) {
                var body = bodies.get(id); double d = body.depth(x, z);
                if (d > 0) return new Sample(id, body.water(), d + 2*smooth(0, 2, d), 1);
            }
            return null;
        }
        double gx = (x - originX) / step, gz = (z - originZ) / step;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= depth - 1) return null;
        double tx = gx - ix, tz = gz - iz;
        tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        int c0 = iz * width + ix, c1 = c0 + 1, c2 = c0 + width, c3 = c2 + 1;
        int b0 = basin[c0], b1 = basin[c1], b2 = basin[c2], b3 = basin[c3];
        if (b0 < 0 && b1 < 0 && b2 < 0 && b3 < 0) return null;
        double w0 = (1 - tx) * (1 - tz), w1 = tx * (1 - tz), w2 = (1 - tx) * tz, w3 = tx * tz;
        int id = -1;
        double d = Double.NEGATIVE_INFINITY, distance = SHORE_REACH;
        for (int k = 0; k < 4; k++) {
            int candidate = k == 0 ? b0 : k == 1 ? b1 : k == 2 ? b2 : b3;
            if (candidate < 0 || candidate == id) continue;
            double cd = w0 * (b0 == candidate ? depthField[c0] : -REGION_DEPTH) + w1 * (b1 == candidate ? depthField[c1] : -REGION_DEPTH)
                + w2 * (b2 == candidate ? depthField[c2] : -REGION_DEPTH) + w3 * (b3 == candidate ? depthField[c3] : -REGION_DEPTH);
            if (cd > d || cd == d && candidate < id) {
                d = cd; id = candidate;
                distance = w0 * (b0 == candidate ? reach[c0] : SHORE_REACH) + w1 * (b1 == candidate ? reach[c1] : SHORE_REACH)
                    + w2 * (b2 == candidate ? reach[c2] : SHORE_REACH) + w3 * (b3 == candidate ? reach[c3] : SHORE_REACH);
            }
        }
        // Irregular shoreline: perturbs only the shallow margin, never the deep interior.
        double shoreline = 1 - smooth(0, 10, Math.abs(d));
        d += shoreline * (2 * (2 * noise(seed, x, z, 160) - 1) + (2 * noise(seed + 1, x, z, 56) - 1));
        return new Sample(id, levels[id], d, 1 - smooth(SHORE_REACH / 3, SHORE_REACH, distance));
    }
}
